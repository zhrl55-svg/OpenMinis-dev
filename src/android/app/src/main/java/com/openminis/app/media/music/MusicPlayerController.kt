package com.openminis.app.media.music

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How the queue moves on when a track ends. */
enum class PlayMode { SEQUENCE, REPEAT_ALL, REPEAT_ONE }

/** Everything the player UI renders. */
data class MusicUiState(
    val track: MusicTrack? = null,
    val queue: List<MusicTrack> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lyricText: String = "",
    val lyricsReady: Boolean = false,
    val mode: PlayMode = PlayMode.REPEAT_ALL,
    val shuffle: Boolean = false,
    val error: String? = null,
) {
    val hasTrack: Boolean get() = track != null
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * 音乐播放器，逻辑移植自 pilot 的 `MusicPlayer`：
 *
 *  - **一首歌一个 ExoPlayer**：`startCurrent()` 新建，放完/出错/停止即
 *    release —— 空载时没有播放器对象常驻；
 *  - 队列、顺序/列表循环/单曲循环、随机 + 历史回退、连续失败自动跳过；
 *  - 播放地址每首现取（代理给的 URL ~20 分钟过期），歌词同时解析；
 *  - 「最近播放」本地缓存 30 首，网易云接口为空时仍可用。
 *
 * 与 app 的其他部分通过 [state] 单向往外报；语音层用
 * [com.openminis.app.speech.VoiceOutputState.CaptureSuspendable] 在开麦前
 * 暂停音乐，TTS 播报时自动压低音量。
 */
class MusicPlayerController private constructor(
    private val appContext: Context,
) : com.openminis.app.speech.VoiceOutputState.CaptureSuspendable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = NeteaseMusicApi { MusicPreferences.load(appContext) }

    private val _state = MutableStateFlow(MusicUiState())
    val state: StateFlow<MusicUiState> = _state.asStateFlow()

    /** The player for the CURRENT track only; null while idle. */
    private var player: ExoPlayer? = null

    private var lyrics: List<LyricLine> = emptyList()
    private val history = ArrayDeque<Int>()
    private var consecutiveErrors = 0
    private var tickerJob: Job? = null
    private var suspendedForCapture = false

    init {
        com.openminis.app.speech.VoiceOutputState.registerCaptureSuspendable(this)
        tickerJob = scope.launch {
            while (isActive) {
                val exo = player
                if (exo != null && exo.isPlaying) {
                    val position = exo.currentPosition.coerceAtLeast(0L)
                    _state.update { current ->
                        current.copy(
                            positionMs = position,
                            lyricText = lyricAt(position),
                        )
                    }
                    applySpeechDucking(exo)
                }
                delay(TICK_MS)
            }
        }
    }

    // ─────────────────────────────────────────────── playlists

    /**
     * 「播放最近」：网易云账号的最近播放 + 本地缓存，去重。
     */
    suspend fun playRecent(): MusicTrack? {
        val remote = runCatching { api.recentSongs(PLAYLIST_LIMIT) }.getOrDefault(emptyList())
        val songs = (remote + MusicHistoryStore.recent(appContext, PLAYLIST_LIMIT))
            .distinctBy { it.id }
            .take(PLAYLIST_LIMIT)
        return startList(songs, "最近播放")
    }

    /** 「每日推荐」（需登录）。 */
    suspend fun playDaily(): MusicTrack? {
        val songs = runCatching { api.dailyRecommendations(PLAYLIST_LIMIT) }
            .getOrDefault(emptyList())
        return startList(songs, "每日推荐")
    }

    /** 「我喜欢的音乐」（需登录）。 */
    suspend fun playLiked(): MusicTrack? {
        val songs = runCatching { api.likedSongs(PLAYLIST_LIMIT) }.getOrDefault(emptyList())
        return startList(songs, "我喜欢的音乐")
    }

    /** 官方榜：热歌 / 新歌 / 飙升。 */
    suspend fun playChart(chart: MusicChart): MusicTrack? {
        val songs = runCatching { api.chartSongs(chart.id, PLAYLIST_LIMIT) }
            .getOrDefault(emptyList())
        return startList(songs, chart.label)
    }

    /** 「热点/随便放」——热歌榜。 */
    suspend fun playHot(): MusicTrack? = playChart(MusicChart.HOT)

    /**
     * 搜索并播放。空查询/「热门」类关键词走榜单；「最近 / 每日推荐 /
     * 我喜欢 / 新歌 / 飙升」走对应列表——和 pilot 的命令分词一致。
     *
     * @return 实际开始播放的曲目；没有可播放内容时返回 null。
     */
    suspend fun playQuery(query: String): MusicTrack? {
        val keyword = query.trim()
        return when {
            keyword.isBlank() || keyword.contains("热门") || keyword.contains("热歌") ->
                playHot()
            keyword.contains("最近") -> playRecent()
            keyword.contains("每日推荐") || keyword == "推荐" -> playDaily()
            keyword.contains("我喜欢") || keyword.contains("收藏") -> playLiked()
            keyword.contains("新歌") -> playChart(MusicChart.NEW)
            keyword.contains("飙升") -> playChart(MusicChart.RISING)
            else -> playSearch(keyword)
        }
    }

    /** 普通关键词：搜索前 20 首，从最佳匹配开始播。 */
    private suspend fun playSearch(keyword: String): MusicTrack? {
        // 「周杰伦的稻香」is a song called 稻香 by 周杰伦, not a song called
        // 周杰伦的稻香. Search the whole phrase AND the part after 的, then
        // pick the hit that matches the title and the artist best.
        val head = keyword.substringBefore('的').trim()
            .takeIf { it.isNotBlank() && it != keyword }
        val tail = keyword.substringAfterLast('的').trim()
            .takeIf { it.isNotBlank() && it != keyword }
        val candidates = mutableListOf<List<MusicTrack>>()
        candidates += runCatching { api.search(keyword, PLAYLIST_LIMIT) }.getOrDefault(emptyList())
        if (tail != null && tail != keyword) {
            candidates += runCatching { api.search(tail, PLAYLIST_LIMIT) }.getOrDefault(emptyList())
        }
        var best: MusicTrack? = null
        var bestScore = Int.MIN_VALUE
        var bestQueue: List<MusicTrack> = emptyList()
        for (list in candidates) {
            for (track in list) {
                val score = score(track, keyword, head, tail)
                if (score > bestScore) {
                    bestScore = score
                    best = track
                    bestQueue = list
                }
            }
        }
        val chosen = best
        if (chosen == null) {
            _state.update { it.copy(error = "没有找到歌曲") }
            return null
        }
        MusicHistoryStore.remember(appContext, bestQueue)
        val index = bestQueue.indexOfFirst { it.id == chosen.id }.coerceAtLeast(0)
        return startQueue(bestQueue, index)
    }

    /** 开始一个列表（推荐下拉 / 榜单 / 搜索都用它）。 */
    suspend fun startList(songs: List<MusicTrack>, label: String): MusicTrack? {
        if (songs.isEmpty()) {
            _state.update { it.copy(error = "「$label」暂无歌曲") }
            return null
        }
        MusicHistoryStore.remember(appContext, songs)
        return startQueue(songs, 0)
    }

    /** 搜索接口返回的完整候选（供 UI / 命令使用）。 */
    suspend fun search(query: String): List<MusicTrack> = api.search(query, PLAYLIST_LIMIT)

    // ─────────────────────────────────────────────── transport

    /** Replace the queue and start playing at [startIndex]. */
    fun playTracks(tracks: List<MusicTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        scope.launch {
            _state.update { it.copy(queue = tracks, error = null) }
            startQueue(tracks, startIndex.coerceIn(0, tracks.lastIndex))
        }
    }

    fun toggle() {
        scope.launch {
            val exo = player
            if (exo == null) {
                // Nothing loaded: restart the current track.
                if (_state.value.hasTrack) startCurrent() else return@launch
                return@launch
            }
            if (exo.isPlaying) {
                exo.pause()
                _state.update { it.copy(isPlaying = false) }
            } else {
                exo.play()
                _state.update { it.copy(isPlaying = true) }
            }
        }
    }

    fun pause() {
        scope.launch {
            player?.pause()
            _state.update { it.copy(isPlaying = false) }
        }
    }

    fun resume() {
        scope.launch {
            if (_state.value.track == null) return@launch
            val exo = player
            if (exo == null) {
                startCurrent()
                return@launch
            }
            exo.play()
            _state.update { it.copy(isPlaying = true) }
        }
    }

    fun next() {
        scope.launch { advance(automatic = false) }
    }

    fun previous() {
        scope.launch {
            val current = _state.value
            if (current.queue.isEmpty()) return@launch
            val target = when {
                current.shuffle && history.isNotEmpty() -> history.removeLast()
                current.index > 0 -> current.index - 1
                current.mode != PlayMode.SEQUENCE -> current.queue.lastIndex
                else -> return@launch
            }
            history.addLast(current.index)
            while (history.size > 100) history.removeFirst()
            startAt(target)
        }
    }

    fun playAt(index: Int) {
        scope.launch {
            if (index !in _state.value.queue.indices) return@launch
            if (index == _state.value.index) return@launch
            history.addLast(_state.value.index)
            while (history.size > 100) history.removeFirst()
            startAt(index)
        }
    }

    fun seekTo(positionMs: Long) {
        scope.launch {
            val exo = player ?: return@launch
            val duration = _state.value.durationMs
            val target = positionMs.coerceIn(0L, if (duration > 0L) duration else Long.MAX_VALUE)
            exo.seekTo(target)
            _state.update { it.copy(positionMs = target, lyricText = lyricAt(target)) }
        }
    }

    fun cycleMode(): PlayMode {
        val nextMode = when (_state.value.mode) {
            PlayMode.SEQUENCE -> PlayMode.REPEAT_ALL
            PlayMode.REPEAT_ALL -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.SEQUENCE
        }
        _state.update { it.copy(mode = nextMode) }
        return nextMode
    }

    fun toggleShuffle(): Boolean {
        val next = !_state.value.shuffle
        _state.update { it.copy(shuffle = next) }
        return next
    }

    /** Stop and clear the queue - the top bar disappears with it. */
    fun stop() {
        scope.launch {
            releasePlayer()
            lyrics = emptyList()
            history.clear()
            consecutiveErrors = 0
            _state.value = MusicUiState(
                mode = _state.value.mode,
                shuffle = _state.value.shuffle,
            )
        }
    }

    /** Release the player. Only used when the process is going away. */
    fun release() {
        tickerJob?.cancel()
        scope.cancel()
        runCatching { player?.release() }
        player = null
    }

    // ─────────────────────────────────────────────── capture / speech

    /**
     * The mic is about to open. The song would be recorded with it, so the
     * music pauses; [resumeAfterCapture] picks it up where it left off.
     */
    override fun suspendForCapture() {
        scope.launch {
            val exo = player ?: return@launch
            if (!exo.isPlaying) return@launch
            suspendedForCapture = true
            exo.pause()
            _state.update { it.copy(isPlaying = false) }
            AppLogger.info(TAG, "paused for mic capture")
        }
    }

    override fun resumeAfterCapture() {
        scope.launch {
            if (!suspendedForCapture) return@launch
            suspendedForCapture = false
            val exo = player ?: return@launch
            exo.play()
            _state.update { it.copy(isPlaying = true) }
            AppLogger.info(TAG, "resumed after mic capture")
        }
    }

    /** The assistant's spoken answer ducks the music instead of vanishing. */
    private fun applySpeechDucking(exo: ExoPlayer) {
        val speaking = com.openminis.app.speech.VoiceOutputState.anySpeechSpeaking()
        val wanted = if (speaking) DUCK_VOLUME else 1f
        if (kotlin.math.abs(exo.volume - wanted) < 0.01f) return
        exo.volume = wanted
    }

    // ─────────────────────────────────────────────── internals

    private fun currentTrack(): MusicTrack? =
        _state.value.queue.getOrNull(_state.value.index)

    private suspend fun startQueue(tracks: List<MusicTrack>, index: Int): MusicTrack? {
        val started = CompletableDeferred<MusicTrack?>()
        _state.update { it.copy(queue = tracks, index = index, error = null) }
        startCurrent(started)
        return started.await()
    }

    private fun startAt(index: Int) {
        _state.update { it.copy(index = index) }
        scope.launch { startCurrent() }
    }

    private fun startCurrent(result: CompletableDeferred<MusicTrack?>? = null) {
        scope.launch {
            val track = currentTrack()
            if (track == null) {
                result?.complete(null)
                return@launch
            }
            consecutiveErrors = 0
            lyrics = emptyList()
            _state.update {
                it.copy(
                    track = track,
                    isLoading = true,
                    isPlaying = false,
                    error = null,
                    positionMs = 0L,
                    durationMs = track.durationMs,
                    lyricText = "",
                    lyricsReady = false,
                )
            }
            val url = runCatching { api.playUrl(track.id) }.getOrNull()
            if (url.isNullOrBlank()) {
                fail("无法获取播放地址（可能需要登录网易云或该歌曲受限）", result)
                return@launch
            }
            // One ExoPlayer per track, released the moment it is done.
            releasePlayer()
            val exo = createPlayer()
            player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (player !== exo) return
                    when (playbackState) {
                        Player.STATE_BUFFERING -> _state.update { it.copy(isLoading = true) }
                        Player.STATE_READY -> {
                            val duration = exo.duration.takeIf { it > 0L } ?: _state.value.durationMs
                            _state.update { it.copy(isLoading = false, durationMs = duration) }
                        }
                        Player.STATE_ENDED -> scope.launch { onCurrentEnded() }
                        else -> Unit
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    if (player !== exo) return
                    _state.update { it.copy(isPlaying = playing) }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (player !== exo) return
                    AppLogger.warning(TAG, "playback error: ${error.errorCodeName}")
                    scope.launch { fail("播放失败：${error.errorCodeName}") }
                }
            })
            runCatching {
                exo.setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaId(track.id.toString())
                        .build(),
                )
                exo.prepare()
                exo.play()
            }.onFailure {
                fail("播放失败：${it.message ?: it.javaClass.simpleName}", result)
                return@launch
            }
            AppLogger.info(TAG, "playing ${track.title} - ${track.artist}")
            result?.complete(track)
            loadLyrics(track.id)
        }
    }

    private fun createPlayer(): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setDefaultRequestProperties(
                mapOf("Referer" to "https://music.163.com/"),
            )
        val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)
        return ExoPlayer.Builder(appContext)
            .setLooper(Looper.getMainLooper())
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                setHandleAudioBecomingNoisy(true)
            }
    }

    private suspend fun loadLyrics(songId: Long) {
        val lines = runCatching { api.lyrics(songId) }.getOrDefault(emptyList())
        lyrics = lines
        _state.update { it.copy(lyricsReady = true) }
    }

    private fun lyricAt(positionMs: Long): String {
        if (lyrics.isEmpty()) return ""
        var low = 0
        var high = lyrics.lastIndex
        var match = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lyrics[mid].timeMs <= positionMs) {
                match = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return lyrics.getOrNull(match)?.text.orEmpty()
    }

    private suspend fun onCurrentEnded() {
        if (_state.value.mode == PlayMode.REPEAT_ONE) {
            startCurrent()
            return
        }
        advance(automatic = true)
    }

    /**
     * Move to the next track. Returns false when the queue ended.
     *
     * @param automatic true when the track ended by itself, false when the
     *   user pressed "next" - the difference matters at the end of a
     *   sequential queue: an automatic end stops, a manual next wraps.
     */
    private suspend fun advance(automatic: Boolean): Boolean {
        val current = _state.value
        if (current.queue.isEmpty()) return false
        val target = when {
            current.shuffle && current.queue.size > 1 ->
                current.queue.indices.filter { it != current.index }.randomOrNull() ?: -1
            current.index + 1 <= current.queue.lastIndex -> current.index + 1
            current.mode == PlayMode.REPEAT_ALL -> 0
            automatic -> -1
            else -> 0
        }
        if (target < 0) {
            // Sequence finished: stop and let the bar disappear, like pilot.
            stop()
            return false
        }
        history.addLast(current.index)
        while (history.size > 100) history.removeFirst()
        startAt(target)
        return true
    }

    /** A track could not be played: skip it, or give up and report why. */
    private suspend fun fail(message: String, result: CompletableDeferred<MusicTrack?>? = null) {
        consecutiveErrors++
        val queueSize = _state.value.queue.size
        if (consecutiveErrors < queueSize && advance(automatic = true)) {
            AppLogger.info(TAG, "skipping unplayable track: $message")
            result?.complete(null)
            return
        }
        releasePlayer()
        _state.update { it.copy(isLoading = false, isPlaying = false, error = message) }
        result?.complete(null)
    }

    private fun releasePlayer() {
        val old = player
        player = null
        old?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        _state.update { it.copy(isLoading = false) }
    }

    /**
     * How well a search hit answers the request.
     *
     * The title has to match the song part of the phrase, and the artist has
     * to match the artist part - that is what separates the original from
     * the dozens of covers with the same title.
     */
    private fun score(track: MusicTrack, keyword: String, head: String?, tail: String?): Int {
        var score = 0
        val title = track.title.trim()
        val artist = track.artist.trim()
        if (title == keyword) {
            score += 6
        } else if (title.contains(keyword)) {
            score += 3
        }
        if (tail != null) {
            when {
                title == tail -> score += 5
                title.contains(tail) -> score += 2
                tail.contains(title) -> score += 1
            }
        }
        if (head != null) {
            if (artist.contains(head)) score += 3
            // Same title, different artist: probably a cover.
            else if (tail != null && title == tail) score -= 1
        }
        return score
    }

    companion object {
        private const val TAG = "MusicPlayer"

        private const val TICK_MS = 400L
        private const val PLAYLIST_LIMIT = 20

        /** Volume while the assistant is speaking over the song. */
        private const val DUCK_VOLUME = 0.18f

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"

        @Volatile
        private var instance: MusicPlayerController? = null

        fun get(context: Context): MusicPlayerController = instance ?: synchronized(this) {
            instance ?: MusicPlayerController(context.applicationContext).also { instance = it }
        }
    }
}

/** 网易云官方榜，和 pilot 用同一组 playlist id。 */
enum class MusicChart(val id: Long, val label: String) {
    HOT(3778678L, "网易云热歌榜"),
    NEW(3779629L, "网易云新歌榜"),
    RISING(19723756L, "网易云飙升榜"),
}
