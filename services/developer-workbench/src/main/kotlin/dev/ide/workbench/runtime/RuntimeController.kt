package dev.ide.workbench.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Application/backend-scoped runner. Opening a project never executes it or installs dependencies. */
class RuntimeController(private val host: RuntimeHost, scope: CoroutineScope) : AutoCloseable {
    private val workerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(RuntimeSessionState())
    val state: StateFlow<RuntimeSessionState> = mutableState.asStateFlow()
    private var job: Job? = null
    @Volatile private var child: RuntimeChild? = null
    @Volatile private var preview: StaticPreviewServer? = null
    @Volatile private var workspace: RuntimeWorkspace? = null

    suspend fun targets(projectRoot: Path): List<RuntimeTarget> = withContext(Dispatchers.IO) { RuntimeProjects.targets(projectRoot) }
    suspend fun config(projectRoot: Path): RuntimeProjectConfig = withContext(Dispatchers.IO) { RuntimeProjects.load(projectRoot) }
    suspend fun saveConfig(projectRoot: Path, config: RuntimeProjectConfig) = withContext(Dispatchers.IO) { RuntimeProjects.save(projectRoot, config) }
    fun samples(): List<RuntimeSample> = RuntimeSamples.list()
    suspend fun seedSample(projectRoot: Path, sampleId: String): Path = withContext(Dispatchers.IO) { RuntimeSamples.seed(projectRoot, sampleId) }

    suspend fun probe(): RuntimeProbeReport = withContext(Dispatchers.IO) {
        check(!closed.get()) { "Runtime controller is closed" }
        val installed = host.prepare()
        val commands = listOf(
            RuntimeKind.NODE to installed.node?.let { listOf(it.toString(), "--version") },
            RuntimeKind.NPM to installed.npmCli?.let { cli -> installed.node?.let { listOf(it.toString(), cli.toString(), "--version") } },
            RuntimeKind.PYTHON to installed.python?.let { listOf(it.toString(), "-c", "import sys,ssl,sqlite3,ctypes,readline; print(sys.version.split()[0])") },
            RuntimeKind.SHELL to installed.shell?.let { listOf(it.toString(), "-c", "printf 'dash ready\\n'") },
        )
        val probes = commands.map { (kind, command) ->
            if (command == null) RuntimeProbe(kind, false, error = "Not included for this ABI")
            else {
                val output = StringBuilder()
                try {
                    val process = host.start(command, installed.prefix.parent, installed.environment)
                    val code = withTimeout(30_000) { execute(process, { text -> synchronized(output) { if (output.length < 8192) output.append(text.take(8192 - output.length)) } }, stdinOpen = false) }
                    RuntimeProbe(kind, code == 0, output.toString().trim(), if (code == 0) null else "Probe exited $code")
                } catch (e: TimeoutCancellationException) { RuntimeProbe(kind, false, output.toString().trim(), "Probe timed out after 30 seconds") }
                catch (e: CancellationException) { throw e }
                catch (e: LinkageError) { RuntimeProbe(kind, false, error = "Host API unavailable: ${e.message}") }
                catch (e: Exception) { RuntimeProbe(kind, false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
        RuntimeProbeReport(installed.packId, installed.abi, probes, listOf(
            "Native runtime bytes are delivered in the APK; provisioning and these probes are offline.",
            "User-trusted project execution, not a sandbox. Native Bun, Cargo, arbitrary Linux binaries and native npm/pip packages are not supported.",
            if (installed.allowNativeDependencyDownloads) "Developer distribution: explicit npm installation is allowed with lifecycle scripts disabled; native downloads are not represented as Play compliant."
            else "APK-only mode: arbitrary npm dependency downloads are disabled; only dependency-free projects install offline.",
        ))
    }

    /** Starts asynchronously and returns. Progress and completion are observable through [state]. */
    suspend fun run(request: RuntimeRunRequest) = gate.withLock {
        check(!closed.get()) { "Runtime controller is closed" }
        stopLocked()
        workspace = null
        mutableState.value = RuntimeSessionState(sessionId = UUID.randomUUID().toString(), phase = RuntimePhase.PREPARING, projectRoot = request.projectRoot)
        job = workerScope.launch { runProject(request, install = false) }
    }

    /** Explicit action, never called by detection/open/run. Broad downloads require a developer-only host gate. */
    suspend fun installDependencies(projectRoot: Path) = gate.withLock {
        check(!closed.get()) { "Runtime controller is closed" }
        stopLocked()
        workspace = null
        mutableState.value = RuntimeSessionState(sessionId = UUID.randomUUID().toString(), phase = RuntimePhase.PREPARING, projectRoot = projectRoot)
        job = workerScope.launch { runProject(RuntimeRunRequest(projectRoot, "dependencies:npm"), install = true) }
    }

    fun sendInput(text: String) {
        if (!state.value.acceptsInput || closed.get()) return
        val active = child ?: return
        require(text.length <= 64 * 1024) { "Input is too large" }
        workerScope.launch {
            runCatching { synchronized(active.stdin) { active.stdin.write((text + "\n").toByteArray()); active.stdin.flush() } }
                .onFailure { append("\n[stdin closed]\n") }
        }
    }

    fun closeInput() {
        val active = child
        mutableState.update { it.copy(acceptsInput = false) }
        if (active != null) workerScope.launch { runCatching { active.stdin.close() } }
    }

    suspend fun stop() = gate.withLock { stopLocked() }

    suspend fun syncBack(): RuntimeSyncReport = gate.withLock {
        check(!state.value.isRunning) { "Stop the process before syncing its output files" }
        withContext(Dispatchers.IO) { workspace?.syncBack() ?: RuntimeSyncReport(0) }
    }

    private suspend fun stopLocked() {
        if (job?.isActive == true) mutableState.update { it.copy(phase = RuntimePhase.STOPPING, acceptsInput = false, previewUrl = null) }
        preview?.close(); preview = null
        child?.let { withContext(NonCancellable) { it.terminate() } }
        job?.cancelAndJoin(); job = null
        child = null
        mutableState.update { if (it.isRunning) it.copy(phase = RuntimePhase.FINISHED, acceptsInput = false, previewUrl = null) else it.copy(previewUrl = null) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        preview?.close(); preview = null
        mutableState.update { it.copy(acceptsInput = false, previewUrl = null) }
        // Independent of Activity/caller cancellation: never abandon native children on close.
        cleanupScope.launch {
            try { gate.withLock { stopLocked() } }
            finally { workerScope.cancel(); cleanupScope.cancel() }
        }
    }

    private suspend fun runProject(request: RuntimeRunRequest, install: Boolean) {
        try {
            val original = request.projectRoot.toRealPath()
            val config = RuntimeProjects.load(original)
            val target = if (install) RuntimeTarget("dependencies:npm", "Install npm dependencies", RuntimeKind.NPM, "package.json")
                else RuntimeProjects.targets(original).firstOrNull { it.id == request.targetId } ?: error("Run target no longer exists: ${request.targetId}")
            mutableState.update { it.copy(target = target) }
            val useCopy = config.workspaceMode == RuntimeWorkspaceMode.INTERNAL_COPY || config.workspaceMode == RuntimeWorkspaceMode.AUTO && host.requiresWorkspaceCopy(original)
            val selectedWorkspace = if (useCopy) RuntimeWorkspace(original, host.workspaceCache) else null
            workspace = selectedWorkspace
            val root = selectedWorkspace?.root ?: original
            // Publish ownership before preparation can fail (e.g. conflicting editor/runtime edits).
            mutableState.update { it.copy(workspacePath = root, workspaceCopy = useCopy) }
            selectedWorkspace?.prepare()
            val cwd = safeProjectPath(root, config.workingDirectory)
            require(Files.isDirectory(cwd)) { "Working directory is not a directory" }
            if (useCopy) append("[Internal workspace copy. Source saves copy in on Run; Sync new outputs after Stop creates new files only. Existing-file changes require manual merge. Dependencies remain private.]\n")
            append("[User-trusted execution; this is not a security sandbox.]\n")
            if (target.kind == RuntimeKind.STATIC) {
                val server = StaticPreviewServer(root, target.relativePath)
                preview = server
                mutableState.update { it.copy(phase = RuntimePhase.RUNNING, previewUrl = server.url) }
                append("Preview: ${server.url}\n")
                awaitCancellation()
            }
            val installed = host.prepare()
            val env = installed.environment + config.environment
            val args = config.args + request.args
            require(args.size <= 128 && args.all { it.length <= 8192 && !it.contains('\u0000') }) { "Invalid runtime arguments" }
            val command = if (install) dependencyCommand(root, installed) else command(target, root, installed, args)
            append("> ${target.label}\n")
            val process = host.start(command, cwd, env)
            child = process
            mutableState.update { it.copy(phase = RuntimePhase.RUNNING, acceptsInput = !install, previewUrl = config.previewPort?.let { port -> "http://127.0.0.1:$port/" }) }
            val code = execute(process, { appendStream(it, false) }, stdinOpen = !install, errorOutput = { appendStream(it, true) })
            append("\nProcess finished with exit code $code\n")
            mutableState.update { it.copy(phase = if (code == 0) RuntimePhase.FINISHED else RuntimePhase.FAILED, exitCode = code, acceptsInput = false, previewUrl = null, error = if (code == 0) null else "Process exited $code") }
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(phase = RuntimePhase.FINISHED, acceptsInput = false, previewUrl = null) }
            throw cancelled
        } catch (error: LinkageError) {
            val message = "Host API unavailable: ${error.message}"
            append("\n$message\n")
            mutableState.update { it.copy(phase = RuntimePhase.FAILED, acceptsInput = false, previewUrl = null, error = message) }
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            append("\n$message\n")
            mutableState.update { it.copy(phase = RuntimePhase.FAILED, acceptsInput = false, previewUrl = null, error = message) }
        } finally {
            withContext(NonCancellable) { child?.terminate() }
            child = null
            preview?.close(); preview = null
        }
    }

    private fun command(target: RuntimeTarget, root: Path, installation: RuntimeInstallation, args: List<String>): List<String> = when (target.kind) {
        RuntimeKind.NODE -> listOf(requireNotNull(installation.node) { "Node is not available" }.toString(), safeProjectPath(root, target.relativePath).toString()) + args
        RuntimeKind.PYTHON -> listOf(requireNotNull(installation.python) { "Python is not available" }.toString(), "-u", safeProjectPath(root, target.relativePath).toString()) + args
        RuntimeKind.SHELL -> listOf(requireNotNull(installation.shell) { "dash is not available" }.toString(), safeProjectPath(root, target.relativePath).toString()) + args
        RuntimeKind.NPM -> listOf(requireNotNull(installation.node).toString(), requireNotNull(installation.npmCli) { "npm is not available" }.toString(), "--prefix", root.toString(), "--ignore-scripts", "--no-audit", "--no-fund", "run", requireNotNull(target.script), "--") + args
        RuntimeKind.STATIC -> error("Static targets do not start a process")
    }

    private fun dependencyCommand(root: Path, installation: RuntimeInstallation): List<String> {
        val manifest = readObject(safeProjectPath(root, "package.json"))
        if (installation.allowNativeDependencyDownloads) {
            append("[DEVELOPER DISTRIBUTION ONLY: explicitly installing untrusted npm packages. Native payloads may be downloaded; this is not the Play-target mode. Lifecycle scripts are disabled, but later Run executes user-trusted package code. No Linux/Bionic addon compatibility is promised.]\n")
        } else RuntimeDependencyPolicy.check(manifest, installation.approvedNpmPackages)
        val declared = listOf("dependencies", "devDependencies", "optionalDependencies", "peerDependencies")
            .flatMap { field -> (manifest[field] as? JsonObject)?.values.orEmpty() }
        val entirelyLocal = manifest["workspaces"] == null && declared.all { (it as? JsonPrimitive)?.content?.startsWith("file:") == true }
        return listOf(requireNotNull(installation.node).toString(), requireNotNull(installation.npmCli).toString(), "--prefix", root.toString(), "install", "--ignore-scripts", "--no-audit", "--no-fund", "--registry=https://registry.npmjs.org/", "--strict-ssl=true") +
            if (!installation.allowNativeDependencyDownloads || entirelyLocal) listOf("--offline") else emptyList()
    }

    private fun append(text: String) {
        if (text.isEmpty()) return
        mutableState.update { current ->
            val transcript = (current.transcript + text).takeLast(MAX_TRANSCRIPT)
            val url = if (current.phase == RuntimePhase.RUNNING && current.previewUrl == null) {
                PREVIEW.findAll(transcript.takeLast(8192)).lastOrNull()?.value?.replace("localhost", "127.0.0.1")
            } else current.previewUrl
            current.copy(transcript = transcript, previewUrl = url)
        }
    }

    private fun appendStream(text: String, isError: Boolean) {
        mutableState.update { current ->
            if (isError) current.copy(stderr = (current.stderr + text).takeLast(MAX_TRANSCRIPT), stderrTruncated = current.stderrTruncated || current.stderr.length + text.length > MAX_TRANSCRIPT)
            else current.copy(stdout = (current.stdout + text).takeLast(MAX_TRANSCRIPT), stdoutTruncated = current.stdoutTruncated || current.stdout.length + text.length > MAX_TRANSCRIPT)
        }
        append(text)
    }

    private suspend fun execute(process: RuntimeChild, output: (String) -> Unit, stdinOpen: Boolean, errorOutput: (String) -> Unit = output): Int = coroutineScope {
        val stdout = launch(Dispatchers.IO) { read(process.stdout, output) }
        val stderr = launch(Dispatchers.IO) { read(process.stderr, errorOutput) }
        try {
            if (!stdinOpen) process.stdin.close()
            val code = process.awaitExit()
            // Descendants can retain stdout or a preview listener after their parent exits.
            withContext(NonCancellable) { process.terminate() }
            withTimeoutOrNull(2_000) { stdout.join(); stderr.join() }
            code
        } finally {
            withContext(NonCancellable) { process.terminate() }
            runCatching { process.stdout.close() }; runCatching { process.stderr.close() }; runCatching { process.stdin.close() }
            stdout.cancel(); stderr.cancel()
        }
    }

    private suspend fun read(input: InputStream, output: (String) -> Unit) {
        val reader = input.reader(Charsets.UTF_8)
        val buffer = CharArray(2048)
        try {
            while (currentCoroutineContext().isActive) {
                val count = reader.read(buffer)
                if (count < 0) break
                output(String(buffer, 0, count))
            }
        } catch (error: java.io.IOException) { if (currentCoroutineContext().isActive && !closed.get()) output("\n[stream closed]\n") }
    }

    companion object {
        const val MAX_TRANSCRIPT = 256 * 1024
        private val PREVIEW = Regex("http://(?:127\\.0\\.0\\.1|localhost):[0-9]{2,5}(?:/[^\\s\\\"'<>]*)?")
    }
}

/** Fail closed: ignore-scripts does not itself prevent a tarball containing executable native code. */
internal object RuntimeDependencyPolicy {
    fun check(manifest: JsonObject, approved: Map<String, String>) {
        val dependencies = listOf("dependencies", "devDependencies", "optionalDependencies", "peerDependencies")
            .flatMap { field -> (manifest[field] as? JsonObject)?.entries.orEmpty() }
        require(manifest["workspaces"] == null && manifest["bundledDependencies"] == null && manifest["bundleDependencies"] == null) { "Workspace/bundled dependencies are not audited for APK-only mode" }
        require(dependencies.all { (name, version) -> approved[name] == (version as? JsonPrimitive)?.content }) {
            "Dependency download blocked in APK-only mode. Arbitrary npm/pip packages may contain native code; --ignore-scripts is not enough. Use a dependency-free project or an APK-delivered audited dependency pack."
        }
        require(dependencies.isEmpty()) { "Approved-package installer is not yet available; no dependencies were downloaded" }
    }
}
