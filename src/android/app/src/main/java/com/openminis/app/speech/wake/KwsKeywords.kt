package com.openminis.app.speech.wake

/**
 * Han characters -> sherpa-onnx keyword lines.
 *
 * The KWS model consumes **tonal pinyin tokens**, not Han characters:
 *
 *     x iǎo p ài @小派
 *
 * Android's ICU `Transliterator("Han-Latin")` produces exactly those tonal
 * syllables from API 29 onward. Older devices cannot use it, so the default
 * wake words are also kept as precomputed lines. Custom words simply fail to
 * load there instead of taking the whole pipeline down.
 */
object KwsKeywords {

    /** Initials must be longest-first: zh/ch/sh before z/c/s. */
    private val INITIALS = listOf(
        "zh", "ch", "sh",
        "b", "p", "m", "f", "d", "t", "n", "l",
        "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w",
    )

    /**
     * Precomputed for the words that ship with the feature, so API 26-28 can
     * still wake even though `android.icu.text.Transliterator` does not exist
     * there. Keys are the exact user-facing words.
     */
    private val BUILT_IN = mapOf(
        "你好米尼斯" to "n ǐ h ǎo m ǐ n í s ī @你好米尼斯",
        "米尼斯" to "m ǐ n í s ī @米尼斯",
        "你好小敏" to "n ǐ h ǎo x iǎo m ǐn @你好小敏",
        "小敏" to "x iǎo m ǐn @小敏",
        "小派" to "x iǎo p ài @小派",
    )

    /**
     * Loaded reflectively so merely referencing this object on API 26 does not
     * fail class verification for a class that was only added in API 29.
     */
    private val transliteratorClass: Class<*>? by lazy {
        runCatching { Class.forName("android.icu.text.Transliterator") }.getOrNull()
    }

    private val converter: Any? by lazy {
        if (transliteratorClass == null) return@lazy null
        runCatching {
            transliteratorClass
                ?.getMethod("getInstance", String::class.java)
                ?.invoke(null, "Han-Latin")
        }.getOrNull()
    }

    fun line(keyword: String): String? {
        val word = keyword.trim()
        if (word.isEmpty()) return null
        BUILT_IN[word]?.let { return it }

        val pinyin = transliterate(word)?.trim().orEmpty()
        if (pinyin.isEmpty()) return null
        val syllables = pinyin.split(Regex("\\s+"))
            .map { it.trim().lowercase().filter { ch -> ch.isLetter() } }
            .filter { it.isNotEmpty() }
        if (syllables.isEmpty()) return null
        val tokens = syllables.flatMap { splitSyllable(it) }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") + " @" + word
    }

    fun lines(words: List<String>): List<String> =
        words.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .mapNotNull { line(it) }
            .toList()

    private fun transliterate(word: String): String? {
        val c = converter ?: return null
        return runCatching {
            transliteratorClass
                ?.getMethod("transliterate", String::class.java)
                ?.invoke(c, word) as? String
        }.getOrNull()
    }

    private fun splitSyllable(syllable: String): List<String> {
        for (initial in INITIALS) {
            if (syllable.length > initial.length && syllable.startsWith(initial)) {
                return listOf(initial, syllable.substring(initial.length))
            }
        }
        // Zero-initial syllables (爱 ài, 安 ān) are one token.
        return listOf(syllable)
    }
}
