package com.openminis.app.ui.media.music

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.media.music.MusicChart
import com.openminis.app.media.music.MusicPlayerController
import com.openminis.app.media.music.MusicUiState
import com.openminis.app.media.music.PlayMode
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

/**
 * 紧凑播放器，样式与交互移植自 pilot 的 `MusicBar`：
 * 一行歌曲 + 一行同步歌词 + 2dp 进度 + 一行控制键，尽量节省纵向空间。
 *
 * 播放时浮在聊天页顶端（[com.openminis.app.ui.chat.ChatScreen]），
 * 消息从下面滚过，半透明卡片的层次感才出得来。
 */
@Composable
fun MusicPlayerBar(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val controller = remember(context) { MusicPlayerController.get(context) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    if (!state.hasTrack) return

    val colors = MaterialTheme.colorScheme
    var showRecommendations by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val playing = state.isPlaying
    val repeatLabel = stringResource(
        when (state.mode) {
            PlayMode.SEQUENCE -> R.string.music_mode_sequence
            PlayMode.REPEAT_ALL -> R.string.music_mode_repeat_all
            PlayMode.REPEAT_ONE -> R.string.music_mode_repeat_one
        },
    )
    val lyric = lyricLine(state)
    val trackLine = buildString {
        append(state.track?.title.orEmpty())
        val artist = state.track?.artist.orEmpty()
        if (artist.isNotBlank()) append("  ·  $artist")
    }
    val cardShape = RoundedCornerShape(18.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(cardShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        colors.primary.copy(alpha = 0.28f),
                        colors.surfaceVariant.copy(alpha = 0.92f),
                        colors.surface.copy(alpha = 0.98f),
                    ),
                ),
            )
            .border(1.dp, colors.primary.copy(alpha = 0.20f), cardShape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        // 第一行：音阶 + 曲名 + 推荐下拉 + 关闭
        Row(
            modifier = Modifier.height(21.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayingBars(playing = playing, tint = colors.primary)
            Spacer(Modifier.width(7.dp))
            Text(
                text = trackLine,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box {
                Row(
                    modifier = Modifier
                        .height(22.dp)
                        .clip(RoundedCornerShape(50))
                        .background(colors.primary.copy(alpha = 0.10f))
                        .border(
                            1.dp,
                            colors.primary.copy(alpha = 0.22f),
                            RoundedCornerShape(50),
                        )
                        .clickable { showRecommendations = true }
                        .padding(horizontal = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.music_recommend),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.primary,
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("▾", fontSize = 8.sp, color = colors.primary.copy(alpha = 0.80f))
                }
                DropdownMenu(
                    expanded = showRecommendations,
                    onDismissRequest = { showRecommendations = false },
                ) {
                    RecommendationMenu(
                        onPick = { action ->
                            showRecommendations = false
                            scope.launch { action(controller) }
                        },
                    )
                }
            }
            Spacer(Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onStop),
                contentAlignment = Alignment.Center,
            ) {
                Text("×", fontSize = 20.sp, color = colors.onSurfaceVariant)
            }
        }

        // 第二行：歌词 + 时间
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(27.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(16.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = if (playing) 1f else 0.45f)),
                )
                Spacer(Modifier.width(8.dp))
                Crossfade(
                    targetState = lyric,
                    animationSpec = tween(durationMillis = 220),
                    label = "music-lyric",
                    modifier = Modifier.weight(1f),
                ) { line ->
                    Text(
                        line,
                        fontSize = if (state.lyricText.isNotBlank()) 15.sp else 12.sp,
                        fontWeight = if (state.lyricText.isNotBlank()) {
                            FontWeight.Medium
                        } else {
                            FontWeight.Normal
                        },
                        color = if (state.lyricText.isNotBlank()) {
                            colors.primary
                        } else {
                            colors.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    "${formatMusicTime(state.positionMs)} / ${formatMusicTime(state.durationMs)}",
                    fontSize = 9.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        // 进度
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(CircleShape)
                .background(colors.onSurface.copy(alpha = 0.12f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(state.progress)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(colors.primary),
            )
        }
        Spacer(Modifier.height(3.dp))

        // 第三行：随机 / 上一首 / 播放 / 下一首 / 模式 + 候选
        Row(
            modifier = Modifier.height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            MusicModeChip(
                text = stringResource(R.string.music_shuffle),
                active = state.shuffle,
                onClick = { controller.toggleShuffle() },
            )
            MusicSkipButton(next = false, onClick = { controller.previous() })
            MusicPlayButton(playing = playing, onClick = { controller.toggle() })
            MusicSkipButton(next = true, onClick = { controller.next() })
            MusicModeChip(
                text = repeatLabel,
                // 随机是左侧主状态；开启随机时右侧播放模式只作为待恢复设置，
                // 不能同时高亮，否则用户会误以为两套模式正在叠加生效。
                active = !state.shuffle,
                onClick = { controller.cycleMode() },
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box {
                    Row(
                        modifier = Modifier
                            .height(24.dp)
                            .clip(RoundedCornerShape(50))
                            .background(colors.primary.copy(alpha = 0.10f))
                            .border(
                                1.dp,
                                colors.primary.copy(alpha = 0.22f),
                                RoundedCornerShape(50),
                            )
                            .clickable { showQueue = true }
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(
                                R.string.music_queue_chip,
                                (state.index + 1).coerceAtLeast(1),
                                state.queue.size,
                            ),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.primary,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(2.dp))
                        Text("▾", fontSize = 8.sp, color = colors.primary.copy(alpha = 0.75f))
                    }
                    DropdownMenu(
                        expanded = showQueue,
                        onDismissRequest = { showQueue = false },
                    ) {
                        state.queue.forEachIndexed { index, entry ->
                            val active = index == state.index
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (active) {
                                                        colors.primary
                                                    } else {
                                                        colors.primary.copy(alpha = 0.30f)
                                                    },
                                                ),
                                        )
                                        Spacer(Modifier.width(9.dp))
                                        Column {
                                            Text(
                                                entry.title,
                                                fontSize = 13.sp,
                                                color = if (active) colors.primary else colors.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (entry.artist.isNotBlank()) {
                                                Text(
                                                    entry.artist,
                                                    fontSize = 10.sp,
                                                    color = colors.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                },
                                onClick = {
                                    showQueue = false
                                    controller.playAt(index)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 推荐下拉的六项，顺序和 pilot 一致（本地音乐需读取音频权限，暂不提供）。 */
@Composable
private fun RecommendationMenu(onPick: (suspend (MusicPlayerController) -> Unit) -> Unit) {
    val items: List<Pair<Int, suspend (MusicPlayerController) -> Unit>> = listOf(
        R.string.music_recent to { it.playRecent() },
        R.string.music_chart_hot to { it.playHot() },
        R.string.music_daily to { it.playDaily() },
        R.string.music_liked to { it.playLiked() },
        R.string.music_chart_new to { it.playChart(MusicChart.NEW) },
        R.string.music_chart_rising to { it.playChart(MusicChart.RISING) },
    )
    items.forEach { (labelRes, action) ->
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(labelRes),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            onClick = { onPick(action) },
        )
    }
}

/** 播放中的音阶：四根柱子，各自相位，避免整齐跳动。 */
@Composable
private fun PlayingBars(playing: Boolean, tint: Color) {
    val transition = rememberInfiniteTransition(label = "bars")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "t",
    )
    Canvas(Modifier.size(width = 18.dp, height = 16.dp)) {
        val n = 4
        val gap = size.width / n
        val barW = gap * 0.45f
        for (i in 0 until n) {
            val phase = (t + i * 0.23f) % 1f
            val h = if (playing) {
                0.25f + 0.75f * abs(sin(phase * Math.PI * 2).toFloat())
            } else {
                0.28f
            }
            val barH = size.height * h
            drawRoundRect(
                color = tint,
                topLeft = Offset(i * gap + (gap - barW) / 2, size.height - barH),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2),
            )
        }
    }
}

/** 随机 / 模式小胶囊。 */
@Composable
private fun MusicModeChip(text: String, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (active) {
                    colors.primary.copy(alpha = 0.20f)
                } else {
                    colors.onSurface.copy(alpha = 0.06f)
                },
            )
            .border(
                1.dp,
                if (active) {
                    colors.primary.copy(alpha = 0.55f)
                } else {
                    colors.onSurface.copy(alpha = 0.10f)
                },
                RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) colors.primary else colors.onSurfaceVariant,
        )
    }
}

/** 圆形播放/暂停。 */
@Composable
private fun MusicPlayButton(playing: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(colors.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(17.dp)) {
            if (playing) {
                val barWidth = size.width * 0.24f
                val barHeight = size.height * 0.72f
                val top = (size.height - barHeight) / 2f
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(size.width * 0.22f, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(size.width * 0.54f, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            } else {
                val path = Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.16f)
                    lineTo(size.width * 0.84f, size.height * 0.5f)
                    lineTo(size.width * 0.28f, size.height * 0.84f)
                    close()
                }
                drawPath(path, Color.White)
            }
        }
    }
}

/** 上一首 / 下一首。 */
@Composable
private fun MusicSkipButton(next: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 15.dp, height = 17.dp)) {
            val path = Path().apply {
                if (next) {
                    moveTo(size.width * 0.12f, size.height * 0.15f)
                    lineTo(size.width * 0.68f, size.height * 0.5f)
                    lineTo(size.width * 0.12f, size.height * 0.85f)
                } else {
                    moveTo(size.width * 0.88f, size.height * 0.15f)
                    lineTo(size.width * 0.32f, size.height * 0.5f)
                    lineTo(size.width * 0.88f, size.height * 0.85f)
                }
                close()
            }
            drawPath(path, onSurface)
            val barX = if (next) size.width * 0.77f else size.width * 0.12f
            drawRoundRect(
                color = onSurface,
                topLeft = Offset(barX, size.height * 0.14f),
                size = Size(size.width * 0.10f, size.height * 0.72f),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
    }
}

/** 歌词行的文案（无歌词时显示加载/前奏）。 */
@Composable
private fun lyricLine(state: MusicUiState): String = when {
    state.lyricText.isNotBlank() -> state.lyricText
    !state.lyricsReady -> stringResource(R.string.music_lyrics_loading)
    else -> stringResource(R.string.music_lyric_intro)
}

private fun formatMusicTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000L
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
