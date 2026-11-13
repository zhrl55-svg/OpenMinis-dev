package com.openminis.app.llm.mnn

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 端侧模型探针：跑一次本地推理，把结果交回调用方。
 *
 * 用途有两个：
 *  1. **技术验证** —— 确认 MNN 在这台机上真能加载、能生成、多快。
 *     经 `minis://action/local_llm?prompt=...` 触发（adb 可直接打）。
 *  2. **助理模式的本地入口** —— 「规则范围内的事端侧直达」最终走这里。
 *
 * ## 为什么先做探针而不是直接接主链路
 * 端侧模型是这个方案的技术前提：MNN 只发 arm64-v8a 的 .so、模型文件 171MB、
 * 首次加载约 2 秒、常驻 300MB。任一条不成立，整个「助理模式端侧直达」就得换路线
 * （比如退回 GGUF + 本地 server）。所以先把可行性钉死，再动主链路。
 */
object LocalLlmProbe {

    private const val TAG = "LocalLlmProbe"
    private const val REPORT_FILE = "local_llm_probe.txt"

    /** 探针结果。 */
    data class Result(
        val ok: Boolean,
        val output: String,
        val loadMs: Long,
        val generateMs: Long,
        val error: String? = null,
    )

    /**
     * 跑一次本地推理。
     *
     * 加载是阻塞的（2 秒量级），推理也是阻塞的，所以整体放 IO 线程。
     */
    suspend fun run(context: Context, prompt: String): Result = withContext(Dispatchers.IO) {
        val model = LocalMnnModel.get(context)
        if (!model.abiSupported()) {
            return@withContext Result(
                ok = false,
                output = "",
                loadMs = -1,
                generateMs = -1,
                error = "当前 ABI 不支持端侧模型（需要 arm64 真机）",
            )
        }
        if (!model.isAvailable()) {
            return@withContext Result(
                ok = false,
                output = "",
                loadMs = -1,
                generateMs = -1,
                error = "端侧模型文件缺失（需要 scripts/deploy_mnn_llm.sh 部署）",
            )
        }

        val t0 = System.currentTimeMillis()
        if (!model.ensureReady()) {
            return@withContext Result(
                ok = false,
                output = "",
                loadMs = -1,
                generateMs = -1,
                error = model.lastError ?: "端侧模型加载失败",
            )
        }
        val loadMs = System.currentTimeMillis() - t0

        // Qwen3 系列的 jinja 模板带思考模式，native 侧不套 jinja 会默认思考：
        // 一句自我介绍要写 150+ token、23 秒才出结果。/no_think 是官方软开关。
        val effectivePrompt = if (model.wantsNoThink) "$prompt\n/no_think" else prompt

        val t1 = System.currentTimeMillis()
        val text = model.generate(effectivePrompt)
        val generateMs = System.currentTimeMillis() - t1

        if (text == null) {
            Result(
                ok = false,
                output = "",
                loadMs = loadMs,
                generateMs = generateMs,
                error = model.lastError ?: "端侧模型未返回结果",
            )
        } else {
            Result(
                ok = true,
                output = text,
                loadMs = loadMs,
                generateMs = generateMs,
            )
        }
    }

    /** 同步版本，供不需要协程的调用点（如 deep-link 探针）使用。 */
    fun runBlocking(context: Context, prompt: String): Result {
        val model = LocalMnnModel.get(context)
        if (!model.abiSupported()) {
            return Result(false, "", -1, -1, "当前 ABI 不支持端侧模型（需要 arm64 真机）")
        }
        if (!model.isAvailable()) {
            return Result(false, "", -1, -1, "端侧模型文件缺失（需要 scripts/deploy_mnn_llm.sh 部署）")
        }
        val t0 = System.currentTimeMillis()
        if (!model.ensureReady()) {
            return Result(false, "", -1, -1, model.lastError ?: "端侧模型加载失败")
        }
        val loadMs = System.currentTimeMillis() - t0
        val effectivePrompt = if (model.wantsNoThink) "$prompt\n/no_think" else prompt
        val t1 = System.currentTimeMillis()
        val text = model.generate(effectivePrompt)
        val generateMs = System.currentTimeMillis() - t1
        return if (text == null) {
            Result(false, "", loadMs, generateMs, model.lastError ?: "端侧模型未返回结果")
        } else {
            Result(true, text, loadMs, generateMs)
        }
    }

    /**
     * 把结果写到 App 的外部目录（`getExternalFilesDir(null)/local_llm_probe.txt`）。
     *
     * 为什么不用 AppLogger：EMUI 会丢弃 logcat，而 AppLogger 落在 filesDir/logs 里
     * —— 那是 App 私有目录，非 debuggable 包连 adb shell 都读不到。
     * 外部目录 adb 直接 cat 得到，验证闭环最短。
     */
    fun writeReport(context: Context, r: Result, prompt: String) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return@runCatching
            val text = buildString {
                appendLine("=== LocalLlmProbe ${System.currentTimeMillis()} ===")
                appendLine("prompt: $prompt")
                appendLine("ok: ${r.ok}")
                appendLine("loadMs: ${r.loadMs}")
                appendLine("generateMs: ${r.generateMs}")
                if (r.error != null) appendLine("error: ${r.error}")
                appendLine("output: ${r.output}")
            }
            File(dir, REPORT_FILE).writeText(text)
        }.onFailure {
            AppLogger.error(TAG, "写探针报告失败：${it.message}")
        }
    }

    fun logResult(r: Result) {
        if (r.ok) {
            AppLogger.info(
                TAG,
                "端侧探针成功：加载 ${r.loadMs}ms，生成 ${r.generateMs}ms → 「${r.output.take(120)}」",
            )
        } else {
            AppLogger.error(TAG, "端侧探针失败：${r.error}")
        }
    }
}
