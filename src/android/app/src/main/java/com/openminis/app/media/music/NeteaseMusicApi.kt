package com.openminis.app.media.music

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One playable song. [durationMs] is 0 when the catalogue did not say. */
data class MusicTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val coverUrl: String = "",
)

/** The logged-in NetEase account. */
data class Profile(val userId: Long, val nickname: String)

/** A single lyric line anchored at [timeMs] from the start of the track. */
data class LyricLine(val timeMs: Long, val text: String)

/** Where a QR login stands right now. */
enum class QrState {
    /** The QR is on screen, nobody scanned it yet. */
    WAITING_SCAN,

    /** Scanned - waiting for the phone to confirm. */
    WAITING_CONFIRM,

    /** Logged in; the cookie is in the companion value. */
    SUCCESS,

    /** The QR timed out and has to be refreshed. */
    EXPIRED,

    /** The proxy refused the request. */
    ERROR,

    /** The proxy could not be reached at all. */
    NETWORK_ERROR,
}

/**
 * A QR login session. The key only exists on the API instance that created
 * it, so [baseUrl] travels with it and every step of the login is pinned
 * to that address.
 */
data class QrSession(val baseUrl: String, val key: String)

/**
 * Client for the self-hosted NeteaseCloudMusicApi on the buntu host.
 *
 * The proxy mirrors the upstream API, so the paths are the upstream ones:
 *  - `/search`      song search
 *  - `/top/song`    the public chart, used as "something to play"
 *  - `/song/url`    the streaming URL for one song (NOT `/song/url/v1`,
 *                   which trips the upstream risk control and answers -460)
 *  - `/lyric`       timed lyrics
 *
 * Every call tries the LAN address first and the tailnet address second, and
 * remembers whichever answered so the next call goes straight there.
 */
class NeteaseMusicApi(
    private val settingsProvider: () -> MusicSettings = { MusicSettings() },
) {

    /**
     * The base client. Every request asks [com.openminis.app.network.NetworkHttp]
     * for the routed version, so the tailnet address works when the phone
     * is off the home network (the app's embedded Tailscale node carries it)
     * and the LAN address works when it is on it.
     */
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /** The base that answered last time; tried first on the next call. */
    @Volatile
    private var activeBase: String? = null

    /** Search the catalogue. Returns an empty list when nothing matched. */
    suspend fun search(keywords: String, limit: Int = 20): List<MusicTrack> {
        val query = keywords.trim()
        if (query.isBlank()) return emptyList()
        val json = get("/search", mapOf("keywords" to query, "limit" to limit.toString(), "type" to "1"))
            ?: return emptyList()
        return parseTracks(json.optJSONObject("result")?.optJSONArray("songs"))
    }

    /** The public chart - the "just play something" list. */
    suspend fun hotSongs(limit: Int = 20): List<MusicTrack> {
        val json = get("/top/song", mapOf("type" to "0")) ?: return emptyList()
        return parseTracks(json.optJSONArray("data")).take(limit)
    }

    /**
     * 网易云「最近播放」。需要登录账号的 uid；结果为空时由调用方回退到
     * 本地播放历史（[MusicHistoryStore]）。
     */
    suspend fun recentSongs(limit: Int = 20): List<MusicTrack> {
        val cookie = settingsProvider().cookie
        if (cookie.isBlank()) return emptyList()
        val profile = loginProfile() ?: return emptyList()
        val json = get(
            "/user/record",
            mapOf("uid" to profile.userId.toString(), "type" to "1"),
        ) ?: return emptyList()
        val all = json.optJSONObject("data")?.optJSONArray("allData") ?: return emptyList()
        return parseTracks(all).take(limit)
    }

    /**
     * 官方榜。3778678=热歌榜，3779629=新歌榜，19723756=飙升榜。
     * 和 pilot 一样走 `/playlist/detail`。
     */
    suspend fun chartSongs(chartId: Long, limit: Int = 20): List<MusicTrack> {
        val json = get("/playlist/detail", mapOf("id" to chartId.toString())) ?: return emptyList()
        val tracks = json.optJSONObject("playlist")?.optJSONArray("tracks") ?: return emptyList()
        return parseTracks(tracks).take(limit)
    }

    /** 每日推荐（需登录）。 */
    suspend fun dailyRecommendations(limit: Int = 20): List<MusicTrack> {
        if (settingsProvider().cookie.isBlank()) return emptyList()
        val json = get("/recommend/songs", emptyMap()) ?: return emptyList()
        val songs = json.optJSONObject("data")?.optJSONArray("dailySongs") ?: return emptyList()
        return parseTracks(songs).take(limit)
    }

    /** 我喜欢的音乐（需登录）：先取 id 列表，再取歌曲详情。 */
    suspend fun likedSongs(limit: Int = 20): List<MusicTrack> {
        val cookie = settingsProvider().cookie
        if (cookie.isBlank()) return emptyList()
        val profile = loginProfile() ?: return emptyList()
        val list = get("/likelist", mapOf("uid" to profile.userId.toString())) ?: return emptyList()
        val ids = list.optJSONArray("ids")
            ?: list.optJSONObject("data")?.optJSONArray("ids")
            ?: return emptyList()
        val selected = buildList {
            for (i in 0 until minOf(ids.length(), limit)) add(ids.optLong(i))
        }.filter { it > 0L }
        if (selected.isEmpty()) return emptyList()
        val detail = get("/song/detail", mapOf("ids" to selected.joinToString(","))) ?: return emptyList()
        val songs = detail.optJSONArray("songs") ?: return emptyList()
        return parseTracks(songs).take(limit)
    }

    /** 账号信息；cookie 失效时返回 null。 */
    suspend fun loginProfile(): Profile? {
        val cookie = settingsProvider().cookie
        if (cookie.isBlank()) return null
        val json = get("/login/status", emptyMap(), cookieOverride = cookie) ?: return null
        val data = json.optJSONObject("data") ?: return null
        val profile = data.optJSONObject("profile")
        val account = data.optJSONObject("account")
        val userId = account?.optLong("id")?.takeIf { it > 0L }
            ?: profile?.optLong("userId")?.takeIf { it > 0L }
            ?: return null
        return Profile(userId, profile?.optString("nickname").orEmpty())
    }

    // ── 扫码登录 ────────────────────────────────────────────────────────

    /**
     * Create a QR login session.
     *
     * The key belongs to the API instance that created it, so the base URL
     * travels with it: every later step of the same login is pinned to that
     * address, otherwise the proxy would answer "key not found".
     */
    suspend fun qrSession(): QrSession? {
        val json = get("/login/qr/key", mapOf("timestamp" to System.currentTimeMillis().toString()))
            ?: return null
        val key = json.optJSONObject("data")?.optString("unikey").orEmpty()
        if (key.isBlank()) return null
        val base = activeBase ?: return null
        return QrSession(base, key)
    }

    /** The QR image as a `data:image/png;base64,…` URI (null when it failed). */
    suspend fun qrImage(session: QrSession): String? {
        val json = get(
            "/login/qr/create",
            mapOf(
                "key" to session.key,
                "qrimg" to "true",
                "timestamp" to System.currentTimeMillis().toString(),
            ),
            pinnedBase = session.baseUrl,
        ) ?: return null
        val img = json.optJSONObject("data")?.optString("qrimg").orEmpty()
        if (img.isBlank()) return null
        return if (img.startsWith("data:")) img else "data:image/png;base64,$img"
    }

    /**
     * Poll the login state. On success the second element is the cookie to
     * store - the proxy appends Set-Cookie attributes to it, so only the
     * real cookie pairs are kept.
     */
    suspend fun qrCheck(session: QrSession): Pair<QrState, String?> {
        val json = get(
            "/login/qr/check",
            mapOf(
                "key" to session.key,
                "timestamp" to System.currentTimeMillis().toString(),
            ),
            pinnedBase = session.baseUrl,
        ) ?: return QrState.NETWORK_ERROR to null
        val code = json.optInt("code", -1)
        val cookie = sanitizeCookie(json.optString("cookie")).takeIf { it.isNotBlank() }
        val state = when (code) {
            800 -> QrState.EXPIRED
            801 -> QrState.WAITING_SCAN
            802 -> QrState.WAITING_CONFIRM
            803 -> QrState.SUCCESS
            else -> QrState.ERROR
        }
        return state to cookie
    }

    /** The nickname of the logged-in account, or null when the cookie is dead. */
    suspend fun loginNickname(cookie: String): String? {
        if (cookie.isBlank()) return null
        val json = get("/login/status", emptyMap(), pinnedBase = null, cookieOverride = cookie)
            ?: return null
        val profile = json.optJSONObject("data")?.optJSONObject("profile")
        return profile?.optString("nickname").orEmpty().takeIf { it.isNotBlank() }
    }

    /** Keep only the real cookie pairs; the proxy appends cookie attributes. */
    private fun sanitizeCookie(raw: String): String = raw.split(';')
        .map(String::trim)
        .filter { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) return@filter false
            val name = pair.substring(0, separator).trim().lowercase(Locale.ROOT)
            name !in setOf("path", "expires", "max-age", "domain", "samesite", "secure", "httponly")
        }
        .joinToString("; ")

    /**
     * The streaming URL for [songId].
     *
     * Null when the proxy refuses (not logged in, VIP-only track, region
     * lock): the caller reports that instead of silently doing nothing.
     */
    suspend fun playUrl(songId: Long): String? {
        val json = get("/song/url", mapOf("id" to songId.toString(), "br" to "320000"))
            ?: return null
        val entry = json.optJSONArray("data")?.optJSONObject(0) ?: return null
        val url = entry.optString("url")
        if (url.isBlank()) {
            val code = entry.optInt("code", -1)
            AppLogger.info(TAG, "play url empty for id=$songId code=$code (login or VIP limited)")
            return null
        }
        return url
    }

    /** Timed lyrics; empty when the song has none. */
    suspend fun lyrics(songId: Long): List<LyricLine> {
        val json = get("/lyric", mapOf("id" to songId.toString())) ?: return emptyList()
        val raw = json.optJSONObject("klyric")?.optString("lyric").orEmpty()
            .ifBlank { json.optJSONObject("lrc")?.optString("lyric").orEmpty() }
        return parseLyric(raw)
    }

    /** GET one path, trying every known base until one answers. */
    private suspend fun get(
        path: String,
        params: Map<String, String>,
        pinnedBase: String? = null,
        cookieOverride: String? = null,
    ): JSONObject? =
        withContext(Dispatchers.IO) {
            val bases = pinnedBase?.let { listOf(it) } ?: bases()
            var lastError: String? = null
            for (base in bases) {
                val url = buildUrl(base, path, params)
                if (url == null) continue
                try {
                    val request = Request.Builder()
                        .url(url)
                        .apply {
                            val cookie = cookieOverride ?: settingsProvider().cookie
                            if (cookie.isNotBlank()) header("Cookie", cookie)
                        }
                        .build()
                    routedClient().newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            lastError = "HTTP ${response.code}"
                            AppLogger.info(TAG, "GET $path via $base -> $lastError")
                            return@use null
                        }
                        val body = response.body?.string().orEmpty()
                        val json = runCatching { JSONObject(body) }.getOrNull()
                        if (json == null) {
                            // A 2xx whose body is not JSON means something in
                            // the middle answered instead of the proxy. Say
                            // what came back - otherwise this is a dead end.
                            lastError = "bad json (${body.length} chars): ${body.take(120)}"
                            AppLogger.warning(TAG, "GET $path via $base -> $lastError")
                            return@use null
                        }
                        activeBase = base
                        // `use` returns to the caller, but this whole block
                        // IS the withContext body: a plain `return@use`
                        // would drop the JSON on the floor and every call
                        // would look like a failure.
                        return@withContext json
                    }
                } catch (t: Throwable) {
                    lastError = "${t.javaClass.simpleName}: ${t.message}"
                    AppLogger.info(TAG, "GET $path via $base failed: $lastError")
                }
            }
            AppLogger.warning(TAG, "GET $path failed on all bases: $lastError")
            null
        }

    /**
     * The client for this request: the app's network route decides whether
     * it goes out directly or through the local entry (the embedded
     * Tailscale node), which is what makes the tailnet address reachable
     * from a phone that is not on the home network.
     */
    private fun routedClient(): OkHttpClient =
        com.openminis.app.network.NetworkHttp.client(NETWORK_TAG, baseClient)

    /** The bases to try, the one that worked last first. */
    private fun bases(): List<String> {
        val settings = settingsProvider()
        val all = listOf(settings.baseUrl, settings.fallbackUrl)
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
        val active = activeBase
        return if (active != null && all.contains(active)) {
            listOf(active) + all.filter { it != active }
        } else {
            all
        }
    }

    private fun buildUrl(base: String, path: String, params: Map<String, String>): String? {
        val root = base.toHttpUrlOrNull() ?: return null
        val builder = root.newBuilder()
        val cleanPath = path.trimStart('/')
        builder.addPathSegments(cleanPath)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    companion object {
        private const val TAG = "NeteaseMusic"

        /** The network policy tag the music traffic follows. */
        private const val NETWORK_TAG = "music"

        /**
         * Parse a song list. The proxy answers with two different shapes
         * depending on the endpoint (`artists`/`album` on `/search`,
         * `ar`/`al` on `/top/song`), so every field is read defensively.
         */
        internal fun parseTracks(songs: JSONArray?): List<MusicTrack> {
            if (songs == null) return emptyList()
            val out = ArrayList<MusicTrack>(songs.length())
            for (i in 0 until songs.length()) {
                // `/user/record` wraps each entry as {playCount, song:{…}}.
                val entry = songs.optJSONObject(i) ?: continue
                val song = entry.optJSONObject("song") ?: entry
                val id = song.optLong("id", 0L)
                if (id <= 0L) continue
                val title = song.optString("name").ifBlank { song.optString("title") }
                if (title.isBlank()) continue
                val artists = song.optJSONArray("artists") ?: song.optJSONArray("ar")
                val album = song.optJSONObject("album") ?: song.optJSONObject("al")
                out.add(
                    MusicTrack(
                        id = id,
                        title = title,
                        artist = joinNames(artists),
                        album = album?.optString("name").orEmpty(),
                        durationMs = song.optLong("duration", song.optLong("dt", 0L)),
                        coverUrl = coverUrl(album),
                    ),
                )
            }
            return out
        }

        /**
         * The cover image.
         *
         * `/top/song` hands out `picUrl` directly, but `/search` only gives
         * the numeric `picId` - the image URL is built from it, which is how
         * the NetEase CDN addresses covers.
         */
        private fun coverUrl(album: JSONObject?): String {
            if (album == null) return ""
            val direct = album.optString("picUrl")
            if (direct.isNotBlank()) return direct
            val picId = album.optLong("picId", 0L)
            if (picId <= 0L) return ""
            return "https://p2.music.126.net/$picId/$picId.jpg"
        }

        private fun joinNames(array: JSONArray?): String {
            if (array == null) return ""
            val names = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val name = array.optJSONObject(i)?.optString("name").orEmpty()
                if (name.isNotBlank()) names.add(name)
            }
            return names.joinToString(" / ")
        }

        private val lyricTimeTag = Regex("""\[(\d{1,3}):([0-5]?\d)(?:[.:](\d{1,3}))?]""")

        private val lyricCreditTag = Regex(
            """^(作词|作曲|编曲|制作人|混音|母带|录音|监制|吉他|贝斯|鼓|键盘|弦乐|出品|发行|词|曲)\s*[:：]""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Parse an LRC blob into timed lines. Credit lines ("作词 : …") are
         * dropped: they sit in the intro for a long time and make the lyric
         * display look stuck.
         */
        internal fun parseLyric(raw: String): List<LyricLine> {
            if (raw.isBlank()) return emptyList()
            val out = sortedMapOf<Long, String>()
            raw.lineSequence().forEach { rawLine ->
                val tags = lyricTimeTag.findAll(rawLine).toList()
                if (tags.isEmpty()) return@forEach
                val text = rawLine.replace(lyricTimeTag, "").trim()
                if (text.isBlank()) return@forEach
                tags.forEach { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                    val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                    val fraction = match.groupValues[3]
                    val millis = when (fraction.length) {
                        0 -> 0L
                        1 -> fraction.toLong() * 100L
                        2 -> fraction.toLong() * 10L
                        else -> fraction.take(3).toLong()
                    }
                    val timeMs = (minutes * 60L + seconds) * 1000L + millis
                    if (timeMs >= 10_000L || !lyricCreditTag.containsMatchIn(text)) {
                        out[timeMs] = text
                    }
                }
            }
            return out.map { (timeMs, text) -> LyricLine(timeMs, text) }
        }
    }
}
