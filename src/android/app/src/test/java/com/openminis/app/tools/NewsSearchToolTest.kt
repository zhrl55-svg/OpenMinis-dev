package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsSearchToolTest {

    private val rssFixture = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
          <item>
            <title>英伟达发布新芯片 - 科技日报</title>
            <link>https://example.com/a</link>
            <pubDate>Thu, 12 Nov 2026 08:00:00 GMT</pubDate>
            <description>&lt;p&gt;英伟达 &amp; 台积电 宣布合作&lt;/p&gt;</description>
          </item>
          <item>
            <title>深度求索发布新模型</title>
            <source url="https://example.com">新浪科技</source>
            <link>https://example.com/b</link>
            <pubDate>Fri, 13 Nov 2026 09:30:00 GMT</pubDate>
            <description>模型更新</description>
          </item>
          <item>
            <title>重复条目</title>
            <source url="https://example.com">同一来源</source>
            <pubDate>Fri, 13 Nov 2026 09:30:00 GMT</pubDate>
          </item>
          <item>
            <title>重复条目</title>
            <source url="https://example.com">同一来源</source>
            <pubDate>Fri, 13 Nov 2026 09:30:00 GMT</pubDate>
          </item>
        </channel></rss>
    """.trimIndent()

    @Test
    fun `query normalization strips command words and keeps the topic`() {
        assertEquals("科技新闻", NewsSearchTool.normalizeQuery("今天有什么科技新闻"))
        assertEquals("财经新闻", NewsSearchTool.normalizeQuery("帮我看看财经新闻"))
        assertEquals("国际新闻", NewsSearchTool.normalizeQuery("最近有什么国际新闻"))
        assertEquals("最新新闻", NewsSearchTool.normalizeQuery("有什么新闻"))
        assertEquals("人工智能 AI 最新动态", NewsSearchTool.normalizeQuery("AI 最新动态"))
        assertEquals("英伟达", NewsSearchTool.normalizeQuery("英伟达最新消息"))
    }

    @Test
    fun `rss parser extracts titles sources and plain summaries`() {
        val items = NewsSearchTool.parseRss(rssFixture)

        assertEquals(3, items.size)
        assertEquals("英伟达发布新芯片", items[0].title)
        assertEquals("科技日报", items[0].source)
        assertEquals("英伟达 & 台积电 宣布合作", items[0].description)
        assertEquals("https://example.com/a", items[0].link)
        assertEquals("新浪科技", items[1].source)
    }

    @Test
    fun `spoken summary reads the first three headlines with their sources`() {
        val summary = NewsSearchTool.spokenSummary(NewsSearchTool.parseRss(rssFixture))
        assertTrue(summary.startsWith("找到 3 条新闻。"))
        assertTrue(summary.contains("第 1 条，英伟达发布新芯片（科技日报）。"))
        assertTrue(summary.contains("第 2 条，深度求索发布新模型（新浪科技）。"))
        assertTrue(summary.contains("第 3 条，重复条目（同一来源）。"))
    }

    @Test
    fun `plain text strips tags and decodes entities`() {
        assertEquals(
            "Hello &中文",
            NewsSearchTool.plainText("<p>Hello&nbsp;&amp;&#x4E2D;&#25991;</p>"),
        )
    }

    @Test
    fun `rss date accepts rfc822 timestamps`() {
        assertNotNull(NewsSearchTool.parseRssDate("Thu, 12 Nov 2026 08:00:00 GMT"))
        assertNotNull(NewsSearchTool.parseRssDate("Thu, 12 Nov 2026 08:00:00 +0000"))
        assertTrue(NewsSearchTool.parseRssDate("not a date") == null)
    }

    @Test
    fun `blank rss yields no items`() {
        assertTrue(NewsSearchTool.parseRss("").isEmpty())
        assertTrue(NewsSearchTool.parseRss("<rss><channel/></rss>").isEmpty())
    }
}
