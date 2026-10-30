package fragments.agentui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Source-preserving code lines; highlighting is computed when content or theme changes. */
@Composable
fun AgentCodeBlock(
    lines: List<String>,
    palette: AgentUiPalette = AgentUiTheme.palette,
    language: String = "text",
    modifier: Modifier = Modifier,
    softWrap: Boolean = false,
) {
    val sizing = AgentUiTheme.sizing
    val normalizedLanguage = remember(language) { language.lowercase() }
    val highlighted = remember(lines, normalizedLanguage, palette) {
        buildAnnotatedString {
            lines.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                append(highlightCodeLine(line, normalizedLanguage, palette))
            }
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .background(palette.background, RoundedCornerShape(if (sizing.isMobile) 8.dp else 6.dp))
            .testTag("agent-code-block")
            .padding(if (sizing.isMobile) 10.dp else 8.dp),
    ) {
        BasicText(
            highlighted,
            if (softWrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()),
            style = TextStyle(
                color = palette.text,
                fontSize = (if (sizing.isMobile) 13 else 12).sp,
                lineHeight = 20.sp,
                fontFamily = FontFamily.Monospace,
            ),
            softWrap = softWrap,
        )
    }
}

private val codeKeywords = mapOf(
    "js" to ("abstract as async await break case catch class const continue debugger declare default delete do else enum export extends " +
        "false finally for from function get if implements import in instanceof interface let new null of private protected public " +
        "readonly return satisfies set static super switch this throw true try type typeof undefined var void while with yield").split(' ').toSet(),
    "py" to "and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return True try while with yield".split(' ').toSet(),
    "sh" to "if then else elif fi for in do done while case esac function export local return".split(' ').toSet(),
    "kt" to "as class data else false for fun if import in interface is null object private return true val var when".split(' ').toSet(),
)

internal fun highlightCodeLine(line: String, language: String, palette: AgentUiPalette): AnnotatedString =
    buildAnnotatedString {
        if (language == "text" || language == "md") {
            append(line)
            return@buildAnnotatedString
        }
        val keywords = when (language) {
            "py", "python" -> codeKeywords.getValue("py")
            "sh", "bash", "shell" -> codeKeywords.getValue("sh")
            "kt", "kotlin" -> codeKeywords.getValue("kt")
            "js", "jsx", "ts", "tsx", "javascript", "typescript" -> codeKeywords.getValue("js")
            else -> emptySet()
        }
        val hashComments = language == "py" || language == "python" || language == "sh" ||
            language == "bash" || language == "shell" || language == "yaml"
        val propertyStrings = language == "json" || language == "yaml" || language == "css"
        val jsonLiterals = language == "json"
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                (hashComments && char == '#') ||
                    (char == '/' && index + 1 < line.length && line[index + 1] == '/' && !jsonLiterals) -> {
                    withStyle(SpanStyle(color = palette.muted)) { append(line, index, line.length) }
                    break
                }
                char == '"' || char == '\'' || char == '`' -> {
                    val start = index++
                    var escaped = false
                    while (index < line.length) {
                        val current = line[index++]
                        if (escaped) escaped = false
                        else if (current == '\\') escaped = true
                        else if (current == char) break
                    }
                    var next = index
                    while (next < line.length && line[next].isWhitespace()) next++
                    val isProperty = propertyStrings &&
                        next < line.length && line[next] == ':'
                    withStyle(SpanStyle(color = if (isProperty) palette.accent else palette.success)) {
                        append(line, start, index)
                    }
                }
                char.isDigit() || char == '-' && index + 1 < line.length && line[index + 1].isDigit() -> {
                    val start = index++
                    while (index < line.length && (line[index].isDigit() || line[index] in ".eE_xXaAbBcCdDfF+-")) index++
                    withStyle(SpanStyle(color = palette.accent)) { append(line, start, index) }
                }
                char.isLetter() || char == '_' || char == '$' -> {
                    val start = index++
                    while (index < line.length && (line[index].isLetterOrDigit() ||
                        line[index] == '_' || line[index] == '$')) index++
                    val word = line.substring(start, index)
                    if (word in keywords || jsonLiterals && (word == "true" || word == "false" || word == "null")) {
                        withStyle(SpanStyle(color = palette.accent)) { append(word) }
                    } else append(word)
                }
                char in "{}()[ ];,.<>/=+*!?:&|%^~@#-" -> {
                    withStyle(SpanStyle(color = palette.muted)) { append(char) }
                    index++
                }
                else -> append(line[index++])
            }
        }
    }
