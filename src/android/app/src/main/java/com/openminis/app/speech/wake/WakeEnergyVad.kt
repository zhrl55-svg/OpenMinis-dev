package com.openminis.app.speech.wake

import com.openminis.app.logging.AppLogger
import kotlin.math.sqrt

/**
 * Energy VAD: adaptive noise floor + two thresholds, cutting continuous
 * audio into utterances.
 *
 * Ported from pilot's `EnergyVad`. Energy-based rather than silero VAD on
 * purpose: silero needs onnxruntime plus a 2MB model, and on a phone the
 * noise floor is steady while speech sits clearly above it - energy is
 * enough, with zero extra dependencies and zero extra latency.
 *
 * Two different kinds of "silence" must not be confused:
 *  - [minSilenceMs] (default 800 ms) decides "this sentence is over" and
    triggers transcription;
 *  - session-level sleep is a different thing and lives in
    [WakeWordService].
 */
class WakeEnergyVad(
    private val onSpeechStart: () -> Unit,
    private val onSegment: (ShortArray) -> Unit,
    private val minSilenceMs: () -> Int = { DEFAULT_SILENCE_MS },
    /**
     * Longest utterance before it is cut even without a pause.
     *
     * This is a safety valve, NOT the endpointing rule: a request ends when
     * the speaker goes quiet for [minSilenceMs], no matter how long it runs.
     * The old 15 s default was short enough to chop a long dictation in half
     * ("长语音只进去前半段") even though the user never paused. The barge-in
     * listener still overrides this with a much shorter limit: while the
     * assistant talks there is no punctuation guarantee in the captured
     * audio, and a stop command must be recognised within a second or two.
     */
    private val maxSegmentMs: () -> Int = { MAX_SEGMENT_MS },
    /**
     * Diagnostics sink.
     *
     * The VAD decisions (opened / cut / dropped) have to be visible outside
     * logcat — on a real device the logcat buffer rotates away exactly when
     * one needs to know why a sentence was not picked up.
     */
    private val onEvent: ((String) -> Unit)? = null,
) {

    private var noiseFloor = 120.0
    private var voicedFrames = 0
    private var silentFrames = 0
    private val preRoll = ArrayDeque<ShortArray>()
    private val segment = ArrayList<Short>()
    private var inSpeech = false

    /** Loudest frame since the last [reset] — diagnostics only. */
    @get:Synchronized
    var peakRms: Double = 0.0
        private set

    /** The energy threshold currently in use — diagnostics only. */
    @get:Synchronized
    val threshold: Double get() = maxOf(noiseFloor * 2.6, MIN_RMS)

    /** The adaptive noise floor — diagnostics only. */
    @get:Synchronized
    val floor: Double get() = noiseFloor

    /** RMS of the most recent frame — diagnostics only. */
    @get:Synchronized
    val lastRms: Double get() = lastRmsValue

    /** How many frames have been fed since the last [reset]. */
    @get:Synchronized
    val frameCount: Int get() = framesFed

    /** True while an utterance is being accumulated. */
    @get:Synchronized
    val isInSpeech: Boolean get() = inSpeech

    private var lastRmsValue = 0.0
    private var framesFed = 0

    @Synchronized
    fun reset() {
        voicedFrames = 0
        silentFrames = 0
        inSpeech = false
        segment.clear()
        preRoll.clear()
        peakRms = 0.0
        lastRmsValue = 0.0
        framesFed = 0
    }

    @Synchronized
    fun feed(frame: ShortArray, len: Int) {
        val rms = rms(frame, len)
        lastRmsValue = rms
        framesFed++
        if (rms > peakRms) peakRms = rms

        // While nobody is talking the noise floor tracks slowly: it rises
        // slowly and falls fast, so a single cough cannot drag it away.
        if (!inSpeech) {
            noiseFloor = if (rms < noiseFloor) {
                noiseFloor * 0.90 + rms * 0.10
            } else {
                noiseFloor * 0.995 + rms * 0.005
            }.coerceIn(40.0, 3000.0)
        }

        val threshold = maxOf(noiseFloor * 2.6, MIN_RMS)

        if (!inSpeech) {
            preRoll.addLast(frame.copyOf(len))
            while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()

            if (rms > threshold) {
                voicedFrames++
                if (voicedFrames >= 3) {
                    inSpeech = true
                    silentFrames = 0
                    segment.clear()
                    // Keep the 300 ms before the first sound, so the first
                    // syllable is not clipped.
                    preRoll.forEach { segment.addAll(it.toList()) }
                    preRoll.clear()
                    onEvent?.invoke(
                        "VAD 开口 rms=${"%.0f".format(rms)} 阈值=${"%.0f".format(threshold)} " +
                            "底噪=${"%.0f".format(noiseFloor)}",
                    )
                    onSpeechStart()
                }
            } else {
                voicedFrames = 0
            }
            return
        }

        segment.addAll(frame.take(len))

        if (rms > threshold) {
            silentFrames = 0
        } else {
            silentFrames++
        }

        val silentMs = silentFrames * WakeAudioCapture.FRAME_MS
        val totalMs = segment.size * 1000 / WakeAudioCapture.SAMPLE_RATE
        if (silentMs < minSilenceMs() && totalMs < maxSegmentMs()) return

        inSpeech = false
        voicedFrames = 0
        silentFrames = 0
        val pcm = segment.toShortArray()
        segment.clear()

        // Too short is noise (a door, clothes rustling) - drop it.
        val speechMs = pcm.size * 1000 / WakeAudioCapture.SAMPLE_RATE
        if (speechMs >= MIN_SPEECH_MS) {
            AppLogger.info(TAG, "VAD 切出一句 ${speechMs}ms")
            onEvent?.invoke("VAD 切句 ${speechMs}ms 峰值=${"%.0f".format(peakRms)}")
            onSegment(pcm)
        } else {
            AppLogger.info(TAG, "VAD 丢弃过短片段 ${speechMs}ms")
            onEvent?.invoke("VAD 丢弃过短片段 ${speechMs}ms 峰值=${"%.0f".format(peakRms)}")
        }
    }

    /**
     * Force the current utterance out (used when the idle window closes).
     *
     * @return true when there was an unfinished utterance to hand over.
     *   The listening window has a hard deadline, and a user who speaks
     *   without an 800 ms pause would otherwise have their sentence thrown
     *   away with the segment — the window ends mid-utterance.
     */
    @Synchronized
    fun flush(): Boolean {
        if (!inSpeech) return false
        inSpeech = false
        val pcm = segment.toShortArray()
        segment.clear()
        if (pcm.isEmpty()) return false
        onSegment(pcm)
        return true
    }

    private fun rms(buf: ShortArray, len: Int): Double {
        if (len <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until len) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / len)
    }

    companion object {
        private const val TAG = "VoiceWake"
        private const val MIN_RMS = 260.0
        private const val DEFAULT_SILENCE_MS = 600
        private const val MIN_SPEECH_MS = 300
        private const val MAX_SEGMENT_MS = 60_000
        private const val PRE_ROLL_FRAMES = 15 // 300ms
    }
}
