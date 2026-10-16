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
    val app = LocalContext.current.applicationContext
    val catalogs = remember(app) { RuntimePacks.catalogs(app) }
    Column {
        catalogs.filterNot { it.optBoolean("hidden") }.forEach { catalog -> key(catalog.getString("module")) {
            RuntimePackDownload(catalog.getString("module"))
        } }
    }
}

@Composable
private fun RuntimePackDownload(module: String) {
    val context = LocalContext.current
    val app = context.applicationContext
    val isNode = module == RuntimePacks.NODE_MODULE
    val name = if (isNode) "Node.js" else "Swift and xtool"
    val tag = if (isNode) "node" else "xtool"
    val manager = remember(app) { SplitInstallManagerFactory.create(app) }
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(RuntimePacks.installed(app, module)) }
    var session by remember { mutableStateOf<SplitInstallSessionState?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    fun refresh() {
        installed = RuntimePacks.installed(app, module)
        if (installed) {
            verifying = true
            scope.launch {
                try {
                    val pack = withContext(Dispatchers.IO) { AndroidRuntimeHost(app).prepare() }
                    if (isNode) check(pack.node != null && pack.npmCli != null) { "Node pack is incomplete" }
                    else check(pack.xtool != null && java.nio.file.Files.isRegularFile(pack.prefix.resolve("bin/swiftc"))) { "Swift pack is incomplete" }
                    message = "Installed and verified. The switches below control new sessions."
                } catch (failed: Exception) { message = "Verification failed: ${failed.message}" }
                finally { verifying = false }
            }
        }
    }
    val confirmation = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode != Activity.RESULT_OK) message = "Download confirmation canceled."
    }
    DisposableEffect(manager, module) {
        val listener = SplitInstallStateUpdatedListener { state ->
            if (module !in state.moduleNames()) return@SplitInstallStateUpdatedListener
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
            states.lastOrNull { module in it.moduleNames() }?.let {
                session = it; busy = !it.hasTerminalStatus()
            }
        }.addOnFailureListener { message = "Cannot read download status: ${it.message}" }
        onDispose { manager.unregisterListener(listener) }
    }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$name download", style = MaterialTheme.typography.titleMedium)
        Text(if (installed) "Installed" else "Not installed", Modifier.testTag("runtime-$tag-installed"))
        Text(if (isNode) "Node.js, npm, pnpm and Node headers." else "Swift compiler, Swift Package Manager and xtool. Building for iPhone also requires an Apple SDK that you supply.", style = MaterialTheme.typography.bodySmall)
        if (!installed) {
            Text("Google Play supplies the optional pack. Downloading it keeps your runtime switches unchanged.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy, modifier = Modifier.testTag("runtime-$tag-download"), onClick = {
                busy = true; message = "Requesting $name…"
                val request = SplitInstallRequest.newBuilder()
                RuntimePacks.requestModules(app, module).forEach { request.addModule(it) }
                manager.startInstall(request.build())
                    .addOnFailureListener { busy = false; message = "Download unavailable: ${it.message}. Install this build through Google Play or the local pack test installer." }
            }) { Text(if (session?.status() == Status.FAILED) "Retry download" else "Download $name") }
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
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("runtime-$tag-message"))
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.activity()
    else -> null
}
