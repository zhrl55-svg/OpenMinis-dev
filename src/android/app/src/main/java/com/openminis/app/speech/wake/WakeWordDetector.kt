package com.openminis.app.speech.wake

import android.content.Context
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.openminis.app.logging.AppLogger
import java.io.File

/** Where the KWS zipformer files live: bundled assets or an external override. */
data class WakeWordModel(
    val assetManager: AssetManager?,
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
    val keywordsFile: String,
    val tokensFile: File?,
)

/**
 * sherpa-onnx zipformer KWS.
 *
 * KWS constrains decoding to the configured pinyin paths, so it costs a
 * fraction of a full ASR decode and does not hallucinate fragments from
 * ambient noise. The bundled model is the same 4.6 MB model the pilot used.
 */
class WakeWordDetector(private val model: WakeWordModel) {

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var labels: List<String> = emptyList()
        private set

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var failed = false
    private var loadedWith: List<String> = emptyList()
    private var fedFrames = 0L

    private val samples = FloatArray(WakeAudioCapture.FRAME_SAMPLES)

    /** Tokens the model actually knows, used to discard lines it cannot decode. */
    private val vocab: Set<String> by lazy {
        runCatching {
            val lines = if (model.assetManager != null) {
                model.assetManager.open(model.tokens).bufferedReader().useLines { it.toList() }
            } else {
                model.tokensFile?.bufferedReader()?.useLines { it.toList() }.orEmpty()
            }
            lines.mapNotNull { line ->
                line.substringBefore(' ').trim().takeIf { it.isNotEmpty() }
            }.toSet()
        }.getOrDefault(emptySet())
    }

    @Synchronized
    fun load(words: List<String>): Boolean {
        val wanted = words.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) {
            lastError = "唤醒词为空"
            return false
        }
        if (ready && wanted == loadedWith) return true
        if (failed) return false

        val generated = KwsKeywords.lines(wanted)
        val keywordLines = generated.filter { line ->
            line.substringBefore('@').trim()
                .split(' ')
                .filter { it.isNotEmpty() }
                .all { it in vocab }
        }
        val dropped = wanted.filter { word ->
            keywordLines.none { it.endsWith("@$word") }
        }
        if (dropped.isNotEmpty()) {
            AppLogger.info(TAG, "KWS 丢弃含未知音节的词：$dropped（词表 ${vocab.size} token）")
        }
        if (keywordLines.isEmpty()) {
            lastError = "唤醒词转拼音失败或音节不在模型词表里"
            AppLogger.info(TAG, "KWS 跳过：$lastError")
            return false
        }

        closeInternal()
        return try {
            val started = System.currentTimeMillis()
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = WakeAudioCapture.SAMPLE_RATE, featureDim = 80, dither = 0f),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = model.encoder,
                        decoder = model.decoder,
                        joiner = model.joiner,
                    ),
                    tokens = model.tokens,
                    numThreads = 1,
                    debug = false,
                    provider = "cpu",
                    modelType = "zipformer2",
                ),
                maxActivePaths = 16,
                // Must be a real (empty) file: the native parser uses stof on
                // every line, so comments are fatal and an empty string is
                // rejected. Actual words always arrive through createStream.
                keywordsFile = model.keywordsFile,
                keywordsScore = KEYWORD_SCORE,
                keywordsThreshold = KEYWORD_THRESHOLD,
                numTrailingBlanks = 1,
            )
            val next = KeywordSpotter(model.assetManager, config)
            spotter = next
            stream = next.createStream(keywordLines.joinToString("\n"))
            labels = wanted
            loadedWith = wanted
            ready = true
            failed = false
            lastError = null
            fedFrames = 0
            AppLogger.info(
                TAG,
                "KWS 就绪 words=$wanted ${System.currentTimeMillis() - started}ms " +
                    "model=${if (model.assetManager != null) "assets" else "external"}",
            )
            true
        } catch (t: Throwable) {
            failed = true
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            closeInternal()
            AppLogger.info(TAG, "KWS 初始化失败 $lastError")
            false
        }
    }

    /**
     * Feed one 16 kHz PCM16 frame. Returns the matched wake word (and resets
     * the stream for the next one), or null when nothing matched.
     */
    @Synchronized
    fun feed(frame: ShortArray, len: Int): String? {
        if (!ready) return null
        val kws = spotter ?: return null
        val input = stream ?: return null
        val n = len.coerceAtMost(frame.size).coerceAtMost(samples.size)
        if (n <= 0) return null

        return try {
            for (i in 0 until n) samples[i] = frame[i] / 32768f
            val chunk = if (n == samples.size) samples else samples.copyOf(n)
            input.acceptWaveform(chunk, WakeAudioCapture.SAMPLE_RATE)

            fedFrames++
            if (fedFrames % HEARTBEAT_FRAMES == 0L) {
                AppLogger.info(TAG, "KWS 已监听 ${fedFrames * WakeAudioCapture.FRAME_MS / 1000}s，未命中")
            }

            var hit: String? = null
            var rejected: String? = null
            while (kws.isReady(input)) {
                kws.decode(input)
                val keyword = kws.getResult(input).keyword
                if (keyword.isBlank()) continue
                val label = labels.firstOrNull { keyword.contains(it) } ?: keyword
                if (labels.none { keyword.contains(it) }) {
                    rejected = label
                    break
                }
                AppLogger.info(TAG, "KWS 命中「$label」")
                hit = label
                break
            }
            if (rejected != null) {
                AppLogger.info(TAG, "KWS 命中词表外的「$rejected」，忽略")
                kws.reset(input)
            }
            if (hit != null) kws.reset(input)
            hit
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            ready = false
            failed = true
            closeInternal()
            AppLogger.info(TAG, "KWS 识别失败 $lastError")
            null
        }
    }

    /**
     * Release the native spotter and stream.
     *
     * Synchronized for the same reason [feed] is: the capture thread can be
     * inside the native decoder while the service tears the engine down,
     * and releasing a spotter that is decoding is a native crash.
     */
    @Synchronized
    fun release() {
        failed = true
        closeInternal()
    }

    private fun closeInternal() {
        runCatching { stream?.release() }
        runCatching { spotter?.release() }
        stream = null
        spotter = null
        ready = false
    }

    companion object {
        private const val TAG = "VoiceWake"
        private const val KEYWORD_SCORE = 2.0f
        private const val KEYWORD_THRESHOLD = 0.12f
        private const val HEARTBEAT_FRAMES = 3000L // 60 s at 20 ms/frame

        private const val ASSET_DIR = "kws"
        private const val MODEL_DIR_NAME = "kws"
        private const val ENCODER = "encoder.int8.onnx"
        private const val DECODER = "decoder.onnx"
        private const val JOINER = "joiner.int8.onnx"
        private const val TOKENS = "tokens.txt"
        private const val KEYWORDS = "keywords.txt"

        private const val MIN_ENCODER_BYTES = 1L * 1024 * 1024
        private const val MIN_DECODER_BYTES = 100L * 1024
        private const val MIN_JOINER_BYTES = 20L * 1024
        private const val MIN_TOKENS_BYTES = 1024L

        /**
         * Prefer an externally deployed model so it can be replaced without a
         * rebuild (same pattern as the SenseVoice model); fall back to the
         * 4.6 MB copy bundled in assets.
         */
        fun resolveModel(context: Context): WakeWordModel? {
            val dirs = ArrayList<File>(2)
            context.getExternalFilesDir(null)?.let { dirs.add(File(it, "models/$MODEL_DIR_NAME")) }
            dirs.add(File(context.filesDir, "models/$MODEL_DIR_NAME"))
            val external = dirs.firstOrNull { dir ->
                File(dir, ENCODER).length() > MIN_ENCODER_BYTES &&
                    File(dir, DECODER).length() > MIN_DECODER_BYTES &&
                    File(dir, JOINER).length() > MIN_JOINER_BYTES &&
                    File(dir, TOKENS).length() > MIN_TOKENS_BYTES
            }
            if (external != null) {
                val keywords = File(external, KEYWORDS).apply {
                    if (!isFile) runCatching { createNewFile() }
                }
                return WakeWordModel(
                    assetManager = null,
                    encoder = File(external, ENCODER).absolutePath,
                    decoder = File(external, DECODER).absolutePath,
                    joiner = File(external, JOINER).absolutePath,
                    tokens = File(external, TOKENS).absolutePath,
                    keywordsFile = keywords.absolutePath,
                    tokensFile = File(external, TOKENS),
                )
            }
            return runCatching {
                context.assets.open("$ASSET_DIR/$TOKENS").use { }
                WakeWordModel(
                    assetManager = context.assets,
                    encoder = "$ASSET_DIR/$ENCODER",
                    decoder = "$ASSET_DIR/$DECODER",
                    joiner = "$ASSET_DIR/$JOINER",
                    tokens = "$ASSET_DIR/$TOKENS",
                    keywordsFile = "$ASSET_DIR/$KEYWORDS",
                    tokensFile = null,
                )
            }.onFailure {
                AppLogger.info(TAG, "KWS 模型资源缺失：${it.message}")
            }.getOrNull()
        }
    }
}
