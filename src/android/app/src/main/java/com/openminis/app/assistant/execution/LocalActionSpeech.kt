package com.openminis.app.assistant.execution

import org.json.JSONObject
import java.util.Locale

/** 用户对语音确认提示的回答。 */
enum class VoiceConfirmationDecision { CONFIRM, CANCEL, UNCLEAR }

/**
 * 本地写操作确认卡的语音文案与回答解析。
 *
 * WHY: 确认卡只解决了「看得见」的问题。用户通过语音发起一条写操作时，
 * 还要抬头看屏幕、再点按钮；语音确认让整条链路闭环：先念出要做什么，
 * 听到「确认」就执行并收起卡片，听到「取消」就放弃。
 *
 * 这里只放纯函数，方便单测；TTS / ASR 的编排在
 * [com.openminis.app.ui.chat.voice.LocalActionVoiceConfirmer]。
 */
object LocalActionSpeech {

    /**
     * 播报用的动作名。英文界面直接沿用路由 label；中文界面用短中文，
     * 避免中文 TTS 念一长串英文路由名。
     */
    fun subject(
        capability: String,
        action: String,
        chinese: Boolean,
        fallbackLabel: String,
    ): String {
        if (!chinese) return fallbackLabel.ifBlank { "$capability/$action" }
        if (capability == "device") return "查看设备状态"
        if (capability == "media") return "控制播放"
        return when (capability to action) {
            "calendar" to "create" -> "创建日程"
            "calendar" to "update" -> "修改日程"
            "calendar" to "delete" -> "删除日程"
            "calendar" to "list" -> "查看日程"
            "calendar" to "freebusy" -> "查询空闲时间"
            "alarm" to "set" -> "设置闹钟"
            "alarm" to "timer" -> "开始计时器"
            "alarm" to "open" -> "打开时钟"
            "reminder" to "schedule" -> "设置提醒"
            "reminder" to "pending", "reminder" to "list" -> "查看提醒"
            "reminder" to "cancel" -> "取消提醒"
            "note" to "create" -> "新建笔记"
            "note" to "search" -> "搜索笔记"
            "note" to "list" -> "查看笔记"
            "note" to "delete" -> "删除笔记"
            "settings" to "open" -> "打开设置"
            "settings" to "set" -> "修改系统设置"
            "scheduled_task" to "create" -> "新建定时任务"
            "scheduled_task" to "list" -> "查看定时任务"
            "scheduled_task" to "cancel" -> "取消定时任务"
            "weather" to "current" -> "查询天气"
            "contacts" to "dial" -> "拨打电话"
            "contacts" to "search" -> "搜索联系人"
            "app_launch" to "open" -> "打开应用"
            "shell_execute" to "run" -> "运行命令"
            "file_read" to "read" -> "读取文件"
            "file_write" to "write" -> "写入文件"
            "file_edit" to "edit" -> "修改文件"
            "browser_use" to "navigate" -> "打开网页"
            "memory_write" to "write" -> "保存记忆"
            "memory_get" to "search" -> "查询记忆"
            "hub_call" to "call" -> "远程执行"
            "read_image" to "read" -> "读取图片"
            "amap_search" to "search" -> "搜索地点"
            else -> fallbackLabel.ifBlank { "$capability/$action" }
        }
    }

    /**
     * 解析一句话里的确认/取消意图。
     *
     * 先算两侧再统一判断：同时包含确认与取消词的句子返回 UNCLEAR，而不是
     * 让「取消」赢。这样既不会把「确认取消」这种歧义回答执行掉，也不会把
     * TTS 提示的尾音（「…请说确认或取消」）误判成取消。
     * 短回答（好/嗯/对）只在整句就是它们时才算肯定，避免「好麻烦」。
     */
    fun decision(text: String): VoiceConfirmationDecision {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return VoiceConfirmationDecision.UNCLEAR

        val cancel = normalized in CANCEL_EXACT ||
            CANCEL_CONTAINS.any { normalized.contains(it) }
        if (!cancel && normalized in CONFIRM_EXACT) {
            return VoiceConfirmationDecision.CONFIRM
        }
        // 先剥掉取消词再找肯定词：「不确认」剥完是空句，应该是取消；
        // 「确认取消」剥完还剩「确认」，两句并存，才判为歧义。
        var remainder = normalized
        if (cancel) {
            CANCEL_CONTAINS.sortedByDescending { it.length }.forEach {
                remainder = remainder.replace(it, "")
            }
        }
        val confirm = normalized in CONFIRM_EXACT ||
            (remainder.isNotEmpty() && CONFIRM_CONTAINS.any { remainder.contains(it) })
        return when {
            cancel && confirm -> VoiceConfirmationDecision.UNCLEAR
            cancel -> VoiceConfirmationDecision.CANCEL
            confirm -> VoiceConfirmationDecision.CONFIRM
            else -> VoiceConfirmationDecision.UNCLEAR
        }
    }

    /**
     * 这句话是否**几乎只是**在回答确认提示。
     *
     * 用于拦截键盘输入和语音唤醒：待确认时用户可能说「确认」，也可能开始
     * 一句新请求（「确认一下明天的日程」）。前者应当落到卡片上，后者必须
     * 照常发给模型，所以只接受短回答或整句确认词。
     */
    fun isDirectAnswer(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        if (normalized in CONFIRM_EXACT || normalized in CANCEL_EXACT) return true
        return normalized.length <= 6 && decision(text) != VoiceConfirmationDecision.UNCLEAR
    }

    /**
     * 执行完成后的一句话播报。短、只说结果：成功报动作和目标，失败报首行错误。
     */
    fun completion(
        capability: String,
        action: String,
        chinese: Boolean,
        fallbackLabel: String,
        args: JSONObject,
        success: Boolean,
        output: String,
    ): String {
        if (!success) {
            val detail = output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
                .take(60)
            return if (chinese) {
                "操作失败" + if (detail.isBlank()) "" else "：$detail"
            } else {
                "Action failed" + if (detail.isBlank()) "" else ": $detail"
            }
        }
        val target = args.optString("title").ifBlank {
            args.optString("name").ifBlank {
                args.optString("app").ifBlank { args.optString("id_or_title") }
            }
        }.trim().take(30)
        return if (chinese) {
            "已" + subject(capability, action, chinese = true, fallbackLabel = fallbackLabel) +
                if (target.isBlank()) "" else "：$target"
        } else {
            "Done: $fallbackLabel" + if (target.isBlank()) "" else " ($target)"
        }
    }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace(Regex("""[\s，。！？、；：,.!?;:'"“”‘’]"""), "")

    private val CANCEL_EXACT = setOf(
        "不", "不要", "不用", "不是", "别", "取消", "算了",
        "no", "nope", "cancel", "stop",
    )

    private val CANCEL_CONTAINS = setOf(
        "取消", "不要", "不用", "算了", "别执行", "别了", "不执行", "不确认", "先不",
        "不用了", "不要了", "不可以", "不行", "不同意", "不是", "停", "cancel", "stop",
        "don't", "dont", "nothanks", "nope",
    )

    private val CONFIRM_EXACT = setOf(
        "好", "嗯", "对", "是", "是的", "好的", "可以", "确定", "确认", "执行",
        "yes", "y", "ok", "okay", "sure", "confirm",
    )

    private val CONFIRM_CONTAINS = setOf(
        "确认", "确定", "执行吧", "执行", "可以", "好的", "是的", "没问题", "同意",
        "就这么办", "yesplease", "yesdoit", "okay", "confirm", "doit", "goahead",
        "proceed", "runit", "surething",
    )
}
