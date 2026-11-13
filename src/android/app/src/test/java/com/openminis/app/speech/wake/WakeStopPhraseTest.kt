package com.openminis.app.speech.wake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeStopPhraseTest {

    @Test
    fun `explicit stop commands match`() {
        for (text in listOf(
            "停一下",
            "停一停",
            "停下来！",
            "别说了",
            "不要讲了",
            "不用念了",
            "安静一点",
            "闭嘴",
            "打住",
            "停止播报",
        )) {
            assertTrue(text, WakeStopPhrase.matches(text))
        }
    }

    @Test
    fun `punctuation and spaces do not hide a command`() {
        assertTrue(WakeStopPhrase.matches("停、一下。"))
        assertTrue(WakeStopPhrase.matches("别 说 了"))
    }

    @Test
    fun `normal requests that merely mention stopping are not commands`() {
        assertFalse(WakeStopPhrase.matches("帮我查一下明天北京到上海的高铁还有没有票"))
        assertFalse(WakeStopPhrase.matches("停车场的收费标准是什么"))
        assertFalse(WakeStopPhrase.matches("你能不能告诉我怎么停止这个定时任务"))
    }
}
