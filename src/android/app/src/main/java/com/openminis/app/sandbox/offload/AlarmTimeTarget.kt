package com.openminis.app.sandbox.offload

import com.openminis.app.offload.RepeatMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class AlarmTimeTarget(
    val hour: Int,
    val minute: Int,
    val explicitDate: LocalDate? = null,
)

/**
 * Android's [android.provider.AlarmClock.ACTION_SET_ALARM] API accepts only a
 * wall-clock hour/minute (plus repeat days); it cannot identify a one-shot
 * date. Parsing an ISO datetime down to HH:mm therefore silently turned
 * "next Friday at 7" into the next 7:00. Keep the date through parsing so the
 * handler can reject a mismatch instead of scheduling the wrong day.
 */
object AlarmTimeParser {
    private val HHMM = Regex("""^(\d{1,2}):(\d{2})$""")

    fun parse(value: String, zone: ZoneId = ZoneId.systemDefault()): AlarmTimeTarget? {
        val trimmed = value.trim()
        HHMM.matchEntire(trimmed)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return AlarmTimeTarget(hour, minute)
        }

        val instant = runCatching { Instant.parse(trimmed) }.getOrNull()
        if (instant != null) {
            val local = instant.atZone(zone).toLocalDateTime()
            return AlarmTimeTarget(local.hour, local.minute, local.toLocalDate())
        }
        val offset = runCatching {
            OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }.getOrNull()
        if (offset != null) {
            val local = offset.atZoneSameInstant(zone).toLocalDateTime()
            return AlarmTimeTarget(local.hour, local.minute, local.toLocalDate())
        }
        val local = runCatching {
            LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        }.getOrNull() ?: return null
        return AlarmTimeTarget(local.hour, local.minute, local.toLocalDate())
    }

    fun nextOccurrenceDate(
        hour: Int,
        minute: Int,
        now: ZonedDateTime,
    ): LocalDate {
        val candidate = now.toLocalDate().atTime(hour, minute)
        return if (candidate.isAfter(now.toLocalDateTime())) {
            candidate.toLocalDate()
        } else {
            candidate.toLocalDate().plusDays(1)
        }
    }

    /**
     * Returns the requested date when the system Clock cannot represent it.
     * Repeating alarms intentionally ignore the date because their semantics
     * are weekday-based.
     */
    fun unsupportedExplicitDate(
        target: AlarmTimeTarget,
        repeatMode: RepeatMode,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): LocalDate? {
        if (repeatMode != RepeatMode.ONCE) return null
        val requested = target.explicitDate ?: return null
        val expected = nextOccurrenceDate(target.hour, target.minute, now)
        return requested.takeIf { it != expected }
    }
}
