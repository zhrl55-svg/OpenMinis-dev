package com.openminis.app.llm.mnn

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import org.json.JSONObject

/**
 * 端侧参数抽取的验收探针。
 *
 * 验收标准（不是演示）：
 *   1. 规则选工具：**必须全对**（8/8），否则端侧白搭。
 *   2. 规则不命中：**必须全部转云端**（0 误触发）。
 *   3. 端侧抽参：抽出的值里必须包含期望的关键词（不是要求逐字相等，
 *      因为模型会加引号、换行、中文标点）。
 */
object LocalIntentProbe {

    private const val TAG = "LocalIntentProbe"
    private const val REPORT_FILE = "local_intent_probe.txt"

    /**
     * 一条用例。
     *
     * @param expectTool 期望规则选中的工具；null = 期望不命中（转云端）
     * @param expectArgIn 期望端侧抽出的参数里包含的关键词（任一命中即可）
     */
    private data class Case(
        val text: String,
        val expectTool: String?,
        val expectArgIn: List<String> = emptyList(),
    )

    private val CASES = listOf(
        // —— 规则选 shell_execute，端侧抽 command
        Case("运行命令 ls -la", "shell_execute", listOf("ls -la")),
        Case("执行一下 echo hello world", "shell_execute", listOf("echo", "hello")),
        Case("在终端里跑个 python 脚本 print.py", "shell_execute", listOf("python print.py")),
        // —— file_read 抽 path
        Case("读一下 /var/minis/memory/GLOBAL.md", "file_read", listOf("GLOBAL.md")),
        Case("读取文件 /tmp/data.json", "file_read", listOf("data.json")),
        // —— file_write 抽 path + content
        Case("把内容 hello 写入 /tmp/a.txt", "file_write", listOf("a.txt")),
        // —— file_edit 抽 path + old_string/new_string（真实 schema 三者都必填）
        Case("修改 /tmp/conf.ini 里的 port=80 改成 port=8080", "file_edit", listOf("conf.ini", "port=80")),
        // —— browser_use 抽 url
        Case("打开网页 https://example.com/x", "browser_use", listOf("example.com")),
        // —— memory_write 抽 content
        Case("记住我更喜欢简短回答", "memory_write", listOf("简短")),
        // —— 规则不该命中 → 转云端
        Case("今天天气怎么样", null),
        Case("张掖西站怎么走", null),
        Case("你是谁", null),
        Case("写一首关于秋天的诗", null),
    )

    /** 工具定义：只用到 name 和参数的键，描述不影响抽取。 */
    private fun toolDefs(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = "shell_execute",
            description = "在沙箱里执行命令",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "摘要"),
                "command" to AgentToolParam("string", "命令"),
                "timeout" to AgentToolParam("integer", "超时"),
            ),
            required = listOf("tool_title", "command"),
        ),
        AgentToolDefinition(
            name = "file_read",
            description = "读取文件",
            parameters = mapOf("tool_title" to AgentToolParam("string", "摘要"),
                "path" to AgentToolParam("string", "路径")),
            required = listOf("tool_title", "path"),
        ),
        AgentToolDefinition(
            name = "file_write",
            description = "写文件",
            parameters = mapOf("tool_title" to AgentToolParam("string", "摘要"),
                "path" to AgentToolParam("string", "路径"),
                "content" to AgentToolParam("string", "内容")),
            required = listOf("tool_title", "path", "content"),
        ),
        AgentToolDefinition(
            name = "file_edit",
            description = "编辑文件",
            parameters = mapOf("tool_title" to AgentToolParam("string", "摘要"),
                "path" to AgentToolParam("string", "路径"),
                "old_string" to AgentToolParam("string", "原文"),
                "new_string" to AgentToolParam("string", "新文")),
            required = listOf("tool_title", "path", "old_string", "new_string"),
        ),
        AgentToolDefinition(
            name = "browser_use",
            description = "浏览器操作",
            parameters = mapOf("tool_title" to AgentToolParam("string", "摘要"),
                "action" to AgentToolParam("string", "动作"),
                "url" to AgentToolParam("string", "网址")),
            required = listOf("tool_title", "action"),
        ),
        AgentToolDefinition(
            name = "memory_write",
            description = "写长期记忆",
            parameters = mapOf("tool_title" to AgentToolParam("string", "摘要"),
                "content" to AgentToolParam("string", "内容")),
            required = listOf("tool_title", "content"),
        ),
    )

    fun run(context: Context): String {
        val model = LocalMnnModel.get(context)
        val planner = LocalIntentPlanner()
        val defs = toolDefs()
        val sb = StringBuilder()
        sb.appendLine("=== LocalIntentProbe ===")
        if (!model.abiSupported()) {
            sb.appendLine("SKIP: 当前 ABI 不支持端侧模型")
            writeReport(context, sb.toString()); return sb.toString()
        }
        if (!model.isAvailable()) {
            sb.appendLine("SKIP: 端侧模型文件缺失")
            writeReport(context, sb.toString()); return sb.toString()
        }
        if (!model.ensureReady()) {
            sb.appendLine("FAIL: 端侧模型加载失败 —— ${model.lastError}")
            writeReport(context, sb.toString()); return sb.toString()
        }

        var ruleOk = 0        // 规则选对
        var ruleMiss = 0      // 规则选错
        var argOk = 0         // 参数抽对
        var argMiss = 0       // 参数没抽对（含转云端）
        var pass = 0          // 正确不命中
        var falsePositive = 0 // 本该转云端却选了工具
        var totalMs = 0L
        var cloudHandoff = 0  // 端侧失败→转云端的次数

        for (c in CASES) {
            val t0 = System.currentTimeMillis()
            val ruleTool = planner.resolveTool(c.text, defs)
            val expectName = c.expectTool

            // 规则判定
            val ruleRight = (expectName == null && ruleTool == null) ||
                (expectName != null && ruleTool?.name == expectName)
            if (expectName == null && ruleTool != null) {
                falsePositive++
            } else if (expectName != null && ruleTool?.name != expectName) {
                ruleMiss++
            } else {
                ruleOk++
            }

            // 端侧抽参（规则不命中就不抽）
            var ex: LocalIntentPlanner.Extraction? = null
            if (ruleTool != null) {
                ex = planner.extractArgs(c.text, ruleTool, model)
            }
            val ms = System.currentTimeMillis() - t0
            totalMs += ms

            val argRight: Boolean
            if (expectName == null) {
                argRight = ruleTool == null
                if (argRight) pass++
            } else if (ex == null) {
                argRight = false
                argMiss++
                cloudHandoff++
            } else {
                // 只比较模型抽出的参数值，不含规则生成的 tool_title ——
                // 否则「command 抽错了但摘要里正好有这个词」会蒙混过关。
                val blob = buildString {
                    val keys = ex.args.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k == "tool_title") continue
                        append(ex.args.optString(k)).append(' ')
                    }
                }
                argRight = c.expectArgIn.isEmpty() || c.expectArgIn.any { blob.contains(it) }
                if (argRight) argOk++ else { argMiss++; cloudHandoff++ }
            }

            val mark = if (ruleRight && argRight) "✓" else "✗"
            sb.appendLine("[$mark] 规则=${ruleTool?.name ?: "无"} 期望=$expectName  参数${if (argRight) "对" else "错"}")
            sb.appendLine("    输入：${c.text}")
            sb.appendLine("    耗时：${ms}ms")
            ex?.let { sb.appendLine("    抽出：${it.args}") }
            if (ex != null && !argRight) sb.appendLine("    期望包含：${c.expectArgIn}")
            if (ex == null && ruleTool != null && planner.lastRaw.isNotBlank()) {
                sb.appendLine("    原始输出：${planner.lastRaw.take(160)}")
            }
        }

        val total = CASES.size
        val expectHit = CASES.count { it.expectTool != null }
        sb.appendLine("---- 汇总 ----")
        sb.appendLine("用例 $total")
        sb.appendLine("规则选对 $ruleOk / 选错 $ruleMiss / 本该转云端却选了 $falsePositive")
        sb.appendLine("端侧抽参对 $argOk / 错 $argMiss（错的会转云端，共 $cloudHandoff 次）")
        sb.appendLine("端侧可用率：${argOk}/$expectHit")
        sb.appendLine("总耗时 ${totalMs}ms，平均 ${totalMs / total}ms")

        val report = sb.toString()
        writeReport(context, report)
        AppLogger.info(TAG, report.replace('\n', ' ').take(600))
        return report
    }

    fun writeReport(context: Context, text: String) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return@runCatching
            java.io.File(dir, REPORT_FILE).writeText(text)
        }.onFailure { AppLogger.error(TAG, "写报告失败：${it.message}") }
    }

    fun runBlocking(context: Context): String = run(context)
}
