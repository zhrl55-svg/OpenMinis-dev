package com.openminis.app.llm.mnn

import android.content.Context

/**
 * 助理模式（端侧直连）的开关。
 *
 * ★ **默认关闭**。
 *
 * 端侧链路是可选加速，不是单点故障：关掉它，全部流量回到云端，
 * 行为与今天完全一致。打开它也只是「规则命中的那批指令」走端侧，
 * 其余照旧走云端。
 *
 * 放在 SharedPreferences 而不是 ProviderConfig：这是一个**本机行为开关**，
 * 不跟着 iCloud 同步（另一台设备上的端侧模型文件、ABI、审批策略都不一样，
 * 同步过去只会让那台设备莫名其妙地走端侧）。
 */
object LocalAssistantPrefs {

    /** SharedPreferences 的名字（配置项 `assistant.localMode` 也读这里）。 */
    const val PREFS_NAME = "local_assistant"

    /** 开关的键。 */
    const val KEY_ENABLED = "enabled"

    /** 助理模式是否开启。 */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
