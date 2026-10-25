package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.pressScale
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.import_source_title
import dev.ide.ui.generated.resources.import_source_message
import dev.ide.ui.generated.resources.import_source_folder
import dev.ide.ui.generated.resources.import_source_folder_desc
import dev.ide.ui.generated.resources.import_source_package
import dev.ide.ui.generated.resources.import_source_package_desc
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * "Import project": which SOURCE the project comes from. The two live behind different host APIs
 * ([dev.ide.ui.backend.FileActions.pickDirectory] vs [dev.ide.ui.backend.FileActions.pickFile]), so the
 * choice has to be made before a picker can be launched. Options the host cannot service are hidden rather
 * than shown failing.
 */
@Composable
fun ImportSourceDialog(
    visible: Boolean,
    canPickFolder: Boolean,
    canPickPackage: Boolean,
    onFolder: () -> Unit,
    onPackage: () -> Unit,
    onDismiss: () -> Unit,
) {
    CenteredDialog(visible = visible, onDismiss = onDismiss) {
        DialogCard {
            Text(
                stringResource(Res.string.import_source_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(Res.string.import_source_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(4.dp))
            if (canPickFolder) ModeOption(
                icon = CaIcons.folderOpen,
                title = stringResource(Res.string.import_source_folder),
                description = stringResource(Res.string.import_source_folder_desc),
                onClick = onFolder,
            )
            if (canPickPackage) ModeOption(
                icon = CaIcons.pkg,
                title = stringResource(Res.string.import_source_package),
                description = stringResource(Res.string.import_source_package_desc),
                accent = true,
                onClick = onPackage,
            )
        }
    }
}

@Composable
private fun DialogCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .widthIn(max = 400.dp)
            .padding(horizontal = 24.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.lg))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun ModeOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val border = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier.fillMaxWidth()
            .pressScale(interaction)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.md))
            .border(1.dp, border, RoundedCornerShape(Ca.radius.md))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
