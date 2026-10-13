@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ide.android.workbench.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ide.android.BuildConfig
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.workbench.runtime.RuntimeController
import dev.ide.workbench.runtime.RuntimeKind
import dev.ide.workbench.runtime.RuntimePhase
import dev.ide.workbench.runtime.RuntimeSessionState
import dev.ide.workbench.runtime.RuntimeWorkspaceMode

/** Android screen over an activity-owned controller. Merely entering the screen never runs project code. */
@Composable
fun RuntimePanel(
    controller: RuntimeController,
    backend: IdeBackend,
    fileActions: FileActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val form = remember(controller) { RuntimePanelState(controller, context, scope) }
    val session by controller.state.collectAsState()
    val projectEpoch by backend.projects.projectEpoch.collectAsState()
    val project = remember(backend, projectEpoch) { backend.project }
    var choosingTarget by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var showSamples by rememberSaveable { mutableStateOf(false) }
    var showAvailability by rememberSaveable { mutableStateOf(true) }
    var stream by rememberSaveable { mutableStateOf("Combined") }
    var showSyncConfirmation by remember { mutableStateOf(false) }
    var externalPreview by remember { mutableStateOf<String?>(null) }
    val developmentDownloads = BuildConfig.NATIVE_TOOLCHAIN_DOWNLOADS_ENABLED

    LaunchedEffect(controller, project.rootPath) { form.attachProject(project.rootPath) }
    LaunchedEffect(controller) { form.probe() }
    LaunchedEffect(session.sessionId) { stream = "Combined"; showSyncConfirmation = false; externalPreview = null }
    LaunchedEffect(session.phase) { if (session.phase != RuntimePhase.RUNNING) externalPreview = null }

    val editable = form.busy == null && !session.isRunning
    val target = form.selectedTarget
    val probe = form.report?.runtimes?.firstOrNull { it.kind == target?.kind }
    val unavailable = target != null && target.kind != RuntimeKind.STATIC && probe?.available == false
    val currentSession = runtimeSamePath(session.projectRoot, form.root)
    val preview = if (session.phase == RuntimePhase.RUNNING && currentSession) {
        session.previewUrl?.let(RuntimePreviewAddress::parse)
    } else null

    Column(modifier.fillMaxSize().imePadding()) {
        // A separate, wrapping bar keeps Stop available while logs/settings scroll or the IME is open.
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(runtimePhaseLabel(session), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("runtime-status"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = { form.requestRun() },
                        enabled = editable && form.loaded && target != null && !unavailable,
                        modifier = Modifier.testTag("runtime-start"),
                    ) { Text(if (target?.kind == RuntimeKind.STATIC) "Start preview" else "Start") }
                    OutlinedButton(
                        onClick = form::stop,
                        enabled = session.isRunning && session.phase != RuntimePhase.STOPPING,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("runtime-stop"),
                    ) { Text(if (session.phase == RuntimePhase.STOPPING) "Stopping…" else "Stop") }
                    TextButton(onClick = form::save, enabled = editable && form.loaded) { Text("Save config") }
                }
                if (form.busy != null) Text(form.busy.orEmpty(), style = MaterialTheme.typography.bodySmall)
                if (form.busy != null || session.phase == RuntimePhase.PREPARING) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        HorizontalDivider()
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("runtime-content"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            item(key = "notices") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    form.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("runtime-error")) }
                    form.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    session.error?.let { Text("Session error: $it", color = MaterialTheme.colorScheme.error) }
                    if (form.error != null || form.message != null) TextButton(onClick = form::clearNotice) { Text("Dismiss message") }
                    Text(
                        "Run only code you trust. Processes use this app's Android UID and permissions, can read or modify app-accessible files, and are not sandboxed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (session.projectRoot != null && (session.acceptsInput || session.phase == RuntimePhase.RUNNING)) {
                item(key = "live-input") { RuntimeInput(form, session) }
            }
            item(key = "workspace") {
                RuntimeSection("Workspace") {
                    Text(form.source.ifBlank { "No workspace selected" }, style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(form.root?.toString() ?: "Choose a folder or create an offline sample.", style = MaterialTheme.typography.bodyMedium) }
                    if (form.source.startsWith("Selected folder")) {
                        Text("The Android folder picker imports a local copy. Runtime sync targets that local copy, not the original document-provider folder; it does not write back through SAF.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Runs read saved files. Save editor buffers before starting; opening this screen only detects targets and probes bundled runtimes.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            try {
                                fileActions.pickDirectory { path -> if (path != null) form.select(path, "Selected folder · local import") }
                            } catch (failure: Exception) { form.showError(failure.message ?: "The folder picker is unavailable.") }
                        }, enabled = editable && fileActions.canPickDirectory) { Text("Choose folder") }
                        if (project.rootPath.isNotBlank()) TextButton(onClick = { form.select(project.rootPath, "Current project") }, enabled = editable) { Text("Use current project") }
                        TextButton(onClick = form::refresh, enabled = editable && form.root != null) { Text("Rescan targets") }
                    }
                    if (!fileActions.canPickDirectory) Text("This host does not provide a folder picker. Use the current project or a sample.", style = MaterialTheme.typography.bodySmall)
                    if (form.trusted) {
                        Text("Execution consent saved for this workspace.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = form::forgetTrust, enabled = editable) { Text("Forget workspace trust") }
                    }
                    TextButton(onClick = { showSamples = !showSamples }) { Text(if (showSamples) "Hide offline samples" else "Create an offline sample") }
                    if (showSamples) {
                        Text("Samples create new child folders in app-private storage. They are loaded here, not automatically run or opened in an editor tab.", style = MaterialTheme.typography.bodySmall)
                        form.samples.forEach { sample ->
                            OutlinedButton(onClick = { form.seed(sample) }, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(sample.label, fontWeight = FontWeight.SemiBold)
                                    Text(sample.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            item(key = "run-config") {
                RuntimeSection("Run configuration") {
                    OutlinedButton(onClick = { choosingTarget = true }, enabled = editable && form.targets.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("runtime-target")) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(target?.label ?: "No runnable target detected", fontWeight = FontWeight.SemiBold)
                            target?.let { Text("${runtimeKindLabel(it.kind)} · ${it.relativePath}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (form.loaded && form.targets.isEmpty()) Text("No package.json scripts, JavaScript (.js/.mjs/.cjs), Python (.py), shell (.sh), or static HTML entry points were found in the scan. Add a saved entry file and rescan; discovery is bounded, not recursive without limit.", style = MaterialTheme.typography.bodySmall)
                    if (unavailable) Text("${runtimeKindLabel(requireNotNull(target).kind)} is unavailable: ${probe.error ?: "probe failed"}. Choose another target or recheck availability.", color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(
                        value = form.arguments, onValueChange = { form.arguments = it },
                        label = { Text("Arguments") },
                        supportingText = { Text("Space-separated; quote values containing spaces. No shell expansion. Saved per workspace.") },
                        enabled = editable && form.loaded, minLines = 1, maxLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("runtime-arguments"),
                    )
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced configuration" else "Working directory, environment & copy mode") }
                    if (advanced) {
                        OutlinedTextField(form.workingDirectory, { form.workingDirectory = it }, label = { Text("Working directory (project-relative)") }, enabled = editable && form.loaded, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(form.previewPort, { form.previewPort = it }, label = { Text("Preview port (optional)") }, supportingText = { Text("1024–65535. Otherwise detect a loopback URL from output. Setting a port does not start a server.") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = editable && form.loaded, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(form.environment, { form.environment = it }, label = { Text("Environment (NAME=value, one per line)") }, supportingText = { Text("Saved as plain text in the workspace. Do not put credentials here. Reserved runtime variables are rejected by the engine.") }, minLines = 2, maxLines = 5, enabled = editable && form.loaded, modifier = Modifier.fillMaxWidth())
                        Text("Workspace mode", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RuntimeWorkspaceMode.entries.forEach { mode ->
                                FilterChip(selected = form.workspaceMode == mode, onClick = { form.workspaceMode = mode }, enabled = editable && form.loaded, label = { Text(when (mode) { RuntimeWorkspaceMode.AUTO -> "Auto"; RuntimeWorkspaceMode.DIRECT -> "Direct"; RuntimeWorkspaceMode.INTERNAL_COPY -> "Internal copy" }) })
                            }
                        }
                        Text("Auto may mirror external storage into app-private storage for executable and symlink semantics. Direct runs write to the selected folder and can fail on external storage. An internal copy is not the editor's live files; sync is explicit after stopping.", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { form.requestRun(install = true) }, enabled = editable && form.loaded && form.hasPackageManifest, modifier = Modifier.testTag("runtime-install")) { Text("Install npm dependencies…") }
                    Text(if (developmentDownloads) "Developer distribution: explicit npm install may download untrusted packages. Lifecycle scripts stay disabled; native packages may fail. No automatic install, pip installer, or promised Vite/Next.js compatibility." else "APK-only distribution: dependency installation is restricted to the engine's audited policy. Arbitrary npm/pip downloads are not available; unsupported dependencies fail explicitly.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (session.projectRoot != null) {
                item(key = "session") {
                    RuntimeSection("Session & files") {
                        Text(session.target?.label ?: "Preparing target", style = MaterialTheme.typography.labelLarge)
                        if (!currentSession) Text("These results belong to a previous workspace: ${session.projectRoot}", style = MaterialTheme.typography.bodySmall)
                        SelectionContainer { Text("Execution folder: ${session.workspacePath ?: "not prepared"}", style = MaterialTheme.typography.bodySmall) }
                        if (session.workspaceCopy) {
                            Text("Running from a separate internal copy, NOT the editor's live files. Sync new outputs only creates files missing from the source. Existing source files are never replaced: runtime edits to them remain in this copy and require a manual merge. Saved source files are copied inward on the next run only when safe.", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { showSyncConfirmation = true }, enabled = editable && currentSession, modifier = Modifier.testTag("runtime-sync")) { Text("Sync new outputs…") }
                            Text("Dependencies, runtime caches, and deletions are not synced. Refresh or reopen the project to see new files; editor buffers are not changed automatically.", style = MaterialTheme.typography.bodySmall)
                        } else if (session.workspacePath != null) {
                            Text("Direct workspace: process writes change saved files at this path. Unsaved editor buffers are separate; reopen affected files before editing further.", style = MaterialTheme.typography.bodySmall)
                        }
                        form.syncReport?.let { report ->
                            Text("Sync result: ${report.copied} new file(s) copied to the selected source workspace.", color = MaterialTheme.colorScheme.primary)
                            if (report.conflicts.isEmpty()) Text("No conflicts reported. This does not sync an imported SAF folder back to its original provider.", style = MaterialTheme.typography.bodySmall)
                            else {
                                Text("${report.conflicts.size} existing-file conflict(s) NOT overwritten. Manually merge these files from the runtime copy:", color = MaterialTheme.colorScheme.error)
                                SelectionContainer { Text(report.conflicts.take(40).joinToString("\n"), style = MaterialTheme.typography.bodySmall) }
                                if (report.conflicts.size > 40) Text("Showing the first 40 conflicts.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            item(key = "output") {
                RuntimeSection("Output") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Combined", "Stdout", "Stderr").forEach { name -> FilterChip(selected = stream == name, onClick = { stream = name }, label = { Text(name) }) }
                    }
                    Text("${runtimePhaseLabel(session)} · bounded session output; this view shows at most the latest 300 lines / 32 KiB, wrapped for the screen.", style = MaterialTheme.typography.bodySmall)
                    val raw = when (stream) { "Stdout" -> session.stdout; "Stderr" -> session.stderr; else -> session.transcript }
                    val visible = remember(raw) { runtimeVisibleOutput(raw) }
                    if ((stream == "Stdout" && session.stdoutTruncated) || (stream == "Stderr" && session.stderrTruncated)) Text("Older output was removed from the engine's bounded buffer.", style = MaterialTheme.typography.bodySmall)
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
                        SelectionContainer {
                            Text(
                                visible.ifEmpty { if (session.isRunning) "Waiting for output…" else "No output yet. Choose a target and press Start." },
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                color = if (stream == "Stderr") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                softWrap = true,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(12.dp).testTag("runtime-output"),
                            )
                        }
                    }
                }
            }
            item(key = "preview") {
                RuntimeSection("Local preview") {
                    if (preview != null && session.sessionId != null) {
                        SelectionContainer { Text(preview.url, style = MaterialTheme.typography.bodyMedium) }
                        Text("JavaScript is enabled. Only this live session's loopback origin is allowed in the embedded view; file/content access, popups, downloads, permissions and external navigation are blocked. This is a preview restriction, not a sandbox for the process.", style = MaterialTheme.typography.bodySmall)
                        if (fileActions.canOpenUrl) OutlinedButton(onClick = { externalPreview = preview.url }) { Text("Open in external browser…") }
                        key(session.sessionId, preview.url) { RuntimePreview(preview, requireNotNull(session.sessionId)) }
                    } else {
                        Text(if (session.phase == RuntimePhase.RUNNING && session.previewUrl != null) "The reported preview URL is not an allowed loopback HTTP(S) address for the current workspace." else "Start a static target or a server that reports a loopback URL. The preview is cleared and its WebView destroyed when the session stops.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item(key = "availability") {
                RuntimeSection("Runtime availability") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showAvailability = !showAvailability }) { Text(if (showAvailability) "Hide details" else "Show versions & limitations") }
                        OutlinedButton(onClick = form::probe, enabled = !form.probing && !session.isRunning) { Text(if (form.probing) "Probing…" else "Recheck runtimes") }
                    }
                    if (form.probing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    form.probeError?.let { Text("Availability check failed: $it", color = MaterialTheme.colorScheme.error) }
                    if (showAvailability) {
                        form.report?.let { report ->
                            Text("${report.packId} · ${report.abi}", style = MaterialTheme.typography.labelLarge)
                            listOf(RuntimeKind.NODE, RuntimeKind.NPM, RuntimeKind.PYTHON, RuntimeKind.SHELL).forEach { kind ->
                                val result = report.runtimes.firstOrNull { it.kind == kind }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${runtimeKindLabel(kind)} · ${when (result?.available) { true -> "Available"; false -> "Unavailable"; null -> "Not reported" }}", fontWeight = FontWeight.SemiBold)
                                    Text(result?.version?.takeIf { it.isNotBlank() } ?: "No version output.", style = MaterialTheme.typography.bodySmall)
                                    result?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                            report.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        } ?: Text(if (form.probing) "Checking installed binaries; availability is not assumed." else "Native runtime availability is unknown. Use Recheck for actual versions and errors.", style = MaterialTheme.typography.bodySmall)
                        Text("Static preview is served in-app without Node or Python. Shell support is the bundled POSIX shell, not a Linux distribution or full interactive terminal. Bun, Cargo, arbitrary Linux executables, native npm/pip modules, and full desktop toolchain compatibility are not promised.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (choosingTarget) AlertDialog(
        onDismissRequest = { choosingTarget = false }, title = { Text("Choose a run target") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(form.targets, key = { it.id }) { option ->
                    OutlinedButton(onClick = { form.targetId = option.id; choosingTarget = false }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(option.label, fontWeight = FontWeight.SemiBold)
                            Text("${runtimeKindLabel(option.kind)} · ${option.relativePath}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { choosingTarget = false }) { Text("Cancel") } },
    )
    form.pending?.let { request ->
        AlertDialog(
            onDismissRequest = form::dismissConsent,
            title = { Text(if (request.install) "Install project dependencies?" else "Trust this workspace to run?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(request.root.toString(), style = MaterialTheme.typography.bodySmall)
                    Text("Project code runs as this app's Android UID, with access to its permitted files and network. It is NOT isolated or sandboxed. Run only code you have reviewed and trust.")
                    if (request.install) Text(if (developmentDownloads) "This developer build may download untrusted npm packages into the execution workspace. Lifecycle scripts are disabled, but dependencies still contain executable code; native modules may fail. The engine controls what is allowed. This does not install pip packages or guarantee Vite/Next.js support." else "This APK-only build applies a strict audited dependency policy and may refuse this project. No arbitrary npm/pip downloads are allowed. Lifecycle scripts remain disabled; the engine reports any refusal explicitly.")
                    Text(if (form.trusted) "Workspace trust is already saved. Dependency installation always requires this separate confirmation." else "Your execution consent is saved for this workspace, so ordinary runs do not ask again. You can revoke it with Forget workspace trust.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = form::confirm) { Text(if (request.install) "Confirm install" else "Trust & run") } },
            dismissButton = { TextButton(onClick = form::dismissConsent) { Text("Cancel") } },
        )
    }
    if (showSyncConfirmation) AlertDialog(
        onDismissRequest = { showSyncConfirmation = false }, title = { Text("Sync new runtime outputs?") },
        text = { Text("Create only new output files in ${session.projectRoot}. Existing source files are NEVER replaced. Runtime modifications to existing files stay in the internal copy and are reported as conflicts requiring manual merge. Dependencies and deletions are not synced. Imported SAF folders are not written back to their original provider.") },
        confirmButton = { TextButton(onClick = { showSyncConfirmation = false; form.sync() }, enabled = !session.isRunning && currentSession) { Text("Sync new outputs") } },
        dismissButton = { TextButton(onClick = { showSyncConfirmation = false }) { Text("Cancel") } },
    )
    externalPreview?.let { url ->
        AlertDialog(
            onDismissRequest = { externalPreview = null }, title = { Text("Open external browser?") },
            text = { Text("Open $url outside the restricted embedded preview? The browser has its own cookies, permissions and navigation policy. It can remain open after this runtime session stops.") },
            confirmButton = { TextButton(onClick = {
                externalPreview = null
                if (preview?.url == url && session.phase == RuntimePhase.RUNNING) {
                    try { fileActions.openUrl(url) } catch (failure: Exception) { form.showError(failure.message ?: "No external browser is available.") }
                }
            }) { Text("Open browser") } },
            dismissButton = { TextButton(onClick = { externalPreview = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RuntimeInput(form: RuntimePanelState, session: RuntimeSessionState) {
    RuntimeSection("Session input") {
        if (session.acceptsInput) {
            OutlinedTextField(
                form.stdin, { form.stdin = it }, label = { Text("Standard input") },
                supportingText = { Text("Send appends one newline. EOF closes stdin for this session. No terminal emulation or password masking.") },
                minLines = 1, maxLines = 4, modifier = Modifier.fillMaxWidth().testTag("runtime-stdin"),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = form::sendInput, modifier = Modifier.testTag("runtime-send")) { Text("Send line") }
                OutlinedButton(onClick = form::closeInput, modifier = Modifier.testTag("runtime-eof")) { Text("Send EOF") }
            }
        } else Text("This session's standard input is closed or unavailable.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RuntimeSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

private fun runtimeKindLabel(kind: RuntimeKind): String = when (kind) {
    RuntimeKind.NODE -> "Node.js"
    RuntimeKind.NPM -> "npm"
    RuntimeKind.PYTHON -> "Python"
    RuntimeKind.SHELL -> "POSIX shell"
    RuntimeKind.STATIC -> "Static web"
}

private fun runtimePhaseLabel(state: RuntimeSessionState): String {
    val phase = when (state.phase) {
        RuntimePhase.IDLE -> "Ready · no session started"
        RuntimePhase.PREPARING -> "Preparing"
        RuntimePhase.RUNNING -> "Running"
        RuntimePhase.STOPPING -> "Stopping"
        RuntimePhase.FINISHED -> "Stopped / finished"
        RuntimePhase.FAILED -> "Failed"
    }
    return state.exitCode?.let { "$phase · exit code $it" } ?: phase
}
