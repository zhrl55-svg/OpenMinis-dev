package com.openminis.app.assistant.routing

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AssistantRouteStoreTest {

    private fun tempDir(): File =
        Files.createTempDirectory("assistant-routes").toFile()

    private fun route(
        id: String = "test.route",
        keywords: List<String> = listOf("测试日程"),
    ) = AssistantRoute(
        id = id,
        label = "Test route",
        capability = "calendar",
        action = "create",
        keywords = keywords,
    )

    private fun doc(
        vararg routes: AssistantRoute,
        defaultsVersion: Int = DefaultAssistantRoutes.SEED_VERSION,
    ): String = JSONObject()
        .put("schemaVersion", 1)
        .put("defaultsVersion", defaultsVersion)
        .put("routes", JSONArray().also { arr -> routes.forEach { arr.put(it.toJson()) } })
        .toString()

    private fun rejected(contains: String, block: () -> Unit) {
        try {
            block()
            fail("expected rejection containing '$contains'")
        } catch (e: IllegalArgumentException) {
            assertTrue("message=${e.message}", e.message.orEmpty().contains(contains))
        }
    }

    @Test
    fun `first launch seeds editable defaults`() {
        val dir = tempDir()
        val store = AssistantRouteStore(dir)
        assertTrue(store.snapshot().size >= 40)
        assertTrue(File(dir, "routes.json").isFile)
        assertNotNull(store.find("life.alarm"))
        assertEquals(
            DefaultAssistantRoutes.SEED_VERSION,
            JSONObject(File(dir, "routes.json").readText()).getInt("defaultsVersion"),
        )
    }

    @Test
    fun `add update remove and reorder persist atomically`() {
        val dir = tempDir()
        val store = AssistantRouteStore(dir)
        store.add(route())
        store.update(route(keywords = listOf("改过的关键词")))
        assertEquals(listOf("改过的关键词"), store.find("test.route")?.keywords)

        val reloaded = AssistantRouteStore(dir)
        assertEquals(listOf("改过的关键词"), reloaded.find("test.route")?.keywords)

        val before = reloaded.snapshot().map { it.id }
        reloaded.reorder(before.reversed())
        assertEquals(before.reversed(), AssistantRouteStore(dir).snapshot().map { it.id })

        reloaded.remove("test.route")
        assertFalse(AssistantRouteStore(dir).snapshot().any { it.id == "test.route" })
    }

    @Test
    fun `user edits survive reopening without being replaced by defaults`() {
        val dir = tempDir()
        val first = AssistantRouteStore(dir)
        val original = first.find("life.alarm")!!
        first.update(original.copy(keywords = listOf("我的专属闹钟词")))

        val second = AssistantRouteStore(dir)
        assertEquals(listOf("我的专属闹钟词"), second.find("life.alarm")?.keywords)
    }

    @Test
    fun `reset restores defaults`() {
        val dir = tempDir()
        val store = AssistantRouteStore(dir)
        store.remove("life.alarm")
        assertTrue(store.find("life.alarm") == null)
        store.resetDefaults()
        assertNotNull(store.find("life.alarm"))
    }

    @Test
    fun `export round trips through import`() {
        val dir = tempDir()
        val store = AssistantRouteStore(dir)
        store.add(route())
        val exported = store.exportJson()

        val other = tempDir()
        val imported = AssistantRouteStore(other)
        assertEquals(store.snapshot().size, imported.importJson(exported))
        assertEquals(store.find("test.route"), imported.find("test.route"))
    }

    @Test
    fun `duplicate ids are rejected on import`() {
        val store = AssistantRouteStore(tempDir())
        rejected("Duplicate route ids") {
            store.importJson(doc(route("same"), route("same")))
        }
    }

    @Test
    fun `unknown capability action is rejected`() {
        val store = AssistantRouteStore(tempDir())
        rejected("Unknown capability") {
            store.importJson(doc(route().copy(capability = "calendar", action = "not-real")))
        }
    }

    @Test
    fun `invalid regex is rejected`() {
        val store = AssistantRouteStore(tempDir())
        rejected("Invalid regular expression") {
            store.importJson(doc(route().copy(patterns = listOf("["))))
        }
    }

    @Test
    fun `limits reject rather than silently truncate`() {
        val store = AssistantRouteStore(tempDir())
        rejected("At most 20 keywords") {
            store.importJson(doc(route(keywords = (1..21).map { "k$it" })))
        }

        val many = (0..AssistantRouteStore.MAX_ROUTES).map {
            route("r$it").copy(keywords = listOf("测试$it"))
        }
        rejected("At most ${AssistantRouteStore.MAX_ROUTES} routes") {
            store.importJson(doc(*many.toTypedArray()))
        }
    }

    @Test
    fun `duplicate plain add and unknown update fail`() {
        val store = AssistantRouteStore(tempDir())
        store.add(route())
        rejected("Duplicate route id") { store.add(route()) }
        rejected("Unknown route id") { store.update(route("missing")) }
        rejected("Unknown route id") { store.remove("missing") }
    }

    @Test
    fun `invalid file is replaced by defaults instead of crashing`() {
        val dir = tempDir()
        File(dir, "routes.json").writeText("{not-json")
        val store = AssistantRouteStore(dir)
        assertTrue(store.snapshot().size >= 40)
    }

    @Test
    fun `v2 media route retires while user edits and custom routes survive`() {
        val dir = tempDir()
        val oldMedia = AssistantRoute(
            id = "life.media",
            label = "Media control",
            capability = "media",
            action = "pause",
            keywords = listOf("播放"),
        )
        val editedAlarm = DefaultAssistantRoutes.all
            .first { it.id == "life.alarm" }
            .copy(keywords = listOf("我的专属闹钟"))
        val custom = route("custom.media").copy(
            capability = "media",
            action = "status",
            keywords = listOf("自定义播放状态"),
        )
        File(dir, "routes.json").writeText(
            doc(oldMedia, editedAlarm, custom, defaultsVersion = 2),
        )

        val store = AssistantRouteStore(dir)
        assertTrue(store.find("life.media") == null)
        assertNotNull(store.find("life.media-pause"))
        assertEquals(listOf("我的专属闹钟"), store.find("life.alarm")?.keywords)
        assertEquals(listOf("自定义播放状态"), store.find("custom.media")?.keywords)
        assertEquals(
            DefaultAssistantRoutes.SEED_VERSION,
            JSONObject(File(dir, "routes.json").readText()).getInt("defaultsVersion"),
        )
    }
}
