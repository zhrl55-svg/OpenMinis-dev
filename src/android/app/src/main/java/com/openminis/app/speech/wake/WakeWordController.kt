package com.openminis.app.speech.wake

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.openminis.app.logging.AppLogger

/**
 * Single entry point for turning the wake service on/off.
 *
 * It is deliberately separate from the settings UI so the same rules apply to
 * the UI toggle, `minis-config`, and app startup: enabled + RECORD_AUDIO
 * granted starts the service; anything else stops it and publishes an
 * actionable state for the overlay / notification.
 */
object WakeWordController {

    private const val TAG = "VoiceWake"
    @Volatile
    private var initialized = false
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * The last foreground-service start was refused by the system.
     *
     * Android 12+ throws `ForegroundServiceStartNotAllowedException` when a
     * microphone foreground service is started from the background, and
     * Android 14+ refuses the microphone type outright unless the app is
     * eligible (the user switched to the app, or a boot/package event put
     * it in the allowlist). Either way the service is not running and the
     * wake word would silently never work — so the refusal is remembered
     * and retried the moment the app comes back to the foreground.
     */
    @Volatile
    private var startRefused = false

    fun init(context: Context) {
        if (initialized) {
            sync(context)
            return
        }
        synchronized(this) {
            if (initialized) {
                sync(context)
                return
            }
            val app = context.applicationContext
            WakeWordSettings.init(app)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    WakeWordSettings.KEY_ENABLED,
                    WakeWordSettings.KEY_WAKE_WORD,
                    WakeWordSettings.KEY_OVERLAY_ENABLED,
                    -> sync(app)
                }
            }
            app.getSharedPreferences(WakeWordSettings.PREFS_NAME, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(listener)
            prefsListener = listener
            initialized = true
        }
        sync(context)
    }

    fun sync(context: Context) {
        WakeWordSettings.init(context)
        if (!WakeWordSettings.enabled.value) {
            startRefused = false
            WakeWordService.stop(context)
            VoiceWakeState.update(context, VoiceWakePhase.OFF, "语音唤醒已关闭")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            startRefused = false
            WakeWordService.stop(context)
            VoiceWakeState.update(context, VoiceWakePhase.ERROR, "需要麦克风权限")
            AppLogger.info(TAG, "唤醒已开启但缺少 RECORD_AUDIO，服务未启动")
            return
        }
        startRefused = !WakeWordService.start(context)
        if (startRefused) {
            AppLogger.info(TAG, "唤醒服务启动被系统拒绝，应用回到前台后会自动重试")
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        WakeWordSettings.setEnabled(context, enabled)
        sync(context)
    }

    /**
     * The app came back to the foreground, where a foreground-service start
     * is allowed again. Retry the wake service if its previous start was
     * refused.
     *
     * The wake word stays available in the foreground too: the service (not
     * the Activity lifecycle) decides when the microphone must be yielded,
     * and it keeps listening while the app is simply open.
     */
    fun onAppForegrounded(context: Context) {
        if (!startRefused) return
        if (!WakeWordSettings.enabled.value) return
        AppLogger.info(TAG, "应用回到前台，重试启动唤醒服务")
        sync(context)
    }

    /**
     * The service was torn down while the feature is still enabled — either
     * the system refused the microphone foreground service or it was killed.
     * Remember it so the next foreground transition restarts it.
     */
    fun onServiceStartRefused(context: Context) {
        if (!WakeWordSettings.enabled.value) return
        startRefused = true
        VoiceWakeState.update(
            context,
            VoiceWakePhase.ERROR,
            "后台录音服务未运行，打开 Minis 后会自动恢复",
        )
    }
}
