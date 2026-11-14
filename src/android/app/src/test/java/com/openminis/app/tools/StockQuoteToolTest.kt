package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockQuoteToolTest {

    private val sample = buildString {
        append("var hq_str_s_").append("sh000001=\"上证指数,3634.3530,-21.4765,-0.59,4722936,77061417\";\n")
        append("var hq_str_s_").append("sz399001=\"深证成指,10968.79,-229.189,-2.05,559255207,88505404\";\n")
        append("var hq_str_s_").append("sh600519=\"\";")
    }

    @Test
    fun `parser reads the GBK-decoded sinajs payload`() {
        val quotes = StockQuoteTool.parseQuotes(sample)
        assertEquals(2, quotes.size)
        assertEquals("上证指数", quotes[0].name)
        assertEquals("3634.3530", quotes[0].point)
        assertEquals(false, quotes[0].isUp)
        assertEquals("深证成指", quotes[1].name)
        assertEquals("-2.05", quotes[1].changePercent)
    }

    @Test
    fun `formatter speaks direction and rounds the point`() {
        val text = StockQuoteTool.formatQuotes(StockQuoteTool.parseQuotes(sample))
        assertTrue(text.contains("上证指数 3634.35 点，跌 0.59%"))
        assertTrue(text.contains("深证成指 10968.79 点，跌 2.05%"))
    }

    @Test
    fun `spoken summary joins quotes without the header or bullets`() {
        val summary = StockQuoteTool.spokenSummary(StockQuoteTool.parseQuotes(sample))
        assertEquals(
            "上证指数 3634.35 点，跌 0.59%；深证成指 10968.79 点，跌 2.05%",
            summary,
        )
    }

    @Test
    fun `explicit indices select only those codes`() {
        assertEquals(listOf("s_sh000001"), StockQuoteTool.selectIndexCodes("上证指数现在多少"))
        assertEquals(listOf("s_sh000001"), StockQuoteTool.selectIndexCodes("今天大盘怎么样"))
        assertEquals(listOf("s_sz399001"), StockQuoteTool.selectIndexCodes("深证成指涨了还是跌了"))
        assertEquals(listOf("s_sh000300"), StockQuoteTool.selectIndexCodes("沪深300怎么样"))
    }

    @Test
    fun `generic market phrases fall back to the three majors`() {
        assertEquals(
            listOf("s_sh000001", "s_sz399001", "s_sz399006"),
            StockQuoteTool.selectIndexCodes(""),
        )
        assertEquals(
            listOf("s_sh000001", "s_sz399001", "s_sz399006"),
            StockQuoteTool.selectIndexCodes("股票行情怎么样"),
        )
    }

    @Test
    fun `individual stocks and other markets are rejected instead of silently returning indices`() {
        assertNull(StockQuoteTool.selectIndexCodes("贵州茅台股价"))
        assertNull(StockQuoteTool.selectIndexCodes("买什么股票"))
        assertNull(StockQuoteTool.selectIndexCodes("纳斯达克指数怎么样"))
        assertNull(StockQuoteTool.selectIndexCodes("上证50怎么样"))
        assertNull(StockQuoteTool.selectIndexCodes("中证500指数"))
    }
}
