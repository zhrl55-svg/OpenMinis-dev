package com.openminis.app.hub

import android.content.Context
import com.openminis.app.data.DeviceIdentity
import com.openminis.app.util.EncryptedPrefsFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The phone's hub identity: who it is on the relay, where the relay is, and the
 * monotonic counter that makes a call id unique across restarts.
 *
 * Contract C1.3: one credential owns one device id and the id must survive a
 * process death, so it is derived from [DeviceIdentity] (an encrypted UUID that
 * only changes on uninstall) — not from a fresh random UUID.
 * Contract C7.1: the call sequence number is persisted with `commit()`, so a
 * crash cannot hand out the same sequence twice. A duplicate call id would be
 * rejected by the relay as `duplicate_call` and the real call would be lost.
 *
 * The configuration may live in the app's private directory or on the
 * external one (which `adb push` can reach):
 *
 *   hub.json = {
 *     "relay": ["ws://192.168.2.102:8787/v1/hub", "ws://100.95.180.77:8787/v1/hub"],
 *     "key": "nm_phone",
 *     "proxy": "127.0.0.1:7890",
 *     "enabled": true
 *   }
 *
 * "relay" is an ordered list: the LAN address first, the Tailscale one as the
 * fallback for when the phone is out of the house. "proxy" is optional — it
 * exists because the phone's VPN tears a direct websocket down every 20-40
 * seconds, while the same socket through the local proxy stays up for hours.
 * "enabled" lets the user switch the whole link off without deleting the file.
 */
object HubIdentity {
    private const val PREFS = "minis_hub_settings"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_RELAY = "relay_url"
    private const val KEY_SEQ = "call_seq"
    private const val KEY_PROXY = "proxy"
    private const val KEY_ENABLED = "enabled"

    /** `phone-<uuid8>` — the kind is fixed to `phone` by contract C1.3. */
    fun deviceId(context: Context): String =
        "phone-" + DeviceIdentity.deviceId(context).replace("-", "").take(8)

    /** What the other devices see in the device list. */
    fun deviceName(context: Context): String =
        DeviceIdentity.customName(context) ?: DeviceIdentity.deviceName(context)

    fun relayUrl(context: Context): String? = relayUrls(context).firstOrNull()

    /**
     * The relay endpoints, in order of preference: the LAN address first,
     * then the Tailscale one. The stored value may be a single URL or a JSON
     * array of them.
     */
    fun relayUrls(context: Context): List<String> {
        parseUrls(prefs(context).getString(KEY_RELAY, null))?.let { return it }
        parseUrls(configJson(context)?.opt("relay")?.let { rawToString(it) })?.let { return it }
        return emptyList()
    }

    fun setRelayUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_RELAY, url.trim()).apply()
    }

    /** The relay credential. Never logged, never in a notification. */
    fun apiKey(context: Context): String? =
        secrets(context).getString(KEY_API_KEY, null)
            ?: configJson(context)?.optString("key", "")?.trim()?.ifEmpty { null }

    fun setApiKey(context: Context, key: String) {
        secrets(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    /**
     * An optional HTTP proxy for the hub socket, "host:port". Empty = direct.
     */
    fun proxyFor(context: Context): String? {
        val stored = prefs(context).getString(KEY_PROXY, null)
        if (!stored.isNullOrBlank()) return stored.trim()
        return configJson(context)?.optString("proxy", "")?.trim()?.ifEmpty { null }
    }

    fun setProxy(context: Context, proxy: String) {
        prefs(context).edit().putString(KEY_PROXY, proxy.trim()).apply()
    }

    /** Whether the cross-device link may run at all (the user can switch it off). */
    fun isEnabled(context: Context): Boolean {
        val fromFile = configJson(context)?.opt("enabled")
        if (fromFile != null && fromFile != JSONObject.NULL) return fromFile != false
        return prefs(context).getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun hasCredentials(context: Context): Boolean =
        !relayUrl(context).isNullOrBlank() && !apiKey(context).isNullOrBlank()

    /**
     * Next call sequence number. Persisted synchronously: the id must never
     * repeat, even across a crash (contract C7.1).
     */
    fun nextSeq(context: Context): Long {
        val p = prefs(context)
        val next = p.getLong(KEY_SEQ, 0L) + 1L
        p.edit().putLong(KEY_SEQ, next).commit()
        return next
    }

    // ── the config file ────────────────────────────────────────────────────

    /** Every place the config may live, most specific first. */
    private fun configFiles(context: Context): List<File> = listOfNotNull(
        File(context.filesDir, "hub.json"),
        // The app's own external files dir: readable without any storage
        // permission, and writable by `adb push`.
        context.getExternalFilesDir(null)?.let { File(it, "hub.json") },
        File("/sdcard/minis/hub.json"),
        File("/storage/emulated/0/minis/hub.json"),
    )

    private fun configJson(context: Context): JSONObject? {
        for (file in configFiles(context)) {
            try {
                if (!file.exists()) continue
                return JSONObject(file.readText())
            } catch (_: Exception) {
                // try the next candidate
            }
        }
        return null
    }

    private fun parseUrls(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[")) return listOf(trimmed)
        return try {
            val arr = JSONArray(trimmed)
            (0 until arr.length()).map { arr.optString(it, "").trim() }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** A JSON value as the string form the parsers above expect. */
    private fun rawToString(raw: Any?): String = when (raw) {
        is String -> raw
        is JSONArray -> raw.toString()
        else -> ""
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secrets(context: Context) =
        EncryptedPrefsFactory.safeCreate(context.applicationContext, "minis_hub_secrets")
}
