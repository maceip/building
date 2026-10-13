package dev.ide.android.workbench.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** All externally supplied prose is plain text, never rendered as HTML/Markdown or interpreted as actions. */
@Composable
internal fun QualityText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = Int.MAX_VALUE,
) {
    val safe = remember(text) { qualityRedactUiText(text) }
    Text(safe, modifier, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun QualityCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QualityText(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

@Composable
internal fun QualityFold(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(
                qualityRedactUiText(if (expanded) "Hide · $title" else "Show · $title"),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (expanded) content()
    }
}

@Composable
internal fun QualityEvidenceText(text: String, maxChars: Int = 6_000) {
    val safe = remember(text) { qualityRedactUiText(text) }
    SelectionContainer {
        Text(
            safe.take(maxChars),
            modifier = Modifier.fillMaxWidth(),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (safe.length > maxChars) {
        QualityText("View limited to $maxChars characters. The bounded report remains available for export.")
    }
}

@Composable
internal fun QualityMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        QualityText(label, Modifier.weight(1f))
        QualityText(value, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Only one page is composed, even for an archive at the engine's record limit. */
@Composable
internal fun <T> QualityPagedList(
    values: List<T>,
    pageSize: Int,
    identity: Any,
    emptyText: String = "No entries.",
    newestFirstPage: Boolean = false,
    item: @Composable (T) -> Unit,
) {
    var page by remember(identity) { mutableStateOf(0) }
    if (values.isEmpty()) {
        QualityText(emptyText)
        return
    }
    val lastPage = (values.size - 1) / pageSize
    val current = page.coerceIn(0, lastPage)
    val start = if (newestFirstPage) (values.size - (current + 1) * pageSize).coerceAtLeast(0) else current * pageSize
    val end = if (newestFirstPage) values.size - current * pageSize else (start + pageSize).coerceAtMost(values.size)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (index in start until end) item(values[index])
        if (values.size > pageSize) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { page = current - 1 }, enabled = current > 0) {
                    Text(if (newestFirstPage) "Newer" else "Previous")
                }
                Text(
                    "${start + 1}–$end / ${values.size}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                )
                TextButton(onClick = { page = current + 1 }, enabled = current < lastPage) {
                    Text(if (newestFirstPage) "Older" else "Next")
                }
            }
        }
    }
}

internal fun qualityEnumLabel(name: String): String = name.lowercase(java.util.Locale.ROOT)
    .replace('_', ' ').replaceFirstChar { it.uppercase() }
