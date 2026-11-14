package com.openminis.app.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * file_read 的语音摘要：说文件名和行数，不朗读正文。
 */
class FileReadToolSpeechTest {

    private val tmp: File = Files.createTempDirectory("filetool-speech").toFile()

    @After
    fun cleanUp() {
        tmp.deleteRecursively()
    }

    @Test
    fun `successful read announces file name and line count`() {
        val file = File(tmp, "notes.txt").apply { writeText("a\nb\nc") }
        val result = FileReadTool.read(file, "/var/minis/notes.txt", 1, null, 15_000, "head", "t")
        assertTrue(result.success)
        assertEquals("已读取 notes.txt，共 3 行。", result.spokenSummary)
    }

    @Test
    fun `binary read speaks no contents`() {
        val file = File(tmp, "blob.bin").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        val result = FileReadTool.read(file, "/var/minis/blob.bin", 1, null, 15_000, "head", "t")
        assertTrue(result.success)
        assertTrue(result.spokenSummary.contains("blob.bin"))
        assertTrue(result.spokenSummary.contains("二进制"))
    }
}
