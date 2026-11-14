package com.openminis.app.assistant.execution

import android.os.Handler
import android.os.Looper
import com.openminis.app.logging.AppLogger

/**
 * 待确认的本地写操作与语音唤醒之间的桥。
 *
 * WHY: 确认卡属于用户当前打开的那个聊天会话，而语音唤醒一直在自己的
 * 「语音对话」会话里跑。用户看到卡片后喊唤醒词说「确认」，唤醒链路会把
 * 这句话当成一条新的聊天消息发到语音会话，卡片却还挂着，执行结果也落到
 * 错误的会话里。
 *
 * 这里让 ChatViewModel 在弹卡时注册一对回调；唤醒服务在把转写结果交给
 * 云端之前先问一次桥：这句话是不是在回答那张卡？是就只执行确认/取消，
 * 不再新开会话。
 */
object LocalConfirmationBridge {

    enum class Resolution { CONFIRM, CANCEL }

    private data class Pending(
        val id: String,
        val sessionId: String,
        val confirm: () -> Unit,
        val cancel: () -> Unit,
    )

    private const val TAG = "LocalConfirmBridge"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var pending: Pending? = null

    fun register(
        id: String,
        sessionId: String,
        confirm: () -> Unit,
        cancel: () -> Unit,
    ) {
        pending = Pending(id, sessionId, confirm, cancel)
    }

    fun unregister(id: String) {
        if (pending?.id == id) pending = null
    }

    fun hasPending(): Boolean = pending != null

    /**
     * 尝试用 [text] 回答当前待确认的卡片。
     *
     * @return CONFIRM/CANCEL 表示已把结果交给卡片所在会话；null 表示没有
     *   待确认卡片，或这句话不是确认/取消（继续走正常聊天）。
     */
    fun resolve(text: String): Resolution? {
        val current = pending ?: return null
        if (!LocalActionSpeech.isDirectAnswer(text)) return null
        val decision = LocalActionSpeech.decision(text)
        val resolution = when (decision) {
            VoiceConfirmationDecision.CONFIRM -> Resolution.CONFIRM
            VoiceConfirmationDecision.CANCEL -> Resolution.CANCEL
            VoiceConfirmationDecision.UNCLEAR -> return null
        }
        // 先摘掉，保证界面上的语音确认与唤醒链路同时听到「确认」时只执行一次。
        pending = null
        AppLogger.info(
            TAG,
            "resolved $resolution from voice wake session=${current.sessionId}",
        )
        val callback = when (resolution) {
            Resolution.CONFIRM -> current.confirm
            Resolution.CANCEL -> current.cancel
        }
        mainHandler.post(callback)
        return resolution
    }
}
