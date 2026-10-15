package dev.ide.android.workbench.terminal

import java.nio.file.Path

/** The owned interactive process, NOT a command inferred from its input, title or output. */
enum class TerminalProgramKind { SHELL, NODE }

enum class TerminalOutcomeClassification {
    PREPARE_FAILURE,
    LAUNCH_FAILURE,
    /** PTY/session infrastructure failed without an already observed process exit. */
    SESSION_FAILURE,
    /** A nonzero shell/REPL exit is an ordinary, nonfatal outcome, not evidence of a crash. */
    NONZERO_EXIT,
    SUCCESS,
    USER_CANCELLED,
}

/**
 * Host-private routing context, never a persisted event. [workingDirectory] is exactly the user's
 * selected directory (null means runtime home), not a directory scraped from the terminal. The host
 * must not serialize this context or add its path to default SDK payloads. Deliberately not a data
 * class: its generated toString/copy/component methods must not expose the directory accidentally.
 *
 * [sessionId] belongs to one RuntimeTerminalSession.start attempt; preparation/launch failure may
 * finish that attempt without creating a process. [startedAtEpochMs] is the start-attempt wall time,
 * including preparation, so failed launches have timing too. onStart is only sent for a real child.
 */
class TerminalSessionContext(
    val sessionId: String,
    val programKind: TerminalProgramKind,
    val workingDirectory: Path?,
    val startedAtEpochMs: Long,
)

/** Trusted installation metadata supplied by the runtime host, never inferred from terminal bytes. */
data class TerminalRuntimeIdentity(val packId: String, val abi: String)

/** Fixed allowlist only. No path, transcript, title, typed commands, arguments or environment. */
data class TerminalSessionStart(
    val sessionId: String,
    val programKind: TerminalProgramKind,
    val startedAtEpochMs: Long,
)

/**
 * Outcome of the owned shell/Node REPL process, never of an arbitrary interactive command. A command
 * may fail (or be interrupted by Ctrl-C) while its shell/REPL remains healthy. No output parsing or
 * keystroke interception is used to infer those commands' results, exceptions or crash attribution.
 *
 * [durationMs] uses a monotonic clock from the start attempt to observed exit/failure (or completed
 * cancellation cleanup), clamped to 0..7 days for the host payload contract. [exitCode] is null when
 * no exit was observed; it is never synthesized from
 * an exception. User Stop/Close/coroutine cancellation is USER_CANCELLED, not a capture-worthy crash.
 * A host decides consent, persistence and which classifications merit capture; this API does no I/O.
 */
data class TerminalOutcome(
    val sessionId: String,
    val programKind: TerminalProgramKind,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val exitCode: Int?,
    val classification: TerminalOutcomeClassification,
)

/**
 * Called serially on the dedicated PTY worker, never on Main. The factory and onRuntimePrepared run
 * before fork; onTerminal runs after PTY close. Bounded host-private snapshot/journal I/O may run at
 * those boundaries, but never during onStart: the child is then live, so onStart must return promptly.
 * This observer adds no delivery queue; the host owns consent, bounded persistence and SDK delivery.
 * No callback is retried, and factory/callback exceptions are ignored without logging their messages.
 */
interface TerminalOutcomeObserver {
    /** Once after successful runtime preparation, before process launch; absent if preparation fails. */
    fun onRuntimePrepared(identity: TerminalRuntimeIdentity) = Unit

    /** Exactly once after the owned process is created; absent for failed/cancelled preparation. */
    fun onStart(start: TerminalSessionStart) = Unit

    /** Exactly once per start attempt, after PTY cleanup, including failed launch or cancellation. */
    fun onTerminal(outcome: TerminalOutcome)

    companion object {
        val NONE: TerminalOutcomeObserver = object : TerminalOutcomeObserver {
            override fun onTerminal(outcome: TerminalOutcome) = Unit
        }
    }
}

/** Optional host injection, evaluated once per start attempt before preparation; no global sink. */
fun interface TerminalOutcomeObserverFactory {
    fun create(context: TerminalSessionContext): TerminalOutcomeObserver

    companion object {
        val NONE = TerminalOutcomeObserverFactory { TerminalOutcomeObserver.NONE }
    }
}

/**
 * Constant-space delivery state. Production calls are worker-confined; synchronization also makes
 * duplicate/reentrant calls harmless. A terminal verdict is latched before invoking external code.
 * This retains neither the private context nor any Throwable/terminal bytes, and adds no work queue.
 */
internal class TerminalOutcomeDelivery(
    context: TerminalSessionContext,
    factory: TerminalOutcomeObserverFactory,
    private val startedAtNanos: Long,
) {
    private val start = TerminalSessionStart(context.sessionId, context.programKind, context.startedAtEpochMs)
    private var observer = runCatching { factory.create(context) }.getOrDefault(TerminalOutcomeObserver.NONE)
    private var prepared = false
    private var started = false
    private var finished = false
    private var failureStage = TerminalOutcomeClassification.PREPARE_FAILURE
    private var classification: TerminalOutcomeClassification? = null
    private var exitCode: Int? = null
    private var endedAtNanos: Long? = null

    @Synchronized fun runtimePrepared(identity: TerminalRuntimeIdentity) {
        if (prepared || started || finished) return
        prepared = true
        runCatching { observer.onRuntimePrepared(identity) }
    }

    @Synchronized fun launching() {
        if (!finished && !started) failureStage = TerminalOutcomeClassification.LAUNCH_FAILURE
    }

    @Synchronized fun processStarted() {
        if (started || finished) return
        started = true
        failureStage = TerminalOutcomeClassification.SESSION_FAILURE
        runCatching { observer.onStart(start) }
    }

    @Synchronized fun cancelled() {
        if (!finished && classification == null) classification = TerminalOutcomeClassification.USER_CANCELLED
    }

    @Synchronized fun processExited(code: Int) {
        if (finished || exitCode != null) return
        exitCode = code
        if (classification == null) classification = if (code == 0) {
            TerminalOutcomeClassification.SUCCESS
        } else {
            TerminalOutcomeClassification.NONZERO_EXIT
        }
        if (endedAtNanos == null) endedAtNanos = System.nanoTime()
    }

    @Synchronized fun failed() {
        if (finished) return
        if (classification == null) classification = failureStage
        if (endedAtNanos == null) endedAtNanos = System.nanoTime()
    }

    @Synchronized fun finish() {
        if (finished) return
        finished = true
        val outcome = TerminalOutcome(
            sessionId = start.sessionId,
            programKind = start.programKind,
            startedAtEpochMs = start.startedAtEpochMs,
            durationMs = (((endedAtNanos ?: System.nanoTime()) - startedAtNanos) / 1_000_000L).coerceIn(0L, MAX_DURATION_MS),
            exitCode = exitCode,
            classification = classification ?: failureStage,
        )
        val target = observer
        observer = TerminalOutcomeObserver.NONE
        runCatching { target.onTerminal(outcome) }
    }
}

private const val MAX_DURATION_MS = 7L * 24 * 60 * 60 * 1000
