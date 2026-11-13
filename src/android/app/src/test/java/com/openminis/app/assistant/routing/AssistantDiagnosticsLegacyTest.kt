package com.openminis.app.assistant.routing

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantDiagnosticsLegacyTest {
    private val zone = ZoneId.of("Asia/Singapore")
    private val now = LocalDateTime.of(2026, 11, 13, 10, 0)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    @Test
    fun `old local route lines become diagnostic records`() {
        val record = AssistantDiagnostics.parseLegacyTraceLine(
            "[09:58:12] route: enabled=true text=「明天早上7点设个闹钟」 decision=local alarm/set score=76",
            now,
        )!!
        assertEquals("明天早上7点设个闹钟", record.text)
        assertEquals("alarm", record.capability)
        assertEquals("set", record.action)
        assertEquals("local", record.decision)
        assertEquals("legacy-local", record.reason)
        assertTrue(record.at > 0)
    }

    @Test
    fun `old cloud lines keep the handoff reason`() {
        val record = AssistantDiagnostics.parseLegacyTraceLine(
            "[09:59:01] route: enabled=true text=「你好呀」 decision=cloud reason=no-match",
            now,
        )!!
        assertEquals("cloud", record.decision)
        assertEquals("no-match", record.reason)
        assertNull(record.capability)
    }

    @Test
    fun `unrelated trace lines are ignored`() {
        assertNull(
            AssistantDiagnostics.parseLegacyTraceLine(
                "[09:59:01] skip: disabled",
                now,
            ),
        )
    }
}
