package com.openminis.app.ui.chat.voice

import android.content.Context
import com.openminis.app.R
import com.openminis.app.assistant.execution.LocalActionSpeech
import com.openminis.app.assistant.execution.VoiceConfirmationDecision
import com.openminis.app.i18n.LocaleWrap
import com.openminis.app.logging.AppLogger
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.TextToSpeechManager
import com.openminis.app.speech.wake.WakeWordService
import com.openminis.app.ui.chat.LocalActionConfirmation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 语音确认当前处于哪一步。卡片据此显示「正在聆听 / 正在播报」，
 * 否则麦克风悄悄打开，用户根本不知道可以说话。
 */
enum class VoiceConfirmPhase { IDLE, LISTENING, SPEAKING }

/**
 * 本地写操作的语音确认。
 *
 * 确认卡负责「看得见」，这里负责「说得通」：
 *  1. 卡片一出现立刻用 TTS 播报要确认的动作（语音场景不用先看屏幕）；
 *  2. 播报结束后自动开麦，用户直接说「确认/取消」，不需要唤醒词；
 *     输入框的流光和卡片上的脉冲提示表示正在聆听；
 *  3. 肯定 → [onDecision] true（ViewModel 执行并收起卡片）；
 *     否定 → false（取消）；没听清 → 再问一次，仍不明白就把卡片留给手指。
 *
 * 多候选（同名日程）不在这里选：语音挑不出具体哪一条，只念一句提示让用户
 * 在屏幕上点。
 *
 * 任何一步失败（TTS 未就绪、识别器不可用、没有麦克风权限）都静默退回确认卡，
 * 这条语音链路是可选的辅助，不是执行的前置条件。
 */
class LocalActionVoiceConfirmer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _phase = MutableStateFlow(VoiceConfirmPhase.IDLE)
    /** 供确认卡显示状态；IDLE 表示语音确认没有在跑（或已失败退回卡片）。 */
    val phase: StateFlow<VoiceConfirmPhase> = _phase.asStateFlow()

    /** 只在识别器确实由本类打开时，停止时才去取消，避免误停用户的语音面板。 */
    private var capturing = false

    private val tts by lazy {
        TextToSpeechManager().also { it.init(appContext) }
    }

    /**
     * 开始一次语音确认。回调在主线程触发；[onDecision] 为 true 表示用户确认。
     */
    fun start(action: LocalActionConfirmation, onDecision: (Boolean) -> Unit) {
        stop()
        // 先按“正在播报”点亮：run() 会立刻开始 TTS，避免先闪一下“正在聆听”。
        _phase.value = VoiceConfirmPhase.SPEAKING
        com.openminis.app.llm.mnn.AssistantFastPath.trace(
            appContext,
            "voice: start ${action.capability}/${action.action} choices=${action.choices.size}",
        )
        job = scope.launch {
            try {
                com.openminis.app.llm.mnn.AssistantFastPath.trace(appContext, "voice: run")
                run(action, onDecision)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                com.openminis.app.llm.mnn.AssistantFastPath.trace(
                    appContext,
                    "voice: failed ${t.javaClass.simpleName}: ${t.message}",
                )
                AppLogger.warning(
                    TAG,
                    "voice confirmation failed: ${t.javaClass.simpleName}: ${t.message}",
                )
            } finally {
                capturing = false
                _phase.value = VoiceConfirmPhase.IDLE
                runCatching { tts.stop() }
            }
        }
    }

    /** 用户改用手势、或确认卡已关闭：立刻停止播报与识别。 */
    fun stop() {
        job?.cancel()
        job = null
        _phase.value = VoiceConfirmPhase.IDLE
        if (capturing) {
            capturing = false
            runCatching { SpeechRecognitionManager.cancelRecording() }
        }
        runCatching { tts.stop() }
    }

    /** ViewModel 销毁时调用。 */
    fun release() {
        stop()
        announceJob?.cancel()
        announceJob = null
        scope.cancel()
        runCatching { tts.shutdown() }
    }

    /**
     * 执行完成后简短播报结果（语音场景闭环）。
     * 不阻塞调用方；TTS 不可用时静默跳过。
     */
    fun announceCompletion(
        capability: String,
        action: String,
        fallbackLabel: String,
        args: org.json.JSONObject,
        success: Boolean,
        output: String,
    ) {
        val chinese = LocaleWrap.wrap(appContext).resources.configuration.locales[0]
            .language.startsWith("zh")
        announce(
            LocalActionSpeech.completion(
                capability = capability,
                action = action,
                chinese = chinese,
                fallbackLabel = fallbackLabel,
                args = args,
                success = success,
                output = output,
            ),
        )
    }

    fun announceCancelled() {
        val chinese = LocaleWrap.wrap(appContext).resources.configuration.locales[0]
            .language.startsWith("zh")
        announce(if (chinese) "已取消" else "Cancelled")
    }

    private var announceJob: Job? = null

    private fun announce(text: String) {
        if (text.isBlank()) return
        announceJob?.cancel()
        announceJob = scope.launch {
            try {
                if (!tts.awaitReady()) {
                    AppLogger.info(TAG, "announce skipped (tts unavailable): $text")
                    return@launch
                }
                // 稍等一拍：唤醒确认的短应答可能还在播，避免两个 TTS 实例抢话。
                delay(ANNOUNCE_DELAY_MS)
                AppLogger.info(TAG, "announce: $text")
                com.openminis.app.llm.mnn.AssistantFastPath.trace(appContext, "voice: announce $text")
                tts.speak(text)
                AppLogger.info(TAG, "announce started speaking=${tts.isSpeaking.value}")
                withTimeoutOrNull(TTS_TIMEOUT_MS) {
                    while (tts.isSpeaking.value && isActive) delay(80)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "announce failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private suspend fun run(action: LocalActionConfirmation, onDecision: (Boolean) -> Unit) {
        val resources = LocaleWrap.wrap(appContext).resources
        if (action.choices.isNotEmpty()) {
            _phase.value = VoiceConfirmPhase.SPEAKING
            speak(resources.getString(R.string.assistant_voice_confirm_choices))
            return
        }

        val chinese = resources.configuration.locales[0].language.startsWith("zh")
        val subject = LocalActionSpeech.subject(
            capability = action.capability,
            action = action.action,
            chinese = chinese,
            fallbackLabel = action.title,
        )
        // 只念动作名，不念参数：卡片上已经写清楚了，短提示能更快进入第二段
        // 聆听，也减少 TTS 尾音被识别成回答的机会。
        val prompt = resources.getString(R.string.assistant_voice_confirm_prompt, subject)

        // 卡片一出现就播报：这条链路大部分是语音场景，用户可能没看屏幕。
        // 播报完再开麦（尾音会污染识别），卡片上的动态提示负责说明正在听。
        AppLogger.info(TAG, "voice confirmation: speaking prompt immediately")
        _phase.value = VoiceConfirmPhase.SPEAKING
        val spoke = speak(prompt)
        if (!spoke) {
            // TTS 不可用：至少把麦克风打开，用户仍可以说「确认/取消」，
            // 输入框的动态光效会提示正在聆听。
            AppLogger.info(TAG, "voice confirmation: prompt not spoken; listening anyway")
            _phase.value = VoiceConfirmPhase.LISTENING
            if (!handle(listenOnce(LISTEN_WINDOW_MS), onDecision)) {
                AppLogger.info(TAG, "voice confirmation: no usable answer, leaving the card open")
            }
            return
        }
        // 让扬声器的尾音散掉再开麦，否则识别器会把刚播报的「确认」当成回答。
        delay(ECHO_GUARD_MS)
        _phase.value = VoiceConfirmPhase.LISTENING
        if (handle(listenOnce(LISTEN_WINDOW_MS), onDecision)) return

        // 第一遍没听清：再问一次。仍然不明白就把卡片留给手指。
        AppLogger.info(TAG, "voice confirmation: retry prompt")
        _phase.value = VoiceConfirmPhase.SPEAKING
        if (speak(resources.getString(R.string.assistant_voice_confirm_retry))) {
            delay(ECHO_GUARD_MS)
            _phase.value = VoiceConfirmPhase.LISTENING
            if (!handle(listenOnce(LISTEN_WINDOW_MS), onDecision)) {
                AppLogger.info(TAG, "voice confirmation: no usable answer, leaving the card open")
            }
        }
    }

    /** @return true 表示已经做出确认/取消决定。 */
    private fun handle(heard: String?, onDecision: (Boolean) -> Unit): Boolean {
        com.openminis.app.llm.mnn.AssistantFastPath.trace(
            appContext,
            "voice: heard ${heard?.take(40) ?: "null"}",
        )
        if (heard.isNullOrBlank()) return false
        return when (LocalActionSpeech.decision(heard)) {
            VoiceConfirmationDecision.CONFIRM -> {
                AppLogger.info(TAG, "voice confirmation: confirm 「${heard.take(40)}」")
                onDecision(true)
                true
            }
            VoiceConfirmationDecision.CANCEL -> {
                AppLogger.info(TAG, "voice confirmation: cancel 「${heard.take(40)}」")
                onDecision(false)
                true
            }
            VoiceConfirmationDecision.UNCLEAR -> {
                AppLogger.info(TAG, "voice confirmation: unclear 「${heard.take(40)}」")
                false
            }
        }
    }

    private suspend fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        val ready = tts.awaitReady()
        com.openminis.app.llm.mnn.AssistantFastPath.trace(appContext, "voice: tts-ready=$ready")
        AppLogger.info(TAG, "voice confirmation: tts ready=$ready")
        if (!ready) return false
        tts.speak(text)
        if (!tts.isSpeaking.value) {
            com.openminis.app.llm.mnn.AssistantFastPath.trace(
                appContext,
                "voice: tts-did-not-start len=${text.length}",
            )
            AppLogger.info(TAG, "voice confirmation: tts did not start (len=${text.length})")
            return false
        }
        val finished = withTimeoutOrNull(TTS_TIMEOUT_MS) {
            while (tts.isSpeaking.value && isActive) delay(80)
            true
        } == true
        if (!finished) tts.stop()
        return finished
    }

    /**
     * 听一句回答。系统识别器会自己断句；端侧 SenseVoice 只在 stop() 时
     * 才转写，所以等满窗口后主动收一次。
     */
    private suspend fun listenOnce(windowMs: Long): String? {
        com.openminis.app.llm.mnn.AssistantFastPath.trace(
            appContext,
            "voice: listen state=${SpeechRecognitionManager.state.value} " +
                "available=${SpeechRecognitionManager.isAvailable.value} window=$windowMs",
        )
        if (!SpeechRecognitionManager.isAvailable.value) return null
        if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) return null

        val result = CompletableDeferred<Pair<Boolean, String?>>()
        capturing = true
        // 唤醒服务一直握着 AudioRecord；先确定性地让它松手，否则卡片一出现
        // 就开麦会和它抢麦克风（和 HoldToTalkController 的做法一致）。
        // stopEngine 会 join 采集线程，放到 IO 上，避免卡主线程。
        withContext(Dispatchers.IO) { WakeWordService.pauseForInAppCapture() }
        try {
            SpeechRecognitionManager.startRecording(
                onPartialOrFinal = { text, isFinal ->
                    if (isFinal) result.complete(true to text)
                },
                onError = { error, message ->
                    AppLogger.info(TAG, "voice confirmation ASR error: $error ${message.orEmpty()}")
                    result.complete(false to null)
                },
            )
            if (result.isCompleted) return result.await().second
            if (SpeechRecognitionManager.state.value == RecognitionState.IDLE) {
                // startRecording 拒绝了本次请求（没有引擎 / 正在被占用）。
                return null
            }

            withTimeoutOrNull(windowMs) { result.await() }?.let {
                return it.second
            }

            // 本地引擎需要显式 stop 才会出最终文本。stop() 会 join 采集线程，
            // 放到 IO 上做，避免在流式回复时卡主线程（实测 146ms）。
            withContext(Dispatchers.IO) { SpeechRecognitionManager.stopRecording() }
            val finalized = withTimeoutOrNull(TRANSCRIBE_WINDOW_MS) { result.await() }
            if (finalized == null) {
                runCatching { SpeechRecognitionManager.cancelRecording() }
                return null
            }
            return finalized.second
        } finally {
            capturing = false
            WakeWordService.resumeAfterInAppCapture()
        }
    }

    companion object {
        private const val TAG = "VoiceConfirm"

        /** 播报上限：确认提示最多二十来秒，失控时也要把麦克风还回去。 */
        private const val TTS_TIMEOUT_MS = 20_000L

        /** 播报提示后的聆听窗口：一句话足够；超时后主动 stop 转写。 */
        private const val LISTEN_WINDOW_MS = 5_000L

        /** stop 之后等待端侧模型出字的时间。 */
        private const val TRANSCRIBE_WINDOW_MS = 8_000L

        /** 播报结束到开麦之间的静默，避开扬声器尾音。 */
        private const val ECHO_GUARD_MS = 1_500L

        /** 执行完成播报前的缓冲：让唤醒确认的短应答先说完。 */
        private const val ANNOUNCE_DELAY_MS = 1_200L
    }
}
