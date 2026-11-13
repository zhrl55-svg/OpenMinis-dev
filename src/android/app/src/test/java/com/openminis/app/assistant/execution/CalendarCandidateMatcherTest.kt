package com.openminis.app.assistant.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarCandidateMatcherTest {
    private val events = listOf(
        CalendarEventCandidate(1, "项目评审", "2026-11-14T10:00:00", "会议室 A"),
        CalendarEventCandidate(2, "项目评审复盘", "2026-11-15T10:00:00"),
        CalendarEventCandidate(3, "产品评审", "2026-11-16T10:00:00"),
        CalendarEventCandidate(4, "项目评审", "2026-11-17T10:00:00"),
    )

    @Test
    fun `unique substring resolves without asking`() {
        val match = CalendarCandidateMatcher.match(events, "产品")
        assertTrue(match is CalendarMatchResult.Found)
        assertEquals(3L, (match as CalendarMatchResult.Found).event.id)
    }

    @Test
    fun `duplicate exact titles require a choice`() {
        val match = CalendarCandidateMatcher.match(events, "项目评审")
        assertTrue(match is CalendarMatchResult.Multiple)
        val multiple = match as CalendarMatchResult.Multiple
        assertEquals(2, multiple.total)
        assertEquals(listOf(1L, 4L), multiple.events.map { it.id })
    }

    @Test
    fun `broad query returns candidates instead of the first row`() {
        val match = CalendarCandidateMatcher.match(events, "评审")
        assertTrue(match is CalendarMatchResult.Multiple)
        assertEquals(4, (match as CalendarMatchResult.Multiple).total)
    }

    @Test
    fun `blank and unmatched queries do nothing`() {
        assertEquals(CalendarMatchResult.None, CalendarCandidateMatcher.match(events, " "))
        assertEquals(CalendarMatchResult.None, CalendarCandidateMatcher.match(events, "不存在的日程"))
    }

    @Test
    fun `parses the calendar envelope with surrounding text`() {
        val output = """
            {
              "events": [
                {"id": 7, "title": "周会", "start": "2026-11-14T09:00:00", "location": "线上"}
              ],
              "count": 1
            }
        """.trimIndent()
        val parsed = CalendarCandidateMatcher.parseEvents("result:\n$output")
        assertEquals(1, parsed.size)
        assertEquals(7L, parsed.single().id)
        assertEquals("线上", parsed.single().location)
    }
}
