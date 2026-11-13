package com.openminis.app.assistant.execution

import org.json.JSONObject

data class CalendarEventCandidate(
    val id: Long,
    val title: String,
    val start: String,
    val location: String = "",
)

sealed interface CalendarMatchResult {
    data class Found(val event: CalendarEventCandidate) : CalendarMatchResult
    data class Multiple(
        val events: List<CalendarEventCandidate>,
        val total: Int,
    ) : CalendarMatchResult
    data object None : CalendarMatchResult
}

/**
 * Pure matching shared by the local executor and its unit tests. An exact
 * title match wins; otherwise a single bidirectional substring match is safe.
 * Anything broader is returned as a choice instead of being guessed.
 */
object CalendarCandidateMatcher {
    const val MAX_CHOICES = 8

    fun parseEvents(output: String): List<CalendarEventCandidate> {
        val trimmed = output.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val events = runCatching {
            JSONObject(trimmed.substring(start, end + 1)).optJSONArray("events")
        }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val id = event.optLong("id", -1L)
                if (id <= 0L) continue
                add(
                    CalendarEventCandidate(
                        id = id,
                        title = event.optString("title").trim(),
                        start = event.optString("start"),
                        location = event.optString("location"),
                    ),
                )
            }
        }
    }

    fun match(
        events: List<CalendarEventCandidate>,
        query: String,
        limit: Int = MAX_CHOICES,
    ): CalendarMatchResult {
        val needle = query.trim()
        if (needle.isBlank()) return CalendarMatchResult.None

        val exact = events.filter { it.title.equals(needle, ignoreCase = true) }
        when (exact.size) {
            1 -> return CalendarMatchResult.Found(exact.single())
            in 2..Int.MAX_VALUE -> return multiple(exact, limit)
        }

        val contains = events.filter { event ->
            event.title.isNotBlank() &&
                (event.title.contains(needle, ignoreCase = true) ||
                    needle.contains(event.title, ignoreCase = true))
        }.sortedBy { it.start }
        return when (contains.size) {
            0 -> CalendarMatchResult.None
            1 -> CalendarMatchResult.Found(contains.single())
            else -> multiple(contains, limit)
        }
    }

    private fun multiple(
        events: List<CalendarEventCandidate>,
        limit: Int,
    ): CalendarMatchResult.Multiple = CalendarMatchResult.Multiple(
        events = events.sortedBy { it.start }.take(limit.coerceAtLeast(1)),
        total = events.size,
    )
}
