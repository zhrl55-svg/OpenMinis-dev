package com.openminis.app.assistant.execution

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LocalConfirmationBridgeTest {

    @After
    fun clear() {
        LocalConfirmationBridge.unregister("pending")
    }

    @Test
    fun `short confirm resolves the pending card`() {
        var confirmed = false
        LocalConfirmationBridge.register(
            id = "pending",
            sessionId = "session-a",
            confirm = { confirmed = true },
            cancel = {},
        )

        assertEquals(
            LocalConfirmationBridge.Resolution.CONFIRM,
            LocalConfirmationBridge.resolve("确认。"),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(confirmed)
        assertFalse(LocalConfirmationBridge.hasPending())
    }

    @Test
    fun `cancel resolves and clears without confirming`() {
        var cancelled = false
        LocalConfirmationBridge.register(
            id = "pending",
            sessionId = "session-a",
            confirm = {},
            cancel = { cancelled = true },
        )

        assertEquals(
            LocalConfirmationBridge.Resolution.CANCEL,
            LocalConfirmationBridge.resolve("取消"),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(cancelled)
        assertFalse(LocalConfirmationBridge.hasPending())
    }

    @Test
    fun `a new request is not treated as an answer`() {
        var called = false
        LocalConfirmationBridge.register(
            id = "pending",
            sessionId = "session-a",
            confirm = { called = true },
            cancel = { called = true },
        )

        assertNull(LocalConfirmationBridge.resolve("确认一下明天的日程"))
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(called)
        assertTrue(LocalConfirmationBridge.hasPending())
    }
}
