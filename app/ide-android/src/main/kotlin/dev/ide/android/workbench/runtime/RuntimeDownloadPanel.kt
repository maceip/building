package dev.ide.android.workbench.runtime

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.*
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus as Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Installation and enablement are independent. Downloading never starts project code. */
@Composable
fun RuntimeDownloadPanel() {
    val context = LocalContext.current
    val app = context.applicationContext
    val catalog = remember(app) { RuntimePacks.catalog(app) } ?: return
    val manager = remember(app) { SplitInstallManagerFactory.create(app) }
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(RuntimePacks.nodeInstalled(app)) }
    var session by remember { mutableStateOf<SplitInstallSessionState?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    fun refresh() {
        installed = RuntimePacks.nodeInstalled(app)
        if (installed) {
            verifying = true
            scope.launch {
                try {
                    val pack = withContext(Dispatchers.IO) { AndroidRuntimeHost(app).prepare() }
                    check(pack.node != null && pack.npmCli != null) { "Node pack is incomplete" }
                    message = "Installed and verified. The switches below control new sessions."
                } catch (failed: Exception) { message = "Verification failed: ${failed.message}" }
                finally { verifying = false }
            }
        }
    }
    val confirmation = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode != Activity.RESULT_OK) message = "Download confirmation canceled."
    }
    DisposableEffect(manager) {
        val listener = SplitInstallStateUpdatedListener { state ->
            if (RuntimePacks.NODE_MODULE !in state.moduleNames()) return@SplitInstallStateUpdatedListener
            session = state
            busy = !state.hasTerminalStatus()
            when (state.status()) {
                Status.INSTALLED -> {
                    SplitCompat.install(app)
                    context.activity()?.let { SplitCompat.installActivity(it) }
                    refresh()
                }
                Status.FAILED -> message = "Download failed (${state.errorCode()}). Check your connection and available storage, then retry."
                Status.CANCELED -> message = "Download canceled."
            }
        }
        manager.registerListener(listener)
        manager.sessionStates.addOnSuccessListener { states ->
            states.lastOrNull { RuntimePacks.NODE_MODULE in it.moduleNames() }?.let {
                session = it; busy = !it.hasTerminalStatus()
            }
        }.addOnFailureListener { message = "Cannot read download status: ${it.message}" }
        onDispose { manager.unregisterListener(listener) }
    }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Node.js download", style = MaterialTheme.typography.titleMedium)
        Text(if (installed) "Installed" else "Not installed", Modifier.testTag("runtime-node-installed"))
        Text("Node.js, npm, pnpm and Node headers. Python, shell and shared build tools are already included.", style = MaterialTheme.typography.bodySmall)
        if (!installed) {
            Text("Google Play supplies the optional pack. Downloading it keeps your runtime switches unchanged.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy, modifier = Modifier.testTag("runtime-node-download"), onClick = {
                busy = true; message = "Requesting Node.js…"
                manager.startInstall(SplitInstallRequest.newBuilder().addModule(RuntimePacks.NODE_MODULE).build())
                    .addOnFailureListener { busy = false; message = "Download unavailable: ${it.message}. Install this build through Google Play or the local pack test installer." }
            }) { Text(if (session?.status() == Status.FAILED) "Retry download" else "Download Node.js") }
        }
        session?.takeIf { busy }?.let { state ->
            val total = state.totalBytesToDownload()
            Text(if (total > 0) "${state.bytesDownloaded() / 1048576} / ${total / 1048576} MB" else "Preparing download…")
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { manager.cancelInstall(state.sessionId()).addOnFailureListener { message = it.message.orEmpty() } }) { Text("Cancel download") }
            if (state.status() == Status.REQUIRES_USER_CONFIRMATION) Button(onClick = {
                runCatching { manager.startConfirmationDialogForResult(state, confirmation) }.onFailure { message = it.message.orEmpty() }
            }) { Text("Confirm download") }
        }
        if (verifying) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (installed) TextButton(onClick = ::refresh, enabled = !verifying) { Text("Verify installed pack") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("runtime-node-message"))
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.activity()
    else -> null
}
