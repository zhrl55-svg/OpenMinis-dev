package com.openminis.app.deeplink

import android.net.Uri
import com.openminis.app.ui.navigation.Routes

/**
 * Parses minis:// deep link URIs into navigation actions.
 *
 * Supported routes (matching iOS):
 *   minis://share                              → open share flow
 *   minis://views/alarm                        → open alarm list
 *   minis://open_terminal?init_command=...      → open terminal with command
 *   minis://session/<id>                        → open specific session
 *   minis://settings                            → Settings home
 *   minis://settings/providers                  → Provider list
 *   minis://settings/providers/<instanceId>     → Provider detail
 *   minis://settings/model-groups               → Model Groups (incl. Agent Loop section)
 *   minis://settings/model-groups/<groupId>     → Model Group detail
 *   minis://settings/usage                      → Token usage
 *   minis://settings/skills                     → Skills management
 *   minis://settings/memory                     → Memory management
 *   minis://settings/storage                    → Storage management
 *   minis://settings/mount-external             → Mount External Folders list
 *   minis://settings/mounts                     → alias for mount-external
 *   minis://settings/shared-folders             → Shared Folders list (T235)
 *   minis://settings/shared_folders             → alias for shared-folders
 *   minis://settings/logs                       → Log management
 *   minis://settings/appearance                 → Appearance
 *   minis://settings/background                 → Background settings
 *   minis://settings/about                      → About
 *   minis://settings/permissions                → Permissions
 *   minis://settings/environments[?create_key=...&create_value=...&create_note=...]
 *                                               → Environment variables
 *   minis://settings/rootfs                     → Rootfs management (mirror config lives here)
 *   minis://settings/mirrors                    → alias for rootfs (mirrors live inside Rootfs UI)
 *
 * Unknown settings paths fall back to Settings home rather than
 * Unknown — matches iOS's "best-effort land somewhere reasonable"
 * behavior so an LLM-generated link can never strand the user.
 *
 * Resource-class URIs (`minis://workspace/...`, `minis://skills/...`,
 * etc.) are intentionally NOT handled here — they resolve to on-disk
 * files and go through `ChatLinkResolver` at the chat-view layer. This
 * parser only handles *navigation* targets that change the app's top-
 * level screen.
 */
sealed class DeepLinkAction {
    data object OpenShare : DeepLinkAction()
    data object OpenAlarmList : DeepLinkAction()
    data class OpenTerminal(val initCommand: String?) : DeepLinkAction()
    data class CreateEnvironmentVariable(val key: String, val value: String, val note: String) : DeepLinkAction()
    data object OpenPermissionSettings : DeepLinkAction()
    data class OpenSession(val sessionId: String) : DeepLinkAction()

    /**
     * App-icon long-press quick actions (mirrors iOS QuickActionRouter).
     *
     *  - [NewChat] — plain new draft chat
     *  - [NewCameraChat] — new chat + auto-launch camera attachment on first compose
     *
     * Encoded as `minis://action/<name>` so the static shortcuts XML can
     * point to them via plain Intent.data without any custom extras —
     * matches the existing minis:// deep-link conventions.
     */
    data object NewChat : DeepLinkAction()
    data object NewCameraChat : DeepLinkAction()

    /**
     * [T-mnn-local] 跑一次端侧小模型推理（进程内 MNN）。
     *
     * 编码为 `minis://action/local_llm?prompt=...` —— 走既有的 deep-link
     * 通道，不新增 exported receiver（那会多一个越权入口）。
     * 结果写日志（tag `LocalLlmProbe`），用于验证端侧模型在本机真能跑。
     */
    data class RunLocalLlm(val prompt: String) : DeepLinkAction()

    /**
     * [T-mnn-local] 跑一遍端侧意图规划的验收用例集。
     *
     * 编码为 `minis://action/local_intent` —— 无参数，结果写外部目录。
     */
    data object RunLocalIntent : DeepLinkAction()

    /**
     * [T-mnn-local] 开关助理模式（端侧快速路径）。
     *
     * 编码为 `minis://action/assistant_mode?on=true|false`。
     * 关掉时一条云端路径都不受影响 —— 这正是「失败即回退」的一部分：
     * 开关本身也要能一条命令回退。
     */
    data class SetAssistantMode(val on: Boolean) : DeepLinkAction()

    /**
     * T183: any settings screen reachable by route string. Extends the
     * sealed family so consumers don't have to learn 14 new case types
     * — they `safeNavigate(action.route)` and the route table in
     * [com.openminis.app.ui.navigation.Routes] does the rest. Routes
     * with arguments (provider detail, model-group detail) carry the
     * already-formatted route ("provider/<id>", "model_group/<id>").
     */
    data class OpenSettingsScreen(val route: String) : DeepLinkAction()

    /**
     * Launch the in-chat HTML preview, fullscreen, for a pinned home-screen
     * shortcut.
     *
     * Encoded as `minis://session/<sessionId>/<resource-path>` —
     * [sessionId] selects which chat to land in; [resourcePath] is the
     * resource path under `/var/minis/` (e.g. `/browser/snake.html`).
     * [title] is the cached page title at pin time, used as the fallback
     * while WebView re-reports its own.
     */
    data class OpenHtmlPreview(
        val sessionId: String,
        val resourcePath: String,
        val title: String,
    ) : DeepLinkAction()

    data object Unknown : DeepLinkAction()
}

object DeepLinkHandler {
    fun parse(uri: Uri?): DeepLinkAction {
        if (uri == null || uri.scheme != "minis") return DeepLinkAction.Unknown
        val host = uri.host ?: return DeepLinkAction.Unknown
        val path = uri.path.orEmpty()

        return when (host) {
            "share" -> DeepLinkAction.OpenShare
            "views" -> when (path) {
                "/alarm" -> DeepLinkAction.OpenAlarmList
                else -> DeepLinkAction.Unknown
            }
            "open_terminal" -> DeepLinkAction.OpenTerminal(
                initCommand = uri.getQueryParameter("init_command")
            )
            // Quick-actions surface (app-icon long-press). Path drives which
            // pending action ChatScreen consumes on first compose. Mirrors iOS
            // QuickActionRouter.swift action ids 1:1.
            "action" -> when (path.removePrefix("/")) {
                "new_chat" -> DeepLinkAction.NewChat
                // Legacy wake-overlay / shortcut link. The inline voice panel
                // is gone; keep the URI working as a plain new chat.
                "voice_chat" -> DeepLinkAction.NewChat
                "camera_chat" -> DeepLinkAction.NewCameraChat
                "local_llm" -> DeepLinkAction.RunLocalLlm(
                    uri.getQueryParameter("prompt")?.takeIf { it.isNotBlank() } ?: "你好",
                )
                "local_intent" -> DeepLinkAction.RunLocalIntent
                "assistant_mode" -> DeepLinkAction.SetAssistantMode(
                    on = uri.getQueryParameter("on")?.lowercase() != "false",
                )
                else -> DeepLinkAction.Unknown
            }
            "settings" -> parseSettingsPath(uri)
            "session" -> {
                // minis://session/<sessionId>                → OpenSession
                // minis://session/<sessionId>/<resource-path> → OpenHtmlPreview
                val segments = path.removePrefix("/")
                    .split('/')
                    .filter { it.isNotEmpty() }
                val sid = segments.firstOrNull()
                when {
                    sid.isNullOrBlank() -> DeepLinkAction.Unknown
                    segments.size == 1 -> DeepLinkAction.OpenSession(sid)
                    else -> {
                        val resourcePath = "/" + segments.drop(1).joinToString("/")
                        val title = uri.getQueryParameter("title").orEmpty()
                        DeepLinkAction.OpenHtmlPreview(sid, resourcePath, title)
                    }
                }
            }
            else -> DeepLinkAction.Unknown
        }
    }

    /**
     * T183: walk a `minis://settings/<path>` URI to the right NavHost
     * route. Two cases stay distinct:
     *
     *  - `environments?create_key=…` — keeps the dedicated
     *    [DeepLinkAction.CreateEnvironmentVariable] case because the
     *    target screen needs the parameters wired through
     *    `DeepLinkCoordinator.setPendingEnvVarCreate`.
     *  - `permissions` — keeps [DeepLinkAction.OpenPermissionSettings]
     *    so existing dispatch logic stays unchanged.
     *
     * Everything else funnels through [DeepLinkAction.OpenSettingsScreen]
     * carrying a route string from [Routes]. Unknown paths land on
     * Settings home rather than [DeepLinkAction.Unknown] so an LLM-
     * generated link can never strand the user; iOS does the same.
     */
    private fun parseSettingsPath(uri: Uri): DeepLinkAction {
        val path = uri.path?.trimStart('/').orEmpty()
        if (path.isEmpty()) return DeepLinkAction.OpenSettingsScreen(Routes.SETTINGS)
        val segments = path.split('/').filter { it.isNotEmpty() }
        val head = segments.firstOrNull() ?: return DeepLinkAction.OpenSettingsScreen(Routes.SETTINGS)
        val arg = segments.getOrNull(1)?.takeIf { it.isNotBlank() }

        return when (head) {
            "providers" ->
                if (arg != null) DeepLinkAction.OpenSettingsScreen(Routes.providerDetail(arg))
                else DeepLinkAction.OpenSettingsScreen(Routes.PROVIDER_LIST)
            "model-groups", "model_groups" ->
                if (arg != null) DeepLinkAction.OpenSettingsScreen(Routes.modelGroupDetail(arg))
                else DeepLinkAction.OpenSettingsScreen(Routes.MODEL_GROUPS)
            "usage", "usage-stats", "usage_stats" ->
                DeepLinkAction.OpenSettingsScreen(Routes.USAGE_STATS)
            "skills" -> DeepLinkAction.OpenSettingsScreen(Routes.SKILLS)
            "memory" -> DeepLinkAction.OpenSettingsScreen(Routes.MEMORY)
            "assistant-routes", "assistant_routes", "assistant" ->
                DeepLinkAction.OpenSettingsScreen(Routes.ASSISTANT_ROUTES)
            // [T-p2-agent-settings] Settings › Agents; scheduled tasks list.
            "agents" -> DeepLinkAction.OpenSettingsScreen(Routes.AGENTS)
            "scheduled", "scheduled-tasks", "scheduled_tasks" -> DeepLinkAction.OpenSettingsScreen(Routes.SCHEDULED_TASKS)
            "storage" -> DeepLinkAction.OpenSettingsScreen(Routes.STORAGE)
            "mount-external", "mount_external", "mounts", "mounted-folders", "mounted_folders" ->
                DeepLinkAction.OpenSettingsScreen(Routes.MOUNTED_FOLDERS)
            "shared-folders", "shared_folders" ->
                DeepLinkAction.OpenSettingsScreen(Routes.SHARED_FOLDERS)
            "logs" -> {
                // Optional ?tab=… selects the segmented control on the
                // Logs screen. Currently recognized: "logs" (default),
                // "config-audit". Pushed onto DeepLinkCoordinator so the
                // screen can read it on appear and clear. Mirrors iOS
                // DeepLinkRouter.handleSettings logs handling.
                DeepLinkCoordinator.setPendingLogsTab(uri.getQueryParameter("tab"))
                DeepLinkAction.OpenSettingsScreen(Routes.LOGS)
            }
            "appearance" -> DeepLinkAction.OpenSettingsScreen(Routes.APPEARANCE)
            "background" -> DeepLinkAction.OpenSettingsScreen(Routes.background())
            "about" -> DeepLinkAction.OpenSettingsScreen(Routes.ABOUT)
            "network" -> DeepLinkAction.OpenSettingsScreen(Routes.NETWORK)
            // [FEAT-261021-04] Same paths and aliases as iOS DeepLinkRouter.
            "tailscale" -> DeepLinkAction.OpenSettingsScreen(Routes.NETWORK_TAILSCALE)
            "proxy", "proxies" -> {
                // ?id=<proxy id> opens that proxy's editor (iOS FIX-261019-30).
                uri.getQueryParameter("id")?.takeIf { it.isNotBlank() }
                    ?.let { com.openminis.app.network.NetworkHub.pendingProxyEditor.value = it }
                DeepLinkAction.OpenSettingsScreen(Routes.NETWORK_PROXIES)
            }
            "dns", "container-dns", "container_dns" -> DeepLinkAction.OpenSettingsScreen(Routes.CONTAINER_DNS)
            "permissions" -> DeepLinkAction.OpenPermissionSettings
            // mirrors live as a section inside Rootfs management — no
            // standalone destination, so route both /mirrors and /rootfs
            // there. The user lands on the same screen; mirror config is
            // visible as the "Mirrors" section inside.
            "mirrors", "rootfs", "rootfs-management", "rootfs_management" ->
                DeepLinkAction.OpenSettingsScreen(Routes.ROOTFS_MANAGEMENT)
            "environments" -> {
                // iOS parity (AIChatView.swift L1407-1411): only `create_key`
                // is required. Missing `create_value`/`create_note` default
                // to empty string so a link like
                //   minis://settings/environments?create_key=GH_TOKEN&create_value=
                // (where `create_value=` is present-but-blank) still opens
                // the prefilled form. Without create_key, plain navigation
                // to the Env Vars list.
                val key = uri.getQueryParameter("create_key")
                if (!key.isNullOrEmpty()) {
                    DeepLinkAction.CreateEnvironmentVariable(
                        key = key,
                        value = uri.getQueryParameter("create_value") ?: "",
                        note = uri.getQueryParameter("create_note") ?: "",
                    )
                } else {
                    DeepLinkAction.OpenSettingsScreen(Routes.ENV_VARS)
                }
            }
            // Unknown path — land on Settings home rather than failing,
            // so the user can find what they wanted by browsing.
            else -> DeepLinkAction.OpenSettingsScreen(Routes.SETTINGS)
        }
    }
}
