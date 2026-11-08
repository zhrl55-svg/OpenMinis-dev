package com.openminis.app.hub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.openminis.app.logging.AppLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The phone's end of the hub: one outbound WebSocket to the relay, kept up.
 *
 * Everything here follows the contract, not taste:
 *  - C1.2 the first frame after connect is `hello`, sent at once;
 *  - C1.4 OkHttp's `pingInterval` is set explicitly (its default is 0, i.e.
 *    no heartbeat at all — the number-one cause of "the peer thinks it is
 *    online while it is dead"), plus an application-level `ping` every 25s and
 *    a watchdog that drops the link when ~50s pass with no inbound frame;
 *  - C1.5 the reconnect backoff starts at 1s, doubles, caps at 60s and resets
 *    on any inbound frame; a refused credential (4001/4002) stops the backoff
 *    and retries once a minute;
 *  - C2.1 an unknown frame is answered with `error bad_frame` and the socket
 *    stays open;
 *  - C8.2 the outbound queue is bounded (64 frames / 8MB) — a saturated link
 *    fails loudly instead of growing without bound.
 *
 * The client never listens on a port and never dials the peer: everything
 * goes through the relay, so the phone works from any network.
 */
class HubClient(private val listener: Listener) {

    interface Listener {
        /** The socket is open; the hello frame has been sent. */
        fun onOpen()

        /** Any inbound frame, already parsed. */
        fun onFrame(frame: JSONObject)

        /** The link went down for a reason we may retry. */
        fun onDisconnected(code: Int, reason: String)

        /** The relay refused this credential (4001/4002) — back off hard. */
        fun onRefused(code: Int, reason: String)

        /** Local problem worth surfacing. */
        fun onError(message: String)
    }

    enum class State { IDLE, CONNECTING, CONNECTED, REFUSED }

    companion object {
        private const val TAG = "HubClient"

        /** C1.4 — the WS-level heartbeat. */
        private const val WS_PING_SECONDS = 20L

        /** C1.4 — the application-level heartbeat. */
        private const val APP_PING_MS = 15_000L

        /** C1.4 — no inbound frame at all for this long means the link is dead. */
        private const val INBOUND_SILENCE_MS = 50_000L

        /** C1.5 — first retry delay; it doubles up to the cap. */
        private const val BACKOFF_MIN_MS = 1_000L
        private const val BACKOFF_MAX_MS = 60_000L

        /** C1.5 — a refused credential is retried once a minute, not on backoff. */
        private const val REFUSED_RETRY_MS = 60_000L

        /** C1.5 — `hub_paused` means "wait a minute", not "retry now". */
        private const val PAUSED_RETRY_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The http client. Built per start() because the proxy may differ per
     * configuration.
     *
     * The proxy matters: on the direct path the phone's VPN tears the
     * websocket down every 20-40 seconds; through the local proxy the same
     * socket stays up for hours (the Python probe proved it).
     */
    private var http: OkHttpClient = defaultHttp(null)

    /** AppLogger survives the release build; android.util.Log does not. */
    private fun log(message: String) = AppLogger.info(TAG, message)

    private fun defaultHttp(proxy: String?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .pingInterval(WS_PING_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
        if (!proxy.isNullOrBlank()) {
            try {
                val parts = proxy.split(":")
                val host = parts[0].trim()
                val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 7890
                builder.proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port)))
                AppLogger.info(TAG, "proxy = $host:$port")
            } catch (e: Exception) {
                AppLogger.info(TAG, "proxy ignored: ${e.message}")
            }
        }
        return builder.build()
    }

    private var socket: WebSocket? = null
    /** The endpoints to try, in order of preference (LAN first). */
    private var urls: List<String> = emptyList()
    private var urlIndex = 0
    private var apiKey: String? = null
    private var hello: JSONObject? = null

    private val outFrames = AtomicInteger(0)
    private val outBytes = AtomicLong(0)
    private val lastInbound = AtomicLong(0)
    private var backoffMs = BACKOFF_MIN_MS
    private var pingJob: Job? = null
    private var watchdogJob: Job? = null
    /**
     * Set by [stop]. Without it the close callback runs the reconnect path
     * and the socket comes back on its own — an orphan connection that then
     * fights the next one for the same device id.
     */
    @Volatile
    private var stopped = false

    @Volatile
    var state: State = State.IDLE
        private set

    /** Connect (or reconnect) with the given endpoint. */
    fun start(urls: List<String>, apiKey: String, hello: JSONObject, proxy: String? = null) {
        http = defaultHttp(proxy)
        this.urls = urls.filter { it.isNotBlank() }
        this.urlIndex = 0
        this.apiKey = apiKey
        this.hello = hello
        stopped = false
        openSocket()
    }

    fun stop() {
        stopped = true
        pingJob?.cancel()
        watchdogJob?.cancel()
        socket?.close(1000, "client stop")
        socket = null
        state = State.IDLE
    }

    private fun openSocket() {
        if (stopped) return
        val endpoint = urls.getOrNull(urlIndex) ?: return
        val key = apiKey ?: return
        state = State.CONNECTING
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLogger.info(TAG, "socket open")
                state = State.CONNECTED
                backoffMs = BACKOFF_MIN_MS
                // Prefer the first address again for the next cycle.
                urlIndex = 0
                lastInbound.set(System.currentTimeMillis())
                hello?.let { send(it) }
                startHeartbeats()
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastInbound.set(System.currentTimeMillis())
                backoffMs = BACKOFF_MIN_MS
                if (text.length > HubFrames.MAX_FRAME_BYTES) {
                    AppLogger.warning(TAG, "inbound frame over the ceiling: ${text.length}")
                    return
                }
                val frame = HubFrames.parse(text)
                if (frame == null) {
                    // C2.1 — bad frame, keep the socket open.
                    send(HubFrames.error("bad_frame", "unparseable frame"))
                    return
                }
                listener.onFrame(frame)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                AppLogger.info(TAG, "closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLogger.info(TAG, "closed: $code $reason")
                onLinkDown(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AppLogger.warning(TAG, "failure: ${t.message}")
                onLinkDown(-1, t.message ?: "")
            }
        })
    }

    private fun onLinkDown(code: Int, reason: String) {
        pingJob?.cancel()
        watchdogJob?.cancel()
        socket = null
        if (stopped) {
            state = State.IDLE
            return
        }
        when {
            code == 4001 || code == 4002 -> {
                // C1.5 — the credential is bad or the identity is taken.
                state = State.REFUSED
                listener.onRefused(code, reason)
                scheduleRetry(REFUSED_RETRY_MS)
            }
            code == 4003 && reason.contains("hub_paused") -> {
                state = State.CONNECTING
                scheduleRetry(PAUSED_RETRY_MS)
            }
            code == 4003 -> {
                // Replaced by a newer socket of the same id: reconnect.
                state = State.CONNECTING
                scheduleRetry(1_000L)
            }
            else -> {
                state = State.CONNECTING
                listener.onDisconnected(code, reason)
                // The next address in the list gets the next try: the LAN
                // relay first, the Tailscale one when the phone is out.
                if (urls.size > 1) urlIndex = (urlIndex + 1) % urls.size
                scheduleRetry(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
            }
        }
    }

    private fun scheduleRetry(delayMs: Long) {
        scope.launch {
            delay(delayMs)
            if (state != State.IDLE) openSocket()
        }
    }

    private fun startHeartbeats() {
        pingJob?.cancel()
        watchdogJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(APP_PING_MS)
                send(HubFrames.ping())
            }
        }
        watchdogJob = scope.launch {
            while (true) {
                delay(5_000L)
                val silent = System.currentTimeMillis() - lastInbound.get()
                if (silent > INBOUND_SILENCE_MS && state == State.CONNECTED) {
                    AppLogger.warning(TAG, "no inbound frame for ${silent}ms — dropping the link")
                    socket?.cancel()
                    return@launch
                }
            }
        }
    }

    /**
     * Send one frame. Returns false when the outbound budget is exhausted —
     * the caller must fail loudly (contract C8.2), never queue forever.
     */
    fun send(frame: JSONObject): Boolean {
        val text = frame.toString()
        val size = text.toByteArray(Charsets.UTF_8).size
        if (outFrames.get() >= HubFrames.MAX_OUT_FRAMES || outBytes.get() + size > HubFrames.MAX_OUT_BYTES) {
            listener.onError("hub send queue is full (${outFrames.get()} frames / ${outBytes.get()} bytes)")
            return false
        }
        val ws = socket ?: return false
        val ok = ws.send(text)
        if (ok) {
            outFrames.incrementAndGet()
            outBytes.addAndGet(size.toLong())
        }
        return ok
    }

    /** Called once an inbound frame was handled: the queue has room again. */
    fun noteInbound() {
        outFrames.set(0)
        outBytes.set(0)
    }
}
