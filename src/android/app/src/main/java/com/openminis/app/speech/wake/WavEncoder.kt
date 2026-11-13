package com.openminis.app.speech.wake

/**
 * Wraps mono PCM16 in a WAV container.
 *
 * Both the background wake conversation and the composer's press-to-talk
 * hand raw capture buffers to the offline recogniser, and the recogniser
 * only accepts a WAV — so the header is written in exactly one place.
 */
object WavEncoder {

    fun encode(pcm: ShortArray, sampleRate: Int = WakeAudioCapture.SAMPLE_RATE): ByteArray {
        val dataSize = pcm.size * 2
        val out = ByteArray(44 + dataSize)

        fun put(offset: Int, text: String) {
            text.forEachIndexed { i, c -> out[offset + i] = c.code.toByte() }
        }

        fun putInt(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
            out[offset + 2] = ((value shr 16) and 0xFF).toByte()
            out[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        fun putShort(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        put(0, "RIFF")
        putInt(4, 36 + dataSize)
        put(8, "WAVE")
        put(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1) // PCM
        putShort(22, 1) // mono
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2)
        putShort(32, 2) // block align
        putShort(34, 16) // bits per sample
        put(36, "data")
        putInt(40, dataSize)
        pcm.forEachIndexed { i, s ->
            val v = s.toInt()
            out[44 + i * 2] = (v and 0xFF).toByte()
            out[44 + i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
