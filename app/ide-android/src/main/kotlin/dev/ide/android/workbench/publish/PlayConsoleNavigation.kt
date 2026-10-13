package dev.ide.android.workbench.publish

import java.net.URI

internal const val PLAY_CONSOLE_URL = "https://play.google.com/console/"

/** A navigation decision, never evidence that a Console account is signed in. */
internal enum class PlayConsoleDestination { CONSOLE, GOOGLE_SIGN_IN, BLOCKED }

/**
 * Only the exact HTTPS Console origin and path segment may be a top-level document.
 * Check every destination, including redirects; an allowed starting URL confers no trust on its target.
 * Account URLs are classified for an honest, manual browser handoff, never loaded as embedded OAuth.
 */
internal fun playConsoleDestination(url: String): PlayConsoleDestination {
    val uri = consoleHttpsUri(url) ?: return PlayConsoleDestination.BLOCKED
    if (uri.host.equals("accounts.google.com", ignoreCase = true)) return PlayConsoleDestination.GOOGLE_SIGN_IN
    if (!uri.host.equals("play.google.com", ignoreCase = true)) return PlayConsoleDestination.BLOCKED

    val path = uri.rawPath.orEmpty()
    if (path != "/console" && !path.startsWith("/console/")) return PlayConsoleDestination.BLOCKED
    // Do not accept alternate separators, traversal, or multiply encoded paths that parsers may disagree on.
    val decodedPath = uri.path.orEmpty()
    if (encodedSlash.containsMatchIn(path) || decodedPath.any { it == '\\' || it == '%' || it.isISOControl() } ||
        decodedPath.split('/').any { it == "." || it == ".." } || uri.normalize().rawPath != path
    ) return PlayConsoleDestination.BLOCKED
    return PlayConsoleDestination.CONSOLE
}

/** HTTPS resources may use other origins; account/sign-in documents are still forbidden. */
internal fun playConsoleResourceAllowed(url: String): Boolean {
    val uri = consoleHttpsUri(url) ?: return false
    return !uri.host.equals("accounts.google.com", ignoreCase = true)
}

private val encodedSlash = Regex("%2f", RegexOption.IGNORE_CASE)

private fun consoleHttpsUri(url: String): URI? {
    if (url.isBlank() || url.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
    val uri = try { URI(url) } catch (_: Exception) { return null }
    if (!uri.isAbsolute || uri.isOpaque || !uri.scheme.equals("https", ignoreCase = true) ||
        uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)
    ) return null
    val host = uri.host ?: return null
    // Reject ambiguous authority spellings, encoded hosts, empty ports and credential-bearing URLs.
    val authority = host + if (uri.port == 443) ":443" else ""
    if (!uri.rawAuthority.equals(authority, ignoreCase = true)) return null
    return uri
}
