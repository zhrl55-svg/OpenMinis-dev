package com.openminis.app.llm.mnn

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 助理模式的端侧快速路径。
 *
 * ## 它做什么
 * 用户说一句**高频、明确**的指令（跑命令、读文件、查记忆…）时，
 * 规则选出工具 → 端侧 0.3B 抽出参数 → 直接执行 → 出结果，**完全不调云端**。
 * 规则不命中、抽参失败、或工具有风险时返回 false，调用方照旧走云端。
 *
 * ## 它不做什么
 * - 不做推理、不做多步、不生成解释性回答 —— 那些是云端的活。
 * - 不改「Agent 模式」的任何行为：只有开了助理模式才走这条路。
 * - 不吞错误：工具执行失败会把真实错误显示给用户，不伪装成功。
 *
 * ## 为什么必须是「失败即回退」
 * 端侧模型是**可选的加速器**，不是单点。任何一步不成立（ABI 不对、模型缺失、
 * 规则没命中、抽参不全、工具执行失败）都返回 false，让云端接手。
 * 用户永远不应该因为端侧模型的存在而得到比之前更差的结果。
 */
object AssistantFastPath {

    private const val TRACE_FILE = "assistant_fastpath.log"

    /** 助理模式是否开启。默认关闭 —— 新路径不能默认改变现有行为。 */
    fun isEnabled(context: Context): Boolean = LocalAssistantPrefs.isEnabled(context)

    fun setEnabled(context: Context, on: Boolean) {
        LocalAssistantPrefs.setEnabled(context, on)
    }

    /**
     * 诊断轨迹：写到 getExternalFilesDir(null)/assistant_fastpath.log。
     *
     * 端侧接管时只往聊天里写一条助手消息，不留任何痕迹；没有这个文件时
     * 无法区分「开关没开 / 规则没命中 / 抽参失败 / 工具执行失败」。
     */
    fun trace(context: Context, line: String) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return@runCatching
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            File(dir, TRACE_FILE).appendText("[$stamp] $line\n")
        }
    }

    /**
     * 给用户看的工具调用摘要：**动作 + 关键参数**，不是用户原话的前 N 个字。
     *
     * 为什么不用原话截断：截断出来的东西读起来像半句话（「shell echo O…」、
     * 「shellEcho」），用户看不出这次到底要干什么。动作 + 参数是完整的：
     * 「运行 echo OK」「读取 /tmp/a.txt」「打开 example.com」。
     */
    fun titleFor(tool: String, args: JSONObject, userText: String): String {
        val action = when (tool) {
            "shell_execute" -> "运行"
            "file_read" -> "读取"
            "file_write" -> "写入"
            "file_edit" -> "修改"
            "browser_use" -> "打开网页"
            "memory_write" -> "记住"
            "memory_get" -> "查记忆"
            "hub_call" -> "远程调用"
            "read_image" -> "看图"
            "amap_search" -> "查周边"
            "news_search" -> "查新闻"
            "stock_quote" -> "查行情"
            else -> "执行"
        }
        // 关键参数：决定「查周边 超市」里那个「超市」。
        // query 放最后 —— 只有 amap_search 有它，不影响其他工具。
        val key = args.optString("path").ifBlank {
            args.optString("url").ifBlank {
                args.optString("command").ifBlank {
                    args.optString("content").ifBlank { args.optString("query") }
                }
            }
        }
        val brief = key.trim().replace(Regex("\\s+"), " ").take(24)
        return if (brief.isEmpty()) action else "$action $brief"
    }

    /**
     * 端侧没接住、这条走了云端时的**轻提示**。
     *
     * 只在两种「用户明显想执行工具、但端侧没搞定」的情形下提示 ——
     * 规则没命中（普通对话）不提示，那是绝大多数消息，提示了就是噪音。
     *
     * @param reason 为什么没接住
     */
    fun cloudHandoffNote(reason: String): String? = when (reason) {
        "no-args" -> "端侧模型没抽准参数，已转云端处理"
        "unsafe" -> "这条涉及高风险操作，已转云端确认"
        else -> null
    }

    /**
     * 把工具执行结果格式化成给用户看的一句话。
     *
     * 第一版不做云端总结（那会多一次往返，违背「直达」的初衷），
     * 所以这里要**自己把裸结果说人话**：成功就报做了什么 + 输出，
     * 失败就报真实错误。
     */
    fun formatResult(tool: String, summary: String, text: String, isError: Boolean): String {
        val header = if (isError) "⚠️ $summary 失败" else "✅ $summary"
        val body = text.trim()
        return when {
            body.isEmpty() -> header
            // 长了就截断 —— 端侧这条路是「快速看一眼结果」，不是「拉一份报告」。
            body.length > 1200 -> "$header\n\n${body.take(1200)}\n…（已截断）"
            else -> "$header\n\n$body"
        }
    }
}
