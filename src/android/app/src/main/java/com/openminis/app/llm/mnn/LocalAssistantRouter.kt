package com.openminis.app.llm.mnn

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.logging.AppLogger
import org.json.JSONObject

/**
 * 助理模式：把一句用户的话变成一次**本地工具调用**。
 *
 * 分工（见 [LocalIntentPlanner] 的实测结论）：
 *   规则选工具 → 端侧抽参数 → 安全校验 → 执行；任何一步不过就转云端。
 *
 * ## 为什么本地执行要有安全校验
 *
 * 云端路径里，命令是模型看着整段对话决定的；端侧路径里，命令是
 * 「用户刚说的那句话」+ 规则拼出来的。所以端侧多了一条铁律：
 *
 * > **端侧执行的命令 / 写入的内容，必须逐词来自用户自己的原话。**
 *
 * 这条由 [LocalIntentPlanner] 的逐词校验（`isVerbatim`）兜底 ——
 * 模型把 `ls -la` 截成 `ls`、把占位符抄成 `示例值`，都会在这一层被丢掉。
 * 这里再加一道**破坏性命令**的拒绝：用户自己说了 `rm -rf` 也转云端，
 * 让云端那条链路（模型 + 工具卡 + 用户的停止按钮）去处理。
 */
object LocalAssistantRouter {

    private const val TAG = "LocalAssistantRouter"

    /** 一次本地工具调用。 */
    data class Plan(
        val toolName: String,
        val args: JSONObject,
    )

    /**
     * 破坏性命令 —— 即使用户原话里有，也转云端。
     *
     * 宁可多走一次云端往返，也不要让端侧把 `rm -rf` 当成一条普通指令执行。
     */
    private val DESTRUCTIVE = Regex(
        """(^|[\s;&|(])(rm|mv|cp|dd|mkfs|mkswap|chmod|chown|kill|killall|pkill|""" +
            """shutdown|reboot|halt|poweroff|insmod|rmmod|truncate|shred|wipe|""" +
            """sudo|su|doas|passwd|iptables|ufw|systemctl|service|apt|apk|yum|dnf|pip|npm)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** 重定向：写文件，不该走 shell。 */
    private val REDIRECT = Regex("""[<>]""")

    /** 管道到解释器：下载即执行。 */
    private val PIPE_TO_SHELL = Regex("""\|\s*(ba|z|k|da)?sh\b""", RegexOption.IGNORE_CASE)

    /**
     * 这句话是**人说的**，还是系统自己塞进来的信封？
     *
     * 定时任务的 `<scheduled_task …>`、子 agent 回调的 `<agent_callback …>`、
     * 系统提醒的 `<system-reminder>` 都不是人在讲话 —— 端侧不该接管它们。
     * CLI / RPC 发来的一句普通指令是用户的话，照样该走端侧。
     */
    fun isPlainInstruction(text: String): Boolean {
        val lower = text.lowercase()
        return !lower.contains("<scheduled_task") &&
            !lower.contains("<agent_callback") &&
            !lower.contains("<system-reminder")
    }

    /**
     * 试着把这句话变成一次本地工具调用。
     *
     * 阻塞（内部跑推理），必须在后台线程调用。
     *
     * @return null = 端侧不接管（未命中规则 / 抽不出参数 / 没过安全校验），
     *   调用方照旧走云端。
     */
    fun plan(
        context: Context,
        userText: String,
        tools: List<AgentToolDefinition>,
    ): Plan? {
        val text = userText.trim()
        if (text.isEmpty()) return null

        val model = LocalMnnModel.get(context)
        // 端侧模型不在 / ABI 不支持 —— 不是错误，直接走云端。
        if (!model.abiSupported() || !model.isAvailable()) return null

        val planner = LocalIntentPlanner()
        val def = planner.resolveTool(context, text, tools) ?: return null
        val extraction = planner.extractArgs(text, def, model) ?: return null

        val args = extraction.args
        if (!isSafe(def.name, args, text)) {
            AppLogger.info(TAG, "本地执行被安全校验拦下：${def.name} ${args} ← 「${text.take(30)}」")
            return null
        }
        AppLogger.info(TAG, "本地直连：${def.name} ${args}")
        return Plan(def.name, args)
    }

    /**
     * 安全校验。
     *
     * 逐工具看：**这个工具被这样调用，会造成什么后果**。
     */
    fun isSafe(toolName: String, args: JSONObject, userText: String): Boolean =
        when (toolName) {
            "shell_execute" -> safeShell(args.optString("command", ""))
            "file_write" -> safeWrite(args, userText)
            "file_edit" -> safeWrite(args, userText)
            "memory_write" -> safeWrite(args, userText)
            "browser_use" -> args.optString("action", "").equals("navigate", ignoreCase = true)
            // amap_search 只读（查地点），query 已过子序列校验。
            "amap_search" -> true
            // file_read / memory_get 是只读的，路径和参数都已经过逐词校验。
            else -> true
        }

    /**
     * 命令：逐词来自用户原话（上层已保证）+ 不碰危险操作。
     */
    private fun safeShell(command: String): Boolean {
        val cmd = command.trim()
        if (cmd.isEmpty()) return false
        if (REDIRECT.containsMatchIn(cmd)) return false
        if (PIPE_TO_SHELL.containsMatchIn(cmd)) return false
        if (DESTRUCTIVE.containsMatchIn(cmd)) return false
        return true
    }

    /**
     * 写入：内容必须是从用户原话里「摘出来」的，不是模型自己编的。
     *
     * 用**子序列**而不是子串：「记得我更喜欢简短回答」不是
     * 「记住我更喜欢简短回答」的子串，但去掉空格标点后是它的子序列 ——
     * 模型的措辞可以变，字不能凭空多出来。
     */
    private fun safeWrite(args: JSONObject, userText: String): Boolean {
        val content = args.optString("content", "")
        if (content.isBlank()) return false
        if (!derivedFrom(content, userText)) return false
        // file_edit 用的是 old_string / new_string。
        val old = args.optString("old_string", "")
        val new = args.optString("new_string", "")
        if (old.isNotEmpty() && !derivedFrom(old, userText)) return false
        if (new.isNotEmpty() && !derivedFrom(new, userText)) return false
        return true
    }

    /** [content] 的字符（去掉空白标点后）是否是 [userText] 的子序列。 */
    private fun derivedFrom(content: String, userText: String): Boolean =
        LocalIntentPlanner.isDerivedFrom(content, userText)
}
