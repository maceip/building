package dev.ide.android.reliability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.ide.workbench.reliability.HostRuntimeCapture
import dev.ide.workbench.reliability.RuntimeCaptureStatus
import dev.ide.workbench.reliability.RuntimeCaptureResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

@Composable
internal fun RuntimeCaptureControls(root: Path?, onOpenInbox: (() -> Unit)? = null) {
    key(root) {
        val context = LocalContext.current.applicationContext
        val scope = rememberCoroutineScope()
        var capture by remember { mutableStateOf<HostRuntimeCapture?>(null) }
        var status by remember { mutableStateOf<RuntimeCaptureStatus?>(null) }
        var failed by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var confirm by remember { mutableStateOf(false) }
        LaunchedEffect(root) {
            if (root != null) try {
                val loaded = withContext(Dispatchers.IO) {
                    val service = AndroidRuntimeReliability(context).capture
                    val consent = service.status(root)
                    service.replay()
                    service to consent
                }
                capture = loaded.first
                status = loaded.second
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
        fun enable(value: Boolean) {
            val service = capture ?: return
            val selected = root ?: return
            if (busy) return
            busy = true
            scope.launch {
                try { status = withContext(Dispatchers.IO) { service.setEnabled(selected, value) }; failed = false }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Runtime capture for this workspace", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = status?.enabled == true,
                    enabled = root != null && status != null && status?.consentKnown == true && !busy && !failed,
                    onCheckedChange = { if (it) confirm = true else enable(false) },
                    modifier = Modifier.testTag("runtime-capture-toggle"),
                )
            }
            Text("Local only. No output or AI sharing.", style = MaterialTheme.typography.bodySmall)
            if (root == null) Text("Choose a workspace to configure runtime capture.", style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) Text("Runtime capture storage is unavailable. No capture consent was assumed.", color = MaterialTheme.colorScheme.error)
            if (status?.consentKnown == false) {
                Text("The saved consent state could not be confirmed. Capture may still be enabled; do not treat this as a successful revocation.", color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = { enable(false) }) { Text("Retry disabling capture") }
            }
            capture?.let { service ->
                val update by service.updates.collectAsState()
                LaunchedEffect(update) {
                    if (update.workspaceId != null && update.workspaceId == status?.workspaceId) status = update
                }
                val localUpdate = update.workspaceId == status?.workspaceId && status?.workspaceId != null
                val globalOperation = update.workspaceId == null
                val result = if (localUpdate || globalOperation) update.result else status?.result ?: RuntimeCaptureResult.DISABLED
                // Replay is device-wide; its enabled bit and queue must never describe this workspace's consent.
                val queued = if (localUpdate || globalOperation) update.queuedEvents else status?.takeIf { it.workspaceId != null }?.queuedEvents ?: 0
                if (queued > 0) Text(
                    if (globalOperation) "$queued outcome(s) awaiting inbox delivery across workspaces."
                    else "$queued outcome(s) awaiting inbox delivery for this workspace.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (result != RuntimeCaptureResult.READY && result != RuntimeCaptureResult.DISABLED) {
                    val resultMessage = if (globalOperation && result == RuntimeCaptureResult.REVOKED) {
                        "Pending outcomes from revoked grants were not delivered."
                    } else runtimeCaptureMessage(result)
                    Text(if (globalOperation) "Across workspaces: $resultMessage" else resultMessage, style = MaterialTheme.typography.bodySmall,
                        color = if (result in setOf(RuntimeCaptureResult.INVALID, RuntimeCaptureResult.QUOTA, RuntimeCaptureResult.ID_CONFLICT, RuntimeCaptureResult.STORAGE_UNAVAILABLE)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (queued > 0 || result in setOf(RuntimeCaptureResult.RETRY_LATER, RuntimeCaptureResult.STORAGE_UNAVAILABLE, RuntimeCaptureResult.QUOTA, RuntimeCaptureResult.ID_CONFLICT)) TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val confirmed = withContext(Dispatchers.IO) {
                                val consent = root?.let(service::status)
                                service.replay()
                                consent
                            }
                            if (confirmed != null) status = confirmed
                            failed = false
                        }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                        finally { busy = false }
                    }
                }) { Text("Retry local delivery") }
            }
            if (onOpenInbox != null) TextButton(onClick = onOpenInbox, modifier = Modifier.testTag("runtime-open-incidents")) { Text("Open incident inbox") }
        }
        if (confirm) AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Enable local runtime capture?") },
            text = { Text("For this workspace on this device, save failed runtime preparation/launch, nonzero command exits and owned terminal session failures. A nonzero exit may be expected or a failed test; it is not labeled a crash.\n\nRecords contain a random workspace/session identity, a before-run source-content hash when available, the IDE/runtime pack identity, stage, exit code and elapsed time. The snapshot excludes dependency/output directories and credential filenames; it does not attest later file changes or individual terminal commands.\n\nNo raw output, typed commands, file paths, environment, source contents or arbitrary error messages are saved in incidents. Stop and successful runs create no incident. This does not authorize AI sharing, remote relay, reproduction commands or repairs. Disabling drops pending delivery and invalidates active capture sessions; already saved incidents remain.") },
            confirmButton = { TextButton(onClick = { confirm = false; enable(true) }, modifier = Modifier.testTag("runtime-capture-confirm")) { Text("Enable locally") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

private fun runtimeCaptureMessage(result: RuntimeCaptureResult): String = when (result) {
    RuntimeCaptureResult.READY -> "Capture enabled for this workspace on this device."
    RuntimeCaptureResult.DISABLED -> "Capture is off."
    RuntimeCaptureResult.CAPTURING -> "This session started with local capture enabled."
    RuntimeCaptureResult.IGNORED -> "No incident was saved for this outcome."
    RuntimeCaptureResult.QUEUED -> "Outcome saved locally; inbox delivery is pending."
    RuntimeCaptureResult.DELIVERED -> "Outcome durably saved in the local inbox."
    RuntimeCaptureResult.REVOKED -> "Capture was revoked; pending outcomes were not delivered."
    RuntimeCaptureResult.INVALID -> "Capture metadata was invalid. No incident was acknowledged."
    RuntimeCaptureResult.QUOTA -> "Local capture storage is full. This outcome was not acknowledged."
    RuntimeCaptureResult.ID_CONFLICT -> "Conflicting incident identity; delivery was rejected."
    RuntimeCaptureResult.RETRY_LATER -> "Local delivery is unavailable; saved outcomes remain queued."
    RuntimeCaptureResult.STORAGE_UNAVAILABLE -> "Private capture storage is unavailable. No durable receipt was assumed."
}
