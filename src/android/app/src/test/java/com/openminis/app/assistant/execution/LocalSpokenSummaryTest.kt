package com.openminis.app.assistant.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSpokenSummaryTest {

    @Test
    fun `weather speaks the formatted answer`() {
        assertEquals(
            "现在：晴，25.0°C。",
            LocalSpokenSummary.forCapability(
                capability = "weather",
                action = "current",
                output = "{}",
                answer = "现在：晴，25.0°C。",
                success = true,
            ),
        )
    }

    @Test
    fun `device status speaks the formatted answer`() {
        assertEquals(
            "电量 80%，正在充电（USB）。",
            LocalSpokenSummary.forCapability(
                capability = "device",
                action = "battery",
                output = "{}",
                answer = "电量 80%，正在充电（USB）。",
                success = true,
            ),
        )
    }

    @Test
    fun `calendar list counts events and reads the first titles`() {
        val output = """
            {
              "events": [
                {"title": "周会", "start": "2026-11-14T10:00:00"},
                {"title": "项目评审", "start": "2026-11-14T14:00:00"}
              ],
              "count": 2
            }
        """.trimIndent()
        assertEquals(
            "找到 2 条：周会、项目评审。",
            LocalSpokenSummary.forCapability(
                capability = "calendar",
                action = "list",
                output = output,
                answer = "",
                success = true,
            ),
        )
    }

    @Test
    fun `freebusy reads the first free slot`() {
        val output = """
            {
              "busy": [{"title": "周会", "start": "2026-11-14T10:00:00", "end": "2026-11-14T11:00:00"}],
              "free": [{"start": "2026-11-14T09:00:00", "end": "2026-11-14T10:00:00"}]
            }
        """.trimIndent()
        assertEquals(
            "有空，第一个空闲时段是 09:00 到 10:00。",
            LocalSpokenSummary.forCapability(
                capability = "calendar",
                action = "freebusy",
                output = output,
                answer = "",
                success = true,
            ),
        )
    }

    @Test
    fun `freebusy with no free slot says so`() {
        assertEquals(
            "这段时间没有空闲。",
            LocalSpokenSummary.forCapability(
                capability = "calendar",
                action = "freebusy",
                output = """{"busy":[],"free":[]}""",
                answer = "",
                success = true,
            ),
        )
    }

    @Test
    fun `contacts array reads names`() {
        val output = """[{"name":"张三"},{"name":"李四"},{"name":"王五"},{"name":"赵六"}]"""
        assertEquals(
            "找到 4 条：张三、李四、王五。",
            LocalSpokenSummary.forCapability(
                capability = "contacts",
                action = "search",
                output = output,
                answer = "",
                success = true,
            ),
        )
    }

    @Test
    fun `empty result arrays say nothing was found`() {
        assertEquals(
            "没有找到结果。",
            LocalSpokenSummary.forCapability(
                capability = "reminder",
                action = "list",
                output = """{"reminders":[],"count":0}""",
                answer = "",
                success = true,
            ),
        )
    }

    @Test
    fun `plain text media status is spoken as is`() {
        assertEquals(
            "正在播放：晴天（12/240 秒），播放中",
            LocalSpokenSummary.forCapability(
                capability = "media",
                action = "status",
                output = "正在播放：晴天（12/240 秒），播放中",
                answer = "正在播放：晴天（12/240 秒），播放中",
                success = true,
            ),
        )
    }

    @Test
    fun `writes and failures stay silent`() {
        assertEquals(
            "",
            LocalSpokenSummary.forCapability(
                capability = "calendar",
                action = "create",
                output = "{}",
                answer = "已创建",
                success = true,
            ),
        )
        assertEquals(
            "",
            LocalSpokenSummary.forCapability(
                capability = "weather",
                action = "current",
                output = "定位失败",
                answer = "定位失败",
                success = false,
            ),
        )
        assertEquals(
            "",
            LocalSpokenSummary.forTool("news_search", "[]", true),
        )
    }

    @Test
    fun `memory recall gets a short readout`() {
        assertEquals(
            "没有找到相关记忆。",
            LocalSpokenSummary.forTool("memory_get", "No matches found for: 旅行", true),
        )
        val summary = LocalSpokenSummary.forTool(
            "memory_get",
            "user.md:12: 偏好靠窗座位\nuser.md:18: 喜欢早上出发",
            true,
        )
        assertTrue(summary.startsWith("找到相关记忆"))
        assertTrue(summary.contains("偏好靠窗座位"))
    }

    @Test
    fun `summaries are capped for speech`() {
        val longLine = "长".repeat(400)
        val summary = LocalSpokenSummary.summarize(longLine)
        assertTrue(summary.length <= 220)
    }
}
