package com.openminis.app.llm.mnn

import android.content.Context
import android.os.Build
import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.alibaba.mnnllm.android.llm.LlmSession
import com.openminis.app.logging.AppLogger
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 进程内端侧小模型（MNN-LLM + Pocket-0.3B int4）。
 *
 * 与 pilot 的 `LocalLlm` 同源（同一个 `libmnnllmapp.so`，JNI 只认包名+类名+方法名），
 * 但按 Minis 的约定重写：
 *   - 日志走 [AppLogger]（不是 pilot 的 Dbg）
 *   - 模型查找顺序跟 [com.openminis.app.speech.LocalSenseVoiceEngine] 一致：
 *     先 `getExternalFilesDir(null)/models/mnn-llm-0.3b`（adb push 可达），
 *     再 `filesDir/models/mnn-llm-0.3b`。
 *     找不到 → [isAvailable] = false → 调用方回退云端，不报错。
 *   - 模型不进 APK、不入库，由 `scripts/deploy_mnn_llm.sh` 部署。
 *
 * ## 为什么进程内而不是起个本地 server
 * pilot 的注释写了同样的判断：走 MNN Chat 的 HTTP 方案有俩死穴 ——
 * 退后台端口就没了、队列卡死要重启进程。进程内推理没有这些问题。
 *
 * ## 内存
 * 加载约 2 秒、常驻约 300MB。必须后台线程初始化且只初始化一次（单例）。
 */
class LocalMnnModel private constructor(private val context: Context) {

    private val lock = ReentrantLock()
    private var session: LlmSession? = null
    private var initTried = false
    private var initFailed = false

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var lastLoadMs: Long = -1L
        private set

    @Volatile
    var lastGenerateMs: Long = -1L
        private set

    @Volatile
    var lastError: String? = null
        private set

    /**
     * 模型目录查找。
     *
     * 顺序与 LocalSenseVoiceEngine 对齐：外部目录优先（adb push 能直接写），
     * 私有目录其次（用户手动放进去的情况）。
     */
    fun modelDir(): File? {
        val external = context.getExternalFilesDir(null)?.let { File(it, "models/$MODEL_DIR_NAME") }
        if (external != null && File(external, "llm.mnn.weight").isFile) return external
        val internal = File(context.filesDir, "models/$MODEL_DIR_NAME")
        if (File(internal, "llm.mnn.weight").isFile) return internal
        return null
    }

    /** 模型文件是否齐全。不齐时上层直接回退云端，不要抛。 */
    fun isAvailable(): Boolean = modelDir() != null

    /**
     * .so 只有 arm64-v8a（官方只发这一份），x86_64 模拟器上 dlopen 直接失败 ——
     * APK 里同时有 x86_64 库时 PackageManager 会定 primaryCpuAbi=x86_64，
     * 不会走 ARM 翻译层。所以端侧模型必须在 arm64 真机上验证。
     */
    fun abiSupported(): Boolean =
        Build.SUPPORTED_ABIS.any { it.startsWith("arm64") }

    /**
     * 模型是否带思考模式，需要在 prompt 末尾加 `/no_think` 关掉。
     *
     * Qwen3 的 jinja 模板里有 enable_thinking，但 native 侧不套 jinja，于是默认思考：
     * 一句自我介绍要写 150+ token、23 秒才出结果。`/no_think` 是官方软开关，
     * 实测 23s → 0.6s。
     */
    val wantsNoThink: Boolean
        get() = runCatching {
            val dir = modelDir() ?: return@runCatching false
            File(dir, "llm_config.json").readText().contains("enable_thinking")
        }.getOrDefault(false)

    /** 同步加载模型。阻塞，必须放后台线程。 */
    fun ensureReady(): Boolean {
        if (isReady) return true
        lock.withLock {
            if (isReady) return true
            if (initTried && initFailed) return false
            initTried = true

            if (!abiSupported()) {
                initFailed = true
                lastError = "当前 ABI（${Build.SUPPORTED_ABIS.joinToString()}）不支持端侧模型，需要 arm64 真机"
                AppLogger.warning(TAG, lastError!!)
                return false
            }
            val dir = modelDir()
            if (dir == null) {
                initFailed = true
                lastError = "端侧模型文件缺失（需要 scripts/deploy_mnn_llm.sh 部署）"
                AppLogger.warning(TAG, lastError!!)
                return false
            }

            val t0 = System.currentTimeMillis()
            try {
                // ★ configPath 必须是 config.json 这个【文件】的路径，不是模型目录。
                //   native 拿它去 readText，再从路径反推模型目录。传目录会报
                //   "Module load failed for config: <dir>"。
                val cfg = File(dir, "config.json")

                // ★ max_new_tokens 必须写进 init 的合并配置。
                //   模型自带 config.json 没这个字段，native 默认只生成 1 个 token
                //   （实测 decode_len=1，输出停在「我」一个字）。
                //   单独调 updateMaxNewTokens 不生效，只有 init 时带上才行。
                val runtimeConfig = JSONObject(cfg.readText())
                    .put("max_new_tokens", MAX_NEW_TOKENS)
                    // 模型自带 repetition_penalty=1.0 等于不惩罚，小模型很容易复读用户原话。
                    // 1.15 明显缓解，再高开始胡言乱语。
                    .put("repetition_penalty", 1.15)
                    .put("presence_penalty", 0.3)
                    .toString()

                // keep_history=false：手机指令之间没有上下文依赖，每句独立更稳，
                // 也避免 KV cache 越滚越大拖慢 prefill。
                val extra = """{"is_r1":false,"mmap_dir":"","keep_history":false}"""

                val s = LlmSession()
                if (!s.init(cfg.absolutePath, runtimeConfig, extra)) {
                    initFailed = true
                    lastError = "端侧模型加载失败（initNative 返回 false）"
                    AppLogger.error(TAG, lastError!!)
                    return false
                }
                s.setMaxNewTokens(MAX_NEW_TOKENS)
                session = s
                lastLoadMs = System.currentTimeMillis() - t0
                isReady = true
                lastError = null
                AppLogger.info(TAG, "端侧模型就绪，加载 ${lastLoadMs}ms noThink=$wantsNoThink")
                return true
            } catch (t: Throwable) {
                initFailed = true
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                AppLogger.error(TAG, "端侧模型初始化失败 $lastError")
                return false
            }
        }
    }

    /**
     * 生成。返回 null = 不可用（调用方回退云端）。
     *
     * @param onDelta 流式增量回调（可选）
     */
    fun generate(prompt: String, onDelta: ((String) -> Unit)? = null): String? {
        if (!ensureReady()) return null
        val s = session ?: return null
        val t0 = System.currentTimeMillis()
        var firstTokenMs = -1L
        // ★ 文本是从 onProgress 流式回调里攒出来的，不是从返回的 map 取。
        //   map 里只有 prefill_time / decode_len / decode_time 这些指标。
        val acc = StringBuilder()
        return try {
            val result = s.generate(
                prompt,
                false,
                object : GenerateProgressListener {
                    override fun onProgress(progress: String?): Boolean {
                        if (!progress.isNullOrEmpty()) {
                            if (firstTokenMs < 0) firstTokenMs = System.currentTimeMillis() - t0
                            acc.append(progress)
                            onDelta?.invoke(progress)
                        }
                        // ★ 返回值语义是「要不要【停止】」，不是「要不要继续」。
                        //   按直觉写 true 的后果：每轮只生成 1 个 token，日志还一切正常，
                        //   极难联想到是这个。
                        return false
                    }
                },
            )
            lastGenerateMs = System.currentTimeMillis() - t0
            val fromStream = acc.toString().trim()
            val fromMap = (result["text"] as? String)?.trim().orEmpty()
            val text = fromStream.ifBlank { fromMap }
            AppLogger.info(TAG, "端侧模型生成 ${lastGenerateMs}ms（首字 ${firstTokenMs}ms）→ 「${text.take(80)}」")
            text
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            AppLogger.error(TAG, "端侧模型生成异常 $lastError")
            null
        }
    }

    fun release() {
        lock.withLock {
            runCatching { session?.release() }
            session = null
            isReady = false
            initTried = false
            initFailed = false
        }
    }

    companion object {
        private const val TAG = "LocalMnnModel"
        private const val MODEL_DIR_NAME = "mnn-llm-0.3b"

        /**
         * 单次生成上限。
         *
         * 从 256 降到 96：Pocket-0.3B 实测会很实在地把 256 token 写满，
         * 而本场景的输出是「一个 JSON 动作」或「一句 20 字内的回答」，96 绰绰有余。
         * 降下来能把胡言乱语的尾部直接砍掉，生成时间减半。
         */
        const val MAX_NEW_TOKENS = 96

        @Volatile
        private var instance: LocalMnnModel? = null

        fun get(context: Context): LocalMnnModel =
            instance ?: synchronized(this) {
                instance ?: LocalMnnModel(context.applicationContext).also { instance = it }
            }
    }
}
