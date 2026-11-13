package com.openminis.app.speech.wake

import android.content.Context
import com.openminis.app.logging.AppLogger

/**
 * Audio capture + KWS, with no service or UI knowledge.
 *
 * `start` is synchronous (model init is ~100 ms), so callers should run it off
 * the main thread if they care about frame pacing. `stop` is idempotent and
 * safe to call from the capture thread when capture reports a failure.
 */
class WakeWordEngine(
    private val context: Context,
    private val onHit: (String) -> Unit,
    private val onError: (String) -> Unit,
) {

    @Volatile
    var words: List<String> = emptyList()
        private set

    @Volatile
    var isListening: Boolean = false
        private set

    /**
     * True while the keyword spotter consumes the audio.
     *
     * The conversation phase turns this off: the microphone stays open (the
     * user's request is being recorded) but running the KWS on it would only
     * cost CPU and risk a second hit inside the same utterance.
     */
    @Volatile
    var detecting: Boolean = true
        private set

    /**
     * A second consumer of the captured frames.
     *
     * The conversation phase uses it to run the energy VAD over the same
     * audio the KWS sees — one microphone, two readers.
     */
    @Volatile
    var onFrame: ((ShortArray, Int) -> Unit)? = null

    /**
     * Loudest mic frame since the engine started, 0..1 RMS.
     * Diagnostics for telling "heard the user" apart from "heard the
     * assistant's own voice".
     */
    val peakLevel: Float
        get() = capture?.peakLevel ?: 0f

    /** The most recent [ms] milliseconds of captured audio. */
    fun recentAudio(ms: Int): ShortArray = capture?.recentAudio(ms) ?: ShortArray(0)

    /** Stop or resume keyword spotting without touching the microphone. */
    fun setDetecting(on: Boolean) {
        detecting = on
    }

    private var detector: WakeWordDetector? = null
    private var capture: WakeAudioCapture? = null
    private val lock = Any()

    fun start(nextWords: List<String>): Boolean {
        val wanted = nextWords.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) {
            onError("唤醒词为空")
            return false
        }
        synchronized(lock) {
            if (isListening && wanted == words) return true
            stopLocked()

            val model = WakeWordDetector.resolveModel(context)
            if (model == null) {
                onError("唤醒词模型缺失")
                return false
            }
            val d = WakeWordDetector(model)
            if (!d.load(wanted)) {
                onError(d.lastError ?: "唤醒词加载失败")
                d.release()
                return false
            }
            val cap = WakeAudioCapture(
                onFrame = { frame, len ->
                    onFrame?.invoke(frame, len)
                    if (isListening && detecting) {
                        val hit = d.feed(frame, len)
                        if (hit != null) onHit(hit)
                        // A decoder failure inside [WakeWordDetector.feed]
                        // marks the detector unusable but returns null.
                        // Without this check the engine would look healthy
                        // to the gate loop while never matching again.
                        if (!d.ready) {
                            isListening = false
                            onError(d.lastError ?: "唤醒识别已中断")
                        }
                    }
                },
                onError = { message ->
                    // Called from the capture thread: do not join ourselves.
                    isListening = false
                    onError(message)
                },
            )
            detector = d
            capture = cap
            words = wanted
            isListening = true
            cap.resetPeak()
            if (!cap.start()) {
                isListening = false
                runCatching { d.release() }
                detector = null
                capture = null
                onError(cap.lastError ?: "麦克风启动失败")
                return false
            }
            AppLogger.info(TAG, "唤醒引擎启动 words=$wanted")
            return true
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        isListening = false
        capture?.stop()
        capture = null
        detector?.release()
        detector = null
        words = emptyList()
        AppLogger.info(TAG, "唤醒引擎停止")
    }

    private companion object {
        const val TAG = "VoiceWake"
    }
}
