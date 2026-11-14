package com.openminis.app.ui.chat.voice

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openminis.app.logging.AppLogger
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.wake.WakeAudioCapture
import com.openminis.app.speech.wake.WakeEnergyVad
import com.openminis.app.speech.wake.WakeVoiceprintGate
import com.openminis.app.speech.wake.WakeWordService
import com.openminis.app.speech.wake.WavEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Press-and-hold dictation for the chat composer.
 *
 * The gesture: press the input field and hold → the composer starts
 * listening (the border lights up) → let go → the recognised text is sent.
 *
 * The microphone is opened by this class and by nobody else: the wake
 * service is told to release it synchronously before the recorder starts and
 * resumes standby when the hold ends, because a device can only hold one
 * AudioRecord.
 *
 * The voiceprint gate decides whether a sentence counts as "the owner
 * talking". Eight seconds without a sentence that passes the gate put the
 * controller back to sleep — a hold that nobody used must not leave the
 * microphone open forever.
 */
class HoldToTalkController(private val context: Context) {

    enum class State {
        /** Not listening. */
        IDLE,

        /** The microphone is open and a sentence is being recorded. */
        LISTENING,

        /** A sentence passed the voiceprint and is being transcribed. */
        TRANSCRIBING,

        /** Eight seconds passed without an accepted sentence — back to sleep. */
        SLEEPING,
    }

    var state by mutableStateOf(State.IDLE)
        private set

    /** The text of the last accepted sentence. */
    var transcript by mutableStateOf("")
        private set

    /** Called with the final text once the finger has been released. */
    var onSend: ((String) -> Unit)? = null

    /** Called when the listening window expired without an accepted sentence. */
    var onSleep: (() -> Unit)? = null

    /**
     * Asks the host for the microphone permission.
     *
     * The host owns the permission flow (it has the Activity and the
     * settings gate); the controller only needs the answer.
     */
    var requestPermission: (suspend () -> Boolean)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var capture: WakeAudioCapture? = null
    private var vad: WakeEnergyVad? = null
    private var watchdog: Job? = null
    private val gate by lazy { WakeVoiceprintGate(context) }

    /** Serialises segment handling; the VAD can cut a second sentence while
     *  the previous one is still being transcribed. */
    private val segmentLock = Mutex()

    /** Identifies one hold, so work from an abandoned hold cannot deliver. */
    private var session = 0

    /** Segments handed to [onSegment] that have not finished yet. */
    private var pendingSegments = 0

    /** When the current hold opened the microphone. */
    private var startedAt = 0L

    /** When the last sentence passed the voiceprint. */
    private var lastAcceptedAt = 0L

    /** True once the finger was lifted. */
    private var released = false

    /** True while a transcription is in flight. */
    private var transcribing = false

    /** True while the wake service is holding the microphone back for us. */
    private var wakePaused = false

    /** Set when the finger was lifted before the microphone was open. */
    private var stopRequested = false

    /** Open the microphone and start the listening window. */
    suspend fun start() {
        if (state == State.LISTENING || state == State.TRANSCRIBING) return
        stopRequested = false
        watchdog?.cancel()
        watchdog = null
        val currentSession = ++session
        // The app's own voice panel may still own the microphone.
        if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) {
            SpeechRecognitionManager.cancelRecording()
        }
        val granted = requestPermission?.invoke() ?: true
        if (!granted) {
            AppLogger.info(TAG, "没有麦克风权限，放弃")
            state = State.IDLE
            return
        }
        if (currentSession != session) return
        // The finger can be lifted while the permission dialog is on screen.
        if (stopRequested) {
            AppLogger.info(TAG, "手指已经松开，不再开始录音")
            state = State.IDLE
            return
        }
        state = State.LISTENING
        transcript = ""
        released = false
        transcribing = false
        pendingSegments = 0
        startedAt = SystemClock.elapsedRealtime()
        lastAcceptedAt = startedAt

        val detector = WakeEnergyVad(
            onSpeechStart = { AppLogger.info(TAG, "开口") },
            onSegment = { pcm ->
                AppLogger.info(TAG, "切句 ${pcm.size * 1000 / WakeAudioCapture.SAMPLE_RATE}ms")
                pendingSegments++
                scope.launch {
                    try {
                        onSegment(pcm, currentSession)
                    } finally {
                        if (currentSession == session) {
                            pendingSegments = (pendingSegments - 1).coerceAtLeast(0)
                            settle()
                        }
                    }
                }
            },
            // [T-voice-request-silence] A long dictation must not be cut by
            // the clock. People pause inside sentences; the old 600 ms
            // silence window split every natural pause into a segment (each
            // re-checked against the voiceprint, so a rejected segment
            // silently lost a chunk), and the 15 s cap chopped continuous
            // speech in half. End a segment only after 1.2 s of quiet, and
            // keep the 60 s cap as a safety valve, not as endpointing.
            minSilenceMs = { HOLD_SILENCE_MS },
            maxSegmentMs = { HOLD_MAX_SEGMENT_MS },
        )
        vad = detector
        val recorder = WakeAudioCapture(
            onFrame = { frame, len -> detector.feed(frame, len) },
            onError = { message -> AppLogger.info(TAG, "录音中断：$message") },
        )
        capture = recorder
        // The wake service holds the only other AudioRecord. Pause it before
        // this one starts — SpeechRecognitionManager stays IDLE while the raw
        // capture is open, so the service cannot infer the hand-off itself.
        WakeWordService.pauseForInAppCapture()
        wakePaused = true
        if (!withContext(Dispatchers.IO) { recorder.start() }) {
            // The background wake service hands the microphone back within a
            // frame or two — retry once instead of losing the hold.
            AppLogger.info(TAG, "麦克风启动失败，稍后重试")
            capture = null
            delay(400)
            val retried = withContext(Dispatchers.IO) { recorder.start() }
            if (currentSession != session || stopRequested || !retried) {
                AppLogger.info(TAG, "麦克风启动失败")
                resumeWake()
                state = State.IDLE
                return
            }
        }
        // The finger may have been lifted while the recorder was starting.
        // stop() already settled the hold; do not leave an orphaned capture
        // (and the wake service paused) behind it.
        if (currentSession != session || stopRequested || state != State.LISTENING) {
            AppLogger.info(TAG, "手指已经松开，停止录音")
            capture = null
            recorder.stop()
            resumeWake()
            state = State.IDLE
            return
        }
        capture = recorder
        AppLogger.info(TAG, "开始按住说话")
        watchdog = scope.launch {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                if (currentSession != session) return@launch
                if (state != State.LISTENING || transcribing) continue
                // A sentence that is still being spoken has not been cut yet;
                // cutting the microphone now would delete it mid-word.
                if (vad?.isInSpeech == true) {
                    lastAcceptedAt = SystemClock.elapsedRealtime()
                    continue
                }
                val reference = maxOf(startedAt, lastAcceptedAt)
                if (SystemClock.elapsedRealtime() - reference >= LISTEN_WINDOW_MS) {
                    AppLogger.info(TAG, "${LISTEN_WINDOW_MS / 1000}s 内没有通过声纹的语音，进入休眠")
                    sleep()
                    return@launch
                }
            }
        }
    }

    /** The finger was lifted: flush the tail, transcribe and hand the text over. */
    fun stop() {
        stopRequested = true
        if (state != State.LISTENING && state != State.TRANSCRIBING) return
        released = true
        watchdog?.cancel()
        watchdog = null
        // The last words are still in the VAD — push them out before the
        // microphone goes away, otherwise the tail of the sentence is lost.
        vad?.flush()
        stopCapture()
        settle()
    }

    /** Give up without sending anything. */
    fun cancel() {
        session++
        watchdog?.cancel()
        watchdog = null
        stopCapture()
        resumeWake()
        vad?.reset()
        vad = null
        released = true
        transcribing = false
        pendingSegments = 0
        transcript = ""
        state = State.IDLE
    }

    fun release() {
        session++
        watchdog?.cancel()
        watchdog = null
        stopCapture()
        resumeWake()
        vad?.reset()
        vad = null
        scope.cancel()
    }

    private fun stopCapture() {
        capture?.stop()
        capture = null
    }

    /** Hand the microphone back to the wake service (idempotent). */
    private fun resumeWake() {
        if (!wakePaused) return
        wakePaused = false
        WakeWordService.resumeAfterInAppCapture()
    }

    /**
     * One finished sentence.
     *
     * The voiceprint decides whether this was the owner. A sentence that
     * does not pass is ignored — the hold stays open, waiting for the real
     * one.
     */
    private suspend fun onSegment(pcm: ShortArray, currentSession: Int) {
        segmentLock.withLock {
            if (currentSession != session) return
            if (state == State.IDLE || state == State.SLEEPING) return
            val accepted = withContext(Dispatchers.Default) { passesVoiceprint(pcm) }
            if (!accepted) {
                AppLogger.info(TAG, "声纹不通过，忽略这句话")
                return
            }
            lastAcceptedAt = SystemClock.elapsedRealtime()
            transcribing = true
            state = State.TRANSCRIBING
            val text = transcribe(pcm)
            transcribing = false
            if (!text.isNullOrBlank()) {
                val piece = text.trim()
                transcript = if (transcript.isBlank()) piece else transcript + piece
                AppLogger.info(TAG, "识别结果：${transcript}")
            }
            if (!released && state == State.TRANSCRIBING) {
                state = State.LISTENING
            }
        }
        settle()
    }

    /**
     * Deliver once the finger is up and every segment has been processed.
     *
     * A release without any accepted sentence must still leave LISTENING;
     * otherwise the border keeps glowing and the next hold is ignored.
     */
    private fun settle() {
        if (!released || transcribing || pendingSegments > 0) return
        if (state == State.IDLE || state == State.SLEEPING) return
        val text = transcript
        transcript = ""
        if (text.isBlank()) {
            AppLogger.info(TAG, "没有可发送的语音")
            resumeWake()
            state = State.IDLE
            return
        }
        resumeWake()
        state = State.IDLE
        onSend?.invoke(text)
    }

    /** Back to sleep: no sentence passed the gate in time. */
    private fun sleep() {
        watchdog?.cancel()
        watchdog = null
        stopCapture()
        vad?.reset()
        vad = null
        transcribing = false
        transcript = ""
        released = true
        state = State.SLEEPING
        AppLogger.info(TAG, "进入休眠")
        resumeWake()
        onSleep?.invoke()
    }

    /**
     * The voiceprint gate.
     *
     * A gate that cannot run (no model, no template, audio too short) lets
     * the sentence through — a soft gate must never lock the owner out.
     */
    private fun passesVoiceprint(pcm: ShortArray): Boolean {
        if (!com.openminis.app.speech.wake.WakeWordSettings.voiceprintEnabled.value) return true
        if (!gate.hasTemplate()) {
            // First sentence: it becomes the owner template.
            gate.enrollIfAbsent(pcm, ENROLL_MIN_SECONDS)
            return true
        }
        val score = gate.verify(pcm) ?: return true
        val threshold = com.openminis.app.speech.wake.WakeWordSettings.voiceprintThreshold.value
        val ok = score >= threshold
        AppLogger.info(
            TAG,
            "声纹分数 ${"%.3f".format(score)} 阈值 ${"%.2f".format(threshold)} → " +
                (if (ok) "通过" else "拒绝"),
        )
        return ok
    }

    /** Recognise the recorded sentence offline — no microphone needed. */
    private suspend fun transcribe(pcm: ShortArray): String? {
        val wav = WavEncoder.encode(pcm)
        var attempt = 0
        while (attempt < 3) {
            val result = suspendCancellableCoroutine<String?> { cont ->
                val started = SpeechRecognitionManager.transcribeAudio(
                    wav,
                    { text, isFinal ->
                        if (isFinal && cont.isActive) cont.resume(text) {}
                    },
                    { error, message ->
                        AppLogger.info(TAG, "离线识别失败：${error.name} ${message ?: ""}")
                        if (cont.isActive) cont.resume(null) {}
                    },
                )
                if (!started && cont.isActive) {
                    AppLogger.info(
                        TAG,
                        "离线识别未启动：识别引擎状态=${SpeechRecognitionManager.state.value}",
                    )
                    cont.resume(null) {}
                }
            }
            if (!result.isNullOrBlank()) return result
            if (SpeechRecognitionManager.state.value == RecognitionState.IDLE) return null
            delay(400)
            attempt++
        }
        return null
    }

    private companion object {
        const val TAG = "HoldToTalk"

        /** How long a hold may stay open without an accepted sentence. */
        const val LISTEN_WINDOW_MS = 8_000L

        /** 句中自然停顿：超过这个静音才认为这一句说完了。 */
        const val HOLD_SILENCE_MS = 1_200

        /** 安全阀：一直不停顿的长语音最多 60 秒切一段。 */
        const val HOLD_MAX_SEGMENT_MS = 60_000

        /** How often the idle window is checked. */
        const val WATCHDOG_TICK_MS = 500L

        /** Minimum speech before the first sentence may enrol the owner. */
        const val ENROLL_MIN_SECONDS = 1.0f
    }
}
