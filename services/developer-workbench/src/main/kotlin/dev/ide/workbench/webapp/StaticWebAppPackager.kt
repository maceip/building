package dev.ide.workbench.webapp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * Copies a reviewed STATIC build into a NEW native CodeAssist Android project, without invoking npm,
 * Gradle, Python, a network client, signing, or an IDE project switch. Run these blocking methods on IO.
 *
 * The format is ModelPersistence's workspace schema 1 / module schema 2 and AndroidFacetCodec's
 * [android] table. The IDE seeds its installed SDK on open; the wrapper has no Maven dependencies.
 */
class StaticWebAppPackager(private val limits: WebAppPackageLimits = WebAppPackageLimits()) {
    fun review(request: WebAppPackageRequest): WebAppPackageReview {
        validateIdentity(request)
        val workspace = realDirectory(request.workspaceRoot, "Source workspace")
        val relative = checkedRelative(request.staticRoot, allowRoot = true)
        val staticDirectory = workspace.resolve(relative).normalize()
        requireSafePath(workspace, staticDirectory, directory = true)
        val parent = realDirectory(request.outputParent, "Output parent")
        val destination = parent.resolve(request.projectName)
        require(!parent.startsWith(workspace) && !workspace.startsWith(destination)) {
            "Output must be a new project outside the source workspace, never inside or over it."
        }
        require(!Files.exists(destination, NOFOLLOW_LINKS)) {
            "Output already exists: $destination. Choose a different project name; nothing will be replaced."
        }
        val normalized = request.copy(workspaceRoot = workspace, staticRoot = relative, outputParent = parent)
        val snapshot = scan(staticDirectory)
        require(snapshot.assets.any { it.path == "index.html" && it.bytes > 0 }) {
            "The chosen static output must contain a non-empty, regular index.html (not a link or secret). " +
                "Build/export the site first; SSR, Node and Python servers are not packaged."
        }
        return WebAppPackageReview(
            normalized, staticDirectory, destination, snapshot.assets, snapshot.excluded,
            sha256(assetList(snapshot.assets).toString().toByteArray(Charsets.UTF_8)), limits,
        )
    }

    /** Rejects a stale review, rechecks every copied file, and reserves the exact reviewed path atomically. */
    fun create(review: WebAppPackageReview): WebAppPackageResult {
        require(review.limits == limits) { "Review again with the current packaging limits." }
        val fresh = review(review.request)
        require(fresh.staticDirectory == review.staticDirectory && fresh.outputDirectory == review.outputDirectory &&
            fresh.assets == review.assets && fresh.excluded == review.excluded &&
            fresh.assetManifestSha256 == review.assetManifestSha256) {
            "The static output changed since review. Review the assets again before packaging."
        }
        checkInterrupted()
        // createDirectory, NOT createDirectories/move(REPLACE_EXISTING): even an empty directory or a
        // dangling link created since review causes failure. No source or existing project is reused.
        val tree = NewProjectTree(fresh.outputDirectory)
        try {
            var copied = 0L
            for (asset in fresh.assets) {
                checkInterrupted()
                tree.write("app/src/main/assets/web/${asset.path}") { output ->
                    val actual = inspectFile(fresh.staticDirectory, asset.path, output) { bytes ->
                        copied += bytes
                        require(copied <= limits.maxTotalBytes) { "Static output exceeds the total byte limit." }
                    }
                    require(actual.first == asset && !actual.second) {
                        "Asset changed since review: ${asset.path}. No project was created; review again."
                    }
                }
            }
            val result = WebAppPackageResult(
                fresh.outputDirectory, fresh.request.workspaceRoot, fresh.staticDirectory,
                fresh.request.applicationId, fresh.assets.size, fresh.totalBytes, fresh.assetManifestSha256,
            )
            val generated = NativeWebAppProject.files(fresh)
            // Publish the workspace marker last, after assets, source and model are complete.
            generated.filterKeys { it != WORKSPACE_FILE }.forEach { (path, text) -> tree.text(path, text) }
            tree.text("app/src/main/assets/webapp-manifest.json", manifest(fresh).toString() + "\n")
            tree.text(PACKAGE_FILE, resultJson(result).toString() + "\n")
            tree.text(WORKSPACE_FILE, generated.getValue(WORKSPACE_FILE))
            return result
        } catch (failure: Throwable) {
            tree.rollback()
            throw failure
        }
    }

    /** Recognizes only a bounded, regular local metadata file. Does not open or mutate an IDE project. */
    fun generatedProject(projectRoot: Path): WebAppPackageResult? = runCatching {
        val root = realDirectory(projectRoot, "Project")
        requireSafePath(root, root.resolve(PACKAGE_FILE), directory = false)
        val file = root.resolve(PACKAGE_FILE)
        val text = Files.newInputStream(file, READ, NOFOLLOW_LINKS).use { stream ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= 16 * 1024)
                bytes.write(buffer, 0, count)
            }
            bytes.toString("UTF-8")
        }
        val value = Json.parseToJsonElement(text).jsonObject
        require(value.getValue("schema").jsonPrimitive.int == 1)
        require(value.getValue("kind").jsonPrimitive.content == "codeassist-offline-webapp")
        WebAppPackageResult(
            root,
            Paths.get(value.getValue("sourceWorkspace").jsonPrimitive.content),
            Paths.get(value.getValue("sourceStaticDirectory").jsonPrimitive.content),
            value.getValue("applicationId").jsonPrimitive.content,
            value.getValue("assetCount").jsonPrimitive.int,
            value.getValue("assetBytes").jsonPrimitive.long,
            value.getValue("assetManifestSha256").jsonPrimitive.content,
        ).also {
            require(it.assetCount in 1..10_000 && it.assetBytes in 1..(512L * 1024 * 1024))
            require(it.assetManifestSha256.matches(Regex("[a-f0-9]{64}")))
        }
    }.getOrNull()

    private data class Snapshot(val assets: List<WebAppAsset>, val excluded: List<WebAppExcludedAsset>)

    private fun scan(root: Path): Snapshot {
        val assets = mutableListOf<WebAppAsset>()
        val excluded = mutableListOf<WebAppExcludedAsset>()
        val portablePaths = mutableSetOf<String>()
        var entries = 0
        var candidates = 0
        var inspectedBytes = 0L
        fun entry(path: Path) {
            checkInterrupted()
            require(++entries <= limits.maxEntries) { "Static output has too many entries (limit ${limits.maxEntries})." }
            require(root.relativize(path).nameCount <= limits.maxDepth) { "Static output is too deeply nested." }
        }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                entry(dir)
                requireSafePath(root, dir, directory = true)
                if (dir == root) return FileVisitResult.CONTINUE
                val relative = relativeName(root, dir)
                exclusion(dir.fileName.toString(), directory = true)?.let {
                    excluded += WebAppExcludedAsset("$relative/", it)
                    return FileVisitResult.SKIP_SUBTREE
                }
                validateAssetPath(relative)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                entry(file)
                val relative = relativeName(root, file)
                val reason = when {
                    attrs.isSymbolicLink -> "Symbolic link (never followed)"
                    !attrs.isRegularFile -> "Not a regular file"
                    hasMultipleLinks(file) -> "Hard-linked file"
                    else -> exclusion(file.fileName.toString(), directory = false)
                }
                if (reason != null) {
                    excluded += WebAppExcludedAsset(relative, reason)
                    return FileVisitResult.CONTINUE
                }
                validateAssetPath(relative)
                require(++candidates <= limits.maxFiles) { "Static output has too many files (limit ${limits.maxFiles})." }
                val (asset, secret) = inspectFile(root, relative) { bytes ->
                    inspectedBytes += bytes
                    require(inspectedBytes <= limits.maxTotalBytes) {
                        "Static output exceeds ${limits.maxTotalBytes} inspected bytes. Choose a smaller production build."
                    }
                }
                if (secret) excluded += WebAppExcludedAsset(relative, "Recognizable private credential content")
                else {
                    val portable = Normalizer.normalize(relative, Normalizer.Form.NFC).lowercase(Locale.ROOT)
                    require(portablePaths.add(portable)) { "Ambiguous case/Unicode asset name: $relative" }
                    assets += asset
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                throw IOException("Cannot safely inspect ${relativeName(root, file)}", exc)
        })
        return Snapshot(assets.sortedBy { it.path }, excluded.sortedBy { it.path })
    }

    private fun inspectFile(
        root: Path,
        relative: String,
        output: OutputStream? = null,
        onBytes: (Long) -> Unit,
    ): Pair<WebAppAsset, Boolean> {
        val file = root.resolve(relative)
        requireSafePath(root, file, directory = false)
        val before = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(before.size() <= limits.maxFileBytes) { "Asset exceeds ${limits.maxFileBytes} bytes: $relative" }
        require(!hasMultipleLinks(file)) { "Hard links cannot be packaged: $relative" }
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        var secret = false
        var suffix = ""
        Files.newInputStream(file, READ, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                checkInterrupted()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                bytes += count
                require(bytes <= limits.maxFileBytes) { "Asset grew past the byte limit: $relative" }
                onBytes(count.toLong())
                digest.update(buffer, 0, count)
                // A rolling window catches markers across read boundaries without loading a bundle into RAM.
                val text = suffix + String(buffer, 0, count, Charsets.ISO_8859_1)
                secret = secret || credentialMarkers.any { it.containsMatchIn(text) }
                suffix = text.takeLast(256)
                output?.write(buffer, 0, count)
            }
        }
        requireSafePath(root, file, directory = false)
        val after = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(before.fileKey() == after.fileKey() && before.size() == bytes && after.size() == bytes &&
            before.lastModifiedTime() == after.lastModifiedTime()) { "Asset changed while reading: $relative" }
        return WebAppAsset(relative, bytes, digest.digest().hex()) to secret
    }

    private fun validateAssetPath(path: String) {
        require(path.toByteArray(Charsets.UTF_8).size <= limits.maxPathBytes) { "Asset path is too long: $path" }
        require(path.split('/').all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".." &&
                segment.none { it.code < 32 || it.code == 127 || it in "\\:%?#" } &&
                segment == Normalizer.normalize(segment, Normalizer.Form.NFC)
        }) { "Asset path is not a portable, normalized URL path: $path" }
    }

    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        internal const val WORKSPACE_FILE = ".platform/workspace.json"
        internal const val PACKAGE_FILE = ".platform/webapp-package.json"

        private val javaKeywords = ("abstract assert boolean break byte case catch char class const continue default " +
            "do double else enum extends final finally float for goto if implements import instanceof int interface " +
            "long native new package private protected public return short static strictfp super switch synchronized " +
            "this throw throws transient try void volatile while true false null _").split(' ').toSet()
        private val excludedDirectories = setOf("node_modules", "vendor", "secrets", "credentials", "__pycache__", "coverage")
        private val excludedNames = setOf(
            "package.json", "package-lock.json", "npm-shrinkwrap.json", "yarn.lock", "pnpm-lock.yaml", "bun.lockb", "bun.lock",
            "requirements.txt", "pipfile", "pipfile.lock", "poetry.lock", "pyproject.toml", "dockerfile", "compose.yaml",
            "docker-compose.yml", "google-services.json", "gradle.properties", "local.properties", "key.properties",
            "server.js", "server.cjs", "server.mjs", "id_rsa", "id_ed25519", "id_dsa", "id_ecdsa",
        )
        private val excludedExtensions = setOf(
            "pem", "key", "p12", "pfx", "jks", "keystore", "env", "db", "sqlite", "sqlite3", "log", "bak", "map",
            "py", "pyc", "pyo", "ipynb", "php", "rb", "sh", "bash", "java", "kt", "class", "jar", "node",
        )
        private val secretName = Regex("(^|[-_.])(secrets?|credentials?|private[-_]?keys?|service[-_]?accounts?|keystores?|tokens?)([-_.]|$)")
        private val credentialMarkers = listOf(
            Regex("-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----"),
            Regex("\"private_key\"\\s*:"),
            Regex("\"type\"\\s*:\\s*\"service_account\""),
            Regex("(?:AKIA|ASIA)[A-Z0-9]{16}"),
            Regex("gh[pousr]_[A-Za-z0-9]{30,}"),
        )

        private fun exclusion(name: String, directory: Boolean): String? {
            val lower = name.lowercase(Locale.ROOT)
            return when {
                lower.startsWith('.') -> "Hidden/configuration file or directory"
                directory && lower in excludedDirectories -> "Dependency, credential, or cache directory"
                secretName.containsMatchIn(lower) || lower.contains("firebase-adminsdk") -> "Credential-like name"
                !directory && (lower in excludedNames || lower.substringAfterLast('.', "") in excludedExtensions) ->
                    "Server/source/configuration/credential file (not a public asset)"
                else -> null
            }
        }

        internal fun validateIdentity(request: WebAppPackageRequest) {
            require(request.projectName.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}"))) {
                "Project name must start with a letter and contain only letters, digits, '-' or '_' (maximum 64)."
            }
            val segments = request.applicationId.split('.')
            require(request.applicationId.length <= 150 && segments.size >= 2 && segments.all {
                it.matches(Regex("[a-z][a-z0-9_]*")) && it !in javaKeywords
            }) { "Package name needs at least two lowercase Java identifiers, e.g. com.example.myapp (no keywords)." }
            require(request.appLabel.isNotBlank() && request.appLabel == request.appLabel.trim() &&
                request.appLabel.length <= 80 && request.appLabel.none { Character.isISOControl(it) || Character.isSurrogate(it) }) {
                "App label must contain 1–80 visible characters, without leading/trailing whitespace."
            }
            require(request.versionCode in 1..2_100_000_000) { "Version code must be 1–2100000000." }
            require(request.versionName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}"))) {
                "Version name must contain 1–64 letters, digits, '.', '_', '+' or '-'."
            }
        }

        private fun checkedRelative(raw: String, allowRoot: Boolean = false): String {
            if (allowRoot && raw == ".") return "."
            require(raw.isNotBlank() && !Paths.get(raw).isAbsolute && raw.none { it.code < 32 || it in "\\:%?#" }) {
                "Choose a workspace-relative static output directory, not an absolute path."
            }
            require(raw.split('/').all { it.isNotBlank() && it != "." && it != ".." && exclusion(it, true) == null }) {
                "Static output must not traverse '..', hidden, dependency, or credential directories."
            }
            return raw
        }

        private fun realDirectory(path: Path, label: String): Path {
            val absolute = path.toAbsolutePath().normalize()
            val attrs = Files.readAttributes(absolute, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attrs.isDirectory && !attrs.isSymbolicLink) { "$label must be a real directory, not a link." }
            return absolute.toRealPath()
        }

        internal fun requireSafePath(root: Path, path: Path, directory: Boolean) {
            require(path.normalize() == path && path.startsWith(root) && root.toRealPath() == root) { "Path escapes or redirects the chosen root." }
            var current = root
            val parts = root.relativize(path).filter { it.toString().isNotEmpty() }
            for ((index, part) in parts.withIndex()) {
                current = current.resolve(part)
                val attrs = Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(!attrs.isSymbolicLink) { "Links cannot be packaged: ${root.relativize(current)}" }
                require(if (index < parts.lastIndex || directory) attrs.isDirectory else attrs.isRegularFile) {
                    "Not a regular ${if (directory) "directory" else "file"}: ${root.relativize(current)}"
                }
            }
            require(path.toRealPath().startsWith(root)) { "Path escapes the chosen root." }
        }

        private fun hasMultipleLinks(path: Path): Boolean = try {
            (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as? Number)?.toLong()?.let { it > 1 } ?: false
        } catch (_: UnsupportedOperationException) {
            false // FAT/emulated Android storage cannot have hard links; not all providers expose unix:nlink.
        } catch (_: IllegalArgumentException) {
            false
        }

        internal fun checkInterrupted() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Web app packaging cancelled")
        }

        private fun relativeName(root: Path, path: Path): String = root.relativize(path).joinToString("/")
        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

        internal fun assetList(assets: List<WebAppAsset>): JsonArray = JsonArray(assets.map { asset ->
            buildJsonObject { put("path", asset.path); put("bytes", asset.bytes); put("sha256", asset.sha256) }
        })

        private fun manifest(review: WebAppPackageReview): JsonObject = buildJsonObject {
            put("schema", 1)
            put("origin", ORIGIN)
            put("entryPoint", "index.html")
            put("applicationId", review.request.applicationId)
            put("versionCode", review.request.versionCode)
            put("versionName", review.request.versionName)
            put("assetManifestSha256", review.assetManifestSha256)
            put("files", assetList(review.assets))
        }

        private fun resultJson(result: WebAppPackageResult): JsonObject = buildJsonObject {
            put("schema", 1)
            put("kind", "codeassist-offline-webapp")
            put("sourceWorkspace", result.sourceWorkspace.toString())
            put("sourceStaticDirectory", result.sourceStaticDirectory.toString())
            put("applicationId", result.applicationId)
            put("assetCount", result.assetCount)
            put("assetBytes", result.assetBytes)
            put("assetManifestSha256", result.assetManifestSha256)
        }
    }
}

/** Only removes entries this invocation created; never recursively deletes a pre-existing directory. */
private class NewProjectTree(private val root: Path) {
    private val made = mutableListOf<Path>()
    private val rootKey: Any?

    init {
        Files.createDirectory(root)
        made.add(root)
        rootKey = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
    }

    fun text(relative: String, text: String) = write(relative) { it.write(text.toByteArray(Charsets.UTF_8)) }

    fun write(relative: String, body: (OutputStream) -> Unit) {
        StaticWebAppPackager.checkInterrupted()
        checkRoot()
        val path = root.resolve(relative).normalize()
        require(path.startsWith(root) && path != root)
        var parent = root
        root.relativize(path.parent).filter { it.toString().isNotEmpty() }.forEach { part ->
            parent = parent.resolve(part)
            if (parent !in made) {
                Files.createDirectory(parent)
                made.add(parent)
            }
            StaticWebAppPackager.requireSafePath(root, parent, directory = true)
        }
        Files.newOutputStream(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
            made.add(path)
            body(output)
        }
        checkRoot()
    }

    private fun checkRoot() {
        require(Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == rootKey)
        StaticWebAppPackager.requireSafePath(root, root, directory = true)
    }

    fun rollback() {
        for (path in made.asReversed()) runCatching {
            checkRoot()
            if (path != root) StaticWebAppPackager.requireSafePath(root, path.parent, directory = true)
            Files.deleteIfExists(path)
        }
    }
}
