// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.android.workbench.appengine

import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.RunStatus
import dev.ide.workbench.appengine.AppEnginePackageResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.ZipFile

/** Picker-safe only for the backend's explicit no-project condition; unrelated failures stay visible. */
internal fun engineWorkspaceOrEmpty(backend: IdeBackend): String = try {
    backend.project.rootPath
} catch (error: IllegalStateException) {
    if (error.message != "No project is open") throw error
    ""
}

internal suspend fun openEngineGeneratedProject(backend: IdeBackend, generated: AppEnginePackageResult) {
    check(backend.build.buildState.value.status != RunStatus.Running) { "Wait for the current build before switching projects." }
    check(backend.projects.openProject(generated.projectRoot.toString())) { "Could not open the generated native project." }
}

/** Do not auto-open, fall back to a desktop/Gradle task, sign a release or install the APK. */
internal fun buildEngineGeneratedDebug(backend: IdeBackend, generated: AppEnginePackageResult) {
    check(engineWorkspaceOrEmpty(backend) == generated.projectRoot.toString()) { "Open the generated project before building it." }
    check(backend.build.buildState.value.status != RunStatus.Running) { "A build is already running." }
    check(backend.build.supported()) { "The active backend cannot build Android apps." }
    val task = backend.build.runTasks().firstOrNull { it.id == "assemble:app:debug" }
        ?: error("Native assemble:app:debug is unavailable. Check the installed Android SDK/toolchain.")
    check(engineWorkspaceOrEmpty(backend) == generated.projectRoot.toString()) { "The active project changed; reopen the generated project." }
    backend.build.runTask(task.id)
}

/** Existing artifact checks, not a claim about freshness, signing certificates or release acceptance. */
internal fun engineGeneratedArtifacts(generated: AppEnginePackageResult): Pair<Path?, Path?> {
    val root = generated.projectRoot
    val apk = root.resolve("app/build/outputs/apk/debug/app-debug.apk")
    val debug = apk.takeIf { file ->
        regularEngineArtifact(root, file) && runCatching {
            ZipFile(file.toFile()).use { zip ->
                zip.getEntry("AndroidManifest.xml")?.let { !it.isDirectory && it.size > 0 } == true &&
                    zip.getEntry("classes.dex")?.let { !it.isDirectory && it.size > 0 } == true
            }
        }.getOrDefault(false)
    }
    val source = generated.sourceBundle.takeIf { regularEngineArtifact(root, it) && Files.size(it) > 1024L }
    return debug to source
}

private fun regularEngineArtifact(root: Path, file: Path): Boolean = try {
    EnginePackageInputs.noLinks(root)
    EnginePackageInputs.noLinks(file)
    Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) > 0L && file.toRealPath().startsWith(root.toRealPath())
} catch (_: Exception) { false }
