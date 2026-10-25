package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Symbol

/**
 * "Start something new" bottom sheet: describe-it prompt, stack chips, primary CTA,
 * then bring-existing-code rows.
 */
@Composable
fun NewProjectSheetContent(
    prompt: String,
    onPromptChange: (String) -> Unit,
    onStartBuilding: () -> Unit,
    onClose: () -> Unit = {},
    stacks: List<String> = listOf("Android app", "Python", "Node.js", "Let agent decide"),
    selectedStack: Int = 0,
    onSelectStack: (Int) -> Unit = {},
    /** Paste a Git/GitHub URL and clone + open — same contract as the home QuickCloneBar. */
    onCloneUrl: (String) -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onImportPackage: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var cloneUrl by rememberSaveable { mutableStateOf("") }
    val fieldShape = RoundedCornerShape(10.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(c.surfaceContainer)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(c.outlineVariant),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "New project",
                style = MaterialTheme.typography.titleLarge,
                color = c.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButtonCa(
                icon = CaIcons.close,
                contentDescription = "Close",
                onClick = onClose,
                iconSize = 18,
                boxSize = 40,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Describe it — the agent picks the stack",
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.surfaceContainerLow)
                    .border(1.dp, c.outlineVariant, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                BasicTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    singleLine = false,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                    cursorBrush = SolidColor(c.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth()) {
                            if (prompt.isEmpty()) {
                                Text(
                                    "A habit tracker with a home-screen widget…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = c.outline,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                stacks.forEachIndexed { i, label ->
                    val selected = i == selectedStack
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectStack(i) },
                        label = {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            Button(
                onClick = onStartBuilding,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text("Start building", style = MaterialTheme.typography.labelLarge)
            }
        }

        Text(
            "Or clone a repository",
            style = MaterialTheme.typography.labelSmall,
            color = c.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(fieldShape)
                    .background(c.surfaceContainerLow)
                    .border(1.dp, c.outlineVariant, fieldShape)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Symbol(CaSymbols.forkRight, contentDescription = null, size = 16.dp, tint = c.onSurfaceVariant)
                BasicTextField(
                    value = cloneUrl,
                    onValueChange = { cloneUrl = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.onSurface),
                    cursorBrush = SolidColor(c.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (cloneUrl.isEmpty()) {
                                Text(
                                    "Paste GitHub URL or owner/repo to clone…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (cloneUrl.isNotEmpty()) {
                    IconButtonCa(
                        CaIcons.close,
                        "Clear",
                        { cloneUrl = "" },
                        iconSize = 14,
                        boxSize = 24,
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    val clip = clipboard.getText()?.text?.trim().orEmpty()
                    if (clip.isNotBlank()) cloneUrl = clip
                },
                shape = fieldShape,
                border = BorderStroke(1.dp, c.outlineVariant),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.height(38.dp),
            ) {
                Text("Paste", style = MaterialTheme.typography.labelLarge)
            }
            Button(
                onClick = {
                    val url = cloneUrl.trim()
                    if (url.isNotEmpty()) onCloneUrl(url)
                },
                shape = fieldShape,
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.height(38.dp),
                enabled = cloneUrl.isNotBlank(),
            ) {
                Symbol(CaSymbols.forkRight, contentDescription = null, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text("Clone", style = MaterialTheme.typography.labelLarge)
            }
        }

        Text(
            "Or bring existing code",
            style = MaterialTheme.typography.labelSmall,
            color = c.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NewProjectSheetAction(CaSymbols.folderOpen, "Open a folder on this phone", onOpenFolder)
            NewProjectSheetAction(CaSymbols.download, "Import a .caproj package", onImportPackage)
        }
    }
}

/** Sheet row matching [SheetAction]: icon tile + title, full-width tap target. */
@Composable
private fun NewProjectSheetAction(glyph: Char, label: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        contentColor = c.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(c.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(glyph, contentDescription = null, size = 20.dp, tint = c.onSurfaceVariant)
            }
            Text(label, style = MaterialTheme.typography.titleSmall, color = c.onSurface)
        }
    }
}

