package com.openminis.app.hub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-minis-hub] The arguments of a remote action.
 *
 * The schema says "args" is an object, but a model may hand it over as a
 * JSON string. The first version only read the object form, so the string
 * form became an empty args and the PC answered "shell needs
 * args.command" — a failure that looks like the remote side being broken.
 */
class HubCallToolTest {

    @Test
    fun `args as an object are used as they are`() {
        val args = JSONObject().put("args", JSONObject().put("command", "ls -la"))
        assertEquals("ls -la", HubCallTool.actionArgs(args).optString("command"))
    }

    @Test
    fun `args as a json string are parsed`() {
        val args = JSONObject().put("args", "{\"command\":\"echo hi\"}")
        assertEquals("echo hi", HubCallTool.actionArgs(args).optString("command"))
    }

    @Test
    fun `args that make no sense become an empty object`() {
        assertEquals(0, HubCallTool.actionArgs(JSONObject().put("args", "not json")).length())
        assertEquals(0, HubCallTool.actionArgs(JSONObject()).length())
    }
}
