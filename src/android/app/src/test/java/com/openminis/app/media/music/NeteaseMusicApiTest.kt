package com.openminis.app.media.music

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The NetEase proxy answers with two different song shapes: `/search` uses
 * `artists`/`album`, `/top/song` uses `ar`/`al`. Both have to parse, and the
 * lyric parser has to drop the credit lines that sit in the intro.
 */
class NeteaseMusicApiTest {

    @Test
    fun `parses the search shape`() {
        val songs = JSONArray().put(
            JSONObject()
                .put("id", 509781655)
                .put("name", "想你就写信 (Live)")
                .put("duration", 238698)
                .put(
                    "artists",
                    JSONArray().put(JSONObject().put("name", "周杰伦")).put(JSONObject().put("name", "李硕")),
                )
                .put(
                    "album",
                    JSONObject().put("name", "中国新歌声").put("picUrl", "http://p4.music.126.net/a.jpg"),
                ),
        )

        val tracks = NeteaseMusicApi.parseTracks(songs)

        assertEquals(1, tracks.size)
        val track = tracks.first()
        assertEquals(509781655L, track.id)
        assertEquals("想你就写信 (Live)", track.title)
        assertEquals("周杰伦 / 李硕", track.artist)
        assertEquals("中国新歌声", track.album)
        assertEquals(238698L, track.durationMs)
        assertEquals("http://p4.music.126.net/a.jpg", track.coverUrl)
    }

    @Test
    fun `parses the chart shape`() {
        val songs = JSONArray().put(
            JSONObject()
                .put("id", 186016)
                .put("name", "晴天")
                .put("dt", 269000)
                .put("ar", JSONArray().put(JSONObject().put("name", "周杰伦")))
                .put("al", JSONObject().put("name", "叶惠美").put("picUrl", "http://p3.music.126.net/b.jpg")),
        )

        val tracks = NeteaseMusicApi.parseTracks(songs)

        assertEquals(1, tracks.size)
        assertEquals("晴天", tracks.first().title)
        assertEquals("周杰伦", tracks.first().artist)
        assertEquals(269000L, tracks.first().durationMs)
    }

    @Test
    fun `skips entries without an id or a title`() {
        val songs = JSONArray()
            .put(JSONObject().put("id", 0).put("name", "无 id"))
            .put(JSONObject().put("id", 12).put("name", ""))
            .put(JSONObject().put("id", 34).put("name", "正常"))

        val tracks = NeteaseMusicApi.parseTracks(songs)

        assertEquals(1, tracks.size)
        assertEquals("正常", tracks.first().title)
    }

    @Test
    fun `parses lrc into timed lines`() {
        val raw = """
            [00:00.00] 作词 : 方文山
            [00:01.00] 作曲 : 周杰伦
            [00:26.17] 达布：看你在摇椅上织围巾
            [00:33.82] 我要到丽江
        """.trimIndent()

        val lines = NeteaseMusicApi.parseLyric(raw)

        // The credit lines are dropped, the singing lines are kept.
        assertEquals(2, lines.size)
        assertEquals(26_170L, lines[0].timeMs)
        assertEquals("达布：看你在摇椅上织围巾", lines[0].text)
        assertEquals(33_820L, lines[1].timeMs)
    }

    @Test
    fun `keeps credit lines that arrive late`() {
        val raw = "[00:30.00] 制作人 : 周杰伦"

        val lines = NeteaseMusicApi.parseLyric(raw)

        assertEquals(1, lines.size)
        assertEquals("制作人 : 周杰伦", lines.first().text)
    }

    @Test
    fun `empty lyric is empty`() {
        assertTrue(NeteaseMusicApi.parseLyric("").isEmpty())
        assertTrue(NeteaseMusicApi.parseLyric("[00:00.00]").isEmpty())
    }
}
