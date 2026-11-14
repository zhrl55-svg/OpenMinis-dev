package com.openminis.app.media.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地「最近播放」缓存，移植自 pilot 的 MusicHistoryStore。
 *
 * 网易云 `/user/record` 只收录已经在网易账号留下播放记录的歌；直接拉流的
 * 歌服务端可能还没来得及记录。本地再保留最近 30 首，「播放最近」在没有
 * 网络结果时依然可用。
 */
object MusicHistoryStore {

    private const val PREFS = "music_player"
    private const val KEY = "recent_json"
    private const val LIMIT = 30

    @Synchronized
    fun remember(context: Context, songs: List<MusicTrack>) {
        if (songs.isEmpty()) return
        val merged = LinkedHashMap<Long, MusicTrack>()
        songs.forEach { merged[it.id] = it }
        recent(context, LIMIT).forEach { merged.putIfAbsent(it.id, it) }
        val array = JSONArray()
        merged.values.take(LIMIT).forEach { song ->
            array.put(
                JSONObject()
                    .put("id", song.id)
                    .put("name", song.title)
                    .put("artist", song.artist)
                    .put("duration", song.durationMs),
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    @Synchronized
    fun recent(context: Context, limit: Int = LIMIT): List<MusicTrack> {
        val raw = prefs(context).getString(KEY, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until minOf(array.length(), limit)) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optLong("id")
                    val name = item.optString("name").trim()
                    if (id > 0L && name.isNotBlank()) {
                        add(
                            MusicTrack(
                                id = id,
                                title = name,
                                artist = item.optString("artist"),
                                durationMs = item.optLong("duration"),
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
