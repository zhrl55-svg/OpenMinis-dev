package com.openminis.app.speech.wake

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.openminis.app.logging.AppLogger
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Voiceprint gate: "is the voice that called us the owner?"
 *
 ## What it is for

 A wake word is public — anyone can shout it. The voiceprint makes the
 assistant answer only its owner, and it also blocks the assistant's own
 TTS echo (a machine voice is a long way from the owner's embedding).

 The gate runs twice: once on the wake word itself, before the assistant
 says or records anything, and once on every request after it. The
 wake-time check is what keeps a TV or a passer-by from opening a silent
 eight-second conversation every time the KWS model hears the phrase.

 ## What it is NOT

 This is a **soft gate that lowers false triggers, not authentication**.
 Raising the threshold locks the owner out (a car or a changed environment
 can drop the score below 0.1), so the policy is "rather let it through
 than lock the owner out": no template, model not ready, or audio too
 short — all of those let the utterance through.

 ## Model

 `campplus_sv_zh_16k.onnx` (28MB, sherpa's 3D-Speaker build). The original
 WeSpeaker `cnceleb_resnet34_LM.onnx` carries **no sherpa metadata**; the
 native constructor then fails silently and the next `createStream` SIGSEGVs
 (pilot crashed five times before locating it), so it is only a fallback.
 */
class WakeVoiceprintGate(private val context: Context) {

    private var extractor: SpeakerEmbeddingExtractor? = null
    private var initTried = false
    private var initFailed = false

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** Cosine similarity of the last [verify] call; -1 when there was none. */
    @Volatile
    var lastScore: Float = -1f
        private set

    /** The model file, by priority. */
    fun modelFile(): File {
        val dirs = buildList {
            context.getExternalFilesDir(null)?.let { add(File(it, "models/$MODEL_DIR_NAME")) }
            add(File(context.filesDir, "models/$MODEL_DIR_NAME"))
        }
        for (dir in dirs) {
            for (name in MODEL_CANDIDATES) {
                val f = File(dir, name)
                if (f.isFile && f.length() > 1_000_000) return f
            }
        }
        return File(dirs.first(), MODEL_CANDIDATES.first())
    }

    private fun templateFile(): File = File(context.filesDir, TEMPLATE_FILE)

    /** True once an owner template has been enrolled. */
    fun hasTemplate(): Boolean = templateFile().isFile && templateFile().length() > 256

    fun ensureReady(): Boolean {
        if (ready) return true
        if (initTried && initFailed) return false
        initTried = true

        val model = modelFile()
        if (!model.isFile) {
            initFailed = true
            lastError = "声纹模型缺失：${model.absolutePath}"
            AppLogger.info(TAG, lastError!!)
            return false
        }
        return try {
            val started = System.currentTimeMillis()
            val cfg = SpeakerEmbeddingExtractorConfig(
                model = model.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )
            // The model lives in a real directory, so the asset manager must
            // be null — passing context.assets aborts the native side.
            extractor = SpeakerEmbeddingExtractor(null, cfg)
            ready = true
            initFailed = false
            lastError = null
            AppLogger.info(TAG, "声纹模型就绪 ${System.currentTimeMillis() - started}ms")
            true
        } catch (t: Throwable) {
            initFailed = true
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            AppLogger.info(TAG, "声纹模型加载失败 $lastError")
            false
        }
    }

    /** Extract an embedding from 16 kHz mono PCM. */
    fun embed(pcm: ShortArray, sampleRate: Int = WakeAudioCapture.SAMPLE_RATE): FloatArray? {
        if (!ensureReady()) return null
        if (sampleRate != WakeAudioCapture.SAMPLE_RATE) return null
        // Too short to describe a voice reliably.
        if (pcm.size < WakeAudioCapture.SAMPLE_RATE * 0.6f) {
            AppLogger.info(TAG, "声纹跳过：音频仅 ${pcm.size * 1000 / WakeAudioCapture.SAMPLE_RATE}ms")
            return null
        }
        val ex = extractor ?: return null
        var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
        return try {
            val samples = FloatArray(pcm.size) { pcm[it] / 32768.0f }
            stream = ex.createStream()
            // No inputFinished(): that is for streaming ASR and the speaker
            // extractor does not want it.
            stream.acceptWaveform(samples, sampleRate)
            val raw = ex.compute(stream)
            val norm = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
            if (norm <= 0f) null else FloatArray(raw.size) { raw[it] / norm }
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            AppLogger.info(TAG, "声纹提取失败 $lastError")
            null
        } finally {
            runCatching { stream?.release() }
        }
    }

    /**
     * Enroll on the first utterance: with no template yet, the first long
     * enough piece of speech becomes the owner's voiceprint.
     *
     * The pilot's reason for this over a "read a passphrase three times"
     * dialog: the passphrase has to match the wake word to be judged
     * accurately, users never remember it, and the dialog is heavy.
     */
    fun enrollIfAbsent(pcm: ShortArray, minSeconds: Float = 1.5f): Boolean {
        if (hasTemplate()) return false
        val seconds = pcm.size.toDouble() / WakeAudioCapture.SAMPLE_RATE
        if (seconds < minSeconds) {
            AppLogger.info(TAG, "首句注册：音频 ${"%.1f".format(seconds)}s 太短，不注册")
            return false
        }
        val ok = enroll(pcm)
        AppLogger.info(TAG, "首句注册：${"%.1f".format(seconds)}s → saved=$ok")
        return ok
    }

    /** Overwrite the template (enroll / re-enroll). */
    fun enroll(pcm: ShortArray): Boolean {
        val emb = embed(pcm) ?: return false
        return runCatching {
            val buf = ByteBuffer.allocate(emb.size * 4)
            emb.forEach { buf.putFloat(it) }
            templateFile().writeBytes(buf.array())
            AppLogger.info(TAG, "声纹模板已保存 dim=${emb.size}")
            true
        }.getOrElse {
            AppLogger.info(TAG, "声纹模板保存失败 ${it.message}")
            false
        }
    }

    /**
     * Compare against the template.
 *
     * @return the cosine similarity, or null when the check could not run —
 *   the caller must **let the utterance through** in that case.
     */
    fun verify(pcm: ShortArray): Float? {
        if (!hasTemplate()) return null
        if (!ensureReady()) return null
        val emb = embed(pcm) ?: return null
        val tpl = loadTemplate() ?: return null
        if (tpl.size != emb.size) {
            AppLogger.info(TAG, "声纹维度不匹配 tpl=${tpl.size} now=${emb.size}，请重新注册")
            return null
        }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in emb.indices) {
            dot += emb[i] * tpl[i]
            na += emb[i] * emb[i]
            nb += tpl[i] * tpl[i]
        }
        if (na == 0.0 || nb == 0.0) return null
        val score = (dot / (sqrt(na) * sqrt(nb))).toFloat()
        lastScore = score
        return score
    }

    /** Forget the owner template. */
    fun clearTemplate() {
        runCatching { templateFile().delete() }
        AppLogger.info(TAG, "声纹模板已清除")
    }

    fun release() {
        runCatching { extractor?.release() }
        extractor = null
        ready = false
        initTried = false
        initFailed = false
    }

    private fun loadTemplate(): FloatArray? = runCatching {
        val f = templateFile()
        if (!f.isFile) return null
        val bytes = f.readBytes()
        val buf = ByteBuffer.wrap(bytes)
        FloatArray(bytes.size / 4) { buf.float }
    }.getOrNull()

    companion object {
        private const val TAG = "VoiceWake"
        private const val MODEL_DIR_NAME = "voiceprint"
        private const val TEMPLATE_FILE = "voiceprint_template.bin"

        /**
         * Where the owner's template lives.
         *
         * Exposed as a static so the settings screen can forget the
         * template without loading the 28 MB model first.
         */
        fun templatePath(context: Context): File =
            File(context.applicationContext.filesDir, TEMPLATE_FILE)

        /** Forget the owner template (used when the gate is switched off). */
        fun clearTemplate(context: Context): Boolean = runCatching {
            templatePath(context).delete()
        }.getOrDefault(false)

        /**
         * `campplus_sv_zh_16k.onnx` is sherpa's official 3D-Speaker build
         * (its metadata says `framework=3d-speaker`), so the extractor
         * initialises correctly. The raw WeSpeaker resnet34 has no metadata
         * sherpa recognises, so the native constructor fails silently and
         * `createStream` SIGSEGVs — pilot crashed five times on it.
         */
        val MODEL_CANDIDATES = listOf("campplus_sv_zh_16k.onnx", "cnceleb_resnet34_LM.onnx")

        /**
         * Gate threshold.
         *
         * pilot measured on a real device: TTS echo 0.02–0.11, the owner
         0.39–0.52. 0.30 sits in between, biased towards the owner — better
         to let a stranger wake it once (they still cannot give a command
         that passes the gate) than to lock the owner out. The same value
         gates the wake word itself and the request that follows it.
         */
        const val WAKE_THRESHOLD = 0.30f
    }
}
