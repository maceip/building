package fragments.components.agentelements

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fragments.components.ReactUiPalette
import fragments.components.UiButton

private val citationMarker = Regex("\\[(\\d{1,3})]")
private val headingRegex = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)(?:\\s+#+\\s*)?$")
private val thematicBreakRegex = Regex("^\\s{0,3}(?:(?:-\\s*){3,}|(?:\\*\\s*){3,}|(?:_\\s*){3,})$")
private val bulletItemRegex = Regex("^(\\s*)([-*+])\\s+(.*)$")
private val orderedItemRegex = Regex("^(\\s*)(\\d+)\\.\\s+(.*)$")
private val taskMarkerRegex = Regex("^\\[([ xX])]\\s+(.*)$")
private val tableDelimiterCellRegex = Regex("^:?-{3,}:?$")

private sealed interface MdBlock {
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    data class BulletList(val items: List<MdListItem>) : MdBlock
    data class OrderedList(val startNumber: Int, val items: List<MdListItem>) : MdBlock
    data class BlockQuote(val blocks: List<MdBlock>) : MdBlock
    data class CodeBlock(val code: String, val language: String) : MdBlock
    data class Table(val rows: List<List<List<MdInline>>>) : MdBlock
    data object ThematicBreak : MdBlock
}

private data class MdListItem(
    val checked: Boolean?,
    val blocks: List<MdBlock>,
)

private sealed interface MdInline {
    data class Text(val literal: String) : MdInline
    data class Code(val literal: String) : MdInline
    data class Strong(val children: List<MdInline>) : MdInline
    data class Emphasis(val children: List<MdInline>) : MdInline
    data class Strikethrough(val children: List<MdInline>) : MdInline
    data class Link(val destination: String, val children: List<MdInline>) : MdInline
    data class Image(val children: List<MdInline>) : MdInline
    data object SoftBreak : MdInline
    data object HardBreak : MdInline
}

private fun isTableDelimiterLine(line: String): Boolean {
    val trimmed = line.trim()
    if ('|' !in trimmed) return false
    val cells = splitTableRow(trimmed)
    return cells.isNotEmpty() && cells.all { tableDelimiterCellRegex.matches(it.trim()) }
}

private fun splitTableRow(line: String): List<String> {
    val trimmed = line.trim()
    val stripped = trimmed.removePrefix("|").removeSuffix("|")
    return stripped.split('|').map { it.trim() }
}

private fun fencePrefix(line: String): Pair<Char, Int>? {
    val trimmed = line.trimStart()
    if (line.length - trimmed.length > 3 || trimmed.length < 3) return null
    val ch = trimmed[0]
    if (ch != '`' && ch != '~') return null
    val count = trimmed.takeWhile { it == ch }.length
    return if (count >= 3) ch to count else null
}

private fun parseBlocks(text: String): List<MdBlock> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MdBlock>()
    var i = 0

    fun isBlockStarter(index: Int): Boolean {
        if (index >= lines.size) return false
        val line = lines[index]
        if (line.isBlank()) return true
        if (fencePrefix(line) != null) return true
        if (thematicBreakRegex.matches(line)) return true
        if (headingRegex.matches(line)) return true
        if (line.trimStart().startsWith(">")) return true
        if (bulletItemRegex.matches(line) || orderedItemRegex.matches(line)) return true
        if ('|' in line && index + 1 < lines.size && isTableDelimiterLine(lines[index + 1])) return true
        return false
    }

    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            i++
            continue
        }

        val fence = fencePrefix(line)
        if (fence != null) {
            val (fenceChar, fenceLen) = fence
            val info = line.trimStart().drop(fenceLen).trim().substringBefore(' ').ifBlank { "text" }
            i++
            val codeLines = mutableListOf<String>()
            while (i < lines.size) {
                val candidate = lines[i]
                val trimmed = candidate.trimStart()
                if (candidate.length - trimmed.length <= 3 &&
                    trimmed.takeWhile { it == fenceChar }.length >= fenceLen &&
                    trimmed.dropWhile { it == fenceChar }.isBlank()
                ) {
                    i++
                    break
                }
                codeLines.add(candidate)
                i++
            }
            blocks.add(MdBlock.CodeBlock(codeLines.joinToString("\n").trimEnd('\n'), info))
            continue
        }

        if (line.startsWith("    ") || line.startsWith("\t")) {
            val codeLines = mutableListOf<String>()
            while (i < lines.size && (lines[i].startsWith("    ") || lines[i].startsWith("\t") || lines[i].isBlank())) {
                val raw = lines[i]
                codeLines.add(
                    when {
                        raw.startsWith("    ") -> raw.drop(4)
                        raw.startsWith("\t") -> raw.drop(1)
                        else -> ""
                    }
                )
                i++
            }
            while (codeLines.isNotEmpty() && codeLines.last().isEmpty()) {
                codeLines.removeAt(codeLines.lastIndex)
            }
            blocks.add(MdBlock.CodeBlock(codeLines.joinToString("\n"), "text"))
            continue
        }

        if (thematicBreakRegex.matches(line)) {
            blocks.add(MdBlock.ThematicBreak)
            i++
            continue
        }

        val headingMatch = headingRegex.matchEntire(line)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val content = headingMatch.groupValues[2]
            blocks.add(MdBlock.Heading(level, parseInlines(content)))
            i++
            continue
        }

        if (line.trimStart().startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                val stripped = lines[i].trimStart().removePrefix(">").let {
                    if (it.startsWith(" ")) it.drop(1) else it
                }
                quoteLines.add(stripped)
                i++
            }
            blocks.add(MdBlock.BlockQuote(parseBlocks(quoteLines.joinToString("\n"))))
            continue
        }

        if ('|' in line && i + 1 < lines.size && isTableDelimiterLine(lines[i + 1])) {
            val headerCells = splitTableRow(line).map { parseInlines(it) }
            val rows = mutableListOf(headerCells)
            i += 2
            while (i < lines.size && lines[i].isNotBlank() && '|' in lines[i]) {
                val cells = splitTableRow(lines[i]).map { parseInlines(it) }
                rows.add(cells)
                i++
            }
            blocks.add(MdBlock.Table(rows))
            continue
        }

        val bulletMatch = bulletItemRegex.matchEntire(line)
        val orderedMatch = if (bulletMatch == null) orderedItemRegex.matchEntire(line) else null
        if (bulletMatch != null || orderedMatch != null) {
            val isOrdered = orderedMatch != null
            val startNumber = orderedMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1
            val items = mutableListOf<MdListItem>()
            while (i < lines.size) {
                val currentLine = lines[i]
                val itemMatch = if (isOrdered) orderedItemRegex.matchEntire(currentLine)
                else bulletItemRegex.matchEntire(currentLine)
                if (itemMatch == null) break
                val rawFirstLine = itemMatch.groupValues[3]
                val taskMatch = taskMarkerRegex.matchEntire(rawFirstLine)
                val checked = taskMatch?.let { it.groupValues[1].lowercase() == "x" }
                val firstLineContent = taskMatch?.groupValues?.get(2) ?: rawFirstLine
                val itemLines = mutableListOf(firstLineContent)
                i++
                while (i < lines.size && lines[i].isNotBlank() &&
                    !bulletItemRegex.matches(lines[i]) &&
                    !orderedItemRegex.matches(lines[i]) &&
                    (lines[i].startsWith("  ") || lines[i].startsWith("\t"))
                ) {
                    itemLines.add(lines[i].trimStart())
                    i++
                }
                items.add(MdListItem(checked, parseBlocks(itemLines.joinToString("\n"))))
            }
            if (isOrdered) {
                blocks.add(MdBlock.OrderedList(startNumber, items))
            } else {
                blocks.add(MdBlock.BulletList(items))
            }
            continue
        }

        val paragraphLines = mutableListOf(line)
        i++
        while (i < lines.size && !isBlockStarter(i)) {
            paragraphLines.add(lines[i])
            i++
        }
        blocks.add(MdBlock.Paragraph(parseInlines(paragraphLines.joinToString("\n"))))
    }

    return blocks
}

private fun parseInlines(text: String): List<MdInline> {
    val result = mutableListOf<MdInline>()
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            result.add(MdInline.Text(plain.toString()))
            plain.clear()
        }
    }

    var i = 0
    val len = text.length
    while (i < len) {
        val ch = text[i]

        if (ch == '\\' && i + 1 < len) {
            val next = text[i + 1]
            if (next == '\n') {
                flushPlain()
                result.add(MdInline.HardBreak)
                i += 2
                continue
            }
            if (next in "\\`*_{}[]()#+-.!|~") {
                plain.append(next)
                i += 2
                continue
            }
        }

        if (ch == '\n') {
            val hard = plain.endsWith("  ")
            if (hard) {
                plain.setLength(plain.length - 2)
            }
            flushPlain()
            result.add(if (hard) MdInline.HardBreak else MdInline.SoftBreak)
            i++
            continue
        }

        if (ch == '`') {
            val ticks = text.substring(i).takeWhile { it == '`' }
            val tickLen = ticks.length
            val closeIdx = text.indexOf(ticks, i + tickLen)
            if (closeIdx != -1) {
                flushPlain()
                val rawCode = text.substring(i + tickLen, closeIdx)
                val cleaned = if (rawCode.length >= 2 && rawCode.startsWith(' ') && rawCode.endsWith(' ') && rawCode.any { it != ' ' }) {
                    rawCode.substring(1, rawCode.length - 1)
                } else {
                    rawCode
                }
                result.add(MdInline.Code(cleaned))
                i = closeIdx + tickLen
                continue
            }
        }

        if (ch == '!' && i + 1 < len && text[i + 1] == '[') {
            val linkParsed = tryParseBracketLink(text, i + 1)
            if (linkParsed != null) {
                flushPlain()
                result.add(MdInline.Image(parseInlines(linkParsed.first)))
                i = linkParsed.third
                continue
            }
        }

        if (ch == '[') {
            val linkParsed = tryParseBracketLink(text, i)
            if (linkParsed != null) {
                flushPlain()
                result.add(MdInline.Link(linkParsed.second, parseInlines(linkParsed.first)))
                i = linkParsed.third
                continue
            }
        }

        if (text.startsWith("~~", i)) {
            val closeIdx = text.indexOf("~~", i + 2)
            if (closeIdx > i + 2) {
                flushPlain()
                result.add(MdInline.Strikethrough(parseInlines(text.substring(i + 2, closeIdx))))
                i = closeIdx + 2
                continue
            }
        }

        if (text.startsWith("**", i) || text.startsWith("__", i)) {
            val delim = text.substring(i, i + 2)
            val closeIdx = text.indexOf(delim, i + 2)
            if (closeIdx > i + 2) {
                flushPlain()
                result.add(MdInline.Strong(parseInlines(text.substring(i + 2, closeIdx))))
                i = closeIdx + 2
                continue
            }
        }

        if (ch == '*' || ch == '_') {
            val closeIdx = text.indexOf(ch, i + 1)
            if (closeIdx > i + 1 && !text[i + 1].isWhitespace()) {
                flushPlain()
                result.add(MdInline.Emphasis(parseInlines(text.substring(i + 1, closeIdx))))
                i = closeIdx + 1
                continue
            }
        }

        plain.append(ch)
        i++
    }
    flushPlain()
    return result
}

private fun tryParseBracketLink(text: String, bracketStart: Int): Triple<String, String, Int>? {
    var depth = 0
    var i = bracketStart
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i++
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) break
            }
        }
        i++
    }
    if (i >= text.length || text[i] != ']' || i + 1 >= text.length || text[i + 1] != '(') return null
    val label = text.substring(bracketStart + 1, i)
    var parenDepth = 0
    var j = i + 1
    while (j < text.length) {
        when (text[j]) {
            '\\' -> j++
            '(' -> parenDepth++
            ')' -> {
                parenDepth--
                if (parenDepth == 0) break
            }
        }
        j++
    }
    if (j >= text.length || text[j] != ')') return null
    val rawUrl = text.substring(i + 2, j).trim()
    val destination = rawUrl.substringBefore(' ').removeSurrounding("<", ">")
    return Triple(label, destination, j + 1)
}

/** The same parsed tree drives standalone source previews and the curated chat renderer. */
@Composable
internal fun ParsedMarkdown(
    text: String,
    modifier: Modifier,
    palette: ReactUiPalette,
    size: Int,
    citationCount: Int,
    onCitation: ((Int) -> Unit)?,
) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { MarkdownBlock(it, palette, size, citationCount, onCitation) }
    }
}

@Composable
private fun MarkdownBlock(
    block: MdBlock,
    palette: ReactUiPalette,
    size: Int,
    citationCount: Int,
    onCitation: ((Int) -> Unit)?,
    depth: Int = 0,
) {
    when (block) {
        is MdBlock.Paragraph -> InlineText(block.inlines, palette, size, citationCount, onCitation)
        is MdBlock.Heading -> InlineText(
            block.inlines,
            palette,
            size + when (block.level) {
                1 -> 4
                2 -> 2
                else -> 1
            },
            citationCount,
            onCitation,
            FontWeight.SemiBold,
            Modifier.padding(top = 6.dp, bottom = 2.dp),
        )
        is MdBlock.BulletList -> MarkdownList(
            items = block.items,
            ordered = false,
            startNumber = 1,
            palette = palette,
            size = size,
            citationCount = citationCount,
            onCitation = onCitation,
            depth = depth,
        )
        is MdBlock.OrderedList -> MarkdownList(
            items = block.items,
            ordered = true,
            startNumber = block.startNumber,
            palette = palette,
            size = size,
            citationCount = citationCount,
            onCitation = onCitation,
            depth = depth,
        )
        is MdBlock.BlockQuote -> if (depth < 12) {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(palette.border))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.blocks.forEach { MarkdownBlock(it, palette, size, citationCount, onCitation, depth + 1) }
                }
            }
        }
        is MdBlock.CodeBlock -> CodeBlock(block.code, block.language, palette)
        is MdBlock.Table -> MarkdownTable(block, palette, size, citationCount, onCitation)
        is MdBlock.ThematicBreak -> Hairline(palette)
    }
}

@Composable
private fun MarkdownList(
    items: List<MdListItem>,
    ordered: Boolean,
    startNumber: Int,
    palette: ReactUiPalette,
    size: Int,
    citationCount: Int,
    onCitation: ((Int) -> Unit)?,
    depth: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                val marker = when {
                    item.checked != null -> if (item.checked) "☑" else "☐"
                    ordered -> "${startNumber + index}."
                    else -> "•"
                }
                BasicText(marker, style = markdownStyle(palette, size).copy(color = palette.muted))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    item.blocks.forEach { MarkdownBlock(it, palette, size, citationCount, onCitation, depth + 1) }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, language: String, palette: ReactUiPalette) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, palette.border, RoundedCornerShape(8.dp))
            .background(palette.subtle, RoundedCornerShape(8.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(
                language,
                style = markdownStyle(palette, 11).copy(
                    color = palette.muted,
                    fontFamily = FontFamily.Monospace,
                ),
            )
            UiButton("Copy", { clipboard.setText(AnnotatedString(code)) }, palette)
        }
        Hairline(palette)
        BasicText(
            code,
            Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
            style = markdownStyle(palette, 12).copy(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun MarkdownTable(
    table: MdBlock.Table,
    palette: ReactUiPalette,
    size: Int,
    citationCount: Int,
    onCitation: ((Int) -> Unit)?,
) {
    Column(
        Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(1.dp, palette.border, RoundedCornerShape(8.dp)),
    ) {
        table.rows.forEachIndexed { index, row ->
            if (index > 0) Hairline(palette)
            Row(if (index == 0) Modifier.background(palette.subtle) else Modifier) {
                row.forEach { cell ->
                    InlineText(
                        cell,
                        palette,
                        size - 1,
                        citationCount,
                        onCitation,
                        if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                        Modifier.widthIn(min = 100.dp).padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineText(
    inlines: List<MdInline>,
    palette: ReactUiPalette,
    size: Int,
    citationCount: Int,
    onCitation: ((Int) -> Unit)?,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
) {
    val annotated = remember(inlines, palette, citationCount, onCitation) {
        buildAnnotatedString {
            inlines.forEach { appendInline(it, palette, citationCount, onCitation) }
        }
    }
    BasicText(annotated, modifier, style = markdownStyle(palette, size).copy(fontWeight = weight))
}

private fun markdownStyle(palette: ReactUiPalette, size: Int) = TextStyle(
    color = palette.text,
    fontSize = size.sp,
    lineHeight = (size * 1.5f).sp,
)

@Composable
private fun Hairline(palette: ReactUiPalette) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
}

private fun AnnotatedString.Builder.appendInline(
    node: MdInline,
    palette: ReactUiPalette,
    citationCount: Int,
    onCitation: ((Int) -> Unit)?,
    allowCitations: Boolean = true,
) {
    when (node) {
        is MdInline.Text -> {
            if (!allowCitations || citationCount < 1 || onCitation == null) {
                append(node.literal)
            } else {
                var cursor = 0
                citationMarker.findAll(node.literal).forEach { match ->
                    append(node.literal, cursor, match.range.first)
                    val index = match.groupValues[1].toInt()
                    if (index in 1..citationCount) {
                        withLink(
                            LinkAnnotation.Clickable(
                                "source-$index",
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = palette.accent,
                                        textDecoration = TextDecoration.Underline,
                                    )
                                ),
                                linkInteractionListener = LinkInteractionListener { onCitation(index) },
                            )
                        ) {
                            append(match.value)
                        }
                    } else {
                        append(match.value)
                    }
                    cursor = match.range.last + 1
                }
                append(node.literal, cursor, node.literal.length)
            }
        }
        is MdInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = palette.subtle)) {
            append(node.literal)
        }
        is MdInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            node.children.forEach { appendInline(it, palette, citationCount, onCitation, allowCitations) }
        }
        is MdInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            node.children.forEach { appendInline(it, palette, citationCount, onCitation, allowCitations) }
        }
        is MdInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            node.children.forEach { appendInline(it, palette, citationCount, onCitation, allowCitations) }
        }
        is MdInline.Link -> {
            val url = node.destination
            if (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")) {
                withLink(LinkAnnotation.Url(url)) {
                    withStyle(SpanStyle(color = palette.accent, textDecoration = TextDecoration.Underline)) {
                        node.children.forEach { appendInline(it, palette, citationCount, onCitation, false) }
                    }
                }
            } else {
                node.children.forEach { appendInline(it, palette, citationCount, onCitation, false) }
            }
        }
        is MdInline.Image -> {
            append("[Image: ")
            node.children.forEach { appendInline(it, palette, citationCount, onCitation, false) }
            append("]")
        }
        is MdInline.SoftBreak -> append(' ')
        is MdInline.HardBreak -> append('\n')
    }
}
