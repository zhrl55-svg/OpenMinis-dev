package com.openminis.app.assistant.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalResultFormatterTest {

    @Test
    fun `weather json becomes a readable chinese sentence`() {
        val json = """
            {
              "time": "2026-11-14T14:00",
              "temperature": 12.3,
              "temperature_unit": "°C",
              "feels_like": 10.8,
              "humidity": 65,
              "precipitation_mm": 0.0,
              "wind_speed": 8.0,
              "wind_speed_unit": "km/h",
              "weather_code": 2,
              "conditions": "Partly cloudy"
            }
        """.trimIndent()

        val answer = LocalResultFormatter.answer("weather", "current", json, true)

        assertEquals(
            "现在（14:00）：多云，12.3°C（体感 10.8°C），湿度 65%，风速 8.0 km/h，降水 0.0 mm。",
            answer,
        )
    }

    @Test
    fun `weather report envelope reads the nested current block`() {
        val json = """
            {
              "current": {
                "time": "2026-11-14T08:15",
                "temperature": 3.0,
                "feels_like": 1.0,
                "humidity": 80,
                "precipitation_mm": 1.2,
                "wind_speed": 5.5,
                "weather_code": 61,
                "conditions": "Slight rain"
              }
            }
        """.trimIndent()

        val answer = LocalResultFormatter.answer("weather", "current", json, true)

        assertTrue(answer.contains("小雨"))
        assertTrue(answer.contains("3.0°C"))
        assertTrue(answer.contains("降水 1.2 mm"))
    }

    @Test
    fun `unknown structured payload stays out of the bubble`() {
        val answer = LocalResultFormatter.answer(
            "calendar",
            "list",
            """{"events":[{"id":1,"title":"周会"}],"count":1}""",
            true,
        )
        assertEquals("", answer)
    }

    @Test
    fun `plain text output is shown as is`() {
        val answer = LocalResultFormatter.answer(
            "shell_execute",
            "run",
            "hi-from-local-model",
            true,
        )
        assertEquals("hi-from-local-model", answer)
    }

    @Test
    fun `failure text is always shown`() {
        val answer = LocalResultFormatter.answer(
            "weather",
            "current",
            """{"error":"permission_denied","message":"The user declined the location permission."}""",
            false,
        )
        assertTrue(answer.contains("permission_denied"))
    }

    @Test
    fun `battery json becomes a readable sentence`() {
        val json = """
            {
              "level_percent": 76,
              "charging": true,
              "status": "charging",
              "power_source": "usb",
              "temperature_celsius": 31.2
            }
        """.trimIndent()

        val answer = LocalResultFormatter.answer("device", "battery", json, true)

        assertEquals("电量 76%，正在充电（USB），电池温度 31.2°C。", answer)
    }

    @Test
    fun `storage json becomes a readable sentence`() {
        val json = """
            {
              "internal_total_gb": "128.0",
              "internal_used_gb": "90.0",
              "internal_free_gb": "38.0",
              "app_data_mb": "120.0",
              "app_cache_mb": "45.0"
            }
        """.trimIndent()

        val answer = LocalResultFormatter.answer("device", "storage", json, true)

        assertEquals(
            "内部存储 128.0 GB（已用 90.0 GB，剩余 38.0 GB）；应用数据 120.0 MB，缓存 45.0 MB。",
            answer,
        )
    }
}
