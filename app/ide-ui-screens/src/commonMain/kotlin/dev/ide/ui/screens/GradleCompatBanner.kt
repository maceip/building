package dev.ide.ui.screens

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.backend.UiCompatibilityInfo
import dev.ide.ui.backend.UiSyncResult
import dev.ide.ui.components.CircularProgressIndicator
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.GlassSurface
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.dismiss
import dev.ide.ui.generated.resources.gradle_convert
import dev.ide.ui.generated.resources.gradle_kmp_android
import dev.ide.ui.generated.resources.gradle_kmp_jvm
import dev.ide.ui.generated.resources.gradle_kmp_target
import dev.ide.ui.generated.resources.gradle_mode_title
import dev.ide.ui.generated.resources.gradle_resync
import dev.ide.ui.generated.resources.gradle_sync_needed
import dev.ide.ui.generated.resources.gradle_syncing
import dev.ide.ui.generated.resources.hide_details
import dev.ide.ui.generated.resources.show_details
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The editor-level notice for a **Gradle compatibility-mode** project: an amber strip under the toolbar
 * explaining that the build scripts were read statically (not run), so builds and dependency resolution may
 * fail, and dependencies/versions were extracted best-effort. Expands to the reader's per-item notes, offers
 * **Re-sync** (re-read the scripts into the model + re-resolve + re-index), and is dismissible — the top-bar
 * compat chip re-opens it, so the limitation is never truly hidden. For a Kotlin Multiplatform project whose
 * modules could be imported for Android or the desktop JVM, the details also hold that choice.
 * [onSynced] runs after a successful sync, so the caller can re-read [info] (its notes describe the import).
 */
@Composable
internal fun GradleCompatBanner(
    state: IdeUiState,
    info: UiCompatibilityInfo,
    visible: Boolean,
    compact: Boolean,
    onDismiss: () -> Unit,
    onConvert: () -> Unit,
    onSynced: () -> Unit = {},
) {
    // Intentionally empty: Gradle compatibility orange warning banner is permanently removed
}

/**
 * Which target a Kotlin Multiplatform project's modules are imported for: Android or the desktop JVM. Picking
 * the other one re-imports the project (see `ProjectService.setKmpTarget`).
 */
@Composable
private fun KmpTargetChoice(current: String, enabled: Boolean, onPick: (String) -> Unit) {
    Row(
        Modifier.padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(Res.string.gradle_kmp_target),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
        )
        for ((id, label) in listOf("android" to Res.string.gradle_kmp_android, "jvm" to Res.string.gradle_kmp_jvm)) {
            val selected = id == current
            Text(
                stringResource(label),
                modifier = Modifier
                    .background(Ide.colors.warning.copy(alpha = if (selected) 0.30f else 0.10f), RoundedCornerShape(Ca.radius.pill))
                    .clickable(enabled = enabled && !selected) { onPick(id) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = Ide.colors.warning,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
