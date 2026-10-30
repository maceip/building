package fragments.agentui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fragments.components.UiText
import fragments.components.UiButton

/** Read-only JSON output with a full-value copy action and a bounded, foldable viewport. */
@Composable
fun AgentJsonView(
    json: String,
    palette: AgentUiPalette = AgentUiTheme.palette,
    label: String = "JSON",
    collapseAfter: Int = 24,
    modifier: Modifier = Modifier,
) {
    require(collapseAfter > 0) { "collapseAfter must be positive" }
    val lines = remember(json) { json.lines() }
    var showAll by remember(json) { mutableStateOf(false) }
    val shown = remember(lines, showAll, collapseAfter) {
        (if (showAll || lines.size <= collapseAfter) lines else lines.take(collapseAfter)).joinToString("\n")
    }
    val highlighted = remember(shown, palette) { colorJson(shown, palette) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            UiText(label, palette, muted = true, size = 12)
            AgentCopyButton(json, label = "Copy $label", palette = palette)
        }
        Box(Modifier.fillMaxWidth().heightIn(max = 320.dp)
            .border(1.dp, palette.border, RoundedCornerShape(palette.toolRadius))
            .background(palette.surface, RoundedCornerShape(palette.toolRadius))
            .verticalScroll(rememberScrollState())
            .semantics { contentDescription = "$label output" }
            .testTag("agent-json-output")
            .padding(10.dp)) {
            BasicText(highlighted, Modifier.horizontalScroll(rememberScrollState()),
                style = TextStyle(color = palette.text, fontSize = 12.sp,
                    lineHeight = 20.sp, fontFamily = FontFamily.Monospace), softWrap = false)
        }
        if (lines.size > collapseAfter) {
            UiButton(if (showAll) "Show less" else "Show all ${lines.size} lines",
                { showAll = !showAll }, palette)
        }
    }
}

private fun colorJson(value: String, palette: AgentUiPalette) = buildAnnotatedString {
    var index = 0
    while (index < value.length) {
        when {
            value[index] == '"' -> {
                val start = index++
                var escaped = false
                while (index < value.length) {
                    val char = value[index++]
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') break
                }
                var next = index
                while (next < value.length && value[next].isWhitespace()) next++
                withStyle(SpanStyle(color = if (next < value.length && value[next] == ':')
                    palette.accent else palette.success)) { append(value, start, index) }
            }
            value[index].isDigit() || value[index] == '-' &&
                index + 1 < value.length && value[index + 1].isDigit() -> {
                val start = index++
                while (index < value.length && (value[index].isDigit() ||
                    value[index] in ".eE+-")) index++
                withStyle(SpanStyle(color = palette.accent)) { append(value, start, index) }
            }
            listOf("true", "false", "null").any { value.startsWith(it, index) } -> {
                val token = listOf("true", "false", "null").first { value.startsWith(it, index) }
                withStyle(SpanStyle(color = palette.muted)) { append(token) }
                index += token.length
            }
            else -> append(value[index++])
        }
    }
}
