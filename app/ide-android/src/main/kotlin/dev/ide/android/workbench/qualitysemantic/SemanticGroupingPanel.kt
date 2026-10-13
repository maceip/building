package dev.ide.android.workbench.qualitysemantic

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.core.qualitysemantic.ConfiguredSemanticLabeler
import dev.ide.ui.backend.IdeBackend
import dev.ide.workbench.quality.QualityReport
import dev.ide.workbench.quality.RecordKind
import dev.ide.workbench.quality.semantic.SemanticException
import dev.ide.workbench.quality.semantic.SemanticGroupingResult
import dev.ide.workbench.quality.semantic.SemanticGroupingService
import dev.ide.workbench.quality.semantic.SemanticLocality
import dev.ide.workbench.quality.semantic.SemanticPreparedRun
import dev.ide.workbench.quality.semantic.SemanticRunStatus
import dev.ide.workbench.quality.semantic.SemanticSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optional, in-memory overlay. No constructor, remember block or composition performs provider calls. */
@Composable
fun SemanticGroupingPanel(backend: IdeBackend, report: QualityReport, enabled: Boolean, modifier: Modifier = Modifier) {
    key(backend, report) { SemanticGroupingContent(backend, report, enabled, modifier) }
}

@Composable
private fun SemanticGroupingContent(backend: IdeBackend, report: QualityReport, enabled: Boolean, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var semantic by remember { mutableStateOf(false) }
    var ids by remember { mutableStateOf("") }
    var includePrs by remember { mutableStateOf(false) }
    var service by remember { mutableStateOf<SemanticGroupingService?>(null) }
    var preview by remember { mutableStateOf<SemanticPreparedRun?>(null) }
    var reviewed by remember { mutableStateOf(false) }
    var showPayload by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SemanticGroupingResult?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    val editable = enabled && !busy

    fun clearPreview() { preview = null; reviewed = false; showPayload = false; result = null; message = null }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Optional grouping", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !semantic, onClick = { semantic = false; clearPreview(); service = null },
                enabled = editable, label = { Text("Offline heuristic · default") })
            FilterChip(selected = semantic, onClick = { semantic = true }, enabled = editable,
                label = { Text("Semantic · review required") })
        }
        if (!semantic) {
            Text("Offline grouping remains unchanged. No model request or upload.")
            return@Column
        }
        Text("Experimental classification only; independent accuracy gate is BLOCKED. No tools, code edits, automatic uploads or source-file access.")
        Text("Select up to 24 canonical IDs from this imported report. Existing repository/date filters remain in force; withheld sensitive records cannot be shared.")
        OutlinedTextField(value = ids, onValueChange = { ids = it.take(8_000); clearPreview() },
            label = { Text("Canonical IDs · owner/repository#number") },
            supportingText = { Text("Comma or newline separated. Empty selects all admitted issues, subject to the 24-record cap.") },
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, enabled = editable)
        Row {
            Checkbox(checked = includePrs, onCheckedChange = { includePrs = it; clearPreview() }, enabled = editable)
            Text("Also admit PRs (proposals, counted separately from issues)")
        }
        OutlinedButton(onClick = {
            clearPreview()
            busy = true
            job = scope.launch {
                try {
                    // Read existing settings only on a tap. No live model discovery or credential persistence.
                    val labeler = ConfiguredSemanticLabeler.from(backend)
                    if (labeler == null) {
                        service = null
                        message = "Configure an existing AI provider/model in AI settings first. Nothing was sent."
                    } else {
                        val next = SemanticGroupingService(labeler)
                        val selection = SemanticSelection(
                            recordIds = ids.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                            kinds = if (includePrs) setOf(RecordKind.ISSUE, RecordKind.PULL_REQUEST) else setOf(RecordKind.ISSUE),
                        )
                        preview = withContext(Dispatchers.Default) { next.prepare(report, selection) }
                        service = next
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    message = (failure as? SemanticException)?.message ?: "Cannot prepare a safe preview. Nothing was sent."
                } finally { busy = false; job = null }
            }
        }, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text("Prepare / refresh exact sharing preview") }
        preview?.let { prepared ->
            val manifest = prepared.manifest
            val cloud = manifest.provider.locality == SemanticLocality.CLOUD
            Text("Provider: ${manifest.provider.displayName} (${manifest.provider.id}) · ${manifest.provider.model}")
            Text("Destination: ${manifest.provider.destination} · ${if (cloud) "cloud/remote" else "loopback local endpoint"}")
            Text("Share ${manifest.counts.total} records: ${manifest.counts.issues} issues, ${manifest.counts.pullRequests} PRs. ${manifest.requestBytes} UTF-8 prompt bytes (plus provider protocol overhead).")
            Text("Fields: ${manifest.fields.joinToString()}")
            Text("${manifest.truncatedRecords} truncated · ${manifest.withheldSensitive} sensitive withheld · ${manifest.excludedByFilter} filtered out. Up to ${manifest.maxAttempts} model turns, ${manifest.attemptTimeoutMillis / 1000}s per turn. Total network sends including retries/fallbacks: ${manifest.provider.maxWireRequestsPerRun ?: "not established"}; redirects are disabled. Retries share only this payload.")
            Text("Redaction is best effort, not a privacy guarantee. IDs disclose repository and issue number. ${if (cloud) "These fields leave this device on confirmation." else "A local server may itself forward data; verify its configuration."} No archive file, external labels, credentials, other project files or previous chat is included.")
            TextButton(onClick = { showPayload = !showPayload }, enabled = !busy) {
                Text(if (showPayload) "Hide exact redacted payload" else "Review exact redacted title/body payload")
            }
            if (showPayload) {
                Text(prepared.request.untrustedRecordsJson,
                    Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall)
                Text("Payload SHA-256: ${manifest.payloadSha256}", style = MaterialTheme.typography.bodySmall)
            }
            Row {
                Checkbox(checked = reviewed, onCheckedChange = { reviewed = it }, enabled = editable && showPayload)
                Text("I reviewed these fields and consent to share this exact selection with this provider for this run only.")
            }
            Button(onClick = {
                val active = service ?: return@Button
                reviewed = false
                busy = true
                message = null
                result = null
                job = scope.launch {
                    try {
                        val consent = active.confirm(prepared)
                        result = withContext(Dispatchers.Default) { active.run(prepared, consent) }
                        message = if (result?.status == SemanticRunStatus.COMPLETED)
                            "Unverified grouping proposals returned. Original offline report and source metadata are unchanged."
                        else "Request failed or output was invalid; selected records abstained. Offline report is unchanged."
                    } catch (cancelled: CancellationException) {
                        message = "Cancelled. A request already sent cannot be recalled. Offline report is unchanged."
                        throw cancelled
                    } catch (failure: Exception) {
                        message = (failure as? SemanticException)?.message ?: "Semantic request failed; offline report is unchanged."
                    } finally { preview = null; busy = false; job = null }
                }
            }, enabled = editable && reviewed && showPayload, modifier = Modifier.fillMaxWidth()) {
                Text(if (cloud) "Confirm cloud sharing · run once" else "Confirm local request · run once")
            }
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { job?.cancel() }) { Text("Cancel") }
        }
        message?.let { Text(it) }
        result?.let { grouping ->
            Text("${grouping.labels.size - grouping.reviewCount} classified proposals · ${grouping.reviewCount} abstain/review. Model confidence is not calibrated accuracy.")
            for (group in grouping.groups) {
                Text("${group.theme.label} / ${group.problemFamily.key} / ${group.intent.name}: ${group.counts.issues} issues, ${group.counts.pullRequests} PRs")
                Text(group.recordIds.joinToString(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
