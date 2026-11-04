package dev.ide.workbench.scenario

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/** Dependency-free fixtures, written ONLY into a newly allocated child workspace on explicit user action. */
object RegressionScenarioSamples {
    const val STATE_SCENARIO = "scenarios/state-roundtrip.json"
    const val COMMAND_SCENARIO = "scenarios/command-smoke.json"

    fun seedOfflineNode(parent: Path): Path {
        val base = ScenarioFiles.root(parent)
        val destination = Files.createTempDirectory(base, "regression-node-")
        nodeFiles().forEach { (relative, content) ->
            val file = ScenarioFiles.resolve(destination, relative, mustExist = false)
            Files.createDirectories(file.parent)
            Files.write(file, (content + "\n").toByteArray(Charsets.UTF_8), CREATE_NEW, WRITE)
        }
        return destination
    }

    fun nodeFiles(): Map<String, String> = linkedMapOf(
        "package.json" to """{"name":"building-regression-offline","version":"1.0.0","private":true,"scripts":{"start":"node server.cjs","test":"node smoke.cjs"}}""",
        "server.cjs" to """
            'use strict';
            // Local fixture only. No external services, dependencies, tokens or network reconfiguration.
            const http = require('node:http');
            const fs = require('node:fs');
            const path = require('node:path');
            const stateFile = path.join(__dirname, 'scenario-state.json');
            let state = { value: 'initial', revision: 0 };
            if (fs.existsSync(stateFile)) state = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
            let providerErrorPending = false;
            const send = (response, status, body) => {
              response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
              response.end(JSON.stringify(body));
            };
            const server = http.createServer((request, response) => {
              const route = request.url;
              if (request.method === 'GET' && route === '/health') return send(response, 200, { ready: true });
              if (request.method === 'GET' && route === '/state') return send(response, 200, state);
              if (request.method === 'GET' && route === '/provider') {
                if (providerErrorPending) {
                  providerErrorPending = false;
                  return send(response, 429, { error: { code: 'RATE_LIMITED', message: 'Local simulated provider limit', retryable: true }, fixture: true });
                }
                return send(response, 200, { ok: true, recovered: true, fixture: true });
              }
              if (request.method === 'POST' && route === '/fixtures/provider-error') {
                request.resume();
                providerErrorPending = true;
                return send(response, 202, { armed: true, fixture: true });
              }
              if (request.method === 'POST' && route === '/state') {
                let size = 0;
                const chunks = [];
                request.on('data', chunk => {
                  size += chunk.length;
                  if (size > 16384) { request.destroy(); return; }
                  chunks.push(chunk);
                });
                request.on('end', () => {
                  try {
                    const candidate = JSON.parse(Buffer.concat(chunks).toString('utf8'));
                    if (typeof candidate.value !== 'string' || candidate.value.length > 256 || !Number.isInteger(candidate.revision)) {
                      return send(response, 400, { error: { code: 'INVALID_STATE', retryable: false } });
                    }
                    const next = { value: candidate.value, revision: candidate.revision };
                    const temporary = stateFile + '.tmp';
                    fs.writeFileSync(temporary, JSON.stringify(next), { mode: 0o600 });
                    fs.renameSync(temporary, stateFile);
                    state = next;
                    send(response, 200, state);
                  } catch (_) { send(response, 400, { error: { code: 'INVALID_JSON', retryable: false } }); }
                });
                return;
              }
              request.resume();
              return send(response, 404, { error: { code: 'NOT_FOUND', retryable: false } });
            });
            server.requestTimeout = 3000;
            server.headersTimeout = 4000;
            server.listen(0, '127.0.0.1', () => {
              console.log('Preview: http://127.0.0.1:' + server.address().port + '/');
            });
            process.on('SIGTERM', () => server.close(() => process.exit(0)));
        """.trimIndent(),
        "smoke.cjs" to """
            'use strict';
            const assert = require('node:assert/strict');
            const restored = JSON.parse(JSON.stringify({ enabled: true, revision: 7 }));
            assert.equal(restored.enabled, true);
            assert.equal(restored.revision, 7);
            process.stdout.write('offline state serialization passed\n');
            process.stderr.write('fixture diagnostic only\n');
        """.trimIndent(),
        STATE_SCENARIO to """
            {
              "version": 1,
              "id": "offline-state-and-recovery",
              "title": "Persist state across restart and recover from a simulated provider error",
              "targetId": "node:server.cjs",
              "artifacts": ["package.json"],
              "limits": { "runTimeoutMs": 150000, "stepTimeoutMs": 10000, "maxOutputBytes": 32768 },
              "steps": [
                { "id": "start", "type": "start", "timeoutMs": 45000, "ready": { "path": "/health", "status": 200 } },
                { "id": "update", "type": "http", "method": "POST", "path": "/state",
                  "jsonBody": { "value": "retained-after-restart", "revision": 7 },
                  "expect": { "status": 200, "json": [{ "pointer": "/revision", "equals": 7 }] } },
                { "id": "retained", "type": "http", "method": "GET", "path": "/state",
                  "expect": { "status": 200, "json": [{ "pointer": "/value", "equals": "retained-after-restart" }, { "pointer": "/revision", "equals": 7 }] } },
                { "id": "stop-before-restart", "type": "stop" },
                { "id": "restart", "type": "start", "timeoutMs": 45000, "ready": { "path": "/health", "status": 200 } },
                { "id": "persisted", "type": "http", "method": "GET", "path": "/state",
                  "expect": { "status": 200, "json": [{ "pointer": "/value", "equals": "retained-after-restart" }, { "pointer": "/revision", "equals": 7 }], "maxResponseTimeMs": 2000 } },
                { "id": "arm-provider-error", "type": "http", "method": "POST", "path": "/fixtures/provider-error", "jsonBody": {},
                  "expect": { "status": 202, "json": [{ "pointer": "/armed", "equals": true }] } },
                { "id": "structured-provider-error", "type": "http", "method": "GET", "path": "/provider",
                  "expect": { "status": 429, "json": [{ "pointer": "/error/code", "equals": "RATE_LIMITED" }, { "pointer": "/error/retryable", "equals": true }] } },
                { "id": "provider-recovered", "type": "http", "method": "GET", "path": "/provider",
                  "expect": { "status": 200, "body": { "contains": ["recovered"] }, "json": [{ "pointer": "/recovered", "equals": true }] } },
                { "id": "stop", "type": "stop" }
              ]
            }
        """.trimIndent(),
        COMMAND_SCENARIO to """
            {
              "version": 1,
              "id": "offline-command-smoke",
              "title": "Run a real local command and assert separate output streams",
              "targetId": "node:smoke.cjs",
              "limits": { "runTimeoutMs": 60000, "stepTimeoutMs": 45000, "maxOutputBytes": 8192 },
              "steps": [
                { "id": "smoke", "type": "command", "expect": {
                  "exitCode": 0,
                  "stdout": { "equals": "offline state serialization passed\n" },
                  "stderr": { "equals": "fixture diagnostic only\n" }
                } }
              ]
            }
        """.trimIndent(),
    )
}
