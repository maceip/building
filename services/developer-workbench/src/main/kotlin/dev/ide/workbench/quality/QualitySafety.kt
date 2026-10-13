package dev.ide.workbench.quality

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal val qualityJson = Json { prettyPrint = true; isLenient = false }

internal fun limitExceeded(subject: String): Nothing =
    throw QualityException(QualityErrorCode.LIMIT_EXCEEDED, "$subject exceeds the configured safety limit.")

internal fun readBounded(input: InputStream, limit: Int, subject: String): ByteArray {
    val output = ByteArrayOutputStream(minOf(limit, 16_384))
    val buffer = ByteArray(8_192)
    var total = 0
    while (true) {
        val read = input.read(buffer, 0, minOf(buffer.size, limit - total + 1))
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > limit) limitExceeded(subject)
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun decodeUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
} catch (_: java.nio.charset.CharacterCodingException) {
    throw QualityException(QualityErrorCode.INVALID_INPUT, "Input must contain valid UTF-8 text.")
}

/** Check nesting and approximate token count before the recursive JSON tree parser allocates nodes. */
internal fun parseBoundedJson(text: String, maxDepth: Int = 64, maxTokens: Int = 400_000): JsonElement {
    var quoted = false
    var escaped = false
    var depth = 0
    var tokens = 0
    var inPrimitive = false
    for (ch in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (ch == '\\') escaped = true
            else if (ch == '"') quoted = false
            continue
        }
        when (ch) {
            '"' -> { quoted = true; tokens++; inPrimitive = false }
            '{', '[' -> { depth++; tokens++; inPrimitive = false; if (depth > maxDepth) limitExceeded("JSON nesting") }
            '}', ']' -> { depth--; inPrimitive = false }
            ':', ',' -> { tokens++; inPrimitive = false }
            ' ', '\r', '\n', '\t' -> inPrimitive = false
            else -> if (!inPrimitive) { tokens++; inPrimitive = true }
        }
        if (tokens > maxTokens) limitExceeded("JSON token count")
    }
    return try { qualityJson.parseToJsonElement(text) } catch (_: IllegalArgumentException) {
        throw QualityException(QualityErrorCode.INVALID_INPUT, "Malformed JSON; the archive was not imported.")
    }
}

internal fun safePathLabel(path: Path): String = path.toString().replace(Regex("[\\u0000-\\u001F\\u007F\\u202A-\\u202E\\u2066-\\u2069]"), "?")

internal fun stableHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal data class RedactedText(val text: String, val changed: Boolean)

/** Best effort only: arbitrary prose can contain identifiers that no local pattern can recognize. */
internal object QualityRedactor {
    private val ansi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val privateKey = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)")
    private val authorization = Regex("(?im)([\\\"']?(?:authorization|proxy-authorization|cookie|set-cookie)[\\\"']?\\s*[:=]\\s*)[^\\r\\n]+")
    private val bearer = Regex("(?i)\\b(?:Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+")
    private val secrets = Regex("(?i)([\\\"']?(?:password|passwd|passphrase|secret|client[_-]?secret|api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|token|session[_-]?id)[\\\"']?\\s*[:=]\\s*)(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;}&]+)")
    private val knownToken = Regex("\\b(?:gh[pousr]_[A-Za-z0-9_]*|github_pat_[A-Za-z0-9_]*|glpat-[A-Za-z0-9_-]*|(?:AKIA|ASIA)[A-Z0-9]{16}|sk-[A-Za-z0-9_-]{16,}|AIza[A-Za-z0-9_-]+|ya29\\.[A-Za-z0-9_.-]+|xox[baprs]-[A-Za-z0-9-]+|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)\\b")
    private val ipv4 = Regex("(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\w.])")
    private val ipv6 = Regex("(?i)(?<![\\w:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?:%[A-Za-z0-9]+)?(?![\\w:])")
    private val privatePath = Regex("(?i)(?<![\\w:/])/(?:Users|home|data|storage|sdcard|private|tmp|var|mnt|root|Volumes)/[^\\s<>\"'`,;)}\\]]+")
    private val windowsPath = Regex("(?i)\\b[A-Z]:[\\\\/][^\\r\\n<>\"'`,;)}\\]]+")
    private val uri = Regex("(?i)\\b(?:https?|content|file)://[^\\s<>\"'`\\]]+")
    private val githubCitation = Regex("https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/(?:issues|pull)/[0-9]+")
    private val phone = Regex("(?<!\\w)\\+[1-9][0-9 ()-]{8,18}[0-9](?!\\w)")

    /** Scan only at actual @ characters; long non-email runs never trigger backtracking. */
    private fun redactEmails(input: String): String {
        val result = StringBuilder(input.length)
        var copied = 0
        var cursor = 0
        while (cursor < input.length) {
            val at = input.indexOf('@', cursor)
            if (at < 0) break
            var start = at
            while (start > copied && emailLocal(input[start - 1])) start--
            var end = at + 1
            var dot = -1
            while (end < input.length && (input[end].isAsciiLetterOrDigit() || input[end] == '.' || input[end] == '-')) {
                if (input[end] == '.') dot = end
                end++
            }
            if (dot == end - 1) {
                while (end > at + 1 && input[end - 1] == '.') end--
                dot = end - 1
                while (dot > at && input[dot] != '.') dot--
            }
            val valid = start < at && dot > at + 1 && end - dot - 1 >= 2 &&
                (dot + 1 until end).all { input[it] in 'A'..'Z' || input[it] in 'a'..'z' }
            if (valid) {
                result.append(input, copied, start).append("[REDACTED_EMAIL]")
                copied = end
            }
            cursor = maxOf(at + 1, end)
        }
        return result.append(input, copied, input.length).toString()
    }
    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    private fun emailLocal(ch: Char): Boolean = ch.isAsciiLetterOrDigit() || ch in ".!#${'$'}%&'*+/=?^_`{|}~-"

    fun redact(input: String): RedactedText {
        var text = input
        text = ansi.replace(text, "")
        text = buildString(text.length) { for (ch in text) append(if (ch == '\n' || ch == '\t' || (!ch.isISOControl() && ch !in '\u202A'..'\u202E' &&
            ch !in '\u2066'..'\u2069' && ch != '\u200E' && ch != '\u200F')) ch else ' ') }
        text = privateKey.replace(text) { "[REDACTED_PRIVATE_KEY]" + "\n".repeat(it.value.count { ch -> ch == '\n' }) }
        text = authorization.replace(text) { "${it.groupValues[1]}[REDACTED]" }
        text = secrets.replace(text) { "${it.groupValues[1]}[REDACTED]" }
        text = bearer.replace(text, "[REDACTED_AUTH]")
        text = knownToken.replace(text, "[REDACTED_TOKEN]")
        text = uri.replace(text) { match ->
            if (githubCitation.matches(match.value)) match.value else "[REDACTED_URL]"
        }
        text = redactEmails(text)
        text = privatePath.replace(text, "[REDACTED_PATH]")
        text = windowsPath.replace(text, "[REDACTED_PATH]")
        text = ipv4.replace(text, "[REDACTED_IP]")
        text = ipv6.replace(text, "[REDACTED_IP]")
        text = phone.replace(text, "[REDACTED_PHONE]")
        return RedactedText(text, text != input)
    }
}

internal fun safeSnippet(text: String, maxChars: Int = 220): String =
    QualityRedactor.redact(text).text.replace(Regex("\\s+"), " ").trim().take(maxChars)

internal fun readRegularFile(path: Path, maxBytes: Int): ByteArray {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        throw QualityException(QualityErrorCode.UNSAFE_PATH, "Select a regular file, not a symbolic link or special file.")
    }
    if (Files.size(path) > maxBytes.toLong()) limitExceeded("Input file")
    return Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
        readBounded(it, maxBytes, "Input file")
    }
}

internal val archiveCaveats = listOf(
    "Offline heuristic triage, not semantic-perfect clustering: labels and groups need review; low-information or ambiguous records are left unclassified.",
    "Reports describe symptoms or author hypotheses, not independently confirmed causes. A PR describes a proposal; closed does not mean merged, tested, or fixed.",
    "The export may mix Android, desktop, and unspecified platform reports. Repository selection, pagination, age, and uneven repository volume bias this sample; counts are not ecosystem prevalence or user counts.",
    "Full archive dates and the declared recent window are reported separately using created_at; a filename or declared period does not prove all records are recent.",
    "Theme and stack groups may overlap. Sum record statistics, not group counts. Shared stack signatures are stronger recurrence evidence, not proof of a shared cause.",
    "Only explicit textual references link issues and PRs; no links, attachments, commands, remote code, or source uploads are executed or fetched.",
    "Redaction and specialized-device detection are heuristic and incomplete. Review exports before sharing; source citations and repository names remain visible. Detected/configured specialized text is withheld; review repository scope to cover terse reports.",
)
