package com.openminis.app.llm.mnn

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.logging.AppLogger
import org.json.JSONObject

/**
 * 端侧参数抽取器。
 *
 * ## 分工：规则选工具，端侧抽参数
 *
 * 2026-11-13 实测（`LocalIntentProbe`，11 条用例）：
 * - 规则圈候选：**8/8 全对**，3/3 正确地「不命中 → 转云端」，0 误触发。
 * - 端侧自己选工具：**1/8**。它会照抄 few-shot 的工具名，或把用户话里的词
 *   当工具名编出来（`echo` / `python\_run` / `file_modifier` / `web_opening`）。
 * - 但端侧**抽值很准**：`"path":"/var/minis/memory/GLOBAL.md"` 抽对了，
 *   `"tool_title":"执行Echohello"` 也把命令抓出来了，只是放错了字段。
 *
 * 结论：**0.3B 不会「从列表里选」，但会「从一句话里找值」。**
 * 所以工具名由规则决定，端侧只负责把参数值抽出来 —— 任务简单一个量级，
 * 0.3B 完全够用。
 *
 * ## prompt 为什么这么短
 * pilot 的实测（麒麟 990，0.6B）：**系统提示词每多 100 字就多 0.6 秒首字延迟**，
 * 700 字 → 首字 3.5 秒，260 字 → 1.4 秒。端侧小模型的瓶颈是 prompt 长度，
 * 不是模型大小。所以这里只给：
 *   - 一句指令
 *   - 要抽的参数名（一行一个）
 *   - 一个用当前参数名写的示例
 *   - 用户原话
 * 全长 ~120 字。
 */
class LocalIntentPlanner {

    /** 抽取结果。 */
    data class Extraction(
        val tool: String,
        val args: JSONObject,
        /** 模型原始输出（调试用）。 */
        var raw: String = "",
    )

    /** 最近一次抽取的模型原始输出（调试/验收用）。 */
    var lastRaw: String = ""
        private set

    /**
     * 中文关键词 → 工具名。
     *
     * 只放**信号强**的词：命中即说明用户明确要动那个工具。
     * 弱信号（「查」「看」）不进来 —— 它们出现得太泛，命中了反而把候选集撑大。
     *
     * 顺序即优先级：一条指令只对应一个工具，靠前的先匹配。
     */
    private val ruleHints: List<Pair<List<String>, String>> = listOf(
        listOf("运行", "执行", "跑一下", "跑个", "命令", "终端", "shell", "脚本") to "shell_execute",
        listOf("读一下", "读取", "打开文件", "看看文件", "文件内容", "读文件") to "file_read",
        listOf("写入", "写文件", "保存到", "存到", "新建文件", "写一个") to "file_write",
        listOf("修改", "编辑文件", "改一下", "改成", "替换掉", "编辑") to "file_edit",
        listOf("网页", "浏览器", "打开网址", "上网查", "搜网页") to "browser_use",
        listOf("记住", "记一下", "存进记忆", "别忘了") to "memory_write",
        listOf("我说过", "我的记忆", "之前说过", "查记忆") to "memory_get",
        listOf("另一台电脑", "远程电脑", "那台机器", "远程执行") to "hub_call",
        listOf("这张图", "看图", "图片内容", "识别图") to "read_image",
        // 地点查询：周边/附近/找个 这类问法。放在最后 —— 前面几条的关键词更具体，
        // 命中它们时不应该被这里抢走。
        listOf(
            "附近", "周边", "周围", "旁边", "最近的", "找一家", "找个", "有哪些",
            "在哪有", "有没有", "地址是", "怎么去",
        ) to "amap_search",
    )

    /**
     * few-shot 示例。
     *
     * ★ 不能用「示例值」这类占位符：0.3B 会把占位符原样抄进输出
     * （实测 9 条用例里 7 条输出 `"path":"示例值"`）。示例必须是**另一句
     * 用户话 + 从这句话里抽出的真实值**，模型才会学着「从原话里找值」。
     *
     * 每个工具用 2 条：一条示范最简形式，一条示范带修饰词的形式。
     */
    private data class Demo(val user: String, val output: String)

    private val demos: Map<String, List<Demo>> = mapOf(
        "shell_execute" to listOf(
            Demo("执行 ls /tmp", """{"command":"ls /tmp"}"""),
            Demo("运行命令 df -h", """{"command":"df -h"}"""),
            Demo("运行命令 cat /etc/hosts", """{"command":"cat /etc/hosts"}"""),
        ),
        "file_read" to listOf(
            Demo("读一下 /tmp/notes.md", """{"path":"/tmp/notes.md"}"""),
            Demo("读取文件 /var/minis/memory/GLOBAL.md", """{"path":"/var/minis/memory/GLOBAL.md"}"""),
        ),
        "file_write" to listOf(
            Demo("把 hello 写入 /tmp/a.txt", """{"path":"/tmp/a.txt","content":"hello"}"""),
            Demo("写文件 /tmp/x.txt 内容为 你好", """{"path":"/tmp/x.txt","content":"你好"}"""),
        ),
        "file_edit" to listOf(
            Demo(
                "修改 /tmp/conf.ini 里的 port=80 为 port=8080",
                """{"path":"/tmp/conf.ini","old_string":"port=80","new_string":"port=8080"}""",
            ),
            Demo(
                "把 /etc/hosts 里的 127.0.0.1 改成 0.0.0.0",
                """{"path":"/etc/hosts","old_string":"127.0.0.1","new_string":"0.0.0.0"}""",
            ),
        ),
        "browser_use" to listOf(
            Demo(
                "打开网页 https://www.qq.com/news",
                """{"action":"navigate","url":"https://www.qq.com/news"}""",
            ),
            Demo(
                "在浏览器打开 www.baidu.com",
                """{"action":"navigate","url":"www.baidu.com"}""",
            ),
        ),
        "memory_write" to listOf(
            Demo("记住我喜欢喝咖啡", """{"content":"我喜欢喝咖啡"}"""),
            Demo("记一下 我的项目在 /home/me/app", """{"content":"我的项目在 /home/me/app"}"""),
        ),
        "amap_search" to listOf(
            Demo("附近有超市吗", """{"query":"超市"}"""),
            Demo("找一家附近的咖啡店", """{"query":"咖啡店"}"""),
        ),
    )

    /**
     * 「逐字复制型」的槽位：值必须来自用户原话，不能是模型自己造的。
     *
     * 实测 0.3B 在这几类上会吐垃圾（`command:"l"`、`path:"/tmp(datajson)}"`、
     * 占位符 `"示例值"`、照抄示例里的值）。所以对这几类做**逐词校验**：
     * 值的每个词都必须在用户原话里出现过，否则这个值作废 → 走规则兜底
     * 或转云端。
     *
     * 不在名单里的槽位（action / content / keywords / scope …）是「要理解语义」
     * 的，模型写不出来就转云端，不做词面校验。
     */
    private val verbatimSlots = setOf("path", "url", "command", "old_string", "new_string")

    /**
     * 「子序列」型槽位：值必须是用户原话的一部分，措辞可以变、字不能凭空多。
     *
     * 用**子序列**而不是子串：「附近的超市」不是「附近有超市吗」的子串，
     * 但去掉空白标点后是它的子序列 —— 模型把「超市」从「附近有超市吗」里
     * 摘出来是合法���，凭空造一个「家乐福」就不是。
     */
    private val subsequenceSlots = setOf("query")

    /**
     * 每条规则真正要抽的槽位。
     *
     * 默认取 `required`（去掉 tool_title），但有些参数在 schema 里是可选的、
     * 对这个意图却必不可少 —— 比如 browser_use 的 `url`：只抽到 action=navigate
     * 而没 URL，执行必然失败。这类工具在这里补上。
     */
    private val extraSlots: Map<String, List<String>> = mapOf(
        "browser_use" to listOf("url"),
    )

    private fun slotsOf(def: AgentToolDefinition): List<String> {
        val required = def.required.filter { it != "tool_title" }
        val extra = extraSlots[def.name].orEmpty()
            .filter { it in def.parameters && it !in required }
        return required + extra
    }

    /**
     * 规则选工具。
     *
     * @return 命中的工具；null = 规则不命中 → 直接转云端
     */
    fun resolveTool(userText: String, tools: List<AgentToolDefinition>): AgentToolDefinition? {
        val byName = tools.associateBy { it.name }
        for ((keys, toolName) in ruleHints) {
            if (keys.any { userText.contains(it) }) {
                byName[toolName]?.let { return it }
            }
        }
        return null
    }

    /**
     * 抽取参数。返回 null = 端侧没抽出可用的参数 → 调用方转云端。
     *
     * 必须在后台线程调用（内部会阻塞跑推理）。
     */
    fun extractArgs(
        userText: String,
        def: AgentToolDefinition,
        model: LocalMnnModel,
    ): Extraction? {
        lastRaw = ""
        // tool_title 由规则从用户话截，不让模型生成 —— 它只是给用户看的摘要，
        // 而模型最容易在这里抄示例把参数抽歪。
        val slots = slotsOf(def)
        if (slots.isEmpty()) {
            // 这个工具没有必填的业务参数（只有摘要）→ 规则已经够用，不必问端侧。
            val args = JSONObject()
            args.put("tool_title", AssistantFastPath.titleFor(def.name, args, userText))
            return Extraction(def.name, args)
        }

        val prompt = buildPrompt(userText, slots, def)
        var raw = model.generate(prompt) ?: return null
        lastRaw = raw
        // 0.3B 偶尔会把 JSON 吐坏（实测 `{"path": "/tmp(data.json)}`）。
        // 同一 prompt 重试一次通常就好了 —— 只在解析失败时多花一次延迟。
        var parsed = parseArgs(raw, slots, userText)
        if (parsed == null) {
            raw = model.generate(prompt) ?: return null
            lastRaw = raw
            parsed = parseArgs(raw, slots, userText)
        }
        parsed = parsed ?: run {
            // 模型没救回来 —— 路径/网址这类「原文里就有」的槽位用规则兜底。
            fillByRule(userText, slots)
        } ?: return null

        val args = JSONObject()
        val parsedKeys = parsed.keys()
        while (parsedKeys.hasNext()) {
            val k = parsedKeys.next()
            args.put(k, parsed.optString(k))
        }
        // 模型漏掉的路径/网址同样用规则补上（模型给了但被防抄检查丢掉的值也算漏）。
        fillByRule(userText, slots, args)
        // 摘要最后算：它要用到上面填好的 path/url/command，
        // 所以是「动作 + 关键参数」而不是用户原话的前 N 个字。
        args.put("tool_title", AssistantFastPath.titleFor(def.name, args, userText))

        // 必填参数（含当前意图下必不可少的可选参数）必须齐全 ——
        // 缺了就转云端，不要拿半截参数去执行。
        val essential = def.required + extraSlots[def.name].orEmpty()
        val missing = essential.filter { key ->
            !args.has(key) || args.optString(key).isBlank()
        }
        if (missing.isNotEmpty()) {
            AppLogger.info(TAG, "端侧抽参丢弃：缺 $missing（raw=「${raw.take(80)}」）")
            return null
        }
        AppLogger.info(TAG, "端侧抽参：${def.name} ${args} ← 「${userText.take(30)}」")
        return Extraction(def.name, args, raw)
    }

    /**
     * prompt 构造。极简是硬要求，见类注释的性能说明。
     */
    fun buildPrompt(userText: String, slots: List<String>, def: AgentToolDefinition): String {
        val sb = StringBuilder()
        sb.append("从用户的话里提取参数值，只输出一个 JSON 对象，不要解释。\n")
        sb.append("值必须原样抄自用户的话，不要编造。\n")
        sb.append("参数：").append(slots.joinToString(",")).append("\n")
        sb.append("示例：\n")
        val list = demos[def.name].orEmpty()
        if (list.isEmpty()) {
            // 没有该工具的示例时不要硬造占位符 —— 宁可少一个 shot。
            sb.append("用户：").append(userText.take(60)).append("\n")
            sb.append("输出：")
            return sb.toString()
        }
        for (d in list) {
            sb.append("用户：").append(d.user).append("\n")
            sb.append("输出：").append(d.output).append("\n")
        }
        sb.append("用户：").append(userText.take(60)).append("\n")
        sb.append("输出：")
        return sb.toString()
    }

    /**
     * 解析模型输出。容错：模型可能把 JSON 包在 ```json 里，或前后有废话。
     *
     * [userText] 非空时会丢弃「用户话里没有、但示例里有」的值 ——
     * 这是照抄示例的确定性证据。
     */
    fun parseArgs(raw: String, slots: List<String>, userText: String = ""): JSONObject? {
        val text = raw.trim()
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = text.lastIndexOf('}')
        if (end <= start) return null
        val obj = try {
            JSONObject(text.substring(start, end + 1))
        } catch (_: Exception) {
            return null
        }
        // 只接受我们问过的参数名 —— 模型自己加的键一律丢掉。
        // （不能用 `for ((k, v) in obj)`：JSONObject 实现了 Map，
        //   Kotlin 的解构在它上面会报 component1/component2 歧义。）
        val out = JSONObject()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!slots.contains(k)) continue
            val v = obj.optString(k)
            if (userText.isNotBlank()) {
                if (k in verbatimSlots && !isVerbatim(v, userText)) {
                    AppLogger.info(TAG, "丢弃不在用户原话里的参数 $k=$v")
                    continue
                }
                if (k in subsequenceSlots && !isDerivedFrom(v, userText)) {
                    AppLogger.info(TAG, "丢弃不是用户原话子序列的参数 $k=$v")
                    continue
                }
            }
            out.put(k, v)
        }
        return if (out.length() == 0) null else out
    }

    /**
     * 逐词校验：值的每个词都得在用户原话里出现过。
     *
     * 用「词」而不是「子串」：`l` 是 `ls -la` 的子串，但显然不是用户要的命令。
     */
    private fun isVerbatim(value: String, userText: String): Boolean {
        val words = value.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        val spoken = tokenize(userText)
        return words.all { w -> spoken.any { it.equals(w, ignoreCase = true) } }
    }

    private fun tokenize(text: String): List<String> =
        text.split(TOKEN_SPLIT).map { it.trim() }.filter { it.isNotBlank() }

    /**
     * 规则兜底：路径 / 网址这类值原文里就有，模型只是偶尔把它吐坏。
     *
     * 只补这两个槽位 —— 它们是纯「复制粘贴」型的；content / old_string
     * 那种要理解语义的值仍然只信模型，缺了就转云端。
     */
    private fun fillByRule(
        userText: String,
        slots: List<String>,
        into: JSONObject? = null,
    ): JSONObject? {
        val out = into ?: JSONObject()
        if (slots.contains("path") && out.optString("path").isBlank()) {
            PATH_REGEX.find(userText)?.let { out.put("path", it.value) }
        }
        if (slots.contains("url") && out.optString("url").isBlank()) {
            URL_REGEX.find(userText)?.let { out.put("url", it.value) }
        }
        if (slots.contains("command")) {
            // 命令是「触发词后面的原文」，规则比模型可靠：
            // 模型实测会把 "ls -la" 截成 "ls"。规则结果不合法（带中文、
            // 抽不出命令）时才用模型的值。
            ruleCommand(userText)?.let { out.put("command", it) }
        }
        return if (out.length() == 0) null else out
    }

    /**
     * 命令的规则兜底：剥掉「运行命令」「执行一下」「在终端里跑个」这类前缀，
     * 再去掉「脚本 / 命令 / 指令」这类填充词，剩下的必须是一串纯 ASCII。
     *
     * 只 whitelist 地删填充词、其余中文一律保留并判为不合格 ——
     * 「rm -rf / 千万别」这种如果把中文统统删掉，就会变成一条真会执行的
     * 危险命令，宁可转云端。
     */
    private fun ruleCommand(userText: String): String? {
        val stripped = userText.trim().replace(COMMAND_PREFIX_REGEX, "").trim()
        if (stripped.isEmpty()) return null
        val tokens = stripped.split(TOKEN_SPLIT)
            .map { it.trim() }
            .filter { it.isNotBlank() && it !in COMMAND_FILLER_WORDS }
        if (tokens.isEmpty()) return null
        val command = tokens.joinToString(" ")
        if (command.any { it.code > 127 }) return null
        return command
    }

    /** 给用户看的工具调用摘要：从用户话里截，不经模型。 */
    fun titleOf(userText: String): String {
        val t = userText.trim().replace(Regex("\\s+"), " ")
        return if (t.length <= 12) t else t.substring(0, 12) + "…"
    }

    companion object {
        private const val TAG = "LocalIntentPlanner"

        /** 标点：子序列比对时忽略。 */
        private const val PUNCTUATION = "，。；、！？：,.!?;:'\"()（）【】[]{}"

        /**
         * [content] 的字符（去掉空白标点后）是否是 [userText] 的**子序列**。
         *
         * 用子序列而不是子串：「附近的超市」不是「附近有超市吗」的子串，
         * 但去掉空白标点后是它的子序列 —— 模型把「超市」从「附近有超市吗」里
         * 摘出来是合法的，凭空造一个「家乐福」就不是。
         *
         * 放在 companion 里是因为安全闸（[LocalAssistantRouter]）也要用同一份判定 ——
         * 两处各写一遍，迟早会漂移。
         */
        fun isDerivedFrom(content: String, userText: String): Boolean {
            val c = content.filter { !it.isWhitespace() && it !in PUNCTUATION }
            if (c.isEmpty()) return false
            val u = userText.filter { !it.isWhitespace() && it !in PUNCTUATION }
            var i = 0
            for (ch in u) {
                if (ch == c[i]) {
                    i++
                    if (i == c.length) return true
                }
            }
            return false
        }

        /** 路径：`/tmp/data.json`、`/var/minis/memory/GLOBAL.md`。 */
        private val PATH_REGEX = Regex("""/(?:[A-Za-z0-9_.\-~]+/)*[A-Za-z0-9_.\-~]+""")

        /** 网址：`https://example.com/x`、`www.baidu.com`。 */
        private val URL_REGEX = Regex("""https?://\S+|\bwww\.[A-Za-z0-9.\-]+\S*""")

        /** 值的分词：空白 + 中文标点。 */
        private val WHITESPACE = Regex("""\s+""")
        private val TOKEN_SPLIT = Regex("""[\s,，。；;、！？!?:：]+""")

        /** 命令前缀：跑命令的各种中文说法。 */
        private val COMMAND_PREFIX_REGEX = Regex(
            """^(?:请|帮我|麻烦|你)?\s*(?:在[\u4e00-\u9fa5]{0,8}?(?:里|中))?\s*""" +
                """(?:运行命令|执行命令|运行|执行|跑一下|跑个|跑)\s*(?:一下|下|一条|个)?\s*""" +
                """(?:命令|指令)?\s*[:：,，]?\s*""" +
                """|(?:shell|sh|bash|cmd|command)\s+"""
        )

        /** 命令里可以安全剥掉的填充词（其余中文一律判为不合格）。 */
        private val COMMAND_FILLER_WORDS = setOf("脚本", "命令", "指令")
    }
}
