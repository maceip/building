// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
package dev.ide.platform.log

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import kotlin.test.assertTrue

class SnortLiveServerTest {

    @Test
    fun shipsLogsAndSpansToLiveSnortServerEndToEnd() {
        val snortDir = File("/Users/mac/snort")
        assumeTrue(snortDir.isDirectory, "Skipping live snort server test: /Users/mac/snort not present")

        val port = ServerSocket(0).use { it.localPort }
        val dataDir = Files.createTempDirectory("snort-live-test-").toFile()
        val pythonBin = listOf("/opt/homebrew/bin/python3", "/usr/local/bin/python3", "python3")
            .firstOrNull { File(it).canExecute() } ?: "python3"

        // Boot the real snort server (shimming optional `lance`/`duckdb` batch sealers if the host
        // Python hasn't installed pylance yet, while exercising the real snort.server.SnortStoreManager,
        // snort.ingest.otlp.parse_otlp_logs/parse_otlp_traces, WalWriter, and LiveRuntime SQLite store).
        val bootScript = """
            import sys, types
            for mod in ('lance', 'duckdb', 'pyarrow'):
                try:
                    __import__(mod)
                except ImportError:
                    sys.modules[mod] = types.ModuleType(mod)
            from snort.server import run_server
            run_server(data_dir=${"'"}${dataDir.absolutePath}${"'"}, host='127.0.0.1', port=$port)
        """.trimIndent()

        val process = ProcessBuilder(pythonBin, "-c", bootScript)
            .directory(snortDir)
            .apply { environment()["PYTHONPATH"] = snortDir.absolutePath }
            .redirectErrorStream(true)
            .start()

        try {
            val baseUrl = "http://127.0.0.1:$port"
            waitForSnortReady(baseUrl, process)

            val sink = SnortLogSink(
                endpointBaseUrl = baseUrl,
                serviceName = "building-ide-e2e",
                hostName = "mac-studio",
                shipImmediately = true,
            )

            Log.addSink(sink)
            Log.addSpanSink(sink)
            try {
                val component = Log.registerComponent(
                    id = "agent.settings.live",
                    owner = "agent-ui",
                    description = "Live Snort E2E verification component",
                )
                component.logger.traceSpan("e2e.selectProvider", mapOf("provider" to "antigravity")) {
                    Log.recordStateMutation("agent.settings.live", "selectedProvider", "antigravity")
                    component.logger.info("Switched provider to antigravity in live snort test")
                }

                // Trigger a deliberate dead-UI-action warning so Snort ingests the shitty-code detector event too:
                Log.traceUiAction("agent.settings.live", "DeadMockButton") {
                    // no-op
                }
            } finally {
                Log.removeSpanSink(sink)
                Log.removeSink(sink)
            }

            // Query Snort's HTTP /api/logs endpoint to verify OTLP logs were Durably WAL-committed
            val logsJson = httpGet("$baseUrl/api/logs?limit=20")
            assertTrue(
                logsJson.contains("Switched provider to antigravity in live snort test") &&
                    logsJson.contains("SHITTY_CODE_NOOP_ACTION") &&
                    logsJson.contains("building-ide-e2e"),
                "Expected live Snort server to return ingested logs + shitty-code detector warning, got: $logsJson",
            )
            assertTrue(sink.shippedLogCount >= 2, "Expected at least 2 logs shipped to live Snort server")
            assertTrue(sink.shippedSpanCount >= 1, "Expected at least 1 trace span shipped to live Snort server")
        } finally {
            process.destroy()
            process.waitFor()
            dataDir.deleteRecursively()
        }
    }

    private fun waitForSnortReady(baseUrl: String, process: Process) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                val out = process.inputStream.bufferedReader().use { it.readText() }
                error("snort server exited early with code ${process.exitValue()}:\n$out")
            }
            try {
                val code = (URI.create("$baseUrl/api/logs?limit=1").toURL().openConnection() as HttpURLConnection).run {
                    connectTimeout = 500
                    readTimeout = 500
                    requestMethod = "GET"
                    responseCode
                }
                if (code in 200..299) return
            } catch (_: Throwable) {
                Thread.sleep(100)
            }
        }
        error("Timed out waiting for live snort server at $baseUrl")
    }

    private fun httpGet(url: String): String {
        val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        conn.requestMethod = "GET"
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}
