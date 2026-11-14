package com.openminis.app.speech.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音请求的断句规则：由静音时长决定结束，而不是固定时长。
 *
 * 真机反馈「长语音只进去前半段」：旧规则 600ms 静音就切句、15s 强制切段，
 * 长听写被切成多段（每段重新过声纹，被拒的那段直接丢），唤醒对话里
 * 8s 窗口还会把没说完的句子提前送走。
 */
class WakeEnergyVadTest {

    private val speech = ShortArray(FRAME_SAMPLES) { 3000 }
    private val silence = ShortArray(FRAME_SAMPLES)

    private fun vad(
        minSilenceMs: Int,
        maxSegmentMs: Int,
        onSegment: (ShortArray) -> Unit,
    ) = WakeEnergyVad(
        onSpeechStart = {},
        onSegment = onSegment,
        minSilenceMs = { minSilenceMs },
        maxSegmentMs = { maxSegmentMs },
    )

    @Test
    fun `a natural pause shorter than the silence window keeps the sentence open`() {
        val segments = mutableListOf<ShortArray>()
        val v = vad(1_200, 60_000) { segments += it }
        repeat(3) { v.feed(speech, speech.size) } // 开口
        repeat(25) { v.feed(speech, speech.size) } // 560ms 语音
        repeat(29) { v.feed(silence, silence.size) } // 580ms 句中停顿
        assertEquals("0.58s 的停顿不应断句", 0, segments.size)

        repeat(40) { v.feed(silence, silence.size) } // 累计 1.38s 静音
        assertEquals(1, segments.size)
    }

    @Test
    fun `continuous speech is not cut at the old fifteen second limit`() {
        val segments = mutableListOf<ShortArray>()
        val v = vad(1_200, 60_000) { segments += it }
        repeat(3 + 750) { v.feed(speech, speech.size) } // 约 15s 连续说话
        assertEquals(0, segments.size)
    }

    @Test
    fun `the maximum segment length is only a safety valve`() {
        val segments = mutableListOf<ShortArray>()
        val v = vad(1_200, 20_000) { segments += it }
        repeat(3 + 1_000) { v.feed(speech, speech.size) } // 约 20s 不停顿
        assertEquals(1, segments.size)
        assertTrue(segments[0].size * 1000 / WakeAudioCapture.SAMPLE_RATE >= 19_000)
    }

    private companion object {
        const val FRAME_SAMPLES = WakeAudioCapture.SAMPLE_RATE / 1000 * WakeAudioCapture.FRAME_MS
    }
}
