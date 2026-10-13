package dev.ide.android.workbench.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ide.android.workbench.qualitysemantic.SemanticGroupingPanel
import dev.ide.ui.backend.AppLogUi
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiLogLevel
import dev.ide.workbench.quality.ArchiveImportConfig
import dev.ide.workbench.quality.ArchiveWindow
import dev.ide.workbench.quality.ExportFormat
import dev.ide.workbench.quality.ExportResult
import dev.ide.workbench.quality.QualityConfig
import dev.ide.workbench.quality.QualityException
import dev.ide.workbench.quality.QualityReport
import dev.ide.workbench.quality.QualityService
import dev.ide.workbench.quality.ReportKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * On-device triage by default. Optional semantic grouping separately previews and confirms each model request.
 * [onOpenFile] receives an absolute source path and a one-based line, only after root/symlink validation.
 */
@Composable
fun QualityPanel(
    backend: IdeBackend,
    fileActions: FileActions,
    modifier: Modifier = Modifier,
    onOpenFile: ((String, Int) -> Unit)? = null,
) {
    // A project change must not leave a report, pasted log or selected task belonging to the old project.
    key(backend, backend.project.rootPath) {
        QualityPanelContent(backend, fileActions, modifier, onOpenFile)
    }
}

private enum class QualityInput(val label: String, val kind: ReportKind) {
    ARCHIVE("GitHub archive", ReportKind.ARCHIVE), LOGS("App logs", ReportKind.LOGS), PROJECT("Project scan", ReportKind.PROJECT),
}

private const val QUALITY_PASTE_LIMIT = 120_000

@Composable
private fun QualityPanelContent(
    backend: IdeBackend,
    fileActions: FileActions,
    modifier: Modifier,
    onOpenFile: ((String, Int) -> Unit)?,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val service = remember { QualityService() }
    val appLogFlow = remember(backend) { backend.build.appLog }
    val appLog by appLogFlow.collectAsState()
    val projectRoot = backend.project.rootPath
    var input by remember { mutableStateOf(QualityInput.ARCHIVE) }
    var reports by remember { mutableStateOf<Map<ReportKind, QualityReport>>(emptyMap()) }
    var preparedExports by remember { mutableStateOf<Map<ReportKind, ExportResult>>(emptyMap()) }
    var exportFormat by remember { mutableStateOf(ExportFormat.MARKDOWN) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var repository by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf("") }
    var endDate by remember { mutableStateOf("") }
    var recentOnly by remember { mutableStateOf(false) }
    var includeSpecialized by remember { mutableStateOf(false) }
    var specializedRepositories by remember { mutableStateOf("") }
    // Intentionally not rememberSaveable: potentially sensitive log text never goes into saved-state storage.
    var pastedLogs by remember { mutableStateOf("") }
    var pasteWasRedacted by remember { mutableStateOf(false) }
    var pasteWasTruncated by remember { mutableStateOf(false) }

    fun showFailure(action: String, failure: Exception) {
        val detail = if (failure is QualityException) "${failure.code}: ${failure.message.orEmpty()}"
        else failure.message ?: failure.javaClass.simpleName
        error = "$action: ${qualityRedactUiText(detail).take(900)}"
    }

    fun analyze(kind: ReportKind, label: String, operation: () -> QualityReport) {
        if (busy != null || !scope.isActive) return
        busy = label
        error = null
        message = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { operation() }
                reports = reports + (kind to result)
                preparedExports = preparedExports - kind
                input = QualityInput.entries.first { it.kind == kind }
                message = "Local analysis complete. No tests were executed and nothing was uploaded."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                showFailure("Analysis failed; any previous report is unchanged", failure)
            } finally {
                busy = null
            }
        }
    }

    fun scan(path: String, picked: Boolean) {
        analyze(ReportKind.PROJECT, "Inspecting project files on device…") {
            val report = service.scanProject(Paths.get(path))
            if (picked) report.copy(warnings = report.warnings + "Source: a user-selected directory. Android's picker may provide a local copy; source navigation opens that copy, not the original provider document.")
            else report
        }
    }

    val openSource: QualityOpenSource? = if (onOpenFile == null) null else { path, line, root ->
        scope.launch {
            try {
                val resolved = withContext(Dispatchers.IO) { qualityResolveProjectFile(Paths.get(root), path) }
                onOpenFile(resolved.toString(), line.coerceAtLeast(1))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                showFailure("Cannot open source", failure)
            }
        }
    }
    val openUrl: ((String) -> Unit)? = if (!fileActions.canOpenUrl) null else { raw ->
        qualityGitHubUrl(raw)?.let { url ->
            try {
                fileActions.openUrl(url)
            } catch (failure: Exception) {
                showFailure("Cannot open GitHub source", failure)
            }
        }
    }
    val report = reports[input.kind]
    val exported = preparedExports[input.kind]
    val enabled = busy == null

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("heading") {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("App Quality & Reliability Lab", style = MaterialTheme.typography.titleLarge)
                QualityText("Evidence → local triage → real verification. Offline by default; optional semantic grouping requires a separate preview and per-run consent. No automatic fix or external log collection.")
                QualityText("Issue text is untrusted data, never instructions. Redaction is best effort; review private information before export.")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (choice in QualityInput.entries) {
                        FilterChip(
                            selected = input == choice,
                            onClick = { input = choice },
                            label = { Text(choice.label) },
                        )
                    }
                }
            }
        }
        item("input-${input.name}") {
            when (input) {
                QualityInput.ARCHIVE -> QualityCard("Import GitHub evidence") {
                    QualityText("Choose an exported issues/PR JSON file or a ZIP containing JSON. Nothing is fetched from GitHub automatically.")
                    QualityFold("Import scope & optional date window") {
                        OutlinedTextField(
                            value = repository,
                            onValueChange = { repository = qualityRedactUiText(it.take(200)) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Fallback repository · owner/name") },
                            supportingText = { Text("Only needed when records contain no repository or GitHub URL.") },
                            singleLine = true,
                            enabled = enabled,
                        )
                        OutlinedTextField(
                            value = startDate,
                            onValueChange = { startDate = it.filter { char -> char in '0'..'9' || char == '-' }.take(10) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("From date · YYYY-MM-DD (UTC)") },
                            singleLine = true,
                            enabled = enabled,
                        )
                        OutlinedTextField(
                            value = endDate,
                            onValueChange = { endDate = it.filter { char -> char in '0'..'9' || char == '-' }.take(10) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Through date · YYYY-MM-DD (UTC)") },
                            singleLine = true,
                            enabled = enabled,
                        )
                        QualityCheckRow("Include only the recent created-at window", recentOnly, enabled) { recentOnly = it }
                        QualityText("Both dates are optional; absent dates use declared archive metadata when available. No recent window is invented.")
                        QualityCheckRow("Include specialized device/security records in counts", includeSpecialized, enabled) { includeSpecialized = it }
                        QualityText("Specialized text stays withheld. Detection is heuristic and may miss records; review repository scope rather than treating this as an exhaustive filter.")
                        OutlinedTextField(
                            value = specializedRepositories,
                            onValueChange = { specializedRepositories = it.take(4000) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Specialized repositories · one owner/name per line") },
                            supportingText = { Text("Optional: explicitly mark repositories to exclude from the general-app slice. Full totals remain visible.") },
                            minLines = 2,
                            maxLines = 4,
                            enabled = enabled,
                        )
                    }
                    Button(
                        onClick = {
                            try {
                                val specialized = specializedRepositories.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                                require(specialized.all { it.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) }) {
                                    "Enter one owner/repository per line for specialized scope."
                                }
                                val config = qualityArchiveConfig(repository, startDate, endDate, recentOnly, includeSpecialized)
                                    .copy(deviceSecurityRepositories = specialized)
                                busy = "Waiting for a JSON or ZIP file…"
                                error = null
                                fileActions.pickFile(listOf("json", "zip")) { path ->
                                    busy = null
                                    if (path == null) message = "No archive selected."
                                    else analyze(ReportKind.ARCHIVE, "Importing and classifying local evidence…") {
                                        QualityService(QualityConfig(archive = config)).importArchive(Paths.get(path))
                                    }
                                }
                            } catch (failure: Exception) {
                                busy = null
                                showFailure("Cannot import archive", failure)
                            }
                        },
                        enabled = enabled && fileActions.canPickFile,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Choose JSON / ZIP") }
                    if (!fileActions.canPickFile) QualityText("File picking is unavailable in this host.")
                    QualityText("Import is bounded by file, record and text limits. Evidence and categories are derived from your archive, not preloaded research counts.")
                }
                QualityInput.LOGS -> QualityCard("Analyze app logs locally") {
                    QualityText("Current app buffer: ${appLog.lines.size} entries · ${if (appLog.connected) "connected" else "not connected"}")
                    appLog.packageName?.let { QualityText("App: $it") }
                    Button(
                        onClick = {
                            val snapshot = appLogFlow.value
                            analyze(ReportKind.LOGS, "Redacting and grouping the app-log snapshot…") {
                                val captured = qualityCaptureLogSnapshot(snapshot)
                                val result = service.analyzeLogs(captured.text)
                                result.copy(warnings = result.warnings + captured.notes + "Source: an explicit snapshot of the current backend app-log buffer. Later log entries are not included.")
                            }
                        },
                        enabled = enabled && appLog.lines.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Analyze current app-log snapshot") }
                    QualityText("The snapshot is read only on tap. No device-wide logcat, network upload or ongoing capture is started by this Lab.")
                    QualityFold("Or paste redacted logs") {
                        OutlinedTextField(
                            value = pastedLogs,
                            onValueChange = { value ->
                                val bounded = value.take(QUALITY_PASTE_LIMIT)
                                val redacted = qualityRedactUiText(bounded)
                                pastedLogs = redacted
                                pasteWasRedacted = pasteWasRedacted || redacted != bounded
                                pasteWasTruncated = pasteWasTruncated || value.length > QUALITY_PASTE_LIMIT
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Paste logs (known credentials are stripped)") },
                            minLines = 4,
                            maxLines = 9,
                            enabled = enabled,
                        )
                        QualityText("${pastedLogs.length} / $QUALITY_PASTE_LIMIT characters. Remove personal data that pattern-based redaction cannot recognize.")
                        if (pasteWasRedacted) QualityText("Known credential/control patterns were removed before display.")
                        if (pasteWasTruncated) QualityText("Pasted input was truncated at the editor limit; this will be noted in the report.")
                        OutlinedButton(
                            onClick = {
                                val text = pastedLogs
                                val redacted = pasteWasRedacted
                                val truncated = pasteWasTruncated
                                analyze(ReportKind.LOGS, "Grouping pasted logs locally…") {
                                    val result = service.analyzeLogs(text)
                                    result.copy(
                                        logSummary = result.logSummary?.let { it.copy(redacted = it.redacted || redacted) },
                                        warnings = result.warnings + listOfNotNull(
                                            "Source: user-pasted logs; no external logs were collected.",
                                            if (redacted) "Known credential/control patterns were removed by the input field before analysis." else null,
                                            if (truncated) "Pasted input exceeded the $QUALITY_PASTE_LIMIT-character editor limit and was truncated." else null,
                                        ),
                                    )
                                }
                            },
                            enabled = enabled && pastedLogs.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Analyze pasted logs") }
                        TextButton(
                            onClick = { pastedLogs = ""; pasteWasRedacted = false; pasteWasTruncated = false },
                            enabled = enabled && pastedLogs.isNotEmpty(),
                        ) { Text("Clear pasted input") }
                    }
                }
                QualityInput.PROJECT -> QualityCard("Inspect project reliability signals") {
                    QualityText(backend.project.name, color = MaterialTheme.colorScheme.onSurface)
                    QualityText(projectRoot)
                    QualityText("Read-only, bounded checks of files on disk. This does not run tests, modify source, validate a release or establish a pass/fail result.")
                    Button(onClick = { scan(projectRoot, picked = false) }, enabled = enabled && projectRoot.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text("Scan current project")
                    }
                    if (fileActions.canPickDirectory) {
                        OutlinedButton(
                            onClick = {
                                busy = "Waiting for a project directory…"
                                error = null
                                try {
                                    fileActions.pickDirectory { path ->
                                        busy = null
                                        if (path == null) message = "No directory selected." else scan(path, picked = true)
                                    }
                                } catch (failure: Exception) {
                                    busy = null
                                    showFailure("Cannot select directory", failure)
                                }
                            },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Choose another directory") }
                        QualityText("Android may scan a local copy of the selected folder. Real build tasks below always target the current IDE project, not the selected copy.")
                    }
                }
            }
        }
        if (busy != null || error != null || message != null) {
            item("operation-state") {
                QualityCard(if (busy != null) "Working locally" else if (error != null) "Action needs attention" else "Status") {
                    busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); QualityText(it) }
                    error?.let { QualityText(it, color = MaterialTheme.colorScheme.error) }
                    if (error == null) message?.let { QualityText(it) }
                    if (busy == null) TextButton(onClick = { error = null; message = null }) { Text("Dismiss") }
                }
            }
        }
        if (report != null) {
            if (report.kind == ReportKind.ARCHIVE) {
                item("optional-semantic-grouping") { SemanticGroupingPanel(backend, report, enabled) }
            }
            item("summary-${report.kind}") { QualityReportSummary(report) }
            item("export-${report.kind}") {
                QualityCard("Export this report") {
                    QualityText("Prepared files stay in the app's private cache. Save or share only after reviewing evidence and redaction.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (format in ExportFormat.entries) {
                            FilterChip(
                                selected = exportFormat == format,
                                onClick = { exportFormat = format },
                                label = { Text(if (format == ExportFormat.MARKDOWN) "Markdown" else "JSON") },
                                enabled = enabled,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val format = exportFormat
                            busy = "Preparing a private ${if (format == ExportFormat.JSON) "JSON" else "Markdown"} export…"
                            error = null
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        val directory = context.cacheDir.toPath().resolve("exports").resolve("quality")
                                        Files.createDirectories(directory)
                                        val extension = if (format == ExportFormat.JSON) "json" else "md"
                                        val target = directory.resolve("quality-${report.kind.name.lowercase()}-${UUID.randomUUID()}.$extension")
                                        service.export(report, target, format)
                                    }
                                    preparedExports = preparedExports + (report.kind to result)
                                    message = "Export prepared privately. Nothing has been saved externally or shared."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    showFailure("Export failed", failure)
                                } finally {
                                    busy = null
                                }
                            }
                        },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Prepare ${if (exportFormat == ExportFormat.JSON) "JSON" else "Markdown"} export") }
                    exported?.let { result ->
                        QualityText("${result.path.fileName} · ${result.bytesWritten} bytes")
                        if (fileActions.canExport) OutlinedButton(
                            onClick = {
                                try { fileActions.exportFile(result.path.toString()) }
                                catch (failure: Exception) { showFailure("Cannot open Save As", failure) }
                            },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Save a copy…") }
                        if (fileActions.canShare) OutlinedButton(
                            onClick = {
                                try { fileActions.share(result.path.toString()) }
                                catch (failure: Exception) { showFailure("Cannot open share sheet", failure) }
                            },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Share prepared report…") }
                        if (!fileActions.canExport && !fileActions.canShare) QualityText("This host cannot save or share the private export.")
                    }
                    TextButton(
                        onClick = { reports = reports - report.kind; preparedExports = preparedExports - report.kind },
                        enabled = enabled,
                    ) { Text("Clear this report from view") }
                }
            }
            item("details-${report.kind}-${report.generatedAt}") {
                QualityReportDetails(report, openUrl, openSource)
            }
        }
        item("real-build-checks") { QualityBuildTasks(backend, openSource) }
        item("footer") {
            QualityText(
                "Offline analysis does not contact a server. Optional semantic grouping sends only the separately previewed fields after per-run confirmation. Opening GitHub, saving/sharing an export, or executing trusted build scripts is also a separate explicit action. Prepared exports may remain in private cache; clearing a view does not delete saved copies.",
                Modifier.padding(bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun QualityCheckRow(label: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChecked, enabled = enabled)
        QualityText(label, Modifier.weight(1f))
    }
}

internal fun qualityArchiveConfig(
    repository: String,
    from: String,
    through: String,
    recentOnly: Boolean,
    includeSpecialized: Boolean,
): ArchiveImportConfig {
    val repo = repository.trim().takeIf { it.isNotEmpty() }
    require(repo == null || Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repo)) { "Use a repository in owner/name form, not a URL or token." }
    val hasStart = from.isNotBlank()
    val hasEnd = through.isNotBlank()
    require(hasStart == hasEnd) { "Set both dates, or leave both empty to use archive metadata." }
    val window = if (hasStart) {
        val dates = try { LocalDate.parse(from.trim()) to LocalDate.parse(through.trim()) }
        catch (_: Exception) { throw IllegalArgumentException("Use valid UTC dates in YYYY-MM-DD form.") }
        require(!dates.second.isBefore(dates.first)) { "The through date must be on or after the from date." }
        ArchiveWindow(dates.first.atStartOfDay().toInstant(ZoneOffset.UTC), dates.second.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusNanos(1))
    } else null
    return ArchiveImportConfig(defaultRepository = repo, recentWindow = window, recentOnly = recentOnly, includeDeviceSecuritySpecific = includeSpecialized)
}

private data class QualityLogCapture(val text: String, val notes: List<String>)

/** Build a bounded newest-entry snapshot on IO without joining the backend's entire buffer first. */
private fun qualityCaptureLogSnapshot(snapshot: AppLogUi): QualityLogCapture {
    val entries = ArrayList<String>()
    var characters = 0
    var clipped = 0
    for (line in snapshot.lines.asReversed()) {
        if (entries.size == 20_000) break
        val level = when (line.level) { UiLogLevel.Debug -> "D"; UiLogLevel.Info -> "I"; UiLogLevel.Warn -> "W"; UiLogLevel.Error -> "E" }
        val prefix = "${line.timeLabel.take(40)} ${line.pid} ${line.tid} $level ${line.tag.take(120)}: "
        val bounded = line.message.take(4_096)
        val text = prefix + bounded
        if (characters + text.length + 1 > 1_000_000) break
        if (bounded.length != line.message.length) clipped += 1
        entries += text
        characters += text.length + 1
    }
    val omitted = snapshot.lines.size - entries.size
    return QualityLogCapture(
        entries.asReversed().joinToString("\n"),
        listOfNotNull(
            if (omitted > 0) "$omitted older app-log entries were omitted to keep the snapshot bounded." else null,
            if (clipped > 0) "$clipped app-log entries were clipped at 4096 message characters before analysis." else null,
        ),
    )
}
