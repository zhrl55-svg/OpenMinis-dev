package com.openminis.app.assistant.routing

import org.json.JSONObject

sealed class RouteDecision {
    data class Local(
        val route: AssistantRoute,
        val capability: String,
        val action: String,
        val args: JSONObject,
        val needsConfirmation: Boolean,
        val needsModelExtraction: Boolean,
        val missingSlots: Set<String>,
        val score: Int,
        val ranked: List<RouteMatch> = emptyList(),
        val summary: String = "",
        val ambiguousTime: Boolean = false,
    ) : RouteDecision()

    data class Cloud(
        val reason: String,
        val note: String? = null,
        val ranked: List<RouteMatch> = emptyList(),
    ) : RouteDecision()
}

/**
 * Single entry point from "a sentence" to either a local call or the cloud.
 * This class does not spawn coroutines or touch the model; free-text model
 * filling is an explicit follow-up step in ChatViewModel so the deterministic
 * path never waits on model loading.
 */
class AssistantRouter(
    private val extractor: SlotExtractor = SlotExtractor(),
) {
    fun route(
        userText: String,
        routes: List<AssistantRoute>,
    ): RouteDecision {
        val ranked = AssistantRouteMatcher.rank(userText, routes, limit = 3)
        val best = ranked.firstOrNull()
            ?: return RouteDecision.Cloud("no-match")
        if (best.score < AssistantRouteMatcher.MIN_SCORE) {
            return RouteDecision.Cloud("low-confidence", ranked = ranked)
        }

        val capability = LocalCapabilityRegistry.get(best.route.capability)
            ?: return RouteDecision.Cloud("unknown-capability", ranked = ranked)
        val action = capability.action(best.route.action)
            ?: return RouteDecision.Cloud("unknown-action", ranked = ranked)

        val extraction = extractor.extract(userText, best.route)
        if (capability.id == "reminder" && action.name == "schedule" &&
            extraction.args.optString("at").isBlank() &&
            extraction.args.optLong("after", 0L) <= 0L
        ) {
            return RouteDecision.Cloud("no-args", note = "no-args", ranked = ranked)
        }
        if (capability.id == "scheduled_task" && action.name == "create" &&
            extraction.args.optString("at").isBlank() &&
            extraction.args.optLong("after", 0L) <= 0L
        ) {
            return RouteDecision.Cloud("no-args", note = "no-args", ranked = ranked)
        }
        val readOnly = action.readOnly
        val highRisk = action.risk == CapabilityRisk.HIGH
        val needsConfirmation = when (best.route.confirm) {
            ConfirmMode.ALWAYS -> true
            ConfirmMode.NEVER -> highRisk
            ConfirmMode.WRITE -> !readOnly || highRisk
        }

        // A write route built only from a one-character/weak keyword is too
        // easy to trigger by accident. Let the stronger cloud agent handle it.
        if (!readOnly && !best.isStrong) {
            return RouteDecision.Cloud("weak-write-match", ranked = ranked)
        }
        if (extraction.ambiguous && !readOnly) {
            // Ambiguity is surfaced to the user through the confirmation card
            // rather than silently guessing.
            return RouteDecision.Local(
                route = best.route,
                capability = capability.id,
                action = action.name,
                args = extraction.args,
                needsConfirmation = true,
                needsModelExtraction = false,
                missingSlots = extraction.missing,
                score = best.score,
                ranked = ranked,
                summary = extraction.summary,
                ambiguousTime = true,
            )
        }

        if (extraction.missing.isNotEmpty()) {
            if (capability.needsLlm) {
                return RouteDecision.Local(
                    route = best.route,
                    capability = capability.id,
                    action = action.name,
                    args = extraction.args,
                    needsConfirmation = needsConfirmation,
                    needsModelExtraction = true,
                    missingSlots = extraction.missing,
                    score = best.score,
                    ranked = ranked,
                    summary = extraction.summary,
                    ambiguousTime = extraction.ambiguous,
                )
            }
            return RouteDecision.Cloud("no-args", note = "no-args", ranked = ranked)
        }

        return RouteDecision.Local(
            route = best.route,
            capability = capability.id,
            action = action.name,
            args = extraction.args,
            needsConfirmation = needsConfirmation,
            needsModelExtraction = false,
            missingSlots = emptySet(),
            score = best.score,
            ranked = ranked,
            summary = extraction.summary,
            ambiguousTime = extraction.ambiguous,
        )
    }
}
