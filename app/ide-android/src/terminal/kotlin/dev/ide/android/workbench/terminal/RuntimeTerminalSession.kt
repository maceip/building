package dev.ide.android.workbench.terminal

import android.content.Context
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import dev.ide.android.workbench.runtime.AndroidRuntimeHost
import dev.ide.workbench.runtime.RuntimeInstallation
import dev.ide.workbench.runtime.RuntimeKind
import dev.ide.workbench.runtime.requireEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Separate, explicitly user-trusted terminal. It does not alter RuntimeController's pipe-based runs.
 * Optional observation describes only this owned shell/REPL process, not individual typed commands.
 */
class RuntimeTerminalSession(
    context: Context,
    private val outcomeObserverFactory: TerminalOutcomeObserverFactory = TerminalOutcomeObserverFactory.NONE,
) {
    enum class Program { SHELL, NODE }
    enum class Phase { STARTING, RUNNING, STOPPING, FINISHED, FAILED }
    data class Status(
        val phase: Phase = Phase.STARTING,
        val directory: String = "",
        val title: String = "Terminal",
        val exitCode: Int? = null,
        val error: String? = null,
        val cleanupWarning: String? = null,
    )
    private data class Size(val columns: Int, val rows: Int)

    private val host = AndroidRuntimeHost(context.applicationContext)
    // Linux PR_SET_PDEATHSIG follows the *forking thread*, not only the app process.
    // A Dispatchers.IO worker may retire while this coroutine is suspended on Main.
    // Keep one non-timeout worker alive until its child is killed, drained and reaped.
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "runtime-terminal-pty").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    @Volatile private var forkThread: Thread? = null
    internal fun forkThreadIsAlive(): Boolean = forkThread?.isAlive == true
    private val stopping = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    // Keystrokes/IME paste are bounded; neither a full kernel input queue nor output flood blocks UI.
    private val input = ArrayBlockingQueue<ByteArray>(128)
    private val desiredSize = AtomicReference(Size(80, 24))
    private val mutableStatus = MutableStateFlow(Status())
    val status = mutableStatus.asStateFlow()
    private val mutableFrame = MutableStateFlow(0L)
    val frame = mutableFrame.asStateFlow()
    private var pasteBuffer: java.io.ByteArrayOutputStream? = null
    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            val collecting = pasteBuffer
            if (collecting != null) collecting.write(data, offset, count)
            else offer(data.copyOfRange(offset, offset + count))
        }
        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            mutableStatus.update { it.copy(title = newTitle.orEmpty().take(120).ifBlank { "Terminal" }) }
        }
        // A program cannot read or replace the user's clipboard through OSC 52/clipboard queries.
        // Clipboard paste is ONLY through our explicit user action in the terminal UI.
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }
    private val emulator = TerminalEmulator(output, 80, 24, 8, 16, 2000, object : TerminalSessionClient {})

    /** All emulator mutation/drawing occurs on Main; no ever-growing text transcript or pipe facade. */
    fun <T> withEmulator(block: (TerminalEmulator) -> T): T = block(emulator)

    @OptIn(DelicateCoroutinesApi::class)
    fun start(directory: Path?, program: Program = Program.SHELL) {
        check(started.compareAndSet(false, true)) { "Terminal already started" }
        val startedAtNanos = System.nanoTime()
        val observationContext = TerminalSessionContext(
            sessionId = java.util.UUID.randomUUID().toString(),
            programKind = when (program) {
                Program.SHELL -> TerminalProgramKind.SHELL
                Program.NODE -> TerminalProgramKind.NODE
            },
            workingDirectory = directory,
            startedAtEpochMs = System.currentTimeMillis(),
        )
        // Enter the worker's try/finally even if cancellation wins before dispatch. UNDISPATCHED
        // would run the host factory on Main; DEFAULT could skip observation and cleanup entirely.
        scope.launch(start = CoroutineStart.ATOMIC) {
            val observation = TerminalOutcomeDelivery(observationContext, outcomeObserverFactory, startedAtNanos)
            var child: RuntimePty? = null
            var exit: Int? = null
            try {
                currentCoroutineContext().ensureActive()
                if (stopping.get()) { observation.cancelled(); return@launch }
                val kind = if (program == Program.NODE) RuntimeKind.NODE else RuntimeKind.SHELL
                host.requireEnabled(kind)
                val installation = host.prepare()
                observation.runtimePrepared(TerminalRuntimeIdentity(installation.packId, installation.abi))
                currentCoroutineContext().ensureActive()
                if (stopping.get()) { observation.cancelled(); return@launch }
                val root = (directory ?: java.nio.file.Paths.get(installation.environment.getValue("HOME"))).toRealPath()
                require(Files.isDirectory(root)) { "Terminal working directory is unavailable" }
                val command = when (program) {
                    Program.SHELL -> listOf(requireNotNull(installation.shell) { "Bundled shell is unavailable" }.toRealPath().toString(), "-i")
                    Program.NODE -> listOf(requireNotNull(installation.node) { "Bundled Node.js is unavailable" }.toRealPath().toString(), "--interactive")
                }
                val system = SYSTEM_ENV.mapNotNull { key -> System.getenv(key)?.let { key to it } }.toMap()
                val environment = terminalEnvironment(installation, system)
                currentCoroutineContext().ensureActive()
                if (stopping.get()) { observation.cancelled(); return@launch }
                observation.launching()
                host.requireEnabled(kind)
                if (!RuntimePty.supportsSessionCleanup()) mutableStatus.update { it.copy(cleanupWarning = CLEANUP_WARNING) }
                var size = desiredSize.get()
                forkThread = Thread.currentThread()
                child = RuntimePty.start(command, root.toString(), environment, size.columns, size.rows)
                observation.processStarted()
                withContext(Dispatchers.Main) {
                    // The Android View may have measured while fork/exec was in flight. Apply that
                    // latest size to the slave BEFORE reading the first prompt into the emulator.
                    size = desiredSize.get()
                    child.resize(size.columns, size.rows)
                    mutableStatus.update { it.copy(phase = Phase.RUNNING, directory = root.toString()) }
                }
                val buffer = ByteArray(16384)
                var pending: ByteArray? = null
                var offset = 0
                var stopAt = 0L
                var exitAt = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    check(Thread.currentThread() === forkThread) { "Terminal lost its fork-owner thread" }
                    val now = System.nanoTime()
                    if (stopping.get() && stopAt == 0L) {
                        observation.cancelled()
                        mutableStatus.value = mutableStatus.value.copy(phase = Phase.STOPPING)
                        if (!child.signalSession(RuntimePty.SIGTERM)) mutableStatus.update { it.copy(cleanupWarning = CLEANUP_WARNING) }
                        stopAt = now
                        input.clear(); pending = null
                    }
                    if (stopAt != 0L && now - stopAt > 300_000_000L && !child.signalSession(RuntimePty.SIGKILL)) {
                        mutableStatus.update { it.copy(cleanupWarning = CLEANUP_WARNING) }
                    }
                    val nextSize = desiredSize.get()
                    if (nextSize != size) { child.resize(nextSize.columns, nextSize.rows); size = nextSize }
                    if (stopAt == 0L) {
                        if (pending == null) { pending = input.poll(); offset = 0 }
                        pending?.let { bytes ->
                            offset += child.write(bytes, offset, bytes.size - offset)
                            if (offset == bytes.size) pending = null
                        }
                    }
                    val count = child.read(buffer)
                    if (count > 0) {
                        // Backpressure instead of enqueuing unbounded UI Runnables.
                        withContext(Dispatchers.Main) {
                            emulator.append(buffer, count)
                            mutableFrame.value += 1
                        }
                    }
                    if (exit == null) {
                        child.peekExit().takeIf { it != RuntimePty.RUNNING }?.let {
                            exit = it; exitAt = now
                            observation.processExited(it)
                            // Shell can exit while a background process still holds the tty or sockets.
                            if (!child.signalSession(RuntimePty.SIGKILL)) mutableStatus.update { state -> state.copy(cleanupWarning = CLEANUP_WARNING) }
                        }
                    }
                    if (exit != null && (count < 0 || (count == 0 && now - exitAt > 200_000_000L) || now - exitAt > 1_000_000_000L)) break
                    if (stopAt != 0L && now - stopAt > 3_000_000_000L) error("Terminal did not stop after SIGKILL")
                    if (count < 0) delay(10)
                }
                mutableStatus.value = mutableStatus.value.copy(phase = Phase.FINISHED, exitCode = exit)
            } catch (failure: CancellationException) {
                observation.cancelled()
                throw failure
            } catch (failure: Throwable) {
                if (stopping.get()) observation.cancelled()
                // Final input/resize can race an ordinary shell exit. Prefer the real process
                // result, if available, over calling the resulting PTY error a session failure.
                if (exit == null) runCatching { child?.peekExit() }.getOrNull()?.takeIf { it != RuntimePty.RUNNING }?.let {
                    exit = it
                    observation.processExited(it)
                }
                observation.failed()
                mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED, error = failure.message ?: failure.javaClass.simpleName)
            } finally {
                try {
                    withContext(NonCancellable + worker) {
                        // Stop may race preparation, launch or final drain. A verdict already observed
                        // from the process is latched; later Stop/cleanup cannot turn it into a crash.
                        if (stopping.get()) observation.cancelled()
                        runCatching { child?.close() }.onFailure { failure ->
                            observation.failed()
                            mutableStatus.value = mutableStatus.value.copy(phase = Phase.FAILED, error = failure.message)
                        }
                        if (mutableStatus.value.phase in setOf(Phase.STARTING, Phase.STOPPING, Phase.RUNNING)) {
                            mutableStatus.value = mutableStatus.value.copy(phase = Phase.FINISHED, exitCode = exit)
                        }
                        input.clear()
                        observation.finish()
                    }
                } finally {
                    scope.cancel()
                    worker.close()
                }
            }
        }
    }

    /** Main thread only. IME and hardware keys send bytes immediately; Enter is CR, not a send-line box. */
    fun send(text: String) = offer(text.toByteArray(Charsets.UTF_8))
    private fun offer(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        if (stopping.get() || mutableStatus.value.phase != Phase.RUNNING) return false
        if (bytes.size > 64 * 1024 || !input.offer(bytes)) {
            mutableStatus.value = mutableStatus.value.copy(error = "Terminal input queue is full; wait before typing or paste less text.")
            return false
        }
        return true
    }
    fun paste(text: String): Boolean {
        if (text.toByteArray(Charsets.UTF_8).size > 16 * 1024) {
            mutableStatus.value = mutableStatus.value.copy(error = "Paste is limited to 16 KiB at a time.")
            return false
        }
        // Reserve one queue entry for the whole paste, including bracket delimiters. A full
        // queue must not accept the opening delimiter and drop the closing one.
        val buffer = java.io.ByteArrayOutputStream()
        pasteBuffer = buffer
        try { emulator.paste(text) } finally { pasteBuffer = null }
        return offer(buffer.toByteArray())
    }
    fun resize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        val size = Size(columns.coerceIn(2, 400), rows.coerceIn(2, 200))
        desiredSize.set(size)
        emulator.resize(size.columns, size.rows, cellWidth, cellHeight)
        mutableFrame.value += 1
    }
    /** Nonblocking and idempotent. Worker signals all session process groups, drains, closes and reaps. */
    fun stop() { stopping.set(true) }
}

/** Derive from the LIVE installation, not hard-coded runtime version paths or inherited app secrets. */
internal fun terminalEnvironment(pack: RuntimeInstallation, system: Map<String, String>): Map<String, String> = buildMap {
    putAll(system.filterKeys { it in SYSTEM_ENV })
    putAll(pack.environment)
    remove("NO_COLOR")
    remove("PIP_NO_INPUT")
    remove("CI")
    put("TERM", "xterm-256color")
    put("COLORTERM", "truecolor")
    put("PS1", "runtime $ ")
    put("TERMINFO", pack.prefix.resolve("share/terminfo").toString())
    pack.environment["npm_config_prefix"]?.let { global -> put("PATH", "$global/bin:${pack.environment.getValue("PATH")}") }
}

private const val CLEANUP_WARNING = "This device cannot safely target every job-control process group. Stop/Close terminates the shell group; separate background/foreground jobs may remain. Avoid background jobs and stop them from the shell before closing."
private val SYSTEM_ENV = setOf("ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT")
