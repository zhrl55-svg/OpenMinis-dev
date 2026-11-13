package com.openminis.app.assistant.routing

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 90+ sentence corpus for the local assistant. Every local life route must
 * resolve without waiting for the 0.3B model; unrelated chat must not be
 * captured by a keyword.
 */
class AssistantRouteCorpusTest {

    private val zone = ZoneId.of("Asia/Singapore")
    private val now = LocalDateTime.of(2026, 11, 13, 10, 0) // Friday
    private val router = AssistantRouter(SlotExtractor({ now }, zone))
    private val routes = DefaultAssistantRoutes.all

    private data class Case(
        val text: String,
        val capability: String? = null,
        val action: String? = null,
    )

    private fun local(text: String, capability: String, action: String) =
        Case(text, capability, action)

    private fun cloud(text: String) = Case(text)

    private val cases = listOf(
        // Alarm / timer
        local("明天早上7点设个闹钟", "alarm", "set"),
        local("设一个8点半的闹钟", "alarm", "set"),
        local("每天早上6点闹钟", "alarm", "set"),
        local("关闭闹钟", "alarm", "open"),
        local("查看闹钟", "alarm", "open"),
        local("取消闹钟", "alarm", "open"),
        local("开始一个25分钟计时器", "alarm", "timer"),
        local("倒计时10分钟", "alarm", "timer"),
        local("start a 10 minute timer", "alarm", "timer"),
        local("set an alarm for 7am", "alarm", "set"),
        local("wake me up at 7am", "alarm", "set"),

        // Reminder
        local("30分钟后提醒我开会", "reminder", "schedule"),
        local("提醒我明天下午3点给妈妈打电话", "reminder", "schedule"),
        local("明天上午9点提醒我交报告", "reminder", "schedule"),
        local("remind me in 10 minutes to call mom", "reminder", "schedule"),
        local("remind me at 9am to drink water", "reminder", "schedule"),
        local("查看提醒", "reminder", "list"),
        local("我的提醒", "reminder", "list"),
        local("待触发提醒", "reminder", "pending"),
        local("取消提醒 会议", "reminder", "cancel"),

        // Calendar
        local("明天下午3点安排会议", "calendar", "create"),
        local("下午3点创建日程 项目评审", "calendar", "create"),
        local("schedule a meeting tomorrow at 3pm", "calendar", "create"),
        local("今天有什么安排", "calendar", "list"),
        local("查看日历", "calendar", "list"),
        local("打开日历", "calendar", "list"),
        local("明天日程", "calendar", "list"),
        local("我有空吗", "calendar", "freebusy"),
        local("free time tomorrow", "calendar", "freebusy"),
        local("删除日程 项目评审", "calendar", "delete"),
        local("取消会议", "calendar", "delete"),
        local("修改日程 项目评审", "calendar", "update"),
        local("reschedule meeting to Friday at 2pm", "calendar", "update"),

        // Notes
        local("记笔记：明天买牛奶", "note", "create"),
        local("新建笔记 购物清单", "note", "create"),
        local("备忘 周末计划", "note", "create"),
        local("take a note: buy milk", "note", "create"),
        local("搜索笔记 会议", "note", "search"),
        local("找笔记 Project Alpha", "note", "search"),
        local("我的笔记", "note", "list"),
        local("打开笔记", "note", "list"),
        local("删除笔记 3", "note", "delete"),
        local("delete note 2", "note", "delete"),

        // Memory
        local("记一下我喜欢喝茶", "memory_write", "write"),
        local("记住 Project X uses Port 8080", "memory_write", "write"),
        local("我的记忆", "memory_get", "search"),
        local("我说过什么", "memory_get", "search"),

        // Settings
        local("打开WiFi设置", "settings", "open"),
        local("打开蓝牙设置", "settings", "open"),
        local("打开设置", "settings", "open"),
        local("打开电池设置", "settings", "open"),
        local("打开无障碍设置", "settings", "open"),
        local("打开应用详情", "settings", "open"),
        local("打开勿扰模式设置", "settings", "open"),
        local("自动旋转设置", "settings", "open"),
        local("open bluetooth settings", "settings", "open"),
        local("把亮度调到50%", "settings", "set"),
        local("打开勿扰模式", "settings", "set"),
        local("关闭WiFi", "settings", "set"),
        local("打开飞行模式", "settings", "set"),
        local("turn off wifi", "settings", "set"),
        local("息屏时间设为5分钟", "settings", "set"),

        // Scheduled tasks
        local("每天帮我总结新闻", "scheduled_task", "create"),
        local("每周一帮我发周报", "scheduled_task", "create"),
        local("取消定时任务 每日总结", "scheduled_task", "cancel"),
        local("我的定时任务", "scheduled_task", "list"),
        local("明天上午10点定时帮我备份", "scheduled_task", "create"),
        local("10分钟后定时帮我备份照片", "scheduled_task", "create"),
        local("schedule task every day at 8am", "scheduled_task", "create"),
        local("every day help me summarize news", "scheduled_task", "create"),
        local("remind me every day to drink water", "scheduled_task", "create"),

        // Weather, device, media, contacts, app
        local("今天天气怎么样", "weather", "current"),
        local("what's the weather", "weather", "current"),
        local("电量还剩多少", "device", "battery"),
        local("battery level", "device", "battery"),
        local("剩余空间多少", "device", "storage"),
        local("disk space", "device", "storage"),
        local("手机型号是什么", "device", "info"),
        local("暂停播放", "media", "pause"),
        local("pause music", "media", "pause"),
        local("继续播放", "media", "resume"),
        local("resume music", "media", "resume"),
        local("停止播放", "media", "stop"),
        local("播放状态", "media", "status"),
        local("播放列表", "media", "list"),
        local("打电话给张三", "contacts", "dial"),
        local("直接拨打张三", "contacts", "dial"),
        local("call Alice Wang", "contacts", "dial"),
        local("拨打李四", "contacts", "dial"),
        local("搜索联系人 Alice", "contacts", "search"),
        local("打开微信", "app_launch", "open"),

        // Unrelated chat / deliberate low-information commands
        cloud("你好呀"),
        cloud("你是谁"),
        cloud("写一首关于秋天的诗"),
        cloud("讲个笑话"),
        cloud("今天心情不好"),
        cloud("帮我分析一下这份代码的架构"),
        cloud("张掖西站怎么走"),
        cloud("我想学英语"),
        cloud("明天会更好"),
        cloud("这个项目怎么样"),
        cloud("explain quantum physics"),
        cloud("tell me a story"),
        cloud("谢谢"),
        cloud("晚安"),
        cloud("打开"),
        cloud("提醒我"),
        cloud("明天"),
        cloud("schedule"),
        cloud("会议"),
        cloud("删除"),
    )

    @Test
    fun `corpus has enough coverage`() {
        assertTrue("corpus must cover 90+ sentences", cases.size >= 90)
        assertTrue(cases.count { it.capability != null } >= 70)
        assertTrue(cases.count { it.capability == null } >= 15)
    }

    @Test
    fun `every corpus sentence routes as expected`() {
        val failures = mutableListOf<String>()
        for (case in cases) {
            val decision = router.route(case.text, routes)
            if (case.capability == null) {
                if (decision is RouteDecision.Local) {
                    failures += "CLOUD EXPECTED: ${case.text} -> " +
                        "${decision.capability}/${decision.action} score=${decision.score}"
                }
            } else {
                if (decision !is RouteDecision.Local) {
                    failures += "LOCAL EXPECTED: ${case.text} -> " +
                        "cloud/${(decision as? RouteDecision.Cloud)?.reason}"
                } else if (decision.capability != case.capability || decision.action != case.action) {
                    failures += "WRONG: ${case.text} -> " +
                        "${decision.capability}/${decision.action}, want ${case.capability}/${case.action}"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `life writes require confirmation by default`() {
        val writes = listOf(
            "明天下午3点安排会议",
            "明天早上7点设个闹钟",
            "30分钟后提醒我开会",
            "记笔记：明天买牛奶",
            "把亮度调到50%",
            "每天帮我总结新闻",
            "打电话给张三",
        )
        for (text in writes) {
            val decision = router.route(text, routes)
            assertTrue(text, decision is RouteDecision.Local && decision.needsConfirmation)
        }
    }

    @Test
    fun `read life actions do not require confirmation`() {
        val reads = listOf(
            "今天有什么安排",
            "我的笔记",
            "今天天气怎么样",
            "电量还剩多少",
            "查看闹钟",
            "播放状态",
        )
        for (text in reads) {
            val decision = router.route(text, routes)
            assertTrue(text, decision is RouteDecision.Local && !decision.needsConfirmation)
        }
    }
}
