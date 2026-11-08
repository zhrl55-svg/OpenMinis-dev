package com.openminis.app.hub

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-minis-hub] The answer side of the approval round trip.
 *
 * Two regressions motivated this file, both of which compiled clean and only
 * showed up on the wire:
 *
 *  1. `JSONArray(resultJson)` — the card's result is an OBJECT with a
 *     "results" array, so every answer threw and was swallowed into "deny".
 *  2. `var allow` declared twice, the inner one shadowing the outer — Kotlin
 *     only warns, so "允许一次" kept sending allow=false.
 *
 * These vectors are the shapes UserAskResultEntry.encode() actually produces.
 */
class HubApprovalsTest {

    /** One entry of the results array, as UserAskResultEntry.toJson() writes it. */
    private fun entry(status: String, vararg answer: String): JSONObject =
        JSONObject().put("status", status).put("answer", JSONArray(answer.toList()))

    private fun results(vararg entries: JSONObject): String =
        JSONObject().put("results", JSONArray(entries.toList())).toString()

    @Test
    fun `choosing the allow option allows`() {
        assertTrue(HubApprovals.parseAllow(results(entry("answered", "允许一次"))))
    }

    @Test
    fun `choosing the deny option denies`() {
        assertFalse(HubApprovals.parseAllow(results(entry("answered", "拒绝"))))
    }

    /**
     * The card can carry several questions; the first answered one decides.
     * An explicit denial anywhere in the payload still wins.
     */
    @Test
    fun `a denial later in the payload wins over an earlier allow`() {
        assertFalse(
            HubApprovals.parseAllow(
                results(entry("answered", "允许一次"), entry("answered", "拒绝")),
            ),
        )
    }

    /**
     * A card nobody answered must never be read as a yes, even though its
     * stored payload is well-formed.
     */
    @Test
    fun `a timeout is a no`() {
        assertFalse(HubApprovals.parseAllow(results(entry("timeout"))))
    }

    @Test
    fun `a dismissal is a no`() {
        assertFalse(HubApprovals.parseAllow(results(entry("dismissed"))))
    }

    @Test
    fun `an unanswered card with empty answers is a no`() {
        assertFalse(HubApprovals.parseAllow(results(entry("answered"))))
    }

    /**
     * The exact bug: a bare array (the old assumption) must not throw its way
     * into a silent deny that looks like a working path. It is still a deny —
     * but for the right reason, and the allow path is exercised above.
     */
    @Test
    fun `a bare array is a no rather than an exception`() {
        assertFalse(HubApprovals.parseAllow(JSONArray().put(entry("answered", "允许一次")).toString()))
    }

    @Test
    fun `garbage is a no`() {
        assertFalse(HubApprovals.parseAllow(""))
        assertFalse(HubApprovals.parseAllow("not json"))
        assertFalse(HubApprovals.parseAllow("{\"results\":null}"))
    }
}
