package dev.ide.android.workbench.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.RunTaskOption
import dev.ide.ui.backend.UiSeverity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun QualityBuildTasks(backend: IdeBackend, onOpenSource: QualityOpenSource?) {
    val build by backend.build.buildState.collectAsState()
    val root = backend.project.rootPath
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var tasks by remember { mutableStateOf<List<RunTaskOption>>(emptyList()) }
    var supported by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var selectedKind by remember { mutableStateOf(QualityTaskKind.LINT) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<RunTaskOption?>(null) }
    var requesting by remember { mutableStateOf(false) }
    var requestedId by remember { mutableStateOf<String?>(null) }
    var requestNumber by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(backend, root, refresh) {
        loading = true
        try {
            val result = withContext(Dispatchers.IO) {
                val canBuild = backend.build.supported()
                canBuild to if (canBuild) backend.build.runTasks().filter { qualityTaskKind(it.id) != null }.distinctBy { it.id } else emptyList()
            }
            supported = result.first
            tasks = result.second
            if (tasks.none { it.id == selectedId }) selectedId = null
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            tasks = emptyList()
            error = "Task discovery failed: ${qualityRedactUiText(failure.message ?: failure.javaClass.simpleName)}"
        } finally {
            loading = false
        }
    }

    val running = build.status == RunStatus.Running
    val options = remember(tasks, selectedKind) { tasks.filter { qualityTaskKind(it.id) == selectedKind } }
    val selected = options.firstOrNull { it.id == selectedId }
    QualityCard("Run real project checks") {
        QualityText("Separate from static analysis: these are real tasks supplied by the current project's build backend.")
        QualityText(
            "Native in-process test engine: unsupported. No device/OS test matrix is simulated. " +
                "Only listed backend test tasks (for example Gradle) can execute.",
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (kind in QualityTaskKind.entries) {
                FilterChip(
                    selected = selectedKind == kind,
                    onClick = { selectedKind = kind; selectedId = null },
                    label = { Text(kind.label) },
                )
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            !loading && !supported -> QualityText("This host does not support build execution.")
            !loading && options.isEmpty() -> QualityText(
                "${selectedKind.label} is not offered by this backend. No substitute task will run. " +
                    "Install/launch and the legacy Gradle assemble action are intentionally hidden.",
            )
            options.isNotEmpty() -> QualityFold("Choose ${selectedKind.label} task (${options.size})", initiallyExpanded = true) {
                QualityPagedList(options, 5, selectedKind) { task ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        RadioButton(
                            selected = task.id == selectedId,
                            onClick = { selectedId = task.id },
                            enabled = !running && !requesting,
                        )
                        Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            QualityText(task.label, color = MaterialTheme.colorScheme.onSurface)
                            QualityText(task.id)
                        }
                    }
                }
            }
        }
        Button(
            onClick = { confirm = selected },
            enabled = selected != null && supported && !loading && !running && !requesting,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (requesting) "Requesting task…" else "Run selected ${selectedKind.label.lowercase()} task") }
        TextButton(onClick = { refresh += 1 }, enabled = !loading && !requesting && !running) { Text("Refresh available tasks") }
        error?.let { QualityText(it, color = MaterialTheme.colorScheme.error) }
        requestedId?.let {
            QualityText("Last requested here: $it")
            QualityText("A request is not proof of execution. The live status below is backend-wide and may include runs started elsewhere.")
        }
        HorizontalDivider()
        val status = when (build.status) {
            RunStatus.Idle -> "Backend idle · no execution result"
            RunStatus.Running -> "Backend task running"
            RunStatus.Succeeded -> "Backend task completed · not an app-quality pass"
            RunStatus.Failed -> "Backend task failed or stopped · inspect output"
        }
        QualityText(status, style = MaterialTheme.typography.titleSmall, color = if (build.status == RunStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (build.moduleName.isNotBlank()) QualityText("${build.moduleName} · ${build.elapsedMs / 1_000}s")
        build.banner?.let { QualityText(it) }
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { backend.build.stopBuild() }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = "Could not stop the build: ${qualityRedactUiText(failure.message.orEmpty())}"
                        }
                    }
                },
            ) { Text("Stop active build") }
        }
        if (build.steps.isNotEmpty()) {
            QualityFold("Backend steps (${build.steps.size})") {
                QualityPagedList(build.steps, 10, requestNumber) { step ->
                    QualityMetric(step.name, qualityEnumLabel(step.status.name))
                }
                QualityText("Skipped, no-source and up-to-date steps are not newly executed tests.")
            }
        }
        if (build.diagnostics.isNotEmpty()) {
            val errors = build.diagnostics.count { it.severity == UiSeverity.Error }
            QualityText("${build.diagnostics.size} diagnostics · $errors errors")
            QualityFold("Build diagnostics", initiallyExpanded = build.status == RunStatus.Failed) {
                QualityPagedList(build.diagnostics, 6, requestNumber) { diagnostic ->
                    QualityText("${diagnostic.severity.name} · ${diagnostic.source}", color = if (diagnostic.severity == UiSeverity.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    QualityText(diagnostic.message, color = MaterialTheme.colorScheme.onSurface)
                    diagnostic.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                        QualityFold("Diagnostic detail") { QualityEvidenceText(detail) }
                    }
                    diagnostic.file?.let { file ->
                        QualityText("$file:${diagnostic.line.coerceAtLeast(1)}")
                        if (onOpenSource != null) TextButton(onClick = { onOpenSource(file, diagnostic.line.coerceAtLeast(1), root) }) { Text("Open diagnostic source") }
                    }
                    HorizontalDivider()
                }
            }
        }
        QualityFold("Build log (${build.log.size} lines)") {
            QualityText("Known credential patterns are redacted for display. Build output is not uploaded or added to exports automatically.")
            QualityPagedList(build.log, 25, requestNumber, "No build output yet.", newestFirstPage = true) { line ->
                QualityEvidenceText("${line.timeLabel} ${line.level.name} ${line.task?.let { "[$it] " }.orEmpty()}${line.message}", maxChars = 4_096)
            }
        }
    }

    confirm?.let { task ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Execute this backend task?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QualityText(task.label, color = MaterialTheme.colorScheme.onSurface)
                    QualityText(task.id)
                    QualityText("Only this selected task ID will be requested. The Lab never chooses a default Run/Install task.")
                    QualityText("Build scripts and plugins are executable project code: they can download dependencies, use signing configuration or have their own side effects. Run only a project you trust.")
                    QualityText("Static findings and suggested checklists will remain separate from actual backend results.")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !running && !requesting,
                    onClick = {
                        confirm = null
                        requesting = true
                        error = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    check(backend.build.supported()) { "Build execution is not supported by this host." }
                                    check(backend.build.buildState.value.status != RunStatus.Running) { "Another build is already running." }
                                    check(backend.project.rootPath == root) { "The active project changed. Refresh the task list." }
                                    check(qualityTaskKind(task.id) != null && backend.build.runTasks().any { it.id == task.id }) {
                                        "This task is no longer offered by the backend. Refresh the task list."
                                    }
                                    backend.build.runTask(task.id)
                                }
                                requestedId = task.id
                                requestNumber += 1
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = "Task request failed: ${qualityRedactUiText(failure.message ?: failure.javaClass.simpleName)}"
                            } finally {
                                requesting = false
                            }
                        }
                    },
                ) { Text("Run task") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
