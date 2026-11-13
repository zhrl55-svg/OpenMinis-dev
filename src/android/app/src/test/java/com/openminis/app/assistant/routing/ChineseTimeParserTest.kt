package com.openminis.app.assistant.routing

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseTimeParserTest {

    private val zone = ZoneId.of("Asia/Singapore")
    private val now = LocalDateTime.of(2026, 11, 13, 10, 0) // Friday

    private fun parse(text: String) = ChineseTimeParser.parse(text, now, zone)

    @Test
    fun `relative minutes and hours`() {
        assertEquals(now.plusMinutes(30), parse("30分钟后")?.dateTime)
        assertEquals(now.plusMinutes(30), parse("半个小时后")?.dateTime)
        assertEquals(now.plusMinutes(90), parse("一个半小时后")?.dateTime)
        assertEquals(now.plusMinutes(90), parse("1个半小时后")?.dateTime)
        assertEquals(now.plusHours(2), parse("两小时后")?.dateTime)
        assertTrue(parse("30分钟后")?.relative == true)
    }

    @Test
    fun `tomorrow and clock periods`() {
        val p = parse("明天下午三点半")!!
        assertEquals(LocalDateTime.of(2026, 11, 14, 15, 30), p.dateTime)
        assertEquals(15, p.dateTime.hour)
        assertEquals(30, p.dateTime.minute)
        assertFalse(p.ambiguous)
    }

    @Test
    fun `weekday resolves to the next occurrence`() {
        val p = parse("周三上午十点")!!
        assertEquals(DayOfWeek.WEDNESDAY, p.dateTime.dayOfWeek)
        assertEquals(LocalDateTime.of(2026, 11, 18, 10, 0), p.dateTime)
    }

    @Test
    fun `next week monday`() {
        val p = parse("下周一晚上8点")!!
        assertEquals(LocalDateTime.of(2026, 11, 16, 20, 0), p.dateTime)
    }

    @Test
    fun `absolute month and day rolls into next year`() {
        val p = parse("11月20日上午9点")!!
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 0), p.dateTime)

        val past = parse("十月一日")!!
        assertEquals(2027, past.dateTime.year)
        assertTrue(past.allDay)
        assertTrue(past.ambiguous)
    }

    @Test
    fun `chinese number clock`() {
        assertEquals(LocalDateTime.of(2026, 11, 14, 23, 30), parse("明天晚上十一点半")?.dateTime)
        assertEquals(LocalDateTime.of(2026, 11, 14, 0, 15), parse("凌晨零点一刻")?.dateTime)
    }

    @Test
    fun `date without time is all day and ambiguous`() {
        val p = parse("后天")!!
        assertEquals(LocalDateTime.of(2026, 11, 15, 0, 0), p.dateTime)
        assertTrue(p.allDay)
        assertTrue(p.ambiguous)
    }

    @Test
    fun `today at a past clock time is marked ambiguous instead of shifted`() {
        val p = parse("今天上午8点")!!
        assertEquals(LocalDateTime.of(2026, 11, 13, 8, 0), p.dateTime)
        assertTrue(p.ambiguous)
        assertTrue(p.confidence < 0.8)
    }

    @Test
    fun `clock without a date rolls to the next occurrence`() {
        val p = parse("早上7点")!!
        assertEquals(LocalDateTime.of(2026, 11, 14, 7, 0), p.dateTime)
    }

    @Test
    fun `repeat modes`() {
        assertEquals(ChineseTimeParser.RepeatMode.DAILY, parse("每天上午9点")?.repeat)
        assertEquals(ChineseTimeParser.RepeatMode.WEEKDAYS, parse("工作日早上8点")?.repeat)
        assertEquals(ChineseTimeParser.RepeatMode.WEEKLY, parse("每周一上午9点")?.repeat)
        val noClock = parse("每天帮我总结")!!
        assertEquals(ChineseTimeParser.RepeatMode.DAILY, noClock.repeat)
        assertEquals(9, noClock.dateTime.hour)
        assertTrue(noClock.ambiguous)
    }

    @Test
    fun `english relative durations`() {
        assertEquals(now.plusMinutes(10), parse("in 10 minutes")?.dateTime)
        assertEquals(now.plusMinutes(10), parse("10 minutes from now")?.dateTime)
        assertEquals(now.plusMinutes(10), parse("10 minutes later")?.dateTime)
        assertEquals(now.plusMinutes(30), parse("in half an hour")?.dateTime)
        assertEquals(now.plusHours(1), parse("in an hour")?.dateTime)
    }

    @Test
    fun `english dates weekdays and meridiem`() {
        assertEquals(LocalDateTime.of(2026, 11, 14, 15, 0), parse("tomorrow at 3pm")?.dateTime)
        assertEquals(LocalDateTime.of(2026, 11, 13, 19, 30), parse("tonight at 7:30pm")?.dateTime)
        assertEquals(LocalDateTime.of(2026, 11, 16, 15, 0), parse("next Monday at 3pm")?.dateTime)
        assertEquals(LocalDateTime.of(2026, 11, 14, 7, 0), parse("wake me up at 7am")?.dateTime)
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 0), parse("November 20 at 9am")?.dateTime)
    }

    @Test
    fun `english repeat modes`() {
        assertEquals(ChineseTimeParser.RepeatMode.DAILY, parse("every day at 8am")?.repeat)
        assertEquals(ChineseTimeParser.RepeatMode.WEEKDAYS, parse("weekdays at 8am")?.repeat)
        assertEquals(ChineseTimeParser.RepeatMode.WEEKLY, parse("every Monday at 3pm")?.repeat)
    }

    @Test
    fun `english bare afternoon clock is ambiguous`() {
        val p = parse("tomorrow at 3")!!
        assertEquals(LocalDateTime.of(2026, 11, 14, 3, 0), p.dateTime)
        assertTrue(p.ambiguous)
    }

    @Test
    fun `duration parser accepts compound and english forms`() {
        assertEquals(5400L, ChineseTimeParser.parseDuration("1小时30分钟")?.seconds)
        assertEquals(5400L, ChineseTimeParser.parseDuration("90 minutes")?.seconds)
        assertEquals(600L, ChineseTimeParser.parseDuration("10 minutes")?.seconds)
        assertEquals(90L * 60, ChineseTimeParser.parseDuration("an hour 30 minutes")?.seconds)
        assertEquals(30L * 60, ChineseTimeParser.parseDuration("half an hour")?.seconds)
    }

    @Test
    fun `duration rejects junk`() {
        assertNull(ChineseTimeParser.parseDuration("hello"))
        assertNull(ChineseTimeParser.parseDuration("0分钟"))
        assertNull(ChineseTimeParser.parseRelativeDuration("明天开会"))
    }

    @Test
    fun `strip time expressions keeps the actionable title`() {
        assertEquals("买牛奶", ChineseTimeParser.stripTimeExpressions("明天下午3点买牛奶"))
        assertEquals("buy milk", ChineseTimeParser.stripTimeExpressions("tomorrow at 3pm buy milk"))
    }

    @Test
    fun `zone is carried and day range is local`() {
        val p = parse("明天上午9点")!!
        assertEquals(zone, p.zoneId)
        val (start, end) = ChineseTimeParser.dayRange(p.dateTime.toLocalDate(), zone)
        assertEquals(LocalDateTime.of(2026, 11, 14, 0, 0), start)
        assertEquals(LocalDateTime.of(2026, 11, 15, 0, 0), end)
    }

    @Test
    fun `chinese numbers cover one through one hundred`() {
        assertEquals(LocalDateTime.of(2026, 11, 14, 23, 0), parse("明天二十三点")?.dateTime)
        assertEquals(LocalDateTime.of(2026, 11, 14, 11, 0), parse("明天十一点")?.dateTime)
    }

    @Test
    fun `unparseable text stays unparsed`() {
        assertNull(parse("你好呀"))
        assertNull(parse(""))
        assertNotNull(parse("中午十二点"))
    }
}
