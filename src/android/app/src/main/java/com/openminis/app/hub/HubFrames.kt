package com.openminis.app.hub

import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire format of Minis Hub 0.31 (contract C2). Frames are JSON text; the
 * relay forwards them and never interprets them, so this file only has to be
 * exact about the field names the upstream uses.
 *
 * Nothing here may invent a field: the relay is nanoMuse's, and the PC side
 * is already tested against it (contract D1). An unknown frame is answered
 * with `error bad_frame` and the socket stays open (C2.1).
 */
object HubFrames {
    const val TYPE_HELLO = "hello"
    const val TYPE_WELCOME = "welcome"
    const val TYPE_DEVICES = "devices"
    const val TYPE_CALL = "call"
    const val TYPE_EVENT = "event"
    const val TYPE_RESULT = "result"
    const val TYPE_ERROR = "error"
    const val TYPE_PING = "ping"
    const val TYPE_PONG = "pong"
    const val TYPE_RENAME = "rename"
    const val TYPE_FORGET = "forget"
    const val TYPE_STOP = "stop"
    const val TYPE_PROFILE = "profile"
    const val TYPE_SYNC = "sync"
    const val TYPE_WORKING = "working"

    /** Client-side frame ceiling (contract C8: 16MB, same as the desktop). */
    const val MAX_FRAME_BYTES = 16 * 1024 * 1024

    /** Outbound queue ceiling (contract C8.2: 64 frames / 8MB, fail loudly). */
    const val MAX_OUT_FRAMES = 64
    const val MAX_OUT_BYTES = 8 * 1024 * 1024

    fun hello(deviceId: String, name: String, version: String, actions: List<String>): JSONObject =
        JSONObject()
            .put("type", TYPE_HELLO)
            .put("device", JSONObject().apply {
                put("id", deviceId)
                put("name", name)
                put("kind", "phone")
                put("os", "android")
                put("version", version)
                put("actions", JSONArray(actions))
            })

    fun call(id: String, to: String, action: String, args: JSONObject): JSONObject =
        JSONObject()
            .put("type", TYPE_CALL)
            .put("id", id)
            .put("to", to)
            .put("action", action)
            .put("args", args)

    fun ping(): JSONObject = JSONObject().put("type", TYPE_PING)

    fun error(code: String, message: String): JSONObject =
        JSONObject().put("type", TYPE_ERROR).put("code", code).put("message", message)

    /** Parse one frame. Returns null for anything that is not a JSON object. */
    fun parse(text: String): JSONObject? = try {
        val o = JSONObject(text)
        if (o.length() == 0) null else o
    } catch (_: Exception) {
        null
    }

    fun type(frame: JSONObject): String = frame.optString("type", "")

    /** The device list carried by `welcome` and `devices` frames. */
    fun devices(frame: JSONObject): List<HubDevice> {
        val arr = frame.optJSONArray("devices") ?: return emptyList()
        val out = ArrayList<HubDevice>(arr.length())
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            out.add(
                HubDevice(
                    id = d.optString("id", ""),
                    name = d.optString("name", ""),
                    kind = d.optString("kind", ""),
                    os = d.optString("os", ""),
                    actions = d.optJSONArray("actions")?.let { a ->
                        (0 until a.length()).map { idx -> a.optString(idx, "") }
                    } ?: emptyList(),
                    online = d.optBoolean("online", false),
                    controllable = d.optBoolean("controllable", true),
                    lastSeen = d.optLong("last_seen", 0L),
                )
            )
        }
        return out
    }
}

/** One entry of the account's device table. */
data class HubDevice(
    val id: String,
    val name: String,
    val kind: String,
    val os: String,
    val actions: List<String>,
    val online: Boolean,
    val controllable: Boolean,
    val lastSeen: Long,
) {
    /** A phone is never a target (contract C2.3 — only computers are). */
    val isComputer: Boolean get() = kind == "computer"
}
