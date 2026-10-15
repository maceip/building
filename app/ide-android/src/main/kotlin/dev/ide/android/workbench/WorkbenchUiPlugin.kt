package dev.ide.android.workbench

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.android.workbench.publish.PlayPublishPanel
import dev.ide.android.reliability.ReliabilityPanel
import dev.ide.android.workbench.appengine.EnginePackPanel
import dev.ide.android.workbench.appengine.AppEnginePackagePanel
import dev.ide.android.workbench.scenario.RegressionScenarioPanel
import dev.ide.android.workbench.webapp.WebAppPackagePanel
import dev.ide.android.workbench.ui.QualityPanel
import dev.ide.android.workbench.ui.RuntimePanel
import dev.ide.ui.backend.UiActionPlaces
import dev.ide.ui.ext.ScreenContext
import dev.ide.ui.ext.ScreenContribution
import dev.ide.ui.ext.SimpleUiAction
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowContribution
import dev.ide.ui.ext.UiContributionScope
import dev.ide.ui.ext.UiPlugin
import dev.ide.workbench.runtime.RuntimeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val LocalWorkbenchRuntime = staticCompositionLocalOf<RuntimeController?> { null }

object WorkbenchUiPlugin : UiPlugin {
    override val id: String = "developer-workbench"
    const val SCREEN_ID = "developer.workbench"
    const val NODE_SCREEN_ID = "developer.workbench.node"

    override fun contributeUi(scope: UiContributionScope) {
        scope.screen(
            ScreenContribution(SCREEN_ID, "Developer workbench") { WorkbenchScreen(it) }.apply {
                showInTools = true
                toolDescription = "Local runtimes, app quality checks, and Play publishing"
                toolIconId = "build"
            },
        )
        scope.screen(
            ScreenContribution(NODE_SCREEN_ID, "Run Node project") { WorkbenchScreen(it, forceProjectRoot = true) },
        )
        scope.action(
            SimpleUiAction(
                id = "ui.runNodeProject",
                text = "Run Node project",
                description = "Choose an npm script or Node file from this project's saved Runtime configuration",
                iconId = "run",
                places = setOf(UiActionPlaces.MORE_MENU, UiActionPlaces.MAIN_OVERFLOW, UiActionPlaces.TOOL_STRIPE, UiActionPlaces.COMMAND_PALETTE),
                order = 60,
                visible = { runCatching { it.backend.project.isNode }.getOrDefault(false) },
            ) { it.navigate(NODE_SCREEN_ID) },
        )
        scope.action(
            SimpleUiAction(
                id = "ui.developerWorkbench",
                text = "Developer workbench",
                description = "Run web and Python projects, check quality, and publish to Play",
                iconId = "build",
                places = setOf(UiActionPlaces.MORE_MENU, UiActionPlaces.TOOL_STRIPE, UiActionPlaces.COMMAND_PALETTE),
                order = 65,
            ) { it.navigate(SCREEN_ID) },
        )
        scope.toolWindow(
            ToolWindowContribution(
                id = "developer.workbench.launcher",
                title = "Workbench",
                iconId = "build",
                anchor = ToolWindowAnchor.BOTTOM,
                order = 80,
            ) { context ->
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Build, verify, ship", style = MaterialTheme.typography.titleMedium)
                    Text("Local runtimes, evidence-backed quality checks, and Google Play publishing.")
                    TextButton(onClick = { context.openScreen(SCREEN_ID) }) { Text("Open developer workbench") }
                }
            },
        )
    }
}

@Composable
private fun WorkbenchScreen(context: ScreenContext, forceProjectRoot: Boolean = false) {
    val backend = remember(context.backend) { WorkbenchBackend(context.backend) }
    val appContext = LocalContext.current.applicationContext
    val repairProject = dev.ide.android.reliability.repair.LocalIncidentRepairProject.current
    val repairError = dev.ide.android.reliability.repair.LocalIncidentRepairError.current
    var selectedIncident by remember(backend.project.rootPath) { mutableStateOf<dev.ide.agent.IncidentIdentity?>(null) }
    val runtime = LocalWorkbenchRuntime.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var publishingReady by remember { mutableStateOf(false) }
    var qualitySection by rememberSaveable { mutableIntStateOf(0) }
    val projectRoot = backend.project.rootPath
    val previousProject = remember(runtime) { arrayOf(projectRoot) }
    LaunchedEffect(runtime, projectRoot) {
        if (previousProject[0] != projectRoot) runtime?.stop()
        previousProject[0] = projectRoot
    }
    LaunchedEffect(tab) {
        publishingReady = false
        if (tab == 2) {
            runtime?.stop()
            publishingReady = true
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = context::back) { Text("Back") }
                Column(Modifier.weight(1f).padding(8.dp)) {
                    Text(if (forceProjectRoot) "Run Node project" else "Developer workbench", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        backend.project.name.ifBlank { "Choose a workspace in a tool below" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TabRow(selectedTabIndex = tab) {
                listOf("Runtime", "Quality", "Publish").forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            HorizontalDivider()
            if (tab == 0) {
                EnginePackPanel(Modifier.fillMaxWidth())
                AppEnginePackagePanel(backend, context.fileActions, Modifier.fillMaxWidth())
                WebAppPackagePanel(backend, context.fileActions, Modifier.fillMaxWidth())
                GradleToolchainPanel(Modifier.fillMaxWidth())
            }
            if (tab == 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { qualitySection = 0 }) { Text("Local incidents") }
                    TextButton(onClick = { qualitySection = 3 }) { Text("AI repair") }
                    TextButton(onClick = { qualitySection = 1 }) { Text("Checks") }
                    TextButton(onClick = { qualitySection = 2 }) { Text("Scenarios") }
                }
            }
            when (tab) {
                0 -> if (runtime != null) {
                    RuntimePanel(runtime, backend, context.fileActions, Modifier.weight(1f), forceProjectRoot = forceProjectRoot,
                        onOpenIncidents = { tab = 1; qualitySection = 0 })
                } else {
                    Text("The Android runtime host is unavailable.", Modifier.padding(20.dp))
                }
                1 -> if (qualitySection == 0) {
                    ReliabilityPanel(
                        backend, context.fileActions, Modifier.weight(1f),
                        onIncidentSelected = { incident ->
                            require(incident.key.origin == dev.ide.workbench.reliability.IncidentOrigin.SDK_APPLICATION)
                            selectedIncident = dev.ide.agent.IncidentIdentity(
                                incident.key.packageName, incident.key.buildId, incident.key.sessionId, incident.key.eventId,
                            )
                            qualitySection = 3
                        },
                        onPairedApk = { packageName, buildId, apkPath ->
                            val apk = java.nio.file.Paths.get(apkPath)
                            dev.ide.android.reliability.repair.AndroidIncidentRepairHost.get(appContext)
                                .recordArtifact(packageName, buildId, apk)
                            check(dev.ide.android.ApkInstallerImpl(appContext).installAndLaunch(apk, packageName) { }) {
                                "Android installation was not requested. Pairing remains active."
                            }
                        },
                    )
                } else if (qualitySection == 3) {
                    if (repairProject != null) {
                        dev.ide.android.reliability.repair.IncidentRepairSettings(
                            repairProject, selectedIncident, Modifier.weight(1f),
                        )
                    } else {
                        Text(repairError ?: "Open a project to configure incident repair. No repair is running.", Modifier.padding(20.dp))
                    }
                } else if (qualitySection == 2 && runtime != null) {
                    RegressionScenarioPanel(runtime, backend, context.fileActions, Modifier.weight(1f))
                } else QualityPanel(
                    backend,
                    context.fileActions,
                    Modifier.weight(1f),
                    onOpenFile = { path, line ->
                        scope.launch {
                            val offset = withContext(Dispatchers.IO) {
                                runCatching {
                                    backend.files.readFile(path).lineSequence().take((line - 1).coerceAtLeast(0))
                                        .sumOf { it.length + 1 }
                                }.getOrDefault(0)
                            }
                            context.openFile(path, offset)
                        }
                    },
                )
                2 -> if (publishingReady) {
                    PlayPublishPanel(backend, context.fileActions, Modifier.weight(1f))
                } else {
                    Text("Stopping local project processes before opening publishing…", Modifier.padding(20.dp))
                }
            }
        }
    }
}
