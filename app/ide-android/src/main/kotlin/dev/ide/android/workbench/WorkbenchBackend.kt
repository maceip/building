package dev.ide.android.workbench

import dev.ide.ui.backend.BuildService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.RunTaskOption

/**
 * The workbench is reachable from the project picker as well as the editor. The engine's project getter
 * deliberately throws before a project is open; only this surface treats that condition as an empty
 * workspace, so its folder picker, offline samples and artifact tools remain usable.
 */
internal class WorkbenchBackend(private val delegate: IdeBackend) : IdeBackend by delegate {
    override val project: ProjectInfo
        get() = try {
            delegate.project
        } catch (failure: IllegalStateException) {
            // Do not turn unrelated backend failures into a misleading "no workspace" state.
            if (failure.message != "No project is open") throw failure
            NoProject
        }

    // The real build backend's flows are picker-safe, but its task discovery requires an open engine.
    // Keep those live flows and all execution operations delegated; no task exists to offer without a root.
    override val build: BuildService = object : BuildService by delegate.build {
        override fun supported(): Boolean = project.rootPath.isNotBlank() && delegate.build.supported()

        override fun runTasks(): List<RunTaskOption> =
            if (project.rootPath.isBlank()) emptyList() else delegate.build.runTasks()
    }

    private companion object {
        val NoProject = ProjectInfo(name = "", rootPath = "", moduleCount = 0)
    }
}
