package dev.ide.android.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ide.android.BuildConfig
import dev.ide.android.gradle.AndroidGradleHost
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GradleToolchainPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    // Construction creates private directories, so defer it to the explicit detail check on IO.
    var host by remember(context) { mutableStateOf<AndroidGradleHost?>(null) }
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(open) {
        if (open) {
            available = null
            status = ""
            try {
                val result = withContext(Dispatchers.IO) {
                    val toolchain = host ?: AndroidGradleHost(context)
                    toolchain to toolchain.available()
                }
                host = result.first
                available = result.second
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = "Toolchain status unavailable. Close and reopen to retry."
            }
        }
    }
    Row(
        modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.md))
            .clickable { open = true }.defaultMinSize(minHeight = 60.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Gradle toolchain", style = MaterialTheme.typography.bodyMedium)
            Text("Optional native setup · Details", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(CaIcons.chevronRight, "Open Gradle toolchain details", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!open) return
    AlertDialog(
        onDismissRequest = { if (!installing) open = false },
        title = { Text("On-device Gradle toolchain") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(when (available) {
                    true -> "The pinned native Gradle toolchain is installed."
                    false -> "The native Gradle toolchain is not ready."
                    null -> if (status.isEmpty()) "Checking installed toolchain…" else "Installed status unknown."
                })
                Text("Node, npm, Python, shell, static previews, and native Android builds use APK-delivered components and do not require this download.")
                if (BuildConfig.NATIVE_TOOLCHAIN_DOWNLOADS_ENABLED) {
                    Text("This developer-build action downloads approximately 323 MiB of pinned native packages into private app storage. Unpacked storage is larger. Use a reliable connection; the setup may take several minutes.")
                    Text("The downloaded native toolchain is for developer distributions, not a claim of Google Play policy compliance. Nothing downloads until you choose Install.")
                } else {
                    Text("This distribution disables native toolchain downloads. Use an APK-delivered toolchain or a developer distribution for real Gradle execution.")
                }
                if (installing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(progress, style = MaterialTheme.typography.bodySmall)
                }
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (BuildConfig.NATIVE_TOOLCHAIN_DOWNLOADS_ENABLED && available == false) {
                Button(
                    enabled = !installing,
                    onClick = {
                        installing = true
                        status = ""
                        scope.launch {
                            try {
                                available = withContext(Dispatchers.IO) {
                                    val toolchain = requireNotNull(host)
                                    toolchain.installIfNeeded { line -> scope.launch { progress = line } }
                                    toolchain.available()
                                }
                                status = if (available == true) "Installed. Reopen or sync your Gradle project to use the toolchain." else "Setup did not pass its readiness checks. Retry after checking your connection."
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                status = "Toolchain setup failed. Check your connection and available storage, then retry. Incomplete downloads are not marked ready."
                            } finally {
                                installing = false
                            }
                        }
                    },
                ) { Text("Install native toolchain") }
            }
        },
        dismissButton = {
            TextButton(enabled = !installing, onClick = { open = false }) { Text("Close") }
        },
    )
}
