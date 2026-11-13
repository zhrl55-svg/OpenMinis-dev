package com.openminis.app.speech.wake

/**
 * Text-side echo handling for the wake conversation.
 *
 * The wake acknowledgement ("我在") and the user's request share one
 * microphone stream.  A VAD segment can therefore contain:
 *
 *  - only the acknowledgement;
 *  - the acknowledgement followed by the request; or
 *  - only the request (the acknowledgement already finished).
 *
 * The old service dropped every segment that overlapped the acknowledgement,
 * which also dropped the request when the user started talking while "我在"
 * was still playing.  These helpers let the service keep the request and only
 * discard a segment that has nothing but the acknowledgement.
 */
internal object WakeEcho {

    private const val PUNCTUATION = "，,。.！!？?、；;：:~～…"
    private val LEADING_PUNCTUATION = PUNCTUATION.toCharArray()
    private val ACK_PARTICLES = charArrayOf('了', '呢', '啊', '呀', '哦', '噢', '嗯')
    private val ACK_PREFIX_TRIM = LEADING_PUNCTUATION + ACK_PARTICLES

    /**
     * Drop the wake word (and optionally the acknowledgement) from [text].
     *
     * @return the remaining request, or null when nothing is left.
     */
    fun strip(text: String?, wakeWords: List<String>, ack: String = ""): String? {
        var out = text?.trim().orEmpty()
        if (out.isEmpty()) return null

        for (word in wakeWords) {
            if (word.isNotBlank()) out = out.replace(word, " ")
        }
        out = out.trim()

        // "你好" is the greeting half of most wake words and survives on its
        // own when the spotter only heard part of the keyword.
        if (out.length > 2 && out.startsWith("你好")) {
            out = out.removePrefix("你好").trim()
        }
        out = out.trimStart(*LEADING_PUNCTUATION)

        val acknowledgement = ack.trim()
        if (acknowledgement.isNotEmpty() && out.startsWith(acknowledgement)) {
            out = out.removePrefix(acknowledgement)
            out = out.trimStart(*ACK_PREFIX_TRIM)
        }

        return out.trim().ifEmpty { null }
    }

    /**
     * Is [text] nothing but the assistant's own acknowledgement?
     *
     * Punctuation and whitespace are ignored so ASR variants such as
     * "我在。" and "我在了" are recognised as the echo.  Any real request
     * after the acknowledgement ("我在，现在几点") is NOT an echo.
     */
    fun isOnly(text: String?, ack: String): Boolean {
        val spoken = normalize(text.orEmpty())
        val acknowledgement = normalize(ack)
        if (acknowledgement.isEmpty()) return false
        if (spoken.isEmpty()) return false
        if (spoken == acknowledgement) return true
        // The recogniser only caught part of the acknowledgement.
        if (acknowledgement.startsWith(spoken)) return true
        // The acknowledgement plus at most one stray character.
        if (spoken.startsWith(acknowledgement) &&
            spoken.length - acknowledgement.length <= 1
        ) {
            return true
        }
        return false
    }

    private fun normalize(text: String): String =
        text.filterNot { it.isWhitespace() || it in PUNCTUATION }
}
