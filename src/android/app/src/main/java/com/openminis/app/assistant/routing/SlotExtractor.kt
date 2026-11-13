package com.openminis.app.assistant.routing

import com.openminis.app.assistant.routing.ChineseTimeParser.RepeatMode
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

data class SlotExtraction(
    val args: JSONObject,
    val missing: Set<String>,
    val summary: String,
    val parsedTime: ChineseTimeParser.Parsed? = null,
    val ambiguous: Boolean = false,
)

/**
 * Deterministic slot extraction. This class never calls a model: wording,
 * paths, note bodies and reminder titles are taken from the user's original
 * text, while dates and durations come from [ChineseTimeParser].
 */
class SlotExtractor(
    private val nowProvider: () -> LocalDateTime = { LocalDateTime.now() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val pathRegex = Regex("""/(?:[A-Za-z0-9_.\-~]+/)*[A-Za-z0-9_.\-~]+""")
    private val urlRegex = Regex("""https?://\S+|\bwww\.[A-Za-z0-9.\-]+\S*""")
    private val numberIdRegex = Regex("""(?:id|编号|序号)\s*[:：]?\s*(\d+)""", RegexOption.IGNORE_CASE)

    fun extract(userText: String, route: AssistantRoute): SlotExtraction {
        val text = AssistantRouteMatcher.normalizeTextPreservingCase(userText)
        val raw = normalizeContent(userText)
        val args = JSONObject()
        val parsed = ChineseTimeParser.parse(text, nowProvider(), zone)
        val now = nowProvider()

        when (route.capability) {
            "calendar" -> extractCalendar(text, route.action, parsed, args)
            "alarm" -> extractAlarm(text, route.action, parsed, args)
            "reminder" -> extractReminder(text, raw, route.action, parsed, now, args)
            "note" -> extractNote(text, raw, route.action, args)
            "settings" -> extractSettings(text, route.action, args)
            "scheduled_task" -> extractScheduled(text, route.action, parsed, now, args)
            "weather" -> Unit
            "device" -> extractDevice(text, route.action, args)
            "media" -> extractMedia(text, route.action, args)
            "contacts" -> extractContacts(text, route.action, args)
            "app_launch" -> extractApp(text, route.action, args)
            "shell_execute" -> extractShell(raw, args)
            "file_read", "read_image" -> pathRegex.find(text)?.let { args.put("path", it.value) }
            "file_write" -> {
                pathRegex.find(text)?.let { args.put("path", it.value) }
                extractWriteContent(raw, args)
            }
            "browser_use" -> urlRegex.find(text)?.let {
                args.put("action", "navigate")
                args.put("url", it.value)
            }
            "memory_write" -> extractAfterTrigger(
                raw,
                listOf("记住", "记一下", "存进记忆", "别忘了", "remember that", "remember"),
            )?.let { args.put("content", it) }
            "memory_get" -> extractAfterTrigger(
                raw,
                listOf("我说过", "我的记忆", "之前说过", "查记忆", "what did i say", "my memory"),
            )?.let { args.put("keywords", it) }
            "amap_search" -> args.put("query", extractPlaceQuery(text))
            "file_edit" -> {
                pathRegex.find(text)?.let { args.put("path", it.value) }
            }
        }

        args.optString("tool_title").takeIf { it.isNotBlank() }?.let { }
        if (!args.has("tool_title") || args.optString("tool_title").isBlank()) {
            val title = titleFor(route, args, text)
            args.put("tool_title", title)
        }

        val spec = LocalCapabilityRegistry.action(route.capability, route.action)
        val missing = spec?.requiredSlots.orEmpty()
            .filterTo(linkedSetOf()) { args.optString(it).isBlank() }
        val ambiguousTime = parsed?.ambiguous == true ||
            (parsed != null && parsed.confidence < 0.8)
        return SlotExtraction(
            args = args,
            missing = missing,
            summary = summaryFor(route, args),
            parsedTime = parsed,
            ambiguous = ambiguousTime,
        )
    }

    private fun extractCalendar(
        text: String,
        action: String,
        parsed: ChineseTimeParser.Parsed?,
        args: JSONObject,
    ) {
        when (action) {
            "list" -> {
                val start = parsed?.dateTime?.toLocalDate()?.atStartOfDay()
                    ?: nowProvider().toLocalDate().atStartOfDay()
                val end = if (parsed?.dateTime != null) {
                    start.plusDays(1)
                } else {
                    start.plusDays(7)
                }
                args.put("start", start.format(ISO))
                args.put("end", end.format(ISO))
            }
            "freebusy" -> {
                val start = parsed?.dateTime ?: nowProvider()
                args.put("start", start.format(ISO))
                args.put("end", start.plusHours(8).format(ISO))
            }
            "create" -> {
                if (parsed != null) {
                    args.put("start", parsed.iso())
                    val end = if (parsed.allDay) {
                        parsed.dateTime.toLocalDate().plusDays(1).atStartOfDay()
                    } else {
                        parsed.dateTime.plusHours(1)
                    }
                    args.put("end", end.format(ISO))
                    args.put("allDay", parsed.allDay)
                    if (parsed.repeat == RepeatMode.DAILY) args.put("repeat", "DAILY")
                }
                args.put("title", calendarTitle(text))
                locationFrom(text)?.let { args.put("location", it) }
                Regex("""提前\s*(\d{1,3})\s*分钟""").find(text)?.let {
                    args.put("alarmMinutes", it.groupValues[1].toInt())
                }
            }
            "delete" -> {
                numberIdRegex.find(text)?.let { args.put("id", it.groupValues[1]) }
                args.put("id_or_title", calendarTitle(text))
            }
            "update" -> {
                numberIdRegex.find(text)?.let { args.put("id", it.groupValues[1]) }
                val rename = Regex(
                    """^(.+?)(?:改名为|改名成|标题改为|名称改为|改成|改为|rename\s+to)\s*(.+)$""",
                    RegexOption.IGNORE_CASE,
                ).find(text)
                val queryText = if (rename != null) rename.groupValues[1] else text
                args.put("id_or_title", calendarTitle(queryText))
                val renamedTitle = rename?.groupValues?.get(2)
                    ?.trim()
                    ?.trim('，', ',', '。', '.', ':', '：', '"', '“', '”')
                    ?.takeIf { it.isNotBlank() }
                if (renamedTitle != null &&
                    ChineseTimeParser.stripTimeExpressions(renamedTitle).isNotBlank()
                ) {
                    args.put("title", renamedTitle.take(80))
                }
                parsed?.let {
                    args.put("start", it.iso())
                    args.put("end", it.dateTime.plusHours(1).format(ISO))
                }
            }
        }
    }

    private fun extractAlarm(
        text: String,
        action: String,
        parsed: ChineseTimeParser.Parsed?,
        args: JSONObject,
    ) {
        when (action) {
            "set" -> {
                parsed?.let {
                    // The Clock handler accepts ISO and can therefore detect
                    // an explicit date that does not match "the next HH:mm".
                    // Passing only HH:mm silently turned "today 7am" (already
                    // past) or "next Friday 7am" into the wrong day.
                    args.put("time", it.iso())
                    args.put("repeat", when (it.repeat) {
                        RepeatMode.DAILY -> "DAILY"
                        RepeatMode.WEEKDAYS -> "WEEKDAYS"
                        else -> "ONCE"
                    })
                    args.put("targetDate", it.dateIso())
                }
                args.put("label", alarmLabel(text))
            }
            "timer" -> {
                val duration = ChineseTimeParser.parseDuration(text)
                if (duration != null) {
                    args.put("duration", duration.seconds)
                    args.put("durationText", duration.matchedText)
                }
                args.put("label", timerLabel(text))
            }
            "open" -> Unit
        }
    }

    private fun extractReminder(
        text: String,
        raw: String,
        action: String,
        parsed: ChineseTimeParser.Parsed?,
        now: LocalDateTime,
        args: JSONObject,
    ) {
        when (action) {
            "schedule" -> {
                if (parsed != null) {
                    if (parsed.relative) {
                        val seconds = ChineseTimeParser.secondsUntil(parsed.dateTime, now)
                        args.put("after", seconds)
                    } else {
                        args.put("at", parsed.iso())
                    }
                }
                args.put("title", reminderTitle(text))
                args.put("body", raw)
            }
            "cancel" -> {
                numberIdRegex.find(text)?.let { args.put("id", it.groupValues[1]) }
                if (!args.has("id")) {
                    extractAfterTrigger(text, listOf("取消提醒", "删掉提醒", "cancel reminder"))
                        ?.let { args.put("id", it) }
                }
            }
        }
    }

    private fun extractNote(text: String, raw: String, action: String, args: JSONObject) {
        when (action) {
            "create" -> {
                val body = noteBody(raw)
                if (body.isNotBlank()) {
                    args.put("content", body)
                    args.put("title", body.lineSequence().firstOrNull().orEmpty().take(40))
                }
            }
            "search" -> extractAfterTrigger(
                text,
                listOf("搜笔记", "找笔记", "查找笔记", "搜索笔记", "search notes", "find note"),
            )?.let { args.put("query", it) }
            "delete" -> {
                numberIdRegex.find(text)?.let { args.put("id", it.groupValues[1]) }
                if (!args.has("id")) {
                    extractAfterTrigger(text, listOf("删除笔记", "删掉笔记", "delete note"))
                        ?.let { args.put("id_or_query", it) }
                }
            }
        }
    }

    private fun extractSettings(text: String, action: String, args: JSONObject) {
        val target = detectSettingsTarget(text)
        val lower = text.lowercase(Locale.ROOT)
        if (action == "open") {
            if (target != null) args.put("target", target)
            return
        }
        if (target != null) args.put("target", target)
        val value = when (target) {
            "brightness" -> Regex("""(?:亮度|brightness).{0,8}?(\d{1,3})\s*%?""")
                .find(text)?.groupValues?.get(1)
            "screen_timeout" -> Regex("""(\d{1,4})\s*(秒|分钟|minute|second)""")
                .find(text)?.let { m ->
                    val n = m.groupValues[1].toInt()
                    if (m.groupValues[2].contains("分") || m.groupValues[2].startsWith("minute")) {
                        (n * 60).toString()
                    } else {
                        n.toString()
                    }
                }
            "rotation" -> if (lower.contains("关闭") || lower.contains("锁定") || lower.contains("off")) "off" else "on"
            else -> when {
                lower.contains("打开") || lower.contains("开启") || lower.contains("turn on") ||
                    lower.contains("enable") -> "on"
                lower.contains("关闭") || lower.contains("关掉") || lower.contains("turn off") ||
                    lower.contains("disable") -> "off"
                lower.contains("勿扰") -> "on"
                else -> null
            }
        }
        value?.let { args.put("value", it) }
    }

    private fun extractScheduled(
        text: String,
        action: String,
        parsed: ChineseTimeParser.Parsed?,
        now: LocalDateTime,
        args: JSONObject,
    ) {
        when (action) {
            "create" -> {
                parsed?.let {
                    if (it.relative) {
                        args.put("after", ChineseTimeParser.secondsUntil(it.dateTime, now))
                    } else {
                        args.put("at", it.iso())
                        args.put("hour", it.dateTime.hour)
                        args.put("minute", it.dateTime.minute)
                    }
                    args.put("repeat", it.repeat.name)
                }
                args.put("prompt", scheduledPrompt(text))
                args.put("label", scheduledPrompt(text).take(40))
            }
            "cancel" -> {
                numberIdRegex.find(text)?.let { args.put("id", it.groupValues[1]) }
                if (!args.has("id")) {
                    extractAfterTrigger(text, listOf("取消定时任务", "删掉定时任务", "cancel scheduled task"))
                        ?.let { args.put("id", it) }
                }
            }
        }
    }

    private fun extractDevice(text: String, action: String, args: JSONObject) {
        if (action == "info") Unit
        if (text.contains("内存") || text.contains("memory", ignoreCase = true)) {
            args.put("includeMemory", true)
        }
    }

    private fun extractMedia(text: String, action: String, args: JSONObject) {
        val lower = text.lowercase(Locale.ROOT)
        val resolved = when {
            lower.contains("继续播放") || lower.contains("恢复播放") || lower.contains("resume") -> "resume"
            lower.contains("停止播放") || lower.contains("stop") -> "stop"
            lower.contains("状态") || lower.contains("status") -> "status"
            lower.contains("列表") || lower.contains("list") -> "list"
            else -> action
        }
        args.put("action", resolved)
        Regex(
            """(?:会话|session)\s*[:：]?\s*([A-Za-z0-9_.-]+)""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let {
            args.put("session", it.groupValues[1])
        }
    }

    private fun extractContacts(text: String, action: String, args: JSONObject) {
        val triggers = if (action == "search") {
            listOf("搜索联系人", "查找联系人", "找联系人", "search contacts", "find contact")
        } else {
            listOf("打电话给", "拨打", "call", "给")
        }
        val name = extractAfterTrigger(text, triggers)
            ?.trim()
            ?.trim('。', '.', ',', '，', '!', '！')
            ?.replace(Regex("""(?:打电话|电话|call)$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
        if (!name.isNullOrBlank()) {
            args.put("name", name)
            args.put("query", name)
        }
        args.put("action", action)
        if (action == "dial" && Regex(
                """(?:直接|立刻|马上|现在)\s*(?:拨打|打|呼叫)|(?:call|dial)\s+now""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(text)
        ) {
            args.put("directCall", true)
        }
    }

    private fun extractApp(text: String, action: String, args: JSONObject) {
        val app = extractAfterTrigger(
            text,
            listOf("打开应用", "启动应用", "启动app", "打开app", "launch app", "打开", "启动", "launch"),
        )?.replace(Regex("""^(?:应用|app)\s*""", RegexOption.IGNORE_CASE), "")
            ?.trim()
        if (!app.isNullOrBlank()) args.put("app", app)
        args.put("action", action)
    }

    private fun extractShell(text: String, args: JSONObject) {
        var s = text
        val prefixes = listOf(
            "运行命令", "执行命令", "运行一下", "执行一下", "跑一下", "跑个",
            "运行", "执行", "在终端里", "终端", "shell", "bash", "cmd",
            "run command", "execute",
        )
        prefixes.forEach { s = s.replaceFirst(Regex("""^\s*${Regex.escape(it)}\s*""", RegexOption.IGNORE_CASE), "") }
        s = s.trim().trimStart(':', '：', ',', '，').trim()
        s = s.removeSuffix("命令").removeSuffix("脚本").trim()
        if (s.isNotEmpty() && s.all { it.code < 128 }) args.put("command", s)
    }

    private fun extractWriteContent(text: String, args: JSONObject) {
        Regex(
            """(?:内容为|内容是|写入|写|save)\s+(.+?)\s+(?:到|to)\s+/""",
            RegexOption.IGNORE_CASE,
        )
            .find(text)?.let { args.put("content", it.groupValues[1].trim()) }
        if (!args.has("content")) {
            Regex("""(?:把|将)\s+(.+?)\s+(?:写入|写到|保存到|存到)""")
                .find(text)?.let { args.put("content", it.groupValues[1].trim()) }
        }
    }

    private fun calendarTitle(text: String): String {
        var t = ChineseTimeParser.stripTimeExpressions(text)
        t = extractAfterTrigger(
            t,
            listOf(
                "安排日程", "创建日程", "新建日程", "添加日程", "加个会议", "安排会议",
                "删除日程", "取消日程", "删掉会议", "取消会议", "修改日程", "更新日程",
                "删除", "取消", "修改", "更新", "安排", "创建", "新建", "添加", "加个",
                "calendar add", "schedule event", "delete event", "cancel meeting",
                "update event", "reschedule meeting", "schedule", "calendar",
            ),
        ).orEmpty()
        t = t.replace(Regex("""^(?:一个|个|下|一下)\s*"""), "")
        t = t.replace(Regex("""^(?:a|an|the)\s+""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""\s+(?:at|on|for)\s*$""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""(?:的)?(?:日程|事件)$"""), "")
        return t.trim().trim('，', ',', '。', '.', ':', '：').ifBlank { "日程" }.take(80)
    }

    private fun alarmLabel(text: String): String {
        var t = ChineseTimeParser.stripTimeExpressions(text)
        listOf("设个闹钟", "设一个闹钟", "设置闹钟", "定个闹钟", "闹钟", "叫我起床", "叫我起来", "叫醒我", "我记得", "alarm", "wake me up")
            .forEach { t = t.replace(it, " ", ignoreCase = true) }
        return t.replace(Regex("""\s+"""), " ").trim().ifBlank { "闹钟" }.take(60)
    }

    private fun timerLabel(text: String): String {
        var t = text
        ChineseTimeParser.parseDuration(text)?.matchedText?.let { t = t.replace(it, " ") }
        listOf("开始一个", "开始", "计时器", "倒计时", "计时", "timer", "countdown")
            .forEach { t = t.replace(it, " ", ignoreCase = true) }
        return t.replace(Regex("""\s+"""), " ").trim().ifBlank { "计时器" }.take(60)
    }

    private fun reminderTitle(text: String): String {
        var t = ChineseTimeParser.stripTimeExpressions(text)
        listOf("记得提醒我", "提醒我", "提醒一下", "到点提醒", "记得提醒", "remind me to", "remind me", "reminder")
            .forEach { t = t.replace(it, " ", ignoreCase = true) }
        t = t.replace(Regex("""^(?:要|去|一下|我)\s*"""), "")
        t = t.replace(Regex("""^in\s+""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""^to\s+""", RegexOption.IGNORE_CASE), "")
        return t.replace(Regex("""\s+"""), " ").trim()
            .trim('，', ',', '。', '.', ':', '：')
            .ifBlank { "提醒" }
            .take(80)
    }

    private fun noteBody(text: String): String {
        val colon = text.indexOfFirst { it == ':' || it == '：' }
        if (colon >= 0 && colon < text.length - 1) {
            return text.substring(colon + 1).trim()
        }
        var t = text
        listOf("记个笔记", "记笔记", "做个笔记", "新建笔记", "创建笔记", "记事本", "备忘", "take a note", "note this")
            .forEach { t = t.replaceFirst(it, "", ignoreCase = true) }
        return t.trim().trim('，', ',', '。', '.', ':', '：')
    }

    private fun scheduledPrompt(text: String): String {
        var t = ChineseTimeParser.stripTimeExpressions(text)
        listOf(
            "创建定时任务", "新建定时任务", "定时任务", "定时帮我", "定时", "每天帮我", "每日帮我",
            "每周帮我", "工作日帮我", "帮我", "替我", "请", "schedule task", "every day help me",
        ).forEach { t = t.replace(it, " ", ignoreCase = true) }
        return t.replace(Regex("""\s+"""), " ").trim().ifBlank { text.take(120) }
    }

    private fun detectSettingsTarget(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("wifi") || lower.contains("无线网") || lower.contains("wi-fi") -> "wifi"
            lower.contains("蓝牙") || lower.contains("bluetooth") -> "bluetooth"
            lower.contains("飞行模式") || lower.contains("airplane") -> "airplane"
            lower.contains("亮度") || lower.contains("brightness") -> "brightness"
            lower.contains("息屏") || lower.contains("屏幕超时") ||
                lower.contains("screen timeout") -> "screen_timeout"
            lower.contains("旋转") || lower.contains("rotation") -> "rotation"
            lower.contains("勿扰") || lower.contains("do not disturb") || lower.contains("dnd") -> "dnd"
            lower.contains("电池") || lower.contains("battery") -> "battery"
            lower.contains("显示") || lower.contains("display") -> "display"
            lower.contains("声音") || lower.contains("sound") -> "sound"
            lower.contains("位置") || lower.contains("定位") || lower.contains("location") -> "location"
            lower.contains("通知") || lower.contains("notification") -> "notifications"
            lower.contains("无障碍") || lower.contains("accessibility") -> "accessibility"
            lower.contains("应用详情") || lower.contains("app info") -> "app"
            lower.contains("设置") || lower.contains("settings") -> "settings"
            else -> null
        }
    }

    private fun extractPlaceQuery(text: String): String {
        var t = text
        listOf("附近", "周边", "周围", "旁边", "最近的", "找一家", "找个", "有哪些", "在哪有", "有没有", "地址是", "怎么去", "nearby", "near me")
            .forEach { t = t.replace(it, " ", ignoreCase = true) }
        t = t.replace(Regex("""(?:吗|呢|？|\?|的|有)"""), " ")
        return t.replace(Regex("""\s+"""), " ").trim().ifBlank { text.take(60) }
    }

    private fun extractAfterTrigger(text: String, triggers: List<String>): String? {
        for (trigger in triggers.sortedByDescending { it.length }) {
            val index = text.indexOf(trigger, ignoreCase = true)
            if (index >= 0) {
                val start = index + trigger.length
                val tail = text.substring(start)
                    .trim()
                    .trimStart(':', '：', ',', '，', '。', '.', '-', ' ')
                if (tail.isNotBlank()) return tail
            }
        }
        return null
    }

    private fun normalizeContent(input: String): String =
        Normalizer.normalize(input, Normalizer.Form.NFKC)
            .replace('\u3000', ' ')
            .trim()

    private fun locationFrom(text: String): String? {
        val m = Regex(
            """(?:地点|location)\s*[:：]?\s*(.{1,40}?)(?=\s*(?:提前|提醒|alarm)|[,，。;；]|$)""",
            RegexOption.IGNORE_CASE,
        )
            .find(text)
        return m?.groupValues?.get(1)?.trim()
    }

    private fun titleFor(route: AssistantRoute, args: JSONObject, text: String): String {
        val key = args.optString("title").ifBlank {
            args.optString("path").ifBlank {
                args.optString("url").ifBlank {
                    args.optString("command").ifBlank {
                        args.optString("content").ifBlank {
                            args.optString("query").ifBlank {
                                args.optString("name").ifBlank {
                                    args.optString("app")
                                }
                            }
                        }
                    }
                }
            }
        }
        return if (key.isBlank()) route.label else "${route.label}: ${key.replace(Regex("\\s+"), " ").take(32)}"
    }

    private fun summaryFor(route: AssistantRoute, args: JSONObject): String {
        val target = args.optString("title").ifBlank {
            args.optString("label").ifBlank {
                args.optString("content").ifBlank {
                    args.optString("query").ifBlank {
                        args.optString("name").ifBlank {
                            args.optString("app").ifBlank {
                                args.optString("path").ifBlank { args.optString("url") }
                            }
                        }
                    }
                }
            }
        }
        val whenText = args.optString("start").ifBlank {
            args.optString("time").ifBlank {
                args.optString("at").ifBlank {
                    args.optString("durationText")
                }
            }
        }
        return listOf(route.label, target, whenText)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .take(160)
    }

    companion object {
        private val ISO = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
