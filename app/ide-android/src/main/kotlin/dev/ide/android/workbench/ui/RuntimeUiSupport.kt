package dev.ide.android.workbench.ui

import dev.ide.android.workbench.runtime.ExternalSyncReport
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale

/** Argument quoting is only a UI convenience. No shell expansion or command evaluation occurs here. */
internal object RuntimeArguments {
    fun parse(text: String): List<String> {
        val result = mutableListOf<String>()
        val word = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var started = false
        for (char in text) {
            when {
                escaped -> { word.append(char); escaped = false; started = true }
                char == '\\' && quote != '\'' -> { escaped = true; started = true }
                quote != null -> if (char == quote) quote = null else word.append(char)
                char == '\'' || char == '"' -> { quote = char; started = true }
                char.isWhitespace() -> if (started) { result += word.toString(); word.clear(); started = false }
                else -> { word.append(char); started = true }
            }
        }
        require(!escaped) { "Arguments end with an unfinished escape. Use \\\\ for a literal backslash." }
        require(quote == null) { "Close the quote in Arguments before saving or running." }
        if (started) result += word.toString()
        require(result.size <= 128 && result.all { it.length <= 8192 && '\u0000' !in it }) { "Use at most 128 arguments, each at most 8,192 characters, without NUL." }
        return result
    }

    fun format(args: List<String>): String = args.joinToString(" ") { arg ->
        if (arg.isNotEmpty() && arg.all { !it.isWhitespace() && it !in "\\\"'" }) arg
        else "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

internal fun runtimeEnvironment(text: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
        val separator = line.indexOf('=')
        require(separator > 0) { "Environment entries must be NAME=value, one per line." }
        val name = line.substring(0, separator).trim()
        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid environment name: $name" }
        require(name !in result) { "Duplicate environment name: $name" }
        result[name] = line.substring(separator + 1)
    }
    return result
}

/** Exact literal loopback only: no DNS lookup, user-info, file/content URLs, wildcard or LAN hosts. */
internal data class RuntimePreviewAddress(val url: String, val scheme: String, val host: String, val port: Int) {
    fun permits(candidate: String): Boolean = parse(candidate)?.let {
        it.scheme == scheme && it.host == host && it.port == port
    } == true

    companion object {
        fun parse(value: String): RuntimePreviewAddress? {
            if (value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
            return runCatching {
                val uri = URI(value)
                val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
                if (scheme !in setOf("http", "https") || uri.rawUserInfo != null) return null
                val host = uri.host?.lowercase(Locale.ROOT)?.removeSurrounding("[", "]") ?: return null
                if (host !in setOf("127.0.0.1", "localhost", "::1")) return null
                val port = if (uri.port == -1) { if (scheme == "https") 443 else 80 } else uri.port
                if (port !in 1..65535) return null
                RuntimePreviewAddress(uri.toASCIIString(), scheme, host, port)
            }.getOrNull()
        }
    }
}

internal fun runtimePreferenceKey(path: String): String = MessageDigest.getInstance("SHA-256")
    .digest(path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun runtimeSamePath(first: Path?, second: Path?): Boolean =
    first != null && second != null && first.toAbsolutePath().normalize() == second.toAbsolutePath().normalize()

/** A partial sync is never presented as success, even if some files reached the original folder. */
internal fun runtimeExternalSyncSummary(report: ExternalSyncReport): String {
    val counts = "${report.pushed} file(s) saved to the original folder; ${report.pulled} pulled into the working copy."
    return when {
        report.cancelled -> "Folder sync cancelled, not complete. $counts Inspect recovery files before retrying."
        report.failures.isNotEmpty() -> "Folder sync incomplete. $counts ${report.failures.size} failure(s); ${report.conflicts.size} conflict(s)."
        report.conflicts.isNotEmpty() -> "Folder sync needs a merge. $counts ${report.conflicts.size} conflict(s) kept unchanged on both sides."
        else -> "Folder sync complete. $counts No deletions were propagated."
    }
}

internal fun runtimeExternalSyncDetails(report: ExternalSyncReport): String = buildString {
    (report.conflicts + report.failures).take(40).forEach { issue ->
        append(if (issue.path.isBlank()) "Folder" else issue.path).append(": ").append(issue.reason).append('\n')
    }
    if (report.conflicts.size + report.failures.size > 40) append("Showing the first 40 issues.\n")
    if (report.recoveryPaths.isNotEmpty()) {
        append("Retained recovery files (relative paths are in the original folder; absolute paths are local):\n")
        report.recoveryPaths.take(40).forEach { append(it).append('\n') }
        if (report.recoveryPaths.size > 40) append("Showing the first 40 recovery paths.\n")
    }
    if (report.pulled > 0) append("Reopen affected editor files before editing further; unsaved editor buffers were not changed.\n")
}.trim()

/** Also caps a newline-free log so a malicious process cannot hand the text layout an unbounded line. */
internal fun runtimeVisibleOutput(text: String, maxChars: Int = 32_768, maxLines: Int = 300): String {
    val chars = text.takeLast(maxChars)
    var start = chars.length
    var lines = 0
    while (start > 0) {
        start--
        if (chars[start] == '\n' && ++lines >= maxLines) { start++; break }
    }
    val visible = chars.substring(start)
    return if (visible.length < text.length) "[Earlier output omitted from this view]\n$visible" else visible
}
