package com.openminis.app.assistant.execution

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
