package fragments.components.signoff

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import fragments.components.ReactUiPalette
import fragments.components.ReactUiPalettes

/** Signoff's Revealed marks characters that could make a command or URL read deceptively. */
@Composable
fun Revealed(
    text: String,
    blanks: Boolean = false,
    modifier: Modifier = Modifier,
    palette: ReactUiPalette = ReactUiPalettes.AgentElementsDark,
) {
    val parts = remember(text, blanks) { revealText(text, blanks) }
    val rendered = remember(parts, palette) {
        buildAnnotatedString {
            parts.forEach { part ->
                when (part) {
                    is RevealedPart.Plain -> append(part.value)
                    is RevealedPart.Mark -> {
                        pushStyle(SpanStyle(
                            color = palette.warning,
                            background = palette.subtle,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        ))
                        append(part.shown)
                        pop()
                    }
                }
            }
        }
    }
    val spoken = remember(parts) {
        parts.joinToString("") { part ->
            when (part) {
                is RevealedPart.Plain -> part.value
                is RevealedPart.Mark -> part.description
            }
        }
    }
    BasicText(rendered, modifier.semantics { contentDescription = spoken },
        style = TextStyle(color = palette.text, fontSize = 14.sp, lineHeight = 20.sp))
}

@Immutable
internal sealed interface RevealedPart {
    @Immutable
    data class Plain(val value: String) : RevealedPart

    @Immutable
    data class Mark(
        val kind: Kind,
        val shown: String,
        val description: String,
    ) : RevealedPart

    enum class Kind { Invisible, Blank }
}

/** Mirrors signoff-ui's revealText grouping, blank thresholds, and emoji exceptions. */
internal fun revealText(text: String, blanks: Boolean): List<RevealedPart> {
    if (text.isEmpty()) return listOf(RevealedPart.Plain(""))
    val codePoints = text.toCodePointArray()
    val parts = mutableListOf<RevealedPart>()
    val plain = StringBuilder()
    val run = StringBuilder()
    val invisible = StringBuilder()
    val codes = linkedSetOf<String>()
    var invisibleCount = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            parts += RevealedPart.Plain(plain.toString())
            plain.clear()
        }
    }

    fun flushInvisible() {
        if (invisibleCount == 0) return
        val shown = if (codes.size <= 2 && invisibleCount == codes.size) codes.joinToString(" ")
            else "${codes.first()}${if (codes.size > 1) " …" else ""} ×$invisibleCount"
        val description = if (invisibleCount == 1) "Invisible character ${codes.first()}"
            else "$invisibleCount invisible characters: ${codes.joinToString(", ")}"
        parts += RevealedPart.Mark(RevealedPart.Kind.Invisible, shown, description)
        invisible.clear()
        codes.clear()
        invisibleCount = 0
    }

    fun addBlank(value: String, lines: Int) {
        flushPlain()
        parts += RevealedPart.Mark(RevealedPart.Kind.Blank,
            if (lines > 0) "↵ ×$lines" else "␣ ×${value.length}",
            if (lines > 0) "$lines line breaks" else "${value.length} blank characters")
    }

    fun emitBlankPiece(value: String) {
        val width = value.sumOf { if (it == '\t') 8 else if (it == ' ') 1 else 0 }
        if (width >= 16) addBlank(value, 0) else plain.append(value)
    }

    fun flushRun() {
        if (run.isEmpty()) return
        val value = run.toString()
        val lines = value.count { it == '\n' }
        if (lines > 3) addBlank(value, lines)
        else {
            var start = 0
            while (start < value.length) {
                val newline = value.indexOf('\n', start)
                if (newline < 0) {
                    emitBlankPiece(value.substring(start))
                    break
                }
                val delimiter = if (newline > start && value[newline - 1] == '\r') newline - 1 else newline
                emitBlankPiece(value.substring(start, delimiter))
                plain.append(value.substring(delimiter, newline + 1))
                start = newline + 1
            }
        }
        run.clear()
    }

    codePoints.forEachIndexed { index, codePoint ->
        if (isInvisible(codePoint, index, codePoints, blanks)) {
            flushRun()
            flushPlain()
            invisible.appendCodePointCompat(codePoint)
            codes += "U+${codePoint.toString(16).uppercase().padStart(4, '0')}"
            invisibleCount++
        } else {
            flushInvisible()
            if (blanks && (codePoint == 0x20 || codePoint == 0x09 || codePoint == 0x0A || codePoint == 0x0D)) {
                run.appendCodePointCompat(codePoint)
            } else {
                flushRun()
                plain.appendCodePointCompat(codePoint)
            }
        }
    }
    flushInvisible()
    flushRun()
    flushPlain()
    return parts
}

private fun String.toCodePointArray(): IntArray {
    val result = IntArray(length)
    var count = 0
    var i = 0
    while (i < length) {
        val high = this[i]
        if (high.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            val low = this[i + 1]
            result[count++] = ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000
            i += 2
        } else {
            result[count++] = high.code
            i++
        }
    }
    return result.copyOf(count)
}

private fun StringBuilder.appendCodePointCompat(codePoint: Int): StringBuilder {
    if (codePoint <= 0xFFFF) {
        append(codePoint.toChar())
    } else {
        val offset = codePoint - 0x10000
        append(((offset ushr 10) + 0xD800).toChar())
        append(((offset and 0x3FF) + 0xDC00).toChar())
    }
    return this
}

private fun isExtendedPictographic(cp: Int): Boolean =
    cp in 0x1F000..0x1FAFF ||
        cp in 0x2600..0x27BF ||
        cp in 0x2300..0x23FF ||
        cp in 0x2B50..0x2B55 ||
        cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 ||
        cp in 0x2194..0x2199 || cp in 0x21A9..0x21AA ||
        cp in 0x25AA..0x25AB || cp == 0x25B6 || cp == 0x25C0 || cp in 0x25FB..0x25FE ||
        cp in 0x2934..0x2935 || cp in 0x2B05..0x2B07 || cp in 0x2B1B..0x2B1C ||
        cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299 ||
        cp == 0x00A9 || cp == 0x00AE

private fun isEmojiModifier(cp: Int): Boolean = cp in 0x1F3FB..0x1F3FF

private fun isEmojiCodePoint(cp: Int): Boolean =
    isExtendedPictographic(cp) ||
        isEmojiModifier(cp) ||
        cp in 0x30..0x39 || cp == 0x23 || cp == 0x2A ||
        cp in 0x1F1E6..0x1F1FF

private fun isControlOrFormatOrIgnorable(cp: Int): Boolean =
    cp in 0x0000..0x001F ||
        cp in 0x007F..0x009F ||
        cp == 0x2028 || cp == 0x2029 ||
        cp == 0x00AD ||
        cp in 0x0600..0x0605 || cp == 0x061C || cp == 0x06DD || cp == 0x070F || cp == 0x08E2 ||
        cp == 0x180E ||
        cp in 0x200B..0x200F ||
        cp in 0x202A..0x202E ||
        cp in 0x2060..0x206F ||
        cp == 0xFEFF ||
        cp in 0xFFF9..0xFFFB ||
        cp in 0x110BD..0x110CD ||
        cp in 0x13430..0x13438 ||
        cp in 0x1BCA0..0x1BCA3 ||
        cp in 0x1D173..0x1D17A ||
        cp == 0xE0001 ||
        cp in 0xE0020..0xE007F

private fun isInvisible(codePoint: Int, index: Int, all: IntArray, blanks: Boolean): Boolean {
    if (codePoint == 0x09 || codePoint == 0x0A) return false
    if (codePoint == 0x0D) return index < all.lastIndex && all[index + 1] != 0x0A
    if (codePoint == 0x200D) {
        val previous = all.getOrNull(index - 1) ?: return true
        val next = all.getOrNull(index + 1) ?: return true
        val emojiPart = isExtendedPictographic(previous) ||
            isEmojiModifier(previous) || previous == 0xFE0F
        return !(emojiPart && isExtendedPictographic(next))
    }
    if (codePoint == 0xFE0E || codePoint == 0xFE0F) {
        val previous = all.getOrNull(index - 1) ?: return true
        if (!isEmojiCodePoint(previous)) return true
        val keycapBase = previous in 0x30..0x39 || previous == 0x23 || previous == 0x2A
        return keycapBase && all.getOrNull(index + 1) != 0x20E3
    }
    if (blanks && (codePoint == 0x00A0 || codePoint == 0x1680 || codePoint in 0x2000..0x200A ||
            codePoint == 0x202F || codePoint == 0x205F || codePoint == 0x3000 || codePoint == 0x2800)) return true
    return isControlOrFormatOrIgnorable(codePoint) || codePoint == 0x034F ||
        codePoint in 0x115F..0x1160 || codePoint in 0x17B4..0x17B5 ||
        codePoint in 0x180B..0x180F || codePoint in 0xFE00..0xFE0F ||
        codePoint in 0xFFF9..0xFFFB || codePoint in 0xE0100..0xE01EF
}
