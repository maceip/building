// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform.log

import dev.ide.platform.Lock
import dev.ide.platform.ThreadLocalValue
import dev.ide.platform.epochMillis
import dev.ide.platform.proof.Fact
import dev.ide.platform.proof.Proof
import dev.ide.platform.proof.defineProof
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Severity of a [LogRecord], ordered TRACE < DEBUG < INFO < WARN < ERROR (compare by [ordinal]). */
enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }

/** Zero-allocation typed 128-bit (32-hex) OpenTelemetry trace identifier. */
@JvmInline
value class TraceId(val hex: String) {
    override fun toString(): String = hex

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun random(): TraceId = TraceId(Uuid.random().toHexString())
    }
}

/** Zero-allocation typed 64-bit (16-hex) OpenTelemetry span identifier. */
@JvmInline
value class SpanId(val hex: String) {
    override fun toString(): String = hex

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun random(): SpanId = SpanId(Uuid.random().toHexString().take(16))
    }
}

/** Zero-allocation typed identifier for a component registered with [Log.registerComponent]. */
@JvmInline
value class ComponentId(val value: String) {
    override fun toString(): String = value
}

/** Metadata and GDP registration proof for a component registered under [Log]. */
data class RegisteredComponent(
    val id: ComponentId,
    val owner: String,
    val description: String = "",
    val registeredAtMs: Long = epochMillis(),
    val proof: Proof<Fact.RegisteredComponent<Any?>>,
) {
    val logger: Logger get() = Log.logger(id.value, source = owner, registeredProof = proof)
}

/**
 * One distributed/in-process execution span recorded by [Logger.trace] or [Log.trace].
 */
data class TraceSpan(
    val traceId: TraceId,
    val spanId: SpanId,
    val parentSpanId: SpanId?,
    val name: String,
    val componentId: String,
    val source: String?,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val status: String,
    val errorMessage: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * One logged event. [throwable] is the cause when logging a caught exception. By convention an
 * `ERROR` with a non-null [throwable] is an **unexpected** failure — that's what surfaces the
 * IntelliJ-style critical-error dialog; routine/handled failures should log at `WARN`.
 *
 * [source] attributes the record to whoever emitted it: the id of the plugin/owner whose [Logger] produced it
 * (set by the plugin registrar or [Log.registerComponent]), or null for legacy logs.
 */
data class LogRecord(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable?,
    val timestampMs: Long,
    val threadName: String,
    val source: String? = null,
    val traceId: String? = null,
    val spanId: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

/** A destination for log records (console, an in-memory ring, `maceip/snort`, crash/analytics bridge, …). */
fun interface LogSink {
    fun log(record: LogRecord)
}

/** A destination for completed [TraceSpan]s (e.g., [SnortLogSink] or in-memory trace ring). */
fun interface SpanSink {
    fun onSpan(span: TraceSpan)
}

/**Mutable scope passed into a [Logger.trace] block so callers can attach attributes or record state mutations. */
class TraceScope internal constructor(
    val traceId: TraceId,
    val spanId: SpanId,
    val parentSpanId: SpanId?,
    val name: String,
    val componentId: String,
) {
    internal val attributes = LinkedHashMap<String, String>()
    internal var childWorkCount: Int = 0

    fun setAttribute(key: String, value: String) {
        attributes[key] = value
    }

    fun recordEffect(description: String = "effect") {
        childWorkCount++
        attributes["last_effect"] = description
    }
}

/**
 * A tag-bound entry point for logging, tracing, and structured error handling.
 * Obtain one via [Log.registerComponent] (preferred) or [Log.logger].
 */
class Logger internal constructor(
    val tag: String,
    val source: String? = null,
    val registeredProof: Proof<Fact.RegisteredComponent<Any?>>? = null,
) {
    fun isEnabled(level: LogLevel): Boolean = Log.isEnabled(level)

    fun trace(message: String, attributes: Map<String, String> = emptyMap()) =
        Log.dispatch(LogLevel.TRACE, tag, message, null, source, attributes)

    inline fun trace(attributes: Map<String, String> = emptyMap(), message: () -> Any?) {
        if (isEnabled(LogLevel.TRACE)) trace(Log.safeMessage(message), attributes)
    }

    fun debug(message: String, attributes: Map<String, String> = emptyMap()) =
        Log.dispatch(LogLevel.DEBUG, tag, message, null, source, attributes)

    inline fun debug(attributes: Map<String, String> = emptyMap(), message: () -> Any?) {
        if (isEnabled(LogLevel.DEBUG)) debug(Log.safeMessage(message), attributes)
    }

    fun info(message: String, attributes: Map<String, String> = emptyMap()) =
        Log.dispatch(LogLevel.INFO, tag, message, null, source, attributes)

    inline fun info(attributes: Map<String, String> = emptyMap(), message: () -> Any?) {
        if (isEnabled(LogLevel.INFO)) info(Log.safeMessage(message), attributes)
    }

    fun warn(message: String, throwable: Throwable? = null, attributes: Map<String, String> = emptyMap()) =
        Log.dispatch(LogLevel.WARN, tag, message, throwable, source, attributes)

    inline fun warn(throwable: Throwable? = null, attributes: Map<String, String> = emptyMap(), message: () -> Any?) {
        if (isEnabled(LogLevel.WARN)) warn(Log.safeMessage(message), throwable, attributes)
    }

    /** An unexpected failure. With a [throwable] this also surfaces the host's critical-error dialog. */
    fun error(message: String, throwable: Throwable? = null, attributes: Map<String, String> = emptyMap()) =
        Log.dispatch(LogLevel.ERROR, tag, message, throwable, source, attributes)

    inline fun error(throwable: Throwable? = null, attributes: Map<String, String> = emptyMap(), message: () -> Any?) {
        if (isEnabled(LogLevel.ERROR)) error(Log.safeMessage(message), throwable, attributes)
    }

    /**
     * Executes [block] inside an instrumented [TraceSpan], automatically propagating `traceId`/`spanId`
     * to nested logs and child spans, measuring wall-clock duration, and logging + tagging any uncaught
     * exception before rethrowing it.
     */
    fun <R> traceSpan(
        operation: String,
        initialAttributes: Map<String, String> = emptyMap(),
        block: (TraceScope) -> R,
    ): R = Log.executeSpan(
        componentId = tag,
        source = source,
        operation = operation,
        initialAttributes = initialAttributes,
        block = block,
    )

    /**
     * Executes [block] inside an instrumented span and catches any exception, logging it as an `ERROR`
     * with full trace context and returning [fallback] instead of crashing the caller.
     */
    fun <R> catchAndLog(
        operation: String,
        fallback: R,
        initialAttributes: Map<String, String> = emptyMap(),
        block: (TraceScope) -> R,
    ): R = try {
        traceSpan(operation, initialAttributes, block)
    } catch (_: Throwable) {
        fallback
    }
}

/**
 * The process-wide logging, component-registration, distributed-tracing, and code-quality hub.
 */
object Log {
    private val lock = Lock()
    private var sinks: List<LogSink> = emptyList()
    private var spanSinks: List<SpanSink> = emptyList()
    private val registeredComponents = LinkedHashMap<String, RegisteredComponent>()
    private val unregisteredAlertedTags = HashSet<String>()
    private val componentProver = defineProof<Fact.RegisteredComponent<Any?>>("Fact.RegisteredComponent")

    // State-thrash detector: (componentId:key) -> list of (timestampMs, valueString)
    private val stateMutationHistory = LinkedHashMap<String, ArrayDeque<Pair<Long, String>>>()

    // Active span stack per thread for automatic traceId / parentSpanId propagation
    private val activeSpanStack = ThreadLocalValue { ArrayDeque<TraceScope>() }

    /** The default in-memory ring — recent records for the in-app Logs viewer / attaching to a report. */
    val ring = RingBufferSink(capacity = 1000)

    /** Recent completed trace spans kept in memory for inspection and tests. */
    val spanRing = SpanRingBufferSink(capacity = 500)

    /** Records below this level are dropped before reaching any sink. */
    var minLevel: LogLevel = LogLevel.DEBUG

    /** Returns true when [level] is at or above [minLevel]. */
    fun isEnabled(level: LogLevel): Boolean = level.ordinal >= minLevel.ordinal

    /** Evaluates a lazy log message lambda safely so a throwing diagnostic expression never crashes the caller. */
    @PublishedApi
    internal inline fun safeMessage(producer: () -> Any?): String = try {
        producer().toString()
    } catch (e: Throwable) {
        "Log message invocation failed: ${e::class.simpleName}: ${e.message}"
    }

    /**
     * When true, any log or span emitted by a component tag not registered via [registerComponent]
     * emits a `SHITTY_CODE_UNREGISTERED_COMPONENT` warning record.
     */
    var warnOnUnregisteredComponents: Boolean = false

    /** Window (ms) within which an `A -> B -> A` state oscillation is flagged as `SHITTY_CODE_STATE_THRASH`. */
    var stateThrashWindowMs: Long = 1_500L

    /** Threshold (ms) above which a main/UI thread span is flagged as `SHITTY_CODE_SLOW_MAIN_THREAD`. */
    var slowMainThreadThresholdMs: Long = 100L

    init {
        defaultLogSink()?.let { sinks = sinks + it }
        sinks = sinks + ring
        spanSinks = spanSinks + spanRing
        // Pre-register core platform logger tags
        registerComponent("Log.CodeQuality", owner = "model-api", description = "Built-in code quality & dead-UI detector")
        registerComponent("Permix", owner = "model-api", description = "Permix KMP permission policy engine")
    }

    /**
     * Registers a software component with the logging & tracing hub and mints an unforgeable
     * GDP [Proof]<[Fact.RegisteredComponent]>.
     */
    fun registerComponent(
        id: String,
        owner: String,
        description: String = "",
    ): RegisteredComponent {
        require(id.isNotBlank()) { "Component id must not be blank" }
        require(owner.isNotBlank()) { "Component owner must not be blank" }
        return lock.withLock {
            registeredComponents.getOrPut(id) {
                RegisteredComponent(
                    id = ComponentId(id),
                    owner = owner,
                    description = description,
                    registeredAtMs = epochMillis(),
                    proof = componentProver.mint("RegisteredComponent($owner:$id)"),
                )
            }
        }
    }

    /** Returns a snapshot of all components registered via [registerComponent]. */
    fun registeredComponents(): Map<String, RegisteredComponent> =
        lock.withLock { LinkedHashMap(registeredComponents) }

    fun isComponentRegistered(id: String): Boolean =
        lock.withLock { id in registeredComponents }

    fun logger(tag: String): Logger {
        val reg = lock.withLock { registeredComponents[tag] }
        return Logger(tag, reg?.owner, reg?.proof)
    }

    /** A logger attributed to [source] (a plugin id) — its records carry it so the Logs viewer can filter
     *  by plugin. The platform sets [source] when a plugin mints its logger; callers cannot forge it. */
    fun logger(
        tag: String,
        source: String?,
        registeredProof: Proof<Fact.RegisteredComponent<Any?>>? = null,
    ): Logger = Logger(tag, source, registeredProof)

    fun addSink(sink: LogSink) {
        lock.withLock { sinks = sinks + sink }
    }

    fun removeSink(sink: LogSink) {
        lock.withLock { sinks = sinks - sink }
    }

    fun addSpanSink(sink: SpanSink) {
        lock.withLock { spanSinks = spanSinks + sink }
    }

    fun removeSpanSink(sink: SpanSink) {
        lock.withLock { spanSinks = spanSinks - sink }
    }

    /**
     * Attaches a [SnortLogSink] for both OpenTelemetry logs (`/v1/logs`) and trace spans (`/v1/traces`)
     * targeting a running `maceip/snort` server, returning the sink handle.
     */
    fun connectSnort(
        endpointBaseUrl: String = "http://127.0.0.1:8788",
        serviceName: String = "building-ide",
        hostName: String = "local-device",
        walFilePath: String? = null,
        shipImmediately: Boolean = true,
    ): SnortLogSink {
        val sink = SnortLogSink(
            endpointBaseUrl = endpointBaseUrl,
            serviceName = serviceName,
            hostName = hostName,
            walFilePath = walFilePath,
            shipImmediately = shipImmediately,
        )
        addSink(sink)
        addSpanSink(sink)
        return sink
    }

    /** Detaches a previously connected [SnortLogSink]. */
    fun disconnectSnort(sink: SnortLogSink) {
        removeSpanSink(sink)
        removeSink(sink)
    }

    /** A snapshot of the most recent records (oldest first), for diagnostics or a report attachment. */
    fun recent(): List<LogRecord> = ring.snapshot()

    /** A snapshot of the most recent completed [TraceSpan]s (oldest first). */
    fun recentSpans(): List<TraceSpan> = spanRing.snapshot()

    /**
     * Records a state mutation for [componentId].[stateKey] = [newValue].
     *
     * Automatically credits the currently active [TraceScope] (so [traceUiAction] knows the user action
     * had a real effect) AND checks for rapid `A -> B -> A` flip-flop oscillations within [stateThrashWindowMs],
     * emitting a `SHITTY_CODE_STATE_THRASH` warning if detected.
     */
    fun recordStateMutation(componentId: String, stateKey: String, newValue: Any?) {
        val now = epochMillis()
        val valStr = newValue.toString()
        val stack = activeSpanStack.get()
        stack.lastOrNull()?.let { active ->
            active.childWorkCount++
            active.attributes["state.$stateKey"] = valStr
        }

        val compositeKey = "$componentId:$stateKey"
        var thrashDetected: Triple<String, String, String>? = null
        lock.withLock {
            val history = stateMutationHistory.getOrPut(compositeKey) { ArrayDeque(8) }
            while (history.isNotEmpty() && now - history.first().first > stateThrashWindowMs) {
                history.removeFirst()
            }
            if (history.size >= 2) {
                val prev1 = history[history.size - 1].second
                val prev2 = history[history.size - 2].second
                if (prev1 != valStr && prev2 == valStr) {
                    thrashDetected = Triple(prev2, prev1, valStr)
                }
            }
            history.addLast(now to valStr)
        }

        thrashDetected?.let { (a, b, c) ->
            dispatch(
                level = LogLevel.WARN,
                tag = "Log.CodeQuality",
                message = "SHITTY_CODE_STATE_THRASH: '$componentId.$stateKey' flip-flopped ($a -> $b -> $c) within ${stateThrashWindowMs}ms",
                throwable = null,
                source = componentId,
                attributes = mapOf(
                    "code_smell" to "SHITTY_CODE_STATE_THRASH",
                    "component" to componentId,
                    "state_key" to stateKey,
                    "oscillation" to "$a->$b->$c",
                ),
            )
        }
    }

    /**
     * Wraps a UI user interaction (button tap, toggle, chip click) in a traced span and verifies
     * that the handler actually performed work (mutated state, emitted a child span/log, or called
     * `scope.recordEffect()`).
     *
     * If the tap completes with **zero** effects, automatically logs `SHITTY_CODE_NOOP_ACTION`.
     */
    fun <R> traceUiAction(
        componentId: String,
        controlLabel: String,
        block: (TraceScope) -> R,
    ): R = executeSpan(
        componentId = componentId,
        source = componentId,
        operation = "ui.action:$controlLabel",
        initialAttributes = mapOf("ui.control" to controlLabel),
        requireEffect = true,
        block = block,
    )

    internal fun <R> executeSpan(
        componentId: String,
        source: String?,
        operation: String,
        initialAttributes: Map<String, String> = emptyMap(),
        requireEffect: Boolean = false,
        block: (TraceScope) -> R,
    ): R {
        checkRegistrationIfEnabled(componentId)
        val stack = activeSpanStack.get()
        val parent = stack.lastOrNull()
        parent?.let { it.childWorkCount++ }

        val traceId = parent?.traceId ?: TraceId.random()
        val spanId = SpanId.random()
        val scope = TraceScope(
            traceId = traceId,
            spanId = spanId,
            parentSpanId = parent?.spanId,
            name = operation,
            componentId = componentId,
        )
        scope.attributes.putAll(initialAttributes)

        stack.addLast(scope)
        val startMs = epochMillis()
        var status = "ok"
        var errorMsg: String? = null
        try {
            val result = block(scope)
            if (requireEffect && scope.childWorkCount == 0) {
                dispatch(
                    level = LogLevel.WARN,
                    tag = "Log.CodeQuality",
                    message = "SHITTY_CODE_NOOP_ACTION: UI control '${initialAttributes["ui.control"] ?: operation}' in '$componentId' executed with zero state mutations or side-effects",
                    throwable = null,
                    source = componentId,
                    attributes = mapOf(
                        "code_smell" to "SHITTY_CODE_NOOP_ACTION",
                        "component" to componentId,
                        "operation" to operation,
                    ),
                )
            }
            return result
        } catch (t: Throwable) {
            status = "error"
            errorMsg = "${t::class.simpleName}: ${t.message}"
            dispatch(
                level = LogLevel.ERROR,
                tag = componentId,
                message = "Span '$operation' failed: $errorMsg",
                throwable = t,
                source = source,
                attributes = scope.attributes + ("span.name" to operation),
            )
            throw t
        } finally {
            val endMs = epochMillis()
            val durationMs = (endMs - startMs).coerceAtLeast(0L)
            if (stack.isNotEmpty()) stack.removeLast()

            val thread = currentThreadName()
            if ((thread.contains("main", ignoreCase = true) || thread.contains("AWT-EventQueue", ignoreCase = true)) &&
                durationMs > slowMainThreadThresholdMs
            ) {
                dispatch(
                    level = LogLevel.WARN,
                    tag = "Log.CodeQuality",
                    message = "SHITTY_CODE_SLOW_MAIN_THREAD: '$componentId:$operation' blocked UI thread for ${durationMs}ms",
                    throwable = null,
                    source = componentId,
                    attributes = mapOf(
                        "code_smell" to "SHITTY_CODE_SLOW_MAIN_THREAD",
                        "component" to componentId,
                        "operation" to operation,
                        "duration_ms" to durationMs.toString(),
                    ),
                )
            }

            val span = TraceSpan(
                traceId = traceId,
                spanId = spanId,
                parentSpanId = scope.parentSpanId,
                name = operation,
                componentId = componentId,
                source = source,
                startTimeMs = startMs,
                endTimeMs = endMs,
                durationMs = durationMs,
                status = status,
                errorMessage = errorMsg,
                attributes = scope.attributes.toMap(),
            )
            for (sink in spanSinks) runCatching { sink.onSpan(span) }
        }
    }

    private fun checkRegistrationIfEnabled(tag: String) {
        if (!warnOnUnregisteredComponents || tag == "Log.CodeQuality") return
        val shouldWarn = lock.withLock {
            if (tag !in registeredComponents && unregisteredAlertedTags.add(tag)) true else false
        }
        if (shouldWarn) {
            dispatch(
                level = LogLevel.WARN,
                tag = "Log.CodeQuality",
                message = "SHITTY_CODE_UNREGISTERED_COMPONENT: Unregistered component '$tag' emitted telemetry without calling Log.registerComponent()",
                throwable = null,
                source = tag,
                attributes = mapOf(
                    "code_smell" to "SHITTY_CODE_UNREGISTERED_COMPONENT",
                    "unregistered_tag" to tag,
                ),
            )
        }
    }

    internal fun dispatch(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
        source: String? = null,
        attributes: Map<String, String> = emptyMap(),
    ) {
        if (level.ordinal < minLevel.ordinal) return
        checkRegistrationIfEnabled(tag)
        val stack = activeSpanStack.get()
        val currentSpan = stack.lastOrNull()
        if (tag != "Log.CodeQuality") {
            currentSpan?.let { it.childWorkCount++ }
        }
        val record = LogRecord(
            level = level,
            tag = tag,
            message = message,
            throwable = throwable,
            timestampMs = epochMillis(),
            threadName = currentThreadName(),
            source = source,
            traceId = currentSpan?.traceId?.hex,
            spanId = currentSpan?.spanId?.hex,
            attributes = attributes,
        )
        // Read once: the list is replaced, never mutated, so a dispatch cannot see a half-updated set of
        // sinks and does not have to hold the lock while a sink runs.
        for (sink in sinks) runCatching { sink.log(record) }
    }
}

/** Keeps the last [capacity] records in memory (drops the oldest). Thread-safe. */
class RingBufferSink(private val capacity: Int) : LogSink {
    private val lock = Lock()
    private val buffer = ArrayDeque<LogRecord>(capacity)

    override fun log(record: LogRecord) {
        lock.withLock {
            buffer.addLast(record)
            while (buffer.size > capacity) buffer.removeFirst()
        }
    }

    fun snapshot(): List<LogRecord> = lock.withLock { buffer.toList() }

    fun clear() = lock.withLock { buffer.clear() }
}

/** Keeps the last [capacity] [TraceSpan]s in memory (drops the oldest). Thread-safe. */
class SpanRingBufferSink(private val capacity: Int) : SpanSink {
    private val lock = Lock()
    private val buffer = ArrayDeque<TraceSpan>(capacity)

    override fun onSpan(span: TraceSpan) {
        lock.withLock {
            buffer.addLast(span)
            while (buffer.size > capacity) buffer.removeFirst()
        }
    }

    fun snapshot(): List<TraceSpan> = lock.withLock { buffer.toList() }

    fun clear() = lock.withLock { buffer.clear() }
}

/** The running thread's name, for attributing a record to the work that produced it. */
internal expect fun currentThreadName(): String

/** The console sink this platform starts with, or null where there is nothing sensible to print to. */
internal expect fun defaultLogSink(): LogSink?
