package com.openminis.app.assistant.notes

import android.content.Context
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

data class LocalNote(
    val id: String,
    val title: String,
    val preview: String,
    val tags: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("preview", preview)
        put("tags", JSONArray(tags))
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): LocalNote = LocalNote(
            id = o.getString("id"),
            title = o.optString("title", ""),
            preview = o.optString("preview", ""),
            tags = o.optJSONArray("tags")?.let { arr ->
                buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
            }.orEmpty(),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
        )
    }
}

/**
 * Local-only Markdown notes under `filesDir/assistant/notes`.
 *
 * Each note body is stored verbatim in `<id>.md`; `index.json` is only a small
 * search/list index. No model rewrites note content.
 */
class NotesRepository internal constructor(private val root: File) {

    private val notesDir = File(root, "notes")
    private val indexPath = File(notesDir, "index.json")
    private val lock = Any()

    init {
        notesDir.mkdirs()
        if (!indexPath.isFile) writeIndex(emptyList())
    }

    fun list(limit: Int = 100): List<LocalNote> = readIndex()
        .sortedByDescending { it.updatedAt }
        .take(limit.coerceIn(1, 500))

    fun get(id: String): LocalNote? = readIndex().firstOrNull { it.id == id }

    fun readContent(id: String): String? {
        val note = get(id) ?: return null
        val f = noteFile(note.id)
        return if (f.isFile) f.readText() else null
    }

    fun create(
        content: String,
        title: String? = null,
        tags: List<String> = emptyList(),
    ): LocalNote = synchronized(lock) {
        val clean = content.trim()
        require(clean.isNotEmpty()) { "Note content is empty" }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val effectiveTitle = title?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: clean.lineSequence().firstOrNull().orEmpty().take(60)
        val note = LocalNote(
            id = id,
            title = effectiveTitle,
            preview = clean.replace(Regex("""\s+"""), " ").take(180),
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(20),
            createdAt = now,
            updatedAt = now,
        )
        atomicWrite(noteFile(id), clean)
        writeIndex(readIndex() + note)
        note
    }

    fun update(
        id: String,
        content: String,
        title: String? = null,
        tags: List<String>? = null,
    ): LocalNote = synchronized(lock) {
        val current = get(id) ?: throw IllegalArgumentException("Unknown note id: $id")
        val clean = content.trim()
        require(clean.isNotEmpty()) { "Note content is empty" }
        val note = current.copy(
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: current.title,
            preview = clean.replace(Regex("""\s+"""), " ").take(180),
            tags = (tags ?: current.tags).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(20),
            updatedAt = System.currentTimeMillis(),
        )
        atomicWrite(noteFile(id), clean)
        writeIndex(readIndex().map { if (it.id == id) note else it })
        note
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val current = readIndex()
        val note = current.firstOrNull { it.id == id } ?: return false
        noteFile(note.id).delete()
        writeIndex(current.filterNot { it.id == id })
        true
    }

    fun search(query: String, limit: Int = 30): List<Pair<LocalNote, String>> {
        val q = query.trim()
        if (q.isEmpty()) return list(limit).map { it to it.preview }
        val normalized = q.lowercase()
        val out = mutableListOf<Pair<LocalNote, String>>()
        for (note in readIndex().sortedByDescending { it.updatedAt }) {
            val content = runCatching { noteFile(note.id).readText() }.getOrDefault("")
            val hit = note.title.lowercase().contains(normalized) ||
                note.tags.any { it.lowercase().contains(normalized) } ||
                content.lowercase().contains(normalized)
            if (!hit) continue
            val excerpt = when {
                note.title.lowercase().contains(normalized) -> note.preview
                else -> excerptAround(content, q)
            }
            out += note to excerpt
            if (out.size >= limit) break
        }
        return out
    }

    private fun excerptAround(content: String, query: String): String {
        val idx = content.indexOf(query, ignoreCase = true)
        if (idx < 0) return content.replace(Regex("""\s+"""), " ").take(180)
        val start = (idx - 60).coerceAtLeast(0)
        val end = (idx + query.length + 120).coerceAtMost(content.length)
        return content.substring(start, end).replace(Regex("""\s+"""), " ").trim()
    }

    private fun readIndex(): List<LocalNote> {
        synchronized(lock) {
            if (!indexPath.isFile) return emptyList()
            return runCatching {
                val arr = JSONArray(indexPath.readText())
                buildList {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { add(LocalNote.fromJson(it)) }
                    }
                }
            }.onFailure {
                AppLogger.warning(TAG, "notes index read failed: ${it.message}")
            }.getOrDefault(emptyList())
        }
    }

    private fun writeIndex(notes: List<LocalNote>) {
        val arr = JSONArray()
        notes.sortedBy { it.createdAt }.forEach { arr.put(it.toJson()) }
        atomicWrite(indexPath, arr.toString(2))
    }

    private fun noteFile(id: String) = File(notesDir, "$id.md")

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    companion object {
        private const val TAG = "NotesRepository"

        @Volatile
        private var instance: NotesRepository? = null

        fun get(context: Context): NotesRepository =
            instance ?: synchronized(this) {
                instance ?: NotesRepository(
                    File(context.applicationContext.filesDir, "assistant"),
                ).also { instance = it }
            }
    }
}
