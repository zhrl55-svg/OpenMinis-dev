package com.openminis.app.assistant.execution

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.openminis.app.assistant.notes.NotesRepository
import com.openminis.app.assistant.routing.RouteDecision
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.offload.MediaPlayerManager
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.offload.AlarmOffloadHandler
import com.openminis.app.sandbox.offload.AlarmTimeParser
import com.openminis.app.sandbox.offload.CalendarOffloadHandler
import com.openminis.app.sandbox.offload.ContactsOffloadHandler
import com.openminis.app.sandbox.offload.DeviceOffloadHandler
import com.openminis.app.sandbox.offload.LocationOffloadHandler
import com.openminis.app.sandbox.offload.NotificationOffloadHandler
import com.openminis.app.sandbox.offload.OpenOffloadHandler
import com.openminis.app.sandbox.offload.PlayerOffloadHandler
import com.openminis.app.sandbox.offload.WeatherOffloadHandler
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTriggerKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class LocalExecutionResult(
    val output: String,
    val success: Boolean,
    val toolName: String,
    val title: String,
    /**
     * 助手气泡里显示的正文。空串表示只显示标题。
     * 由 [LocalResultFormatter] 生成：JSON 结果翻成一句话，纯文本原样显示。
     */
    val answer: String = "",
    /**
     * 只读信息类结果的简短语音摘要（新闻标题、指数行情）。
     * 空串表示不自动播报；不影响气泡里的完整 [answer]。
     */
    val spokenSummary: String = "",
)

/**
 * A preflight result for a local write. Calendar update/delete must resolve a
 * title to one event before the confirmation card is shown; a broad title is
 * returned as a choice rather than letting the executor pick the first row.
 */
data class LocalRoutePreparation(
    val decision: RouteDecision.Local,
    val choices: List<CalendarEventCandidate> = emptyList(),
    val totalChoices: Int = choices.size,
    val error: String? = null,
)

/**
 * Executes only the local life capabilities. Existing agent tools continue to
 * use ChatViewModel.executeTool so their block and permission behaviour stays
 * byte-for-byte compatible.
 */
class LocalCapabilityExecutor(private val context: Context) {

    private val notes = NotesRepository.get(context)
    private val scheduled = ScheduledTaskManager(context)

    suspend fun prepare(decision: RouteDecision.Local): LocalRoutePreparation =
        withContext(Dispatchers.IO) {
            if (decision.capability != "calendar" ||
                decision.action !in setOf("update", "delete") ||
                decision.args.has("id")
            ) {
                return@withContext LocalRoutePreparation(decision)
            }
            val query = decision.args.optString("id_or_title")
                .ifBlank { decision.args.optString("title") }
                .trim()
            if (query.isBlank()) {
                return@withContext LocalRoutePreparation(
                    decision,
                    error = "没有找到要操作的日程名称，未执行。",
                )
            }

            val start = LocalDateTime.now().minusYears(1)
            val end = LocalDateTime.now().plusYears(1)
            val result = offload(
                "android-calendar",
                CalendarOffloadHandler(context),
                listOf(
                    "android-calendar", "list",
                    "--start", start.format(ISO),
                    "--end", end.format(ISO),
                    "--limit", "100",
                ),
            )
            if (result.exitCode != 0) {
                return@withContext LocalRoutePreparation(
                    decision,
                    error = "查询日程失败，未执行：${result.output.trim()}",
                )
            }
            val events = CalendarCandidateMatcher.parseEvents(result.output)
            return@withContext when (val match = CalendarCandidateMatcher.match(events, query)) {
                is CalendarMatchResult.Found -> {
                    val args = JSONObject(decision.args.toString()).put("id", match.event.id.toString())
                    LocalRoutePreparation(decision.copy(args = args))
                }
                is CalendarMatchResult.Multiple -> LocalRoutePreparation(
                    decision = decision,
                    choices = match.events,
                    totalChoices = match.total,
                )
                CalendarMatchResult.None -> LocalRoutePreparation(
                    decision,
                    error = "没有找到匹配“$query”的日程，未执行。",
                )
            }
        }

    suspend fun execute(
        decision: RouteDecision.Local,
        sessionId: String,
    ): LocalExecutionResult = withContext(Dispatchers.IO) {
        val args = decision.args
        val result = try {
            when (decision.capability) {
                "calendar" -> calendar(decision.action, args)
                "alarm" -> alarm(decision.action, args)
                "reminder" -> reminder(decision.action, args, sessionId)
                "note" -> note(decision.action, args)
                "settings" -> settings(decision.action, args)
                "scheduled_task" -> scheduledTask(decision.action, args, sessionId)
                "weather" -> weather(decision.action, args, sessionId)
                "device" -> device(decision.action, args)
                "media" -> media(decision.action, args, decision.route.label)
                "contacts" -> contacts(decision.action, args)
                "app_launch" -> appLaunch(decision.action, args)
                else -> NativeOffloadResult(2, "Unsupported local capability: ${decision.capability}")
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "${decision.capability}/${decision.action} failed: ${t.message}")
            NativeOffloadResult(1, "本地执行失败：${t.message ?: t.javaClass.simpleName}")
        }
        val success = result.exitCode == 0
        val answer = LocalResultFormatter.answer(
            capability = decision.capability,
            action = decision.action,
            output = result.output,
            success = success,
        )
        LocalExecutionResult(
            output = result.output.trim(),
            success = success,
            toolName = decision.capability,
            title = decision.args.optString("tool_title").ifBlank { decision.route.label },
            answer = answer,
            spokenSummary = LocalSpokenSummary.forCapability(
                capability = decision.capability,
                action = decision.action,
                output = result.output,
                answer = answer,
                success = success,
            ),
        )
    }

    private fun calendar(action: String, args: JSONObject): NativeOffloadResult {
        when (action) {
            "list" -> {
                val argv = mutableListOf("android-calendar", "list")
                args.optString("start").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--start", it)
                }
                args.optString("end").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--end", it)
                }
                argv += listOf("--limit", "100")
                return offload("android-calendar", CalendarOffloadHandler(context), argv)
            }
            "freebusy" -> {
                val argv = mutableListOf("android-calendar", "freebusy")
                args.optString("start").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--start", it)
                }
                args.optString("end").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--end", it)
                }
                return offload("android-calendar", CalendarOffloadHandler(context), argv)
            }
            "create" -> {
                val argv = mutableListOf(
                    "android-calendar", "create",
                    "--title", args.optString("title", "日程"),
                    "--start", args.optString("start"),
                )
                args.optString("end").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--end", it)
                }
                if (args.optBoolean("allDay", false)) argv += "--all-day"
                args.optString("location").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--location", it)
                }
                args.optString("notes").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--notes", it)
                }
                if (args.has("alarmMinutes")) {
                    argv += listOf("--alarm", args.optInt("alarmMinutes").toString())
                }
                return offload("android-calendar", CalendarOffloadHandler(context), argv)
            }
            "delete", "update" -> {
                val id = resolveCalendarId(args)
                    ?: return NativeOffloadResult(
                        1,
                        "找到多个或无匹配日程，请说得更具体，或使用日程 id。",
                    )
                if (action == "delete") {
                    return offload(
                        "android-calendar",
                        CalendarOffloadHandler(context),
                        listOf("android-calendar", "delete", "--id", id.toString()),
                    )
                }
                val argv = mutableListOf("android-calendar", "update", "--id", id.toString())
                args.optString("title").takeIf { it.isNotBlank() && it != "日程" }?.let {
                    argv += listOf("--title", it)
                }
                args.optString("start").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--start", it)
                }
                args.optString("end").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--end", it)
                }
                return offload("android-calendar", CalendarOffloadHandler(context), argv)
            }
        }
        return NativeOffloadResult(2, "Unsupported calendar action: $action")
    }

    private fun resolveCalendarId(args: JSONObject): Long? {
        if (args.has("id")) return args.optString("id").toLongOrNull()
        val query = args.optString("id_or_title").ifBlank { args.optString("title") }
        if (query.isBlank()) return null
        val start = LocalDateTime.now().minusYears(1)
        val end = LocalDateTime.now().plusYears(1)
        val argv = listOf(
            "android-calendar", "list",
            "--start", start.format(ISO),
            "--end", end.format(ISO),
            "--limit", "100",
        )
        val result = offload("android-calendar", CalendarOffloadHandler(context), argv)
        if (result.exitCode != 0) return null
        val events = CalendarCandidateMatcher.parseEvents(result.output)
        return when (val match = CalendarCandidateMatcher.match(events, query)) {
            is CalendarMatchResult.Found -> match.event.id
            is CalendarMatchResult.Multiple -> {
                val choices = match.events.joinToString("；") {
                    "id=${it.id} ${it.title} ${it.start}"
                }
                throw IllegalStateException("匹配到多个日程：$choices")
            }
            CalendarMatchResult.None -> null
        }
    }

    private fun alarm(action: String, args: JSONObject): NativeOffloadResult {
        val handler = AlarmOffloadHandler(context)
        return when (action) {
            "set" -> {
                val time = args.optString("time")
                if (time.isBlank()) return NativeOffloadResult(2, "没有听清闹钟时间。")
                val target = AlarmTimeParser.parse(time)
                val repeatMode = runCatching {
                    com.openminis.app.offload.RepeatMode.valueOf(
                        args.optString("repeat", "ONCE").uppercase(),
                    )
                }.getOrDefault(com.openminis.app.offload.RepeatMode.ONCE)
                if (target != null) {
                    val unsupported = AlarmTimeParser.unsupportedExplicitDate(target, repeatMode)
                    if (unsupported != null) {
                        return NativeOffloadResult(
                            2,
                            "系统时钟只能设置下一次 ${target.hour.toString().padStart(2, '0')}:" +
                                "${target.minute.toString().padStart(2, '0')}，无法指定 " +
                                "$unsupported。已取消，请改用“提醒我”或定时任务。",
                        )
                    }
                }
                val argv = mutableListOf(
                    "android-alarm", "set",
                    "--time", time,
                    "--label", args.optString("label", "闹钟"),
                )
                args.optString("repeat").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--repeat", it)
                }
                offload("android-alarm", handler, argv)
            }
            "timer" -> {
                val seconds = args.optLong("duration")
                if (seconds <= 0) return NativeOffloadResult(2, "没有听清计时时长。")
                offload(
                    "android-alarm", handler,
                    listOf(
                        "android-alarm", "timer",
                        "--duration", seconds.toString(),
                        "--label", args.optString("label", "计时器"),
                    ),
                )
            }
            "open" -> offload(
                "android-alarm", handler,
                listOf("android-alarm", "open"),
            )
            else -> NativeOffloadResult(2, "Unsupported alarm action: $action")
        }
    }

    private fun reminder(
        action: String,
        args: JSONObject,
        sessionId: String,
    ): NativeOffloadResult {
        val handler = NotificationOffloadHandler(context)
        return when (action) {
            "schedule" -> {
                val argv = mutableListOf(
                    "android-notification", "schedule",
                    "--title", args.optString("title", "提醒"),
                    "--body", args.optString("body", args.optString("title", "提醒")),
                )
                when {
                    args.has("after") && args.optLong("after") > 0 ->
                        argv += listOf("--after", args.optLong("after").toString())
                    args.optString("at").isNotBlank() ->
                        argv += listOf("--at", args.optString("at"))
                    else -> return NativeOffloadResult(2, "需要指定提醒时间。")
                }
                offload("android-notification", handler, argv)
            }
            "pending" -> offload(
                "android-notification", handler,
                listOf("android-notification", "pending"),
            )
            "list" -> offload(
                "android-notification", handler,
                listOf("android-notification", "pending"),
            )
            "cancel" -> {
                val id = args.optString("id").ifBlank {
                    resolvePendingNotificationId(args) ?: ""
                }
                if (id.isBlank()) return NativeOffloadResult(2, "没有找到要取消的提醒。")
                offload(
                    "android-notification", handler,
                    listOf("android-notification", "cancel", "--id", id),
                )
            }
            else -> NativeOffloadResult(2, "Unsupported reminder action: $action")
        }
    }

    private fun resolvePendingNotificationId(args: JSONObject): String? {
        val query = args.optString("id").ifBlank { args.optString("title") }
        if (query.isBlank()) return null
        val result = offload(
            "android-notification", NotificationOffloadHandler(context),
            listOf("android-notification", "pending"),
        )
        if (result.exitCode != 0) return null
        val notifications = runCatching { JSONObject(result.output.trim()).optJSONArray("notifications") }
            .getOrNull() ?: return null
        val matches = mutableListOf<JSONObject>()
        for (i in 0 until notifications.length()) {
            val n = notifications.optJSONObject(i) ?: continue
            val haystack = n.optString("title") + " " + n.optString("body")
            if (haystack.contains(query, ignoreCase = true)) matches += n
        }
        return matches.singleOrNull()?.optString("id")
    }

    private fun note(action: String, args: JSONObject): NativeOffloadResult {
        return when (action) {
            "create" -> {
                val content = args.optString("content")
                if (content.isBlank()) return NativeOffloadResult(2, "笔记内容为空。")
                val note = notes.create(
                    content = content,
                    title = args.optString("title").takeIf { it.isNotBlank() },
                )
                NativeOffloadResult(
                    0,
                    JSONObject()
                        .put("id", note.id)
                        .put("title", note.title)
                        .put("created", true)
                        .toString(2),
                )
            }
            "search" -> {
                val query = args.optString("query")
                val hits = notes.search(query)
                val arr = JSONArray()
                hits.forEach { (note, excerpt) ->
                    arr.put(
                        JSONObject()
                            .put("id", note.id)
                            .put("title", note.title)
                            .put("excerpt", excerpt)
                            .put("updatedAt", note.updatedAt),
                    )
                }
                NativeOffloadResult(0, JSONObject().put("notes", arr).put("count", arr.length()).toString(2))
            }
            "list" -> {
                val arr = JSONArray()
                notes.list().forEach { arr.put(it.toJson()) }
                NativeOffloadResult(0, JSONObject().put("notes", arr).put("count", arr.length()).toString(2))
            }
            "delete" -> {
                val id = args.optString("id")
                val resolved = id.ifBlank {
                    val hits = notes.search(args.optString("id_or_query"), limit = 5)
                    if (hits.size == 1) hits.first().first.id else ""
                }
                if (resolved.isBlank()) return NativeOffloadResult(2, "没有唯一匹配的笔记。")
                NativeOffloadResult(0, JSONObject().put("deleted", notes.delete(resolved)).put("id", resolved).toString(2))
            }
            else -> NativeOffloadResult(2, "Unsupported note action: $action")
        }
    }

    private fun settings(action: String, args: JSONObject): NativeOffloadResult {
        val target = args.optString("target", "settings")
        if (action == "open") return openSettings(target)
        val value = args.optString("value")
        if (value.isBlank()) return NativeOffloadResult(2, "没有听清要设置的值。")

        if (!ShizukuManager.isReady()) {
            val opened = openSettings(target)
            return NativeOffloadResult(
                1,
                "Shizuku 未就绪，已打开对应设置页，请手动调整。\n${opened.output}",
            )
        }

        val command = when (target) {
            "brightness" -> {
                val raw = value.removeSuffix("%").toIntOrNull() ?: return NativeOffloadResult(2, "亮度需要 0-100。")
                val level = (raw.coerceIn(0, 100) * 255 / 100)
                arrayOf(
                    arrayOf("settings", "put", "system", "screen_brightness_mode", "0"),
                    arrayOf("settings", "put", "system", "screen_brightness", level.toString()),
                )
            }
            "screen_timeout" -> {
                val sec = value.toIntOrNull() ?: return NativeOffloadResult(2, "息屏时间需要秒数。")
                arrayOf(arrayOf("settings", "put", "system", "screen_off_timeout", (sec * 1000).toString()))
            }
            "rotation" -> arrayOf(
                arrayOf(
                    "settings", "put", "system", "accelerometer_rotation",
                    if (value == "off") "0" else "1",
                ),
            )
            "dnd" -> arrayOf(
                arrayOf("cmd", "notification", "set_dnd", if (value == "off") "off" else "on"),
            )
            "wifi" -> arrayOf(
                arrayOf("svc", "wifi", if (value == "off") "disable" else "enable"),
            )
            "bluetooth" -> arrayOf(
                arrayOf("svc", "bluetooth", if (value == "off") "disable" else "enable"),
            )
            "airplane" -> arrayOf(
                arrayOf("settings", "put", "global", "airplane_mode_on", if (value == "off") "0" else "1"),
                arrayOf(
                    "am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE",
                    "--ez", "state", if (value == "off") "false" else "true",
                ),
            )
            else -> return openSettings(target)
        }

        val outputs = mutableListOf<String>()
        var ok = true
        for (argv in command) {
            val r = ShizukuManager.runProcess(argv)
            if (r.exitCode != 0) {
                ok = false
                outputs += "${argv.joinToString(" ")}: ${r.combined}"
                break
            }
        }
        if (!ok) {
            openSettings(target)
            return NativeOffloadResult(
                1,
                "设置修改失败，已打开对应设置页。\n${outputs.joinToString("\n")}",
            )
        }
        return NativeOffloadResult(
            0,
            JSONObject().put("changed", true).put("target", target).put("value", value).toString(2),
        )
    }

    private fun openSettings(target: String): NativeOffloadResult {
        val intent = when (target) {
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "app" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
            "brightness", "screen_timeout", "rotation" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "dnd" -> Intent("android.settings.ZEN_MODE_SETTINGS")
            else -> Intent(Settings.ACTION_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            NativeOffloadResult(0, "已打开设置页：$target")
        } catch (t: Throwable) {
            NativeOffloadResult(1, "无法打开设置页：${t.message}")
        }
    }

    private fun scheduledTask(
        action: String,
        args: JSONObject,
        sessionId: String,
    ): NativeOffloadResult {
        return when (action) {
            "create" -> {
                val prompt = args.optString("prompt")
                if (prompt.isBlank()) return NativeOffloadResult(2, "定时任务内容为空。")
                val at = args.optString("at")
                val after = args.optLong("after", 0L)
                if (at.isBlank() && after <= 0L) return NativeOffloadResult(2, "需要指定定时任务时间。")
                val dateTime = if (at.isNotBlank()) {
                    runCatching { LocalDateTime.parse(at, ISO) }.getOrNull()
                } else null
                val repeat = args.optString("repeat", "ONCE")
                val task = if (after > 0L) {
                    ScheduledTask(
                        label = args.optString("label", prompt.take(40)),
                        timeOfDayHour = LocalDateTime.now().hour,
                        timeOfDayMinute = LocalDateTime.now().minute,
                        repeatMode = ScheduledRepeatMode.ONCE,
                        prompt = prompt,
                        targetMode = if (sessionId.isNotBlank()) {
                            ScheduledTargetMode.AppendToSession(sessionId)
                        } else ScheduledTargetMode.NewSession,
                        triggerKind = ScheduledTriggerKind.AFTER,
                        delaySec = after,
                        creatorSessionId = sessionId.takeIf { it.isNotBlank() },
                    )
                } else {
                    val dt = dateTime ?: return NativeOffloadResult(2, "无法解析定时任务时间。")
                    ScheduledTask(
                        label = args.optString("label", prompt.take(40)),
                        timeOfDayHour = dt.hour,
                        timeOfDayMinute = dt.minute,
                        repeatMode = when (repeat) {
                            "DAILY" -> ScheduledRepeatMode.DAILY
                            "WEEKDAYS" -> ScheduledRepeatMode.WEEKDAYS
                            "WEEKLY" -> ScheduledRepeatMode.CUSTOM
                            else -> ScheduledRepeatMode.ONCE
                        },
                        customDays = if (repeat == "WEEKLY") setOf(dt.dayOfWeek.value % 7 + 1) else emptySet(),
                        prompt = prompt,
                        targetMode = if (sessionId.isNotBlank()) {
                            ScheduledTargetMode.AppendToSession(sessionId)
                        } else ScheduledTargetMode.NewSession,
                        triggerKind = ScheduledTriggerKind.CALENDAR,
                        creatorSessionId = sessionId.takeIf { it.isNotBlank() },
                    )
                }
                val created = scheduled.create(task)
                NativeOffloadResult(
                    0,
                    JSONObject()
                        .put("created", true)
                        .put("id", created.id)
                        .put("label", created.label)
                        .toString(2),
                )
            }
            "list" -> {
                val arr = JSONArray()
                scheduled.list().forEach { arr.put(it.toJson()) }
                NativeOffloadResult(0, JSONObject().put("tasks", arr).put("count", arr.length()).toString(2))
            }
            "cancel" -> {
                val query = args.optString("id")
                    .ifBlank { args.optString("id_or_query") }
                    .ifBlank { args.optString("query") }
                    .trim()
                if (query.isBlank()) {
                    return NativeOffloadResult(2, "没有找到要取消的定时任务。")
                }
                val id = args.optString("id").trim()
                val resolved = id.ifBlank {
                    val matches = scheduled.list().filter {
                        it.label.contains(query, true) || it.prompt.contains(query, true)
                    }
                    if (matches.size == 1) matches.first().id else ""
                }
                if (resolved.isBlank()) return NativeOffloadResult(2, "没有唯一匹配的定时任务。")
                scheduled.delete(resolved)
                NativeOffloadResult(0, JSONObject().put("cancelled", resolved).toString(2))
            }
            else -> NativeOffloadResult(2, "Unsupported scheduled task action: $action")
        }
    }

    private fun weather(
        action: String,
        args: JSONObject,
        sessionId: String,
    ): NativeOffloadResult {
        val loc = offload(
            "android-location",
            LocationOffloadHandler(context),
            listOf("android-location", "current", "--timeout", "8"),
        )
        if (loc.exitCode != 0) return loc
        val obj = runCatching { JSONObject(loc.output.trim()) }.getOrNull()
            ?: return NativeOffloadResult(1, "无法解析当前位置。")
        val lat = obj.optDouble("latitude")
        val lon = obj.optDouble("longitude")
        if (lat.isNaN() || lon.isNaN()) return NativeOffloadResult(1, "无法获取当前位置。")
        return offload(
            "android-weather",
            WeatherOffloadHandler(context),
            listOf(
                "android-weather", "current",
                "--lat", lat.toString(),
                "--lon", lon.toString(),
            ),
        )
    }

    private fun device(action: String, args: JSONObject): NativeOffloadResult {
        val sub = when (action) {
            "battery" -> "battery"
            "storage" -> "storage"
            "info" -> "info"
            else -> "all"
        }
        return offload(
            "android-device",
            DeviceOffloadHandler(context),
            listOf("android-device", sub),
        )
    }

    private suspend fun media(
        action: String,
        args: JSONObject,
        label: String,
    ): NativeOffloadResult {
        val requested = args.optString("action", action)
        // [T-music-player-top] The in-app music player owns the common
        // cases: it is what the user sees at the top of the chat, so the
        // assistant drives the same thing. The generic file-playback
        // sessions stay the fallback for what the player does not know.
        val musicResult = withContext(Dispatchers.Main) { musicControl(requested, args, label) }
        if (musicResult != null) return musicResult

        val handler = PlayerOffloadHandler()
        if (requested == "list") {
            return offload("android-player", handler, listOf("android-player", "list"))
        }
        var session = args.optString("session")
        if (session.isBlank()) {
            val listed = MediaPlayerManager.listSessions()
            session = Regex("""-\s+([^:\s]+):\s+state=PLAYING""")
                .find(listed)?.groupValues?.get(1)
                ?: Regex("""-\s+([^:\s]+):\s+state=PAUSED""")
                    .find(listed)?.groupValues?.get(1)
                .orEmpty()
        }
        if (session.isBlank()) return NativeOffloadResult(2, "当前没有可控制的播放会话。")
        return offload(
            "android-player",
            handler,
            listOf("android-player", requested, session),
        )
    }

    /**
     * Drive the music player. Returns null when the request is not one the
     * music player can serve, so the caller can fall back to the generic
     * playback sessions.
     */
    private suspend fun musicControl(
        action: String,
        args: JSONObject,
        label: String,
    ): NativeOffloadResult? {
        val music = com.openminis.app.media.music.MusicPlayerController.get(context)
        return when (action) {
            "play" -> {
                val query = args.optString("query").trim()
                val track = music.playQuery(query)
                if (track == null) {
                    NativeOffloadResult(
                        1,
                        if (query.isBlank()) {
                            "没有找到可播放的歌曲。"
                        } else {
                            "没有找到「$query」或该歌曲暂时无法播放。"
                        },
                    )
                } else {
                    val artist = track.artist.takeIf { it.isNotBlank() }
                    // The tool pill shows the song that is really playing -
                    // 「放一首周杰伦的稻香」 resolves to 稻香, not to the
                    // phrase the user typed.
                    args.put("tool_title", "$label: ${track.title}")
                    NativeOffloadResult(
                        0,
                        if (artist != null) {
                            "已播放：${track.title} — $artist"
                        } else {
                            "已播放：${track.title}"
                        },
                    )
                }
            }
            "pause" -> if (music.state.value.hasTrack) {
                music.pause()
                NativeOffloadResult(0, "已暂停播放。")
            } else {
                null
            }
            "resume" -> if (music.state.value.hasTrack) {
                music.resume()
                NativeOffloadResult(0, "已继续播放。")
            } else {
                null
            }
            "next" -> if (music.state.value.hasTrack) {
                music.next()
                NativeOffloadResult(0, "已切到下一首。")
            } else {
                null
            }
            "previous" -> if (music.state.value.hasTrack) {
                music.previous()
                NativeOffloadResult(0, "已回到上一首。")
            } else {
                null
            }
            "stop" -> if (music.state.value.hasTrack) {
                music.stop()
                NativeOffloadResult(0, "已停止播放。")
            } else {
                null
            }
            "status" -> if (music.state.value.hasTrack) {
                val state = music.state.value
                val position = state.positionMs / 1000
                val duration = state.durationMs / 1000
                NativeOffloadResult(
                    0,
                    "正在播放：${state.track?.title.orEmpty()}（$position/$duration 秒）" +
                        (if (state.isPlaying) "，播放中" else "，已暂停"),
                )
            } else {
                null
            }
            "list" -> if (music.state.value.queue.isNotEmpty()) {
                val lines = music.state.value.queue.mapIndexed { index, track ->
                    val artist = track.artist.takeIf { it.isNotBlank() }
                    "${index + 1}. ${track.title}${artist?.let { " — $it" }.orEmpty()}"
                }
                NativeOffloadResult(0, lines.joinToString("\n"))
            } else {
                null
            }
            else -> null
        }
    }

    private fun contacts(action: String, args: JSONObject): NativeOffloadResult {
        val query = args.optString("name").ifBlank { args.optString("query") }
        if (query.isBlank()) return NativeOffloadResult(2, "联系人名称为空。")
        val result = offload(
            "android-contacts",
            ContactsOffloadHandler(context),
            listOf("android-contacts", "search", query, "--max", "10"),
        )
        if (result.exitCode != 0) return result
        val body = result.output.trim()
        val arr = runCatching { JSONArray(body) }.getOrNull()
            ?: return NativeOffloadResult(
                1,
                if (body.contains("No contacts found", ignoreCase = true)) {
                    "没有找到联系人：$query"
                } else {
                    "无法解析联系人结果。"
                },
            )
        if (arr.length() == 0) return NativeOffloadResult(1, "没有找到联系人：$query")
        val contacts = buildList {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { add(it) }
            }
        }
        val chosen = contacts.firstOrNull { it.optString("name").equals(query, true) }
            ?: contacts.singleOrNull()
            ?: return NativeOffloadResult(
                1,
                "找到多个联系人：" + contacts.take(8).joinToString("，") { it.optString("name") } +
                    "。请说得更具体。",
            )
        val phone = chosen.optJSONArray("phones")?.optString(0).orEmpty()
        if (phone.isBlank()) return NativeOffloadResult(1, "${chosen.optString("name")} 没有电话号码。")
        if (action == "search") {
            return NativeOffloadResult(0, chosen.toString(2))
        }
        return dialContact(
            name = chosen.optString("name"),
            phone = phone,
            direct = args.optBoolean("directCall", false),
        )
    }

    private fun dialContact(
        name: String,
        phone: String,
        direct: Boolean,
    ): NativeOffloadResult {
        val wantsDirect = direct && ensureCallPermission()
        val method = if (wantsDirect) "ACTION_CALL" else "ACTION_DIAL"
        return try {
            context.startActivity(
                Intent(
                    if (wantsDirect) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                    Uri.parse("tel:${Uri.encode(phone)}"),
                )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            NativeOffloadResult(
                0,
                JSONObject()
                    .put("dialed", name)
                    .put("number", phone)
                    .put("method", method)
                    .put(
                        "note",
                        if (direct && !wantsDirect) {
                            "CALL_PHONE is not granted; opened the dialer instead."
                        } else {
                            ""
                        },
                    )
                    .toString(2),
            )
        } catch (t: Throwable) {
            NativeOffloadResult(1, "无法打开拨号盘：${t.message}")
        }
    }

    private fun ensureCallPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return true
        return runCatching {
            runBlocking {
                OffloadPermissionManager.requestAndroidPermission(
                    listOf(Manifest.permission.CALL_PHONE),
                )
            }
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE,
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    private fun appLaunch(action: String, args: JSONObject): NativeOffloadResult {
        val name = args.optString("app")
        if (name.isBlank()) return NativeOffloadResult(2, "应用名称为空。")
        val normalized = name.lowercase().replace(Regex("""\s+"""), "")
        val known = KNOWN_APPS.entries.firstOrNull { (alias, _) ->
            normalized == alias.lowercase() || normalized.contains(alias.lowercase())
        }?.value
        val launchIntent = when {
            known != null && known.packageName != null ->
                context.packageManager.getLaunchIntentForPackage(known.packageName)
            known?.intent != null -> known.intent(context)
            else -> null
        } ?: resolveLaunchIntentByLabel(name)
        if (launchIntent == null) return NativeOffloadResult(1, "没有找到应用：$name")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launchIntent)
            NativeOffloadResult(0, "已打开应用：$name")
        } catch (t: Throwable) {
            NativeOffloadResult(1, "无法打开应用：${t.message}")
        }
    }

    private fun resolveLaunchIntentByLabel(name: String): Intent? {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = runCatching { pm.queryIntentActivities(main, 0) }.getOrDefault(emptyList())
        val match = activities.firstOrNull {
            it.loadLabel(pm).toString().contains(name, ignoreCase = true)
        } ?: return null
        return pm.getLaunchIntentForPackage(match.activityInfo.packageName)
    }

    private data class KnownApp(
        val packageName: String? = null,
        val intent: ((Context) -> Intent)? = null,
    )

    private fun offload(
        name: String,
        handler: com.openminis.app.sandbox.NativeOffloadHandler,
        argv: List<String>,
    ): NativeOffloadResult {
        return handler.handle(
            NativeOffloadRequest(
                pid = android.os.Process.myPid(),
                argv = argv,
                env = emptyMap(),
                cwd = "/",
            ),
        )
    }

    companion object {
        private const val TAG = "LocalCapabilityExecutor"
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        private val KNOWN_APPS: Map<String, KnownApp> = mapOf(
            "微信" to KnownApp("com.tencent.mm"),
            "wechat" to KnownApp("com.tencent.mm"),
            "支付宝" to KnownApp("com.eg.android.AlipayGphone"),
            "alipay" to KnownApp("com.eg.android.AlipayGphone"),
            "qq" to KnownApp("com.tencent.mobileqq"),
            "淘宝" to KnownApp("com.taobao.taobao"),
            "抖音" to KnownApp("com.ss.android.ugc.aweme"),
            "douyin" to KnownApp("com.ss.android.ugc.aweme"),
            "哔哩哔哩" to KnownApp("tv.danmaku.bili"),
            "bilibili" to KnownApp("tv.danmaku.bili"),
            "微博" to KnownApp("com.sina.weibo"),
            "weibo" to KnownApp("com.sina.weibo"),
            "高德" to KnownApp("com.autonavi.minimap"),
            "百度地图" to KnownApp("com.baidu.BaiduMap"),
            "相机" to KnownApp(intent = { Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) }),
            "camera" to KnownApp(intent = { Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) }),
            "时钟" to KnownApp(intent = { Intent(AlarmClock.ACTION_SHOW_ALARMS) }),
            "clock" to KnownApp(intent = { Intent(AlarmClock.ACTION_SHOW_ALARMS) }),
            "日历" to KnownApp(intent = { Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI) }),
            "calendar" to KnownApp(intent = { Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI) }),
            "设置" to KnownApp(intent = { Intent(Settings.ACTION_SETTINGS) }),
            "settings" to KnownApp(intent = { Intent(Settings.ACTION_SETTINGS) }),
        )
    }
}
