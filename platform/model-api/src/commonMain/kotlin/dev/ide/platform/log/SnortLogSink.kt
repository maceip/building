// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
package dev.ide.platform.log

import dev.ide.platform.Lock

/**
 * Bare-bones, zero-dependency log and trace sink for **`maceip/snort`** (`/Users/mac/snort`).
 *
 * Formats [LogRecord]s and [TraceSpan]s into both:
 * 1. Standard OpenTelemetry HTTP JSON payloads (`/v1/logs` and `/v1/traces`) compatible with
 *    `snort.ingest.otlp`, AND
 * 2. Raw JSONL events (`/ingest`) with optional local blob/WAL file persistence so telemetry is
 *    never lost when offline.
 */
class SnortLogSink(
    val endpointBaseUrl: String = "http://127.0.0.1:8788",
    val serviceName: String = "building-ide",
    val hostName: String = "local-device",
    val walFilePath: String? = null,
    val shipImmediately: Boolean = true,
) : LogSink, SpanSink {

    private val lock = Lock()
    private val pendingLogs = ArrayList<LogRecord>()
    private val pendingSpans = ArrayList<TraceSpan>()

    var shippedLogCount: Int = 0
        private set
    var shippedSpanCount: Int = 0
        private set

    override fun log(record: LogRecord) {
        val jsonLine = formatIngestEventFromLog(record)
        walFilePath?.let { appendLineToFile(it, jsonLine) }
        if (shipImmediately) {
            val otlpPayload = formatOtlpLogsPayload(listOf(record))
            if (postJsonToSnort("${endpointBaseUrl.trimEnd('/')}/v1/logs", otlpPayload)) {
                lock.withLock { shippedLogCount++ }
            }
        } else {
            lock.withLock { pendingLogs += record }
        }
    }

    override fun onSpan(span: TraceSpan) {
        val jsonLine = formatIngestEventFromSpan(span)
        walFilePath?.let { appendLineToFile(it, jsonLine) }
        if (shipImmediately) {
            val otlpPayload = formatOtlpTracesPayload(listOf(span))
            if (postJsonToSnort("${endpointBaseUrl.trimEnd('/')}/v1/traces", otlpPayload)) {
                lock.withLock { shippedSpanCount++ }
            }
        } else {
            lock.withLock { pendingSpans += span }
        }
    }

    /** Flushes buffered logs and spans to the configured `maceip/snort` server. */
    fun flush(): Boolean {
        val (logsToShip, spansToShip) = lock.withLock {
            val l = pendingLogs.toList()
            val s = pendingSpans.toList()
            pendingLogs.clear()
            pendingSpans.clear()
            l to s
        }
        var ok = true
        if (logsToShip.isNotEmpty()) {
            val payload = formatOtlpLogsPayload(logsToShip)
            if (postJsonToSnort("${endpointBaseUrl.trimEnd('/')}/v1/logs", payload)) {
                lock.withLock { shippedLogCount += logsToShip.size }
            } else {
                ok = false
            }
        }
        if (spansToShip.isNotEmpty()) {
            val payload = formatOtlpTracesPayload(spansToShip)
            if (postJsonToSnort("${endpointBaseUrl.trimEnd('/')}/v1/traces", payload)) {
                lock.withLock { shippedSpanCount += spansToShip.size }
            } else {
                ok = false
            }
        }
        return ok
    }

    fun formatOtlpLogsPayload(records: List<LogRecord>): String {
        val logItems = records.joinToString(",") { r ->
            val attrs = buildMap {
                putAll(r.attributes)
                put("thread.name", r.threadName)
                put("logger.tag", r.tag)
                r.source?.let { put("component.owner", it) }
                r.throwable?.let {
                    put("exception.type", it::class.simpleName ?: "Throwable")
                    put("exception.message", it.message ?: "")
                    put("exception.stacktrace", it.stackTraceToString().take(1500))
                }
            }
            val traceField = if (!r.traceId.isNullOrBlank()) "\"traceId\":\"${escapeJson(r.traceId)}\"," else ""
            val spanField = if (!r.spanId.isNullOrBlank()) "\"spanId\":\"${escapeJson(r.spanId)}\"," else ""
            "{" +
                traceField +
                spanField +
                "\"timeUnixNano\":\"${r.timestampMs * 1_000_000L}\"," +
                "\"severityText\":\"${r.level.name}\"," +
                "\"body\":{\"stringValue\":\"${escapeJson(r.message)}\"}," +
                "\"attributes\":${formatOtlpAttributes(attrs)}" +
                "}"
        }
        return """{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"${escapeJson(serviceName)}"}},{"key":"host.name","value":{"stringValue":"${escapeJson(hostName)}"}}]},"scopeLogs":[{"scope":{"name":"dev.ide.platform.log"},"logRecords":[$logItems]}]}]}"""
    }

    fun formatOtlpTracesPayload(spans: List<TraceSpan>): String {
        val spanItems = spans.joinToString(",") { s ->
            val attrs = buildMap {
                putAll(s.attributes)
                put("component.id", s.componentId)
                s.source?.let { put("component.owner", it) }
            }
            val parentField = if (s.parentSpanId != null) "\"parentSpanId\":\"${escapeJson(s.parentSpanId.hex)}\"," else ""
            val statusCode = if (s.status == "error") 2 else 1
            val statusMsg = s.errorMessage?.let { ",\"message\":\"${escapeJson(it)}\"" }.orEmpty()
            "{" +
                "\"traceId\":\"${escapeJson(s.traceId.hex)}\"," +
                "\"spanId\":\"${escapeJson(s.spanId.hex)}\"," +
                parentField +
                "\"name\":\"${escapeJson(s.name)}\"," +
                "\"kind\":1," +
                "\"startTimeUnixNano\":\"${s.startTimeMs * 1_000_000L}\"," +
                "\"endTimeUnixNano\":\"${s.endTimeMs * 1_000_000L}\"," +
                "\"status\":{\"code\":$statusCode$statusMsg}," +
                "\"attributes\":${formatOtlpAttributes(attrs)}" +
                "}"
        }
        return """{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"${escapeJson(serviceName)}"}},{"key":"host.name","value":{"stringValue":"${escapeJson(hostName)}"}}]},"scopeSpans":[{"scope":{"name":"dev.ide.platform.trace"},"spans":[$spanItems]}]}]}"""
    }

    fun formatIngestEventFromLog(r: LogRecord): String {
        val sessionField = r.traceId?.let { "\"session_id\":\"${escapeJson(it)}\"," }.orEmpty()
        return """{"tsMs":${r.timestampMs},"host":"${escapeJson(hostName)}","source_id":"${escapeJson(serviceName)}","action":"log.${r.level.name.lowercase()}","subject":"${escapeJson(r.tag)}","object":"${escapeJson(r.source ?: "core")}",$sessionField"raw":"[${r.level.name}] ${escapeJson(r.tag)}: ${escapeJson(r.message)}"}"""
    }

    fun formatIngestEventFromSpan(s: TraceSpan): String {
        return """{"tsMs":${s.startTimeMs},"host":"${escapeJson(hostName)}","source_id":"${escapeJson(serviceName)}","action":"${escapeJson(s.name)}","subject":"${escapeJson(s.componentId)}","object":"${escapeJson(s.status)}","session_id":"${escapeJson(s.traceId.hex)}","session_root":"${escapeJson((s.parentSpanId ?: s.spanId).hex)}","duration_ms":${s.durationMs},"raw":"[${s.status.uppercase()}] ${escapeJson(s.componentId)}:${escapeJson(s.name)} (${s.durationMs}ms)"}"""
    }

    private fun formatOtlpAttributes(attrs: Map<String, String>): String =
        attrs.entries.joinToString(",", prefix = "[", postfix = "]") { (k, v) ->
            """{"key":"${escapeJson(k)}","value":{"stringValue":"${escapeJson(v)}"}}"""
        }

    private fun escapeJson(value: String): String = buildString(value.length + 8) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append(' ') else append(ch)
            }
        }
    }
}

internal expect fun postJsonToSnort(url: String, jsonPayload: String): Boolean

internal expect fun appendLineToFile(path: String, line: String)
