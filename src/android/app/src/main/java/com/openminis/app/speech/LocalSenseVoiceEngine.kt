package com.openminis.app.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.openminis.app.logging.AppLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * 端侧 SenseVoice 识别引擎（sherpa-onnx，进程内 JNI，无网络依赖）。
 *
 * 模型不打进 APK，运行时按顺序查找 model.int8.onnx + tokens.txt：
 *   1. getExternalFilesDir(null)/models/asr-sensevoice
 *   2. filesDir/models/asr-sensevoice
 * 找不到 / 载入失败时 isAvailable=false，调用方自动回退到云端或系统引擎。
 */
class LocalSenseVoiceEngine(private val context: Context) : SpeechRecognitionEngine {

    companion object {
        const val ENGINE_ID = "local:sensevoice"
        private const val TAG = "LocalAsr"
        private const val SAMPLE_RATE = 16000
        private const val MAX_UTTERANCE_SECONDS = 60

        /**
         * 每次 read() 的时长（毫秒）。
         *
         * 这个值同时决定 [stop] 里 join 的最坏等待：读块多大，就要等多久。
         * 20 ms ≈ 一帧，松手时界面不会顿。别再改回「一次读满整个缓冲」——
         * 那样 join 要等 200~400 ms，而且它跑在主线程（抬手回调）。
         */
        private const val READ_CHUNK_MS = 20

        /** join 的上限。正常路径 ≈ READ_CHUNK_MS，超过就是出问题了 */
        private const val CAPTURE_JOIN_TIMEOUT_MS = 500L
        private const val MODEL_DIR_NAME = "asr-sensevoice"
        private const val MODEL_FILE = "model.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val MIN_MODEL_BYTES = 20L * 1024 * 1024
        private const val MIN_TOKENS_BYTES = 10L * 1024
        private val SUPPORTED_LOCALES = listOf(
            Locale.CHINESE,
            Locale.ENGLISH,
            Locale.JAPANESE,
            Locale.KOREAN,
        )

        /** 只查文件是否就位，不加载模型；供启动期迁移判断用。 */
        fun isModelReady(context: android.content.Context): Boolean {
            val dirs = ArrayList<File>(2)
            context.getExternalFilesDir(null)?.let { dirs.add(File(it, "models/$MODEL_DIR_NAME")) }
            dirs.add(File(context.filesDir, "models/$MODEL_DIR_NAME"))
            return dirs.any { dir ->
                File(dir, MODEL_FILE).length() > MIN_MODEL_BYTES &&
                    File(dir, TOKENS_FILE).length() > MIN_TOKENS_BYTES
            }
        }
    }

    override val id: String = ENGINE_ID
    override val displayName: String = "SenseVoice (端侧)"
    override val supportsPartialResults: Boolean = false
    override val supportedLocales: List<Locale> = SUPPORTED_LOCALES

    private val lock = Any()
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var degraded = false
    @Volatile
    private var recording = false
    @Volatile
    private var cancelled = false

    private var recognizer: OfflineRecognizer? = null
    private var recognizerPath: String? = null
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private var activeListener: SpeechRecognitionEngine.Listener? = null

    val modelDir: File?
        get() = resolveModelDir()

    override val isAvailable: Boolean
        get() = !degraded && resolveModelDir() != null

    private fun resolveModelDir(): File? {
        val dirs = ArrayList<File>(2)
        context.getExternalFilesDir(null)?.let { dirs.add(File(it, "models/$MODEL_DIR_NAME")) }
        dirs.add(File(context.filesDir, "models/$MODEL_DIR_NAME"))
        return dirs.firstOrNull { dir ->
            File(dir, MODEL_FILE).length() > MIN_MODEL_BYTES &&
                File(dir, TOKENS_FILE).length() > MIN_TOKENS_BYTES
        }
    }

    /** 预热：提前把模型载进内存（约 2~4s），避免第一句识别等载入。 */
    fun warmUp() {
        val dir = resolveModelDir() ?: return
        if (recognizer != null) return
        Thread {
            runCatching { ensureRecognizer(dir) }
        }.also { it.isDaemon = true }.start()
    }

    private fun ensureRecognizer(dir: File): OfflineRecognizer? {
        synchronized(lock) {
            recognizer?.let { if (recognizerPath == dir.absolutePath) return it }
            recognizer?.let { runCatching { it.release() } }
            recognizer = null
            recognizerPath = null

            val started = System.currentTimeMillis()
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(dir, MODEL_FILE).absolutePath,
                        language = "auto",
                        useInverseTextNormalization = false,
                    ),
                    tokens = File(dir, TOKENS_FILE).absolutePath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            // assetManager 必须是 null：非 null 时 sherpa 会在 native 层直接 abort，
            // 属于 catch 不到的进程级退出，不是异常。
            return try {
                OfflineRecognizer(null, config).also {
                    recognizer = it
                    recognizerPath = dir.absolutePath
                    AppLogger.info(TAG, "端侧模型载入 ${System.currentTimeMillis() - started}ms @${dir.absolutePath}")
                }
            } catch (t: Throwable) {
                degraded = true
                AppLogger.info(TAG, "端侧模型载入失败: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
        }
    }

    override fun start(locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        fail(reason = null)
        val dir = resolveModelDir()
        if (dir == null) {
            listener.onError(RecognitionError.AUDIO_ERROR, "端侧模型缺失")
            return
        }
        activeListener = listener
        cancelled = false
        synchronized(lock) { pcm.reset() }

        Thread { runCatching { ensureRecognizer(dir!!) } }.also { it.isDaemon = true }.start()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            listener.onError(RecognitionError.AUDIO_ERROR, "麦克风参数不支持")
            return
        }
        val bufferBytes = maxOf(minBuffer * 2, SAMPLE_RATE / 5 * 2)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (t: Throwable) {
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            listener.onError(RecognitionError.MIC_IN_USE, "麦克风打开失败")
            return
        }
        // ★ 必须真的 startRecording()，否则 AudioRecord.read 永远返回 0：
        //   采集线程空转、pcm 一直是空的，stop() 时走 `data.size < SAMPLE_RATE/2`
        //   那条早退，回调 onFinal("")，界面上就是「说了话但一个字都没识别到」。
        //   2026-11-08 真机排查：VoicePipeline 日志 `manager.final chars=0`
        //   且发生在 stop 后 8ms（来不及转写），就是这条路径。
        val started = try {
            record.startRecording()
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (t: Throwable) {
            AppLogger.info(TAG, "startRecording 失败: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        if (!started) {
            runCatching { record.release() }
            recorder = null
            listener.onError(RecognitionError.MIC_IN_USE, "麦克风启动失败")
            return
        }
        recorder = record
        recording = true
        listener.onReadyForSpeech()

        captureThread = Thread {
            // ★ 2026-11-08：读块与缓冲区解耦。
            //   原来 chunk = bufferBytes/2，一次 read() 要攒满 200~400 ms 才返回；
            //   而 stop() 里的 join() 跑在主线程（抬手回调链），于是松手时界面
            //   卡 200~400 ms。读块缩到 20 ms 后 join 最坏只等一块，
            //   尾音一块不丢（仍然 join 到线程自然退出，只是等得短）。
            //   缓冲区（构造参数 bufferBytes）不变，采集不会因为读得勤而出错。
            val chunk = ShortArray(minOf(bufferBytes / 2, SAMPLE_RATE * READ_CHUNK_MS / 1000))
            val maxBytes = SAMPLE_RATE * MAX_UTTERANCE_SECONDS * 2
            var written = 0
            // 音量回调按 ~200ms 一档喂给界面，和读块解耦。
            // 读块现在是 20ms，若每块都回调，波形窗口会从 8 秒缩到 0.8 秒，
            // 而且每秒 50 次状态更新纯属浪费 —— 观感和原来保持一致。
            val levelEvery = maxOf(1, 200 / READ_CHUNK_MS)
            var levelSum = 0.0
            var levelCount = 0
            try {
                while (recording && !Thread.currentThread().isInterrupted) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read <= 0) continue
                    var sum = 0.0
                    val bytes = ByteArray(read * 2)
                    for (i in 0 until read) {
                        val s = chunk[i]
                        sum += s.toDouble() * s
                        bytes[i * 2] = (s.toInt() and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
                    }
                    levelSum += sqrt(sum / read)
                    levelCount++
                    if (levelCount >= levelEvery) {
                        activeListener?.onRmsDb((levelSum / levelCount).toFloat())
                        levelSum = 0.0
                        levelCount = 0
                    }
                    synchronized(lock) {
                        if (written < maxBytes) {
                            pcm.write(bytes, 0, minOf(bytes.size, maxBytes - written))
                            written += bytes.size
                        }
                    }
                    if (written >= maxBytes) break
                }
            } catch (t: Throwable) {
                AppLogger.info(TAG, "端侧采集中断: ${t.javaClass.simpleName}")
            }
        }.also { it.isDaemon = true }
        captureThread?.start()
    }

    override fun stop() {
        if (!recording) {
            if (cancelled) cancelled = false
            return
        }
        recording = false
        // 不再用 runCatching 把 join 包起来：它会把「超时」一起吞掉，
        // 而超时意味着最后一块音频没交出来 —— 尾音缺一截却没有任何痕迹。
        val thread = captureThread
        captureThread = null
        if (thread != null) {
            val waitStart = System.currentTimeMillis()
            try {
                thread.join(CAPTURE_JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val waited = System.currentTimeMillis() - waitStart
            when {
                thread.isAlive ->
                    AppLogger.info(TAG, "采集线程 ${waited}ms 仍未退出，尾音可能缺一块")
                // 正常应该 ~20ms；超过 50ms 说明读块或调度不对，留个痕迹
                waited > 50 ->
                    AppLogger.info(TAG, "采集线程退出耗时 ${waited}ms（预期 ≤${READ_CHUNK_MS}ms）")
            }
        }
        recorder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        recorder = null

        val listener = activeListener
        val dir = resolveModelDir()
        val data = synchronized(lock) { pcm.toByteArray().also { pcm.reset() } }
        if (cancelled) {
            cancelled = false
            activeListener = null
            return
        }
        if (listener == null || dir == null) {
            listener?.onError(RecognitionError.TRANSCRIPTION_FAILED, "端侧模型缺失")
            activeListener = null
            return
        }
        if (data.size < SAMPLE_RATE / 2) {
            listener.onFinal("")
            activeListener = null
            return
        }
        Thread {
            transcribe(data, dir, listener)
            activeListener = null
        }.also { it.isDaemon = true }.start()
    }

    override fun cancel() {
        cancelled = true
        recording = false
        captureThread?.let { runCatching { it.join(300) } }
        captureThread = null
        recorder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        recorder = null
        synchronized(lock) { pcm.reset() }
        activeListener = null
    }

    override fun markDegraded() {
        degraded = true
    }

    override fun clearDegraded() {
        degraded = false
    }

    private fun transcribe(bytes: ByteArray, dir: File, listener: SpeechRecognitionEngine.Listener) {
        val samples = FloatArray(bytes.size / 2)
        for (i in samples.indices) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            val v = (hi shl 8) or lo
            samples[i] = v / 32768.0f
        }
        val engine = ensureRecognizer(dir)
        if (engine == null) {
            listener.onError(RecognitionError.TRANSCRIPTION_FAILED, "端侧模型载入失败")
            return
        }
        val started = System.currentTimeMillis()
        try {
            val stream = engine.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            engine.decode(stream)
            val text = engine.getResult(stream).text.trim()
            runCatching { stream.release() }
            AppLogger.info(
                TAG,
                "端侧识别 ${System.currentTimeMillis() - started}ms, ${(bytes.size / 2)} samples, ${text.length} 字",
            )
            listener.onFinal(text)
        } catch (t: Throwable) {
            AppLogger.info(TAG, "端侧识别失败: ${t.javaClass.simpleName}: ${t.message}")
            listener.onError(RecognitionError.TRANSCRIPTION_FAILED, t.message ?: "端侧识别失败")
        }
    }

    private fun fail(@Suppress("UNUSED_PARAMETER") reason: String?) {
        if (recording) cancel()
    }
}
