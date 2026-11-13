package com.openminis.app.assistant.routing

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class AssistantDecisionRecord(
    val at: Long,
    val text: String,
    val topRoutes: List<RouteMatch>,
    val selected: String?,
    val capability: String?,
    val action: String?,
    val args: String?,
    val decision: String,
    val reason: String,
    val elapsedMs: Long,
)

/**
 * Structured, privacy-local diagnostics. The old text trace remains available
 * for compatibility, while new decisions are appended as JSONL.
 */
object AssistantDiagnostics {
    private const val TAG = "AssistantDiagnostics"
    private const val FILE_NAME = "diagnostics.jsonl"
    private const val MAX_LINES = 1_000

    fun record(
        context: Context,
        userText: String,
        ranked: List<RouteMatch>,
        decision: RouteDecision,
        elapsedMs: Long,
    ) {
        val top = JSONArray()
        ranked.take(3).forEach { m ->
            top.put(
                JSONObject()
                    .put("id", m.route.id)
                    .put("label", m.route.label)
                    .put("score", m.score),
            )
        }
        val row = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("text", userText.take(500))
            .put("top", top)
        when (decision) {
            is RouteDecision.Local -> {
                row.put("selected", decision.route.id)
                row.put("capability", decision.capability)
                row.put("action", decision.action)
                row.put("args", decision.args.toString().take(2_000))
                row.put("decision", if (decision.needsConfirmation) "confirm" else "local")
                row.put("reason", if (decision.needsConfirmation) "write-confirmation" else "matched")
            }
            is RouteDecision.Cloud -> {
                row.put("selected", JSONObject.NULL)
                row.put("decision", "cloud")
                row.put("reason", decision.reason)
            }
        }
        row.put("elapsedMs", elapsedMs)
        append(context, row)
    }

    fun recent(context: Context, limit: Int = 100): List<AssistantDecisionRecord> {
        val safeLimit = limit.coerceIn(1, MAX_LINES)
        val file = file(context)
        val current = if (!file.isFile) emptyList() else runCatching {
            file.readLines()
                .asReversed()
                .mapNotNull { line ->
                    runCatching { parse(JSONObject(line)) }.getOrNull()
                }
                .take(safeLimit)
        }.getOrDefault(emptyList())
        if (current.isNotEmpty()) return current
        return legacyRecent(context, safeLimit)
    }

    /**
     * Group cloud handoffs (no match/low confidence) so Settings can suggest
     * concrete routes the user may want to add.
     */
    fun unmatchedSuggestions(context: Context, limit: Int = 20): List<Pair<String, Int>> =
        recent(context, MAX_LINES)
            .asSequence()
            .filter {
                it.decision == "cloud" &&
                    (it.reason == "no-match" || it.reason == "low-confidence")
            }
            .groupingBy { it.text.trim() }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit.coerceAtLeast(1))
            .map { it.key to it.value }

    private fun append(context: Context, row: JSONObject) {
        runCatching {
            val file = file(context)
            file.parentFile?.mkdirs()
            file.appendText(row.toString() + "\n")
            if (file.length() > 2_000_000L) {
                val keep = file.readLines().takeLast(MAX_LINES)
                val temp = File(file.parentFile, "${file.name}.tmp")
                temp.writeText(keep.joinToString("\n", postfix = "\n"))
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }.onFailure {
            com.openminis.app.logging.AppLogger.warning(TAG, "write failed: ${it.message}")
        }
    }

    private fun parse(o: JSONObject): AssistantDecisionRecord {
        val top = o.optJSONArray("top")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val route = AssistantRoute(
                        id = m.optString("id"),
                        label = m.optString("label"),
                        capability = "",
                        action = "",
                    )
                    add(RouteMatch(route, m.optInt("score"), emptyList(), emptyList()))
                }
            }
        }.orEmpty()
        return AssistantDecisionRecord(
            at = o.optLong("at"),
            text = o.optString("text"),
            topRoutes = top,
            selected = if (o.isNull("selected")) null else o.optString("selected"),
            capability = if (o.isNull("capability")) null else o.optString("capability"),
            action = if (o.isNull("action")) null else o.optString("action"),
            args = if (o.isNull("args")) null else o.optString("args"),
            decision = o.optString("decision"),
            reason = o.optString("reason"),
            elapsedMs = o.optLong("elapsedMs"),
        )
    }

    /**
     * Compatibility for logs written before the JSONL switch. The old trace
     * only carried a time of day, so it is attached to the nearest matching
     * date (today, or yesterday when the log crossed midnight).
     */
    private fun legacyRecent(context: Context, limit: Int): List<AssistantDecisionRecord> {
        val external = context.getExternalFilesDir(null) ?: return emptyList()
        val legacy = File(external, LEGACY_FILE_NAME)
        if (!legacy.isFile) return emptyList()
        val now = System.currentTimeMillis()
        return runCatching {
            legacy.readLines()
                .takeLast(limit)
                .mapNotNull { parseLegacyTraceLine(it, now) }
                .asReversed()
        }.getOrDefault(emptyList())
    }

    internal fun parseLegacyTraceLine(
        line: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): AssistantDecisionRecord? {
        val match = LEGACY_ROUTE_LINE.matchEntire(line) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val second = match.groupValues[3].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        val text = match.groupValues[4]
        val kind = match.groupValues[5]
        val rest = match.groupValues[6]

        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        var stamp = ZonedDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute, second), zone)
        if (stamp.isAfter(now.plusMinutes(5))) stamp = stamp.minusDays(1)

        return if (kind == "local") {
            val capabilityAction = LEGACY_LOCAL_TARGET.find(rest)
            val capability = capabilityAction?.groupValues?.get(1)
            val action = capabilityAction?.groupValues?.get(2)
            AssistantDecisionRecord(
                at = stamp.toInstant().toEpochMilli(),
                text = text,
                topRoutes = emptyList(),
                selected = if (capability != null && action != null) "$capability/$action" else null,
                capability = capability,
                action = action,
                args = null,
                decision = "local",
                reason = "legacy-local",
                elapsedMs = 0,
            )
        } else {
            AssistantDecisionRecord(
                at = stamp.toInstant().toEpochMilli(),
                text = text,
                topRoutes = emptyList(),
                selected = null,
                capability = null,
                action = null,
                args = null,
                decision = "cloud",
                reason = LEGACY_REASON.find(rest)?.groupValues?.get(1) ?: "legacy-cloud",
                elapsedMs = 0,
            )
        }
    }

    private fun file(context: Context) =
        File(File(context.applicationContext.filesDir, "assistant"), FILE_NAME)

    private const val LEGACY_FILE_NAME = "assistant_fastpath.log"
    private val LEGACY_ROUTE_LINE = Regex(
        """^\[(\d{2}):(\d{2}):(\d{2})]\s+route:.*?text=「(.*?)」.*?decision=(local|cloud)(.*)$""",
    )
    private val LEGACY_LOCAL_TARGET =
        Regex("""^\s*([A-Za-z0-9_]+)/([A-Za-z0-9_]+)""")
    private val LEGACY_REASON = Regex("""reason=([A-Za-z0-9_-]+)""")
}
