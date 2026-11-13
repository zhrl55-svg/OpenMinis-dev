package com.openminis.app.assistant.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantRouteMatcherTest {

    private fun route(
        id: String,
        keywords: List<String>,
        priority: Int = 100,
        allKeywords: List<String> = emptyList(),
        exclude: List<String> = emptyList(),
        pattern: List<String> = emptyList(),
    ) = AssistantRoute(
        id = id,
        label = id,
        priority = priority,
        capability = "calendar",
        action = "create",
        keywords = keywords,
        allKeywords = allKeywords,
        exclude = exclude,
        patterns = pattern,
    )

    @Test
    fun `matching normalizes width punctuation whitespace and case`() {
        assertEquals(
            "open wifi, settings!",
            AssistantRouteMatcher.normalizeText("  ＯＰＥＮ　ＷｉＦｉ， settings！ "),
        )
    }

    @Test
    fun `argument normalization preserves case`() {
        val value = AssistantRouteMatcher.normalizeTextPreservingCase(
            "  运行命令  Echo \"Hello World\" 到 /Tmp/Config.JSON  ",
        )
        assertTrue(value.contains("Echo"))
        assertTrue(value.contains("Hello World"))
        assertTrue(value.contains("/Tmp/Config.JSON"))
    }

    @Test
    fun `best match beats the first entry`() {
        val broad = route("broad", listOf("打开"))
        val specific = route("specific", listOf("打开设置"))
        val ranked = AssistantRouteMatcher.rank("打开设置", listOf(broad, specific))
        assertEquals("specific", ranked.first().route.id)
    }

    @Test
    fun `priority breaks a score tie`() {
        val low = route("low", listOf("创建日程"), priority = 10)
        val high = route("high", listOf("创建日程"), priority = 500)
        val ranked = AssistantRouteMatcher.rank("创建日程", listOf(low, high))
        assertEquals("high", ranked.first().route.id)
    }

    @Test
    fun `longest matched keyword breaks a priority tie`() {
        val short = route("short", listOf("日程"))
        val long = route("long", listOf("查看日程", "日程"))
        val ranked = AssistantRouteMatcher.rank("查看日程", listOf(short, long))
        assertEquals("long", ranked.first().route.id)
        assertEquals(4, ranked.first().longestKeywordLength)
    }

    @Test
    fun `exclude eliminates a route even when a keyword matches`() {
        val r = route("note", listOf("记住"), exclude = listOf("记一下"))
        assertTrue(AssistantRouteMatcher.rank("记住这件事", listOf(r)).isNotEmpty())
        assertTrue(AssistantRouteMatcher.rank("记一下这件事", listOf(r)).isEmpty())
    }

    @Test
    fun `allKeywords requires every phrase`() {
        val r = route("calendar", listOf("安排"), allKeywords = listOf("明天", "会议"))
        assertTrue(AssistantRouteMatcher.rank("明天安排会议", listOf(r)).isNotEmpty())
        assertTrue(AssistantRouteMatcher.rank("明天安排日程", listOf(r)).isEmpty())
    }

    @Test
    fun `single character keywords stay below the routing threshold`() {
        val r = route("weak", listOf("开"))
        val ranked = AssistantRouteMatcher.rank("开", listOf(r))
        assertTrue(ranked.isNotEmpty())
        assertTrue(ranked.first().score < AssistantRouteMatcher.MIN_SCORE)
    }

    @Test
    fun `ascii keywords use word boundaries`() {
        val r = route("call", listOf("call"))
        assertTrue(AssistantRouteMatcher.rank("call mom", listOf(r)).isNotEmpty())
        assertTrue(AssistantRouteMatcher.rank("callback failed", listOf(r)).isEmpty())
    }

    @Test
    fun `disabled unknown and malformed routes are not rankable`() {
        val disabled = route("disabled", listOf("创建日程")).copy(enabled = false)
        val unknown = route("unknown", listOf("创建日程")).copy(capability = "not-real")
        val empty = route("empty", emptyList())
        assertTrue(AssistantRouteMatcher.rank("创建日程", listOf(disabled, unknown, empty)).isEmpty())
    }

    @Test
    fun `matched route reports strength for write gating`() {
        val strong = AssistantRouteMatcher.rank(
            "明天下午三点安排会议",
            listOf(route("strong", listOf("安排会议"))),
        ).single()
        assertTrue(strong.isStrong)
        assertFalse(strong.matchedKeywords.isEmpty())
    }
}
