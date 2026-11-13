package com.openminis.app.speech.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The keyword line format is consumed by sherpa's native KWS parser, so a
 * formatting change must be caught here rather than on a device.
 */
class KwsKeywordsTest {

    @Test
    fun defaultWakeWordUsesPrecomputedTonalPinyin() {
        assertEquals(
            "n ǐ h ǎo m ǐ n í s ī @你好米尼斯",
            KwsKeywords.line("你好米尼斯"),
        )
    }

    @Test
    fun shortWakeWordHasZeroInitialHandling() {
        assertEquals("x iǎo m ǐn @小敏", KwsKeywords.line("小敏"))
    }

    @Test
    fun blankAndDuplicateWordsAreDropped() {
        assertNull(KwsKeywords.line(" "))
        assertEquals(
            listOf("x iǎo m ǐn @小敏"),
            KwsKeywords.lines(listOf("小敏", " ", "小敏")),
        )
    }
}
