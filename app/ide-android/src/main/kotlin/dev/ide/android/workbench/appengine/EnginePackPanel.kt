// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.android.workbench.appengine

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import dev.ide.android.BuildConfig
import dev.ide.runtime.engine.Engine
import dev.ide.runtime.engine.EngineAssets
import dev.ide.runtime.engine.EngineClient
import dev.ide.runtime.engine.EngineRequest
import dev.ide.runtime.engine.EngineSnapshot
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optional component delivery is real Play split installation, never an APK/native self-updater.
 * The diagnostic executes a bundled script through the same EngineClient used by generated apps.
 */
@Composable
fun EnginePackPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val application = context.applicationContext
    val scope = rememberCoroutineScope()
    val selected = if (BuildConfig.APP_ENGINE == "node") Engine.NODE else Engine.PYTHON
    val delivery = BuildConfig.APP_ENGINE_DELIVERY
    val manager = remember(application) { SplitInstallManagerFactory.create(application) }
    var available by remember { mutableStateOf<EngineAssets.Availability?>(null) }
    var installedModules by remember { mutableStateOf(manager.installedModules) }
    var install by remember { mutableStateOf<SplitInstallSessionState?>(null) }
    var message by remember { mutableStateOf("") }
    var showInstallConsent by remember { mutableStateOf(false) }
    var showRunConsent by remember { mutableStateOf(false) }
    var nodeConsent by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<EngineSnapshot?>(null) }
    var input by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { EngineAssets.availability(application, selected) }
            available = result
            installedModules = manager.installedModules
        }
    }
    val client = remember(application) {
        EngineClient(application, object : EngineClient.Listener {
            override fun onState(state: EngineSnapshot) { snapshot = state }
            override fun onInputAccepted(bytes: Int) {
                if (bytes > 0) input = "" else message = if (bytes == 0) "Input busy; retry." else "Input is closed."
            }
        })
    }
    val confirmation = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        message = if (it.resultCode == Activity.RESULT_OK) "Play installation confirmed; waiting for delivery." else "Play installation was not approved."
        refresh()
    }
    DisposableEffect(manager, client) {
        val listener = SplitInstallStateUpdatedListener { state ->
            if (MODULE !in state.moduleNames()) return@SplitInstallStateUpdatedListener
            install = state
            when (state.status()) {
                SplitInstallSessionStatus.INSTALLED -> {
                    // SplitCompat only activates Play-verified installed split APKs, never our own downloads.
                    SplitCompat.install(application)
                    context.activity()?.let(SplitCompat::installActivity)
                    message = "Signed component installed. Verifying its actual native libraries and assets."
                    refresh()
                }
                SplitInstallSessionStatus.FAILED -> message = "Play install failed (${state.errorCode()}). No alternative native download is attempted."
                SplitInstallSessionStatus.CANCELED -> message = "Component installation canceled."
            }
        }
        manager.registerListener(listener)
        manager.sessionStates.addOnSuccessListener { sessions ->
            install = sessions.lastOrNull { MODULE in it.moduleNames() }
        }.addOnFailureListener { message = "Play delivery status unavailable: ${it.message}" }
        onDispose { manager.unregisterListener(listener); client.close() }
    }
    LaunchedEffect(selected, delivery) { refresh() }
    val running = snapshot?.isTerminal == false
    val installing = install?.let { !it.hasTerminalStatus() } == true

    val showDetails = expanded || running || installing
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.md))
                .clickable { expanded = !expanded }.defaultMinSize(minHeight = 60.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Generated-app engine", style = MaterialTheme.typography.bodyMedium)
                Text("Optional packaging component · Details", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (showDetails) CaIcons.chevronDown else CaIcons.chevronRight, "Generated-app engine details", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showDetails) {
            Text("${selected.id} · $delivery · ${when { available == null -> "Checking availability…"; available?.installed == true -> "installed ${available?.version}"; else -> "not installed" }}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("appengine-availability"))
            Text("Generated apps carry their own selected runtime. This optional packaging/diagnostic component is separate from Developer tools and the IDE Run action.", style = MaterialTheme.typography.bodySmall)
            Text("Same UID, not a sandbox. GPL runtime linking has no exception. Node Android is upstream-unqualified; native addons/wheels and package installers are unsupported.", style = MaterialTheme.typography.bodySmall)
            available?.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (delivery == "none") {
                Text("Core build contains no Node/Python payload. For sideloading, install a trusted full/direct build made with IDE_APP_ENGINE_DELIVERY=direct. This app will not fetch an APK or native engine outside Play.")
            } else if (delivery == "play" && available?.installed != true) {
                Button(onClick = { showInstallConsent = true }, enabled = !installing, modifier = Modifier.testTag("appengine-install")) { Text("Install signed ${selected.id} component") }
                Text("Delivery requires this app to be installed through an authorized Play test/release track. A local AAB alone does not prove Play delivery.", style = MaterialTheme.typography.bodySmall)
            }
            install?.let { state ->
                Text("Play session ${state.sessionId()}: ${state.status()} · ${state.bytesDownloaded()}/${state.totalBytesToDownload()} bytes")
                if (installing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { manager.cancelInstall(state.sessionId()).addOnFailureListener { message = it.message.orEmpty() } }) { Text("Cancel install") }
                }
                if (state.status() == SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION) {
                    Button(onClick = {
                        try { if (!manager.startConfirmationDialogForResult(state, confirmation)) message = "Play did not provide a confirmation dialog." }
                        catch (failed: Exception) { message = "Cannot confirm install: ${failed.message}" }
                    }) { Text("Confirm Play download") }
                }
            }
            Text("Installed split modules: ${installedModules.sorted().joinToString().ifBlank { "none" }}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = ::refresh, enabled = !running) { Text("Recheck") }
                Button(onClick = { showRunConsent = true }, enabled = available?.installed == true && !running, modifier = Modifier.testTag("appengine-run")) { Text("Run offline check") }
                OutlinedButton(onClick = client::stop, enabled = running, modifier = Modifier.testTag("appengine-stop")) { Text("Stop") }
            }
            snapshot?.let { state ->
                Text("${state.phase} · pid ${state.processId} · exit ${state.exitCode ?: "—"}", modifier = Modifier.testTag("appengine-status"))
                if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
                if (state.acceptsInput) {
                    OutlinedTextField(input, { input = it }, label = { Text("Input line (max 4096 UTF-8 bytes)") }, modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(onClick = {
                            val bytes = (input + "\n").toByteArray(Charsets.UTF_8)
                            if (bytes.size > 4096) message = "Input too long."
                            else if (!client.writeInput(bytes)) message = "Input busy; retry."
                        }) { Text("Send") }
                        TextButton(onClick = client::closeInput) { Text("EOF") }
                    }
                }
                SelectionContainer {
                    Text((state.stdout + "\n[stderr]\n" + state.stderr).takeLast(24000), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()))
                }
                if (state.stdoutTruncated || state.stderrTruncated) Text("Older output discarded; memory remains bounded.")
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showInstallConsent) AlertDialog(
        onDismissRequest = { showInstallConsent = false }, title = { Text("Install optional engine?") },
        text = { Text("Google Play will deliver the app-signed $selected runtime component. This may use mobile data. No code runs automatically and no APK is downloaded from another source.") },
        confirmButton = { TextButton(onClick = {
            showInstallConsent = false
            manager.startInstall(SplitInstallRequest.newBuilder().addModule(MODULE).build())
                .addOnSuccessListener { message = "Play install requested: session $it" }
                .addOnFailureListener { message = "Play delivery unavailable: ${it.message}. Use a Play-installed build or a trusted direct/full build." }
        }) { Text("Install through Play") } },
        dismissButton = { TextButton(onClick = { showInstallConsent = false }) { Text("Cancel") } },
    )
    if (showRunConsent) AlertDialog(
        onDismissRequest = { showRunConsent = false }, title = { Text("Execute bundled diagnostic?") },
        text = { Column {
            Text("This trusted bundled script runs in a separate :runtime process with this app's UID and permissions. It is not sandboxed. It calculates offline, writes a private counter and exits; it never installs packages.")
            if (selected == Engine.NODE) Row {
                Checkbox(nodeConsent, { nodeConsent = it }); Text("I understand Node Android is unqualified upstream.")
            }
        } },
        confirmButton = { TextButton(enabled = selected != Engine.NODE || nodeConsent, onClick = {
            showRunConsent = false
            try { client.start(EngineRequest(selected, if (selected == Engine.PYTHON) "engine-check.py" else "engine-check.js", emptyList(), 15000, 32768, nodeConsent)) }
            catch (failed: Exception) { message = failed.message.orEmpty() }
        }) { Text("Run trusted script") } },
        dismissButton = { TextButton(onClick = { showRunConsent = false }) { Text("Cancel") } },
    )
}

private const val MODULE = "runtime_engine_ide_feature"
private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.activity()
    else -> null
}
