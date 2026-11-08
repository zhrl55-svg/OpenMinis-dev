package com.openminis.app.speech

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * 选一个**实际存在**的系统 TTS 引擎，而不是把引擎交给系统去猜。
 *
 * 起因（2026-11-08，华为 P40 Pro / ELS-AN00 / HarmonyOS）：
 *
 *   `settings get secure tts_default_synth` 返回 `com.svox.pico`，
 *   而那个包**根本没安装**（`pm list packages` 里查不到，它也不在
 *   `queryIntentServices(TTS_SERVICE)` 的结果里）。系统列出来的真实引擎只有两个：
 *
 *     com.huawei.hiai            华为智慧引擎
 *     com.iflytek.speechsuite    讯飞
 *
 *   `TextToSpeech(context, listener)` 不指定引擎时会按那个假值解析，
 *   能不能绑上全看 ROM 的兜底策略 —— 表现就是播报有时没声音、有时音色不对。
 *
 * 做法：用 PackageManager 查一次 TTS_SERVICE（manifest 已声明 queries，
 * Android 11+ 才看得见用户装的引擎），按优先级挑第一个存在的；
 * 一个都没有就返回 null，维持原来的「交给系统」行为，非中文机不受影响。
 *
 * 注意别改成 `TextToSpeech(context)` 探头再去问 defaultEngine：
 * 那样会为了读一个字符串启动又放弃一次引擎绑定，用户能听见半声噪音。
 */
internal object TtsEngineResolver {

    private const val TAG = "TtsEngine"

    /** 顺序即优先级：华为智慧引擎音色最自然，讯飞次之。 */
    private val PREFERRED = listOf(
        "com.huawei.hiai",
        "com.iflytek.speechsuite",
    )

    @Volatile
    private var cached: String? = null

    @Volatile
    private var resolved = false

    /**
     * @return 要显式绑定的引擎包名；null 表示「没有偏好，交给系统默认」。
     *
     * 结果缓存：引擎列表在一个进程生命周期里不会变（装新引擎要重启进程才认），
     * 而这里每次播报前都可能被调用。
     */
    fun resolve(context: Context): String? {
        if (resolved) return cached
        synchronized(this) {
            if (resolved) return cached
            val installed = installedEngines(context)
            cached = PREFERRED.firstOrNull { it in installed }
            resolved = true
            if (cached == null) {
                Log.i(TAG, "no preferred TTS engine installed; using system default (installed=$installed)")
            } else {
                Log.i(TAG, "preferred TTS engine: $cached (installed=$installed)")
            }
            return cached
        }
    }

    /** 包可见性不足时返回空列表，此时调用方退回系统默认，不会更糟。 */
    private fun installedEngines(context: Context): Set<String> = try {
        val pm = context.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }
        resolved.mapNotNull { it.serviceInfo?.packageName }.toSet()
    } catch (t: Throwable) {
        Log.w(TAG, "queryIntentServices(TTS_SERVICE) failed: ${t.javaClass.simpleName}")
        emptySet()
    }
}
