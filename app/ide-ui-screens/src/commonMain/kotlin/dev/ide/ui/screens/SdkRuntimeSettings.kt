package dev.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiSettingControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hosts without a bundled-tool inventory still retain the existing backend runtime controls. */
@Composable
internal fun SdkRuntimeSettings(backend: IdeBackend) {
    var controls by remember(backend) { mutableStateOf<List<UiSettingControl.Toggle>?>(null) }
    var error by remember(backend) { mutableStateOf<String?>(null) }
    var refresh by remember(backend) { mutableStateOf(0) }
    var saving by remember(backend) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(backend, refresh) {
        error = null
        try {
            controls = withContext(Dispatchers.Default) {
                backend.settings.settingsPages().firstOrNull { it.id == "runtimes" }
                    ?.controls.orEmpty().filterIsInstance<UiSettingControl.Toggle>()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Tool settings could not be loaded."
        }
    }
    if (controls?.isEmpty() == true && error == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Developer tools", style = MaterialTheme.typography.bodyLarge)
        if (controls == null && error == null) Text("Loading tool settings…", style = MaterialTheme.typography.bodySmall)
        controls.orEmpty().forEach { control ->
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(control.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(control.value, enabled = !saving, onCheckedChange = { enabled ->
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) { backend.settings.setSetting("runtimes", control.key, enabled.toString()) }
                            refresh++
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "The setting could not be saved."
                        } finally {
                            saving = false
                        }
                    }
                })
            }
        }
        error?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { refresh++ }, enabled = !saving) { Text("Retry") }
            }
        }
    }
}
