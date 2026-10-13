package dev.ide.android.reliability.repair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ide.agent.IncidentAiConfiguration
import dev.ide.agent.IncidentDeviceResult
import dev.ide.agent.IncidentIdentity
import dev.ide.agent.IncidentRepairActions
import dev.ide.agent.IncidentRepairPhase

/** Small host-inbox contribution. Consent controls never reuse the chat's global auto-accept mode. */
@Composable
fun IncidentRepairPanel(
    actions: IncidentRepairActions,
    aiConfiguration: IncidentAiConfiguration?,
    selectedIncident: IncidentIdentity? = null,
    modifier: Modifier = Modifier,
) {
    val policy by actions.policy.collectAsState()
    val state by actions.state.collectAsState()
    var sharingDialog by remember { mutableStateOf(false) }
    var autoPatchDialog by remember { mutableStateOf(false) }
    var diffDialog by remember { mutableStateOf(false) }
    var deviceAction by remember { mutableStateOf<String?>(null) }
    val busy = state.phase in setOf(IncidentRepairPhase.CHECKING, IncidentRepairPhase.REPRODUCING, IncidentRepairPhase.DIAGNOSING, IncidentRepairPhase.VERIFYING)
    val approvedConfiguration = aiConfiguration != null && policy.approvedAiConfiguration == aiConfiguration.id
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI incident repair", style = MaterialTheme.typography.titleMedium)
            ConsentRow("Automatically repair incidents for this project", policy.enabled) { enabled ->
                actions.setPolicy(policy.copy(enabled = enabled))
            }
            Text("Runs only in a private source copy, with at most ${policy.maxAttempts} repair attempts. Source and build identity must match. Original files stay unchanged until a verified diff is approved.",
                style = MaterialTheme.typography.bodySmall)
            ConsentRow("Allow scoped source and incident metadata to this AI", policy.cloudAiDataSharing && approvedConfiguration,
                enabled = aiConfiguration != null) { enabled ->
                if (enabled) sharingDialog = true else actions.setPolicy(policy.copy(cloudAiDataSharing = false, approvedAiConfiguration = null))
            }
            Text(aiConfiguration?.let { "Configured provider: ${it.label}" } ?: "Configure an AI provider in AI settings first. No simulated provider is used.",
                style = MaterialTheme.typography.bodySmall)
            ConsentRow("Auto-apply verified diffs for this project", policy.autoApplyVerifiedPatch) { enabled ->
                if (enabled) autoPatchDialog = true else actions.setPolicy(policy.copy(autoApplyVerifiedPatch = false))
            }
            Text("Checked apply supports one changed source file only when every editor tab is closed and the host confirms all workspace work has stopped. Remote build hosts without that confirmation and multi-file repairs keep the verified copy. These toggles do not grant installation, launch, network access or production deployment.",
                style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("${state.phase.name.lowercase().replace('_', ' ')} · attempt ${state.attempt}/${policy.maxAttempts}", style = MaterialTheme.typography.labelMedium)
            Text(state.detail, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedIncident != null) OutlinedButton(
                    onClick = { actions.repair(selectedIncident) },
                    enabled = policy.enabled && policy.cloudAiDataSharing && approvedConfiguration && !busy && state.review == null,
                ) { Text("Repair selected incident") }
                if (busy) TextButton(onClick = actions::cancel) { Text("Cancel") }
            }
            state.review?.let { review ->
                Text("Verified copy · ${review.changes.size} changed file(s). Same reproducer fail-before/pass-after and separate functional proof retained.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { diffDialog = true }) { Text("Review diff") }
                    if (state.phase != IncidentRepairPhase.APPLIED) TextButton(onClick = { actions.rejectPatch(review.runId) }) { Text("Discard copy") }
                }
                if (state.artifact != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { deviceAction = "install" }) { Text("Install debug APK…") }
                    OutlinedButton(enabled = !busy && state.install == IncidentDeviceResult.Completed,
                        onClick = { deviceAction = "launch" }) { Text("Launch…") }
                }
            }
        }
    }
    if (sharingDialog) AlertDialog(onDismissRequest = { sharingDialog = false },
        title = { Text("Share with configured AI?") },
        text = { Text("${aiConfiguration?.label.orEmpty()}\n\nThis project may send authorized source snippets, build identity and structured stack locations to this provider. Raw incident logs, pairing secrets and credentials are excluded; source may still contain private information. This consent is separate from local collection and patch approval. You can revoke it at any time.") },
        confirmButton = { Button(enabled = aiConfiguration != null, onClick = {
            sharingDialog = false
            actions.setPolicy(policy.copy(cloudAiDataSharing = true, approvedAiConfiguration = aiConfiguration?.id))
        }) { Text("Allow for this project") } },
        dismissButton = { TextButton(onClick = { sharingDialog = false }) { Text("Not now") } })
    if (autoPatchDialog) AlertDialog(onDismissRequest = { autoPatchDialog = false },
        title = { Text("Auto-apply verified diffs?") },
        text = { Text("Only authorized production source files may change, after the unchanged reproducer and independent functional test pass. The host must atomically verify all original source filenames/content and live editor buffers. If that operation is unavailable or source changed, the verified copy is retained instead. This never authorizes install, launch or production deployment.") },
        confirmButton = { Button(onClick = { autoPatchDialog = false; actions.setPolicy(policy.copy(autoApplyVerifiedPatch = true)) }) { Text("Allow for this project") } },
        dismissButton = { TextButton(onClick = { autoPatchDialog = false }) { Text("Keep diff review") } })
    if (diffDialog) state.review?.let { review ->
        AlertDialog(onDismissRequest = { diffDialog = false }, title = { Text("Verified isolated diff") },
            text = { SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Original source has not changed unless the status explicitly says applied.", style = MaterialTheme.typography.bodySmall)
                    review.changes.forEach { change ->
                        Text(change.path, style = MaterialTheme.typography.titleSmall)
                        Text("Before\n${change.before.orEmpty().take(8000)}\n\nAfter\n${change.after.orEmpty().take(8000)}",
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        if ((change.before?.length ?: 0) > 8000 || (change.after?.length ?: 0) > 8000) {
                            Text("Preview truncated. Inspect the complete retained copy before approving.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } },
            confirmButton = { Button(enabled = state.phase != IncidentRepairPhase.APPLIED && policy.enabled && !busy,
                onClick = { diffDialog = false; actions.approvePatch(review.runId) }) { Text("Apply checked diff") } },
            dismissButton = { TextButton(onClick = { diffDialog = false }) { Text("Keep verified copy") } })
    }
    deviceAction?.let { action ->
        AlertDialog(onDismissRequest = { deviceAction = null }, title = { Text(if (action == "install") "Install verified debug APK?" else "Launch verified app?") },
            text = { Text("This is a separate device action. Android permissions and the host must confirm it; an unavailable capability stays pending. No production deployment is performed.") },
            confirmButton = { Button(onClick = {
                deviceAction = null
                state.runId?.let { if (action == "install") actions.install(it) else actions.launch(it) }
            }) { Text(if (action == "install") "Continue to install" else "Launch") } },
            dismissButton = { TextButton(onClick = { deviceAction = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ConsentRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
