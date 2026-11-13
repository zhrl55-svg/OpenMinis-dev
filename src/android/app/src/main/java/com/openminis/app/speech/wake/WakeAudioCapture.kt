package com.openminis.app.speech.wake

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.openminis.app.logging.AppLogger

/**
 * Sole microphone owner while wake mode is active.
 *
 * Hard constraint: one AudioRecord owner at a time. The service stops this
 * capture before it launches the in-app recognizer, otherwise the recognizer
 * opens a second stream and either gets silence or fights for the route.
 *
 * 16 kHz / mono / PCM16, 20 ms per frame.
 */
class WakeAudioCapture(
    private val onFrame: (ShortArray, Int) -> Unit,
    private val onError: (String) -> Unit = {},
) {

    @Volatile
    private var running = false

    @Volatile
    var lastError: String? = null
        private set

    /**
     * Loudest frame seen since the last reset, as a 0..1 RMS ratio.
     *
     * Diagnostics only: a wake hit that lands on a loud frame is usually
     * the assistant hearing itself (TTS echo), while a hit on a quiet frame
     * points at the model's threshold. [resetPeak] is called before each
     * listen session.
     */
    @Volatile
    var peakLevel: Float = 0f
        private set

    fun resetPeak() {
        peakLevel = 0f
    }

    /**
     * Rolling window of the most recent audio.
     *
     * The wake-word utterance itself is the owner's voice — it is the one
     * sample guaranteed to contain only their voice — so the voiceprint
     * check reads the tail of the buffer right after the hit.
     */
    private val ring = ShortArray(RING_SAMPLES)
    private var ringFill = 0

    /**
     * The most recent [ms] milliseconds of audio.
     *
     * @return mono 16 kHz PCM, or an empty array when there is not enough
     *   buffered yet.
     */
    @Synchronized
    fun recentAudio(ms: Int): ShortArray {
        val want = (ms * SAMPLE_RATE / 1000).coerceIn(0, RING_SAMPLES)
        if (want == 0) return ShortArray(0)
        val available = minOf(ringFill, RING_SAMPLES)
        val take = minOf(want, available)
        if (take == 0) return ShortArray(0)
        val out = ShortArray(take)
        // The ring is a plain array that wraps: the newest sample sits at
        // (ringFill - 1) % RING_SAMPLES.
        var src = (ringFill - take) % RING_SAMPLES
        if (src < 0) src += RING_SAMPLES
        for (i in 0 until take) {
            out[i] = ring[src]
            src = (src + 1) % RING_SAMPLES
        }
        return out
    }

    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            fail("设备不支持 16kHz 录音（minBuffer=$minBuf）")
            return false
        }
        val bufSize = maxOf(minBuf * 2, FRAME_SAMPLES * 2 * 8)
        val rec = try {
            // VOICE_RECOGNITION rather than VOICE_COMMUNICATION: the latter
            // applies call-grade AEC that also attenuates near-field speech,
            // measurably hurting recognition.
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufSize,
            )
        } catch (t: Throwable) {
            fail("AudioRecord 创建失败：${t.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            fail("AudioRecord 初始化失败 state=${rec.state}")
            runCatching { rec.release() }
            return false
        }
        val started = runCatching {
            rec.startRecording()
            rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.getOrDefault(false)
        if (!started) {
            fail("AudioRecord 启动失败")
            runCatching { rec.release() }
            return false
        }
        record = rec
        running = true
        AppLogger.info(TAG, "AudioCapture 启动 ${SAMPLE_RATE}Hz buf=$bufSize")

        thread = Thread {
            val frame = ShortArray(FRAME_SAMPLES)
            var fill = 0
            while (running && !Thread.currentThread().isInterrupted) {
                val n = runCatching { rec.read(frame, fill, FRAME_SAMPLES - fill) }.getOrDefault(-1)
                if (n < 0) {
                    if (running) AppLogger.info(TAG, "AudioRecord.read 返回 $n，停止采集")
                    break
                }
                if (n == 0) continue
                fill += n
                if (fill < FRAME_SAMPLES) continue
                updateLevel(frame, FRAME_SAMPLES)
                pushRing(frame, FRAME_SAMPLES)
                runCatching { onFrame(frame, FRAME_SAMPLES) }
                fill = 0
            }
            if (running) {
                // A read failure with running still true means capture died
                // underneath us (route change, permission revoked, HAL error).
                running = false
                onError(lastError ?: "麦克风采集中断")
            }
        }.apply {
            isDaemon = true
            name = "voice-wake-capture"
            start()
        }
        return true
    }

    fun stop() {
        running = false
        val t = thread
        thread = null
        if (t != null && t !== Thread.currentThread()) {
            runCatching { t.join(500) }
        }
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
        AppLogger.info(TAG, "AudioCapture 停止")
    }

    val isRunning: Boolean get() = running

    private fun updateLevel(frame: ShortArray, count: Int) {
        var sum = 0.0
        for (i in 0 until count) {
            val v = frame[i].toDouble()
            sum += v * v
        }
        val rms = Math.sqrt(sum / count) / Short.MAX_VALUE
        if (rms > peakLevel) peakLevel = rms.toFloat()
    }

    @Synchronized
    private fun pushRing(frame: ShortArray, count: Int) {
        for (i in 0 until count) {
            ring[ringFill % RING_SAMPLES] = frame[i]
            ringFill++
        }
    }

    private fun fail(message: String) {
        lastError = message
        AppLogger.info(TAG, message)
        onError(message)
    }

    companion object {
        private const val TAG = "VoiceWake"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MS
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** Four seconds of history — enough for the wake word plus a reply. */
        private const val RING_SAMPLES = SAMPLE_RATE * 4
    }
}
