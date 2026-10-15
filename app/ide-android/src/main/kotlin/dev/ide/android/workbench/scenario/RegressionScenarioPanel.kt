package dev.ide.android.workbench.scenario

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ide.android.workbench.runtime.AndroidRuntimeHost
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.workbench.runtime.RuntimeController
import dev.ide.workbench.runtime.RuntimeHost
import dev.ide.workbench.scenario.RegressionScenarioComparison
import dev.ide.workbench.scenario.RegressionScenarioParser
import dev.ide.workbench.scenario.RegressionScenarioRunner
import dev.ide.workbench.scenario.RegressionScenarioSamples
import dev.ide.workbench.scenario.ReviewedRegressionScenario
import dev.ide.workbench.scenario.RuntimeControllerScenarioHost
import dev.ide.workbench.scenario.ScenarioRunApproval
import dev.ide.workbench.scenario.ScenarioRunCancelledException
import dev.ide.workbench.scenario.ScenarioRunReport
import dev.ide.workbench.scenario.ScenarioValidationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Small embeddable Quality-tab/dialog body. No scaffold/navigation, permissions or installed-app automation.
 * [controller] is used for availability and busy checks only; every run owns a NEW isolated controller. */
@Composable
fun RegressionScenarioPanel(
    controller: RuntimeController,
    backend: IdeBackend,
    fileActions: FileActions,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val project = backend.project.rootPath
    val interactive by controller.state.collectAsState()
    var demoRoot by remember(project) { mutableStateOf<Path?>(null) }
    val root = demoRoot ?: project.takeIf { it.isNotBlank() }?.let { runCatching { Paths.get(it) }.getOrNull() }
    var relativePath by remember(project) { mutableStateOf(RegressionScenarioSamples.STATE_SCENARIO) }
    var reviewed by remember(project) { mutableStateOf<ReviewedRegressionScenario?>(null) }
    var selectedTarget by remember(project) { mutableStateOf<String?>(null) }
    var targetAvailable by remember(project) { mutableStateOf(false) }
    var trusted by remember(project) { mutableStateOf(false) }
    var job by remember(project) { mutableStateOf<Job?>(null) }
    var busy by remember(project) { mutableStateOf(false) }
    var error by remember(project) { mutableStateOf<String?>(null) }
    var report by remember(project) { mutableStateOf<ScenarioRunReport?>(null) }
    var baseline by remember(project) { mutableStateOf<ScenarioRunReport?>(null) }
    var comparison by remember(project) { mutableStateOf<String?>(null) }
    DisposableEffect(project) { onDispose { job?.cancel() } }

    fun clearReview() { reviewed = null; selectedTarget = null; trusted = false; targetAvailable = false; error = null }
    fun safeMessage(failure: Exception): String = if (failure is ScenarioValidationException) failure.message ?: "Scenario validation failed"
        else "Operation failed. No project logs or credentials were copied into this panel."

    LazyColumn(modifier.fillMaxWidth().heightIn(max = 680.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Executable regression scenarios", style = MaterialTheme.typography.titleMedium)
            Text("Local Node/Python commands and owned loopback HTTP, including real stop/restart. No arbitrary Android app rotation, keyboard or permission testing.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text(if (demoRoot == null) "Workspace: ${root?.fileName ?: "no project selected"}" else "Isolated offline demo: ${root?.fileName}", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = { demoRoot = null; clearReview(); report = null }) { Text("Use project") }
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    job = scope.launch {
                        try {
                            demoRoot = withContext(Dispatchers.IO) {
                                val parent = app.filesDir.toPath().resolve("regression-scenario-demos")
                                Files.createDirectories(parent)
                                RegressionScenarioSamples.seedOfflineNode(parent)
                            }
                            relativePath = RegressionScenarioSamples.STATE_SCENARIO
                            clearReview(); report = null
                        } catch (failure: CancellationException) { throw failure }
                        catch (failure: Exception) { error = safeMessage(failure) }
                        finally { busy = false }
                    }
                }) { Text("Create offline demo") }
            }
            Text("Demo creation writes only a new app-private child workspace. No packages or provider requests.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedTextField(relativePath, onValueChange = { relativePath = it; clearReview() }, label = { Text("Reviewed project-relative .json file") },
                modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = root != null && !busy, onClick = {
                    val selectedRoot = root ?: return@Button
                    busy = true; clearReview(); report = null; comparison = null
                    job = scope.launch {
                        try {
                            val loaded = withContext(Dispatchers.IO) { RegressionScenarioParser.load(selectedRoot, relativePath) }
                            targetAvailable = controller.targets(selectedRoot).any { it.id == loaded.scenario.targetId }
                            reviewed = loaded
                        } catch (failure: CancellationException) { throw failure }
                        catch (failure: Exception) { error = safeMessage(failure) }
                        finally { busy = false }
                    }
                }) { Text("Review scenario") }
                if (demoRoot != null) TextButton(enabled = !busy, onClick = {
                    relativePath = if (relativePath == RegressionScenarioSamples.STATE_SCENARIO) RegressionScenarioSamples.COMMAND_SCENARIO else RegressionScenarioSamples.STATE_SCENARIO
                    clearReview(); report = null
                }) { Text("Switch demo test") }
            }
        }
        reviewed?.let { review ->
            item {
                HorizontalDivider()
                Text(review.reviewSummary, style = MaterialTheme.typography.bodySmall)
                Text("Scenario SHA-256: ${review.sha256}", style = MaterialTheme.typography.labelSmall)
                Text("Entry artifact hash: ${review.sourceArtifactHash}", style = MaterialTheme.typography.labelSmall)
                if (!targetAvailable) Text("The reviewed target is not available in this workspace.", color = MaterialTheme.colorScheme.error)
                TextButton(enabled = targetAvailable && !busy, onClick = { selectedTarget = review.scenario.targetId }) {
                    Text(if (selectedTarget == review.scenario.targetId) "Selected: $selectedTarget" else "Select target ${review.scenario.targetId}")
                }
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(checked = trusted, onCheckedChange = { trusted = it }, enabled = !busy)
                    Text("I reviewed this local JSON and trust the selected project code. It can write files and use the network; this is not an OS sandbox.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && trusted && selectedTarget == review.scenario.targetId && !interactive.isRunning, onClick = {
                        val approval = ScenarioRunApproval(review.sha256, requireNotNull(selectedTarget), trusted)
                        busy = true; error = null; report = null; comparison = null
                        job = scope.launch {
                            val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                            var dedicated: RuntimeController? = null
                            try {
                                val host = withContext(Dispatchers.IO) {
                                    val base = AndroidRuntimeHost(app)
                                    val parent = app.cacheDir.toPath().resolve("scenario-runtime-workspaces")
                                    Files.createDirectories(parent)
                                    val isolatedCache = Files.createTempDirectory(parent, "run-")
                                    object : RuntimeHost by base { override val workspaceCache: Path = isolatedCache }
                                }
                                dedicated = RuntimeController(host, runtimeScope)
                                report = RegressionScenarioRunner(RuntimeControllerScenarioHost(requireNotNull(dedicated))).run(review, approval)
                            } catch (cancelled: ScenarioRunCancelledException) {
                                report = cancelled.report
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = safeMessage(failure) }
                            finally { dedicated?.close(); runtimeScope.cancel(); busy = false; trusted = false }
                        }
                    }) { Text("Run reviewed test") }
                    TextButton(enabled = busy, onClick = { job?.cancel() }) { Text("Cancel") }
                }
                if (interactive.isRunning) Text("Stop the interactive Runtime session before starting a regression run.", style = MaterialTheme.typography.bodySmall)
                if (busy) Text("Working within the reviewed time/output limits. Cancellation stops owned IO, processes and previews.", style = MaterialTheme.typography.bodySmall)
            }
        }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        report?.let { result ->
            item {
                HorizontalDivider()
                Text("${result.status}: ${result.evaluatedTestAssertions} evaluated test assertions", style = MaterialTheme.typography.titleSmall)
                Text("${result.finishedAt}\nEvidence: ${result.evidencePath.fileName}\nRaw logs, payloads and secret values are not exported.", style = MaterialTheme.typography.bodySmall)
                if (!result.evidenceStored) Text("Evidence storage failed; this result cannot be compared.", color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (fileActions.canExport) TextButton(enabled = result.evidenceStored, onClick = { fileActions.exportFile(result.evidencePath.toString()) }) { Text("Export evidence") }
                    else if (fileActions.canShare) TextButton(enabled = result.evidenceStored, onClick = { fileActions.share(result.evidencePath.toString()) }) { Text("Share evidence") }
                    TextButton(enabled = result.comparisonEligible && !busy, onClick = { baseline = result; comparison = "Before run selected explicitly." }) { Text("Keep as before") }
                }
                val before = baseline
                if (before != null && before.runId != result.runId) TextButton(enabled = result.comparisonEligible && !busy, onClick = {
                    try {
                        val change = RegressionScenarioComparison.compare(before, result, result.scenarioSha256)
                        comparison = "Same scenario hash verified. ${change.changes.size} assertion verdict changes; reviewed source changed: ${change.sourceChanged}. Only these executed tests are compared."
                    } catch (failure: Exception) { error = safeMessage(failure) }
                }) { Text("Compare selected before / after") }
            }
            items(result.steps, key = { it.id }) { step ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${step.verdict}  ${step.id} · ${step.durationMs} ms", style = MaterialTheme.typography.labelLarge)
                    step.http?.let { Text("HTTP ${it.status ?: "not completed"} · ${it.outcome} · ${it.responseBytes} bytes", style = MaterialTheme.typography.bodySmall) }
                    step.assertions.forEach { assertion -> Text("${assertion.verdict} ${assertion.key}: ${assertion.observed}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        comparison?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
    }
}
