package com.openminis.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.assistant.routing.AssistantDiagnostics
import com.openminis.app.assistant.routing.AssistantRoute
import com.openminis.app.assistant.routing.AssistantRouteMatcher
import com.openminis.app.assistant.routing.AssistantRouteStore
import com.openminis.app.assistant.routing.AssistantRouter
import com.openminis.app.assistant.routing.ConfirmMode
import com.openminis.app.assistant.routing.LocalCapabilityRegistry
import com.openminis.app.assistant.routing.RouteDecision
import com.openminis.app.assistant.routing.RouteMatch
import com.openminis.app.llm.mnn.LocalAssistantPrefs
import com.openminis.app.llm.mnn.LocalMnnModel
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.ui.components.MinisAlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Settings surface for the user-editable local assistant route table.
 *
 * The screen deliberately operates on [AssistantRouteStore] directly. The
 * `minis-config` collection is an adapter over the same store, so a change
 * made here is visible to the next assistant turn without a rebuild.
 */
@Composable
fun AssistantRoutesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AssistantRouteStore.get(context) }
    val routes by store.routes.collectAsState()
    val model = remember { LocalMnnModel.get(context) }

    var assistantEnabled by remember { mutableStateOf(LocalAssistantPrefs.isEnabled(context)) }
    var editing by remember { mutableStateOf<AssistantRoute?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<AssistantRoute?>(null) }
    var showReset by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showTest by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var menuRouteId by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val categories = remember(routes) {
        val preferred = listOf("life", "files_terminal", "network", "memory", "custom")
        preferred.filter { category -> routes.any { it.category == category } } +
            routes.map { it.category }.distinct().filterNot { it in preferred }
    }

    fun updateSafely(block: () -> Unit) {
        runCatching { block() }.onFailure { notice = it.message ?: it.javaClass.simpleName }
    }

    fun move(route: AssistantRoute, delta: Int) {
        val current = routes.map { it.id }
        val categoryIds = routes.filter { it.category == route.category }.map { it.id }
        val categoryIndex = categoryIds.indexOf(route.id)
        val targetCategoryIndex = categoryIndex + delta
        if (categoryIndex < 0 || targetCategoryIndex !in categoryIds.indices) return
        val from = current.indexOf(route.id)
        val to = current.indexOf(categoryIds[targetCategoryIndex])
        if (from < 0 || to < 0) return
        val next = current.toMutableList()
        val tmp = next[from]
        next[from] = next[to]
        next[to] = tmp
        updateSafely { store.reorder(next) }
    }

    fun duplicate(route: AssistantRoute) {
        updateSafely {
            store.add(
                route.copy(
                    id = copyRouteId(route.id, store),
                    label = "${route.label} (copy)",
                    enabled = false,
                ),
            )
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.assistant_routes_title),
        onBack = onBack,
    ) {
        notice?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }

        SettingsSection(header = stringResource(R.string.assistant_routes_status_header)) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_assistant_mode),
                subtitle = stringResource(R.string.assistant_routes_enabled_subtitle),
                checked = assistantEnabled,
                onCheckedChange = {
                    assistantEnabled = it
                    LocalAssistantPrefs.setEnabled(context, it)
                },
                icon = Icons.Filled.Tune,
                iconColor = MaterialTheme.colorScheme.primary,
            )
            val modelReady = model.abiSupported() && model.isAvailable()
            SettingsValueRow(
                title = "0.3B",
                subtitle = stringResource(R.string.assistant_routes_model_subtitle),
                value = stringResource(
                    if (modelReady) R.string.assistant_routes_model_ready
                    else R.string.assistant_routes_model_unavailable,
                ),
                valueColor = if (modelReady) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsValueRow(
                title = "Shizuku",
                subtitle = stringResource(R.string.assistant_routes_shizuku_subtitle),
                value = stringResource(
                    if (ShizukuManager.isReady()) R.string.assistant_routes_shizuku_ready
                    else R.string.assistant_routes_shizuku_unavailable,
                ),
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.assistant_routes_rules_header),
            footer = stringResource(R.string.assistant_routes_rules_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.assistant_routes_add),
                subtitle = stringResource(R.string.assistant_routes_count, routes.size),
                icon = Icons.Filled.Add,
                onClick = {
                    editingIsNew = true
                    editing = blankRoute()
                },
            )
            SettingsRow(
                title = stringResource(R.string.assistant_routes_test),
                icon = Icons.Filled.Tune,
                onClick = { showTest = true },
                showDivider = false,
            )
        }

        categories.forEach { category ->
            val inCategory = routes.filter { it.category == category }
            SettingsSection(
                header = routeCategoryTitle(category),
                footer = stringResource(R.string.assistant_routes_count, inCategory.size),
            ) {
                if (inCategory.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.assistant_routes_no_rules),
                        showDivider = false,
                    )
                } else {
                    inCategory.forEachIndexed { index, route ->
                        val categoryRoutes = inCategory.map { it.id }
                        val categoryIndex = categoryRoutes.indexOf(route.id)
                        AssistantRouteRow(
                            route = route,
                            canMoveUp = categoryIndex > 0,
                            canMoveDown = categoryIndex in 0 until inCategory.lastIndex,
                            menuOpen = menuRouteId == route.id,
                            onMenuOpen = { menuRouteId = route.id },
                            onMenuDismiss = { menuRouteId = null },
                            onToggle = { enabled ->
                                updateSafely { store.update(route.copy(enabled = enabled)) }
                            },
                            onEdit = {
                                menuRouteId = null
                                editingIsNew = false
                                editing = route
                            },
                            onDuplicate = {
                                menuRouteId = null
                                duplicate(route)
                            },
                            onMoveUp = {
                                menuRouteId = null
                                move(route, -1)
                            },
                            onMoveDown = {
                                menuRouteId = null
                                move(route, 1)
                            },
                            onDelete = {
                                menuRouteId = null
                                deleteTarget = route
                            },
                            showDivider = index < inCategory.lastIndex,
                        )
                    }
                }
            }
        }

        SettingsSection(header = stringResource(R.string.assistant_routes_tools_header)) {
            SettingsRow(
                title = stringResource(R.string.assistant_routes_import),
                icon = Icons.Filled.FileDownload,
                onClick = { showImport = true },
            )
            SettingsRow(
                title = stringResource(R.string.assistant_routes_export),
                icon = Icons.Filled.Share,
                onClick = { showExport = true },
            )
            SettingsRow(
                title = stringResource(R.string.assistant_routes_reset),
                icon = Icons.Filled.Refresh,
                onClick = { showReset = true },
            )
            SettingsRow(
                title = stringResource(R.string.assistant_routes_diagnostics),
                icon = Icons.Filled.History,
                onClick = { showDiagnostics = true },
                showDivider = false,
            )
        }
    }

    editing?.let { route ->
        AssistantRouteEditorDialog(
            route = route,
            isNew = editingIsNew,
            onCancel = { editing = null },
            onSave = { next ->
                runCatching {
                    if (editingIsNew) store.add(next) else store.update(next)
                }.onSuccess {
                    editing = null
                    notice = null
                }.onFailure {
                    notice = context.getString(
                        R.string.assistant_routes_save_failed,
                        it.message ?: it.javaClass.simpleName,
                    )
                }
            },
        )
    }

    deleteTarget?.let { route ->
        MinisAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = stringResource(R.string.assistant_routes_delete_title),
            text = stringResource(R.string.assistant_routes_delete_message, route.label),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                updateSafely { store.remove(route.id) }
                deleteTarget = null
            },
        )
    }

    if (showReset) {
        MinisAlertDialog(
            onDismissRequest = { showReset = false },
            title = stringResource(R.string.assistant_routes_reset_title),
            text = stringResource(R.string.assistant_routes_reset_message),
            confirmText = stringResource(R.string.assistant_routes_reset),
            isDestructive = true,
            onConfirm = {
                updateSafely { store.resetDefaults() }
                showReset = false
            },
        )
    }

    if (showImport) {
        ImportRoutesDialog(
            onDismiss = { showImport = false },
            onImport = { json ->
                runCatching { store.importJson(json) }
                    .onSuccess { count ->
                        showImport = false
                        notice = context.getString(R.string.assistant_routes_import_success, count)
                    }
                    .onFailure { error ->
                        notice = context.getString(
                            R.string.assistant_routes_import_failed,
                            error.message ?: error.javaClass.simpleName,
                        )
                    }
            },
        )
    }

    if (showExport) {
        ExportRoutesDialog(
            json = store.exportJson(),
            onDismiss = { showExport = false },
            onCopy = { json ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("assistant routes", json))
                notice = context.getString(R.string.assistant_routes_copied)
            },
        )
    }

    if (showTest) {
        RouteTestDialog(
            routes = routes,
            onDismiss = { showTest = false },
        )
    }

    if (showDiagnostics) {
        RouteDiagnosticsDialog(
            context = context,
            onDismiss = { showDiagnostics = false },
        )
    }
}

@Composable
private fun AssistantRouteRow(
    route: AssistantRoute,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    showDivider: Boolean,
) {
    val keywords = if (route.keywords.isEmpty()) "—" else route.keywords.take(3).joinToString(", ")
    val thresholdPx = with(LocalDensity.current) { 30.dp.toPx() }
    var draggedPx by remember(route.id) { mutableFloatStateOf(0f) }
    val latestMoveUp by rememberUpdatedState(onMoveUp)
    val latestMoveDown by rememberUpdatedState(onMoveDown)
    val latestCanMoveUp by rememberUpdatedState(canMoveUp)
    val latestCanMoveDown by rememberUpdatedState(canMoveDown)
    SettingsRow(
        title = route.label,
        subtitle = "${route.capability}/${route.action} · $keywords · p${route.priority}",
        titleColor = if (route.enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onEdit,
        showChevron = false,
        showDivider = showDivider,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .pointerInput(route.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggedPx = 0f },
                                onDragEnd = { draggedPx = 0f },
                                onDragCancel = { draggedPx = 0f },
                                onDrag = { change, amount ->
                                    change.consume()
                                    draggedPx += amount.y
                                    if (draggedPx <= -thresholdPx && latestCanMoveUp) {
                                        latestMoveUp()
                                        draggedPx += thresholdPx
                                    } else if (draggedPx >= thresholdPx && latestCanMoveDown) {
                                        latestMoveDown()
                                        draggedPx -= thresholdPx
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.DragHandle,
                        contentDescription = stringResource(
                            R.string.assistant_routes_drag_to_reorder,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                SettingsSwitch(
                    checked = route.enabled,
                    onCheckedChange = onToggle,
                )
                Box {
                    IconButton(onClick = onMenuOpen, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.assistant_routes_rule_actions),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.assistant_routes_edit)) },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = onEdit,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.assistant_routes_duplicate)) },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                            onClick = onDuplicate,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.assistant_routes_move_up)) },
                            leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                            enabled = canMoveUp,
                            onClick = onMoveUp,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.assistant_routes_move_down)) },
                            leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                            enabled = canMoveDown,
                            onClick = onMoveDown,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.assistant_routes_delete)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = onDelete,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun AssistantRouteEditorDialog(
    route: AssistantRoute,
    isNew: Boolean,
    onCancel: () -> Unit,
    onSave: (AssistantRoute) -> Unit,
) {
    var draft by remember(route.id, isNew) { mutableStateOf(route) }
    var capabilityMenu by remember { mutableStateOf(false) }
    var actionMenu by remember { mutableStateOf(false) }
    val capability = LocalCapabilityRegistry.get(draft.capability)
    val actions = capability?.actions?.values?.toList().orEmpty()

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            if (isNew) R.string.assistant_routes_editor_new
                            else R.string.assistant_routes_editor_edit,
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            onSave(
                                draft.copy(
                                    label = draft.label.trim(),
                                    id = draft.id.trim(),
                                    category = draft.category.trim().ifBlank { "custom" },
                                    keywords = draft.keywords.map { it.trim() }.filter { it.isNotEmpty() },
                                    allKeywords = draft.allKeywords.map { it.trim() }.filter { it.isNotEmpty() },
                                    patterns = draft.patterns.map { it.trim() }.filter { it.isNotEmpty() },
                                    exclude = draft.exclude.map { it.trim() }.filter { it.isNotEmpty() },
                                    examples = draft.examples.map { it.trim() }.filter { it.isNotEmpty() },
                                ),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    EditorField(
                        label = stringResource(R.string.assistant_routes_field_id),
                        value = draft.id,
                        enabled = isNew,
                        onValueChange = { draft = draft.copy(id = it) },
                    )
                    EditorField(
                        label = stringResource(R.string.assistant_routes_field_label),
                        value = draft.label,
                        onValueChange = { draft = draft.copy(label = it) },
                    )
                    EditorField(
                        label = stringResource(R.string.assistant_routes_field_category),
                        value = draft.category,
                        onValueChange = { draft = draft.copy(category = it) },
                    )

                    DropdownChoice(
                        label = stringResource(R.string.assistant_routes_field_capability),
                        value = draft.capability,
                        expanded = capabilityMenu,
                        onExpandedChange = { capabilityMenu = it },
                        options = LocalCapabilityRegistry.all.map { it.id },
                        optionLabel = { it },
                        onSelect = { id ->
                            val firstAction = LocalCapabilityRegistry.get(id)
                                ?.actions?.keys?.firstOrNull().orEmpty()
                            draft = draft.copy(capability = id, action = firstAction)
                            capabilityMenu = false
                        },
                    )
                    DropdownChoice(
                        label = stringResource(R.string.assistant_routes_field_action),
                        value = draft.action,
                        expanded = actionMenu,
                        onExpandedChange = { actionMenu = it },
                        options = actions.map { it.name },
                        optionLabel = { name ->
                            actions.firstOrNull { it.name == name }?.let {
                                if (it.readOnly) "$name · read-only" else name
                            } ?: name
                        },
                        onSelect = { action ->
                            draft = draft.copy(action = action)
                            actionMenu = false
                        },
                    )

                    EditorField(
                        label = stringResource(R.string.assistant_routes_field_priority),
                        value = draft.priority.toString(),
                        keyboardType = KeyboardType.Number,
                        onValueChange = { value ->
                            if (value.isEmpty() || value.toIntOrNull() != null) {
                                draft = draft.copy(priority = value.toIntOrNull() ?: 0)
                            }
                        },
                    )
                    SettingsSwitchRow(
                        title = stringResource(R.string.assistant_routes_field_enabled),
                        checked = draft.enabled,
                        onCheckedChange = { draft = draft.copy(enabled = it) },
                        showDivider = false,
                    )

                    Text(
                        text = stringResource(R.string.assistant_routes_field_confirm),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConfirmMode.entries.forEach { mode ->
                            FilterChip(
                                selected = draft.confirm == mode,
                                onClick = { draft = draft.copy(confirm = mode) },
                                label = { Text(mode.name) },
                            )
                        }
                    }

                    ChipEditor(
                        label = stringResource(R.string.assistant_routes_field_keywords),
                        values = draft.keywords,
                        onChange = { draft = draft.copy(keywords = it) },
                    )
                    ChipEditor(
                        label = stringResource(R.string.assistant_routes_field_all_keywords),
                        values = draft.allKeywords,
                        onChange = { draft = draft.copy(allKeywords = it) },
                    )
                    ChipEditor(
                        label = stringResource(R.string.assistant_routes_field_patterns),
                        values = draft.patterns,
                        onChange = { draft = draft.copy(patterns = it) },
                    )
                    ChipEditor(
                        label = stringResource(R.string.assistant_routes_field_exclude),
                        values = draft.exclude,
                        onChange = { draft = draft.copy(exclude = it) },
                    )
                    ChipEditor(
                        label = stringResource(R.string.assistant_routes_field_examples),
                        values = draft.examples,
                        onChange = { draft = draft.copy(examples = it) },
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun DropdownChoice(
    label: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<String>,
    optionLabel: (String) -> String,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onExpandedChange(true) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(value.ifBlank { "—" }, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = { onSelect(option) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipEditor(
    label: String,
    values: List<String>,
    onChange: (List<String>) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    fun addInput() {
        val additions = input.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (additions.isEmpty()) return
        onChange((values + additions).distinct().take(20))
        input = ""
    }
    Column(Modifier.padding(top = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (values.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                values.forEach { value ->
                    AssistChip(
                        onClick = { onChange(values - value) },
                        label = {
                            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = false,
                maxLines = 3,
                placeholder = { Text(stringResource(R.string.assistant_routes_items_hint)) },
            )
            TextButton(onClick = { addInput() }) {
                Text(stringResource(R.string.assistant_routes_add_item))
            }
        }
    }
}

@Composable
private fun RouteTestDialog(
    routes: List<AssistantRoute>,
    onDismiss: () -> Unit,
) {
    var phrase by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<RouteTestResult?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assistant_routes_test_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = { Text(stringResource(R.string.assistant_routes_test_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = {
                            val ranked = AssistantRouteMatcher.rank(phrase, routes, limit = 3)
                            result = RouteTestResult(ranked, AssistantRouter().route(phrase, routes))
                        },
                    ) {
                        Text(stringResource(R.string.assistant_routes_test_run))
                    }
                }
                result?.let { snapshot ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        when (val decision = snapshot.decision) {
                            is RouteDecision.Local -> {
                                Text(
                                    if (decision.needsConfirmation) {
                                        stringResource(
                                            R.string.assistant_routes_test_confirm,
                                            decision.capability,
                                            decision.action,
                                        )
                                    } else {
                                        stringResource(
                                            R.string.assistant_routes_test_local,
                                            decision.capability,
                                            decision.action,
                                        )
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${stringResource(R.string.assistant_routes_test_args)}: " +
                                        decision.args.toString(2),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            is RouteDecision.Cloud -> {
                                Text(
                                    stringResource(R.string.assistant_routes_test_cloud, decision.reason),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        if (snapshot.ranked.isEmpty()) {
                            Text(
                                stringResource(R.string.assistant_routes_test_no_match),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            snapshot.ranked.forEach { match ->
                                Text(
                                    "${match.route.label} · ${match.score} · " +
                                        match.route.capability + "/" + match.route.action,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private data class RouteTestResult(
    val ranked: List<RouteMatch>,
    val decision: RouteDecision,
)

@Composable
private fun ImportRoutesDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assistant_routes_import_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp),
                placeholder = { Text(stringResource(R.string.assistant_routes_import_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onImport(text) }) {
                Text(stringResource(R.string.assistant_routes_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ExportRoutesDialog(
    json: String,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assistant_routes_export_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.assistant_routes_export_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    json,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCopy(json) }) {
                Text(stringResource(R.string.assistant_routes_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun RouteDiagnosticsDialog(
    context: Context,
    onDismiss: () -> Unit,
) {
    val records = remember { AssistantDiagnostics.recent(context, limit = 100) }
    val suggestions = remember { AssistantDiagnostics.unmatchedSuggestions(context, limit = 20) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.assistant_routes_diag_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                }
                HorizontalDivider()
                if (suggestions.isNotEmpty()) {
                    Text(
                        stringResource(R.string.assistant_routes_diag_unmatched),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp),
                    )
                    suggestions.forEach { (text, count) ->
                        Text(
                            "$count × $text",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                if (records.isEmpty()) {
                    Text(
                        stringResource(R.string.assistant_routes_diag_empty),
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(records) { record ->
                            val stamp = remember(record.at) {
                                SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                                    .format(Date(record.at))
                            }
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                                Text(
                                    "$stamp · ${record.decision} · ${record.reason} · ${record.elapsedMs}ms",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(record.text, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    record.topRoutes.joinToString(" | ") {
                                        "${it.route.label}:${it.score}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun routeCategoryTitle(category: String): String = when (category) {
    "life" -> stringResource(R.string.assistant_routes_category_life)
    "files_terminal" -> stringResource(R.string.assistant_routes_category_files_terminal)
    "network" -> stringResource(R.string.assistant_routes_category_network)
    "memory" -> stringResource(R.string.assistant_routes_category_memory)
    "custom" -> stringResource(R.string.assistant_routes_category_custom)
    else -> category.replace('_', ' ')
}

private fun blankRoute(): AssistantRoute = AssistantRoute(
    id = "custom-" + UUID.randomUUID().toString().take(8),
    label = "",
    category = "custom",
    capability = "calendar",
    action = "list",
    keywords = emptyList(),
)

private fun copyRouteId(base: String, store: AssistantRouteStore): String {
    val stem = base.take(62)
    var candidate = "$stem-copy"
    var index = 2
    while (store.find(candidate) != null) {
        candidate = "$stem-copy-$index"
        index++
    }
    return candidate
}
