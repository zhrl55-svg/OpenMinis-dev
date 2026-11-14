package com.openminis.app.assistant.execution

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalActionSpeechTest {

    @Test
    fun `chinese subjects are short spoken verbs`() {
        assertEquals("创建日程", LocalActionSpeech.subject("calendar", "create", true, "Create calendar event"))
        assertEquals("删除日程", LocalActionSpeech.subject("calendar", "delete", true, "Delete calendar event"))
        assertEquals("设置闹钟", LocalActionSpeech.subject("alarm", "set", true, "Set alarm"))
        assertEquals("运行命令", LocalActionSpeech.subject("shell_execute", "run", true, "Run shell command"))
    }

    @Test
    fun `english falls back to the route label`() {
        assertEquals(
            "Create calendar event",
            LocalActionSpeech.subject("calendar", "create", false, "Create calendar event"),
        )
    }

    @Test
    fun `short affirmatives confirm`() {
        listOf("确认", "确认。", "好的", "执行", "执行吧", "可以", "嗯", "对", "yes", "OK", "sure")
            .forEach {
                assertEquals(it, VoiceConfirmationDecision.CONFIRM, LocalActionSpeech.decision(it))
            }
    }

    @Test
    fun `cancel wins over confirm words`() {
        listOf("取消", "不用了", "不要", "算了", "不确认", "不可以", "不是的", "no", "cancel")
            .forEach {
                assertEquals(it, VoiceConfirmationDecision.CANCEL, LocalActionSpeech.decision(it))
            }
    }

    @Test
    fun `unrelated speech stays unclear`() {
        listOf(
            "等一下", "好麻烦", "这是什么", "我想想", "help me", "nothing", "yesterday",
            // 同时出现确认和取消词：歧义，宁可不动。
            "不用确认，直接执行", "确认取消",
        ).forEach {
            assertEquals(it, VoiceConfirmationDecision.UNCLEAR, LocalActionSpeech.decision(it))
        }
    }

    @Test
    fun `direct answers are short or exact`() {
        listOf("确认", "确认。", "取消", "好的", "执行吧", "yes", "no", "不用了").forEach {
            assertEquals(it, true, LocalActionSpeech.isDirectAnswer(it))
        }
        listOf("确认一下明天的日程", "把明天的会议改到下午三点", "今天天气怎么样").forEach {
            assertEquals(it, false, LocalActionSpeech.isDirectAnswer(it))
        }
    }

    @Test
    fun `completion announces the action and target`() {
        assertEquals(
            "已创建日程：会议",
            LocalActionSpeech.completion(
                capability = "calendar",
                action = "create",
                chinese = true,
                fallbackLabel = "Create calendar event",
                args = JSONObject().put("title", "会议"),
                success = true,
                output = "{}",
            ),
        )
        assertEquals(
            "已运行命令",
            LocalActionSpeech.completion(
                capability = "shell_execute",
                action = "run",
                chinese = true,
                fallbackLabel = "Run shell command",
                args = JSONObject(),
                success = true,
                output = "hi",
            ),
        )
    }

    @Test
    fun `completion reports the first error line`() {
        assertEquals(
            "操作失败：没有找到匹配“会议”的日程",
            LocalActionSpeech.completion(
                capability = "calendar",
                action = "delete",
                chinese = true,
                fallbackLabel = "Delete calendar event",
                args = JSONObject(),
                success = false,
                output = "没有找到匹配“会议”的日程\n未执行。",
            ),
        )
    }

    @Test
    fun `choice understands ordinals in both languages`() {
        val titles = listOf("项目评审", "周会", "客户拜访")
        assertEquals(0, LocalActionSpeech.choice("第一个", titles))
        assertEquals(1, LocalActionSpeech.choice("选第二个", titles))
        assertEquals(2, LocalActionSpeech.choice("第三个吧", titles))
        assertEquals(1, LocalActionSpeech.choice("2", titles))
        assertEquals(2, LocalActionSpeech.choice("第3个", titles))
        assertEquals(0, LocalActionSpeech.choice("first", titles))
        assertEquals(1, LocalActionSpeech.choice("number two", titles))
        // 直接说标题也认。
        assertEquals(1, LocalActionSpeech.choice("第二个 周会", titles))
        assertEquals(2, LocalActionSpeech.choice("客户拜访", titles))
    }

    @Test
    fun `choice ignores unrelated speech and out of range`() {
        val titles = listOf("项目评审", "周会")
        listOf("确认", "取消", "等一下", "帮我看看", "今天天气").forEach {
            assertEquals(it, null, LocalActionSpeech.choice(it, titles))
        }
        assertEquals(null, LocalActionSpeech.choice("第五个", titles))
        assertEquals(null, LocalActionSpeech.choice("第一个", emptyList()))
        // 单字序号只在短句里认：「统一一下」不是选第一。
        assertEquals(null, LocalActionSpeech.choice("统一一下时间", titles))
    }

    @Test
    fun `choice prompt reads up to three names`() {
        val zh = LocalActionSpeech.choicePrompt(
            listOf("项目评审", "周会", "客户拜访", "第四个不会念"),
            chinese = true,
        )
        assertTrue(zh.contains("4 个匹配"))
        assertTrue(zh.contains("第一个 项目评审"))
        assertTrue(zh.contains("第三个 客户拜访"))
        assertTrue(!zh.contains("第四个不会念"))
        assertTrue(zh.contains("取消"))

        val en = LocalActionSpeech.choicePrompt(listOf("Review", "Standup"), chinese = false)
        assertTrue(en.contains("2 matches"))
        assertTrue(en.contains("1. Review"))
        assertTrue(en.contains("cancel"))
    }

    @Test
    fun `completion is localized`() {
        assertEquals(
            "Done: Create calendar event (meeting)",
            LocalActionSpeech.completion(
                capability = "calendar",
                action = "create",
                chinese = false,
                fallbackLabel = "Create calendar event",
                args = JSONObject().put("title", "meeting"),
                success = true,
                output = "{}",
            ),
        )
    }

    @Test
    fun `completion falls back through label url and prompt`() {
        assertEquals(
            "已设置闹钟：起床",
            LocalActionSpeech.completion(
                capability = "alarm", action = "set", chinese = true,
                fallbackLabel = "Set alarm",
                args = JSONObject().put("label", "起床"),
                success = true, output = "{}",
            ),
        )
        assertTrue(
            LocalActionSpeech.completion(
                capability = "browser_use", action = "navigate", chinese = true,
                fallbackLabel = "Browser",
                args = JSONObject().put("url", "https://example.com"),
                success = true, output = "{}",
            ).contains("example.com"),
        )
        assertTrue(
            LocalActionSpeech.completion(
                capability = "scheduled_task", action = "create", chinese = true,
                fallbackLabel = "Scheduled task",
                args = JSONObject().put("prompt", "每天总结新闻"),
                success = true, output = "{}",
            ).contains("每天总结新闻"),
        )
    }

    @Test
    fun `completion policy keeps media and image silent`() {
        // 状态改变的动作：即使没有确认卡也要报一句「已…」。
        assertEquals(true, LocalActionSpeech.shouldSpeakCompletion("calendar", "create", readOnly = false))
        assertEquals(true, LocalActionSpeech.shouldSpeakCompletion("note", "create", readOnly = false))
        assertEquals(true, LocalActionSpeech.shouldSpeakCompletion("app_launch", "open", readOnly = false))
        // 媒体控制与读图保持安静。
        assertEquals(false, LocalActionSpeech.shouldSpeakCompletion("media", "play", readOnly = false))
        assertEquals(false, LocalActionSpeech.shouldSpeakCompletion("media", "pause", readOnly = false))
        assertEquals(false, LocalActionSpeech.shouldSpeakCompletion("read_image", "read", readOnly = true))
        // 只读内容型结果走摘要；没有摘要时也说不出有用信息。
        assertEquals(false, LocalActionSpeech.shouldSpeakCompletion("news_search", "search", readOnly = true))
        assertEquals(false, LocalActionSpeech.shouldSpeakCompletion("calendar", "list", readOnly = true))
        assertEquals(false, LocalActionSpeech.shouldSpeakCompletion("file_read", "read", readOnly = true))
    }
}
