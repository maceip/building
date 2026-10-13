package dev.ide.android.workbench.webapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.UiKeystore
import dev.ide.workbench.webapp.StaticWebAppPackager
import dev.ide.workbench.webapp.WebAppPackageRequest
import dev.ide.workbench.webapp.WebAppPackageResult
import dev.ide.workbench.webapp.WebAppPackageReview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths

/** Compact runtime-header entry. Only the dialog scrolls; the host can weight its RuntimePanel below. */
@Composable
fun WebAppPackagePanel(backend: IdeBackend, fileActions: FileActions, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Offline web → Android", Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }) { Text("Package / build") }
    }
    if (expanded) WebAppPackageDialog(backend, fileActions) { expanded = false }
}

@Composable
private fun WebAppPackageDialog(backend: IdeBackend, fileActions: FileActions, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val packager = remember { StaticWebAppPackager() }
    val epoch by backend.projects.projectEpoch.collectAsState()
    val build by backend.build.buildState.collectAsState()
    var workspace by remember { mutableStateOf(backend.project.rootPath) }
    var staticRoot by remember { mutableStateOf("dist") }
    var outputParent by remember { mutableStateOf(backend.projects.projectsRootPath().orEmpty()) }
    var projectName by remember { mutableStateOf("MyWebAndroid") }
    var applicationId by remember { mutableStateOf("com.example.webapp") }
    var label by remember { mutableStateOf("My web app") }
    var versionCode by remember { mutableStateOf("1") }
    var versionName by remember { mutableStateOf("1.0") }
    var spa by remember { mutableStateOf(false) }
    var external by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<WebAppPackageReview?>(null) }
    var result by remember { mutableStateOf<WebAppPackageResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var exclusions by remember { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf(false) }
    var keys by remember { mutableStateOf<List<UiKeystore>>(emptyList()) }
    var assignedKey by remember { mutableStateOf<String?>(null) }
    var keyRefresh by remember { mutableIntStateOf(0) }
    var debugReady by remember { mutableStateOf(false) }
    var bundleReady by remember { mutableStateOf(false) }
    val activeRoot = backend.project.rootPath
    val activeResult = result?.projectRoot?.toString() == activeRoot
    val idle = !busy && build.status != RunStatus.Running

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { notice = error.message ?: "The operation could not be completed." }
            finally { busy = false }
        }
    }

    LaunchedEffect(epoch, activeRoot) {
        if (activeRoot.isNotBlank()) {
            val found = withContext(Dispatchers.IO) { packager.generatedProject(Paths.get(activeRoot)) }
            if (found != null) result = found
        }
    }
    LaunchedEffect(epoch, activeResult, keyRefresh) {
        keys = emptyList()
        assignedKey = null
        if (activeResult) {
            try {
                val assignments = backend.signing.signingAssignments(WebAppPackageResult.MODULE_NAME)
                keys = assignments?.keystores.orEmpty().filter(::usableReleaseKey)
                assignedKey = assignments?.assignments?.firstOrNull { it.buildType == "release" }?.keystoreId
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { notice = "Cannot read signing assignments: ${error.message}" }
        }
    }
    LaunchedEffect(result, build.status) {
        debugReady = false
        bundleReady = false
        result?.let { generated ->
            withContext(Dispatchers.IO) {
                // Existence is not certificate verification; label these as existing artifacts, not verified releases.
                debugReady = regularArtifact(generated.projectRoot, generated.debugApk)
                bundleReady = regularArtifact(generated.projectRoot, generated.releaseBundle)
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(12.dp).widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Package a static web app", Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = dismiss, enabled = !busy) { Text("Close") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                HorizontalDivider()
                LazyColumn(
                    Modifier.weight(1f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            "Offline static output only. Build/export your HTML, CSS and JavaScript first. " +
                                "No Next.js SSR, Node/Python server, remote API, CDN, login service or native plugin runs in this app. " +
                                "The wrapper has no Internet permission, JS bridge or file access. No scripts run during packaging.",
                            Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    notice?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
                    val generated = result
                    if (generated != null) {
                        item {
                            Text("Native Android project", style = MaterialTheme.typography.titleSmall)
                            Text(generated.projectRoot.toString(), style = MaterialTheme.typography.bodySmall)
                            Text("${generated.assetCount} assets · ${formatBytes(generated.assetBytes)} · source files unchanged")
                            Text("Source: ${generated.sourceStaticDirectory}", style = MaterialTheme.typography.bodySmall)
                            Text("Asset-list SHA-256: ${generated.assetManifestSha256}", style = MaterialTheme.typography.bodySmall)
                        }
                        item {
                            if (!activeResult) {
                                Button(onClick = { perform {
                                    check(backend.projects.openProject(generated.projectRoot.toString())) { "Could not open the generated project." }
                                    notice = "Opened the new project. The original source project is unchanged."
                                } }, enabled = idle) { Text("Open generated project in IDE") }
                                Text("Opening explicitly switches the active IDE project. Creating the wrapper did not.", style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("This generated project is now active in the IDE.")
                                OutlinedButton(onClick = { perform {
                                    val tasks = backend.build.runTasks()
                                    val task = tasks.firstOrNull { it.id == WebAppPackageResult.DEBUG_RUN_TASK }
                                        ?: tasks.firstOrNull { it.id == WebAppPackageResult.DEBUG_ASSEMBLE_TASK }
                                        ?: error("Native Android debug task is unavailable. Check the installed SDK/toolchain.")
                                    check(backend.build.supported()) { "This backend cannot build Android apps." }
                                    backend.build.runTask(task.id)
                                    notice = "Started ${task.label}. Read the existing IDE build console; this is not a completed APK claim."
                                } }, enabled = idle) { Text("Build / run debug APK") }
                            }
                        }
                        if (activeResult) {
                            item {
                                Text("Release signing", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Create/import your own upload key in IDE Settings → Signing, then select it below. " +
                                        "No production key is generated here. The generic IDE can debug-sign a release when no key is assigned; " +
                                        "this action refuses that default. Verify the finished AAB certificate before publishing.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = { keyRefresh++ }, enabled = idle) { Text("Refresh signing keys") }
                            }
                            if (keys.isEmpty()) item { Text("No readable, unexpired non-debug signing key is available.") }
                            items(keys, key = { "key-${it.id}" }) { key ->
                                OutlinedButton(onClick = { perform {
                                    check(backend.project.rootPath == generated.projectRoot.toString()) { "Active project changed. Reopen the generated project." }
                                    val assignment = backend.signing.assignSigning(WebAppPackageResult.MODULE_NAME, "release", key.id)
                                    check(assignment.success) { assignment.message }
                                    keyRefresh++
                                    notice = assignment.message
                                } }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                                    Text((if (key.id == assignedKey) "Assigned: " else "Use: ") + key.name + " · " + key.keyAlias)
                                }
                            }
                            item {
                                Button(onClick = { perform {
                                    check(backend.project.rootPath == generated.projectRoot.toString()) { "Active project changed. Reopen the generated project." }
                                    val signing = backend.signing.signingAssignments(WebAppPackageResult.MODULE_NAME)
                                        ?: error("Signing assignments are unavailable.")
                                    val id = signing.assignments.firstOrNull { it.buildType == "release" }?.keystoreId
                                        ?: error("Assign your upload key before building a release bundle.")
                                    val key = signing.keystores.firstOrNull { it.id == id && usableReleaseKey(it) }
                                        ?: error("The assigned key is missing, expired, unreadable or a debug key.")
                                    val task = backend.build.runTasks().firstOrNull { it.id == WebAppPackageResult.RELEASE_BUNDLE_TASK }
                                        ?: error("The native bundle:app:release task is unavailable.")
                                    check(backend.build.supported()) { "This backend cannot build Android apps." }
                                    check(backend.project.rootPath == generated.projectRoot.toString()) { "Active project changed." }
                                    backend.build.runTask(task.id)
                                    notice = "Started native release AAB with ${key.name}. No Gradle download, key creation, or upload was requested. " +
                                        "Check build results and the artifact certificate before release."
                                } }, enabled = idle && keys.any { it.id == assignedKey }) { Text("Build signed release AAB") }
                                Text("Build status: ${build.status}. Full logs are in the IDE build console.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item {
                            Text("APK: ${generated.debugApk}", style = MaterialTheme.typography.bodySmall)
                            Text("AAB: ${generated.releaseBundle}", style = MaterialTheme.typography.bodySmall)
                            Text("Existing output is not proof of a successful latest build, valid upload key, or Play acceptance.", style = MaterialTheme.typography.bodySmall)
                            if (fileActions.canExport && debugReady) TextButton(onClick = { fileActions.exportFile(generated.debugApk.toString()) }, enabled = idle) { Text("Export existing debug APK") }
                            if (fileActions.canExport && bundleReady) TextButton(onClick = { fileActions.exportFile(generated.releaseBundle.toString()) }, enabled = idle) { Text("Export existing AAB") }
                            if (fileActions.canReveal) TextButton(onClick = { fileActions.reveal(generated.projectRoot.toString()) }) { Text("Show generated project in Files") }
                            TextButton(onClick = {
                                result = null
                                review = null
                                reviewed = false
                                notice = null
                            }, enabled = idle) { Text("Package another static build") }
                        }
                    } else if (review == null) {
                        item { Field("Source workspace (absolute path)", workspace, !busy) { workspace = it } }
                        if (fileActions.canPickDirectory) item {
                            TextButton(onClick = { fileActions.pickDirectory { path -> if (path != null) workspace = path } }, enabled = !busy) { Text("Choose / import source folder") }
                            Text("Android's folder picker may copy a folder into IDE storage. Packaging reads that selected copy only.", style = MaterialTheme.typography.bodySmall)
                        }
                        item {
                            Field("Static output inside workspace", staticRoot, !busy) { staticRoot = it }
                            Text("Explicitly choose dist, build, public, static, out, or '.' for an already-static site. It must contain index.html. Nothing is auto-detected or executed.", style = MaterialTheme.typography.bodySmall)
                        }
                        item { Field("New project folder name", projectName, !busy) { projectName = it } }
                        item { Field("Output parent (existing directory)", outputParent, !busy) { outputParent = it } }
                        item { Field("Android package name", applicationId, !busy) { applicationId = it } }
                        item { Field("App label", label, !busy) { label = it } }
                        item { Field("Version code (positive integer)", versionCode, !busy) { versionCode = it } }
                        item { Field("Version name", versionName, !busy) { versionName = it } }
                        item { Toggle("SPA: use index.html for missing extensionless page routes", spa, !busy) { spa = it } }
                        item { Toggle("Allow external HTTPS links only after a tap and native confirmation", external, !busy) { external = it } }
                        item {
                            Button(onClick = { perform {
                                require(workspace.isNotBlank() && outputParent.isNotBlank()) { "Choose the source workspace and output parent." }
                                val request = WebAppPackageRequest(
                                    Paths.get(workspace), staticRoot, Paths.get(outputParent), projectName, applicationId, label,
                                    versionCode.toIntOrNull() ?: error("Version code must be an integer."), versionName, spa, external,
                                )
                                review = runInterruptible(Dispatchers.IO) { packager.review(request) }
                                reviewed = false
                                page = 0
                                exclusions = false
                            } }, enabled = idle) { Text("Review assets and destination") }
                        }
                    } else {
                        val snapshot = review!!
                        item {
                            Text("Review before creating", style = MaterialTheme.typography.titleSmall)
                            Text("From: ${snapshot.staticDirectory}", style = MaterialTheme.typography.bodySmall)
                            Text("New project: ${snapshot.outputDirectory}", style = MaterialTheme.typography.bodySmall)
                            Text("${snapshot.request.appLabel} · ${snapshot.request.applicationId} · ${snapshot.request.versionName} (${snapshot.request.versionCode})")
                            Text("${snapshot.assets.size} included · ${formatBytes(snapshot.totalBytes)} · ${snapshot.excluded.size} excluded")
                            Text("JavaScript: own bundle only. SPA fallback: ${snapshot.request.spaFallback}. Confirmed external HTTPS links: ${snapshot.request.allowExternalHttps}.", style = MaterialTheme.typography.bodySmall)
                            Text("Limits: 10,000 files, 32 MiB/file, 128 MiB inspected. Hidden files, common credentials, dependencies, server sources, links and source maps are excluded. This is not a complete secret detector: review everything you publish.", style = MaterialTheme.typography.bodySmall)
                            Text("Asset-list SHA-256: ${snapshot.assetManifestSha256}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { exclusions = !exclusions; page = 0 }) { Text(if (exclusions) "Show included assets" else "Show excluded paths") }
                        }
                        val count = if (exclusions) snapshot.excluded.size else snapshot.assets.size
                        val start = (page * PAGE_SIZE).coerceAtMost(count)
                        if (exclusions) items(snapshot.excluded.drop(start).take(PAGE_SIZE), key = { "excluded-${it.path}" }) { entry ->
                            Text("${entry.path}\n${entry.reason}", style = MaterialTheme.typography.bodySmall)
                        } else items(snapshot.assets.drop(start).take(PAGE_SIZE), key = { "asset-${it.path}" }) { asset ->
                            Text("${asset.path} · ${formatBytes(asset.bytes)}\nSHA-256 ${asset.sha256}", style = MaterialTheme.typography.bodySmall)
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { page-- }, enabled = page > 0) { Text("Previous") }
                                Text("${if (count == 0) 0 else start + 1}–${(start + PAGE_SIZE).coerceAtMost(count)} / $count", Modifier.padding(top = 12.dp))
                                TextButton(onClick = { page++ }, enabled = start + PAGE_SIZE < count) { Text("Next") }
                            }
                        }
                        item {
                            Toggle("I reviewed the included public assets and the separate destination. Files embedded in JS may still contain secrets.", reviewed, !busy) { reviewed = it }
                            Button(onClick = { perform {
                                result = runInterruptible(Dispatchers.IO) { packager.create(snapshot) }
                                notice = "Created the native Android project. Source files and the active IDE project were not changed."
                                review = null
                            } }, enabled = idle && reviewed) { Text("Create native Android project") }
                            TextButton(onClick = { review = null; reviewed = false }, enabled = !busy) { Text("Back to options") }
                        }
                    }
                    item { Text("Release/upload keys remain yours. Packaging does not build, sign, install, upload or claim Play approval until you explicitly choose a later action.", Modifier.padding(bottom = 16.dp), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row {
        Checkbox(checked = checked, onCheckedChange = change, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

private fun usableReleaseKey(key: UiKeystore): Boolean =
    !key.id.equals("debug", ignoreCase = true) && !key.keyAlias.equals("androiddebugkey", ignoreCase = true) &&
        !key.certSubject.orEmpty().contains("Android Debug", ignoreCase = true) &&
        !key.sha256.isNullOrBlank() && !key.certSubject.isNullOrBlank() &&
        (key.validUntilEpochMs ?: 0L) > System.currentTimeMillis()

private fun regularArtifact(root: Path, file: Path): Boolean = runCatching {
    Files.isRegularFile(file, NOFOLLOW_LINKS) && file.toRealPath().startsWith(root.toRealPath())
}.getOrDefault(false)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
    bytes >= 1024 -> "${bytes / 1024} KiB"
    else -> "$bytes B"
}

private const val PAGE_SIZE = 40
