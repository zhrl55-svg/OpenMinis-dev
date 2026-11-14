package com.openminis.app.assistant.execution

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 本地快速路径结果的「用户可见正文」。
 *
 * 工具的原始输出仍然保存在 tool_use 块里（点开胶囊可看），但助手气泡不能
 * 只写 `✅ Weather` —— 那会让「今天天气怎么样」看起来只回了 weather 这个词。
 * 旧版快速路径（AssistantFastPath.formatResult）本来就会把结果正文拼进回复，
 * 路由改造后丢了这段，这里把它补回来。
 *
 * 结构化 JSON 不直接倒进气泡：认识的生活能力翻译成一句中文，不认识的
 * 结构化结果只留在工具块里。纯文本结果（shell / 文件 / 记忆等）原样显示。
 */
object LocalResultFormatter {

    private const val MAX_LENGTH = 1200

    /**
     * @param capability 本地能力 id（weather/device/…）
     * @param output     工具原始输出
     * @param success    工具是否成功；失败时原样显示错误信息
     * @return 气泡正文；空串表示只显示标题
     */
    fun answer(
        capability: String,
        action: String,
        output: String,
        success: Boolean,
    ): String {
        val body = output.trim()
        if (body.isEmpty()) return ""
        if (!success) return truncate(body)
        val structured = parseStructured(body)
        return when {
            structured is JSONObject && capability == "weather" -> weather(structured)
            structured is JSONObject && capability == "device" -> device(action, structured)
            structured != null -> "" // 不把原始 JSON 倒给用户，留在工具块里
            else -> truncate(body)
        }
    }

    private fun parseStructured(body: String): Any? =
        runCatching { JSONObject(body) }.getOrNull()
            ?: runCatching { JSONArray(body) }.getOrNull()

    private fun truncate(text: String): String =
        if (text.length > MAX_LENGTH) {
            text.take(MAX_LENGTH) + "\n…（已截断）"
        } else {
            text
        }

    // ── Weather ──────────────────────────────────────────────────────────

    /**
     * Open-Meteo `current` 块是扁平结构；也接受 report 里的 `current` 子对象。
     */
    private fun weather(json: JSONObject): String {
        val current = json.optJSONObject("current") ?: json
        val temp = current.optDouble("temperature")
        val feels = current.optDouble("feels_like")
        val humidity = current.optInt("humidity", -1)
        val wind = current.optDouble("wind_speed")
        val precipitation = current.optDouble("precipitation_mm")
        val condition = conditionZh(current.optInt("weather_code", -1))
            .ifBlank { current.optString("conditions") }
        val clock = current.optString("time")
            .substringAfter('T', "")
            .take(5)

        val clauses = mutableListOf<String>()
        if (condition.isNotBlank()) clauses += condition
        if (temp.isFinite()) {
            val text = buildString {
                append(formatNumber(temp)).append(current.optString("temperature_unit", "°C"))
                if (feels.isFinite()) {
                    append("（体感 ").append(formatNumber(feels))
                        .append(current.optString("temperature_unit", "°C")).append("）")
                }
            }
            clauses += text
        }
        if (humidity >= 0) clauses += "湿度 $humidity%"
        if (wind.isFinite()) {
            clauses += "风速 ${formatNumber(wind)} ${current.optString("wind_speed_unit", "km/h")}"
        }
        if (precipitation.isFinite()) {
            clauses += "降水 ${formatNumber(precipitation)} mm"
        }
        if (clauses.isEmpty()) return ""

        val prefix = if (clock.isNotBlank()) "现在（$clock）：" else "现在："
        return prefix + clauses.joinToString("，") + "。"
    }

    private fun conditionZh(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "大致晴朗"
        2 -> "多云"
        3 -> "阴"
        45 -> "有雾"
        48 -> "雾凇"
        51 -> "小毛毛雨"
        53 -> "毛毛雨"
        55 -> "大毛毛雨"
        56 -> "冻毛毛雨"
        57 -> "强冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66 -> "冻雨"
        67 -> "强冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "米雪"
        80 -> "小阵雨"
        81 -> "中阵雨"
        82 -> "强阵雨"
        85 -> "小阵雪"
        86 -> "大阵雪"
        95 -> "雷阵雨"
        96 -> "雷阵雨伴小冰雹"
        99 -> "雷阵雨伴大冰雹"
        else -> ""
    }

    // ── Device ───────────────────────────────────────────────────────────

    private fun device(action: String, json: JSONObject): String = when (action) {
        "battery" -> battery(json)
        "storage" -> storage(json)
        "info" -> info(json)
        else -> ""
    }

    private fun battery(json: JSONObject): String {
        val clauses = mutableListOf<String>()
        val level = json.optInt("level_percent", -1)
        if (level >= 0) clauses += "电量 $level%"

        when {
            json.optBoolean("charging") -> {
                val source = when (json.optString("power_source")) {
                    "ac" -> "交流电"
                    "usb" -> "USB"
                    "wireless" -> "无线充电"
                    else -> ""
                }
                clauses += if (source.isBlank()) "正在充电" else "正在充电（$source）"
            }
            json.optString("status") == "full" -> clauses += "已充满"
            json.optString("status") == "discharging" -> clauses += "未充电"
        }

        val temp = json.optDouble("temperature_celsius")
        if (temp.isFinite()) clauses += "电池温度 ${formatNumber(temp)}°C"
        if (clauses.isEmpty()) return ""
        return clauses.joinToString("，") + "。"
    }

    private fun storage(json: JSONObject): String {
        val total = json.optString("internal_total_gb")
        val used = json.optString("internal_used_gb")
        val free = json.optString("internal_free_gb")
        val appData = json.optString("app_data_mb")
        val cache = json.optString("app_cache_mb")

        val parts = mutableListOf<String>()
        if (total.isNotBlank() && free.isNotBlank()) {
            parts += buildString {
                append("内部存储 $total GB")
                if (used.isNotBlank()) append("（已用 $used GB，剩余 $free GB）")
                else append("（剩余 $free GB）")
            }
        }
        val appParts = mutableListOf<String>()
        if (appData.isNotBlank()) appParts += "应用数据 $appData MB"
        if (cache.isNotBlank()) appParts += "缓存 $cache MB"
        if (appParts.isNotEmpty()) parts += appParts.joinToString("，")
        if (parts.isEmpty()) return ""
        return parts.joinToString("；") + "。"
    }

    private fun info(json: JSONObject): String {
        val vendor = json.optString("manufacturer")
        val model = json.optString("model")
        val android = json.optString("android_version")
        val sdk = json.optInt("sdk_level", -1)
        val cores = json.optInt("available_processors", -1)

        val clauses = mutableListOf<String>()
        val device = listOf(vendor, model).filter { it.isNotBlank() }.joinToString(" ")
        if (device.isNotBlank()) clauses += device
        if (android.isNotBlank() || sdk >= 0) {
            clauses += buildString {
                append("Android")
                if (android.isNotBlank()) append(" $android")
                if (sdk >= 0) append("（SDK $sdk）")
            }
        }
        if (cores > 0) clauses += "$cores 核"
        if (clauses.isEmpty()) return ""
        return clauses.joinToString("，") + "。"
    }

    private fun formatNumber(value: Double): String =
        String.format(Locale.US, "%.1f", value)
}
