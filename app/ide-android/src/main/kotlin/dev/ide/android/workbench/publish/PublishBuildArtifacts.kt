package dev.ide.android.workbench.publish

import dev.ide.ui.backend.BuildService
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.RunTaskOption
import dev.ide.workbench.publish.PlayErrorKind
import dev.ide.workbench.publish.PlayPublishingException
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** These are build-only tasks. In particular gradle:assemble can install/run and is never a fallback. */
internal fun isPublishBundleTask(task: RunTaskOption): Boolean = task.id.startsWith("bundle:") ||
    task.id == "gradle:bundle" || task.id.startsWith("gradle:bundle:")

internal fun publishBundleTasks(tasks: List<RunTaskOption>): List<RunTaskOption> = tasks
    .filter(::isPublishBundleTask)
    .sortedWith(compareByDescending<RunTaskOption> { it.id.endsWith(":release", ignoreCase = true) }
        .thenByDescending { "release" in it.label.lowercase() }
        .thenBy { it.label })

/** Subscribe before launching; an old Succeeded state is never evidence that this request finished. */
internal suspend fun runPublishBundleTask(build: BuildService, task: RunTaskOption): BuildState = coroutineScope {
    if (!isPublishBundleTask(task)) buildFailure("Select an explicit Android App Bundle task.")
    if (build.buildState.value.status == RunStatus.Running) buildFailure("Another build is running. Wait for it to finish before starting a bundle build.")
    val before = build.buildState.value
    var sawRunning = false
    val completion = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(30 * 60_000L) {
            build.buildState.first { state ->
                when (state.status) {
                    RunStatus.Running -> { sawRunning = true; false }
                    RunStatus.Succeeded -> sawRunning
                    RunStatus.Failed -> sawRunning || state != before
                    RunStatus.Idle -> false
                }
            }
        }
    }
    try {
        build.runTask(task.id)
        completion.await().also {
            if (it.status != RunStatus.Succeeded) buildFailure("The bundle build failed. Inspect the Build console, fix its errors, and build again. No artifact was selected or uploaded.")
        }
    } finally {
        completion.cancel()
    }
}

internal data class PublishBundleCandidate(val file: File, val reportedByBuild: Boolean, val modifiedAt: Long)

/**
 * Called only after the observed successful build. All candidates must be regular, root-contained
 * files under build/outputs/bundle. No symlinks, arbitrary log paths, hidden caches, or pre-build scan.
 * A cached output can be older than this build, so never call a scan candidate "newly generated".
 */
internal suspend fun findCompletedBundleArtifacts(rootPath: String, completed: BuildState): List<PublishBundleCandidate> = withContext(Dispatchers.IO) {
    if (completed.status != RunStatus.Succeeded || rootPath.isBlank()) return@withContext emptyList()
    val root = File(rootPath).canonicalFile
    if (!root.isDirectory) return@withContext emptyList()
    val rootPrefix = root.path + File.separator
    val reported = completed.log.mapNotNull { line ->
        line.message.trim().takeIf { it.startsWith("Signed bundle: ") }
            ?.removePrefix("Signed bundle: ")?.trim()?.takeIf { it.endsWith(".aab", true) }
    }.mapNotNull { candidate -> runCatching { File(candidate).canonicalFile.path }.getOrNull() }.toSet()
    val found = LinkedHashMap<String, PublishBundleCandidate>()
    var entries = 0
    val visited = HashSet<String>()
    val scanContext = currentCoroutineContext()

    fun allowed(file: File): Boolean {
        if (Files.isSymbolicLink(file.toPath())) return false
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return canonical.path.startsWith(rootPrefix)
    }
    fun accept(file: File) {
        if (!allowed(file) || !file.isFile || !file.name.endsWith(".aab", true) || file.length() <= 0L) return
        val canonical = file.canonicalFile
        val relative = canonical.path.removePrefix(rootPrefix).replace(File.separatorChar, '/')
        if ("/build/outputs/bundle/" !in "/$relative") return
        found[canonical.path] = PublishBundleCandidate(canonical, canonical.path in reported, canonical.lastModified())
    }
    fun scan(directory: File, depth: Int) {
        if (depth > 14 || entries >= 20_000 || found.size >= 100) return
        val canonical = runCatching { directory.canonicalPath }.getOrNull() ?: return
        if (!visited.add(canonical)) return
        scanContext.ensureActive()
        val children = runCatching { Files.newDirectoryStream(directory.toPath()) }.getOrNull() ?: return
        children.use { paths ->
            for (path in paths) {
                scanContext.ensureActive()
                if (++entries > 20_000 || found.size >= 100) break
                val child = path.toFile()
                if (!allowed(child)) continue
                if (child.isDirectory) {
                    if (child.name in setOf(".git", ".gradle", ".idea", ".kotlin", "node_modules", "src", "intermediates", "tmp", "generated")) continue
                    scan(child, depth + 1)
                } else accept(child)
            }
        }
    }
    // Prefer exact successful-build reports, but apply exactly the same path restrictions.
    reported.forEach { path -> accept(File(path)) }
    scan(root, 0)
    found.values.sortedWith(compareByDescending<PublishBundleCandidate> { it.reportedByBuild }.thenByDescending { it.modifiedAt })
}

private fun buildFailure(message: String): Nothing = throw PlayPublishingException(PlayErrorKind.VALIDATION, message)
