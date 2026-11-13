package com.openminis.app.speech.wake

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.speech.TextToSpeechManager
import com.openminis.app.speech.VoiceOutputState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * The background conversation: hear the request, answer it out loud.
 *
 * The wake pipeline never opens an Activity — the user stays wherever they
 * were. That means the turn has to run without the UI: the prompt goes
 * through the same headless funnel the CLI / RPC / scheduled jobs use
 * ([HeadlessChatRunner]), and the reply is spoken by a private
 * [TextToSpeechManager] instead of the read-aloud capsule.
 *
 * The conversation lives in one persistent session so consecutive wake
 * cycles keep their context ("what's the weather" → "and tomorrow?").
 */
internal object WakeConversation {

    private const val TAG = "VoiceWake"
    private const val SESSION_TITLE = "语音对话"

    /** How long the agent gets to answer before we give up on this turn. */
    private const val REPLY_TIMEOUT_MS = 90_000L

    /** Longer replies are trimmed — nobody wants a five-minute monologue. */
    private const val MAX_SPEECH_CHARS = 600

    /**
     * The session backing the background conversation.
     *
     * Created on first use and then reused, so the wake conversation keeps
     * one continuous context.
     */
    suspend fun ensureSession(context: Context): String {
        val app = context.applicationContext as? MinisApp
            ?: throw IllegalStateException("MinisApp 未初始化")
        val cached = WakeWordSettings.voiceSessionId.value
        if (!cached.isNullOrBlank()) {
            val exists = withContext(Dispatchers.IO) {
                app.chatRepository.getSession(cached) != null
            }
            if (exists) return cached
            AppLogger.info(TAG, "语音会话已失效，重新创建")
        }
        return withContext(Dispatchers.IO) {
            val modelId = app.providerRepository.allVisibleEntries()
                .firstOrNull()?.baseModel?.id ?: "unknown"
            val session = app.chatRepository.createSession(
                modelId = modelId,
                title = SESSION_TITLE,
            )
            app.chatRepository.dao.updateSource(session.id, "voice")
            // Bind to the user's default model group, the same way a new
            // chat started by hand would.
            runCatching { HeadlessChatRunner.applyModelOverride(app, session.id, null, null) }
            WakeWordSettings.setVoiceSessionId(app, session.id)
            AppLogger.info(TAG, "已创建语音会话 ${session.id}")
            session.id
        }
    }

    /**
     * Run one turn and return the agent's reply.
     *
     * @return the reply text, or null when the turn failed or timed out.
     */
    suspend fun ask(context: Context, sessionId: String, text: String): String? {
        val app = context.applicationContext as? MinisApp ?: return null
        val result = runCatching {
            HeadlessChatRunner.prompt(
                context = app,
                sessionId = sessionId,
                text = text,
                wait = true,
                timeoutMs = REPLY_TIMEOUT_MS,
            )
        }.getOrElse {
            AppLogger.info(TAG, "语音回合失败：${it.javaClass.simpleName}: ${it.message}")
            return null
        }
        AppLogger.info(TAG, "语音回合 ${result.status} replyChars=${result.responseText?.length ?: 0}")
        if (result.status != "Completed") return null
        return result.responseText?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Speak [text] and return when it finished.
     *
     * The global [VoiceOutputState] is mirrored so the rest of the app
     * (the read-aloud capsule, the mic-capture suppression) knows audio is
     * playing.
     */
    suspend fun speak(tts: TextToSpeechManager, text: String): Boolean {
        val spoken = text.take(MAX_SPEECH_CHARS)
        if (spoken.isBlank()) return false
        if (!tts.awaitReady()) {
            AppLogger.info(TAG, "TTS 未就绪，跳过播报（chars=${spoken.length}）")
            return false
        }
        VoiceOutputState.isSpeaking.value = true
        try {
            tts.speak(spoken)
            // Wait for the engine to finish so the caller keeps the
            // microphone closed until the assistant stops talking.
            val finished = awaitSpeechEnd(tts, spoken)
            if (!finished) {
                AppLogger.info(TAG, "播报超时，主动停止（chars=${spoken.length}）")
                tts.stop()
            }
            return finished
        } finally {
            VoiceOutputState.isSpeaking.value = false
        }
    }

    /**
     * Speak a short acknowledgement **without** touching the global
     * read-aloud state.
     *
     * The wake acknowledgement ("我在") is not a reply: raising the app's
     * read-aloud flag would pop the capsule over whatever the user was
     * doing. It is spoken *before* the request is recorded, so the caller
     * keeps the VAD closed until this returns.
     */
    suspend fun speakQuietly(tts: TextToSpeechManager, text: String): Boolean {
        if (text.isBlank()) return false
        if (!tts.awaitReady()) {
            AppLogger.info(TAG, "TTS 未就绪，跳过唤醒回应")
            return false
        }
        tts.speak(text)
        val finished = awaitSpeechEnd(tts, text)
        if (!finished) {
            AppLogger.info(TAG, "唤醒回应播报超时，主动停止")
            tts.stop()
        }
        return finished
    }

    /**
     * Wait until the engine stops speaking, but never forever.
     *
     * `TextToSpeechManager.speak()` can buffer while the engine is still
     * binding.  If the engine never finishes initialising, `isSpeaking` stays
     * true and the old unbounded loop kept the service in REPLYING with no
     * answer and no way back to listening.
     */
    private suspend fun awaitSpeechEnd(tts: TextToSpeechManager, text: String): Boolean {
        val budget = (text.length * 180L + 10_000L).coerceIn(15_000L, 120_000L)
        return withTimeoutOrNull(budget) {
            while (tts.isSpeaking.value) delay(120)
            true
        } == true
    }
}
