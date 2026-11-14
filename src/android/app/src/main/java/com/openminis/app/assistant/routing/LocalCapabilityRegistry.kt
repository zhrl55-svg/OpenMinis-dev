package com.openminis.app.assistant.routing

/**
 * The complete local capability surface. This is intentionally separate from
 * AgentTools: life capabilities are local-only and never enter the cloud Agent
 * schema.
 */
object LocalCapabilityRegistry {

    private fun slot(name: String, type: SlotType, required: Boolean = true) =
        SlotSpec(name, type, required)

    private fun action(
        name: String,
        readOnly: Boolean,
        risk: CapabilityRisk = if (readOnly) CapabilityRisk.LOW else CapabilityRisk.MEDIUM,
        vararg slots: SlotSpec,
    ) = CapabilityAction(name, readOnly, risk, slots.toList())

    private val capabilities: Map<String, LocalCapability> = listOf(
        LocalCapability(
            "shell_execute", "Shell command",
            mapOf("run" to action("run", false, CapabilityRisk.HIGH, slot("command", SlotType.VERBATIM_TEXT))),
            needsLlm = false,
        ),
        LocalCapability(
            "file_read", "Read file",
            mapOf("read" to action("read", true, slots = arrayOf(slot("path", SlotType.PATH)))),
        ),
        LocalCapability(
            "file_write", "Write file",
            mapOf(
                // [T-assistant-confirm-destructive-only] Writing a file can
                // overwrite what is there: that is destructive, so it keeps
                // asking.
                "write" to action(
                    "write", false, CapabilityRisk.HIGH,
                    slot("path", SlotType.PATH), slot("content", SlotType.TEXT),
                ),
            ),
            needsLlm = true,
        ),
        LocalCapability(
            "file_edit", "Edit file",
            mapOf(
                "edit" to action(
                    "edit", false, CapabilityRisk.HIGH,
                    slot("path", SlotType.PATH),
                    slot("old_string", SlotType.TEXT),
                    slot("new_string", SlotType.TEXT),
                ),
            ),
            needsLlm = true,
        ),
        LocalCapability(
            "browser_use", "Browser",
            mapOf("navigate" to action("navigate", false, slots = arrayOf(slot("url", SlotType.URL)))),
        ),
        LocalCapability(
            "memory_write", "Save memory",
            mapOf("write" to action("write", false, slots = arrayOf(slot("content", SlotType.VERBATIM_TEXT)))),
        ),
        LocalCapability(
            "memory_get", "Recall memory",
            mapOf(
                "search" to action(
                    "search", true,
                    slots = arrayOf(slot("keywords", SlotType.QUERY, required = false)),
                ),
            ),
        ),
        LocalCapability(
            "hub_call", "Remote device",
            mapOf("call" to action("call", false)),
            needsLlm = true,
        ),
        LocalCapability(
            "read_image", "Read image",
            mapOf("read" to action("read", true, slots = arrayOf(slot("path", SlotType.PATH)))),
        ),
        LocalCapability(
            "amap_search", "Places",
            mapOf("search" to action("search", true, slots = arrayOf(slot("query", SlotType.QUERY)))),
        ),
        LocalCapability(
            "calendar", "Calendar",
            mapOf(
                "list" to action("list", true),
                "create" to action(
                    "create", false,
                    slots = arrayOf(
                        slot("title", SlotType.VERBATIM_TEXT),
                        slot("start", SlotType.TIME),
                        slot("end", SlotType.TIME, required = false),
                    ),
                ),
                "update" to action(
                    "update", false, CapabilityRisk.MEDIUM,
                    slot("id_or_title", SlotType.VERBATIM_TEXT),
                    slot("title", SlotType.VERBATIM_TEXT, required = false),
                    slot("start", SlotType.TIME, required = false),
                ),
                "delete" to action(
                    "delete", false, CapabilityRisk.HIGH,
                    slot("id_or_title", SlotType.VERBATIM_TEXT),
                ),
                "freebusy" to action("freebusy", true),
            ),
        ),
        LocalCapability(
            "alarm", "Alarm and timer",
            mapOf(
                "set" to action(
                    "set", false,
                    slots = arrayOf(slot("time", SlotType.TIME), slot("label", SlotType.VERBATIM_TEXT, required = false)),
                ),
                "timer" to action(
                    "timer", false,
                    slots = arrayOf(slot("duration", SlotType.DURATION), slot("label", SlotType.VERBATIM_TEXT, required = false)),
                ),
                "open" to action("open", true),
            ),
        ),
        LocalCapability(
            "reminder", "Reminder notification",
            mapOf(
                "schedule" to action(
                    "schedule", false,
                    slots = arrayOf(
                        slot("title", SlotType.VERBATIM_TEXT),
                        slot("at", SlotType.TIME, required = false),
                        slot("after", SlotType.DURATION, required = false),
                    ),
                ),
                "list" to action("list", true),
                "pending" to action("pending", true),
                "cancel" to action("cancel", false, CapabilityRisk.HIGH, slot("id", SlotType.VERBATIM_TEXT, required = false)),
            ),
        ),
        LocalCapability(
            "note", "Notes",
            mapOf(
                "create" to action(
                    "create", false,
                    slots = arrayOf(
                        slot("content", SlotType.VERBATIM_TEXT),
                        slot("title", SlotType.VERBATIM_TEXT, required = false),
                    ),
                ),
                "search" to action("search", true, slots = arrayOf(slot("query", SlotType.QUERY))),
                "list" to action("list", true),
                "delete" to action("delete", false, CapabilityRisk.HIGH, slot("id_or_query", SlotType.VERBATIM_TEXT)),
            ),
        ),
        LocalCapability(
            "settings", "System settings",
            mapOf(
                "open" to action("open", true, slots = arrayOf(slot("target", SlotType.ENUM))),
                "set" to action(
                    "set", false, CapabilityRisk.HIGH,
                    slot("target", SlotType.ENUM), slot("value", SlotType.TEXT),
                ),
            ),
        ),
        LocalCapability(
            "scheduled_task", "Scheduled task",
            mapOf(
                "create" to action(
                    "create", false,
                    slots = arrayOf(
                        slot("prompt", SlotType.VERBATIM_TEXT),
                        slot("at", SlotType.TIME, required = false),
                        slot("after", SlotType.DURATION, required = false),
                    ),
                ),
                "list" to action("list", true),
                "cancel" to action("cancel", false, CapabilityRisk.HIGH, slot("id", SlotType.VERBATIM_TEXT)),
            ),
        ),
        LocalCapability(
            "weather", "Weather",
            mapOf("current" to action("current", true)),
        ),
        LocalCapability(
            "device", "Device status",
            mapOf(
                "info" to action("info", true),
                "battery" to action("battery", true),
                "storage" to action("storage", true),
            ),
        ),
        LocalCapability(
            "media", "Media control",
            mapOf(
                // [T-music-player-top] `play` drives the in-app music player
                // (NetEase catalogue on the buntu host). The query is
                // optional: a blank query plays the public chart.
                "play" to action(
                    "play", false,
                    slots = arrayOf(slot("query", SlotType.QUERY, required = false)),
                ),
                "pause" to action("pause", false),
                "resume" to action("resume", false),
                "next" to action("next", false),
                "previous" to action("previous", false),
                "stop" to action("stop", false),
                "status" to action("status", true),
                "list" to action("list", true),
            ),
        ),
        LocalCapability(
            "contacts", "Contacts",
            mapOf(
                "dial" to action("dial", false, CapabilityRisk.HIGH, slot("name", SlotType.VERBATIM_TEXT)),
                "search" to action("search", true, slots = arrayOf(slot("name", SlotType.QUERY))),
            ),
        ),
        LocalCapability(
            "app_launch", "Open app",
            mapOf("open" to action("open", false, slots = arrayOf(slot("app", SlotType.VERBATIM_TEXT)))),
        ),
    ).associateBy { it.id }

    val all: List<LocalCapability> get() = capabilities.values.toList()

    fun get(id: String): LocalCapability? = capabilities[id]

    fun supports(capability: String, action: String): Boolean =
        capabilities[capability]?.action(action) != null

    fun action(capability: String, action: String): CapabilityAction? =
        capabilities[capability]?.action(action)

    fun isReadOnly(capability: String, action: String): Boolean =
        this.action(capability, action)?.readOnly ?: false

    fun isHighRisk(capability: String, action: String): Boolean =
        this.action(capability, action)?.risk == CapabilityRisk.HIGH
}
