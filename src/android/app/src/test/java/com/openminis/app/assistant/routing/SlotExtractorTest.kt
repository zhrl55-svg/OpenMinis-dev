package com.openminis.app.assistant.routing

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotExtractorTest {

    private val zone = ZoneId.of("Asia/Singapore")
    private val now = LocalDateTime.of(2026, 11, 13, 10, 0)
    private val extractor = SlotExtractor({ now }, zone)
    private val routes = DefaultAssistantRoutes.all.associateBy { it.id }

    private fun extract(text: String, routeId: String): SlotExtraction =
        extractor.extract(text, routes.getValue(routeId))

    @Test
    fun `shell command keeps case`() {
        val out = extract("运行命令 Echo \"Hello World\" > /Tmp/Result.TXT", "tool.shell")
        assertEquals("Echo \"Hello World\" > /Tmp/Result.TXT", out.args.getString("command"))
    }

    @Test
    fun `file path and url keep case`() {
        val file = extract("读取 /Tmp/Config.JSON", "tool.file-read")
        assertEquals("/Tmp/Config.JSON", file.args.getString("path"))

        val browser = extract("打开网页 https://Example.COM/Path?Q=One", "tool.browser")
        assertEquals("https://Example.COM/Path?Q=One", browser.args.getString("url"))
    }

    @Test
    fun `note body is verbatim and preserves line breaks`() {
        val out = extract("记笔记：Buy Milk\nSecond Line", "life.note-create")
        assertEquals("Buy Milk\nSecond Line", out.args.getString("content"))
        assertEquals("Buy Milk", out.args.getString("title"))
    }

    @Test
    fun `note search keeps the query case`() {
        val out = extract("搜索笔记 Project Alpha", "life.note-search")
        assertEquals("Project Alpha", out.args.getString("query"))
    }

    @Test
    fun `reminder title and body preserve names`() {
        val out = extract("30分钟后提醒我 Call Mom", "life.reminder")
        assertEquals("Call Mom", out.args.getString("title"))
        assertTrue(out.args.getString("body").contains("Call Mom"))
        assertEquals(1800, out.args.getLong("after"))
    }

    @Test
    fun `memory content preserves case`() {
        val out = extract("记住 Project X uses Port 8080", "tool.memory-write")
        assertEquals("Project X uses Port 8080", out.args.getString("content"))
    }

    @Test
    fun `calendar title keeps place and removes time phrases`() {
        val out = extract("明天下午3点安排会议 Product Launch", "life.calendar-create")
        assertEquals("Product Launch", out.args.getString("title"))
        assertEquals("2026-11-14T15:00:00", out.args.getString("start"))
        assertFalse(out.missing.contains("title"))
    }

    @Test
    fun `calendar location and alarm are deterministic`() {
        val out = extract(
            "明天下午3点安排会议 Product Launch 地点：Room A 提前15分钟",
            "life.calendar-create",
        )
        assertEquals("Room A", out.args.getString("location"))
        assertEquals(15, out.args.getInt("alarmMinutes"))
    }

    @Test
    fun `calendar update separates the lookup title from a new title`() {
        val out = extract(
            "修改日程 项目评审 改名为 产品评审",
            "life.calendar-update",
        )
        assertEquals("项目评审", out.args.getString("id_or_title"))
        assertEquals("产品评审", out.args.getString("title"))
    }

    @Test
    fun `calendar update does not mistake a new time for a new title`() {
        val out = extract(
            "修改日程 项目评审 改成明天下午3点",
            "life.calendar-update",
        )
        assertEquals("项目评审", out.args.getString("id_or_title"))
        assertFalse(out.args.has("title"))
        assertEquals("2026-11-14T15:00:00", out.args.getString("start"))
    }

    @Test
    fun `alarm uses ISO date and preserves label`() {
        val out = extract("明天早上7点设个闹钟 Wake UP", "life.alarm")
        assertEquals("2026-11-14T07:00:00", out.args.getString("time"))
        assertEquals("2026-11-14", out.args.getString("targetDate"))
        assertTrue(out.args.getString("label").contains("Wake UP"))
    }

    @Test
    fun `timer extracts seconds and label`() {
        val out = extract("开始一个25分钟计时器 Tea", "life.timer")
        assertEquals(25 * 60L, out.args.getLong("duration"))
        assertTrue(out.args.getString("label").contains("Tea"))
    }

    @Test
    fun `settings target and value`() {
        val open = extract("打开WiFi设置", "life.settings-open")
        assertEquals("wifi", open.args.getString("target"))

        val set = extract("把亮度调到 50%", "life.settings-set")
        assertEquals("brightness", set.args.getString("target"))
        assertEquals("50", set.args.getString("value"))
    }

    @Test
    fun `scheduled task prompt strips the cadence`() {
        val out = extract("每天帮我总结新闻", "life.scheduled-create")
        assertEquals("总结新闻", out.args.getString("prompt"))
        assertEquals("DAILY", out.args.getString("repeat"))
        assertTrue(out.parsedTime?.ambiguous == true)
    }

    @Test
    fun `relative scheduled task uses after instead of demanding a clock time`() {
        val out = extract("10分钟后定时帮我备份照片", "life.scheduled-create")
        assertEquals(600L, out.args.getLong("after"))
        assertFalse(out.args.has("at"))
    }

    @Test
    fun `contacts and app names are not folded`() {
        val contact = extract("打电话给 Alice Wang", "life.contacts")
        assertEquals("Alice Wang", contact.args.getString("name"))

        val app = extract("打开 WeChat", "life.app-open")
        assertEquals("WeChat", app.args.getString("app"))
    }

    @Test
    fun `explicit direct call is marked for the confirmed CALL_PHONE path`() {
        val out = extract("直接拨打张三", "life.contacts")
        assertEquals("张三", out.args.getString("name"))
        assertTrue(out.args.getBoolean("directCall"))
        assertFalse(extract("打电话给张三", "life.contacts").args.has("directCall"))
    }

    @Test
    fun `ambiguous date without time is flagged`() {
        val out = extract("提醒我明天交报告", "life.reminder")
        assertTrue(out.ambiguous)
        assertEquals("2026-11-14T00:00:00", out.args.getString("at"))
    }

    @Test
    fun `news and stock queries are optional and model-free`() {
        val news = extract("今天有什么科技新闻", "info.news")
        assertEquals("今天有什么科技新闻", news.args.getString("query"))
        assertTrue(news.missing.isEmpty())

        val stock = extract("上证指数现在多少", "info.stock")
        assertEquals("上证指数现在多少", stock.args.getString("query"))
        assertTrue(stock.missing.isEmpty())
    }
}
