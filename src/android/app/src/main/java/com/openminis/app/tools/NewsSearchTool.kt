package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import java.io.StringReader
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Recent-news search backed by the public Google News RSS feed.
 *
 * This is a deliberately narrow tool: it returns headlines, source, publish
 * time and the feed's short summary. It does NOT fetch article bodies — the
 * agent already has `browser_use` (get_readable / fetch) for that, and keeping
 * the extraction out of here avoids duplicating it.
 *
 * Freshness policy (same as pilot's NewsSearchTool):
 *   1. Try the last 24 hours.
 *   2. If fewer than `limit` usable items came back, widen to 7 days.
 *   3. Drop entries with no parseable publish time or one in the future
 *      (feeds occasionally emit a few minutes ahead).
 *
 * The RSS source is key-free. The parser is pure Kotlin/JDK (no android.util),
 * so it can be unit-tested on the JVM.
 */
object NewsSearchTool {

    const val NAME = "news_search"

    private const val TAG = "NewsSearchTool"
    private const val BASE = "https://news.google.com/rss/search"
    private const val USER_AGENT = "Minis/1.0 (Android; RSS reader)"

    private const val DEFAULT_LIMIT = 5
    private const val MAX_LIMIT = 10
    private const val MAX_TITLE = 90
    private const val MAX_DESCRIPTION = 90

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS
    private const val WEEK_MS = 7 * DAY_MS

    /** Feeds can be a few minutes ahead of the device clock. */
    private const val FUTURE_SKEW_MS = 5 * MINUTE_MS

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search recent news headlines with Google News RSS. Returns up to 5 " +
            "items with title, source, publish time and a short summary. Prefer this for " +
            "\"latest news\", tech/finance/entertainment headlines and \"what happened today\" " +
            "questions. Use browser_use when the user needs the full article text.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "query" to AgentToolParam("string", "News topic, e.g. \"人工智能\", \"财经\", \"英伟达\". Empty means latest headlines."),
            "range" to AgentToolParam(
                "string",
                "\"auto\" (default): last 24h, widened to 7 days when fewer than `limit` items. " +
                    "\"24h\": last 24 hours only. \"7d\": last 7 days only.",
                enumValues = listOf("auto", "24h", "7d"),
            ),
            "limit" to AgentToolParam("integer", "Maximum items to return (default 5, max 10)."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "query", "range", "limit"),
    )

    // ---------------------------------------------------------------- exec

    /**
     * Blocking call — the caller runs it on an IO dispatcher.
     *
     * The `query` argument may be the user's raw sentence ("今天有什么科技
     * 新闻"); [normalizeQuery] strips the command words. The model may also
     * pass a clean topic ("科技").
     */
    fun execute(argsJson: String): ToolExecutionResult {
        val args = runCatching { org.json.JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("参数不是合法 JSON", false)
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val range = args.optString("range", "auto").trim().lowercase(Locale.ROOT)
            .takeIf { it in setOf("auto", "24h", "7d") } ?: "auto"
        val query = normalizeQuery(args.optString("query"))

        val now = System.currentTimeMillis()
        val dayStart = now - DAY_MS
        val weekStart = now - WEEK_MS

        return when (range) {
            "24h" -> {
                val day = fetchFeed(query, "1d")
                if (!day.ok) return unavailable()
                val items = within(day.items, dayStart, now).take(limit)
                if (items.isEmpty()) {
                    val message = "最近 24 小时没有找到“$query”的新闻。可以换个关键词再搜。"
                    ToolExecutionResult(message, true, spokenSummary = message)
                } else {
                    ToolExecutionResult(
                        format(query, items, "最近24小时"),
                        true,
                        spokenSummary = spokenSummary(items),
                    )
                }
            }

            "7d" -> {
                val week = fetchFeed(query, "7d")
                if (!week.ok) return unavailable()
                val items = within(week.items, weekStart, now).take(limit)
                if (items.isEmpty()) {
                    val message = "最近 7 天没有找到“$query”的新闻。可以换个关键词再搜。"
                    ToolExecutionResult(message, true, spokenSummary = message)
                } else {
                    ToolExecutionResult(
                        format(query, items, "最近7天"),
                        true,
                        spokenSummary = spokenSummary(items),
                    )
                }
            }

            else -> autoRange(query, limit, now, dayStart, weekStart)
        }
    }

    private fun autoRange(
        query: String,
        limit: Int,
        now: Long,
        dayStart: Long,
        weekStart: Long,
    ): ToolExecutionResult {
        val day = fetchFeed(query, "1d")
        val dayItems = if (day.ok) within(day.items, dayStart, now) else emptyList()
        if (day.ok && dayItems.size >= limit) {
            val shown = dayItems.take(limit)
            return ToolExecutionResult(
                format(query, shown, "最近24小时"),
                true,
                spokenSummary = spokenSummary(shown),
            )
        }

        val week = fetchFeed(query, "7d")
        if (!week.ok) {
            return when {
                !day.ok -> unavailable()
                dayItems.isEmpty() -> {
                    val message = "最近 24 小时没有找到“$query”的新闻，7 天数据源暂时不可用。"
                    ToolExecutionResult(message, true, spokenSummary = message)
                }
                else -> {
                    val shown = dayItems.take(limit)
                    ToolExecutionResult(
                        format(query, shown, "最近24小时（7天数据源暂时不可用）"),
                        true,
                        spokenSummary = spokenSummary(shown),
                    )
                }
            }
        }

        val weekItems = within(week.items, weekStart, now)
        val combined = (dayItems + weekItems)
            .distinctBy { it.title to it.source }
            .sortedByDescending { it.publishedAt }
            .take(limit)
        if (combined.isEmpty()) {
            val label = if (day.ok) "最近 7 天" else "最近 7 天（24小时数据源暂时不可用）"
            val message = "${label}没有找到“$query”的新闻。可以换个关键词再搜。"
            return ToolExecutionResult(message, true, spokenSummary = message)
        }
        val label = when {
            !day.ok -> "最近7天（24小时数据源暂时不可用，已扩展范围）"
            dayItems.size < limit -> "最近7天（24小时内结果不足，已扩展范围）"
            else -> "最近7天"
        }
        return ToolExecutionResult(
            format(query, combined, label),
            true,
            spokenSummary = spokenSummary(combined),
        )
    }

    private fun within(items: List<NewsItem>, start: Long, now: Long): List<NewsItem> =
        items.filter { it.publishedAt in start..(now + FUTURE_SKEW_MS) }
            .sortedByDescending { it.publishedAt }

    private fun unavailable(): ToolExecutionResult =
        ToolExecutionResult("新闻服务暂时不可用（网络或数据源不可达），请稍后重试。", false)

    // ---------------------------------------------------------------- http

    private data class Feed(val ok: Boolean, val items: List<NewsItem>)

    private fun fetchFeed(query: String, range: String): Feed {
        val encoded = URLEncoder.encode("$query when:$range", StandardCharsets.UTF_8.name())
        val url = "$BASE?q=$encoded&hl=zh-CN&gl=CN&ceid=CN:zh-Hans"
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/rss+xml, application/xml, text/xml")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.warning(TAG, "RSS HTTP ${response.code} range=$range")
                    Feed(false, emptyList())
                } else {
                    val xml = response.body?.bytes()?.toString(Charsets.UTF_8).orEmpty()
                    Feed(true, parseRss(xml))
                }
            }
        }.getOrElse {
            AppLogger.warning(TAG, "RSS fetch failed range=$range: ${it.javaClass.simpleName}")
            Feed(false, emptyList())
        }
    }

    // -------------------------------------------------------------- parser

    internal data class NewsItem(
        val title: String,
        val source: String,
        val link: String,
        val publishedAt: Long,
        val description: String,
    )

    /**
     * Parse a Google News RSS document. Pure JDK DOM, no Android APIs — the
     * JVM unit tests call this directly with a fixture string.
     */
    internal fun parseRss(xml: String): List<NewsItem> {
        if (xml.isBlank()) return emptyList()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // RSS is untrusted input: never resolve external entities/DTDs.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val document = runCatching {
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrElse {
            AppLogger.warning(TAG, "RSS parse failed: ${it.javaClass.simpleName}")
            return emptyList()
        }

        val items = document.getElementsByTagName("item")
        val out = ArrayList<NewsItem>(items.length)
        for (i in 0 until items.length) {
            val item = items.item(i) as? Element ?: continue
            val rawTitle = childText(item, "title")
            if (rawTitle.isBlank()) continue
            val publishedAt = parseRssDate(childText(item, "pubDate")) ?: continue
            val sourceTag = childText(item, "source")
            val link = childText(item, "link").ifBlank { childAttribute(item, "link", "href") }

            // Google News often appends " - Source" to the title and leaves
            // <source> empty. Split only when the tag is missing.
            val splitAt = rawTitle.lastIndexOf(" - ")
            val source = sourceTag.ifBlank {
                if (splitAt > 0) rawTitle.substring(splitAt + 3) else "新闻来源"
            }
            val title = if (sourceTag.isBlank() && splitAt > 0) {
                rawTitle.substring(0, splitAt)
            } else {
                rawTitle
            }
            val description = plainText(childText(item, "description")).take(MAX_DESCRIPTION)
            out += NewsItem(title.trim(), source.trim(), link.trim(), publishedAt, description)
        }
        return out.distinctBy { it.title to it.source }
    }

    private fun childText(parent: Element, tag: String): String =
        parent.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()

    private fun childAttribute(parent: Element, tag: String, attribute: String): String {
        val node = parent.getElementsByTagName(tag).item(0) as? Element ?: return ""
        return node.getAttribute(attribute).trim()
    }

    internal fun parseRssDate(raw: String): Long? {
        if (raw.isBlank()) return null
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (pattern in patterns) {
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("GMT")
            }
            runCatching { format.parse(raw)?.time }.getOrNull()?.let { return it }
        }
        return null
    }

    /** Strip HTML from an RSS description and decode the common entities. */
    internal fun plainText(html: String): String {
        if (html.isBlank()) return ""
        var text = html
            .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<(br|/p|/div|/li|/h[1-6])[^>]*>"), " ")
            .replace(Regex("(?is)<[^>]+>"), " ")
        text = decodeEntities(text)
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    )

    private fun decodeEntities(value: String): String {
        var out = value
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) { match ->
            match.groupValues[1].toIntOrNull(16)?.let { codePoint(it) } ?: match.value
        }
        out = Regex("&#([0-9]+);").replace(out) { match ->
            match.groupValues[1].toIntOrNull()?.let { codePoint(it) } ?: match.value
        }
        for ((name, replacement) in NAMED_ENTITIES) {
            out = out.replace("&$name;", replacement, ignoreCase = true)
        }
        return out
    }

    private fun codePoint(value: Int): String =
        if (Character.isValidCodePoint(value)) String(Character.toChars(value)) else ""

    // ------------------------------------------------------------- query

    /**
     * Turn a raw request into a search topic. Mirrors pilot's normalization:
     * strip command prefixes, remove generic news words, then map a few
     * high-frequency topics to a useful query. Never returns blank.
     */
    internal fun normalizeQuery(raw: String): String {
        var q = raw.trim()
        val prefixes = listOf(
            "帮我搜一下", "帮我搜索", "帮我看看", "帮我看一下", "搜一下", "搜索一下", "搜索",
            "搜", "查一下", "查查", "查询", "看看", "看一下", "找一下", "帮我找",
            "请搜", "请查", "来点", "说说", "讲讲", "播报一下", "播报",
            "最新的", "最新", "最近的", "最近",
        )
        for (prefix in prefixes.sortedByDescending { it.length }) {
            if (q.startsWith(prefix, ignoreCase = true)) {
                q = q.substring(prefix.length).trim()
            }
        }
        q = q.replace(
            Regex("最近有什么|最近发生|有什么|有啥|最新的?|最近的?|动态|资讯|新闻|消息|热点|头条"),
            " ",
        )
        q = q.replace(Regex("^(?:今天|今日|现在|目前|刚刚|这几天|这两天)\\s*"), "")
        q = q.trim(' ', '，', ',', '。', '.', '？', '?', '！', '!', '：', ':', '；', ';')
        q = q.removeSuffix("吧").removeSuffix("吗").removeSuffix("呢").trim()

        if (q.isBlank()) {
            return when {
                raw.contains("AI", ignoreCase = true) || raw.contains("人工智能") -> "人工智能 AI 最新动态"
                raw.contains("娱乐") || raw.contains("明星") || raw.contains("影视") -> "娱乐新闻"
                raw.contains("财经") || raw.contains("金融") || raw.contains("股市") -> "财经新闻"
                raw.contains("国际") -> "国际新闻"
                raw.contains("国内") -> "国内新闻"
                raw.contains("科技") -> "科技新闻"
                else -> "最新新闻"
            }
        }
        return when {
            q.equals("AI", ignoreCase = true) -> "人工智能 AI 最新动态"
            q == "财经" -> "财经新闻"
            q == "娱乐" -> "娱乐新闻"
            q == "科技" -> "科技新闻"
            q == "国际" -> "国际新闻"
            q == "国内" -> "国内新闻"
            else -> q
        }
    }

    // ------------------------------------------------------------- output

    /** Short readout for TTS: the count plus the first three headlines. */
    internal fun spokenSummary(items: List<NewsItem>): String {
        if (items.isEmpty()) return ""
        return buildString {
            append("找到 ").append(items.size).append(" 条新闻。")
            items.take(3).forEachIndexed { index, item ->
                append("第 ").append(index + 1).append(" 条，").append(item.title.take(40))
                if (item.source.isNotBlank()) {
                    append("（").append(item.source.take(16)).append("）")
                }
                append("。")
            }
        }
    }

    private fun format(query: String, items: List<NewsItem>, rangeLabel: String): String = buildString {
        append("“").append(query).append("”的新闻，范围：").append(rangeLabel)
        append("（共 ").append(items.size).append(" 条）\n")
        items.forEachIndexed { index, item ->
            append('\n').append(index + 1).append(". ").append(item.title.take(MAX_TITLE))
            append("\n   来源：").append(item.source.ifBlank { "新闻来源" })
            append(" · ").append(formatDate(item.publishedAt))
            if (item.description.isNotBlank()) {
                append("\n   摘要：").append(item.description)
            }
        }
    }

    private fun formatDate(time: Long): String {
        val age = (System.currentTimeMillis() - time).coerceAtLeast(0)
        val relative = when {
            age < HOUR_MS -> "${(age / MINUTE_MS).coerceAtLeast(1)} 分钟前"
            age < DAY_MS -> "${age / HOUR_MS} 小时前"
            else -> "${age / DAY_MS} 天前"
        }
        val absolute = SimpleDateFormat("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
            .format(Date(time))
        return "$relative（$absolute）"
    }
}
