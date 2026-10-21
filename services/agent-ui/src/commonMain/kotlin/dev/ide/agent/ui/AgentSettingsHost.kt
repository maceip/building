package dev.ide.agent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAgentPermissionMode
import dev.ide.ui.ext.ScreenContext
import dev.ide.ui.screens.AgentSettingsContent

/**
 * [AgentSettingsContent] wired to [AgentService]. Safe when the backend reports [AgentService.Unsupported]:
 * controls still render with defaults and writes are no-ops.
 */
@Composable
fun AgentSettingsScreen(ctx: ScreenContext) {
    AgentSettingsHost(backend = ctx.backend, onBack = ctx::back, onOpenEditorPrefs = {})
}

@Composable
fun AgentSettingsHost(
    backend: IdeBackend,
    onBack: () -> Unit = {},
    onOpenEditorPrefs: () -> Unit = {},
    onDownloadModel: () -> Unit = {},
) {
    val agent = backend.agent
    var cfg by remember(agent) { mutableStateOf(agent.config()) }

    // 0 = On this phone, 1 = Cloud. Not a backend enum yet — kept locally so the segmented control is
    // truthful and the rest of the page can prefer offline-safe defaults while on-device is selected.
    var modeIndex by remember(agent) { mutableIntStateOf(0) }
    var offlineOnly by remember(agent) { mutableStateOf(modeIndex == 0) }
    var pauseLowBattery by remember(agent) { mutableStateOf(true) }

    // Map the redesigned radios onto the engine's permission modes. Defaults stay ASK_EACH when the
    // service is Unsupported (cfg.mode).
    var permissionIndex by remember(agent) {
        mutableIntStateOf(
            when (cfg.mode) {
                UiAgentPermissionMode.PLAN_ONLY -> 0
                UiAgentPermissionMode.AUTO_ACCEPT -> 2
                else -> 1
            },
        )
    }

    val providers = cfg.providers
    val selected = providers.firstOrNull { it.id == cfg.selectedProvider }
    val localTitle = selected?.displayName?.takeIf { it.isNotBlank() } ?: "On-device agent"
    val localMeta = when {
        cfg.model.isNotBlank() -> "${cfg.model} · Active"
        selected != null -> "${selected.defaultModel} · Active"
        else -> "No model selected"
    }
    val secondary = providers.firstOrNull { it.id != cfg.selectedProvider }
    val secondaryTitle = secondary?.displayName ?: "Cloud models"
    val secondaryMeta = secondary?.let {
        "${it.defaultModel.ifBlank { it.models.firstOrNull()?.id ?: "Ready" }} · Download when you switch to Cloud"
    } ?: "Available after you pick Cloud"

    AgentSettingsContent(
        modeIndex = modeIndex,
        onModeChange = { next ->
            modeIndex = next
            // Prefer an offline posture on-device; allow cloud fallback when Cloud is selected.
            if (next == 0) offlineOnly = true
            // Best-effort provider flip: pick the first cloud provider when moving to Cloud, and leave
            // the current one alone when returning to on-device (no local registry entry exists yet).
            if (next == 1) {
                val cloud = providers.firstOrNull { it.id != "gateway" && it.id != cfg.selectedProvider }
                    ?: providers.firstOrNull()
                if (cloud != null && cloud.id != cfg.selectedProvider) {
                    agent.selectProvider(cloud.id)
                    cfg = agent.config()
                }
            }
        },
        localModelTitle = localTitle,
        localModelMeta = localMeta,
        secondaryModelTitle = secondaryTitle,
        secondaryModelMeta = secondaryMeta,
        permissionIndex = permissionIndex,
        onPermissionChange = { i ->
            permissionIndex = i
            agent.setPermissionMode(
                when (i) {
                    0 -> UiAgentPermissionMode.PLAN_ONLY
                    2 -> UiAgentPermissionMode.AUTO_ACCEPT
                    else -> UiAgentPermissionMode.ASK_EACH
                },
            )
            cfg = agent.config()
        },
        offlineOnly = offlineOnly,
        onOfflineOnlyChange = { offlineOnly = it },
        pauseLowBattery = pauseLowBattery,
        onPauseLowBatteryChange = { pauseLowBattery = it },
        onOpenEditorPrefs = onOpenEditorPrefs,
        onDownloadModel = {
            agent.refreshModels()
            cfg = agent.config()
            onDownloadModel()
        },
        onBack = onBack,
    )
}
