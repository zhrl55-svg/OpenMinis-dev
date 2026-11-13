package com.openminis.app.ui.chat.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The composer's listening border: a bright arc travelling around the edge.
 *
 * The composer card normally has no border at all (it is a floating card
 * separated from the backdrop by a painted shadow), so the glow is the only
 * thing that says "the microphone is open". It is drawn in the draw phase
 * only — the animation never recomposes the composer.
 */
@Composable
fun Modifier.holdToTalkGlow(active: Boolean, cornerRadius: Dp = 20.dp): Modifier {
    if (!active) return this
    val transition = rememberInfiniteTransition(label = "holdToTalkGlow")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "holdToTalkGlowPhase",
    )
    return this.drawWithContent {
        drawContent()
        val radiusPx = cornerRadius.toPx()
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2.5.dp.toPx()
            shader = android.graphics.SweepGradient(
                centerX,
                centerY,
                intArrayOf(
                    android.graphics.Color.argb(255, 96, 205, 255),
                    android.graphics.Color.argb(70, 96, 205, 255),
                    android.graphics.Color.argb(255, 168, 130, 255),
                    android.graphics.Color.argb(70, 96, 205, 255),
                ),
                floatArrayOf(0f, 0.22f, 0.5f, 0.72f),
            ).apply {
                setLocalMatrix(
                    android.graphics.Matrix().apply {
                        setRotate(phase * 360f, centerX, centerY)
                    },
                )
            }
            maskFilter = android.graphics.BlurMaskFilter(
                5.dp.toPx(),
                android.graphics.BlurMaskFilter.Blur.NORMAL,
            )
        }
        val path = android.graphics.Path().apply {
            addRoundRect(
                0f,
                0f,
                size.width,
                size.height,
                radiusPx,
                radiusPx,
                android.graphics.Path.Direction.CW,
            )
        }
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawPath(path, paint)
        }
    }
}
