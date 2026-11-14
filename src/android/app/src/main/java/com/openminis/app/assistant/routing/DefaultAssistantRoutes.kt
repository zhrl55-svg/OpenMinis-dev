package com.openminis.app.assistant.routing

object DefaultAssistantRoutes {
    const val SEED_VERSION = 5

    private val RETIRED_AT_V3 = setOf("life.media")

    private fun r(
        id: String,
        label: String,
        category: String,
        capability: String,
        action: String,
        priority: Int = 100,
        keywords: List<String> = emptyList(),
        allKeywords: List<String> = emptyList(),
        patterns: List<String> = emptyList(),
        exclude: List<String> = emptyList(),
        examples: List<String> = emptyList(),
        confirm: ConfirmMode = ConfirmMode.WRITE,
    ) = AssistantRoute(
        id = id,
        label = label,
        category = category,
        capability = capability,
        action = action,
        priority = priority,
        keywords = keywords,
        allKeywords = allKeywords,
        patterns = patterns,
        exclude = exclude,
        examples = examples,
        confirm = confirm,
    )

    val all: List<AssistantRoute> = listOf(
        // ── Existing local tools ───────────────────────────────────────
        r(
            "tool.shell", "Run shell command", "files_terminal", "shell_execute", "run", 120,
            listOf("运行", "执行", "跑一下", "跑个", "命令", "终端", "shell", "脚本", "run command", "execute"),
            examples = listOf("运行 ls -la", "执行 echo hello", "run ls -la"),
        ),
        r(
            "tool.file-read", "Read file", "files_terminal", "file_read", "read", 120,
            listOf("读一下", "读取", "打开文件", "看看文件", "文件内容", "读文件", "read file", "show file"),
            examples = listOf("读一下 /tmp/a.txt", "read file /tmp/a.txt"),
        ),
        r(
            "tool.file-write", "Write file", "files_terminal", "file_write", "write", 120,
            listOf("写入", "写文件", "保存到", "存到", "新建文件", "write file", "save to"),
            examples = listOf("把 hello 写入 /tmp/a.txt", "write hello to /tmp/a.txt"),
        ),
        r(
            "tool.file-edit", "Edit file", "files_terminal", "file_edit", "edit", 120,
            listOf("修改", "编辑文件", "改一下", "改成", "替换掉", "编辑", "edit file", "replace"),
            examples = listOf("修改 /tmp/a.txt 里的 a 改成 b"),
        ),
        r(
            "tool.browser", "Open web page", "network", "browser_use", "navigate", 115,
            listOf("网页", "浏览器", "打开网址", "上网查", "搜网页", "open website", "browser"),
            examples = listOf("打开网页 https://example.com"),
        ),
        r(
            "tool.memory-write", "Save memory", "memory", "memory_write", "write", 130,
            listOf("记住", "记一下", "存进记忆", "别忘了", "remember that", "remember"),
            examples = listOf("记住我喜欢简短回答", "remember that I like tea"),
        ),
        r(
            "tool.memory-get", "Recall memory", "memory", "memory_get", "search", 125,
            listOf("我说过", "我的记忆", "之前说过", "查记忆", "what did I say", "my memory"),
        ),
        r(
            "tool.hub", "Remote device", "network", "hub_call", "call", 110,
            listOf("另一台电脑", "远程电脑", "那台机器", "远程执行", "remote computer", "other device"),
        ),
        r(
            "tool.read-image", "Read image", "files_terminal", "read_image", "read", 110,
            listOf("这张图", "看图", "图片内容", "识别图", "look at image", "read image"),
        ),
        r(
            "tool.amap", "Search nearby places", "network", "amap_search", "search", 90,
            listOf("附近", "周边", "周围", "旁边", "最近的", "找一家", "找个", "地址是", "怎么去", "nearby"),
        ),

        // ── Life: alarm, reminder, calendar ────────────────────────────
        r(
            "life.alarm", "Set alarm", "life", "alarm", "set", 220,
            listOf("闹钟", "叫我起床", "叫我起来", "叫醒我", "alarm", "wake me up"),
            patterns = listOf("""(?:设|定|开|加).{0,6}(?:闹钟|alarm)"""),
            exclude = listOf(
                "取消闹钟", "关掉闹钟", "关闭闹钟", "打开闹钟", "查看闹钟", "我的闹钟",
                "cancel alarm", "open clock", "show alarms",
            ),
            examples = listOf("明天早上7点设个闹钟", "wake me up at 7am"),
        ),
        r(
            "life.timer", "Start timer", "life", "alarm", "timer", 215,
            listOf("计时器", "倒计时", "计时", "timer", "countdown"),
            examples = listOf("开始一个25分钟计时器", "start a 10 minute timer"),
        ),
        r(
            "life.alarm-open", "Open Clock", "life", "alarm", "open", 200,
            listOf(
                "打开闹钟", "查看闹钟", "我的闹钟", "关闭闹钟", "取消闹钟", "关掉闹钟",
                "open clock", "show alarms", "cancel alarm",
            ),
        ),
        r(
            "life.reminder", "Schedule reminder", "life", "reminder", "schedule", 230,
            listOf("提醒我", "提醒一下", "到点提醒", "记得提醒", "remind me", "reminder"),
            exclude = listOf("闹钟", "alarm"),
            examples = listOf("30分钟后提醒我开会", "remind me at 9 to call mom"),
        ),
        r(
            "life.reminder-pending", "Pending reminders", "life", "reminder", "pending", 210,
            listOf("待触发提醒", "未触发提醒", "待办提醒", "pending reminders"),
        ),
        r(
            "life.reminder-list", "List reminders", "life", "reminder", "list", 195,
            listOf(
                "提醒列表", "我的提醒", "查看提醒", "打开提醒",
                "list reminders", "show reminders",
            ),
        ),
        r(
            "life.reminder-cancel", "Cancel reminder", "life", "reminder", "cancel", 205,
            listOf("取消提醒", "删掉提醒", "cancel reminder"),
        ),
        r(
            "life.calendar-create", "Create calendar event", "life", "calendar", "create", 210,
            listOf(
                "安排日程", "创建日程", "新建日程", "加个会议", "安排会议",
                "calendar add", "schedule event", "schedule a meeting", "schedule meeting",
                "create event", "add event",
            ),
            patterns = listOf(
                """(?:安排|新建|创建|添加|加).{0,12}(?:日程|会议|事件)""",
                """(?:日程|会议|calendar).{0,8}(?:安排|新建|创建)""",
                """(?:schedule|create|add).{0,16}(?:meeting|event)""",
            ),
            examples = listOf("明天下午3点安排会议", "schedule a meeting tomorrow at 3"),
        ),
        r(
            "life.calendar-list", "List calendar", "life", "calendar", "list", 180,
            listOf(
                "查看日历", "打开日历", "我的日程", "今天日程", "明天安排", "有什么安排",
                "查看日程", "我的日历", "calendar", "my schedule",
            ),
            patterns = listOf("""(?:今天|明天|后天|本周|下周).{0,6}(?:日程|安排|会议)"""),
        ),
        r(
            "life.calendar-freebusy", "Calendar availability", "life", "calendar", "freebusy", 185,
            listOf("有空吗", "空闲时间", "什么时候有空", "空闲查询", "free time", "availability"),
        ),
        r(
            "life.calendar-delete", "Delete calendar event", "life", "calendar", "delete", 215,
            listOf("删除日程", "取消日程", "删掉会议", "取消会议", "delete event", "cancel meeting"),
        ),
        r(
            "life.calendar-update", "Update calendar event", "life", "calendar", "update", 205,
            listOf("修改日程", "改会议时间", "推迟会议", "更新日程", "update event", "reschedule meeting"),
        ),

        // ── Life: notes ───────────────────────────────────────────────
        r(
            "life.note-create", "Create note", "life", "note", "create", 225,
            listOf("记笔记", "做个笔记", "记个笔记", "备忘", "记事本", "新建笔记", "take a note", "note this"),
            exclude = listOf("记一下", "记住", "remember"),
            patterns = listOf("""(?:记|写|新建|创建).{0,4}(?:笔记|备忘)"""),
            examples = listOf("记笔记：明天买牛奶", "take a note: buy milk"),
        ),
        r(
            "life.note-search", "Search notes", "life", "note", "search", 205,
            listOf("搜笔记", "找笔记", "查找笔记", "搜索笔记", "search notes", "find note"),
        ),
        r(
            "life.note-list", "List notes", "life", "note", "list", 195,
            listOf("我的笔记", "笔记列表", "查看笔记", "打开笔记", "list notes", "show notes"),
        ),
        r(
            "life.note-delete", "Delete note", "life", "note", "delete", 205,
            listOf("删除笔记", "删掉笔记", "delete note"),
        ),

        // ── Life: settings, scheduled tasks, curated handlers ──────────
        r(
            "life.settings-open", "Open system settings", "life", "settings", "open", 220,
            listOf(
                "打开设置", "系统设置", "打开wifi设置", "打开蓝牙设置", "飞行模式设置",
                "电池设置", "显示设置", "声音设置", "位置设置", "通知设置",
                "无障碍设置", "应用详情", "open settings", "wifi settings", "bluetooth settings",
                "勿扰模式设置", "自动旋转设置", "亮度设置", "息屏设置",
            ),
            examples = listOf("打开WiFi设置", "open bluetooth settings"),
        ),
        r(
            "life.settings-open-english", "Open system settings (English)", "life",
            "settings", "open", 220,
            listOf(
                "brightness settings", "rotation settings", "screen timeout settings",
                "dnd settings", "do not disturb settings",
            ),
        ),
        r(
            "life.settings-set", "Change system setting", "life", "settings", "set", 235,
            listOf(
                "亮度调到", "调高亮度", "调低亮度", "息屏时间", "屏幕超时", "自动旋转",
                "勿扰模式", "飞行模式", "打开wifi", "关闭wifi", "打开蓝牙", "关闭蓝牙",
                "set brightness", "screen timeout", "do not disturb", "turn on wifi", "turn off wifi",
            ),
            exclude = listOf(
                "打开设置", "open settings", "wifi设置", "wi-fi设置",
                "蓝牙设置", "wifi settings", "wi-fi settings", "bluetooth settings",
                "勿扰模式设置", "自动旋转设置", "亮度设置", "息屏设置",
                "brightness settings", "rotation settings", "screen timeout settings",
                "dnd settings", "do not disturb settings",
            ),
            examples = listOf("亮度调到 50%", "打开勿扰模式", "turn off wifi"),
        ),
        r(
            "life.scheduled-create", "Create scheduled task", "life", "scheduled_task", "create", 190,
            listOf("定时任务", "定时帮我", "每天帮我", "每周帮我", "schedule task", "every day help me"),
            patterns = listOf(
                """(?:每天|每日|每周|每个工作日|工作日).{0,10}(?:帮我|替我|提醒我|定时)""",
                """(?:every\s+day|daily|every\s+week|weekdays|every\s+(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*).{0,30}(?:help\s+me|remind\s+me|run|send|summarize)""",
                """(?:remind\s+me|help\s+me).{0,24}(?:every\s+day|daily|every\s+week|weekdays|every\s+(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*)""",
            ),
        ),
        r(
            "life.scheduled-list", "List scheduled tasks", "life", "scheduled_task", "list", 180,
            listOf("定时任务列表", "我的定时任务", "scheduled tasks", "list scheduled tasks"),
        ),
        r(
            "life.scheduled-cancel", "Cancel scheduled task", "life", "scheduled_task", "cancel", 200,
            listOf("取消定时任务", "删掉定时任务", "cancel scheduled task"),
        ),
        r(
            "life.weather", "Weather", "life", "weather", "current", 160,
            listOf("天气", "下雨", "温度", "气温", "weather", "forecast"),
            // 只出现「天气」两个字不等于是问天气。真正的问句/指令会命中
            // 下面这些模式；单纯提到天气的长句子由 AssistantRouter 的
            // 话题提及保护挡回云端（例如「可以直接对接高德地图的天气接口」）。
            patterns = listOf(
                """(?:今天|明天|后天|现在|这几天|周末).{0,6}(?:天气|下雨|温度|气温)""",
                """(?:天气|下雨|温度|气温).{0,8}(?:怎么样|如何|多少度|多少|带伞|穿什么)""",
                """\b(?:what'?s|whats|how'?s|hows)\b.{0,16}\bweather\b""",
                """\bweather\b.{0,16}\b(?:today|tomorrow|now|this week)\b""",
            ),
            examples = listOf("今天天气怎么样", "what's the weather"),
        ),
        r(
            "life.device-battery", "Battery status", "life", "device", "battery", 150,
            listOf("电量", "电池", "还有多少电", "battery", "battery level"),
        ),
        r(
            "life.device-storage", "Storage status", "life", "device", "storage", 150,
            listOf("存储空间", "剩余空间", "磁盘空间", "storage", "disk space"),
        ),
        r(
            "life.device-info", "Device info", "life", "device", "info", 140,
            listOf("设备信息", "手机型号", "系统版本", "device info", "android version"),
        ),
        r(
            "life.media-pause", "Pause media", "life", "media", "pause", 145,
            listOf("暂停播放", "暂停音乐", "pause music", "pause playback"),
            // [T-music-player-top] Pausing is a transient state change, not
            // a data write: it must not raise a confirmation card.
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.media-resume", "Resume media", "life", "media", "resume", 145,
            listOf("继续播放", "恢复播放", "resume music", "resume playback"),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.media-stop", "Stop media", "life", "media", "stop", 145,
            listOf("停止播放", "关闭音乐", "stop music", "stop playback"),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.media-status", "Media status", "life", "media", "status", 140,
            listOf("播放状态", "音乐状态", "media status", "playback status"),
        ),
        r(
            "life.media-list", "List media sessions", "life", "media", "list", 135,
            listOf("播放列表", "媒体会话", "media sessions", "list playback"),
        ),
        // [T-music-player-top] The in-app music player: 「放一首周杰伦的稻香」
        // searches the NetEase catalogue on the buntu host and starts
        // playing; the mini player slides in at the top of the chat.
        r(
            "life.music-play", "Play music", "life", "media", "play", 150,
            listOf(
                "播放音乐", "放首歌", "放一首", "放首歌儿", "来首歌", "来一首",
                "听首歌", "听一首", "唱一首", "放点歌", "play music", "play a song",
            ),
            // The patterns are anchored: a command is a short sentence that
            // STARTS with the verb. Without the anchor 「音乐播放逻辑」 would
            // look like a request to play a song called 逻辑.
            patterns = listOf(
                """^(?:帮我|给我|请|帮忙|麻烦|能不能|可以|我想|我要|想|要)?\s*(?:播放|放|来|想听|要听|听|唱)\s*一?[首下]\s*[\p{L}0-9_·\s]{0,20}$""",
                """^(?:帮我|给我|请|帮忙|麻烦|能不能|可以)?\s*(?:播放|放)\s*[\p{L}0-9_·\s]{1,16}(?:的歌|歌曲|曲子|音乐)$""",
                """^(?:帮我|给我|请|帮忙|麻烦|能不能|可以)?\s*(?:播放|放)\s*[\p{L}0-9_·\s]{1,10}$""",
                """^(?:please\s+)?play\s+[\p{L}0-9_' -]{1,40}$""",
            ),
            // 「播放器的设计」这类句子里有「播放」，但那是话题不是指令。
            // 最多 20 个排除词（AssistantRouteStore 的校验上限）。
            exclude = listOf(
                "设计", "方案", "接口", "代码", "实现", "优化", "排查", "日志",
                "讨论", "开发", "架构", "重构", "原理", "为什么", "是什么",
                "逻辑", "播放器", "播放列表", "播放状态", "参数",
            ),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.music-next", "Next track", "life", "media", "next", 146,
            listOf("下一首", "下一首歌", "换一首", "next track", "next song"),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.music-prev", "Previous track", "life", "media", "previous", 146,
            listOf("上一首", "上一首歌", "previous track", "previous song"),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.contacts", "Call contact", "life", "contacts", "dial", 210,
            listOf("打电话给", "拨打", "给...打电话", "call "),
            patterns = listOf("""(?:打电话给|拨打|call)\s*[\p{L}0-9_·]{1,30}"""),
            exclude = listOf("打开电话", "话费", "提醒", "remind"),
        ),
        r(
            "life.contacts-search", "Search contacts", "life", "contacts", "search", 170,
            listOf("搜索联系人", "查找联系人", "找联系人", "search contacts", "find contact"),
        ),
        r(
            "life.app-open", "Open app", "life", "app_launch", "open", 70,
            listOf("打开应用", "启动应用", "启动app", "打开app", "打开微信", "打开支付宝", "launch app"),
            exclude = listOf("打开设置", "打开网页", "打开文件", "打开日历"),
        ),
    )

    /**
     * Add newly introduced defaults without replacing user edits, and remove
     * only the retired media route that was split into pause/resume/stop/
     * status/list. A custom route with a different id is never touched.
     */
    fun migrate(
        loaded: List<AssistantRoute>,
        fromVersion: Int,
    ): List<AssistantRoute> {
        var routes = loaded
        if (fromVersion < 3) {
            routes = routes.filterNot {
                it.id in RETIRED_AT_V3 && it.capability == "media"
            }
        }
        if (fromVersion < 4) {
            // v4 adds question/command patterns to the built-in weather route.
            // Merge rather than replace: a user who added keywords to this
            // route keeps them, but the new patterns start deciding whether a
            // bare mention of 天气 is a command or just a topic.
            val weather = all.firstOrNull { it.id == "life.weather" }
            if (weather != null) {
                routes = routes.map { route ->
                    if (route.id != "life.weather") {
                        route
                    } else {
                        route.copy(
                            keywords = (route.keywords + weather.keywords).distinct(),
                            patterns = (route.patterns + weather.patterns).distinct(),
                        )
                    }
                }
            }
        }
        if (fromVersion < SEED_VERSION) {
            val existing = routes.mapTo(hashSetOf()) { it.id }
            routes = routes + all.filter { it.id !in existing }
        }
        return routes
    }
}
