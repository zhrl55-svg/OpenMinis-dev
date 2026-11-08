package com.openminis.app.crash

import android.content.Context
import android.util.Log
import java.io.File

/**
 * [T-minis-hub-recovery] Clears a stale crash burst before
 * [CrashFrequencyDetector.checkAtLaunch] counts it.
 *
 * The burst detector counts crash files from the last hour. Two or more
 * (from an install/kill storm, for instance) switch the app into safe
 * mode, and in safe mode every launch ends in
 * `MainActivity.finishAndRestartProcess` — the app opens and dies again
 * ("it flashes and crashes"). Crash files that are already minutes old
 * are history, not a live crash loop, so they are dropped here; a crash
 * that happens right now still trips the detector.
 *
 * The file heads are written to logcat first: the crash reason only ever
 * lands in the app's private directory, which is where "why did it die"
 * used to get lost.
 */
object CrashStateRecovery {
    private const val TAG = "CrashStateRecovery"

    /** Crash files older than this are history, not a live crash loop. */
    private const val STALE_MS = 3L * 60L * 1000L

    fun dropStaleCrashLogs(context: Context) {
        try {
            val logsDir = File(context.filesDir, "logs")
            if (!logsDir.isDirectory) return
            val crashes = logsDir.listFiles { f ->
                f.isFile &&
                    (f.name.startsWith("crash-") || f.name.startsWith("native-crash-")) &&
                    f.name.endsWith(".log")
            } ?: return
            if (crashes.isEmpty()) return
            val now = System.currentTimeMillis()
            crashes.forEach { f ->
                runCatching {
                    Log.w(TAG, "crash log ${f.name} age=${(now - f.lastModified()) / 1000}s :: " + f.readText().take(1200))
                }
            }
            val stale = crashes.filter { now - it.lastModified() > STALE_MS }
            stale.forEach { runCatching { it.delete() } }
            if (stale.isNotEmpty()) {
                Log.w(TAG, "dropped ${stale.size} stale crash logs (older than ${STALE_MS / 1000}s)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recovery failed: ${t.message}")
        }
    }
}
