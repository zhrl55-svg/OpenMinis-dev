package com.openminis.app.config

import com.openminis.app.assistant.routing.AssistantRouteStore
import com.openminis.app.config.collections.AssistantRoutesCollection
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantRoutesCollectionTest {

    private fun setup(): Pair<AssistantRouteStore, AssistantRoutesCollection> {
        val dir = Files.createTempDirectory("assistant-config").toFile()
        val store = AssistantRouteStore(dir)
        return store to AssistantRoutesCollection(store)
    }

    @Test
    fun `base path and field paths are stable`() {
        val (_, collection) = setup()
        assertEquals("assistantroutes", collection.basePath)
        val fields = collection.fields("life.alarm").associateBy { it.path }
        assertTrue(fields.containsKey("assistantroutes.life.alarm.keywords"))
        assertTrue(fields.containsKey("assistantroutes.life.alarm.allKeywords"))
        assertTrue(fields.containsKey("assistantroutes.life.alarm.confirm"))
        assertTrue(fields.containsKey("assistantroutes.life.alarm.patterns"))
    }

    @Test
    fun `add set and remove apply without rebuilding`() {
        val (store, collection) = setup()
        val payload = ConfigValue.decode(
            """
            {
              "id": "custom.standup",
              "label": "Standup",
              "capability": "calendar",
              "action": "create",
              "keywords": ["站会", "standup"],
              "allKeywords": ["明天"],
              "confirm": "write"
            }
            """.trimIndent(),
        )!!
        assertEquals("custom.standup", collection.add(payload))
        assertNotNull(store.find("custom.standup"))

        val field = collection.fields("custom.standup")
            .single { it.path == "assistantroutes.custom.standup.keywords" }
        field.write(
            ConfigValue.Arr(
                listOf(ConfigValue.Str("站会"), ConfigValue.Str("每日站会")),
            ),
        )
        assertEquals(listOf("站会", "每日站会"), store.find("custom.standup")?.keywords)

        collection.remove("custom.standup")
        assertEquals(null, store.find("custom.standup"))
    }

    @Test
    fun `unknown capability and oversized arrays are rejected`() {
        val (_, collection) = setup()
        val unknown = ConfigValue.decode(
            """{"id":"bad","label":"Bad","capability":"not-real","action":"x","keywords":["x"]}""",
        )!!
        assertThrows(ConfigError.InvalidValue::class.java) { collection.add(unknown) }

        val tooMany = ConfigValue.decode(
            """{"id":"too-many","label":"Many","capability":"calendar","action":"create",
               "keywords":["1","2","3","4","5","6","7","8","9","10","11","12","13","14","15",
               "16","17","18","19","20","21"]}""",
        )!!
        assertThrows(ConfigError.InvalidValue::class.java) { collection.add(tooMany) }
    }

    @Test
    fun `invalid regex is rejected at the collection boundary`() {
        val (_, collection) = setup()
        val bad = ConfigValue.decode(
            """{"id":"bad-regex","label":"Bad","capability":"calendar","action":"create",
               "keywords":["x"],"patterns":["["]}""",
        )!!
        assertThrows(ConfigError.InvalidValue::class.java) { collection.add(bad) }
    }
}
