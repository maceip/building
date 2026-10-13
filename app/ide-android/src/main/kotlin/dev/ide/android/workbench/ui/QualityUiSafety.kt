package dev.ide.android.workbench.ui

import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Exact backend task IDs, not labels or instructions found in imported issue text. */
internal enum class QualityTaskKind(val label: String) {
    LINT("Lint"), TEST("Test"), BUNDLE("Bundle"), BUILD("Build"),
}

internal fun qualityTaskKind(id: String): QualityTaskKind? {
    if (id.any { it.isWhitespace() || it.isISOControl() }) return null
    val parts = id.split(':')
    if (parts.firstOrNull() == "gradle") {
        // gradle:assemble and gradle:run are deliberately excluded: those can install/launch.
        // New IDs are gradle:<goal> or gradle:<goal>:<module path>:<variant>.
        // The backend uses an empty module segment for a root module: gradle:build::main.
        if (parts.size != 2 && parts.size < 4) return null
        if (parts.any { it.isEmpty() } && !(parts.size == 4 && parts[2].isEmpty() && parts[1].isNotEmpty() && parts[3].isNotEmpty())) return null
        return when (parts[1]) {
            "lint" -> QualityTaskKind.LINT
            "test" -> QualityTaskKind.TEST
            "bundle" -> QualityTaskKind.BUNDLE
            "build" -> QualityTaskKind.BUILD
            else -> null
        }
    }
    if (parts.size < 2 || parts.any { it.isEmpty() }) return null
    return when (parts[0]) {
        "lint" -> QualityTaskKind.LINT
        "test" -> QualityTaskKind.TEST
        "bundle" -> QualityTaskKind.BUNDLE
        "build", "assemble", "assembleAar" -> QualityTaskKind.BUILD
        else -> null
    }
}

/** Only canonical GitHub issue/PR links are actionable; queries, credentials and fragments never leave. */
internal fun qualityGitHubUrl(raw: String?): String? {
    if (raw.isNullOrBlank() || raw.any { it.isISOControl() }) return null
    return runCatching {
        val uri = URI(raw.trim())
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("github.com", ignoreCase = true) ||
            uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)
        ) return null
        val path = uri.rawPath ?: return null
        if (!Regex("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/(issues|pull)/[1-9][0-9]*/?").matches(path)) {
            return null
        }
        if (path.split('/').any { it == "." || it == ".." }) return null
        val canonical = "https://github.com${path.trimEnd('/')}"
        canonical.takeIf { qualityRedactUiText(it) == it }
    }.getOrNull()
}

/** Extra UI-layer defence. The quality engine also redacts its report and both export formats. */
private data class QualitySecretRule(val pattern: Regex, val keepPrefix: Boolean = false)

private val qualitySecretRules = listOf(
    QualitySecretRule(Regex("-----BEGIN [^-\\r\\n]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [^-\\r\\n]*PRIVATE KEY-----|\\z)")),
    QualitySecretRule(
        Regex("(?im)([\"']?\\b(?:authorization|proxy-authorization|cookie|set-cookie)[\"']?\\s*[:=]\\s*)[^\\r\\n]+"), true,
    ),
    QualitySecretRule(
        Regex("(?i)(\\b(?:bearer|basic)\\s+)[A-Za-z0-9._~+/=-]+"), true,
    ),
    QualitySecretRule(
        Regex("(?i)([\"']?\\b(?:access[_-]?token|refresh[_-]?token|id[_-]?token|api[_-]?key|client[_-]?secret|session[_-]?id|secret|password|passwd|passphrase|token|cookie|set-cookie)[\"']?\\s*[:=]\\s*)(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;&}\\r\\n]+)"), true,
    ),
    QualitySecretRule(Regex("(?i)(https?://)[^/\\s@]+@"), true),
    QualitySecretRule(Regex("\\b(?:gh[pousr]_[A-Za-z0-9_]*|github_pat_[A-Za-z0-9_]*|glpat-[A-Za-z0-9_-]*)")),
    QualitySecretRule(Regex("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b")),
    QualitySecretRule(Regex("\\bsk-[A-Za-z0-9_-]{16,}\\b")),
    QualitySecretRule(Regex("\\b(?:AIza[A-Za-z0-9_-]+|ya29\\.[A-Za-z0-9_.-]+|xox[baprs]-[A-Za-z0-9-]+)")),
    QualitySecretRule(Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")),
)

internal fun qualityRedactUiText(text: String): String {
    var result = buildString(text.length) {
        text.forEach { char ->
            // Strip terminal and bidi control effects; imported strings never control the interface.
            append(
                if ((char.isISOControl() && char != '\n' && char != '\t') ||
                    char in '\u202a'..'\u202e' || char in '\u2066'..'\u2069' || char == '\u200e' || char == '\u200f'
                ) ' ' else char,
            )
        }
    }
    for (rule in qualitySecretRules) {
        result = rule.pattern.replace(result) { match ->
            val prefix = if (rule.keepPrefix) match.groupValues[1] else ""
            prefix + "[REDACTED]"
        }
    }
    return result
}

/** Call on IO. Findings can only open regular files within the explicitly scanned/current project. */
internal fun qualityResolveProjectFile(root: Path, reportedPath: String): Path {
    require(reportedPath.isNotBlank() && reportedPath.none { it.isISOControl() }) { "Invalid finding path." }
    val realRoot = root.toRealPath()
    val supplied = root.fileSystem.getPath(reportedPath)
    val candidate = (if (supplied.isAbsolute) supplied else realRoot.resolve(supplied)).normalize()
    require(candidate.startsWith(realRoot)) { "The finding is outside this project." }
    val realFile = candidate.toRealPath()
    require(realFile.startsWith(realRoot) && Files.isRegularFile(realFile, LinkOption.NOFOLLOW_LINKS)) {
        "The finding does not point to a project file."
    }
    return realFile
}
