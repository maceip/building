// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
package dev.ide.platform.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogSnortTest {

    @Test
    fun registersComponentsMintsGdpProofAndTracesSpansWithErrorCapture() {
        Log.ring.clear()
        Log.spanRing.clear()

        val comp = Log.registerComponent(
            id = "agent.settings.test",
            owner = "agent-ui",
            description = "Agent settings screen component",
        )
        assertTrue(Log.isComponentRegistered("agent.settings.test"))
        assertTrue(comp.proof.witness.contains("RegisteredComponent(agent-ui:agent.settings.test)"))

        val logger = comp.logger
        val result = logger.traceSpan("selectProvider", mapOf("provider" to "antigravity")) { span ->
            Log.recordStateMutation("agent.settings.test", "selectedProvider", "antigravity")
            logger.info("Provider switched to antigravity")
            span.setAttribute("model", "gemini-3.1-pro")
            "ok"
        }
        assertEquals("ok", result)

        // Verify the log record inside the span inherited the span's traceId and spanId
        val infoLog = Log.recent().lastOrNull { it.message == "Provider switched to antigravity" }
        assertNotNull(infoLog)
        assertNotNull(infoLog.traceId)
        assertNotNull(infoLog.spanId)
        assertEquals("agent-ui", infoLog.source)

        val completedSpan = Log.recentSpans().lastOrNull { it.name == "selectProvider" }
        assertNotNull(completedSpan)
        assertEquals(infoLog.traceId, completedSpan.traceId.hex)
        assertEquals("ok", completedSpan.status)
        assertEquals("gemini-3.1-pro", completedSpan.attributes["model"])
        assertEquals("antigravity", completedSpan.attributes["state.selectedProvider"])

        // Verify error handling captures exception into both Log and TraceSpan
        assertFailsWith<IllegalStateException> {
            logger.traceSpan("failingOperation") {
                throw IllegalStateException("boom")
            }
        }
        val errorSpan = Log.recentSpans().lastOrNull { it.name == "failingOperation" }
        assertNotNull(errorSpan)
        assertEquals("error", errorSpan.status)
        assertTrue(errorSpan.errorMessage!!.contains("boom"))

        // Verify catchAndLog recovers with fallback and logs ERROR
        val fallbackValue = logger.catchAndLog("recoverableOp", fallback = 42) {
            error("recoverable crash")
        }
        assertEquals(42, fallbackValue)

        // Verify inline lazy-lambda overloads + safeMessage guard + vendored KotlinLogging facade
        var evaluatedWhenDisabled = false
        val previousMin = Log.minLevel
        try {
            Log.minLevel = LogLevel.INFO
            logger.debug {
                evaluatedWhenDisabled = true
                "should not evaluate"
            }
            assertEquals(false, evaluatedWhenDisabled, "Lazy debug lambda must not evaluate when minLevel is INFO")

            // Throwing lambda must not crash caller
            logger.info {
                throw IllegalArgumentException("broken string interpolation")
            }
            val safeRecord = Log.recent().lastOrNull()
            assertNotNull(safeRecord)
            assertTrue(safeRecord.message.contains("Log message invocation failed: IllegalArgumentException"))

            // Vendored io.github.oshai.kotlinlogging.KotlinLogging routes through Log with OTel span context
            val klogger = io.github.oshai.kotlinlogging.KotlinLogging.logger("vendored.klogger")
            logger.traceSpan("kloggerSpan") {
                klogger.atInfo {
                    message = "Hello from vendored kotlin-logging"
                    payload = mapOf("vendored" to true, "stars" to 3113)
                }
            }
            val klogRecord = Log.recent().lastOrNull { it.tag == "vendored.klogger" }
            assertNotNull(klogRecord)
            assertEquals("Hello from vendored kotlin-logging", klogRecord.message)
            assertEquals("true", klogRecord.attributes["vendored"])
            assertNotNull(klogRecord.traceId, "Vendored KotlinLogging must inherit active OTel traceId")
        } finally {
            Log.minLevel = previousMin
        }
    }

    @Test
    fun shittyCodeDetectorCatchesNoOpUiActionStateThrashAndUnregisteredComponent() {
        Log.ring.clear()
        Log.spanRing.clear()

        // 1. Dead/No-Op UI action detector
        Log.traceUiAction(componentId = "agent.settings.test", controlLabel = "BrokenDownloadButton") {
            // Deliberately do nothing!
        }
        val noopWarn = Log.recent().lastOrNull { it.attributes["code_smell"] == "SHITTY_CODE_NOOP_ACTION" }
        assertNotNull(noopWarn, "Expected SHITTY_CODE_NOOP_ACTION warning for no-op UI tap")
        assertTrue(noopWarn.message.contains("BrokenDownloadButton"))

        // 2. State flip-flop / thrash detector (A -> B -> A within 1.5s)
        Log.recordStateMutation("agent.settings.test", "provider", "antigravity")
        Log.recordStateMutation("agent.settings.test", "provider", "anthropic")
        Log.recordStateMutation("agent.settings.test", "provider", "antigravity")
        val thrashWarn = Log.recent().lastOrNull { it.attributes["code_smell"] == "SHITTY_CODE_STATE_THRASH" }
        assertNotNull(thrashWarn, "Expected SHITTY_CODE_STATE_THRASH warning when provider flip-flops A -> B -> A")
        assertEquals("antigravity->anthropic->antigravity", thrashWarn.attributes["oscillation"])

        // 3. Unregistered component detector
        Log.warnOnUnregisteredComponents = true
        try {
            Log.logger("rogue.unregistered.widget").info("hello from unregistered code")
            val unregWarn = Log.recent().lastOrNull { it.attributes["code_smell"] == "SHITTY_CODE_UNREGISTERED_COMPONENT" }
            assertNotNull(unregWarn, "Expected SHITTY_CODE_UNREGISTERED_COMPONENT warning")
            assertEquals("rogue.unregistered.widget", unregWarn.attributes["unregistered_tag"])
        } finally {
            Log.warnOnUnregisteredComponents = false
        }
    }

    @Test
    fun snortLogSinkFormatsValidOtlpTracesAndLogsJson() {
        val sink = SnortLogSink(
            endpointBaseUrl = "http://127.0.0.1:19999",
            serviceName = "building-ide-test",
            hostName = "test-host",
            shipImmediately = false,
        )
        val record = LogRecord(
            level = LogLevel.ERROR,
            tag = "agent.settings",
            message = "Test error message",
            throwable = RuntimeException("sample cause"),
            timestampMs = 1_700_000_000_000L,
            threadName = "main",
            source = "agent-ui",
            traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
            spanId = "00f067aa0ba902b7",
            attributes = mapOf("key" to "val"),
        )
        val otlpLogs = sink.formatOtlpLogsPayload(listOf(record))
        assertTrue(otlpLogs.contains("\"resourceLogs\""))
        assertTrue(otlpLogs.contains("\"4bf92f3577b34da6a3ce929d0e0e4736\""))
        assertTrue(otlpLogs.contains("\"severityText\":\"ERROR\""))

        val span = TraceSpan(
            traceId = TraceId("4bf92f3577b34da6a3ce929d0e0e4736"),
            spanId = SpanId("00f067aa0ba902b7"),
            parentSpanId = null,
            name = "ui.action:SelectProvider",
            componentId = "agent.settings",
            source = "agent-ui",
            startTimeMs = 1_700_000_000_000L,
            endTimeMs = 1_700_000_000_025L,
            durationMs = 25L,
            status = "ok",
            attributes = mapOf("provider" to "openai"),
        )
        val otlpTraces = sink.formatOtlpTracesPayload(listOf(span))
        assertTrue(otlpTraces.contains("\"resourceSpans\""))
        assertTrue(otlpTraces.contains("\"ui.action:SelectProvider\""))
    }
}
