package com.openminis.app.assistant.execution

import org.json.JSONArray
import org.json.JSONObject

/**
 * Short, spoken summaries for local capabilities that do not have a dedicated
 * tool-level summary.
 *
 * The chat bubble keeps the full answer; this object only decides what a
 * voice-first user hears. It is deliberately conservative:
 * - weather / device already produce one natural sentence -> speak it as is;
 * - list-like capabilities -> "找到 N 条：前 2~3 个名字";
 * - JSON outputs (notes, contacts, media sessions) -> count + first labels;
 * - anything without readable text -> empty, so the caller can fall back to
 *   the generic completion phrase or stay silent.
 */
object LocalSpokenSummary {

    private const val MAX_CHARS = 220

    private val ARRAY_KEYS = listOf(
        "items", "notes", "reminders", "events", "contacts",
        "sessions", "results", "tasks", "data",
    )

    /**
     * Summary for a capability executed by [LocalCapabilityExecutor].
     *
     * @param answer the user-facing body from [LocalResultFormatter]
     * @param output the raw tool output (may be JSON)
     */
    fun forCapability(
        capability: String,
        action: String,
        output: String,
        answer: String,
        success: Boolean,
    ): String {
        if (!success) return ""
        val text = when (capability) {
            "weather" -> if (action == "current") answer else ""
            "device" -> answer
            "calendar" -> when (action) {
                // Free/busy 的 JSON 不会被 LocalResultFormatter 渲染成正文，
                // 只能从原始输出里取出第一个空闲时段。
                "freebusy" -> freebusySummary(answer.ifBlank { output })
                "list" -> summarize(answer.ifBlank { output })
                else -> ""
            }
            "reminder" -> when (action) {
                "list", "pending" -> summarize(answer.ifBlank { output })
                else -> ""
            }
            "note" -> when (action) {
                "search", "list" -> summarize(output.ifBlank { answer })
                else -> ""
            }
            "contacts" -> if (action == "search") summarize(output.ifBlank { answer }) else ""
            "media" -> when (action) {
                "status" -> answer.ifBlank { summarize(output) }
                "list" -> summarize(output.ifBlank { answer })
                else -> ""
            }
            "scheduled_task" -> if (action == "list") {
                summarize(answer.ifBlank { output })
            } else {
                ""
            }
            else -> ""
        }
        return text.trim().take(MAX_CHARS)
    }

    /** Summary for a non-life tool executed through ChatViewModel.executeTool. */
    fun forTool(capability: String, output: String, success: Boolean): String {
        if (!success) return ""
        return when (capability) {
            "memory_get" -> summarizeMemory(output)
            else -> ""
        }
    }

    // -------------------------------------------------------------- parsing

    internal fun summarize(output: String): String {
        val text = output.trim()
        if (text.isEmpty()) return ""

        runCatching { JSONArray(text) }.getOrNull()?.let { return listSummary(it) }

        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj != null) {
            for (key in ARRAY_KEYS) {
                obj.optJSONArray(key)?.let { return listSummary(it) }
            }
            val label = firstLabel(obj)
            if (label.isNotBlank()) return label.take(MAX_CHARS)
        }

        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return ""
        if (lines.size == 1) return lines[0].take(MAX_CHARS)
        return ("找到 ${lines.size} 条：" + lines.take(2).joinToString("；")).take(MAX_CHARS)
    }

    /**
     * Free/busy is JSON (`{"busy":[…],"free":[…]}`) that
     * [LocalResultFormatter] deliberately does not render into the bubble, so
     * the spoken answer has to come from the raw output.
     */
    internal fun freebusySummary(output: String): String {
        val obj = runCatching { JSONObject(output) }.getOrNull() ?: return ""
        val free = obj.optJSONArray("free") ?: return ""
        if (free.length() == 0) return "这段时间没有空闲。"
        val first = free.optJSONObject(0) ?: return ""
        val start = first.optString("start").substringAfter('T', "").take(5)
        val end = first.optString("end").substringAfter('T', "").take(5)
        if (start.isBlank() || end.isBlank()) return "有空闲时间。"
        return "有空，第一个空闲时段是 $start 到 $end。"
    }

    internal fun summarizeMemory(output: String): String {        val text = output.trim()
        if (text.isEmpty()) return ""
        if (text.startsWith("No matches", ignoreCase = true) ||
            text.startsWith("No memory files", ignoreCase = true)
        ) {
            return "没有找到相关记忆。"
        }
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return ""
        return ("找到相关记忆，共 ${lines.size} 行：" + lines.first().take(120)).take(MAX_CHARS)
    }

    private fun listSummary(array: JSONArray): String {
        if (array.length() == 0) return "没有找到结果。"
        val labels = (0 until minOf(3, array.length())).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            firstLabel(item).take(40).takeIf { it.isNotBlank() }
        }
        return buildString {
            append("找到 ").append(array.length()).append(" 条")
            if (labels.isNotEmpty()) append("：").append(labels.joinToString("、"))
            append("。")
        }
    }

    private fun firstLabel(item: JSONObject): String =
        item.optString("title").ifBlank { item.optString("name") }
            .ifBlank { item.optString("label") }
            .ifBlank { item.optString("content") }
            .ifBlank { item.optString("text") }
            .trim()
}
