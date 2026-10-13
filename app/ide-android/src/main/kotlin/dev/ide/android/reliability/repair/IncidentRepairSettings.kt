package dev.ide.android.reliability.repair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.agent.IncidentIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Host may use this single contribution inside ReliabilityPanel; no copied incident prompt is needed. */
@Composable
fun IncidentRepairSettings(
    project: AndroidIncidentRepairProject,
    selectedIncident: IncidentIdentity? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var settings by remember(project) { mutableStateOf(project.planStore.read()) }
    var edit by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IncidentRepairPanel(project.controller, project.controller.aiConfiguration(), selectedIncident)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Immutable local verification", style = MaterialTheme.typography.titleSmall)
                Text(settings?.let { "Approved ${it.input.variant}: ${it.input.buildTask}; ${it.input.unitTask}. Reproducer ${it.input.reproducerTest}; separate function ${it.input.functionalTest}." }
                    ?: "No test plan approved. Select an existing failing Gradle unit test and a separate passing functional test before automatic repair can run.",
                    style = MaterialTheme.typography.bodySmall)
                Text("On-device local unit tests only. Android activity instrumentation, installing a test APK, and launching an app require separate device permissions and remain pending here.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { edit = true }) { Text("Approve test plan…") }
                    if (settings != null) TextButton(enabled = !busy, onClick = {
                        project.controller.cancel()
                        project.planStore.revoke()
                        settings = null
                    }) { Text("Revoke execution") }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (edit) PlanDialog(settings?.input, busy, onDismiss = { if (!busy) edit = false }) { input ->
        busy = true
        project.controller.cancel()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { project.planStore.approve(input) }
                settings = project.planStore.read()
                message = "Test/build inputs are frozen. New incidents use this plan; use Repair selected incident to retry a pending one."
                edit = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Plan was not approved. Check exact existing source/test paths, task names and test IDs. No source files were changed." }
            finally { busy = false }
        }
    }
}

@Composable
private fun PlanDialog(
    current: ApprovedRepairPlanStore.Input?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onApprove: (ApprovedRepairPlanStore.Input) -> Unit,
) {
    var variant by remember { mutableStateOf(current?.variant.orEmpty()) }
    var buildTask by remember { mutableStateOf(current?.buildTask.orEmpty()) }
    var unitTask by remember { mutableStateOf(current?.unitTask.orEmpty()) }
    var repro by remember { mutableStateOf(current?.reproducerTest.orEmpty()) }
    var functional by remember { mutableStateOf(current?.functionalTest.orEmpty()) }
    var sources by remember { mutableStateOf(current?.editableFiles?.joinToString("\n").orEmpty()) }
    var reproFiles by remember { mutableStateOf(current?.reproducerFiles?.joinToString("\n").orEmpty()) }
    var functionFiles by remember { mutableStateOf(current?.functionalFiles?.joinToString("\n").orEmpty()) }
    var trusted by remember { mutableStateOf(false) }
    fun paths(text: String) = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Approve fixed build and tests") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This is not an OS sandbox. Only approve a project whose build code and tests you trust. Offline Gradle uses installed dependencies, but project code, including AI-proposed changes run by these tests, runs as the IDE's Android UID and can access its files and network. The repair agent receives no network or shell tool. Do not approve untrusted code.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Text("All noneditable source/build/test inputs are frozen now. Reproducer assertions and the separate functional test cannot be changed by the agent. A same-test failure before and pass after are mandatory.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(variant, { variant = it }, label = { Text("Exact incident build variant (debug)") }, singleLine = true)
            OutlinedTextField(buildTask, { buildTask = it }, label = { Text("Build task, e.g. :app:assembleDebug") }, singleLine = true)
            OutlinedTextField(unitTask, { unitTask = it }, label = { Text("Unit test task, e.g. :app:testDebugUnitTest") }, singleLine = true)
            OutlinedTextField(repro, { repro = it }, label = { Text("Reproducer classname#method") }, singleLine = true)
            OutlinedTextField(functional, { functional = it }, label = { Text("Independent functional classname#method") }, singleLine = true)
            OutlinedTextField(sources, { sources = it }, label = { Text("Editable production source paths, one per line") }, minLines = 2)
            OutlinedTextField(reproFiles, { reproFiles = it }, label = { Text("Immutable reproducer source paths") }, minLines = 2)
            OutlinedTextField(functionFiles, { functionFiles = it }, label = { Text("Immutable functional-test source paths") }, minLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(trusted, { trusted = it })
                Text("I trust this project and authorize these fixed local build/test tasks with the access described above.", style = MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = { Button(enabled = trusted && !busy && listOf(variant, buildTask, unitTask, repro, functional, sources, reproFiles, functionFiles).all { it.isNotBlank() },
            onClick = { onApprove(ApprovedRepairPlanStore.Input(variant.trim(), buildTask.trim(), unitTask.trim(), repro.trim(), functional.trim(),
                paths(sources), paths(reproFiles), paths(functionFiles), trustedLocalExecution = true)) }) { Text(if (busy) "Freezing inputs…" else "Approve and freeze") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
}
