package com.openminis.app.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import com.openminis.app.logging.AppLogger
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * The host of the hub link (contract M5).
 *
 * The phone is the *caller*, but the socket still has to live in a foreground
 * service: the app is frozen in the background, and a websocket that dies with
 * it would lose the device table. EMUI has no way around this — a foreground
 * service with an ongoing notification is the only shape that survives.
 *
 * The notification has its own channel and id so it never collides with the
 * agent's own status notification, and its wording stays matter-of-fact: a
 * notification that looks like advertising gets switched off, and the service
 * goes with it.
 */
class HubService : Service() {

    companion object {
        private const val TAG = "HubService"
        const val CHANNEL_ID = "minis_hub"
        // NOT 9002: AgentForegroundService uses 9002 for its overlay nudge
        // and cancels it when that prompt is answered — the two notifications
        // would take turns evicting each other.
        const val NOTIFICATION_ID = 9003
        private const val ACTION_STOP = "com.openminis.app.hub.STOP"

        fun start(context: Context) {
            val intent = Intent(context, HubService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                AppLogger.warning(TAG, "cannot start the hub service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, HubService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // The user can switch the whole link off in the configuration file.
        if (!com.openminis.app.hub.HubIdentity.isEnabled(applicationContext)) {
            AppLogger.info(TAG, "hub is disabled — stopping the service")
            stopSelf()
            return START_NOT_STICKY
        }
        // If startForeground() fails, the system kills the process for not
        // calling it in time ("ForegroundServiceDidNotStartInTime") — that
        // reads as a crash of the whole app. Stop the service instead.
        if (!startForegroundSafely()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Hub.onStateChanged = { updateNotification() }
        Hub.ensureStarted(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() {
        Hub.onStateChanged = null
        Hub.stop()
        super.onDestroy()
    }

    /** P1.4 — the notification used to freeze on its first text. */
    private fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "notification update failed: ${t.message}")
        }
    }

    private fun startForegroundSafely(): Boolean {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // mediaPlayback is the type this app already uses for its
                // agent service: it needs no extra permission and has no
                // 6-hour cap. `remoteMessaging` does not exist before
                // API 34, and the phone here runs Android 12.
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            return true
        } catch (e: Exception) {
            AppLogger.warning(TAG, "startForeground failed: ${e.message}")
            return false
        }
    }

    private fun buildNotification(): Notification {
        val online = Hub.onlineTargets().size
        val text = when (Hub.status) {
            Hub.Status.ONLINE -> if (online > 0) "已连接 · $online 台远程设备在线" else "已连接 · 暂无远程设备在线"
            Hub.Status.CONNECTING -> "正在连接中继…"
            Hub.Status.REFUSED -> "凭据被拒绝 · 请在设置里检查"
            Hub.Status.OFFLINE -> "未启用"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Minis 远程")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Minis 远程",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "手机与同一账号下电脑之间的远程通道状态"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
