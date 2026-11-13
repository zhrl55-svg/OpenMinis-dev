package com.openminis.app.assistant.routing

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local, atomic route storage. The file is the single source of truth:
 * Settings and `minis-config` both write through this store.
 */
class AssistantRouteStore internal constructor(
    private val rootDir: File,
) {
    private val file = File(rootDir, "routes.json")
    private val lock = Any()
    private val _routes = MutableStateFlow<List<AssistantRoute>>(emptyList())

    val routes: StateFlow<List<AssistantRoute>> = _routes.asStateFlow()

    init {
        ensureLoaded()
    }

    fun snapshot(): List<AssistantRoute> = _routes.value

    fun find(id: String): AssistantRoute? = _routes.value.firstOrNull { it.id == id }

    fun add(route: AssistantRoute): AssistantRoute {
        validate(route)
        synchronized(lock) {
            if (_routes.value.any { it.id == route.id }) {
                throw IllegalArgumentException("Duplicate route id: ${route.id}")
            }
            writeAll(_routes.value + route)
        }
        return route
    }

    fun update(route: AssistantRoute): AssistantRoute {
        validate(route)
        synchronized(lock) {
            if (_routes.value.none { it.id == route.id }) {
                throw IllegalArgumentException("Unknown route id: ${route.id}")
            }
            writeAll(_routes.value.map { if (it.id == route.id) route else it })
        }
        return route
    }

    fun update(id: String, transform: (AssistantRoute) -> AssistantRoute) {
        val current = find(id) ?: throw IllegalArgumentException("Unknown route id: $id")
        update(transform(current))
    }

    fun remove(id: String) {
        synchronized(lock) {
            val next = _routes.value.filterNot { it.id == id }
            if (next.size == _routes.value.size) throw IllegalArgumentException("Unknown route id: $id")
            writeAll(next)
        }
    }

    fun reorder(ids: List<String>) {
        synchronized(lock) {
            val byId = _routes.value.associateBy { it.id }
            val ordered = ids.mapNotNull { byId[it] }
            val rest = _routes.value.filter { it.id !in ids }
            writeAll(ordered + rest)
        }
    }

    fun resetDefaults() {
        synchronized(lock) {
            writeAll(DefaultAssistantRoutes.all, seedVersion = DefaultAssistantRoutes.SEED_VERSION, force = true)
        }
    }

    fun exportJson(): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("defaultsVersion", DefaultAssistantRoutes.SEED_VERSION)
        put("routes", JSONArray().also { arr -> _routes.value.forEach { arr.put(it.toJson()) } })
    }.toString(2)

    /**
     * Import replaces the complete rule set only after every route validates,
     * so a malformed file cannot leave the store half-written.
     */
    fun importJson(json: String): Int {
        val parsed = parseDocument(json)
        parsed.forEach { validate(it) }
        val duplicate = parsed.groupingBy { it.id }.eachCount().filterValues { it > 1 }
        if (duplicate.isNotEmpty()) {
            throw IllegalArgumentException("Duplicate route ids: ${duplicate.keys.joinToString()}")
        }
        if (parsed.size > MAX_ROUTES) {
            throw IllegalArgumentException("At most $MAX_ROUTES routes are allowed")
        }
        synchronized(lock) {
            writeAll(parsed, seedVersion = DefaultAssistantRoutes.SEED_VERSION, force = true)
        }
        return parsed.size
    }

    fun validate(route: AssistantRoute) {
        require(route.id.matches(Regex("""[A-Za-z0-9_.-]{1,80}"""))) {
            "Route id must be 1-80 chars of letters, digits, '.', '_' or '-'"
        }
        require(route.label.isNotBlank() && route.label.length <= 80) {
            "Route label is required (max 80 chars)"
        }
        require(LocalCapabilityRegistry.supports(route.capability, route.action)) {
            "Unknown capability/action: ${route.capability}/${route.action}"
        }
        require(route.keywords.size <= MAX_KEYWORDS) { "At most $MAX_KEYWORDS keywords per route" }
        require(route.allKeywords.size <= MAX_KEYWORDS) { "At most $MAX_KEYWORDS allKeywords per route" }
        require(route.patterns.size <= MAX_PATTERNS) { "At most $MAX_PATTERNS patterns per route" }
        require(route.exclude.size <= MAX_KEYWORDS) { "At most $MAX_KEYWORDS exclude terms per route" }
        require(route.examples.size <= MAX_KEYWORDS) { "At most $MAX_KEYWORDS examples per route" }
        require(route.keywords.all { it.length <= 80 }) { "A keyword is longer than 80 chars" }
        require(route.allKeywords.all { it.length <= 80 }) { "An allKeywords term is longer than 80 chars" }
        require(route.exclude.all { it.length <= 80 }) { "An exclude term is longer than 80 chars" }
        require(route.patterns.all { it.length <= 200 }) { "A pattern is longer than 200 chars" }
        require(route.examples.all { it.length <= 200 }) { "An example is longer than 200 chars" }
        route.patterns.forEach { pattern ->
            try {
                Regex(pattern)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid regular expression: $pattern (${e.message})")
            }
        }
        require(route.priority in -10_000..10_000) { "priority must be between -10000 and 10000" }
    }

    private fun ensureLoaded() {
        synchronized(lock) {
            rootDir.mkdirs()
            if (!file.isFile) {
                writeAll(DefaultAssistantRoutes.all, DefaultAssistantRoutes.SEED_VERSION, force = true)
                return
            }
            val doc = runCatching { JSONObject(file.readText()) }.getOrNull()
            if (doc == null) {
                AppLogger.warning(TAG, "routes.json is malformed; restoring defaults")
                writeAll(DefaultAssistantRoutes.all, DefaultAssistantRoutes.SEED_VERSION, force = true)
                return
            }
            val loaded = runCatching { parseRoutesObject(doc) }.getOrElse { e ->
                AppLogger.warning(TAG, "routes.json invalid (${e.message}); restoring defaults")
                writeAll(DefaultAssistantRoutes.all, DefaultAssistantRoutes.SEED_VERSION, force = true)
                return
            }
            val valid = loaded.filter { route ->
                runCatching { validate(route) }.isSuccess
            }.take(MAX_ROUTES)
            val seedVersion = doc.optInt("defaultsVersion", 0)
            val withNewDefaults = DefaultAssistantRoutes
                .migrate(valid, seedVersion)
                .take(MAX_ROUTES)
            _routes.value = withNewDefaults
            if (withNewDefaults.size != loaded.size ||
                seedVersion < DefaultAssistantRoutes.SEED_VERSION
            ) {
                writeAll(withNewDefaults, DefaultAssistantRoutes.SEED_VERSION, force = true)
            }
        }
    }

    private fun parseDocument(json: String): List<AssistantRoute> {
        val trimmed = json.trim()
        if (trimmed.startsWith("[")) {
            return parseRoutesArray(JSONArray(trimmed))
        }
        return parseRoutesObject(JSONObject(trimmed))
    }

    private fun parseRoutesObject(o: JSONObject): List<AssistantRoute> {
        val arr = o.optJSONArray("routes")
            ?: if (o.has("id")) JSONArray().put(o) else JSONArray()
        return parseRoutesArray(arr)
    }

    private fun parseRoutesArray(arr: JSONArray): List<AssistantRoute> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(AssistantRoute.fromJson(o))
        }
    }

    private fun writeAll(
        routes: List<AssistantRoute>,
        seedVersion: Int = DefaultAssistantRoutes.SEED_VERSION,
        force: Boolean = false,
    ) {
        rootDir.mkdirs()
        val doc = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("defaultsVersion", seedVersion)
            put("routes", JSONArray().also { arr -> routes.forEach { arr.put(it.toJson()) } })
        }
        if (!force && routes == _routes.value && file.isFile) return
        val tmp = File(rootDir, "routes.json.tmp")
        tmp.writeText(doc.toString(2))
        if (file.exists()) {
            val backup = File(rootDir, "routes.json.bak")
            runCatching { file.copyTo(backup, overwrite = true) }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        _routes.value = routes
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_ROUTES = 200
        const val MAX_KEYWORDS = 20
        const val MAX_PATTERNS = 20
        private const val TAG = "AssistantRouteStore"

        @Volatile
        private var instance: AssistantRouteStore? = null

        fun get(context: Context): AssistantRouteStore {
            return instance ?: synchronized(this) {
                instance ?: AssistantRouteStore(File(context.applicationContext.filesDir, "assistant"))
                    .also { instance = it }
            }
        }
    }
}
