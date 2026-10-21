package dev.ide.workbench.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** Static package metadata shared by the project importer and the existing Runtime screen. */
data class NodePackageJson(
    val name: String?,
    val scripts: Map<String, String>,
    val main: String?,
    val packageManager: String?,
) {
    companion object {
        /** No installs, executable config loading, package resolution, or project code evaluation. */
        fun read(root: Path): NodePackageJson? = runCatching {
            if (!Files.isDirectory(root)) return null
            val file = safeProjectPath(root, "package.json")
            if (!Files.isRegularFile(file)) return null
            val value = readObject(file, 1024 * 1024)
            fun text(key: String): String? = (value[key] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            val scripts = (value["scripts"] as? JsonObject)?.entries?.mapNotNull { (name, command) ->
                if (name.isBlank() || name.startsWith('-') || name.any { it.isISOControl() } ||
                    command !is JsonPrimitive || !command.isString) null
                else name to command.content
            }?.toMap(LinkedHashMap()).orEmpty()
            NodePackageJson(text("name"), scripts, text("main"), text("packageManager"))
        }.getOrNull()

        /**
         * Recognizes a TypeScript/JavaScript repository that has no root `package.json` (for example,
         * `anthropics/claude-code` which has `mods/tsconfig.json` and `scripts/` `.ts` files without a root `package.json`).
         * Never called when a root `package.json` file or symlink is present.
         */
        fun detectTypeScriptWorkspace(root: Path): Pair<NodePackageJson, Path>? = runCatching {
            if (!Files.isDirectory(root)) return null
            if (Files.exists(root.resolve("package.json"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null
            val excluded = setOf(".git", ".platform", "node_modules", "build", "dist", ".venv", "venv")
            var tsconfigMarker: Path? = null
            var firstTs: Path? = null
            var tsCount = 0
            var pyCount = 0
            Files.walk(root, 5).use { stream ->
                stream.filter { p ->
                    Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                        root.relativize(p).none { seg -> seg.toString() in excluded }
                }.sorted().forEach { p ->
                    val name = p.fileName.toString()
                    when {
                        name == "tsconfig.json" && tsconfigMarker == null -> tsconfigMarker = p
                        (name.endsWith(".ts") || name.endsWith(".tsx")) && !name.endsWith(".d.ts") -> {
                            tsCount++
                            if (firstTs == null) firstTs = p
                        }
                        name.endsWith(".py") -> pyCount++
                    }
                }
            }
            if (tsCount == 0 || tsCount <= pyCount) return null
            val marker = tsconfigMarker ?: firstTs ?: return null
            val mainRel = firstTs?.let { root.relativize(it).toString().replace('\\', '/') }
            NodePackageJson(
                name = root.fileName?.toString() ?: "node-project",
                scripts = emptyMap(),
                main = mainRel,
                packageManager = null,
            ) to marker
        }.getOrNull()
    }
}

/** A read-only view of the SAME root the editor and Runtime controller use. */
data class NodeProjectRunInfo(
    val root: Path,
    val name: String,
    val manifest: NodePackageJson,
    val targets: List<RuntimeTarget>,
    val selectedTargetId: String?,
    /** Existing source/entry file, relative to [root], for the editor shortcut. Never a package resolution. */
    val entryFile: String?,
)

object NodeProjectBridge {
    fun inspect(root: Path): NodeProjectRunInfo? {
        val manifest = if (Files.exists(root.resolve("package.json"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            NodePackageJson.read(root) ?: return null
        } else {
            NodePackageJson.detectTypeScriptWorkspace(root)?.first ?: return null
        }
        val canonical = root.toRealPath()
        val targets = RuntimeProjects.targets(canonical).filter {
            it.kind == RuntimeKind.NODE || it.kind == RuntimeKind.NPM && it.script in manifest.scripts
        }
        val config = RuntimeProjects.load(canonical)
        val entry = (listOfNotNull(manifest.main) + listOf(
            "src/index.ts", "src/index.mts", "src/index.js", "src/index.mjs",
            "index.js", "index.mjs", "index.cjs", "server.js", "server.cjs",
        )).firstOrNull { relative ->
            runCatching { Files.isRegularFile(safeProjectPath(canonical, relative)) }.getOrDefault(false)
        }
        return NodeProjectRunInfo(
            canonical,
            manifest.name ?: canonical.fileName?.toString() ?: "Node project",
            manifest,
            targets,
            config.targetId?.takeIf { id -> targets.any { it.id == id } }
                ?: preferredTarget(targets, manifest.main)?.id,
            entry,
        )
    }

    /** Preserve a saved selection at the caller; this is only the conventional first-open default. */
    fun preferredTarget(targets: List<RuntimeTarget>, main: String? = null): RuntimeTarget? {
        val scripts = targets.filter { it.kind == RuntimeKind.NPM }.mapNotNull { it.script }.toSet()
        return targets.firstOrNull { it.kind == RuntimeKind.NPM && it.script == "start" }
            ?: targets.firstOrNull { it.kind == RuntimeKind.NPM && it.script == "dev" }
            ?: targets.firstOrNull { it.kind == RuntimeKind.NODE && it.relativePath == main?.removePrefix("./") }
            ?: targets.firstOrNull { it.kind == RuntimeKind.NPM && isDefaultScript(it.script, scripts) }
            ?: targets.firstOrNull { it.kind == RuntimeKind.NODE }
    }

    // Hooks remain explicit selectable targets, never the first-open default. Running an ordinary npm
    // script still executes that script's pre/post hooks, which the Runtime consent explains.
    private fun isDefaultScript(name: String?, scripts: Set<String>): Boolean {
        if (name == null || name in lifecycleScripts) return false
        val hooked = when {
            name.startsWith("pre") -> name.removePrefix("pre")
            name.startsWith("post") -> name.removePrefix("post")
            else -> return true
        }
        return hooked !in scripts && hooked !in lifecycleScripts && hooked !in setOf("start", "stop", "restart", "test")
    }

    private val lifecycleScripts = setOf(
        "install", "uninstall", "prepare", "prepublish", "prepublishOnly", "publish", "pack", "version", "dependencies", "restart", "stop",
    )
}

