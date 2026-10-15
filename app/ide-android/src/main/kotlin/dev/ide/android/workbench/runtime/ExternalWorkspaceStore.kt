package dev.ide.android.workbench.runtime

import dev.ide.android.AndroidImportLimits
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Kept in app-private storage, never trusted from a project-supplied marker. */
internal data class ExternalWorkspaceBinding(
    val treeUri: String,
    val displayName: String,
    val workingRoot: String,
    val rootDocumentId: String,
    val revision: Long = 1,
    val baseline: Map<String, ExternalFileBaseline> = emptyMap(),
    /** Exact names reserved before a write, including interrupted writes. Never a prefix-based exclusion. */
    val recoveryPaths: Set<String> = emptySet(),
)

internal data class ExternalFileBaseline(
    val sha256: String,
    val size: Long,
    val documentId: String,
    val modifiedMillis: Long?,
)

internal class ExternalWorkspaceException(message: String) : IOException(message)

/** Atomic, versioned metadata. A missing/corrupt ledger must NOT be inferred from current file contents. */
internal class ExternalWorkspaceStore(
    private val directory: Path,
    private val replaceAtomically: (staged: Path, target: Path) -> Unit = { staged, target ->
        try { Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING) }
        Unit
    },
) {
    private val json = Json { prettyPrint = false }

    fun read(root: Path): ExternalWorkspaceBinding? {
        val file = record(root)
        if (!Files.exists(file, NOFOLLOW_LINKS)) return null
        try {
            require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= MAX_METADATA_BYTES)
            val data = json.parseToJsonElement(String(Files.readAllBytes(file), Charsets.UTF_8)).jsonObject
            require(data.getValue("version").jsonPrimitive.long == 1L)
            val binding = ExternalWorkspaceBinding(
                treeUri = data.getValue("treeUri").jsonPrimitive.content,
                displayName = data.getValue("displayName").jsonPrimitive.content,
                workingRoot = data.getValue("workingRoot").jsonPrimitive.content,
                rootDocumentId = data.getValue("rootDocumentId").jsonPrimitive.content,
                revision = data.getValue("revision").jsonPrimitive.long,
                baseline = data.getValue("baseline").jsonObject.mapValues { (_, element) ->
                    val value = element.jsonObject
                    ExternalFileBaseline(
                        value.getValue("sha256").jsonPrimitive.content,
                        value.getValue("size").jsonPrimitive.long,
                        value.getValue("documentId").jsonPrimitive.content,
                        value["modifiedMillis"]?.jsonPrimitive?.contentOrNull?.toLong(),
                    )
                },
                recoveryPaths = data.getValue("recoveryPaths").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            )
            validate(binding)
            require(Paths.get(binding.workingRoot).toAbsolutePath().normalize() == root.toAbsolutePath().normalize())
            return binding
        } catch (_: Exception) {
            throw ExternalWorkspaceException("The saved folder link is unreadable. Working files were kept; no original files were changed.")
        }
    }

    fun write(binding: ExternalWorkspaceBinding) {
        validate(binding)
        Files.createDirectories(directory)
        require(!Files.isSymbolicLink(directory)) { "Folder-link storage cannot be a symbolic link." }
        val data = buildJsonObject {
            put("version", 1)
            put("treeUri", binding.treeUri)
            put("displayName", binding.displayName)
            put("workingRoot", binding.workingRoot)
            put("rootDocumentId", binding.rootDocumentId)
            put("revision", binding.revision)
            put("baseline", JsonObject(binding.baseline.mapValues { (_, value) ->
                buildJsonObject {
                    put("sha256", value.sha256)
                    put("size", value.size)
                    put("documentId", value.documentId)
                    put("modifiedMillis", value.modifiedMillis?.let(::JsonPrimitive) ?: JsonNull)
                }
            }))
            put("recoveryPaths", JsonArray(binding.recoveryPaths.sorted().map(::JsonPrimitive)))
        }.toString().toByteArray(Charsets.UTF_8)
        require(data.size <= MAX_METADATA_BYTES) { "Folder-link metadata exceeds its size limit." }
        val target = record(Paths.get(binding.workingRoot))
        val staged = directory.resolve(".link-${UUID.randomUUID()}.tmp")
        try {
            java.io.FileOutputStream(staged.toFile()).use { output -> output.write(data); output.fd.sync() }
            replaceAtomically(staged, target)
            if (Files.exists(staged, NOFOLLOW_LINKS) || !Files.isRegularFile(target, NOFOLLOW_LINKS) ||
                Files.size(target) != data.size.toLong() || !Files.readAllBytes(target).contentEquals(data)
            ) throw ExternalWorkspaceException("Folder-link metadata was not saved completely. Sync stopped without accepting a new baseline.")
        } finally { Files.deleteIfExists(staged) }
    }

    fun freshStaging(): Path {
        Files.createDirectories(directory)
        require(!Files.isSymbolicLink(directory)) { "Folder-link storage cannot be a symbolic link." }
        return Files.createTempDirectory(directory, ".sync-")
    }

    private fun record(root: Path): Path = directory.resolve(externalWorkspaceKey(root.toAbsolutePath().normalize().toString()) + ".json")

    private fun validate(binding: ExternalWorkspaceBinding) {
        require(binding.treeUri.length in 1..16_384 && binding.displayName.length <= 255)
        require(Paths.get(binding.workingRoot).isAbsolute && binding.revision > 0)
        require(binding.rootDocumentId.length in 1..4096)
        require(binding.baseline.size <= AndroidImportLimits.MAX_TREE_ENTRIES && binding.recoveryPaths.size <= 100_000)
        binding.baseline.forEach { (relative, value) ->
            externalRelativePath(relative)
            require(value.sha256.matches(Regex("[0-9a-f]{64}")))
            require(value.size in 0..AndroidImportLimits.MAX_FILE_BYTES && value.documentId.length in 1..4096)
        }
        binding.recoveryPaths.forEach(::externalRelativePath)
        require(binding.baseline.keys.intersect(binding.recoveryPaths).isEmpty())
    }

    companion object { private const val MAX_METADATA_BYTES = 32 * 1024 * 1024 }
}

internal fun externalWorkspaceKey(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun externalRelativePath(value: String): List<String> {
    require(value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 4096) { "Invalid relative workspace path." }
    return value.split('/').also { parts ->
        require(parts.size <= AndroidImportLimits.MAX_TREE_DEPTH) { "Workspace path is nested too deeply." }
        parts.forEach(AndroidImportLimits::fileName)
    }
}
