package com.openminis.app.hub

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The hub on the phone: the device table, the calls in flight, and the frame
 * routing between the websocket ([HubClient]) and the agent.
 *
 * This is the caller side of the contract (scenario ②: the phone asks the
 * computer to do something). The phone does not execute remote actions in
 * v0.1 — contract C3.5 fixed that whitelist for a later version — so an
 * inbound `call` is refused instead of executed.
 */
object Hub {
    private const val TAG = "Hub"

    /** C6 — a call nobody answers fails after 15 minutes upstream. */
    const val CALL_TIMEOUT_MS = 15 * 60 * 1000L

    enum class Status { OFFLINE, CONNECTING, ONLINE, REFUSED }

    data class HubResult(
        val ok: Boolean,
        val body: JSONObject? = null,
        val error: String? = null,
        val message: String? = null,
    )

    class PendingCall(
        val deferred: CompletableDeferred<HubResult>,
        val target: String,
        val sessionId: String,
        val toolCallId: String,
    )

    private var client: HubClient? = null
    private var appContext: Context? = null
    /** What the client was built with, so a config change is noticed. */
    private var configStamp: String? = null
    private val deviceTable = CopyOnWriteArrayList<HubDevice>()
    private val pending = ConcurrentHashMap<String, PendingCall>()

    @Volatile
    var status: Status = Status.OFFLINE
        private set

    @Volatile
    var lastError: String? = null
        private set

    /**
     * Called whenever the link state or the device table changes, so the
     * foreground notification can say something true (it used to stay on
     * "正在连接中继…" for the whole life of the service).
     */
    @Volatile
    var onStateChanged: (() -> Unit)? = null

    /** Start the link if it is not up. Safe to call from anywhere. */
    fun ensureStarted(context: Context) {
        val ctx = context.applicationContext
        if (client != null) return
        val url = HubIdentity.relayUrl(ctx)
        val key = HubIdentity.apiKey(ctx)
        if (url.isNullOrBlank() || key.isNullOrBlank()) {
            lastError = "还没有配置中继地址或凭据"
            status = Status.OFFLINE
            return
        }
        appContext = ctx
        val c = HubClient(object : HubClient.Listener {
            override fun onOpen() {
                status = Status.ONLINE
                AppLogger.info(TAG, "hub online")
                onStateChanged?.invoke()
            }

            override fun onFrame(frame: JSONObject) {
                client?.noteInbound()
                handle(frame)
            }

            override fun onDisconnected(code: Int, reason: String) {
                status = Status.CONNECTING
                lastError = "连接断开 ($code $reason)"
                failAllPending("连接断开")
                onStateChanged?.invoke()
                // The config may have changed while we were connected (a new
                // endpoint, the proxy, the on/off switch). The client was
                // built with the old values, so rebuild it.
                appContext?.let { refreshConfig(it) }
            }

            override fun onRefused(code: Int, reason: String) {
                status = Status.REFUSED
                lastError = if (code == 4002) "设备身份冲突 ($reason)" else "凭据被拒绝 ($reason)"
                failAllPending(lastError ?: "连接被拒绝")
                onStateChanged?.invoke()
            }

            override fun onError(message: String) {
                lastError = message
            }
        })
        client = c
        val hello = HubFrames.hello(
            HubIdentity.deviceId(ctx),
            HubIdentity.deviceName(ctx),
            "minis-android/0.1",
            emptyList(),
        )
        val urls = HubIdentity.relayUrls(ctx)
        val proxy = HubIdentity.proxyFor(ctx)
        configStamp = urls.joinToString("|") + "#" + proxy
        c.start(urls, key, hello, proxy)
        status = Status.CONNECTING
    }

    /**
     * Re-read the configuration and rebuild the client when it changed.
     * Without this, editing hub.json had no effect until the app was
     * reinstalled — the client kept the endpoint it was born with.
     */
    private fun refreshConfig(ctx: Context) {
        if (!HubIdentity.isEnabled(ctx)) {
            AppLogger.info(TAG, "hub is switched off by the configuration")
            configStamp = null
            client?.stop()
            client = null
            status = Status.OFFLINE
            onStateChanged?.invoke()
            return
        }
        val urls = HubIdentity.relayUrls(ctx)
        val proxy = HubIdentity.proxyFor(ctx)
        val stamp = urls.joinToString("|") + "#" + proxy
        if (stamp == configStamp) return
        configStamp = stamp
        AppLogger.info(TAG, "hub configuration changed — reconnecting: $urls")
        client?.stop()
        client = null
        ensureStarted(ctx)
    }

    fun stop() {
        client?.stop()
        client = null
        status = Status.OFFLINE
        failAllPending("hub 已停止")
    }

    fun devices(): List<HubDevice> = deviceTable.toList()

    /** True once a relay endpoint and a credential are known. */
    fun hasCredentials(context: Context): Boolean = HubIdentity.hasCredentials(context)

    /**
     * Devices a call may be addressed to: the online computers.
     *
     * A computer with its remote control switched off stays in the list on
     * purpose: the relay answers such a call with `not_controllable`, and the
     * tool turns that into "对方没有开放远程控制". Hiding it instead would
     * leave the model with "找不到设备" and no way to tell "the computer is
     * off" from "the computer is gone" (contract A5).
     */
    fun onlineTargets(): List<HubDevice> =
        deviceTable.filter { it.online && it.isComputer }

    fun onlineTargetNames(): List<String> = onlineTargets().map { it.name }

    fun find(nameOrId: String): HubDevice? {
        val q = nameOrId.trim()
        if (q.isEmpty()) return null
        return deviceTable.firstOrNull { it.id == q }
            ?: deviceTable.firstOrNull { it.name.equals(q, ignoreCase = true) }
            ?: deviceTable.firstOrNull { it.name.contains(q, ignoreCase = true) }
    }

    // ── calling ────────────────────────────────────────────────────────────

    /**
     * Ask a device to do something and wait for the answer.
     *
     * The call id follows contract C7.1: `<device_id>:<seq>:<rand4>` with the
     * sequence persisted, so a restart never reuses an id.
     */
    suspend fun call(
        target: String,
        action: String,
        args: JSONObject,
        sessionId: String,
        toolCallId: String,
        timeoutMs: Long = CALL_TIMEOUT_MS,
    ): HubResult {
        val c = client
        if (c == null || c.state != HubClient.State.CONNECTED) {
            return HubResult(false, null, "offline", "hub 未连接")
        }
        val ctx = appContext ?: return HubResult(false, null, "offline", "hub 未初始化")
        val id = nextCallId(ctx)
        val deferred = CompletableDeferred<HubResult>()
        pending[id] = PendingCall(deferred, target, sessionId, toolCallId)
        if (!c.send(HubFrames.call(id, target, action, args))) {
            pending.remove(id)
            return HubResult(false, null, "queue_full", "发送队列已满，请稍后再试")
        }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result == null) {
            pending.remove(id)
            return HubResult(false, null, "timeout", "远程设备没有在期限内回应")
        }
        return result
    }

    /** C7.1 — `<device_id>:<monotonic_seq>:<rand4>`. */
    private fun nextCallId(context: Context): String {
        val seq = HubIdentity.nextSeq(context)
        val rand = UUID.randomUUID().toString().replace("-", "").take(4)
        return "${HubIdentity.deviceId(context)}:$seq:$rand"
    }

    private fun failAllPending(reason: String) {
        val ids = pending.keys.toList()
        for (id in ids) {
            pending.remove(id)?.deferred?.complete(HubResult(false, null, "disconnected", reason))
        }
    }

    // ── inbound frames ─────────────────────────────────────────────────────

    private fun handle(frame: JSONObject) {
        when (HubFrames.type(frame)) {
            HubFrames.TYPE_WELCOME -> {
                deviceTable.clear()
                deviceTable.addAll(HubFrames.devices(frame))
                status = Status.ONLINE
                onStateChanged?.invoke()
            }
            HubFrames.TYPE_DEVICES -> {
                deviceTable.clear()
                deviceTable.addAll(HubFrames.devices(frame))
                onStateChanged?.invoke()
            }
            HubFrames.TYPE_RESULT -> {
                val id = frame.optString("id")
                val call = pending.remove(id) ?: return
                val ok = frame.optBoolean("ok", false)
                call.deferred.complete(
                    HubResult(
                        ok = ok,
                        body = frame.optJSONObject("body"),
                        // A failing result carries "error"; an error frame
                        // carries "code" (hub.md C2). Read both, or every
                        // refusal turns into the word "error".
                        error = if (ok) null else frame.optString("error", frame.optString("code", "error")),
                        message = frame.optString("message", ""),
                    )
                )
            }
            HubFrames.TYPE_ERROR -> {
                val id = frame.optString("id")
                val call = if (id.isEmpty()) null else pending.remove(id)
                call?.deferred?.complete(
                    HubResult(
                        false,
                        null,
                        frame.optString("code", frame.optString("error", "error")),
                        frame.optString("message", ""),
                    )
                )
            }
            HubFrames.TYPE_EVENT -> {
                val body = frame.optJSONObject("body") ?: return
                when (body.optString("stage")) {
                    "approval" -> onApproval(frame.optString("id"), body)
                    "approval_result" -> AppLogger.info(TAG, "approval result: ${body.optString("status")}")
                    else -> Unit
                }
            }
            HubFrames.TYPE_CALL -> {
                // v0.1: the phone does not execute remote actions (C3.5).
                val id = frame.optString("id")
                if (id.isNotEmpty()) {
                    client?.send(
                        JSONObject()
                            .put("type", HubFrames.TYPE_RESULT)
                            .put("id", id)
                            .put("ok", false)
                            .put("error", "not_allowed")
                            .put("message", "这台手机还没有开放被远程操作")
                    )
                }
            }
            else -> Unit
        }
    }

    /** C4 — the card is shown where the request came from, and answered there. */
    private fun onApproval(callId: String, body: JSONObject) {
        val call = pending[callId] ?: run {
            AppLogger.info(TAG, "approval for an unknown call $callId")
            return
        }
        HubApprovals.present(body, call.sessionId, call.toolCallId) { approvalId, allow ->
            sendApprove(call.target, approvalId, allow)
        }
    }

    /** C4.2 — `approve` is a call: it has an id, a timeout and a result. */
    private fun sendApprove(target: String, approvalId: String, allow: Boolean) {
        val ctx = appContext ?: return
        val id = nextCallId(ctx)
        val args = JSONObject()
            .put("approval_id", approvalId)
            .put("allow", allow)
            .put("scope", "once")
        client?.send(HubFrames.call(id, target, "approve", args))
    }
}
