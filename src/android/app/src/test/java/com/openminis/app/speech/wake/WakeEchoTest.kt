package com.openminis.app.speech.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wake acknowledgement and the request share one microphone stream, so a
 * segment may contain both.  Dropping the whole segment loses the request;
 * mistaking the whole segment for a request makes the assistant answer its own
 * "我在".  These cases pin the boundary between the two.
 */
class WakeEchoTest {

    private val wakeWords = listOf("你好米尼斯", "小米")

    @Test
    fun `a request spoken during the acknowledgement keeps the request`() {
        assertEquals(
            "现在几点了",
            WakeEcho.strip("小米，我在，现在几点了", wakeWords, ack = "我在"),
        )
    }

    @Test
    fun `the wake tail plus the acknowledgement leaves nothing`() {
        assertNull(WakeEcho.strip("小米我在", wakeWords, ack = "我在"))
        assertNull(WakeEcho.strip("你好小米，我在了", wakeWords, ack = "我在"))
    }

    @Test
    fun `punctuation does not make the acknowledgement look like a request`() {
        assertTrue(WakeEcho.isOnly("我在。", "我在"))
        assertTrue(WakeEcho.isOnly("我在了", "我在"))
        assertTrue(WakeEcho.isOnly("我在？", "我在"))
    }

    @Test
    fun `a real request after the acknowledgement is not echo`() {
        assertFalse(WakeEcho.isOnly("我在，现在几点了", "我在"))
        assertFalse(WakeEcho.isOnly("我在查天气", "我在"))
    }

    @Test
    fun `the wake tail is removed before the echo check`() {
        val withoutWake = WakeEcho.strip("小米我在", wakeWords)
        assertTrue(WakeEcho.isOnly(withoutWake, "我在"))
    }
}
