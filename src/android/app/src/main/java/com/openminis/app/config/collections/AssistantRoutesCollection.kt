package com.openminis.app.config.collections

import com.openminis.app.assistant.routing.AssistantRoute
import com.openminis.app.assistant.routing.AssistantRouteStore
import com.openminis.app.assistant.routing.ConfirmMode
import com.openminis.app.assistant.routing.LocalCapabilityRegistry
import com.openminis.app.config.ConfigCollection
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.config.readable.ConfigReadable
import com.openminis.app.config.readable.ConfigValueFormatter
import com.openminis.app.config.readable.ReadableLine
import com.openminis.app.config.readable.RedactedConfigValue
import java.util.UUID

/**
 * `assistantroutes.<id>.<field>` for `minis-config`.
 *
 * The store is the single source of truth, so a CLI edit takes effect on the
 * next assistant turn without restarting or recompiling.
 */
class AssistantRoutesCollection(
    private val store: AssistantRouteStore,
) : ConfigCollection {
    override val basePath: String get() = "assistantroutes"
    override val displayName: String get() = "Assistant directory routes"
    override val description: String get() =
        "Local instruction routes used only while Assistant mode is enabled. Changes apply on the next message."
    override val addable: Boolean get() = true
    override val removable: Boolean get() = true
    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE
    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    override fun childIds(): List<String> = store.snapshot().map { it.id }

    private fun route(id: String): AssistantRoute? = store.find(id)

    override fun fields(forId: String): List<ConfigField> {
        route(forId) ?: return emptyList()
        return listOf(
            field(
                forId, "label", "Label", "Name shown in Settings.",
                ConfigSchema.Str(maxLength = 80),
                reader = { r -> ConfigValue.Str(r.label) },
                writer = { r, v -> r.copy(label = str(v)) },
            ),
            field(
                forId, "category", "Category",
                "Group heading: life, files_terminal, network, memory, custom.",
                ConfigSchema.Str(maxLength = 40),
                reader = { r -> ConfigValue.Str(r.category) },
                writer = { r, v -> r.copy(category = str(v).ifBlank { "custom" }) },
            ),
            field(
                forId, "enabled", "Enabled", "Whether the route can match.",
                ConfigSchema.Bool,
                reader = { r -> ConfigValue.Bool(r.enabled) },
                writer = { r, v -> r.copy(enabled = bool(v)) },
            ),
            field(
                forId, "priority", "Priority",
                "Tie-break order after match score. Higher wins.",
                ConfigSchema.Int(-10000, 10000),
                reader = { r -> ConfigValue.Int(r.priority) },
                writer = { r, v -> r.copy(priority = int(v)) },
            ),
            field(
                forId, "capability", "Capability", "Registered local capability id.",
                ConfigSchema.Str(maxLength = 80),
                reader = { r -> ConfigValue.Str(r.capability) },
                writer = { r, v -> r.copy(capability = str(v)) },
            ),
            field(
                forId, "action", "Action", "Capability action id.",
                ConfigSchema.Str(maxLength = 80),
                reader = { r -> ConfigValue.Str(r.action) },
                writer = { r, v -> r.copy(action = str(v)) },
            ),
            arrayField(
                forId, "keywords", "Any keyword",
                "Any one of these phrases can match.",
                reader = { r -> r.keywords },
                writer = { r, list -> r.copy(keywords = list) },
            ),
            arrayField(
                forId, "allKeywords", "All keywords",
                "Every phrase here must appear.",
                reader = { r -> r.allKeywords },
                writer = { r, list -> r.copy(allKeywords = list) },
            ),
            arrayField(
                forId, "patterns", "Patterns",
                "Case-insensitive regular expressions; max 200 chars each.",
                reader = { r -> r.patterns },
                writer = { r, list -> r.copy(patterns = list) },
            ),
            arrayField(
                forId, "exclude", "Exclude",
                "Any matching exclusion rejects this route.",
                reader = { r -> r.exclude },
                writer = { r, list -> r.copy(exclude = list) },
            ),
            arrayField(
                forId, "examples", "Examples",
                "Example phrases shown in the editor and test panel.",
                reader = { r -> r.examples },
                writer = { r, list -> r.copy(examples = list) },
            ),
            field(
                forId,
                "confirm",
                "Confirmation",
                "never, write (default), or always.",
                ConfigSchema.StrEnum(ConfirmMode.entries.map { it.name.lowercase() }),
                reader = { r -> ConfigValue.Str(r.confirm.name.lowercase()) },
                writer = { r, v ->
                    r.copy(confirm = runCatching {
                    ConfirmMode.valueOf(str(v).uppercase())
                    }.getOrDefault(ConfirmMode.WRITE))
                },
            ),
        )
    }

    private fun field(
        id: String,
        name: String,
        displayName: String,
        description: String,
        schema: ConfigSchema,
        reader: (AssistantRoute) -> ConfigValue,
        writer: (AssistantRoute, ConfigValue) -> AssistantRoute,
    ): ConfigField = ClosureField(
        path = "$basePath.$id.$name",
        displayName = displayName,
        description = description,
        valueSchema = schema,
        risk = if (name in setOf("capability", "action", "patterns", "enabled", "confirm")) {
            ConfigRisk.SENSITIVE
        } else ConfigRisk.NORMAL,
        readable = ConfigReadable(ConfigValueFormatter.Text()),
        reader = {
            val r = route(id) ?: throw ConfigError.InvalidValue("route no longer exists")
            reader(r)
        },
        writer = { value ->
            val r = route(id) ?: throw ConfigError.InvalidValue("route no longer exists")
            val next = writer(r, value)
            runCatching { store.update(next) }
                .getOrElse { throw ConfigError.InvalidValue(it.message ?: "invalid route") }
        },
    )

    private fun arrayField(
        id: String,
        name: String,
        displayName: String,
        description: String,
        reader: (AssistantRoute) -> List<String>,
        writer: (AssistantRoute, List<String>) -> AssistantRoute,
    ): ConfigField = ClosureField(
        path = "$basePath.$id.$name",
        displayName = displayName,
        description = description,
        valueSchema = ConfigSchema.Array(ConfigSchema.Str(maxLength = if (name == "patterns") 200 else 80)),
        risk = if (name == "patterns") ConfigRisk.SENSITIVE else ConfigRisk.NORMAL,
        readable = ConfigReadable(ConfigValueFormatter.StringList(ordered = false)),
        reader = {
            val r = route(id) ?: throw ConfigError.InvalidValue("route no longer exists")
            ConfigValue.Arr(reader(r).map { ConfigValue.Str(it) })
        },
        writer = { value ->
            val r = route(id) ?: throw ConfigError.InvalidValue("route no longer exists")
            val list = (value as? ConfigValue.Arr)?.value
                ?.mapNotNull { (it as? ConfigValue.Str)?.value?.trim() }
                ?.filter { it.isNotEmpty() }
                ?: throw ConfigError.TypeMismatch("array of strings")
            runCatching { store.update(writer(r, list)) }
                .getOrElse { throw ConfigError.InvalidValue(it.message ?: "invalid route") }
        },
    )

    override fun add(payload: ConfigValue): String {
        val obj = (payload as? ConfigValue.Obj)?.value
            ?: throw ConfigError.InvalidValue("Expected a JSON object")
        val id = (obj["id"] as? ConfigValue.Str)?.value?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "route-${UUID.randomUUID().toString().take(8)}"
        val capability = (obj["capability"] as? ConfigValue.Str)?.value?.trim()
            ?: throw ConfigError.InvalidValue("`capability` required")
        val action = (obj["action"] as? ConfigValue.Str)?.value?.trim()
            ?: throw ConfigError.InvalidValue("`action` required")
        if (!LocalCapabilityRegistry.supports(capability, action)) {
            throw ConfigError.InvalidValue("Unknown capability/action: $capability/$action")
        }
        val route = AssistantRoute(
            id = id,
            label = (obj["label"] as? ConfigValue.Str)?.value?.trim()
                ?: throw ConfigError.InvalidValue("`label` required"),
            enabled = (obj["enabled"] as? ConfigValue.Bool)?.value ?: true,
            priority = (obj["priority"] as? ConfigValue.Int)?.value ?: 100,
            category = (obj["category"] as? ConfigValue.Str)?.value ?: "custom",
            capability = capability,
            action = action,
            keywords = strings(obj["keywords"]),
            allKeywords = strings(obj["all_keywords"] ?: obj["allKeywords"]),
            patterns = strings(obj["patterns"]),
            exclude = strings(obj["exclude"]),
            examples = strings(obj["examples"]),
            confirm = runCatching {
                ConfirmMode.valueOf(
                    ((obj["confirm"] as? ConfigValue.Str)?.value ?: "write").uppercase(),
                )
            }.getOrDefault(ConfirmMode.WRITE),
        )
        runCatching { store.add(route) }
            .getOrElse { throw ConfigError.InvalidValue(it.message ?: "invalid route") }
        return id
    }

    override fun remove(id: String) {
        runCatching { store.remove(id) }
            .getOrElse { throw ConfigError.InvalidValue(it.message ?: "unknown route") }
    }

    override val readableSubjectFormat: String get() = "Assistant route “%1\$@” – %2\$@"

    override fun entityLabel(id: String): String? = route(id)?.label

    override fun readableForAdd(payload: RedactedConfigValue): List<ReadableLine> {
        val d = (payload.value as? ConfigValue.Obj)?.value ?: return emptyList()
        val label = (d["label"] as? ConfigValue.Str)?.value ?: "?"
        val capability = (d["capability"] as? ConfigValue.Str)?.value ?: "?"
        val action = (d["action"] as? ConfigValue.Str)?.value ?: "?"
        return listOf(
            ReadableLine(
                "Add assistant route “%@” → %@/%@",
                listOf(
                    com.openminis.app.config.readable.ReadableArg.Text(label),
                    com.openminis.app.config.readable.ReadableArg.Text(capability),
                    com.openminis.app.config.readable.ReadableArg.Text(action),
                ),
            ),
        )
    }

    override fun readableForRemove(id: String): List<ReadableLine> {
        val r = route(id) ?: return emptyList()
        return listOf(
            ReadableLine(
                "Delete assistant route “%@”",
                listOf(com.openminis.app.config.readable.ReadableArg.Text(r.label)),
            ),
        )
    }

    private fun strings(value: ConfigValue?): List<String> {
        val list = when (value) {
            is ConfigValue.Arr -> value.value.mapNotNull { (it as? ConfigValue.Str)?.value?.trim() }
                .filter { it.isNotEmpty() }
            is ConfigValue.Str -> listOf(value.value)
            else -> emptyList()
        }
        if (list.size > AssistantRouteStore.MAX_KEYWORDS) {
            throw ConfigError.InvalidValue(
                "At most ${AssistantRouteStore.MAX_KEYWORDS} terms are allowed per field",
            )
        }
        return list
    }

    private fun str(v: ConfigValue): String =
        (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")

    private fun bool(v: ConfigValue): Boolean =
        (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("bool")

    private fun int(v: ConfigValue): Int =
        (v as? ConfigValue.Int)?.value ?: throw ConfigError.TypeMismatch("int")
}
