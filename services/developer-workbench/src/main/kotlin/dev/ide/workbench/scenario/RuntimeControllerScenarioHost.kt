package dev.ide.workbench.scenario

import dev.ide.workbench.runtime.RuntimeController
import dev.ide.workbench.runtime.RuntimePhase
import dev.ide.workbench.runtime.RuntimeRunRequest
import dev.ide.workbench.runtime.RuntimeSessionState
import kotlinx.coroutines.flow.first
import java.nio.file.Path

/** Pass a NEW, dedicated controller, never the shared interactive Runtime-tab controller.
 * The runner closes it after this run, including failures/cancellation. It is not reusable. */
class RuntimeControllerScenarioHost(private val controller: RuntimeController) : RuntimeScenarioHost {
    init {
        require(controller.state.value.sessionId == null && !controller.state.value.isRunning) {
            "Scenario host requires a fresh dedicated runtime controller"
        }
    }
    private var owned: ControllerSession? = null
    private var closed = false

    override suspend fun start(projectRoot: Path, targetId: String): RuntimeScenarioSession {
        check(!closed) { "Scenario host is closed" }
        if (controller.state.value.isRunning) throw ScenarioExecutionException("HOST_BUSY")
        if (controller.targets(projectRoot).none { it.id == targetId }) throw ScenarioExecutionException("TARGET_UNAVAILABLE")
        controller.run(RuntimeRunRequest(projectRoot, targetId))
        val id = controller.state.value.sessionId ?: throw ScenarioExecutionException("SESSION_ID_UNAVAILABLE")
        return ControllerSession(id).also { owned = it }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        try { owned?.stop() }
        finally { controller.close() }
    }

    private inner class ControllerSession(override val sessionId: String) : RuntimeScenarioSession {
        private fun checked(state: RuntimeSessionState): RuntimeSessionState {
            if (state.sessionId != sessionId) throw ScenarioExecutionException("SESSION_OWNERSHIP_LOST")
            return state
        }

        override suspend fun awaitReady(): ScenarioProcessSnapshot {
            val state = controller.state.first { checked(it).phase !in setOf(RuntimePhase.PREPARING, RuntimePhase.STOPPING) }
            return checked(state).asSnapshot()
        }

        override suspend fun awaitExit(): ScenarioProcessSnapshot {
            val state = controller.state.first { !checked(it).isRunning }
            return checked(state).asSnapshot()
        }

        override fun snapshot(): ScenarioProcessSnapshot = checked(controller.state.value).asSnapshot()

        override suspend fun stop() {
            checked(controller.state.value)
            controller.stop()
            if (checked(controller.state.value).isRunning) throw ScenarioExecutionException("STOP_INCOMPLETE")
        }
    }
}

private fun RuntimeSessionState.asSnapshot() = ScenarioProcessSnapshot(
    running = isRunning,
    exitCode = exitCode,
    stdout = stdout,
    stderr = stderr,
    stdoutTruncated = stdoutTruncated,
    stderrTruncated = stderrTruncated,
    previewUrl = previewUrl,
    failed = phase == RuntimePhase.FAILED,
)
