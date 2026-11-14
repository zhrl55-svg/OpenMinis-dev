package com.openminis.app.speech.wake

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.VoiceOutputState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Background wake-word listener.
 *
 * Shape:
 *  - a microphone foreground service (Android 10+ requires the type, Android
 *    14+ the `FOREGROUND_SERVICE_MICROPHONE` permission)
 *  - one AudioRecord owned by [WakeWordEngine]
 *  - a 400 ms gate loop that yields the microphone whenever an in-app voice
 *    capture is active, the in-app recognizer is running, TTS is playing, or
 *    the user has just been woken
 *  - on a hit: keep the microphone and run the turn headlessly; tapping the
 *    overlay/notification opens the persistent voice-conversation session
 *
 * The gate is the important part. Holding a second AudioRecord while the
 * in-app recognizer opens the mic produces silence on one side or the other,
 * and holding it while TTS is speaking lets the assistant wake itself.
 *
 * The wake word stays available in the foreground — the app is a voice
 * assistant first, and the user should be able to say the wake word without
 * leaving the chat. The microphone is only handed over while an in-app
 * capture (composer dictation or speech recognition) actually needs it.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "VoiceWake"
        const val CHANNEL_ID = "minis_voice_wake"
        const val ALERT_CHANNEL_ID = "minis_voice_wake_alert"
        const val NOTIFICATION_ID = 9004
        const val ALERT_NOTIFICATION_ID = 9005
        private const val ACTION_STOP = "com.openminis.app.voice.wake.STOP"
        private const val GATE_INTERVAL_MS = 400L

        /** Guard against a second hit right after the conversation ended. */
        private const val WAKE_SUPPRESS_MS = 2_000L

        /**
         * How long keyword spotting stays off after a rejected wake.
         *
         * The engine and microphone stay open — only the spotter pauses.
         * Without the pause the tail of the same ambient phrase can match
         * again immediately, which burns a voiceprint extraction per repeat.
         */
        private const val WAKE_REJECT_COOLDOWN_MS = 700L

        /** Segments kept while the previous one is being transcribed. */
        private const val MAX_PENDING_UTTERANCES = 2

        /**
         * How long the assistant keeps listening for the request.
         *
         * The clock starts at the wake hit and is renewed after every
         * accepted utterance, so a conversation continues while the user is
         * talking and the assistant goes back to sleep once the user stops.
         */
        private const val CONVERSATION_WINDOW_MS = 8_000L

        /**
         * Minimum speech before a first utterance may enrol the owner
         * template — a cough is not a voiceprint.
         *
         * 1.0 s, not 1.5 s: a short request like "几点了" is 0.8–1.2 s and
         * would otherwise never enrol anything, leaving the gate inert.
         */
        private const val ENROLL_MIN_SECONDS = 1.0f

        /**
         * How much of the recording around the wake word is used to enrol
         * or verify the owner.
         *
         * The keyword spotter fires at the *end* of the keyword, so the last
         * two and a half seconds contain the whole keyword plus a little of
         * what came before it — all of it the owner's own voice, and none of
         * it the assistant's echo.
         */
        private const val WAKE_ENROLL_WINDOW_MS = 2_500

        /** Below this the wake-word sample is too short to describe a voice. */
        private const val WAKE_ENROLL_MIN_SECONDS = 0.8

        /**
         * Quiet period after the assistant stops talking.
         *
         * The microphone stays open during the reply, so the tail of the
         * assistant's own voice is still in the buffer when the VAD comes
         * back. Without this gap the VAD would cut the echo as a new
         * utterance and the assistant would answer itself.
         */
        private const val ECHO_GUARD_MS = 1_000L

        /**
         * How long the conversation survives after the window closed on an
         * unfinished utterance.
         *
         * The utterance still has to be transcribed, checked against the
         * voiceprint and answered, and none of that may be cut short by the
         * deadline that just expired.
         */
        private const val FLUSH_GRACE_MS = 20_000L

        /** Pause that cuts a barge-in utterance while the assistant talks. */
        private const val BARGE_IN_SILENCE_MS = 300

        /**
         * Longest barge-in segment.
         *
         * TTS is continuous, so waiting for a natural sentence pause can
         * delay "别说了" by many seconds. Cutting every 1.5 s keeps the
         * reaction time short without sending frame-sized clips to the ASR.
         */
        private const val BARGE_IN_MAX_SEGMENT_MS = 1_500

        /** Fallback when the string resource cannot be resolved. */
        private const val DEFAULT_ACKNOWLEDGEMENT = "我在"

        /**
         * How long the room keeps ringing after the engine stopped.
         *
         * The microphone hears the tail of the assistant's own voice for a
         * few hundred milliseconds after the TTS engine reports "done".
         */
        private const val ECHO_TAIL_MS = 700L

        /**
         * Grace period before the microphone is opened for the first time.
         *
         * The service is started from `Application.onCreate` (and again on
         * every launch), which runs BEFORE the first Activity is started —
         * so for a moment the app looks "in the background" and the gate
         * would open the microphone only to release it again a few hundred
         * milliseconds later, racing the in-app recognizer for the device.
         */
        private const val STARTUP_GRACE_MS = 5_000L

        /**
         * Backoff before the KWS model is loaded again after a failure.
         *
         * The gate loop runs every 400 ms; without this the service would
         * reload the 4.6 MB zipformer on every tick while the microphone is
         * unavailable (a call in progress, a revoked permission, a HAL
         * error) and burn CPU instead of waiting.
         */
        private const val ENGINE_RETRY_DELAY_MS = 30_000L

        /** True while this process holds a live [WakeWordService]. */
        @Volatile
        var isAlive: Boolean = false
            private set

        /**
         * True while the composer owns the microphone for press-and-hold
         * dictation.
         *
         * The composer records through its own [WakeAudioCapture], and
         * [SpeechRecognitionManager] says nothing about that until the
         * transcription starts — so without this flag the wake engine and
         * the composer would open two AudioRecords at once. Set before the
         * composer opens its recorder and cleared on every terminal path.
         */
        @Volatile
        private var inAppCapturePaused = false

        /** The live instance, so the app can hand the microphone back fast. */
        @Volatile
        private var instance: WakeWordService? = null

        /**
         * Give the microphone up for an in-app recording.
         *
         * Called *before* the composer opens its own AudioRecord: the engine
         * is stopped synchronously, so the hand-off is deterministic instead
         * of a race between two capture streams. The gate loop keeps the
         * engine down until [resumeAfterInAppCapture].
         */
        fun pauseForInAppCapture() {
            inAppCapturePaused = true
            instance?.suspendForInAppCapture()
        }

        /** The in-app recording ended: the wake engine may open the mic again. */
        fun resumeAfterInAppCapture() {
            inAppCapturePaused = false
        }

        /**
         * Stop the reply the wake conversation is speaking.
         *
         * The wake conversation owns its own TTS engine, so the composer's
         * read-aloud player cannot silence it. The UI calls this from the
         * speaker button next to Send.
         */
        fun stopSpeaking() {
            instance?.stopSpeakingNow()
        }

        /**
         * Start the service. Returns false when the system refused the
         * start (Android 12+ throws when a foreground service is started
         * from the background without an exemption) so the caller can
         * retry when the app comes back to the foreground.
         */
        fun start(context: Context): Boolean {
            val intent = Intent(context, WakeWordService::class.java)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (t: Throwable) {
                AppLogger.info(TAG, "无法启动语音唤醒服务：${t.javaClass.simpleName}: ${t.message}")
                VoiceWakeState.update(context, VoiceWakePhase.ERROR, "系统拒绝了后台录音服务的启动")
                false
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WakeWordService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var engine: WakeWordEngine? = null
    private var overlay: VoiceStatusOverlay? = null
    private var gateJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var destroying = false
    private var engineStarting = false
    @Volatile
    private var desiredListening = false
    @Volatile
    private var engineError: String? = null
    @Volatile
    private var nextEngineRetryAt = 0L
    private var wakingUntil = 0L
    private var lastNotificationText = ""
    private val serviceStartedAt = SystemClock.elapsedRealtime()

    // ------------------------------------------------------- conversation

    /** Where the background conversation currently is. */
    private enum class ConversationState {
        /** Only the wake word is being watched for. */
        IDLE,

        /**
         * The wake word matched, but its voiceprint is still being checked.
         *
         * Not a wake yet: no acknowledgement, no VAD, no listening window.
         * The microphone stays open only to keep the engine from being torn
         * down between the keyword and the verdict.
         */
        VERIFYING,

        /** The microphone is open and the user's request is being recorded. */
        LISTENING,

        /** The request is being transcribed / answered / spoken. */
        REPLYING,
    }

    @Volatile
    private var conversationState = ConversationState.IDLE

    /** When the current listening window runs out. */
    @Volatile
    private var conversationDeadline = 0L

    /** Cuts the user's request out of the live microphone stream. */
    private var vad: WakeEnergyVad? = null

    /** True while the wake acknowledgement is being spoken. */
    @Volatile
    private var ackPlaying = false

    /** True while one utterance is being transcribed / answered. */
    @Volatile
    private var utteranceBusy = false

    /**
     * Segments cut while [utteranceBusy].
     *
     * The acknowledgement segment and the request can arrive back-to-back.
     * Dropping the second one made the assistant answer "我在" and then go
     * silent even though the user had already asked the question.
     */
    private val pendingUtterances = ArrayDeque<ShortArray>()

    /** When the assistant's own voice is over (elapsedRealtime). */
    @Volatile
    private var echoDeadline = 0L

    /** When the acknowledgement stopped being spoken (elapsedRealtime). */
    @Volatile
    private var ackEndedAt = 0L

    /** When the acknowledgement started (elapsedRealtime), 0 = none. */
    @Volatile
    private var ackStartedAt = 0L

    /** The word the assistant used as the acknowledgement. */
    @Volatile
    private var ackText: String = ""

    /** The reply the assistant just spoke — its echo must not be answered. */
    @Volatile
    private var replyText: String = ""

    /** True when the user asked to cut the current spoken reply. */
    @Volatile
    private var speechStopRequested = false

    /** Cuts stop commands out of the audio while the reply is speaking. */
    private var bargeInVad: WakeEnergyVad? = null

    /** One barge-in transcription at a time. */
    @Volatile
    private var bargeInBusy = false

    private val voiceprint by lazy { WakeVoiceprintGate(this) }

    /**
     * Speaks the replies.
     *
     * The read-aloud capsule is UI-only, so the service keeps its own TTS
     * engine — the whole point of the background conversation is that no
     * Activity is opened.
     */
    private var tts: com.openminis.app.speech.TextToSpeechManager? = null

    /** When the last conversation diagnostic line was written. */
    private var lastDiagAt = 0L

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        instance = this
        WakeWordSettings.init(this)
        createChannels()
        overlay = VoiceStatusOverlay(this)

        if (!startForegroundSafely(buildNotification())) {
            stopSelf()
            return
        }

        scope.launch {
            VoiceWakeState.state.collect { snapshot ->
                overlay?.update(snapshot)
                updateNotification(snapshot)
            }
        }
        // The TTS engine is bound **now**, not when the first wake word
        // arrives: a cold TTS engine takes seconds to initialise, and every
        // second of that is a second in which the user is already talking
        // into a microphone that answers with silence.
        prewarmTts()
        prewarmVoiceprint()
        gateJob = scope.launch { gateLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WakeWordSettings.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!WakeWordSettings.enabled.value) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foregroundStarted && !startForegroundSafely(buildNotification())) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun onDestroy() {
        destroying = true
        isAlive = false
        if (instance === this) instance = null
        gateJob?.cancel()
        gateJob = null
        runCatching { tts?.stop() }
        tts = null
        engine?.stop()
        engine = null
        overlay?.hide()
        overlay = null
        releaseWakeLock()
        if (WakeWordSettings.enabled.value) {
            // The service died while the feature is still on. Android 14+
            // refuses a microphone foreground service that is started from
            // the background, so the next attempt has to wait for the app
            // to be in the foreground again.
            WakeWordController.onServiceStartRefused(this)
        } else {
            VoiceWakeState.update(this, VoiceWakePhase.OFF, "语音唤醒已停止")
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- gate

    private suspend fun gateLoop() {
        while (scope.isActive && !destroying) {
            val now = SystemClock.elapsedRealtime()
            val app = applicationContext as? MinisApp
            val foreground = app?.isAppForeground() == true
            val enabled = WakeWordSettings.enabled.value
            val overlayEnabled = WakeWordSettings.showOverlay.value
            val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val sttActive = SpeechRecognitionManager.state.value != RecognitionState.IDLE
            val ttsActive = VoiceOutputState.isSpeaking.value || VoiceOutputState.isSynthesizing.value
            val inAppCapture = inAppCapturePaused
            val waking = now < wakingUntil
            val inGrace = now - serviceStartedAt < STARTUP_GRACE_MS

            // The conversation owns the microphone while it runs: the
            // assistant is recording the request, so the normal gate does
            // not apply.
            if (conversationState != ConversationState.IDLE) {
                handleConversationTick(now, enabled, permission)
                val showBall = enabled && overlayEnabled && !foreground
                overlay?.setVisible(showBall)
                delay(GATE_INTERVAL_MS)
                continue
            }

            val reason = when {
                !enabled -> "语音唤醒已关闭"
                !permission -> "需要麦克风权限"
                waking -> "正在唤醒"
                inGrace -> "正在初始化，稍后开始聆听"
                inAppCapture -> "正在使用语音输入"
                // Only the recognizer's own state says whether the app holds
                // the microphone. A composer-mode flag would stay stale after
                // backgrounding and could silence the wake word.
                sttActive -> "语音识别进行中"
                ttsActive -> "正在播报，避免听到自己"
                else -> null
            }
            val shouldListen = reason == null
            desiredListening = shouldListen

            if (shouldListen) {
                val words = WakeWordSettings.wakeWordList()
                val failed = engineError
                if (failed != null && now < nextEngineRetryAt) {
                    // Backoff: keep the error visible, do not reload the
                    // model on every 400 ms tick.
                    if (engine != null) stopEngine()
                    publish(VoiceWakePhase.ERROR, failed)
                } else {
                    if (engine == null && !engineStarting) {
                        startEngine(words)
                    } else if (engine != null && !engine!!.isListening && !engineStarting) {
                        startEngine(words)
                    } else if (engine != null && engine!!.words != words) {
                        startEngine(words)
                    }
                }
                if (engine?.isListening == true) {
                    // The engine is up: any earlier failure is over.
                    if (engineError != null) {
                        engineError = null
                        nextEngineRetryAt = 0L
                    }
                    publish(VoiceWakePhase.WAITING, "等待唤醒词「${WakeWordSettings.wakeWord.value}」")
                }
            } else {
                if (engine != null || engineStarting) stopEngine()
                val phase = when {
                    !enabled -> VoiceWakePhase.OFF
                    !permission -> VoiceWakePhase.ERROR
                    // The wake hit opens the voice chat; keep saying
                    // "waking" for the suppression window instead of
                    // falling back to "paused".
                    waking -> VoiceWakePhase.WAKING
                    else -> VoiceWakePhase.PAUSED
                }
                publish(phase, reason.orEmpty())
            }

            val showBall = enabled && overlayEnabled && !foreground
            overlay?.setVisible(showBall)

            delay(GATE_INTERVAL_MS)
        }
    }

    private fun startEngine(words: List<String>) {
        if (engineStarting || destroying) return
        if (words.isEmpty()) {
            failEngine("唤醒词为空")
            return
        }
        engineStarting = true
        // Never hold two AudioRecords. The service gate calls this only after
        // deciding the old engine must be replaced (wake-word edit) or was
        // stopped, so dropping it first is safe.
        engine?.stop()
        engine = null
        scope.launch {
            var candidate: WakeWordEngine? = null
            val started = withContext(Dispatchers.IO) {
                try {
                    val next = WakeWordEngine(
                        context = this@WakeWordService,
                        onHit = { word -> scope.launch { onWakeHit(word) } },
                        onError = { message -> onEngineError(message) },
                    )
                    candidate = next
                    if (next.start(words)) {
                        true
                    } else {
                        // Never leave a half-started engine behind: it owns
                        // the only AudioRecord.
                        next.stop()
                        false
                    }
                } catch (t: Throwable) {
                    AppLogger.info(TAG, "唤醒引擎异常：${t.javaClass.simpleName}: ${t.message}")
                    // The candidate may already own an AudioRecord.
                    candidate?.stop()
                    false
                }
            }
            engineStarting = false
            val ready = candidate
            if (started && ready != null) {
                if (desiredListening && !destroying && !inAppCapturePaused) {
                    engine = ready
                    engineError = null
                    nextEngineRetryAt = 0L
                    // Only hold the CPU awake while the microphone is
                    // actually open: in the foreground the screen already
                    // keeps the device awake, and a permanent wake lock
                    // would drain the battery for nothing.
                    acquireWakeLock()
                    AppLogger.info(TAG, "唤醒引擎已就绪")
                    publish(VoiceWakePhase.WAITING, "等待唤醒词「${WakeWordSettings.wakeWord.value}」")
                } else {
                    ready.stop()
                }
            } else if (inAppCapturePaused) {
                // The start raced the composer's recorder and lost the mic.
                // Expected, not an engine failure: the gate retries as soon
                // as the hold ends.
                engineError = null
                nextEngineRetryAt = 0L
            } else {
                failEngine(engineError ?: "唤醒引擎启动失败")
            }
        }
    }

    /**
     * The engine failed (model load, microphone, decoder). The state is
     * published immediately and the model is retried after
     * [ENGINE_RETRY_DELAY_MS].
     */
    private fun onEngineError(message: String) {
        failEngine(message)
    }

    private fun failEngine(message: String) {
        engineError = message
        nextEngineRetryAt = SystemClock.elapsedRealtime() + ENGINE_RETRY_DELAY_MS
        scope.launch {
            publish(VoiceWakePhase.ERROR, message)
        }
    }

    private fun stopEngine() {
        engine?.stop()
        engine = null
        releaseWakeLock()
    }

    /**
     * Stop the engine for a composer recording.
     *
     * Runs on the caller's thread (the composer's main-thread gesture
     * coroutine) because the composer opens its AudioRecord immediately
     * after this returns; posting the stop to [scope] would race it.
     */
    private fun suspendForInAppCapture() {
        // A background conversation may be mid-turn. The user has picked up
        // the composer, so that turn yields to the app's own voice input.
        abortConversation("应用内语音输入")
        stopEngine()
        publish(VoiceWakePhase.PAUSED, "语音输入中")
    }

    // ---------------------------------------------------------------- wake

    private fun onWakeHit(word: String) {
        val now = SystemClock.elapsedRealtime()
        if (now < wakingUntil || destroying || conversationState != ConversationState.IDLE) return
        wakingUntil = now + WAKE_SUPPRESS_MS
        // Log what the gate believed at the moment of the hit. A hit with
        // ttsSpeaking=true means the listener heard the assistant's own
        // voice — that is pilot's "agent talks to itself" trap, and the fix
        // is the TTS gate, not the model.
        val ttsSpeaking = VoiceOutputState.isSpeaking.value
        val ttsSynthesizing = VoiceOutputState.isSynthesizing.value
        val stt = SpeechRecognitionManager.state.value
        val foreground = (applicationContext as? MinisApp)?.isAppForeground() == true
        val peak = engine?.peakLevel ?: 0f
        AppLogger.info(
            TAG,
            "唤醒词命中「$word」 peak=${"%.3f".format(peak)} tts=$ttsSpeaking/$ttsSynthesizing " +
                "stt=$stt fg=$foreground",
        )
        VoiceWakeState.trace(this, "WAKE_HIT $word")

        // The microphone STAYS OPEN: the request is the next thing the user
        // says, and the voiceprint needs the exact samples. Only the keyword
        // spotter goes quiet, so it cannot fire again inside the utterance.
        engine?.setDetecting(false)

        if (!WakeWordSettings.voiceprintEnabled.value) {
            publish(VoiceWakePhase.WAKING, "唤醒词「$word」")
            startConversation()
            return
        }

        // Take the snapshot on the caller's thread, before the model has
        // had time to run: the ring buffer keeps moving and must not pick
        // up the acknowledgement or the request.
        val snapshot = engine?.recentAudio(WAKE_ENROLL_WINDOW_MS) ?: ShortArray(0)

        // Nothing to compare against yet. Enrol in the background and let
        // the first wake through immediately: waiting for a 28 MB model to
        // load before saying "我在" would make the very first use feel
        // broken, and there is no verdict to wait for anyway.
        if (!voiceprint.hasTemplate()) {
            publish(VoiceWakePhase.WAKING, "唤醒词「$word」")
            enrollWakeVoiceprint(snapshot)
            startConversation()
            return
        }

        // A hit is not a wake until the owner's voiceprint passes. Park in
        // VERIFYING: the gate loop keeps the microphone open, but no
        // acknowledgement is spoken, no VAD is armed and no listening
        // window starts. An ambient "小派" therefore never becomes a wake.
        conversationState = ConversationState.VERIFYING
        conversationDeadline = now + CONVERSATION_WINDOW_MS
        // Do not publish WAKING here: on a false hit the overlay and
        // notification must stay on "waiting", exactly as if the phrase
        // had never matched. The score line is the diagnostic signal.

        scope.launch {
            val passed = withContext(Dispatchers.IO) {
                passesWakeVoiceprint(snapshot)
            }
            if (destroying || conversationState != ConversationState.VERIFYING) return@launch

            if (passed) {
                // Verdict passed: this is a real wake. Begin the
                // conversation exactly as before.
                conversationState = ConversationState.IDLE
                startConversation()
                return@launch
            }

            // Rejected: go straight back to standby. No acknowledgement,
            // no VAD, no conversation window — the false hit is discarded.
            conversationState = ConversationState.IDLE
            wakeRejected(snapshot)
        }
    }

    /**
     * A failed wake: discard it without saying or recording anything.
     *
     * The spotter pauses briefly so the tail of the same ambient phrase
     * cannot immediately trigger another extraction.
     */
    private suspend fun wakeRejected(snapshot: ShortArray) {
        pendingUtterances.clear()
        // The gate loop stops the engine while [wakingUntil] is in the
        // future and the conversation is idle. Clearing it keeps the
        // microphone (and the KWS model) alive; only [WakeWordEngine.detecting]
        // is paused below.
        wakingUntil = 0L
        publish(VoiceWakePhase.WAITING, "等待唤醒词「${WakeWordSettings.wakeWord.value}」")
        AppLogger.info(TAG, "唤醒声纹未通过，保持待命（${snapshot.size * 1000 / WakeAudioCapture.SAMPLE_RATE}ms）")
        delay(WAKE_REJECT_COOLDOWN_MS)
        if (!destroying && conversationState == ConversationState.IDLE) {
            engine?.setDetecting(true)
        }
    }

    // ------------------------------------------------------- conversation

    /** Enrol the first wake-word sample without delaying the first reply. */
    private fun enrollWakeVoiceprint(pcm: ShortArray) {
        val seconds = pcm.size.toDouble() / WakeAudioCapture.SAMPLE_RATE
        if (seconds < WAKE_ENROLL_MIN_SECONDS) {
            AppLogger.info(TAG, "唤醒词音频 ${"%.1f".format(seconds)}s 太短，稍后用第一句注册")
            return
        }
        scope.launch(Dispatchers.IO) {
            val ok = voiceprint.enroll(pcm)
            AppLogger.info(TAG, "用唤醒词注册声纹 ${"%.1f".format(seconds)}s → saved=$ok")
            VoiceWakeState.trace(this@WakeWordService, "声纹注册 ${if (ok) "成功" else "失败"}")
        }
    }

    /**
     * The wake-word voiceprint gate.
     *
     * The wake word is public: the KWS model fires on the sound of the
     * phrase, not on who said it. Without this check an ambient "小派" from
     * a TV or another person opens a conversation, plays "我在", and holds
     * the microphone for eight seconds.
     *
     * Soft-gate rules are the same as [passesVoiceprint]: no template, no
     * model, or an unusably short sample lets the hit through rather than
     * locking the owner out. With a template and a usable sample, the score
     * must reach the configured threshold.
     *
     * On the very first wake there is no template; that wake-word sample is
     * enrolled, exactly as before — it is still the one sample guaranteed
     * not to contain the assistant's own voice.
     */
    private fun passesWakeVoiceprint(pcm: ShortArray): Boolean {
        if (!WakeWordSettings.voiceprintEnabled.value) return true
        val gate = voiceprint
        val seconds = pcm.size.toDouble() / WakeAudioCapture.SAMPLE_RATE

        if (!gate.hasTemplate()) return true

        if (seconds < WAKE_ENROLL_MIN_SECONDS) {
            AppLogger.info(TAG, "唤醒声纹跳过：音频仅 ${"%.1f".format(seconds)}s，放行")
            return true
        }

        val score = gate.verify(pcm)
        if (score == null) {
            AppLogger.info(TAG, "唤醒声纹无法校验，放行")
            return true
        }
        val threshold = WakeWordSettings.voiceprintThreshold.value
        val ok = score >= threshold
        AppLogger.info(
            TAG,
            "唤醒声纹 ${"%.3f".format(score)} 阈值 ${"%.2f".format(threshold)} → " +
                (if (ok) "通过" else "拒绝"),
        )
        VoiceWakeState.trace(
            this,
            "唤醒声纹 ${"%.3f".format(score)} 阈值 ${"%.2f".format(threshold)} " +
                (if (ok) "通过" else "拒绝"),
        )
        return ok
    }

    /**
     * Enter the listening phase: the microphone is already open, the VAD
     * starts cutting the user's request out of it, and the window starts
     * running. No Activity is opened — the whole point is that the user
     * stays wherever they were.
     */
    private fun startConversation() {
        if (conversationState != ConversationState.IDLE) return
        conversationState = ConversationState.LISTENING
        conversationDeadline = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
        // Every cycle starts with a clean echo window — the previous cycle's
        // acknowledgement must not make this cycle's first sentence look
        // like the assistant talking to itself.
        ackStartedAt = 0L
        ackEndedAt = 0L
        echoDeadline = 0L
        ackText = ""
        replyText = ""
        // The VAD is armed **immediately**. Arming it after the
        // acknowledgement would swallow the request: the user answers the
        // wake word while "我在" is still playing, and that audio is gone.
        // The assistant's own voice is filtered out by the echo window
        // instead (see [acknowledge] / [onUtterance]).
        installVad()
        publish(VoiceWakePhase.LISTENING, "正在聆听")
        AppLogger.info(TAG, "进入聆听状态")
        scope.launch { acknowledge() }
    }

    /**
     * Bind the TTS engine while the service waits for the wake word.
     *
     * Android's TTS engine is a separate process and a cold start costs
     * one to four seconds. Paying that at wake time means the
     * acknowledgement arrives *after* the user's request has already been
     * spoken — and the request is then thrown away with the echo guard.
     * Paying it here costs nothing: the engine is idle until it is needed.
     */
    private fun prewarmTts() {
        scope.launch {
            val speaker = com.openminis.app.speech.TextToSpeechManager()
            speaker.init(applicationContext)
            tts = speaker
            val ready = withContext(Dispatchers.IO) { speaker.awaitReady() }
            AppLogger.info(TAG, "TTS 预热完成 ready=$ready")
        }
    }

    /**
     * Load the 28 MB speaker model while waiting for a wake word.
     *
     * Wake-time verification sits between the keyword and "我在"; loading
     * the model on that path would add seconds to the first wake after
     * every service start. Only prewarm when a template exists — otherwise
     * the first wake is enrolling, not verifying.
     */
    private fun prewarmVoiceprint() {
        if (!WakeWordSettings.voiceprintEnabled.value) return
        if (!voiceprint.hasTemplate()) return
        scope.launch(Dispatchers.IO) {
            val ready = voiceprint.ensureReady()
            AppLogger.info(TAG, "声纹模型预热完成 ready=$ready")
        }
    }

    /**
     * Say "我在" so the user knows the assistant is listening.
     *
     * The old behaviour was a 120 ms beep from `ToneGenerator`. A word is
     * unmistakable — you can tell from the next room whether the wake
     * word was heard — and it is spoken by the same engine that will
     * answer, so it also proves the TTS works before the request starts.
     */
    private suspend fun acknowledge(): Boolean {
        if (!WakeWordSettings.toneEnabled.value) return false
        val speaker = tts ?: com.openminis.app.speech.TextToSpeechManager().also {
            it.init(applicationContext)
            tts = it
        }
        val text = runCatching { getString(R.string.voice_wake_acknowledgement) }
            .getOrDefault(DEFAULT_ACKNOWLEDGEMENT)
        ackText = text
        ackStartedAt = SystemClock.elapsedRealtime()
        ackPlaying = true
        AppLogger.info(TAG, "开始播报「$text」")
        val spoken = runCatching { WakeConversation.speakQuietly(speaker, text) }
            .getOrElse {
                AppLogger.info(TAG, "唤醒回应失败：${it.message}")
                false
            }
        ackPlaying = false
        ackEndedAt = SystemClock.elapsedRealtime()
        // The room keeps ringing for a moment after the engine stops;
        // anything the microphone picked up until now is the assistant's
        // own voice.
        echoDeadline = ackEndedAt + ECHO_TAIL_MS
        AppLogger.info(TAG, "播报结束 spoken=$spoken 用时=${ackEndedAt - ackStartedAt}ms")
        // The listening window starts when the assistant can actually hear
        // the user: the acknowledgement itself is not listening time.
        if (conversationState == ConversationState.LISTENING) {
            conversationDeadline = ackEndedAt + CONVERSATION_WINDOW_MS
        }
        return spoken
    }

    private fun installVad() {
        val detector = WakeEnergyVad(
            onSpeechStart = {
                AppLogger.info(TAG, "检测到说话")
                VoiceWakeState.trace(this, "VAD 开始说话")
            },
            onSegment = { pcm ->
                AppLogger.info(TAG, "VAD 切出一句 ${pcm.size * 1000 / WakeAudioCapture.SAMPLE_RATE}ms")
                VoiceWakeState.trace(this, "VAD 切句 ${pcm.size * 1000 / WakeAudioCapture.SAMPLE_RATE}ms")
                scope.launch { onUtterance(pcm) }
            },
            onEvent = { line ->
                AppLogger.info(TAG, line)
                VoiceWakeState.trace(this, line)
            },
        )
        vad = detector
        engine?.onFrame = { frame, len -> detector.feed(frame, len) }
    }

    private fun removeVad() {
        engine?.onFrame = null
        vad?.reset()
        vad = null
    }

    /**
     * Listen for "停一下 / 别说了" while a reply is playing.
     *
     * The microphone stays open during the reply (the engine is not stopped),
     * so the only missing piece is a VAD on the same frames. Barge-in cuts
     * shorter than a normal request: the audio is dominated by the
     * assistant's own voice, and the user expects the interruption to land
     * within a second or two.
     */
    private fun installBargeInVad(reply: String) {
        val detector = WakeEnergyVad(
            onSpeechStart = {},
            onSegment = { pcm ->
                scope.launch { onBargeInSegment(pcm, reply) }
            },
            minSilenceMs = { BARGE_IN_SILENCE_MS },
            maxSegmentMs = { BARGE_IN_MAX_SEGMENT_MS },
        )
        bargeInVad = detector
        engine?.onFrame = { frame, len -> detector.feed(frame, len) }
    }

    private fun removeBargeInVad() {
        if (bargeInVad != null) engine?.onFrame = null
        bargeInVad?.reset()
        bargeInVad = null
    }

    /**
     * A segment captured while the assistant was speaking.
     *
     * The assistant's own voice is in the same microphone stream, so a
     * plain transcript is not enough: the match only acts on a short
     * command, and a transcript that is merely a fragment of the reply is
     * ignored as echo.
     */
    private suspend fun onBargeInSegment(pcm: ShortArray, reply: String) {
        if (conversationState != ConversationState.REPLYING || destroying) return
        if (!VoiceOutputState.isSpeaking.value || bargeInBusy) return
        bargeInBusy = true
        try {
            // No voiceprint gate here: the segment is a mixture of the
            // assistant's own voice and the user talking over it, and a
            // stop command is a local control, not a request that needs the
            // owner's identity. Echo is rejected by the reply-substring
            // check below instead.
            val raw = transcribeOffline(pcm) ?: return
            val text = WakeEcho.strip(
                raw,
                WakeWordSettings.wakeWordList(),
                ack = ackText,
            ) ?: return
            if (text.isBlank()) return
            val normalized = WakeStopPhrase.normalize(text)
            if (normalized.length >= 2 &&
                WakeStopPhrase.normalize(reply).contains(normalized)
            ) {
                AppLogger.info(TAG, "忽略播报回声「$text」")
                return
            }
            if (!WakeStopPhrase.matches(text)) {
                AppLogger.info(TAG, "播报中听到「$text」，非停止指令")
                return
            }
            AppLogger.info(TAG, "检测到语音停止指令「$text」，中止播报")
            VoiceWakeState.trace(this, "停止播报：$text")
            stopSpeakingNow()
        } finally {
            bargeInBusy = false
        }
    }

    /**
     * Silence the reply. [handleUtterance] notices [speechStopRequested] when
     * its blocking speak returns and goes straight back to listening without
     * the usual echo guard.
     */
    private fun stopSpeakingNow() {
        speechStopRequested = true
        runCatching { tts?.stop() }
        VoiceOutputState.isSpeaking.value = false
        AppLogger.info(TAG, "停止播报")
    }

    /**
     * One tick of the conversation state machine.
     *
     * The window that closes the conversation only runs while we are waiting
     * for the user: while the assistant is transcribing, thinking or talking,
     * the clock does not matter.
     */
    private fun handleConversationTick(
        now: Long,
        enabled: Boolean,
        permission: Boolean,
    ) {
        // One diagnostic line per second: without it there is no way to tell
        // "the microphone was deaf" from "the user did not speak" from
        // "the voiceprint rejected the sentence" once the log has rotated.
        if (now - lastDiagAt >= 1_000L) {
            lastDiagAt = now
            val detector = vad
            val line = buildString {
                append("状态=")
                append(conversationState.name)
                append(" 麦克风=")
                append("%.0f".format(detector?.lastRms ?: 0.0))
                append(" VAD峰值=")
                append("%.0f".format(detector?.peakRms ?: 0.0))
                append(" 阈值=")
                append("%.0f".format(detector?.threshold ?: 0.0))
                append(" 底噪=")
                append("%.0f".format(detector?.floor ?: 0.0))
                append(if (detector?.isInSpeech == true) " 正在说话" else " 静音")
                append(" 帧=")
                append(detector?.frameCount ?: 0)
                append(" 剩余=")
                append(((conversationDeadline - now) / 1000).coerceAtLeast(0))
                append("s")
            }
            AppLogger.info(TAG, line)
            VoiceWakeState.trace(this, line)
        }
        when {
            !enabled -> {
                abortConversation("功能已关闭")
                stopEngine()
                publish(VoiceWakePhase.OFF, "语音唤醒已关闭")
            }
            !permission -> {
                abortConversation("麦克风权限被收回")
                publish(VoiceWakePhase.ERROR, "需要麦克风权限")
            }
            conversationState == ConversationState.LISTENING && now > conversationDeadline -> {
                // The window closed, but the VAD may still be holding an
                // unfinished utterance — the user spoke without the pause
                // that cuts a sentence. Push it out instead of throwing it
                // away together with the segment.
                if (vad?.flush() == true) {
                    conversationDeadline = now + FLUSH_GRACE_MS
                    AppLogger.info(TAG, "窗口到点，但 VAD 里还有半句，先处理")
                } else {
                    sleepConversation()
                }
            }
        }
    }

    /**
     * A finished utterance.
     *
     * The voiceprint decides whether this was the owner. A stranger's
     * sentence is dropped and the assistant keeps waiting — if nothing
     * passes the gate before the window closes, it goes back to sleep.
     */
    private suspend fun onUtterance(pcm: ShortArray) {
        if (conversationState != ConversationState.LISTENING || destroying) return
        // One turn at a time: the transcription takes a second or two, and a
        // second segment cut in the meantime would race it for the recogniser.
        // Queue it instead of dropping it: the first segment is usually the
        // assistant's own "我在", and the queued one is the actual request.
        if (utteranceBusy) {
            if (pendingUtterances.size >= MAX_PENDING_UTTERANCES) {
                pendingUtterances.removeFirst()
            }
            pendingUtterances.addLast(pcm)
            AppLogger.info(TAG, "上一句还在处理，先排队（${pendingUtterances.size}）")
            VoiceWakeState.trace(this, "语音排队 ${pendingUtterances.size}")
            return
        }
        utteranceBusy = true
        try {
            var segment: ShortArray? = pcm
            while (segment != null &&
                conversationState == ConversationState.LISTENING &&
                !destroying
            ) {
                handleUtterance(segment)
                segment = if (conversationState == ConversationState.LISTENING) {
                    pendingUtterances.removeFirstOrNull()
                } else {
                    null
                }
            }
        } finally {
            utteranceBusy = false
            // Anything left was captured for a state that no longer listens.
            pendingUtterances.clear()
        }
    }

    private suspend fun handleUtterance(pcm: ShortArray) {
        if (conversationState != ConversationState.LISTENING || destroying) return
        val now = SystemClock.elapsedRealtime()
        // The segment was cut while the assistant was still saying "我在" —
        // the audio carries the assistant's own voice.
        //
        // It used to be thrown away here, and that is exactly why "唤醒后说
        // 话没有反应": the user starts talking while the acknowledgement is
        // still in the air, so the *request* was the segment that got
        // dropped. Now the segment is transcribed, the echo is stripped from
        // the transcript, and only a segment that contains nothing but the
        // echo is ignored.
        // `echoDeadline` is only known once the acknowledgement has ended;
        // a segment cut *while* it is still playing (the user starts talking
        // over "我在") must be in the echo window too. Otherwise the
        // assistant's own words are sent back to it as a request.
        val overlapsAck = ackStartedAt > 0L && (ackPlaying || now < echoDeadline)
        val raw = transcribeOffline(pcm)
        if (raw.isNullOrBlank()) {
            AppLogger.info(TAG, "识别结果为空，继续聆听")
            VoiceWakeState.trace(this, "识别结果为空，继续聆听")
            // The VAD is still armed and the state is still LISTENING, so
            // the next sentence is picked up right away. The window is NOT
            // renewed: an empty transcript is not a voice, so the original
            // deadline stands ("8 秒内没有通过声纹的语音就休眠").
            return
        }
        val withoutWake = WakeEcho.strip(raw, WakeWordSettings.wakeWordList())
        if (overlapsAck &&
            (WakeEcho.isOnly(raw, ackText) || WakeEcho.isOnly(withoutWake, ackText))
        ) {
            AppLogger.info(TAG, "只有助手自己的回应「$raw」，继续聆听")
            VoiceWakeState.trace(this, "只有助手自己的回应")
            return
        }
        if (!passesVoiceprint(pcm)) {
            AppLogger.info(TAG, "声纹不通过，忽略这句话")
            VoiceWakeState.trace(this, "声纹不通过，忽略")
            publish(VoiceWakePhase.LISTENING, "等待本人说话")
            return
        }

        conversationState = ConversationState.REPLYING
        // A request was accepted; queued segments were captured for the
        // listening phase and must not be answered after this turn.
        pendingUtterances.clear()
        removeVad()
        publish(VoiceWakePhase.REPLYING, "识别中")

        val text = WakeEcho.strip(raw, WakeWordSettings.wakeWordList(), ack = ackText)
        // The app may have come to the foreground (or the feature was
        // switched off) while the transcription was running.
        if (conversationState != ConversationState.REPLYING) return
        if (text.isNullOrBlank()) {
            AppLogger.info(TAG, "识别结果为空，继续聆听")
            resumeListening(renewWindow = false)
            return
        }
        AppLogger.info(TAG, "识别结果：$text")
        VoiceWakeState.trace(this, "识别：$text")

        // 如果当前有一张本地写操作确认卡，这句话可能是「确认/取消」。
        // 必须先问桥：否则唤醒链路会把它当成新消息发到语音会话，卡片仍挂着。
        val bridgeResolution = com.openminis.app.assistant.execution.LocalConfirmationBridge
            .resolve(text)
        if (bridgeResolution != null) {
            // 结果播报由卡片所在会话负责（「已创建日程…」「已取消」）。
            // 这里只给一声很短的应答，避免两个 TTS 实例同时说话。
            val phrase = when (bridgeResolution) {
                com.openminis.app.assistant.execution.LocalConfirmationBridge.Resolution.CONFIRM ->
                    "好"
                com.openminis.app.assistant.execution.LocalConfirmationBridge.Resolution.CANCEL ->
                    null
            }
            AppLogger.info(TAG, "确认卡由唤醒语音处理：$bridgeResolution")
            VoiceWakeState.trace(this, "确认卡：$bridgeResolution")
            publish(VoiceWakePhase.REPLYING, if (phrase != null) "已确认" else "已取消")
            if (phrase != null) {
                val speaker = tts ?: com.openminis.app.speech.TextToSpeechManager().also {
                    it.init(applicationContext)
                    tts = it
                }
                WakeConversation.speakQuietly(speaker, phrase)
            }
            if (conversationState == ConversationState.REPLYING) {
                delay(ECHO_GUARD_MS)
                resumeListening()
            }
            return
        }

        val sessionId = runCatching { WakeConversation.ensureSession(this) }.getOrNull()
        if (sessionId == null) {
            AppLogger.info(TAG, "无法创建语音会话，放弃本轮")
            sleepConversation()
            return
        }

        publish(VoiceWakePhase.REPLYING, "思考中")
        val reply = WakeConversation.ask(this, sessionId, text)
        if (conversationState != ConversationState.REPLYING) return
        if (reply.isNullOrBlank()) {
            AppLogger.info(TAG, "本轮没有拿到回复")
            VoiceWakeState.trace(this, "本轮没有回复")
            sleepConversation()
            return
        }

        publish(VoiceWakePhase.REPLYING, "回复中")
        VoiceWakeState.trace(this, "回复：${reply.take(80)}")
        val speaker = tts ?: com.openminis.app.speech.TextToSpeechManager().also {
            it.init(applicationContext)
            tts = it
        }
        replyText = reply
        speechStopRequested = false
        installBargeInVad(reply)
        val spoke = WakeConversation.speak(speaker, reply)
        removeBargeInVad()
        if (!spoke) {
            AppLogger.info(TAG, "回复未能播报，继续聆听")
            VoiceWakeState.trace(this, "回复未能播报")
        }
        if (conversationState != ConversationState.REPLYING) return

        // The window is renewed after the reply: the user may well have
        // more to say.
        if (speechStopRequested) {
            AppLogger.info(TAG, "播报被用户中止，直接回到聆听")
        } else {
            delay(ECHO_GUARD_MS)
        }
        resumeListening()
    }

    private fun resumeListening() {
        resumeListening(renewWindow = true)
    }

    /**
     * Back to listening.
     *
     * @param renewWindow when false the original 8 s deadline stands — the
     *   turn produced nothing, so the conversation must still end there.
     */
    private fun resumeListening(renewWindow: Boolean) {
        if (conversationState != ConversationState.REPLYING) return
        conversationState = ConversationState.LISTENING
        if (renewWindow) {
            conversationDeadline = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
        }
        installVad()
        publish(VoiceWakePhase.LISTENING, "正在聆听")
    }

    /**
     * The voiceprint gate.
     *
     * @return true when the utterance may be acted on. A gate that cannot
     *   run (no model, no template, audio too short) lets the utterance
     *   through — a soft gate must never lock the owner out.
     */
    private fun passesVoiceprint(pcm: ShortArray): Boolean {
        if (!WakeWordSettings.voiceprintEnabled.value) return true
        val gate = voiceprint
        if (!gate.hasTemplate()) {
            // First utterance: it becomes the owner template.
            gate.enrollIfAbsent(pcm, ENROLL_MIN_SECONDS)
            return true
        }
        val score = gate.verify(pcm) ?: return true
        val threshold = WakeWordSettings.voiceprintThreshold.value
        val ok = score >= threshold
        AppLogger.info(
            TAG,
            "声纹分数 ${"%.3f".format(score)} 阈值 ${"%.2f".format(threshold)} → " +
                (if (ok) "通过" else "拒绝"),
        )
        VoiceWakeState.trace(
            this,
            "声纹 ${"%.3f".format(score)} 阈值 ${"%.2f".format(threshold)} " +
                (if (ok) "通过" else "拒绝"),
        )
        return ok
    }

    /** Recognise the recorded request offline — no microphone needed. */
    private suspend fun transcribeOffline(pcm: ShortArray): String? {
        val wav = WavEncoder.encode(pcm)
        var attempt = 0
        while (attempt < 3) {
            val result = kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
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
            // The recogniser refuses a second transcription while the
            // previous one has not settled yet. Wait for it and try again —
            // otherwise the request is silently lost and the assistant looks
            // unresponsive.
            if (SpeechRecognitionManager.state.value == RecognitionState.IDLE) return null
            AppLogger.info(TAG, "识别引擎忙，稍后重试")
            delay(400)
            attempt++
        }
        return null
    }

    /** The listening window closed without an accepted utterance: sleep. */
    private fun sleepConversation() {
        if (conversationState == ConversationState.IDLE) return
        conversationState = ConversationState.IDLE
        removeVad()
        removeBargeInVad()
        engine?.setDetecting(true)
        publish(VoiceWakePhase.WAITING, "等待唤醒词「${WakeWordSettings.wakeWord.value}」")
        AppLogger.info(TAG, "8 秒内没有通过声纹的语音，回到待命")
    }

    /** Something interrupted the conversation (in-app voice input, disabled). */
    private fun abortConversation(reason: String) {
        if (conversationState == ConversationState.IDLE) return
        val wasReplying = conversationState == ConversationState.REPLYING
        conversationState = ConversationState.IDLE
        removeVad()
        removeBargeInVad()
        if (wasReplying) stopSpeakingNow()
        engine?.setDetecting(true)
        AppLogger.info(TAG, "结束语音对话：$reason")
    }


    // ---------------------------------------------------------------- notification

    private fun publish(phase: VoiceWakePhase, detail: String) {
        VoiceWakeState.update(this, phase, detail)
    }

    private fun startForegroundSafely(notification: Notification): Boolean {
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            foregroundStarted = true
            true
        } catch (t: Throwable) {
            AppLogger.info(TAG, "startForeground 失败：${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun buildNotification(): Notification {
        val snapshot = VoiceWakeState.state.value
        val text = when (snapshot.phase) {
            VoiceWakePhase.WAITING -> getString(R.string.voice_wake_notification_waiting, WakeWordSettings.wakeWord.value)
            VoiceWakePhase.WAKING -> getString(R.string.voice_wake_notification_waking)
            VoiceWakePhase.LISTENING -> getString(R.string.voice_wake_notification_listening)
            VoiceWakePhase.REPLYING -> getString(R.string.voice_wake_notification_replying)
            VoiceWakePhase.PAUSED -> snapshot.detail.ifBlank { getString(R.string.voice_wake_notification_paused) }
            VoiceWakePhase.ERROR -> snapshot.detail.ifBlank { getString(R.string.voice_wake_state_error) }
            VoiceWakePhase.OFF -> getString(R.string.voice_wake_state_off)
        }
        lastNotificationText = text
        val sessionId = WakeWordSettings.voiceSessionId.value
        val openUri = if (sessionId.isNullOrBlank()) {
            "minis://action/new_chat"
        } else {
            "minis://session/$sessionId"
        }
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(openUri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.voice_wake_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.voice_wake_notification_stop), stopPending)
            .build()
    }

    private fun updateNotification(snapshot: VoiceWakeSnapshot) {
        if (destroying || !foregroundStarted) return
        val text = when (snapshot.phase) {
            VoiceWakePhase.WAITING -> getString(R.string.voice_wake_notification_waiting, WakeWordSettings.wakeWord.value)
            VoiceWakePhase.WAKING -> getString(R.string.voice_wake_notification_waking)
            VoiceWakePhase.LISTENING -> getString(R.string.voice_wake_notification_listening)
            VoiceWakePhase.REPLYING -> getString(R.string.voice_wake_notification_replying)
            VoiceWakePhase.PAUSED -> snapshot.detail.ifBlank { getString(R.string.voice_wake_notification_paused) }
            VoiceWakePhase.ERROR -> snapshot.detail.ifBlank { getString(R.string.voice_wake_state_error) }
            VoiceWakePhase.OFF -> getString(R.string.voice_wake_state_off)
        }
        if (text == lastNotificationText) return
        lastNotificationText = text
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.voice_wake_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.voice_wake_notification_channel_desc)
                    setShowBadge(false)
                },
            )
        }
        if (nm.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    getString(R.string.voice_wake_alert_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = getString(R.string.voice_wake_alert_channel)
                    setShowBadge(false)
                    setSound(null, null)
                },
            )
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minis:voice-wake").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { AppLogger.info(TAG, "WakeLock 获取失败：${it.message}") }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }
}
