// Vendored & adapted from https://github.com/oshai/kotlin-logging (Apache-2.0)
// Backed directly by dev.ide.platform.log.Log so it stays 100% pure-KMP (zero SLF4J / cinterop
// dependencies) and routes every log + structured payload into Log's OpenTelemetry trace spans,
// RingBufferSink, and SnortLogSink.
package io.github.oshai.kotlinlogging

import dev.ide.platform.log.Log
import dev.ide.platform.log.LogLevel
import dev.ide.platform.log.Logger

public enum class Level {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    OFF;

    internal fun toPlatformLevel(): LogLevel? = when (this) {
        TRACE -> LogLevel.TRACE
        DEBUG -> LogLevel.DEBUG
        INFO -> LogLevel.INFO
        WARN -> LogLevel.WARN
        ERROR -> LogLevel.ERROR
        OFF -> null
    }
}

public interface Marker {
    public fun getName(): String
}

private data class SimpleMarker(private val markerName: String) : Marker {
    override fun getName(): String = markerName
    override fun toString(): String = markerName
}

public object KMarkerFactory {
    public fun getMarker(name: String): Marker = SimpleMarker(name)
}

public class KLoggingEventBuilder {
    public var message: String? = null
    public var cause: Throwable? = null
    public var payload: Map<String, Any?>? = null
    public var arguments: Array<Any?>? = null
}

public interface KLogger {
    public val name: String

    public fun isLoggingEnabledFor(level: Level, marker: Marker? = null): Boolean

    public fun isTraceEnabled(marker: Marker? = null): Boolean = isLoggingEnabledFor(Level.TRACE, marker)
    public fun isDebugEnabled(marker: Marker? = null): Boolean = isLoggingEnabledFor(Level.DEBUG, marker)
    public fun isInfoEnabled(marker: Marker? = null): Boolean = isLoggingEnabledFor(Level.INFO, marker)
    public fun isWarnEnabled(marker: Marker? = null): Boolean = isLoggingEnabledFor(Level.WARN, marker)
    public fun isErrorEnabled(marker: Marker? = null): Boolean = isLoggingEnabledFor(Level.ERROR, marker)
    public fun isLoggingOff(marker: Marker? = null): Boolean = !isLoggingEnabledFor(Level.ERROR, marker)

    public fun trace(message: () -> Any?): Unit = trace(null, null, message)
    public fun trace(throwable: Throwable?, message: () -> Any?): Unit = trace(null, throwable, message)
    public fun trace(marker: Marker?, message: () -> Any?): Unit = trace(marker, null, message)
    public fun trace(marker: Marker?, throwable: Throwable?, message: () -> Any?): Unit =
        at(Level.TRACE, marker) {
            this.message = Log.safeMessage(message)
            this.cause = throwable
        }

    public fun debug(message: () -> Any?): Unit = debug(null, null, message)
    public fun debug(throwable: Throwable?, message: () -> Any?): Unit = debug(null, throwable, message)
    public fun debug(marker: Marker?, message: () -> Any?): Unit = debug(marker, null, message)
    public fun debug(marker: Marker?, throwable: Throwable?, message: () -> Any?): Unit =
        at(Level.DEBUG, marker) {
            this.message = Log.safeMessage(message)
            this.cause = throwable
        }

    public fun info(message: () -> Any?): Unit = info(null, null, message)
    public fun info(throwable: Throwable?, message: () -> Any?): Unit = info(null, throwable, message)
    public fun info(marker: Marker?, message: () -> Any?): Unit = info(marker, null, message)
    public fun info(marker: Marker?, throwable: Throwable?, message: () -> Any?): Unit =
        at(Level.INFO, marker) {
            this.message = Log.safeMessage(message)
            this.cause = throwable
        }

    public fun warn(message: () -> Any?): Unit = warn(null, null, message)
    public fun warn(throwable: Throwable?, message: () -> Any?): Unit = warn(null, throwable, message)
    public fun warn(marker: Marker?, message: () -> Any?): Unit = warn(marker, null, message)
    public fun warn(marker: Marker?, throwable: Throwable?, message: () -> Any?): Unit =
        at(Level.WARN, marker) {
            this.message = Log.safeMessage(message)
            this.cause = throwable
        }

    public fun error(message: () -> Any?): Unit = error(null, null, message)
    public fun error(throwable: Throwable?, message: () -> Any?): Unit = error(null, throwable, message)
    public fun error(marker: Marker?, message: () -> Any?): Unit = error(marker, null, message)
    public fun error(marker: Marker?, throwable: Throwable?, message: () -> Any?): Unit =
        at(Level.ERROR, marker) {
            this.message = Log.safeMessage(message)
            this.cause = throwable
        }

    public fun atTrace(marker: Marker? = null, block: KLoggingEventBuilder.() -> Unit): Unit = at(Level.TRACE, marker, block)
    public fun atDebug(marker: Marker? = null, block: KLoggingEventBuilder.() -> Unit): Unit = at(Level.DEBUG, marker, block)
    public fun atInfo(marker: Marker? = null, block: KLoggingEventBuilder.() -> Unit): Unit = at(Level.INFO, marker, block)
    public fun atWarn(marker: Marker? = null, block: KLoggingEventBuilder.() -> Unit): Unit = at(Level.WARN, marker, block)
    public fun atError(marker: Marker? = null, block: KLoggingEventBuilder.() -> Unit): Unit = at(Level.ERROR, marker, block)

    public fun at(level: Level, marker: Marker? = null, block: KLoggingEventBuilder.() -> Unit)

    public fun entry(vararg arguments: Any?): Unit = trace { "entry(${arguments.joinToString()})" }
    public fun exit(): Unit = trace { "exit" }
    public fun <T> exit(result: T): T {
        trace { "exit($result)" }
        return result
    }

    public fun <T : Throwable> throwing(throwable: T): T {
        atError {
            cause = throwable
            message = "throwing($throwable)"
        }
        return throwable
    }

    public fun <T : Throwable> catching(throwable: T) {
        atError {
            cause = throwable
            message = "catching($throwable)"
        }
    }
}

internal class PlatformBackedKLogger(
    override val name: String,
    private val delegate: Logger = Log.logger(name),
) : KLogger {

    override fun isLoggingEnabledFor(level: Level, marker: Marker?): Boolean {
        val platformLevel = level.toPlatformLevel() ?: return false
        return delegate.isEnabled(platformLevel)
    }

    override fun at(level: Level, marker: Marker?, block: KLoggingEventBuilder.() -> Unit) {
        val platformLevel = level.toPlatformLevel() ?: return
        if (!delegate.isEnabled(platformLevel)) return
        val builder = KLoggingEventBuilder().apply(block)
        val attrs = buildMap {
            marker?.let { put("marker", it.getName()) }
            builder.payload?.forEach { (k, v) -> if (v != null) put(k, v.toString()) }
            builder.arguments?.takeIf { it.isNotEmpty() }?.let { put("arguments", it.joinToString()) }
        }
        Log.dispatch(
            level = platformLevel,
            tag = name,
            message = builder.message.orEmpty(),
            throwable = builder.cause,
            source = delegate.source,
            attributes = attrs,
        )
    }
}

public object KotlinLogging {
    public fun logger(name: String): KLogger = PlatformBackedKLogger(name.ifBlank { "KotlinLogging" })

    public fun logger(func: () -> Unit): KLogger = logger(resolveLoggerName(func))

    private fun resolveLoggerName(func: () -> Unit): String {
        val raw = func::class.qualifiedName ?: func::class.simpleName ?: "KotlinLogging"
        return raw
            .substringBefore("Kt$")
            .substringBefore("$")
            .substringBefore(".<anonymous>")
            .removeSuffix("Kt")
            .ifBlank { "KotlinLogging" }
    }
}
