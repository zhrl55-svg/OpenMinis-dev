package com.openminis.app.assistant.notes

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesRepositoryTest {

    private fun repository(): Pair<NotesRepository, File> {
        val root = Files.createTempDirectory("assistant-notes").toFile()
        return NotesRepository(root) to root
    }

    @Test
    fun `create stores verbatim markdown and index metadata`() {
        val (repo, root) = repository()
        val note = repo.create("Buy Milk\nSecond line", title = "Shopping", tags = listOf("errand"))

        assertEquals("Buy Milk\nSecond line", File(root, "notes/${note.id}.md").readText())
        val index = JSONArray(File(root, "notes/index.json").readText())
        assertEquals(1, index.length())
        assertEquals("Shopping", index.getJSONObject(0).getString("title"))
        assertEquals(listOf("errand"), note.tags)
    }

    @Test
    fun `search is case insensitive and returns an excerpt`() {
        val (repo, _) = repository()
        repo.create("Project Alpha uses Port 8080", title = "Network")
        repo.create("Groceries", title = "Shopping")

        val hit = repo.search("project alpha")
        assertEquals(1, hit.size)
        assertTrue(hit.single().second.contains("Project Alpha"))

        val titleHit = repo.search("shopping")
        assertEquals(1, titleHit.size)
        assertEquals("Shopping", titleHit.single().first.title)
    }

    @Test
    fun `list is newest first and delete removes body and index row`() {
        val (repo, root) = repository()
        val first = repo.create("first", title = "First")
        Thread.sleep(5)
        val second = repo.create("second", title = "Second")

        assertEquals(listOf(second.id, first.id), repo.list().map { it.id })
        assertTrue(repo.delete(first.id))
        assertFalse(File(root, "notes/${first.id}.md").exists())
        assertNull(repo.get(first.id))
        assertEquals(1, repo.list().size)
    }

    @Test
    fun `reopening reads existing notes`() {
        val (repo, root) = repository()
        val note = repo.create("Persistent", title = "One")

        val reopened = NotesRepository(root)
        assertEquals(note.id, reopened.list().single().id)
        assertEquals("Persistent", reopened.readContent(note.id))
    }

    @Test
    fun `update changes title body and tags`() {
        val (repo, _) = repository()
        val note = repo.create("old", title = "Old")
        val updated = repo.update(note.id, "new body", title = "New", tags = listOf("x"))

        assertEquals("New", updated.title)
        assertEquals("new body", repo.readContent(note.id))
        assertEquals(listOf("x"), updated.tags)
        assertEquals(1, repo.list().size)
    }
}
