package com.openminis.app.speech.wake

/**
 * Phrases that interrupt the assistant while it is speaking.
 *
 * The match is deliberately conservative: the transcript has to be short
 * (a command, not a sentence that happens to mention stopping) and contain
 * one of the explicit phrases or a close variant. This runs on every
 * barge-in segment, so false positives would cut real answers in half.
 */
internal object WakeStopPhrase {

    private val PHRASES = listOf(
        "停一下",
        "停一停",
        "停下来",
        "停止播报",
        "停止朗读",
        "停止说话",
        "先停",
        "停停停",
        "别说了",
        "别讲了",
        "别念了",
        "别吵",
        "不要说了",
        "不要讲了",
        "不要念了",
        "不用说了",
        "不用讲了",
        "不用念了",
        "安静",
        "闭嘴",
        "打住",
        "好了好了",
        "可以停了",
    )

    /**
     * Variants that are not a fixed phrase ("停一下" vs "停一停", "别再说").
     * Applied only to short transcripts, same as [PHRASES].
     */
    private val PATTERNS = listOf(
        Regex("停(一|下|住|止)"),
        Regex("别(再)?(说|讲|念|吵)"),
        Regex("不要(再)?(说|讲|念)"),
        Regex("不用(再)?(说|讲|念)"),
        Regex("安静(一|点)?"),
        Regex("闭嘴"),
    )

    /** Below this a transcript is clearly a command, not a normal request. */
    private const val MAX_COMMAND_CHARS = 12

    fun matches(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty() || normalized.length > MAX_COMMAND_CHARS) return false
        if (PHRASES.any { normalized.contains(it) }) return true
        return PATTERNS.any { it.containsMatchIn(normalized) }
    }

    /** Strip spaces and punctuation so ASR punctuation cannot hide a phrase. */
    fun normalize(text: String): String =
        text.lowercase().replace(PUNCTUATION, "")

    private val PUNCTUATION = Regex(
        "[\\s，。！？、；：,.!?;:\"'“”‘’（）()《》<>…—\\-·~]+",
    )
}
