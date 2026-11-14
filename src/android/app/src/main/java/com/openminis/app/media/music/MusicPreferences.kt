package com.openminis.app.media.music

import android.content.Context

/**
 * Where the NetEase proxy lives and which account to play with.
 *
 * The proxy is the self-hosted NeteaseCloudMusicApi running on the buntu
 * host. The LAN address is the primary route (the phone is usually on the
 * same network); the tailnet address is the fallback for when it is not.
 * A cookie is optional: without one the proxy serves the free catalogue,
 * with one it serves the account's own VIP/high-bitrate catalogue.
 */
data class MusicSettings(
    val baseUrl: String = MusicPreferences.DEFAULT_BASE_URL,
    val fallbackUrl: String = MusicPreferences.DEFAULT_FALLBACK_URL,
    val cookie: String = "",
)

object MusicPreferences {

    const val DEFAULT_BASE_URL = "http://192.168.2.102:3001"
    const val DEFAULT_FALLBACK_URL = "http://100.95.180.77:3001"

    private const val PREFS = "music_player"
    private const val K_BASE = "base_url"
    private const val K_FALLBACK = "fallback_url"
    private const val K_COOKIE = "cookie"

    fun load(context: Context): MusicSettings {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return MusicSettings(
            baseUrl = prefs.getString(K_BASE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
            fallbackUrl = prefs.getString(K_FALLBACK, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_FALLBACK_URL,
            cookie = prefs.getString(K_COOKIE, null).orEmpty(),
        )
    }

    fun saveBaseUrl(context: Context, url: String) {
        prefs(context).edit()
            .putString(K_BASE, url.trim().trimEnd('/'))
            .apply()
    }

    fun saveFallbackUrl(context: Context, url: String) {
        prefs(context).edit()
            .putString(K_FALLBACK, url.trim().trimEnd('/'))
            .apply()
    }

    fun saveCookie(context: Context, cookie: String) {
        prefs(context).edit().putString(K_COOKIE, cookie.trim()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
