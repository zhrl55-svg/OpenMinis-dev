package com.openminis.app.sandbox.offload

import com.openminis.app.offload.RepeatMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmTimeParserTest {
    private val zone = ZoneId.of("Asia/Singapore")
    private val now = ZonedDateTime.of(
        LocalDateTime.of(2026, 11, 13, 10, 0),
        zone,
    )

    @Test
    fun `plain clock keeps no explicit date`() {
        val target = AlarmTimeParser.parse("07:30", zone)!!
        assertEquals(7, target.hour)
        assertEquals(30, target.minute)
        assertNull(target.explicitDate)
    }

    @Test
    fun `local and zoned ISO values keep the local date`() {
        val local = AlarmTimeParser.parse("2026-11-14T07:00", zone)!!
        assertEquals(LocalDate.of(2026, 11, 14), local.explicitDate)
        assertEquals(7, local.hour)

        val utc = AlarmTimeParser.parse("2026-11-13T23:00:00Z", zone)!!
        assertEquals(LocalDate.of(2026, 11, 14), utc.explicitDate)
        assertEquals(7, utc.hour)
    }

    @Test
    fun `next occurrence rolls past times to tomorrow`() {
        assertEquals(
            LocalDate.of(2026, 11, 14),
            AlarmTimeParser.nextOccurrenceDate(7, 0, now),
        )
        assertEquals(
            LocalDate.of(2026, 11, 13),
            AlarmTimeParser.nextOccurrenceDate(11, 0, now),
        )
    }

    @Test
    fun `explicit date mismatch is rejected for one shot alarms`() {
        val nextFriday = AlarmTimeParser.parse("2026-11-20T07:00", zone)!!
        assertTrue(
            AlarmTimeParser.unsupportedExplicitDate(nextFriday, RepeatMode.ONCE, now) != null,
        )
    }

    @Test
    fun `matching explicit date and repeating alarms are allowed`() {
        val tomorrow = AlarmTimeParser.parse("2026-11-14T07:00", zone)!!
        assertNull(AlarmTimeParser.unsupportedExplicitDate(tomorrow, RepeatMode.ONCE, now))
        assertNull(AlarmTimeParser.unsupportedExplicitDate(tomorrow, RepeatMode.DAILY, now))
    }
}
