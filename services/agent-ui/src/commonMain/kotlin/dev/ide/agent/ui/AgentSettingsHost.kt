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

private const val PREF_OFFLINE_ONLY = "agent.restrictExternalWeb"
private const val PREF_PAUSE_LOW_BATTERY = "agent.pauseLowBattery"

/**
 * [AgentSettingsContent] wired to [AgentService] and [IdeBackend.settings]. Safe when the backend reports
 * [AgentService.Unsupported]: controls render with persisted preferences and provider state.
 */
@Composable
fun AgentSettingsScreen(ctx: ScreenContext) {
    AgentSettingsHost(
        backend = ctx.backend,
        onBack = ctx::back,
        onOpenEditorPrefs = { ctx.openScreen("ide.settings") },
    )
}

@Composable
fun AgentSettingsHost(
    backend: IdeBackend,
    onBack: () -> Unit = {},
    onOpenEditorPrefs: () -> Unit = {},
    onDownloadModel: () -> Unit = {},
) {
    val agent = backend.agent
    val settings = backend.settings
    var cfg by remember(agent) { mutableStateOf(agent.config()) }

    var modeIndex by remember(cfg.selectedProvider) {
        mutableIntStateOf(if (cfg.selectedProvider == "gateway") 0 else 1)
    }
    var offlineOnly by remember(settings) {
        mutableStateOf(settings.preference(PREF_OFFLINE_ONLY) == "true")
    }
    var pauseLowBattery by remember(settings) {
        mutableStateOf((settings.preference(PREF_PAUSE_LOW_BATTERY) ?: "true") == "true")
    }

    var permissionIndex by remember(cfg.mode) {
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
    val localTitle = selected?.displayName?.takeIf { it.isNotBlank() } ?: "Cloud AI provider"
    val localMeta = when {
        cfg.model.isNotBlank() -> "${cfg.model} · Active"
        selected != null -> "${selected.defaultModel} · Active"
        else -> "No model selected"
    }
    val secondary = providers.firstOrNull { it.id != cfg.selectedProvider }
    val secondaryTitle = secondary?.displayName ?: "Alternate provider"
    val secondaryMeta = secondary?.let {
        "${it.defaultModel.ifBlank { it.models.firstOrNull()?.id ?: "Ready" }} · Tap to switch"
    } ?: "Configure another provider in Agent settings"

    AgentSettingsContent(
        modeIndex = modeIndex,
        onModeChange = { next ->
            modeIndex = next
            if (next == 0) {
                val gw = providers.firstOrNull { it.id == "gateway" }
                if (gw != null && gw.id != cfg.selectedProvider) {
                    agent.selectProvider(gw.id)
                    cfg = agent.config()
                }
            } else {
                val cloud = providers.firstOrNull { it.id != "gateway" && it.id != cfg.selectedProvider }
                    ?: providers.firstOrNull { it.id != "gateway" }
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
        onOfflineOnlyChange = { enabled ->
            offlineOnly = enabled
            settings.setPreference(PREF_OFFLINE_ONLY, enabled.toString())
        },
        pauseLowBattery = pauseLowBattery,
        onPauseLowBatteryChange = { enabled ->
            pauseLowBattery = enabled
            settings.setPreference(PREF_PAUSE_LOW_BATTERY, enabled.toString())
        },
        onOpenEditorPrefs = onOpenEditorPrefs,
        onDownloadModel = {
            if (secondary != null) {
                agent.selectProvider(secondary.id)
            }
            agent.refreshModels()
            cfg = agent.config()
            onDownloadModel()
        },
        onBack = onBack,
    )
}

