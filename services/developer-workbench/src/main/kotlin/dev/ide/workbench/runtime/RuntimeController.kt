package dev.ide.workbench.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Application/backend-scoped runner. Opening a project never executes it or installs dependencies. */
class RuntimeController(
    private val host: RuntimeHost,
    scope: CoroutineScope,
    private val observer: RuntimeObserver = RuntimeObserver.NONE,
) : AutoCloseable {
    private val workerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(RuntimeSessionState())
    val state: StateFlow<RuntimeSessionState> = mutableState.asStateFlow()
    private var job: Job? = null
    @Volatile private var session: SessionObservation? = null
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
        val kinds = listOf(RuntimeKind.NODE, RuntimeKind.NPM, RuntimeKind.PYTHON, RuntimeKind.SHELL, RuntimeKind.XTOOL)
        if (kinds.all { host.disabledReason(it) != null }) {
            return@withContext RuntimeProbeReport("Not prepared", "", kinds.map {
                RuntimeProbe(it, false, error = host.disabledReason(it))
            } + RuntimeProbe(RuntimeKind.STATIC, host.disabledReason(RuntimeKind.STATIC) == null,
                error = host.disabledReason(RuntimeKind.STATIC)))
        }
        val installed = host.prepare()
        val commands = listOf(
            RuntimeKind.NODE to installed.node?.let { listOf(it.toString(), "--version") },
            RuntimeKind.NPM to installed.npmCli?.let { cli -> installed.node?.let { listOf(it.toString(), cli.toString(), "--version") } },
            RuntimeKind.PYTHON to installed.python?.let { listOf(it.toString(), "-c", "import sys,ssl,sqlite3,ctypes,readline; print(sys.version.split()[0])") },
            RuntimeKind.SHELL to installed.shell?.let { listOf(it.toString(), "-c", "printf 'dash ready\\n'") },
            RuntimeKind.XTOOL to (installed.xtool ?: installed.prefix.resolve("bin/xtool").takeIf { Files.isRegularFile(it) })?.let { listOf(it.toString(), "--version") },
        )
        val probes = commands.map { (kind, command) ->
            val disabled = host.disabledReason(kind)
            if (disabled != null) RuntimeProbe(kind, false, error = disabled)
            else if (command == null) RuntimeProbe(kind, false, error = "Not included for this ABI")
            else {
                val output = StringBuilder()
                try {
                    val env = if (kind == RuntimeKind.XTOOL) {
                        val xtoolLib = installed.prefix.resolve("lib/xtool").takeIf { Files.isDirectory(it) }?.toString()
                        val swiftRuntime = installed.prefix.resolve("lib/swift-runtime").takeIf { Files.isDirectory(it) }?.toString()
                        val swiftHost = installed.prefix.resolve("lib/swift/host").takeIf { Files.isDirectory(it) }?.toString()
                        val swiftCompiler = installed.prefix.resolve("lib/swift/host/compiler").takeIf { Files.isDirectory(it) }?.toString()
                        val baseLd = installed.environment["LD_LIBRARY_PATH"] ?: ""
                        val xtoolLd = listOfNotNull(xtoolLib, swiftRuntime, swiftHost, swiftCompiler, baseLd.takeIf { it.isNotBlank() }).joinToString(":")
                        installed.environment + ("LD_LIBRARY_PATH" to xtoolLd)
                    } else installed.environment
                    val process = host.start(command, installed.prefix.parent, env)
                    val code = withTimeout(30_000) { execute(process, { text -> synchronized(output) { if (output.length < 8192) output.append(text.take(8192 - output.length)) } }, stdinOpen = false) }
                    RuntimeProbe(kind, code == 0, output.toString().trim(), if (code == 0) null else "Probe exited $code")
                } catch (e: TimeoutCancellationException) { RuntimeProbe(kind, false, output.toString().trim(), "Probe timed out after 30 seconds") }
                catch (e: CancellationException) { throw e }
                catch (e: LinkageError) { RuntimeProbe(kind, false, error = "Host API unavailable: ${e.message}") }
                catch (e: Exception) { RuntimeProbe(kind, false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
        val staticDisabled = host.disabledReason(RuntimeKind.STATIC)
        RuntimeProbeReport(installed.packId, installed.abi,
            probes + RuntimeProbe(RuntimeKind.STATIC, staticDisabled == null, error = staticDisabled), listOf(
            "Native runtime bytes are delivered in the APK; provisioning and these probes are offline.",
            "User-trusted project execution, not a sandbox. Native addons and downloaded binaries must support Android/Bionic; desktop Linux, Bun and Cargo compatibility is not guaranteed.",
            "Explicit npm install can download packages and runs lifecycle scripts. npm run also executes pre/post hooks. Only install and run code you trust.",
        ))
    }

    /** Starts asynchronously and returns. Progress and completion are observable through [state]. */
    suspend fun run(request: RuntimeRunRequest) = gate.withLock {
        check(!closed.get()) { "Runtime controller is closed" }
        stopLocked()
        workspace = null
        startSession(request, install = false)
    }

    /** Explicit trusted action, never called by detection/open/run. npm lifecycle scripts execute normally. */
    suspend fun installDependencies(projectRoot: Path) = gate.withLock {
        check(!closed.get()) { "Runtime controller is closed" }
        stopLocked()
        workspace = null
        startSession(RuntimeRunRequest(projectRoot, "dependencies:npm"), install = true)
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun startSession(request: RuntimeRunRequest, install: Boolean) {
        val observation = SessionObservation(UUID.randomUUID(), request.projectRoot)
        session = observation
        mutableState.value = RuntimeSessionState(sessionId = observation.sessionId.toString(), phase = RuntimePhase.PREPARING, projectRoot = request.projectRoot)
        // Enter even for a cancelled parent, then ensureActive in the body. A completion handler could
        // otherwise call the host's provenance capture synchronously on the caller/main thread.
        job = workerScope.launch(start = CoroutineStart.ATOMIC) { runProject(request, install, observation) }
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
        // Record intent before terminate can race awaitExit and look like a project failure.
        session?.requestStop()
        if (job?.isActive == true) mutableState.update { it.copy(phase = RuntimePhase.STOPPING, acceptsInput = false, previewUrl = null) }
        preview?.close(); preview = null
        child?.let { withContext(NonCancellable) { it.terminate() } }
        job?.cancelAndJoin(); job = null
        session = null
        child = null
        mutableState.update { if (it.isRunning) it.copy(phase = RuntimePhase.FINISHED, acceptsInput = false, previewUrl = null) else it.copy(previewUrl = null) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        session?.requestStop()
        preview?.close(); preview = null
        mutableState.update { it.copy(acceptsInput = false, previewUrl = null) }
        // Independent of Activity/caller cancellation: never abandon native children on close.
        cleanupScope.launch {
            try { gate.withLock { stopLocked() } }
            finally { workerScope.cancel(); cleanupScope.cancel() }
        }
    }

    private suspend fun runProject(request: RuntimeRunRequest, install: Boolean, observation: SessionObservation) {
        try {
            currentCoroutineContext().ensureActive()
            val original = request.projectRoot.toRealPath()
            val config = RuntimeProjects.load(original)
            val target = if (install) RuntimeTarget("dependencies:npm", "Install npm dependencies", RuntimeKind.NPM, "package.json")
                else RuntimeProjects.targets(original).firstOrNull { it.id == request.targetId } ?: error("Run target no longer exists: ${request.targetId}")
            mutableState.update { it.copy(target = target) }
            observation.start(original, target)
            host.requireEnabled(target.kind)
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
                observation.stage = RuntimeOutcomeStage.LAUNCH
                host.requireEnabled(target.kind)
                val server = StaticPreviewServer(root, target.relativePath)
                preview = server
                observation.stage = RuntimeOutcomeStage.PROCESS
                mutableState.update { it.copy(phase = RuntimePhase.RUNNING, previewUrl = server.url) }
                append("Preview: ${server.url}\n")
                awaitCancellation()
            }
            val installed = host.prepare()
            observation.prepared(installed)
            val baseEnv = installed.environment + config.environment
            val env = if (target.kind == RuntimeKind.XTOOL) {
                val xtoolLib = installed.prefix.resolve("lib/xtool").takeIf { Files.isDirectory(it) }?.toString()
                val swiftRuntime = installed.prefix.resolve("lib/swift-runtime").takeIf { Files.isDirectory(it) }?.toString()
                val swiftHost = installed.prefix.resolve("lib/swift/host").takeIf { Files.isDirectory(it) }?.toString()
                val swiftCompiler = installed.prefix.resolve("lib/swift/host/compiler").takeIf { Files.isDirectory(it) }?.toString()
                val baseLd = baseEnv["LD_LIBRARY_PATH"] ?: ""
                val xtoolLd = listOfNotNull(xtoolLib, swiftRuntime, swiftHost, swiftCompiler, baseLd.takeIf { it.isNotBlank() }).joinToString(":")
                baseEnv + ("LD_LIBRARY_PATH" to xtoolLd)
            } else baseEnv
            val args = config.args + request.args
            require(args.size <= 128 && args.all { it.length <= 8192 && !it.contains('\u0000') }) { "Invalid runtime arguments" }
            val command = if (install) dependencyCommand(root, installed) else command(target, root, installed, args)
            append("> ${target.label}\n")
            observation.stage = RuntimeOutcomeStage.LAUNCH
            host.requireEnabled(target.kind)
            val process = host.start(command, cwd, env)
            child = process
            observation.stage = RuntimeOutcomeStage.PROCESS
            mutableState.update { it.copy(phase = RuntimePhase.RUNNING, acceptsInput = !install, previewUrl = config.previewPort?.let { port -> "http://127.0.0.1:$port/" }) }
            val code = execute(process, { appendStream(it, false) }, stdinOpen = !install, errorOutput = { appendStream(it, true) })
            observation.exited(code)
            append("\nProcess finished with exit code $code\n")
            mutableState.update { it.copy(phase = if (code == 0) RuntimePhase.FINISHED else RuntimePhase.FAILED, exitCode = code, acceptsInput = false, previewUrl = null, error = if (code == 0) null else "Process exited $code") }
        } catch (cancelled: CancellationException) {
            observation.failed(cancelled)
            mutableState.update { it.copy(phase = RuntimePhase.FINISHED, acceptsInput = false, previewUrl = null) }
            throw cancelled
        } catch (error: LinkageError) {
            observation.failed(error)
            val message = "Host API unavailable: ${error.message}"
            append("\n$message\n")
            mutableState.update { it.copy(phase = RuntimePhase.FAILED, acceptsInput = false, previewUrl = null, error = message) }
        } catch (error: Exception) {
            observation.failed(error)
            val message = error.message ?: error.javaClass.simpleName
            append("\n$message\n")
            mutableState.update { it.copy(phase = RuntimePhase.FAILED, acceptsInput = false, previewUrl = null, error = message) }
        } catch (error: Throwable) {
            observation.failed(error)
            throw error
        } finally {
            try {
                withContext(NonCancellable) { child?.terminate() }
                child = null
                preview?.close(); preview = null
            } catch (error: Throwable) {
                observation.failed(error)
                throw error
            } finally {
                observation.complete()
            }
        }
    }

    /** Session-local facts; observer code can neither alter them nor escape into execution errors. */
    private inner class SessionObservation(val sessionId: UUID, private val requestedRoot: Path) {
        private val startedAt = System.nanoTime()
        private val started = AtomicBoolean(false)
        private val finished = AtomicBoolean(false)
        private var handle = RuntimeSessionObserver.NONE
        private var kind: RuntimeKind? = null
        @Volatile var stage = RuntimeOutcomeStage.PREPARE
        private var stopRequested = false
        private var classification: RuntimeOutcomeClassification? = null
        private var exitCode: Int? = null
        private var exceptionCategory: RuntimeExceptionCategory? = null

        fun start(projectRoot: Path = requestedRoot, target: RuntimeTarget? = null) {
            if (!started.compareAndSet(false, true)) return
            kind = target?.kind
            val descriptor = target?.let {
                RuntimeObservedTarget(it.kind, it.id.take(256).map { char -> if (char in ' '..'~') char else '_' }.joinToString(""))
            }
            handle = try { observer.onSessionStarted(sessionId, projectRoot, kind, descriptor) }
            catch (_: Throwable) { RuntimeSessionObserver.NONE }
        }

        fun prepared(installation: RuntimeInstallation) {
            val metadata = RuntimePreparedMetadata(
                installation.packId.takeIf { it.length in 1..128 && IDENTITY_TOKEN.matches(it) },
                installation.abi.takeIf { it.length in 1..32 && IDENTITY_TOKEN.matches(it) },
            )
            try { handle.onRuntimePrepared(metadata) } catch (_: Throwable) { }
        }

        fun requestStop() = synchronized(this) {
            if (classification == null) stopRequested = true
        }

        fun exited(code: Int) = synchronized(this) {
            if (classification == null) {
                exitCode = code
                classification = when {
                    stopRequested -> RuntimeOutcomeClassification.CANCELLED
                    code == 0 -> RuntimeOutcomeClassification.SUCCESS
                    else -> RuntimeOutcomeClassification.NONZERO_EXIT
                }
            }
        }

        fun failed(error: Throwable) = synchronized(this) {
            if (classification == null) {
                classification = when {
                    stopRequested -> RuntimeOutcomeClassification.CANCELLED
                    error is TimeoutCancellationException -> RuntimeOutcomeClassification.TIMEOUT
                    error is CancellationException -> RuntimeOutcomeClassification.CANCELLED
                    else -> RuntimeOutcomeClassification.UNEXPECTED_FAILURE
                }
                if (classification == RuntimeOutcomeClassification.UNEXPECTED_FAILURE) {
                    exceptionCategory = when (error) {
                        is java.io.IOException -> RuntimeExceptionCategory.IO
                        is SecurityException -> RuntimeExceptionCategory.SECURITY
                        is IllegalArgumentException, is IllegalStateException -> RuntimeExceptionCategory.INVALID_CONFIGURATION
                        is LinkageError -> RuntimeExceptionCategory.HOST_API_UNAVAILABLE
                        else -> RuntimeExceptionCategory.OTHER
                    }
                }
            }
        }

        fun complete() {
            if (!finished.compareAndSet(false, true)) return
            // Resolution/pre-entry failures have no resolved kind: do not parse a caller's target ID.
            start()
            val outcome = synchronized(this) {
                val result = classification ?: if (stopRequested) RuntimeOutcomeClassification.CANCELLED else RuntimeOutcomeClassification.UNEXPECTED_FAILURE
                RuntimeSessionOutcome(
                    sessionId = sessionId,
                    kind = kind,
                    stage = stage,
                    classification = result,
                    exitCode = exitCode,
                    durationMillis = ((System.nanoTime() - startedAt).coerceAtLeast(0) / 1_000_000).coerceAtMost(RuntimeSessionOutcome.MAX_DURATION_MILLIS),
                    exceptionCategory = if (result == RuntimeOutcomeClassification.UNEXPECTED_FAILURE) exceptionCategory ?: RuntimeExceptionCategory.OTHER else null,
                )
            }
            try { handle.onSessionFinished(outcome) } catch (_: Throwable) { }
        }
    }

    private fun command(target: RuntimeTarget, root: Path, installation: RuntimeInstallation, args: List<String>): List<String> = when (target.kind) {
        RuntimeKind.NODE -> listOf(requireNotNull(installation.node) { "Node is not available" }.toString(), safeProjectPath(root, target.relativePath).toString()) + args
        RuntimeKind.PYTHON -> listOf(requireNotNull(installation.python) { "Python is not available" }.toString(), "-u", safeProjectPath(root, target.relativePath).toString()) + args
        RuntimeKind.SHELL -> listOf(requireNotNull(installation.shell) { "dash is not available" }.toString(), safeProjectPath(root, target.relativePath).toString()) + args
        RuntimeKind.NPM -> listOf(requireNotNull(installation.node).toString(), requireNotNull(installation.npmCli) { "npm is not available" }.toString(), "--prefix", root.toString(), "--no-audit", "--no-fund", "run", requireNotNull(target.script), "--") + args
        RuntimeKind.STATIC -> error("Static targets do not start a process")
        RuntimeKind.XTOOL -> {
            val xtoolExe = installation.xtool ?: installation.prefix.resolve("bin/xtool").takeIf { Files.isRegularFile(it) }
            val swiftExe = installation.prefix.resolve("bin/swift").takeIf { Files.isRegularFile(it) }
            if (target.script != null) {
                val exe = requireNotNull(xtoolExe ?: swiftExe) { "xtool is not available" }
                listOf(exe.toString()) + target.script.split(" ").filter { it.isNotBlank() } + args
            } else {
                val exe = requireNotNull(swiftExe ?: xtoolExe) { "Swift runtime is not available" }
                listOf(exe.toString(), safeProjectPath(root, target.relativePath).toString()) + args
            }
        }
    }

    private fun dependencyCommand(root: Path, installation: RuntimeInstallation): List<String> {
        readObject(safeProjectPath(root, "package.json"))
        append("[Installing trusted npm dependencies with lifecycle scripts enabled. Packages may download and execute code with this app's permissions. Native addons and binaries must support Android/Bionic.]\n")
        return listOf(requireNotNull(installation.node) { "Node is not available" }.toString(), requireNotNull(installation.npmCli) { "npm is not available" }.toString(), "--prefix", root.toString(), "install", "--no-audit", "--no-fund")
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
        private val IDENTITY_TOKEN = Regex("[A-Za-z0-9._-]+")
        private val PREVIEW = Regex("http://(?:127\\.0\\.0\\.1|localhost):[0-9]{2,5}(?:/[^\\s\\\"'<>]*)?")
    }
}
