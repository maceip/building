package dev.ide.android.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ide.android.reliability.ReliabilityPanel
import dev.ide.android.workbench.appengine.AppEnginePackagePanel
import dev.ide.android.workbench.publish.PlayPublishPanel
import dev.ide.android.workbench.scenario.RegressionScenarioPanel
import dev.ide.android.workbench.ui.QualityPanel
import dev.ide.android.workbench.ui.RuntimePanel
import dev.ide.android.workbench.webapp.WebAppPackagePanel
import dev.ide.ui.backend.UiActionPlaces
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.ext.ScreenBackRegistry
import dev.ide.ui.ext.ScreenContext
import dev.ide.ui.ext.ScreenContribution
import dev.ide.ui.ext.ScreenSettingsGroup
import dev.ide.ui.ext.SimpleUiAction
import dev.ide.ui.ext.UiContributionScope
import dev.ide.ui.ext.UiPlugin
import dev.ide.ui.icons.CaIcons
import dev.ide.workbench.runtime.RuntimeController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val LocalWorkbenchRuntime = staticCompositionLocalOf<RuntimeController?> { null }

object WorkbenchUiPlugin : UiPlugin {
    override val id = "developer-workbench"
    const val SCREEN_ID = "developer.workbench"
    const val NODE_SCREEN_ID = "developer.workbench.node"
    const val PUBLISH_SCREEN_ID = "developer.workbench.publish"
    const val DIAGNOSTICS_SCREEN_ID = "developer.workbench.diagnostics"
    const val REPAIR_SCREEN_ID = "developer.workbench.repair"
    const val CHECKS_SCREEN_ID = "developer.workbench.checks"
    const val SCENARIOS_SCREEN_ID = "developer.workbench.scenarios"
    const val PACKAGE_SCREEN_ID = "developer.workbench.package"

    override fun contributeUi(scope: UiContributionScope) {
        scope.screen(ScreenContribution(SCREEN_ID, "Run project") { RuntimeScreen(it, false) })
        scope.screen(ScreenContribution(NODE_SCREEN_ID, "Run Node project") { RuntimeScreen(it, true) })
        scope.screen(ScreenContribution(PUBLISH_SCREEN_ID, "Google Play") { PublishScreen(it) }.apply {
            settingsGroup = ScreenSettingsGroup.BUILD
            toolDescription = "Prepare and submit a release"
            toolIconId = "upload"
        })
        scope.screen(ScreenContribution(DIAGNOSTICS_SCREEN_ID, "Diagnostics") { DiagnosticsScreen(it) }.apply {
            settingsGroup = ScreenSettingsGroup.SYSTEM
            toolDescription = "Local incidents, checks and recovery"
            toolIconId = "logs"
        })
        scope.screen(ScreenContribution(REPAIR_SCREEN_ID, "AI repair") { RepairScreen(it) })
        scope.screen(ScreenContribution(CHECKS_SCREEN_ID, "Project checks") { ChecksScreen(it) })
        scope.screen(ScreenContribution(SCENARIOS_SCREEN_ID, "Recovery tests") { ScenariosScreen(it) })
        scope.screen(ScreenContribution(PACKAGE_SCREEN_ID, "Package Android app") { PackageScreen(it) })
        scope.action(SimpleUiAction(
            id = "ui.runNodeProject", text = "Run Node project", description = "Run a package script or JavaScript file",
            iconId = "run", places = setOf(UiActionPlaces.MORE_MENU, UiActionPlaces.MAIN_OVERFLOW, UiActionPlaces.TOOL_STRIPE, UiActionPlaces.COMMAND_PALETTE),
            order = 60, visible = { runCatching { it.backend.project.isNode }.getOrDefault(false) },
        ) { it.navigate(NODE_SCREEN_ID) })
        scope.action(SimpleUiAction(
            id = "ui.developerWorkbench", text = "Run project", description = "Run scripts, servers or a terminal",
            iconId = "run", places = setOf(UiActionPlaces.MORE_MENU, UiActionPlaces.COMMAND_PALETTE),
            order = 65, visible = { !runCatching { it.backend.project.isNode }.getOrDefault(false) },
        ) { it.navigate(SCREEN_ID) })
        scope.action(SimpleUiAction(
            id = "ui.publishGooglePlay", text = "Publish to Google Play", description = "Prepare a signed Android release",
            iconId = "upload", places = setOf(UiActionPlaces.MORE_MENU, UiActionPlaces.COMMAND_PALETTE), order = 70,
        ) { it.navigate(PUBLISH_SCREEN_ID) })
        scope.action(SimpleUiAction(
            id = "ui.runtimeDiagnostics", text = "Diagnostics", description = "Review local incidents and project checks",
            iconId = "logs", places = setOf(UiActionPlaces.COMMAND_PALETTE), order = 75,
        ) { it.navigate(DIAGNOSTICS_SCREEN_ID) })
        scope.action(SimpleUiAction(
            id = "ui.packageAndroidRuntime", text = "Package Android app", description = "Package a web or runtime project",
            iconId = "build", places = setOf(UiActionPlaces.COMMAND_PALETTE), order = 76,
        ) { it.navigate(PACKAGE_SCREEN_ID) })
    }
}

private fun ScreenContext.backFromTool() {
    if (!ScreenBackRegistry.consumeBack()) back()
}

@Composable
private fun RuntimeScreen(context: ScreenContext, node: Boolean) {
    val backend = remember(context.backend) { WorkbenchBackend(context.backend) }
    val runtime = LocalWorkbenchRuntime.current
    var more by remember { mutableStateOf(false) }
    val projectRoot = backend.project.rootPath
    val previousProject = remember(runtime) { arrayOf(projectRoot) }
    LaunchedEffect(runtime, projectRoot) {
        if (previousProject[0] != projectRoot) runtime?.stop()
        previousProject[0] = projectRoot
    }
    ExpressiveScaffold(title = if (node) "Run Node project" else "Run project", large = false, onBack = context::backFromTool,
        actions = {
            IconButton(onClick = { more = true }) { Icon(CaIcons.ellipsis, "Project tools") }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DropdownMenuItem(text = { Text("Diagnostics") }, onClick = { more = false; context.openScreen(WorkbenchUiPlugin.DIAGNOSTICS_SCREEN_ID) })
                DropdownMenuItem(text = { Text("Package Android app") }, onClick = { more = false; context.openScreen(WorkbenchUiPlugin.PACKAGE_SCREEN_ID) })
                DropdownMenuItem(text = { Text("Publish to Google Play") }, onClick = { more = false; context.openScreen(WorkbenchUiPlugin.PUBLISH_SCREEN_ID) })
            }
        },
    ) { padding ->
        if (runtime == null) Text("Runtime tools are unavailable.", Modifier.padding(padding).padding(16.dp))
        else RuntimePanel(runtime, backend, context.fileActions, Modifier.fillMaxSize().padding(padding), forceProjectRoot = node,
            onOpenIncidents = { context.openScreen(WorkbenchUiPlugin.DIAGNOSTICS_SCREEN_ID) })
    }
}

@Composable
private fun PublishScreen(context: ScreenContext) {
    val backend = remember(context.backend) { WorkbenchBackend(context.backend) }
    val runtime = LocalWorkbenchRuntime.current
    var ready by remember { mutableStateOf(false) }
    var stopFailed by remember { mutableStateOf(false) }
    LaunchedEffect(runtime) {
        try { runtime?.stop(); ready = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { stopFailed = true }
    }
    ExpressiveScaffold(title = "Google Play", large = false, onBack = context::backFromTool) { padding ->
        if (ready) PlayPublishPanel(backend, context.fileActions, Modifier.fillMaxSize().padding(padding))
        else Text(if (stopFailed) "Stop the active runtime before publishing." else "Stopping active runtime…", Modifier.padding(padding).padding(16.dp))
    }
}

@Composable
private fun DiagnosticsScreen(context: ScreenContext) {
    val backend = remember(context.backend) { WorkbenchBackend(context.backend) }
    val appContext = LocalContext.current.applicationContext
    var more by remember { mutableStateOf(false) }
    ExpressiveScaffold(title = "Diagnostics", large = false, onBack = context::backFromTool,
        actions = {
            IconButton(onClick = { more = true }) { Icon(CaIcons.ellipsis, "Diagnostic tools") }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DropdownMenuItem(text = { Text("Project checks") }, onClick = { more = false; context.openScreen(WorkbenchUiPlugin.CHECKS_SCREEN_ID) })
                DropdownMenuItem(text = { Text("Recovery tests") }, onClick = { more = false; context.openScreen(WorkbenchUiPlugin.SCENARIOS_SCREEN_ID) })
                DropdownMenuItem(text = { Text("AI repair") }, onClick = { more = false; context.openScreen(WorkbenchUiPlugin.REPAIR_SCREEN_ID) })
            }
        },
    ) { padding ->
        ReliabilityPanel(backend, context.fileActions, Modifier.fillMaxSize().padding(padding),
            onIncidentSelected = { incident ->
                require(incident.key.origin == dev.ide.workbench.reliability.IncidentOrigin.SDK_APPLICATION)
                context.openScreen(WorkbenchUiPlugin.REPAIR_SCREEN_ID, dev.ide.agent.IncidentIdentity(
                    incident.key.packageName, incident.key.buildId, incident.key.sessionId, incident.key.eventId,
                ))
            },
            onPairedApk = { packageName, buildId, apkPath ->
                val apk = java.nio.file.Paths.get(apkPath)
                dev.ide.android.reliability.repair.AndroidIncidentRepairHost.get(appContext).recordArtifact(packageName, buildId, apk)
                check(dev.ide.android.ApkInstallerImpl(appContext).installAndLaunch(apk, packageName) { }) { "Installation was not requested. Pairing remains active." }
            },
        )
    }
}

@Composable
private fun RepairScreen(context: ScreenContext) {
    val project = dev.ide.android.reliability.repair.LocalIncidentRepairProject.current
    val error = dev.ide.android.reliability.repair.LocalIncidentRepairError.current
    ExpressiveScaffold(title = "AI repair", large = false, onBack = context::backFromTool) { padding ->
        if (project == null) Text(error ?: "Open a project to configure AI repair.", Modifier.padding(padding).padding(16.dp))
        else dev.ide.android.reliability.repair.IncidentRepairSettings(project, context.argument as? dev.ide.agent.IncidentIdentity, Modifier.fillMaxSize().padding(padding))
    }
}

@Composable
private fun ChecksScreen(context: ScreenContext) {
    val backend = remember(context.backend) { WorkbenchBackend(context.backend) }
    val scope = rememberCoroutineScope()
    ExpressiveScaffold(title = "Project checks", large = false, onBack = context::backFromTool) { padding ->
        QualityPanel(backend, context.fileActions, Modifier.fillMaxSize().padding(padding), onOpenFile = { path, line ->
            scope.launch {
                val offset = withContext(Dispatchers.IO) {
                    runCatching { backend.files.readFile(path).lineSequence().take((line - 1).coerceAtLeast(0)).sumOf { it.length + 1 } }.getOrDefault(0)
                }
                context.openFile(path, offset)
            }
        })
    }
}

@Composable
private fun ScenariosScreen(context: ScreenContext) {
    val backend = remember(context.backend) { WorkbenchBackend(context.backend) }
    val runtime = LocalWorkbenchRuntime.current
    ExpressiveScaffold(title = "Recovery tests", large = false, onBack = context::backFromTool) { padding ->
        if (runtime == null) Text("Runtime tools are unavailable.", Modifier.padding(padding).padding(16.dp))
        else RegressionScenarioPanel(runtime, backend, context.fileActions, Modifier.fillMaxSize().padding(padding))
    }
}

@Composable
private fun PackageScreen(context: ScreenContext) {
    val backend = remember(context.backend) { WorkbenchBackend(context.backend) }
    ExpressiveScaffold(title = "Package Android app", large = false, onBack = context::backFromTool) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppEnginePackagePanel(backend, context.fileActions, Modifier.fillMaxWidth())
            WebAppPackagePanel(backend, context.fileActions, Modifier.fillMaxWidth())
        }
    }
}
