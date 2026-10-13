package dev.ide.android.reliability

import dev.ide.ui.backend.IdeBackend
import dev.ide.workbench.reliability.ReliabilityError
import dev.ide.workbench.reliability.ReliabilityException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/** Only non-secret project capture preferences. Pairing credentials never enter this file or the editor. */
internal object ReliabilityProjectConsent {
    fun enabled(backend: IdeBackend): Boolean {
        val file = safePath(backend.project.rootPath)
        val config = read(file, backend) ?: return false
        return config.boolean("enabled") && config.boolean("consentGranted")
    }

    fun setEnabled(backend: IdeBackend, enabled: Boolean) {
        val path = safePath(backend.project.rootPath)
        val existing = read(path, backend)
        val text = buildJsonObject {
            put("version", 1); put("enabled", enabled); put("consentGranted", enabled)
            put("captureCrashes", existing?.boolean("captureCrashes", true) ?: true)
            put("captureLifecycle", existing?.boolean("captureLifecycle", true) ?: true)
            put("capturePreviousExitInfo", existing?.boolean("capturePreviousExitInfo", true) ?: true)
            put("captureNetwork", existing?.boolean("captureNetwork") ?: false)
            put("teeStdout", existing?.boolean("teeStdout") ?: false)
            // This debug-local toggle must never silently authorize production collection.
            put("productionEnabled", false)
        }.toString() + "\n"
        if (!Files.exists(path.parent, LinkOption.NOFOLLOW_LINKS)) {
            backend.files.createDirectory(path.parent.parent.toString(), ".building") ?: unavailable()
        }
        safePath(backend.project.rootPath)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) backend.editor.saveFile(path.toString(), text)
        else if (backend.files.createFile(path.parent.toString(), path.fileName.toString(), text) == null) unavailable()
        if (enabled(backend) != enabled) unavailable()
    }

    private fun safePath(root: String): Path {
        val directory = Paths.get(root)
        if (!directory.isAbsolute || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) unavailable()
        val canonicalRoot = directory.toRealPath()
        val configDir = canonicalRoot.resolve(".building")
        if (Files.exists(configDir, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(configDir, LinkOption.NOFOLLOW_LINKS)) unavailable()
        val config = configDir.resolve("reliability.json")
        if (Files.exists(config, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) unavailable()
        return config
    }

    private fun read(path: Path, backend: IdeBackend): JsonObject? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.size(path) > 4_096) unavailable()
        val text = backend.files.readFile(path.toString())
        if (text.length > 4_096) unavailable()
        // The canonical project schema is a flat object of booleans and one numeric version.
        var quoted = false; var escaped = false; var depth = 0
        for (ch in text) {
            if (quoted) { if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false }
            else when (ch) { '"' -> quoted = true; '[' -> unavailable(); '{' -> if (++depth > 1) unavailable(); '}' -> if (--depth < 0) unavailable() }
        }
        val result = try { Json.parseToJsonElement(text) as? JsonObject ?: unavailable() } catch (_: IllegalArgumentException) { unavailable() }
        if ((result["version"] as? JsonPrimitive)?.intOrNull != 1) unavailable()
        val allowed = setOf("version", "enabled", "consentGranted", "captureCrashes", "captureLifecycle", "capturePreviousExitInfo", "captureNetwork", "teeStdout", "productionEnabled")
        // Do not load or preserve unknown fields that might contain secrets or a future unsupported schema.
        if (result.keys.any { it !in allowed }) unavailable()
        if (result.filterKeys { it != "version" }.values.any { (it as? JsonPrimitive)?.takeUnless { primitive -> primitive.isString }?.booleanOrNull == null }) unavailable()
        return result
    }

    private fun JsonObject.boolean(name: String, default: Boolean = false) = (get(name) as? JsonPrimitive)?.booleanOrNull ?: default
    private fun unavailable(): Nothing = throw ReliabilityException(ReliabilityError.INVALID)
}
