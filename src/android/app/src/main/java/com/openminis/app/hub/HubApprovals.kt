package com.openminis.app.hub

import com.openminis.app.agent.userask.PendingAsk
import com.openminis.app.logging.AppLogger
import com.openminis.app.agent.userask.UserAskCenter
import com.openminis.app.agent.userask.UserAskOption
import com.openminis.app.agent.userask.UserAskQuestion
import com.openminis.app.agent.userask.UserAskQuestionType
import com.openminis.app.agent.userask.UserAskRisk
import com.openminis.app.agent.userask.UserAskResultStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The approval round trip (contract C4).
 *
 * The PC side sends `event{stage:"approval"}` and waits. The person answers
 * **here**, on the phone that asked for the work — the card is rendered by
 * the existing user-ask UI, inside the tool block that started the call, so
 * the answer and the request sit in the same place in the transcript.
 *
 * Two rules from the contract that shape this file:
 *  - C4.2 `approve` is a *call*, not a frame of its own: it goes out with an
 *    id, has a timeout and gets a result.
 *  - C4.6 the wait happens on the websocket frame layer, never inside an
 *    offload call: an `ASK_ONCE` that blocked an offload IPC would pin the
 *    guest command and one of the 128 offload workers for as long as the
 *    person takes to answer.
 */
object HubApprovals {
    private const val TAG = "HubApprovals"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Show the card and answer it.
     *
     * @param onAnswer called with (approvalId, allow) once the person answered
     *   or the card timed out — the caller turns that into the `approve` call.
     */
    fun present(
        card: JSONObject,
        sessionId: String,
        toolCallId: String,
        onAnswer: (String, Boolean) -> Unit,
    ) {
        val approvalId = card.optString("approval_id")
        if (approvalId.isEmpty()) return

        val timeoutSec = card.optInt("timeout", 120)
        val preview = card.optString("preview_zh").ifBlank { card.optString("preview") }
        val device = card.optString("device").ifBlank { "远程设备" }
        val risk = if (card.optString("risk") == "high") UserAskRisk.HIGH else UserAskRisk.MEDIUM

        val question = UserAskQuestion(
            type = UserAskQuestionType.OPTIONS,
            question = preview.ifBlank { "$device 请求执行一个远程动作" },
            header = "$device 的远程请求",
            options = listOf(
                UserAskOption("允许一次", "只执行这一次"),
                UserAskOption("拒绝", "不执行"),
            ),
            impact = "这个动作会在 $device 上真实执行",
            risk = risk,
        )
        val ask = PendingAsk.create(
            requestId = "hub-$approvalId",
            sessionId = sessionId,
            toolCallId = toolCallId,
            questions = listOf(question),
            nowMs = System.currentTimeMillis(),
            timeoutMinutes = ((timeoutSec + 59) / 60).coerceAtLeast(1),
        )
        // ownedByToolCall = false: this card is not the answer of the tool
        // call — the tool call's result is the remote output. Claiming it would
        // make the agent loop expect the card's own JSON as the tool result.
        UserAskCenter.begin(ask, ownedByToolCall = false)

        scope.launch {
            val result = UserAskCenter.wait(ask.requestId)
            val allow = parseAllow(result)
            AppLogger.info(TAG, "approval $approvalId answered allow=$allow")
            onAnswer(approvalId, allow)
        }
    }

    /**
     * The card's result JSON is an array of entries; an answered options
     * question carries the chosen label under "answer". Anything else — a
     * timeout, a dismissal, an unreadable payload — counts as "no".
     */
    // internal, not private: this is the one place where "允许一次" can turn
    // into allow=false, and it already did twice. HubApprovalsTest pins it.
    internal fun parseAllow(resultJson: String): Boolean {
        // One variable, assigned only. An earlier version declared a second
        // `var allow` inside the try and returned the outer one, which is why
        // "允许一次" kept going out as allow=false: Kotlin treats the shadowing
        // as a warning, so it compiled and looked right.
        var allow = false
        try {
            // The card's tool_result is an OBJECT: {"results":[{...},{...}]} —
            // UserAskResultEntry.encode(). A bare JSONArray here threw on every
            // answer, the exception was swallowed, and "允许一次" turned into
            // "拒绝" on the wire.
            val arr = JSONObject(resultJson).optJSONArray("results")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    // Only an answered card can allow. A timeout, a dismissal
                    // or a cancel carries no answer, and must never be read as
                    // a yes even if its note happens to contain the word.
                    if (e.optString("status") != UserAskResultStatus.ANSWERED.wire) continue
                    val answer = e.opt("answer")
                    val text = when (answer) {
                        is String -> answer
                        is JSONArray -> (0 until answer.length()).map { answer.optString(it, "") }.joinToString(" ")
                        else -> ""
                    }
                    // An explicit denial wins over anything else in the payload.
                    if (text.contains("拒绝")) {
                        allow = false
                        break
                    }
                    if (text.contains("允许")) allow = true
                }
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "cannot read the card result: ${e.message}")
            allow = false
        }
        return allow
    }
}
