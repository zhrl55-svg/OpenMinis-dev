package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A-share index quotes from Sina's free quote endpoint.
 *
 * ## Why this endpoint
 * It needs no API key and works on mainland networks. Two details are
 * mandatory and easy to get wrong:
 * 1. A `Referer` header — Sina's anti-leech returns 403 without it.
 * 2. The body is **GBK**, not UTF-8. Decoding it as UTF-8 yields mojibake.
 *
 * The response is a JavaScript assignment, not JSON:
 * `var quote = "上证指数,3634.35,-21.47,-0.59,...";`
 *
 * ## Scope
 * This tool covers the main A-share indices only. Individual stocks are
 * deliberately rejected with a clear message instead of silently falling back
 * to the three majors (a bug pilot's StockTool had: asking for 贵州茅台股价
 * answered with 上证/深证/创业板).
 */
object StockQuoteTool {

    const val NAME = "stock_quote"

    private const val TAG = "StockQuoteTool"
    private const val BASE = "https://hq.sinajs.cn/list="
    private const val REFERER = "https://finance.sina.com.cn"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    private const val UNSUPPORTED =
        "目前支持 A 股主要指数（上证、深证、创业板、沪深300），暂不支持该指数、个股或其它市场行情。"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Longest keyword first so "沪深300" wins over "沪深". */
    private val INDEXES: List<Pair<String, String>> = listOf(
        "上证指数" to "s_sh000001",
        "上证" to "s_sh000001",
        "沪指" to "s_sh000001",
        "大盘" to "s_sh000001",
        "深证成指" to "s_sz399001",
        "深证" to "s_sz399001",
        "成指" to "s_sz399001",
        "创业板指" to "s_sz399006",
        "创业板" to "s_sz399006",
        "沪深300" to "s_sh000300",
        "沪深" to "s_sh000300",
    ).sortedByDescending { it.first.length }

    private val DEFAULT_CODES = listOf("s_sh000001", "s_sz399001", "s_sz399006")

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Get current A-share index quotes (上证指数, 深证成指, 创业板, 沪深300) " +
            "from Sina Finance. Use for \"大盘怎么样\", \"上证指数多少\", \"创业板涨了吗\". " +
            "Individual stocks and non-A-share markets are not supported.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "query" to AgentToolParam("string", "Index or market phrase, e.g. \"上证指数\", \"深证成指\", \"创业板\", \"大盘\". Empty returns the three major indices."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "query"),
    )

    // ---------------------------------------------------------------- exec

    /** Blocking call — the caller runs it on an IO dispatcher. */
    fun execute(argsJson: String): ToolExecutionResult {
        val args = runCatching { org.json.JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("参数不是合法 JSON", false)
        val query = args.optString("query").trim()
        val codes = selectIndexCodes(query)
            ?: return ToolExecutionResult(UNSUPPORTED, true, spokenSummary = UNSUPPORTED)

        val url = BASE + codes.joinToString(",")
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.warning(TAG, "HTTP ${response.code}")
                    ToolExecutionResult("行情服务暂时不可用（HTTP ${response.code}），请稍后重试。", false)
                } else {
                    val raw = response.body?.bytes()?.toString(Charset.forName("GBK")).orEmpty()
                    val quotes = parseQuotes(raw)
                    if (quotes.isEmpty()) {
                        AppLogger.warning(TAG, "no quotes parsed: ${raw.take(120)}")
                        ToolExecutionResult("行情解析失败，请稍后重试。", false)
                    } else {
                        ToolExecutionResult(
                            formatQuotes(quotes),
                            true,
                            spokenSummary = spokenSummary(quotes),
                        )
                    }
                }
            }
        }.getOrElse {
            AppLogger.warning(TAG, "quote fetch failed: ${it.javaClass.simpleName}")
            ToolExecutionResult("行情服务暂时不可用（网络或超时），请稍后重试。", false)
        }
    }

    // ------------------------------------------------------------- selector

    /**
     * Resolve the user's phrase to Sina codes.
     *
     * @return distinct codes; [DEFAULT_CODES] when the phrase is generic
     *   ("行情怎么样"); `null` when it names an individual stock, fund or a
     *   market this tool does not cover.
     */
    internal fun selectIndexCodes(query: String): List<String>? {
        val q = query.trim()
        if (q.isEmpty()) return DEFAULT_CODES
        if (unsupportedMarket(q)) return null

        val matched = LinkedHashSet<String>()
        for ((keyword, code) in INDEXES) {
            if (q.contains(keyword, ignoreCase = true)) matched += code
        }
        if (matched.isNotEmpty()) return matched.toList()

        if (looksLikeIndividualStock(q)) return null
        return DEFAULT_CODES
    }

    private val UNSUPPORTED_MARKETS = listOf(
        "美股", "港股", "纳斯达克", "道琼斯", "标普", "日经", "恒生",
        "期货", "基金", "ETF", "etf", "债券", "黄金", "外汇", "比特币", "btc",
        // Indices whose names contain a supported keyword but mean something
        // else (上证50 would otherwise resolve to 上证指数).
        "上证50", "中证", "科创", "北证",
    )

    private fun unsupportedMarket(q: String): Boolean =
        UNSUPPORTED_MARKETS.any { q.contains(it, ignoreCase = true) }

    private val GENERIC_STOCK_TERMS = listOf(
        "股票行情", "股市行情", "股票", "股市", "股价", "行情", "大盘", "a股", "A股",
        "沪深", "创业板", "上证", "深证", "沪指", "指数", "市场", "走势", "情况",
        "怎么样", "如何", "多少点", "多少", "现在", "今天", "目前", "刚刚",
        "涨了", "跌了", "涨停", "跌停", "看看", "查一下", "查询", "一下",
    )

    /**
     * "贵州茅台股价" → true; "股票行情怎么样" → false.
     *
     * The check only runs when no known index keyword matched, so a phrase
     * that names both an index and a stock still answers the index part.
     */
    private fun looksLikeIndividualStock(q: String): Boolean {
        if (!q.contains("股票") && !q.contains("股价") && !q.contains("个股")) return false
        var rest = q
        for (term in GENERIC_STOCK_TERMS.sortedByDescending { it.length }) {
            rest = rest.replace(term, " ", ignoreCase = true)
        }
        rest = rest.replace(Regex("[\\s,，。.？?！!：:、；;]"), "")
        return rest.isNotEmpty()
    }

    // -------------------------------------------------------------- parser

    internal data class Quote(
        val name: String,
        val point: String,
        val change: String,
        val changePercent: String,
    ) {
        val isUp: Boolean get() = !change.startsWith("-")
    }

    internal fun parseQuotes(raw: String): List<Quote> {
        val regex = Regex("""var hq_str_s_\w+="([^"]*)"""")
        return regex.findAll(raw).mapNotNull { match ->
            val fields = match.groupValues[1].split(',')
            if (fields.size < 4 || fields[0].isBlank()) return@mapNotNull null
            Quote(
                name = fields[0].trim(),
                point = fields[1].trim(),
                change = fields[2].trim(),
                changePercent = fields[3].trim(),
            )
        }.toList()
    }

    /** Short readout for TTS: one clause per index, no header or bullets. */
    internal fun spokenSummary(quotes: List<Quote>): String =
        quotes.joinToString("；") { quoteLine(it) }

    private fun quoteLine(quote: Quote): String {
        val direction = if (quote.isUp) "涨" else "跌"
        val point = quote.point.toDoubleOrNull()
            ?.let { String.format(Locale.US, "%.2f", it) }
            ?: quote.point
        return "${quote.name} $point 点，$direction ${quote.changePercent.removePrefix("-")}%"
    }

    internal fun formatQuotes(quotes: List<Quote>): String = buildString {
        append("A 股行情\n")
        quotes.forEach { quote ->
            append("• ").append(quoteLine(quote)).append('\n')
        }
    }.trimEnd()
}
