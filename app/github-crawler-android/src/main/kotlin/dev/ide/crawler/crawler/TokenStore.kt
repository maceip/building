package dev.ide.crawler.crawler

import android.content.Context
import java.io.File

/**
 * Where the GitHub token lives on the phone.
 *
 * The token is stored in the app's private files directory, which is inside
 * the app sandbox - it is never world readable and never leaves the device
 * except as an `Authorization` header to api.github.com.
 */
class TokenStore(context: Context) {

    private val file = File(context.filesDir, "github-token")

    /** The stored token, or null when the user has not saved one yet. */
    fun read(): String? {
        if (!file.isFile) return null
        return runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Persist the token with owner-only permissions. */
    fun write(token: String): Boolean {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return false
        return runCatching {
            file.writeText(trimmed)
            file.setReadable(true, true)
            file.setWritable(true, true)
            true
        }.getOrDefault(false)
    }

    fun clear() {
        try {
            if (file.isFile) file.delete()
        } catch (_: Exception) {
            // A token we cannot delete is a problem the user must fix in
            // Settings; it must not crash the app.
        }
    }

    /** A non-secret description for the status screen. */
    fun describe(): String {
        val token = read() ?: return "not set"
        val prefix = token.substringBefore('_')
        return if (prefix != token) "${prefix}_**** (${token.length} chars)" else "set (${token.length} chars)"
    }
}
