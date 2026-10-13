package dev.ide.android.workbench.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ide.workbench.runtime.RuntimeController
import dev.ide.workbench.runtime.RuntimeKind
import dev.ide.workbench.runtime.RuntimeProbeReport
import dev.ide.workbench.runtime.RuntimeProjectConfig
import dev.ide.workbench.runtime.RuntimeRunRequest
import dev.ide.workbench.runtime.RuntimeSample
import dev.ide.workbench.runtime.RuntimeSyncReport
import dev.ide.workbench.runtime.RuntimeTarget
import dev.ide.workbench.runtime.RuntimeWorkspaceMode
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class RuntimePendingAction(val root: Path, val config: RuntimeProjectConfig, val install: Boolean)

/** Screen lifetime owns only form/jobs. The activity, not this screen, owns the actual controller. */
internal class RuntimePanelState(
    private val controller: RuntimeController,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val preferences = context.getSharedPreferences("runtime-workspaces", Context.MODE_PRIVATE)
    private var projectKey = "none"
    private var attachedProject: String? = null
    private var attached = false
    private var operation: Job? = null
    var root by mutableStateOf<Path?>(null); private set
    var source by mutableStateOf(""); private set
    var targets by mutableStateOf<List<RuntimeTarget>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var hasPackageManifest by mutableStateOf(false); private set
    var targetId by mutableStateOf<String?>(null)
    var arguments by mutableStateOf("")
    var environment by mutableStateOf("")
    var workingDirectory by mutableStateOf(".")
    var previewPort by mutableStateOf("")
    var workspaceMode by mutableStateOf(RuntimeWorkspaceMode.AUTO)
    var trusted by mutableStateOf(false); private set
    var report by mutableStateOf<RuntimeProbeReport?>(null); private set
    var probing by mutableStateOf(false); private set
    var probeError by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var syncReport by mutableStateOf<RuntimeSyncReport?>(null); private set
    var pending by mutableStateOf<RuntimePendingAction?>(null); private set
    var stdin by mutableStateOf("")
    val samples: List<RuntimeSample> = controller.samples()
    val selectedTarget: RuntimeTarget? get() = targets.firstOrNull { it.id == targetId }

    suspend fun attachProject(path: String) {
        val normalized = path.takeIf { it.isNotBlank() }
        if (attached && attachedProject == normalized) return
        val changed = attached && attachedProject != normalized
        attached = true
        attachedProject = normalized
        projectKey = runtimePreferenceKey(normalized ?: "no-project")
        pending = null
        loaded = false
        busy = "Loading workspace"
        try {
            operation?.cancelAndJoin()
            operation = null
            busy = "Loading workspace"
            if (changed) controller.stop()
            val selected = preferences.getString("selected-$projectKey", null)
            val candidate = selected ?: normalized
            if (candidate == null) {
                root = null
                source = ""
                targets = emptyList()
                targetId = null
                hasPackageManifest = false
                trusted = false
                return
            }
            load(Path.of(candidate), if (selected != null) preferences.getString("source-$projectKey", "Selected workspace") ?: "Selected workspace" else "Current project")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { loaded = false; error = failure.message ?: "Could not load the workspace." }
        finally { busy = null }
    }

    fun probe() {
        if (probing) return
        probing = true
        probeError = null
        scope.launch {
            try { report = controller.probe() }
            catch (timeout: TimeoutCancellationException) { report = null; probeError = "A native runtime probe timed out. Availability could not be established; retry or choose static preview." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report = null; probeError = failure.message ?: "Runtime probe failed." }
            finally { probing = false }
        }
    }

    fun select(path: String, description: String) = action("Loading workspace") {
        controller.stop()
        pending = null
        load(Path.of(path), description)
        preferences.edit().putString("selected-$projectKey", root.toString()).putString("source-$projectKey", description).apply()
        message = "Workspace loaded. No project code was executed."
    }

    fun refresh() {
        val current = root ?: return
        action("Detecting targets") {
            if (!loaded) load(current, source)
            else {
                targets = controller.targets(current)
                hasPackageManifest = withContext(Dispatchers.IO) { Files.isRegularFile(current.resolve("package.json")) }
                if (targets.none { it.id == targetId }) targetId = preferredTarget(targets)?.id
            }
            message = "Run targets refreshed. Unsaved editor buffers are not read; save files before running."
        }
    }

    fun seed(sample: RuntimeSample) = action("Creating ${sample.label}") {
        controller.stop()
        val parent = context.filesDir.toPath().resolve("runtime-samples")
        withContext(Dispatchers.IO) { Files.createDirectories(parent) }
        val child = controller.seedSample(parent, sample.id)
        load(child, "Offline sample · ${sample.label}")
        preferences.edit().putString("selected-$projectKey", child.toString()).putString("source-$projectKey", source).apply()
        message = "Created a new sample workspace. Review its target and tap Start when ready; it has not been run."
    }

    fun configuration(): RuntimeProjectConfig {
        val port = previewPort.trim().let { if (it.isEmpty()) null else it.toIntOrNull() ?: error("Preview port must be a number.") }
        require(port == null || port in 1024..65535) { "Preview port must be between 1024 and 65535." }
        val cwd = workingDirectory.trim()
        require(cwd.isNotBlank() && !Path.of(cwd).isAbsolute && '\u0000' !in cwd && '\\' !in cwd) { "Working directory must be a relative project directory." }
        require(!Path.of(cwd).normalize().startsWith("..")) { "Working directory cannot leave the workspace." }
        return RuntimeProjectConfig(targetId, RuntimeArguments.parse(arguments), runtimeEnvironment(environment), cwd, workspaceMode, port)
    }

    fun save() {
        val current = root ?: return
        action("Saving configuration") {
            controller.saveConfig(current, configuration())
            message = "Saved run configuration in this workspace (.building/runtime.json)."
        }
    }

    fun requestRun(install: Boolean = false) {
        val current = root ?: return
        if (busy != null || controller.state.value.isRunning || !loaded) return
        try {
            val config = configuration()
            if (!install) require(selectedTarget != null) { "Choose a detected run target first." }
            val request = RuntimePendingAction(current, config, install)
            if (!trusted || install) pending = request else execute(request)
        } catch (failure: Exception) { showError(failure.message ?: "Invalid run configuration.") }
    }

    fun confirm() {
        val request = pending ?: return
        pending = null
        if (!runtimeSamePath(root, request.root) || busy != null || controller.state.value.isRunning) return
        preferences.edit().putBoolean("trusted-${runtimePreferenceKey(request.root.toString())}", true).apply()
        trusted = true
        execute(request)
    }

    fun dismissConsent() { pending = null }

    fun forgetTrust() {
        val current = root ?: return
        preferences.edit().remove("trusted-${runtimePreferenceKey(current.toString())}").apply()
        trusted = false
        pending = null
        message = "Workspace trust removed. The next run will ask for consent."
    }

    fun stop() {
        // Never let a probe, long output, or configuration job make the Stop affordance inert.
        scope.launch {
            try { controller.stop() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { showError(failure.message ?: "Could not stop the runtime.") }
        }
    }

    fun sync() = action("Syncing new runtime outputs") {
        check(!controller.state.value.isRunning) { "Stop the session before syncing files." }
        check(controller.state.value.workspaceCopy && runtimeSamePath(controller.state.value.projectRoot, root)) { "There is no runtime copy for this workspace to sync." }
        syncReport = controller.syncBack()
        message = null
    }

    fun sendInput() {
        try {
            require(stdin.length <= 64 * 1024) { "Input must not exceed 64 KiB." }
            check(controller.state.value.acceptsInput) { "This session is not accepting input." }
            controller.sendInput(stdin) // The controller itself appends exactly one newline.
            stdin = ""
        } catch (failure: Exception) { showError(failure.message ?: "Could not send input.") }
    }

    fun closeInput() {
        try { controller.closeInput() }
        catch (failure: Exception) { showError(failure.message ?: "Could not close input.") }
    }

    fun showError(text: String) { error = text; message = null }
    fun clearNotice() { error = null; message = null }

    private fun execute(request: RuntimePendingAction) = action(if (request.install) "Starting dependency install" else "Starting runtime") {
        check(runtimeSamePath(root, request.root)) { "The selected workspace changed." }
        syncReport = null
        stdin = ""
        controller.saveConfig(request.root, request.config)
        if (request.install) controller.installDependencies(request.root)
        // Stored config owns the args; passing them again would duplicate every argument.
        else controller.run(RuntimeRunRequest(request.root, requireNotNull(request.config.targetId)))
    }

    private suspend fun load(path: Path, description: String) {
        loaded = false
        root = path.toAbsolutePath().normalize()
        source = description
        trusted = false
        hasPackageManifest = false
        error = null
        message = null
        syncReport = null
        targets = emptyList()
        targetId = null
        val canonical = withContext(Dispatchers.IO) {
            require(Files.isDirectory(path)) { "Workspace folder is missing or unavailable: $path" }
            path.toRealPath()
        }
        root = canonical
        source = description
        trusted = preferences.getBoolean("trusted-${runtimePreferenceKey(canonical.toString())}", false)
        val config = controller.config(canonical)
        arguments = RuntimeArguments.format(config.args)
        environment = config.environment.entries.joinToString("\n") { "${it.key}=${it.value}" }
        workingDirectory = config.workingDirectory
        previewPort = config.previewPort?.toString().orEmpty()
        workspaceMode = config.workspaceMode
        hasPackageManifest = withContext(Dispatchers.IO) { Files.isRegularFile(canonical.resolve("package.json")) }
        targets = controller.targets(canonical)
        targetId = config.targetId?.takeIf { id -> targets.any { it.id == id } } ?: preferredTarget(targets)?.id
        loaded = true
    }

    private fun action(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        error = null
        message = null
        operation = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { showError(failure.message ?: "$label failed.") }
            finally { busy = null }
        }
    }

    private fun preferredTarget(items: List<RuntimeTarget>): RuntimeTarget? =
        items.firstOrNull { it.kind == RuntimeKind.NPM && it.script == "start" }
            ?: items.firstOrNull { it.kind == RuntimeKind.STATIC && it.relativePath.substringAfterLast('/') == "index.html" }
            ?: items.firstOrNull()
}
