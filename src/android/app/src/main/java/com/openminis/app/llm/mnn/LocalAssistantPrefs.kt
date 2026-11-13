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

    private const val LEGACY_PREFS_NAME = "assistant_fast_path"
    private const val LEGACY_KEY_ENABLED = "enabled"
    private const val MIGRATION_KEY = "legacy_fast_path_migrated"

    /** 助理模式是否开启。 */
    fun isEnabled(context: Context): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean(MIGRATION_KEY, false)) {
            migrateLegacyValue(context, prefs)
        }
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putBoolean(MIGRATION_KEY, true)
            .apply()
    }

    private fun migrateLegacyValue(
        context: Context,
        current: android.content.SharedPreferences,
    ) {
        val legacy = context.applicationContext
            .getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val editor = current.edit().putBoolean(MIGRATION_KEY, true)
        if (legacy.contains(LEGACY_KEY_ENABLED)) {
            editor.putBoolean(KEY_ENABLED, legacy.getBoolean(LEGACY_KEY_ENABLED, false))
        }
        editor.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
