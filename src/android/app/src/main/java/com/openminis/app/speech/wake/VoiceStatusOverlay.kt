package com.openminis.app.speech.wake

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.OverlayPosition
import kotlin.math.abs

/**
 * Floating "voice status ball" shown while Minis is in the background.
 *
 * It is deliberately independent from [com.openminis.app.service.ToolOverlayController]:
 * the tool capsule is a half-screen pill anchored bottom-left and only exists
 * while an agent turn is running; this one is a compact ball anchored to the
 * right edge and exists whenever wake-word listening is enabled.
 */
class VoiceStatusOverlay(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)

    private var root: LinearLayout? = null
    private var ring: PulseRingView? = null
    private var ball: View? = null
    private var icon: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var latest: VoiceWakeSnapshot = VoiceWakeSnapshot()

    var isShown: Boolean = false
        private set

    private val positionStore = object : OverlayPosition.Store {
        override fun get(orientation: OverlayPosition.Orientation): OverlayPosition.Point? =
            WakeWordSettings.getOverlayPosition(orientation == OverlayPosition.Orientation.LANDSCAPE)
                ?.let { OverlayPosition.Point(it.first, it.second) }

        override fun set(orientation: OverlayPosition.Orientation, point: OverlayPosition.Point) =
            WakeWordSettings.setOverlayPosition(
                point.x,
                point.y,
                orientation == OverlayPosition.Orientation.LANDSCAPE,
            )
    }

    fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun update(snapshot: VoiceWakeSnapshot) {
        latest = snapshot
        main.post { apply(snapshot) }
    }

    fun setVisible(visible: Boolean) {
        main.post {
            if (visible) {
                if (!hasOverlayPermission()) {
                    if (isShown) hideInternal()
                    return@post
                }
                if (root == null) attach()
                apply(latest)
            } else if (isShown) {
                hideInternal()
            }
        }
    }

    fun hide() {
        main.post { hideInternal() }
    }

    fun onConfigurationChanged() {
        main.post {
            if (!isShown) return@post
            hideInternal()
            if (hasOverlayPermission()) {
                attach()
                apply(latest)
            }
        }
    }

    private fun apply(snapshot: VoiceWakeSnapshot) {
        val r = ring ?: return
        val b = ball ?: return
        val color = when (snapshot.phase) {
            VoiceWakePhase.WAITING,
            VoiceWakePhase.LISTENING,
            VoiceWakePhase.REPLYING,
            VoiceWakePhase.WAKING,
            -> COLOR_ACTIVE
            VoiceWakePhase.PAUSED -> COLOR_PAUSED
            VoiceWakePhase.ERROR -> COLOR_ERROR
            VoiceWakePhase.OFF -> COLOR_OFF
        }
        (b.background as? GradientDrawable)?.setColor(color)
        // The icon is the state: a microphone while the assistant listens,
        // a loudspeaker while it answers. No text at all.
        icon?.setImageResource(
            if (snapshot.phase == VoiceWakePhase.REPLYING) {
                R.drawable.ic_voice_speaker
            } else {
                android.R.drawable.ic_btn_speak_now
            },
        )
        r.setMode(
            when (snapshot.phase) {
                // Idle: a plain circle, nothing moves.
                VoiceWakePhase.WAITING -> RingMode.NONE
                // Listening: the pulse says "I hear you".
                VoiceWakePhase.LISTENING,
                VoiceWakePhase.WAKING,
                -> RingMode.PULSE
                // Answering: sound waves going out.
                VoiceWakePhase.REPLYING -> RingMode.WAVE
                else -> RingMode.NONE
            },
            color,
        )
        val detail = snapshot.detail.trim()
        root?.contentDescription = detail.ifEmpty { context.getString(R.string.voice_wake_overlay_title) }
    }

    private fun attach() {
        val container = buildView()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        container.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val dm = context.resources.displayMetrics
        val at = OverlayPosition.displayed(
            positionStore,
            defaultPosition(dm.widthPixels, dm.heightPixels, container.measuredWidth, container.measuredHeight),
            dm.widthPixels,
            dm.heightPixels,
            container.measuredWidth.coerceAtLeast(dp(48)),
            container.measuredHeight.coerceAtLeast(dp(56)),
            OverlayPosition.avoidFor(
                OverlayPosition.Orientation.of(dm.widthPixels, dm.heightPixels),
                left = 0,
                right = 0,
                bottom = 0,
                top = statusBarHeight(),
            ),
        )
        p.x = at.x
        p.y = at.y

        attachDrag(container, p)
        runCatching {
            windowManager.addView(container, p)
            root = container
            params = p
            isShown = true
            AppLogger.info(TAG, "语音悬浮球显示 at=${p.x},${p.y}")
        }.onFailure {
            AppLogger.info(TAG, "语音悬浮球添加失败：${it.javaClass.simpleName}: ${it.message}")
            root = null
            params = null
            isShown = false
        }
    }

    private fun buildView(): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // No plate under the ball: the circle IS the indicator. A
            // capsule behind it made the state harder to read at a glance
            // and covered whatever the user was doing.
            setPadding(dp(4), dp(4), dp(4), dp(4))
            elevation = dp(8).toFloat()
        }

        val ballFrame = FrameLayout(context)
        val ballSize = dp(BALL_SIZE_DP)
        ballFrame.addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(COLOR_ACTIVE)
                }
            },
            FrameLayout.LayoutParams(ballSize, ballSize, Gravity.CENTER),
        )
        ball = ballFrame.getChildAt(0)

        val ring = PulseRingView(context)
        ballFrame.addView(ring, FrameLayout.LayoutParams(ballSize, ballSize, Gravity.CENTER))
        this.ring = ring

        val icon = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.WHITE)
        }
        ballFrame.addView(icon, FrameLayout.LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP), Gravity.CENTER))
        this.icon = icon
        container.addView(ballFrame, LinearLayout.LayoutParams(ballSize, ballSize))
        return container
    }

    private fun attachDrag(container: LinearLayout, p: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val slop = dp(6)

        container.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        p.x = startX + dx
                        p.y = startY + dy
                        runCatching { windowManager.updateViewLayout(view, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        val dm = context.resources.displayMetrics
                        OverlayPosition.dragEnded(
                            positionStore,
                            dm.widthPixels,
                            dm.heightPixels,
                            OverlayPosition.Point(p.x, p.y),
                        )
                    } else {
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        container.setOnClickListener { openVoiceChat() }
    }

    private fun openVoiceChat() {
        val sessionId = WakeWordSettings.voiceSessionId.value
        val uri = if (sessionId.isNullOrBlank()) {
            "minis://action/new_chat"
        } else {
            "minis://session/$sessionId"
        }
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(uri),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { AppLogger.info(TAG, "打开语音对话失败：${it.message}") }
    }

    private fun hideInternal() {
        val view = root ?: return
        runCatching { windowManager.removeView(view) }
        ring?.stop()
        root = null
        ring = null
        ball = null
        icon = null
        params = null
        isShown = false
    }

    private fun defaultPosition(screenW: Int, screenH: Int, viewW: Int, viewH: Int): OverlayPosition.Point {
        val width = viewW.coerceAtLeast(dp(100))
        val height = viewH.coerceAtLeast(dp(60))
        return OverlayPosition.Point(
            x = (screenW - width - dp(8)).coerceAtLeast(dp(8)),
            y = (screenH * 0.38f - height / 2f).toInt().coerceAtLeast(statusBarHeight() + dp(8)),
        )
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private class PulseRingView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        }
        private var animator: ValueAnimator? = null
        private var fraction = 0f
        private var color = COLOR_ACTIVE
        private var mode = RingMode.NONE

        fun setMode(mode: RingMode, color: Int) {
            this.color = color
            this.mode = mode
            if (mode != RingMode.NONE) {
                if (animator?.isRunning == true) {
                    invalidate()
                    return
                }
                animator?.cancel()
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = if (mode == RingMode.WAVE) 1200L else 1600L
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        fraction = it.animatedFraction
                        invalidate()
                    }
                    start()
                }
            } else {
                animator?.cancel()
                animator = null
                fraction = 0f
                invalidate()
            }
        }

        fun stop() {
            animator?.cancel()
            animator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val base = minOf(width, height) / 2f - paint.strokeWidth
            paint.color = color
            paint.alpha = 210
            when (mode) {
                RingMode.NONE -> {
                    // Idle: the plain outline, nothing moving.
                    canvas.drawCircle(cx, cy, base, paint)
                }
                RingMode.PULSE -> {
                    canvas.drawCircle(cx, cy, base, paint)
                    if (fraction > 0f) {
                        paint.alpha = ((1f - fraction) * 140).toInt()
                        canvas.drawCircle(cx, cy, base + fraction * base * 0.34f, paint)
                    }
                }
                RingMode.WAVE -> {
                    // Answering: sound waves going out of the ball.
                    val waves = 3
                    for (i in 0 until waves) {
                        val phase = (fraction + i.toFloat() / waves) % 1f
                        paint.alpha = ((1f - phase) * 130).toInt()
                        canvas.drawCircle(cx, cy, base + phase * base * 0.55f, paint)
                    }
                }
            }
        }
    }

    private enum class RingMode { NONE, PULSE, WAVE }

    private companion object {
        const val TAG = "VoiceWake"
        /**
         * The ball is only a little bigger than the microphone glyph inside
         * it — a big disc covered a chunk of whatever the user was doing.
         */
        const val BALL_SIZE_DP = 40
        const val ICON_SIZE_DP = 28

        /** Green at 85% opacity. */
        const val COLOR_ACTIVE = 0xD934C759.toInt()
        const val COLOR_PAUSED = 0xD9FF9F0A.toInt()
        const val COLOR_ERROR = 0xD9FF453A.toInt()
        const val COLOR_OFF = 0xD98E8E93.toInt()
    }
}
