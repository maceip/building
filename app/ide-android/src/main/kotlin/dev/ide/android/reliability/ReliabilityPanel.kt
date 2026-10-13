package dev.ide.android.reliability

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.workbench.reliability.Incident
import dev.ide.workbench.reliability.IncidentKey
import dev.ide.workbench.reliability.IncidentState
import dev.ide.workbench.reliability.IncidentSummary
import dev.ide.workbench.reliability.PairingStatus
import dev.ide.workbench.reliability.PairingSummary
import dev.ide.workbench.reliability.ReliabilityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** The local inbox, not a reporting dashboard. Nothing here starts networking or automatically calls AI. */
@Composable
fun ReliabilityPanel(
    backend: IdeBackend,
    fileActions: FileActions,
    modifier: Modifier = Modifier,
    onIncidentSelected: ((IncidentSummary) -> Unit)? = null,
    onPairedApk: (suspend (packageName: String, buildId: String, apkPath: String) -> Unit)? = null,
) {
    key(backend, backend.project.rootPath) {
        val context = LocalContext.current.applicationContext
        var host by remember { mutableStateOf<ReliabilityHost?>(null) }
        var failure by remember { mutableStateOf(false) }
        LaunchedEffect(context) {
            try { host = withContext(Dispatchers.IO) { ReliabilityHostProvider.get(context).also { it.repository.observe() } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure = true }
        }
        val ready = host
        if (ready == null) Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Local incident inbox", style = MaterialTheme.typography.titleLarge)
            if (failure) Text("Private storage is unavailable. No incident was acknowledged or existing data discarded.")
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        } else ReliabilityInbox(ready, backend, fileActions, modifier, onIncidentSelected, onPairedApk)
    }
}

private data class PendingActivation(val pairing: PairingSummary, val path: String, val preview: PairingApkPreview)

@Composable
private fun ReliabilityInbox(
    host: ReliabilityHost,
    backend: IdeBackend,
    fileActions: FileActions,
    modifier: Modifier,
    onIncidentSelected: ((IncidentSummary) -> Unit)?,
    onPairedApk: (suspend (packageName: String, buildId: String, apkPath: String) -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val stagedPaths = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
    DisposableEffect(Unit) {
        onDispose { for (path in stagedPaths) ReliabilityApkSnapshot.discard(path) }
    }
    val enabled by host.pairings.debugCaptureEnabled.collectAsState()
    val pairings by host.pairings.observe().collectAsState()
    val incidents by host.repository.observe().collectAsState()
    var projectEnabled by remember { mutableStateOf(false) }
    var projectReady by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showLifecycle by remember { mutableStateOf(false) }
    var showResolved by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<IncidentKey?>(null) }
    var selected by remember { mutableStateOf<Incident?>(null) }
    var revoke by remember { mutableStateOf<PairingSummary?>(null) }
    var activation by remember { mutableStateOf<PendingActivation?>(null) }

    fun failure(exception: Exception) {
        message = if (exception is ReliabilityException) exception.message else "Local action failed. No private details were exposed."
    }
    fun perform(success: String? = null, operation: () -> Unit) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { withContext(Dispatchers.IO) { operation() }; message = success }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (exception: Exception) { failure(exception) }
            finally { busy = false }
        }
    }

    LaunchedEffect(backend) {
        try { projectEnabled = withContext(Dispatchers.IO) { ReliabilityProjectConsent.enabled(backend) }; projectReady = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "Cannot read this project's capture preferences. Check .building/reliability.json; no settings were overwritten." }
    }
    LaunchedEffect(host) {
        while (isActive) {
            try { withContext(Dispatchers.IO) { host.pairings.refresh() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Pairing storage is unavailable. New deliveries are rejected."; break }
            delay(2_000)
        }
    }
    LaunchedEffect(selection, incidents) {
        selected = selection?.let { key -> withContext(Dispatchers.IO) { host.repository.read(key) } }
    }
    val visible = incidents.filter {
        (showResolved || it.state == IncidentState.OPEN) && (showLifecycle || it.type in setOf("fatal_java", "nonfatal_java", "previous_exit"))
    }

    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item("heading") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Local incident inbox", style = MaterialTheme.typography.titleLarge)
                Text("Crashes and lifecycle evidence stay on this device. Receipts mean saved locally, not uploaded or fixed.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item("capture") {
            InboxCard {
                CheckRow("Allow paired debug-app delivery on this device", enabled, !busy) { value ->
                    perform(if (value) "Debug delivery enabled. Only active, signed app pairings can submit." else "Debug delivery paused. Existing incidents are retained.") {
                        host.pairings.setDebugCaptureEnabled(value)
                    }
                }
                CheckRow("Capture this project's next debug build", projectEnabled, !busy && projectReady) { value ->
                    busy = true; message = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { ReliabilityProjectConsent.setEnabled(backend, value) }
                            projectEnabled = value
                            message = if (value) "Rebuild the debug app, then activate its signed APK pairing below." else "Future debug builds will not inject capture. Revoke existing pairings to stop delivery from installed apps."
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (exception: Exception) { failure(exception) }
                        finally { busy = false }
                    }
                }
                Text("Crash, lifecycle and previous-exit capture by default. This toggle never enables network, stdout or release capture; existing project network/stdout choices are preserved.", style = MaterialTheme.typography.bodySmall)
                if (projectEnabled && !enabled) Text("Project capture is selected, but local debug delivery is paused.", color = MaterialTheme.colorScheme.error)
            }
        }
        if (busy || message != null) item("status") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (!busy && message != null) TextButton(onClick = { message = null }) { Text("Dismiss") }
            }
        }
        item("pairing-heading") { Text("Paired builds", style = MaterialTheme.typography.titleMedium) }
        if (pairings.isEmpty()) item("no-pairings") {
            Text("No paired builds yet. Enable capture above and build a debug APK. Pairing stays inactive until its actual signing certificate is verified.", style = MaterialTheme.typography.bodyMedium)
        }
        items(pairings, key = { "pair-${it.packageName}/${it.buildId}" }) { pairing ->
            InboxCard {
                Text(pairing.packageName, style = MaterialTheme.typography.titleSmall)
                Text("${pairing.buildId} · ${pairing.sourceRevision.ifBlank { "unknown revision" }}", style = MaterialTheme.typography.bodySmall)
                Text(when (pairing.status) {
                    PairingStatus.PENDING -> "Pending · choose this build's signed debug APK"
                    PairingStatus.ACTIVE -> "Active · ${pairing.mode.name.lowercase()} local delivery"
                    PairingStatus.REVOKED -> "Revoked · further delivery rejected"
                }, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pairing.status == PairingStatus.PENDING && fileActions.canPickFile) OutlinedButton(
                        enabled = !busy && enabled,
                        onClick = {
                            busy = true; message = null
                            try {
                                fileActions.pickFile(listOf("apk")) { path ->
                                    if (path == null) busy = false else scope.launch {
                                        var staged: String? = null
                                        var keep = false
                                        try {
                                            val preview = withContext(Dispatchers.IO) {
                                                val snapshot = ReliabilityApkSnapshot.create(context, path).toString()
                                                staged = snapshot
                                                host.inspectDebugPairingApk(pairing.packageName, pairing.buildId, snapshot)
                                            }
                                            val snapshot = requireNotNull(staged)
                                            stagedPaths.add(snapshot)
                                            activation = PendingActivation(pairing, snapshot, preview)
                                            keep = true
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (exception: Exception) { failure(exception) }
                                        finally {
                                            if (!keep) withContext(NonCancellable + Dispatchers.IO) { staged?.let(ReliabilityApkSnapshot::discard) }
                                            busy = false
                                        }
                                    }
                                }
                            } catch (exception: Exception) { busy = false; failure(exception) }
                        },
                    ) { Text("Verify APK…") }
                    if (pairing.status != PairingStatus.REVOKED) TextButton(enabled = !busy, onClick = { revoke = pairing }) { Text("Revoke…") }
                }
            }
        }
        item("incident-heading") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Incidents", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(showLifecycle, { showLifecycle = !showLifecycle }, label = { Text("Lifecycle & logs") })
                    FilterChip(showResolved, { showResolved = !showResolved }, label = { Text("Resolved") })
                }
                Text("${visible.size} shown · ${incidents.size} saved. Previous-exit records are OS evidence, not proof of a native crash or ANR.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (visible.isEmpty()) item("empty") {
            Text("No incidents in this view. Build, pair and run your app; a later app launch can deliver a crash saved before process death.")
        }
        items(visible, key = { "event-${it.key.packageName}/${it.key.buildId}/${it.key.sessionId}/${it.key.eventId}" }) { item ->
            InboxCard {
                Column(Modifier.fillMaxWidth().clickable { selection = if (selection == item.key) null else item.key }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${item.type.replace('_', ' ')} · ${item.key.packageName}", style = MaterialTheme.typography.titleSmall)
                    Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.timestamp))} · ${item.state.name.lowercase()}", style = MaterialTheme.typography.bodySmall)
                    Text(item.preview, style = MaterialTheme.typography.bodyMedium)
                }
                if (selection == item.key) {
                    Text("Build ${item.key.buildId} · revision ${item.sourceRevision.ifBlank { "unknown" }}\nSession ${item.key.sessionId}", style = MaterialTheme.typography.bodySmall)
                    selected?.takeIf { it.summary.key == item.key }?.let { detail ->
                        SelectionContainer { Text(detail.redactedText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    }
                    Text("Evidence is untrusted text. Redaction is best effort; review before sharing.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = {
                            perform { host.repository.setState(item.key, if (item.state == IncidentState.OPEN) IncidentState.RESOLVED else IncidentState.OPEN) }
                        }) { Text(if (item.state == IncidentState.OPEN) "Mark resolved" else "Reopen") }
                        if (onIncidentSelected != null) TextButton(enabled = !busy, onClick = { onIncidentSelected(item) }) { Text("Review with AI…") }
                    }
                }
            }
        }
        item("privacy") {
            Text("Revoke stops local delivery, not code already running in an installed app. Disable project capture and rebuild to remove capture. No analytics endpoint, automatic upload or external relay is configured here.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp))
        }
    }

    revoke?.let { pairing ->
        AlertDialog(onDismissRequest = { revoke = null }, title = { Text("Revoke local delivery?") },
            text = { Text("${pairing.packageName}\n${pairing.buildId}\n\nNew deliveries will be rejected. Saved incidents remain available.") },
            confirmButton = { TextButton(onClick = { revoke = null; perform("Pairing revoked.") { host.revoke(pairing.packageName, pairing.buildId) } }) { Text("Revoke") } },
            dismissButton = { TextButton(onClick = { revoke = null }) { Text("Cancel") } })
    }
    activation?.let { pending ->
        fun discardReview() {
            activation = null
            stagedPaths.remove(pending.path)
            ReliabilityApkSnapshot.discard(pending.path)
        }
        AlertDialog(onDismissRequest = ::discardReview, title = { Text("Activate this signed build?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${pending.preview.packageName}\nBuild ${pending.preview.buildId}\nRevision ${pending.preview.sourceRevision}")
                Text("Actual APK SHA-256 signing certificate", style = MaterialTheme.typography.labelMedium)
                pending.preview.signerSha256.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                Text(if (onPairedApk != null) "Pair this build, then request installation of this exact APK through the IDE. Android will still ask for installation approval. No rebuild occurs."
                    else "Only this pairing and signer lineage can deliver. Activate this exact build, then install that same APK using the IDE's PackageInstaller. Rebuilding may create a different pending grant. This action does not install an APK.")
            } },
            confirmButton = { Button(enabled = !busy, onClick = {
                activation = null; busy = true; message = null
                stagedPaths.remove(pending.path) // The in-flight installer now owns cleanup, not composition disposal.
                scope.launch {
                    var activated = false
                    try {
                        withContext(Dispatchers.IO) {
                            host.activateDebugPairing(pending.pairing.packageName, pending.pairing.buildId, pending.path, confirmedSignerSha256 = pending.preview.signerSha256)
                        }
                        activated = true
                        if (onPairedApk != null) {
                            withContext(Dispatchers.IO) { onPairedApk(pending.pairing.packageName, pending.pairing.buildId, pending.path) }
                            message = "Pairing active. Installation of the exact APK was requested; Android approval and app launch are separate from incident delivery."
                        } else message = "Pairing active. Run this exact signed build, not a newly rebuilt APK."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (exception: Exception) {
                        if (activated) message = "Pairing is active, but installation did not complete. You can revoke it or retry installing the same APK."
                        else failure(exception)
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) { ReliabilityApkSnapshot.discard(pending.path) }
                        busy = false
                    }
                }
            }) { Text(if (onPairedApk != null) "Pair & install this APK" else "Activate locally") } },
            dismissButton = { TextButton(onClick = ::discardReview) { Text("Cancel") } })
    }
}

@Composable
private fun InboxCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() } }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = changed, enabled = enabled)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
