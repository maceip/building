package dev.ide.android.workbench.runtime

import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.ide.android.workbench.GradleToolchainPanel
import dev.ide.android.workbench.appengine.EnginePackPanel
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.SettingsService
import dev.ide.ui.backend.UiSettingControl
import dev.ide.ui.theme.Ca
import dev.ide.workbench.runtime.RuntimeFeature
import dev.ide.workbench.runtime.RuntimePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** APK inventory only: opening Settings never provisions, probes, or executes a runtime. */
@Composable
fun AndroidDeveloperTools(backend: IdeBackend) {
    val context = LocalContext.current
    val application = context.applicationContext
    val scope = rememberCoroutineScope()
    var refresh by remember(backend) { mutableStateOf(0) }
    var inventory by remember(application) { mutableStateOf<DeveloperRuntimeInventory?>(null) }
    var inventoryError by remember(application) { mutableStateOf(false) }
    var preferences by remember(backend) { mutableStateOf<Map<RuntimeFeature, Boolean>?>(null) }
    var settingsError by remember(backend) { mutableStateOf<String?>(null) }
    var saving by remember(backend) { mutableStateOf(false) }

    // Re-read when returning to the app, including changes made by the compatibility Settings page.
    val lifecycle = remember(context) {
        generateSequence(context) { current ->
            (current as? ContextWrapper)?.baseContext?.takeIf { it !== current }
        }.filterIsInstance<LifecycleOwner>().firstOrNull()?.lifecycle
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(application, refresh) {
        inventory = null
        inventoryError = false
        try {
            inventory = loadDeveloperRuntimeInventory {
                application.assets.open("developer-runtime/manifest.json").bufferedReader().use { it.readText() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            inventoryError = true
        }
    }
    LaunchedEffect(backend, refresh) {
        if (saving) return@LaunchedEffect
        preferences = null
        settingsError = null
        try {
            preferences = withContext(Dispatchers.IO) { developerToolPreferences(backend.settings) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            settingsError = "Tool settings could not be loaded."
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Developer tools", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Column(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.md))
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            RuntimeFeature.entries.forEachIndexed { index, feature ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val tool = inventory?.tool(feature)
                val detail = when {
                    feature == RuntimeFeature.STATIC -> "Built in · Local web server"
                    inventoryError -> "Inventory unavailable"
                    inventory == null -> "Loading bundled version…"
                    else -> tool?.description ?: "Not bundled"
                }
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(feature.toolTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = preferences?.get(feature) ?: true,
                        enabled = preferences != null && !saving,
                        modifier = Modifier.testTag("sdk-tool-${feature.key}")
                            .semantics { contentDescription = "Enable ${feature.toolTitle}" },
                        onCheckedChange = { enabled ->
                            saving = true
                            settingsError = null
                            scope.launch {
                                try {
                                    val updated = withContext(Dispatchers.IO) {
                                        backend.settings.setSetting(RuntimeFeature.PAGE_ID, feature.key, enabled.toString())
                                        developerToolPreferences(backend.settings)
                                    }
                                    preferences = updated
                                    if (updated[feature] != enabled) settingsError = "The setting was not saved. Try again."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    settingsError = "The setting could not be saved. Try again."
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    )
                }
            }
        }
        if (preferences == null && settingsError == null) {
            Text("Loading tool settings…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (inventoryError || settingsError != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    settingsError ?: "Bundled inventory could not be read.",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { refresh++ }, enabled = !saving) { Text("Retry") }
            }
        }
        Text(
            "Bundled versions are declared by this app, not a running check. Switches control new IDE sessions; build dependencies stay installed.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GradleToolchainPanel(Modifier.fillMaxWidth())
        EnginePackPanel(Modifier.fillMaxWidth())
    }
}

internal val RuntimeFeature.toolTitle: String
    get() = when (this) {
        RuntimeFeature.NODE -> "Node.js"
        RuntimeFeature.PYTHON -> "Python"
        RuntimeFeature.SHELL -> "Shell"
        RuntimeFeature.STATIC -> "Static preview"
    }

internal data class DeveloperToolInventory(val version: String, val secondary: String? = null) {
    val description: String get() = listOfNotNull("Bundled · $version", secondary).joinToString(" · ")
}

internal data class DeveloperRuntimeInventory(val packages: Map<String, String>, val npmTools: Map<String, String>) {
    fun tool(feature: RuntimeFeature): DeveloperToolInventory? = when (feature) {
        RuntimeFeature.NODE -> (packages["nodejs-lts"] ?: packages["nodejs"])?.let { version ->
            DeveloperToolInventory(version, listOfNotNull(
                packages["npm"]?.let { "npm $it" },
                npmTools["pnpm"]?.let { "pnpm $it" },
            ).joinToString(" · ").takeIf { it.isNotEmpty() })
        }
        RuntimeFeature.PYTHON -> packages["python"]?.let { DeveloperToolInventory(it, packages["python-pip"]?.let { pip -> "pip $pip" }) }
        RuntimeFeature.SHELL -> (packages["dash"] ?: packages["bash"])?.let { DeveloperToolInventory(it) }
        RuntimeFeature.STATIC -> null // App-owned server, not a package with an invented version.
    }
}

/** Validate declared versions rather than hardcoding the full pack's capabilities or executing binaries. */
internal fun parseDeveloperRuntimeInventory(text: String): DeveloperRuntimeInventory {
    val manifest = JSONObject(text)
    require(manifest.getInt("format") == 1) { "Unsupported runtime manifest" }
    fun versions(key: String, optional: Boolean = false): Map<String, String> {
        val array = if (optional && !manifest.has(key)) return emptyMap() else manifest.getJSONArray(key)
        val result = linkedMapOf<String, String>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val name = item.get("name") as? String ?: error("Package name must be text")
            val version = item.get("version") as? String ?: error("Package version must be text")
            require(name.isNotBlank() && name.none { it.isWhitespace() || it.isISOControl() }) { "Invalid package name" }
            require(version.isNotBlank() && version.none { it.isWhitespace() || it.isISOControl() }) { "Invalid package version" }
            require(result.put(name, version) == null) { "Duplicate package declaration" }
        }
        return result
    }
    return DeveloperRuntimeInventory(versions("packages"), versions("npmTools", optional = true))
}

/** Keep the asset read and JSON parsing off the main thread, including retries. */
internal suspend fun loadDeveloperRuntimeInventory(readManifest: () -> String): DeveloperRuntimeInventory =
    withContext(Dispatchers.IO) { parseDeveloperRuntimeInventory(readManifest()) }

/** Fresh backend descriptors, with the existing preference keys/defaults as the compatibility fallback. */
internal fun developerToolPreferences(settings: SettingsService): Map<RuntimeFeature, Boolean> {
    val controls = settings.settingsPages().firstOrNull { it.id == RuntimeFeature.PAGE_ID }?.controls
        .orEmpty().filterIsInstance<UiSettingControl.Toggle>().associateBy { it.key }
    val fallback = RuntimePreferences(settings::preference)
    return RuntimeFeature.entries.associateWith { feature -> controls[feature.key]?.value ?: fallback.enabled(feature) }
}
