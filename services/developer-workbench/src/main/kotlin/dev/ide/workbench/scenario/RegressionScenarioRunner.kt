package dev.ide.workbench.scenario

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A one-shot runner. The caller approves the exact local file and target; closing/cancellation is owned here. */
class RegressionScenarioRunner(private val host: RuntimeScenarioHost) {
    private val used = AtomicBoolean(false)
    private val cleanupAttempted = AtomicBoolean(false)
    private var cleanupSucceeded = false

    private suspend fun closeHost(): Boolean = withContext(NonCancellable) {
        if (cleanupAttempted.compareAndSet(false, true)) {
            cleanupSucceeded = withTimeoutOrNull(ScenarioLimits.CLEANUP_MS) {
                try { host.close(); true } catch (_: Exception) { false }
            } ?: false
        }
        cleanupSucceeded
    }

    suspend fun run(reviewed: ReviewedRegressionScenario, approval: ScenarioRunApproval): ScenarioRunReport = withContext(Dispatchers.IO) {
        check(used.compareAndSet(false, true)) { "Create a new scenario runner/host for each run" }
        try {
            requireSafe(approval.trustedProjectCodeAcknowledged, "Review and acknowledge trusted project execution before running")
            requireSafe(approval.reviewedSha256 == reviewed.sha256 && approval.selectedTargetId == reviewed.scenario.targetId,
                "Approval must match the reviewed file hash and explicitly selected target")
            val current = RegressionScenarioParser.load(reviewed.projectRoot, reviewed.relativePath)
            requireSafe(current.sha256 == reviewed.sha256 && current.sourceArtifactHash == reviewed.sourceArtifactHash,
                "Scenario or reviewed source/config artifacts changed; review again before running")
            Execution(current).execute()
        } finally {
            // Also closes hosts whose validation failed before evidence allocation.
            closeHost()
        }
    }

    private inner class Execution(private val reviewed: ReviewedRegressionScenario) {
        private val scenario = reviewed.scenario
        private val http = ScenarioHttpClient()
        private val evidence = mutableListOf<ScenarioStepEvidence>()
        private var session: RuntimeScenarioSession? = null
        private var origin: HttpUrl? = null
        private var cancelled = false
        private var overallTimedOut = false
        private var cleanupFailed = false
        private var evidenceWriteFailed = false
        private val runId = UUID.randomUUID().toString()
        private val started = now()

        suspend fun execute(): ScenarioRunReport {
            val filename = started.replace(':', '-') + "-" + runId + ".json"
            val sink = try { ScenarioEvidenceStore.reserve(reviewed.projectRoot, filename) }
                catch (error: Exception) { http.close(); throw error }
            try {
                // Checkpoint exists BEFORE any command/request can start. An interrupted process cannot masquerade as PASS.
                sink.store(report(sink.path, stored = true, inProgress = true))
                try {
                    withTimeout(scenario.limits.runTimeoutMs) {
                        for (step in scenario.steps) {
                            currentCoroutineContext().ensureActive()
                            val stepEvidence = executeStep(step)
                            evidence += stepEvidence
                            sink.store(report(sink.path, stored = true, inProgress = true))
                            if (stepEvidence.assertions.any { !it.testAssertion && it.verdict == ScenarioVerdict.FAIL }) break
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    overallTimedOut = true
                } catch (_: CancellationException) {
                    cancelled = true
                } catch (_: Exception) {
                    evidenceWriteFailed = true
                } finally {
                    withContext(NonCancellable) {
                        http.close()
                        cleanupFailed = !closeHost()
                    }
                }
                val done = evidence.map { it.id }.toSet()
                scenario.steps.filter { it.id !in done }.forEach { step ->
                    val reason = when { cancelled -> "CANCELLED"; overallTimedOut -> "RUN_TIMEOUT"; else -> "DEPENDENCY_NOT_EXECUTED" }
                    evidence += skippedStep(step, reason)
                }
                var result = report(sink.path, stored = true)
                withContext(NonCancellable) {
                    try { sink.store(result) }
                    catch (_: Exception) {
                        result = result.copy(status = ScenarioRunStatus.FAILED, evidenceStored = false,
                            notes = result.notes + "Evidence storage failed or was replaced; this run is not comparable.")
                    }
                }
                if (cancelled) throw ScenarioRunCancelledException(result)
                return result
            } finally { sink.close(); http.close() }
        }

        private suspend fun executeStep(step: ScenarioStep): ScenarioStepEvidence {
            val began = now()
            val nanos = System.nanoTime()
            val assertions = mutableListOf<ScenarioAssertionEvidence>()
            var httpEvidence: ScenarioHttpEvidence? = null
            var processEvidence: ScenarioProcessEvidence? = null
            var executed = false
            try {
                withTimeout(step.timeoutMs) {
                    when (step) {
                        is ScenarioStep.Start -> {
                            session = host.start(reviewed.projectRoot, scenario.targetId)
                            executed = true
                            val owned = requireNotNull(session)
                            var snapshot = owned.awaitReady()
                            var attempts = 0
                            while (true) {
                                enforceOutputBound(snapshot)
                                if (!snapshot.running || snapshot.failed) throw ScenarioExecutionException("SERVER_EXITED_BEFORE_READY")
                                val url = snapshot.previewUrl
                                if (url != null) {
                                    val ownedOrigin = http.origin(url)
                                    attempts++
                                    try {
                                        val last = http.request(ownedOrigin, "GET", step.readyPath, null, scenario.limits.maxOutputBytes, minOf(step.timeoutMs, 2_000))
                                        if (last.status == step.readyStatus) {
                                            origin = ownedOrigin
                                            httpEvidence = ScenarioHttpEvidence(last.status, last.durationMs, last.bytes, networkOutcome(last.status), attempts = attempts)
                                            processEvidence = process(owned, snapshot)
                                            assertions += assertion("ready", true, "owned loopback HTTP ${step.readyStatus}", "HTTP ${last.status}; attempts=$attempts", test = false)
                                            break
                                        }
                                    } catch (_: IOException) { /* Explicit readiness GET alone is retried. */ }
                                    if (attempts >= 40) throw ScenarioExecutionException("READINESS_ATTEMPTS_EXHAUSTED")
                                }
                                delay(50)
                                snapshot = owned.snapshot()
                            }
                        }
                        is ScenarioStep.Stop -> {
                            val owned = session ?: throw ScenarioExecutionException("NO_OWNED_SESSION")
                            executed = true
                            owned.stop()
                            val snapshot = owned.snapshot()
                            if (snapshot.running) throw ScenarioExecutionException("STOP_INCOMPLETE")
                            processEvidence = process(owned, snapshot)
                            enforceOutputBound(snapshot)
                            session = null
                            origin = null
                            assertions += assertion("stopped", true, "owned process and preview stopped", "stopped", test = false)
                        }
                        is ScenarioStep.Command -> {
                            val owned = host.start(reviewed.projectRoot, scenario.targetId).also { session = it }
                            executed = true
                            val snapshot = awaitBoundedExit(owned)
                            processEvidence = process(owned, snapshot)
                            enforceOutputBound(snapshot)
                            if (snapshot.running || snapshot.exitCode == null) throw ScenarioExecutionException("NO_PROCESS_EXIT")
                            assertions += assertion("exitCode", snapshot.exitCode == step.expect.exitCode, step.expect.exitCode.toString(), snapshot.exitCode.toString())
                            textAssertions("stdout", step.expect.stdout, snapshot.stdout, snapshot.stdoutTruncated, assertions)
                            textAssertions("stderr", step.expect.stderr, snapshot.stderr, snapshot.stderrTruncated, assertions)
                            owned.stop()
                            session = null
                        }
                        is ScenarioStep.Http -> {
                            val owned = session ?: throw ScenarioExecutionException("NO_OWNED_SESSION")
                            val snapshot = owned.snapshot()
                            enforceOutputBound(snapshot)
                            if (!snapshot.running || snapshot.failed) throw ScenarioExecutionException("SERVER_NOT_RUNNING")
                            val scopedOrigin = origin ?: throw ScenarioExecutionException("NO_SCOPED_PREVIEW")
                            if (snapshot.previewUrl == null || http.origin(snapshot.previewUrl) != scopedOrigin) throw ScenarioExecutionException("PREVIEW_ORIGIN_CHANGED")
                            executed = true
                            val response = http.request(scopedOrigin, step.method, step.path, step.jsonBody?.toString(), scenario.limits.maxOutputBytes, step.timeoutMs)
                            val json = boundedJson(response.body)
                            httpEvidence = ScenarioHttpEvidence(response.status, response.durationMs, response.bytes, networkOutcome(response.status),
                                structuredError = json is JsonObject && json["error"] is JsonObject)
                            assertions += assertion("status", response.status == step.expect.status, step.expect.status.toString(), response.status.toString())
                            textAssertions("body", step.expect.body, response.body, false, assertions)
                            step.expect.json.forEachIndexed { index, expected ->
                                val actual = json?.let { pointer(it, expected.pointer) }
                                assertions += assertion("json[$index]", actual != null && actual == expected.equals,
                                    "JSON ${jsonType(expected.equals)} value [redacted]", if (actual == null) "missing or invalid JSON" else "JSON ${jsonType(actual)} value [redacted]")
                            }
                            step.expect.maxResponseTimeMs?.let { maximum ->
                                assertions += assertion("responseTimeMs", response.durationMs <= maximum, "<= $maximum ms", "${response.durationMs} ms")
                            }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                assertions += operationalFailure("STEP_TIMEOUT")
                httpEvidence = if (step is ScenarioStep.Http || step is ScenarioStep.Start) ScenarioHttpEvidence(null, elapsed(nanos), 0, ScenarioNetworkOutcome.TIMEOUT) else null
                if (!currentCoroutineContext().isActive) overallTimedOut = true
            } catch (error: CancellationException) {
                cancelled = true
                assertions += skippedAssertion("execution", "completed operation", "CANCELLED", test = false)
                addUnexecutedAssertions(step, assertions, "CANCELLED")
                evidence += ScenarioStepEvidence(step.id, step.kind(), ScenarioVerdict.SKIPPED, began, now(), elapsed(nanos), executed, assertions, httpEvidence, processEvidence)
                throw error
            } catch (error: Exception) {
                val code = when (error) {
                    is ScenarioExecutionException -> error.code
                    is InterruptedIOException -> "IO_TIMEOUT"
                    is IOException -> "TRANSPORT_ERROR"
                    is ScenarioValidationException -> "SAFETY_VALIDATION_FAILED"
                    else -> "HOST_OPERATION_FAILED"
                }
                assertions += operationalFailure(code)
                if (step is ScenarioStep.Http || step is ScenarioStep.Start) {
                    val outcome = when (code) {
                        "BLOCKED_URL" -> ScenarioNetworkOutcome.BLOCKED_URL
                        "BLOCKED_REDIRECT" -> ScenarioNetworkOutcome.BLOCKED_REDIRECT
                        "RESPONSE_TOO_LARGE" -> ScenarioNetworkOutcome.RESPONSE_TOO_LARGE
                        "IO_TIMEOUT" -> ScenarioNetworkOutcome.TIMEOUT
                        else -> ScenarioNetworkOutcome.TRANSPORT_ERROR
                    }
                    httpEvidence = ScenarioHttpEvidence(null, elapsed(nanos), 0, outcome)
                }
            }
            if (assertions.any { !it.testAssertion && it.verdict != ScenarioVerdict.PASS }) addUnexecutedAssertions(step, assertions, "OPERATION_NOT_COMPLETED")
            val verdict = when {
                assertions.any { it.verdict == ScenarioVerdict.FAIL } -> ScenarioVerdict.FAIL
                assertions.any { it.verdict == ScenarioVerdict.SKIPPED } -> ScenarioVerdict.SKIPPED
                else -> ScenarioVerdict.PASS
            }
            return ScenarioStepEvidence(step.id, step.kind(), verdict, began, now(), elapsed(nanos), executed, assertions, httpEvidence, processEvidence)
        }

        private suspend fun awaitBoundedExit(owned: RuntimeScenarioSession): ScenarioProcessSnapshot = coroutineScope {
            val exit = async { owned.awaitExit() }
            try {
                while (!exit.isCompleted) {
                    enforceOutputBound(owned.snapshot())
                    delay(25)
                }
                exit.await().also(::enforceOutputBound)
            } finally { exit.cancel() }
        }

        private fun enforceOutputBound(snapshot: ScenarioProcessSnapshot) {
            if (snapshot.stdoutTruncated || snapshot.stderrTruncated ||
                listOfNotNull(snapshot.stdout, snapshot.stderr).any { it.toByteArray().size > scenario.limits.maxOutputBytes }) {
                throw ScenarioExecutionException("PROCESS_OUTPUT_LIMIT")
            }
        }

        private fun process(owned: RuntimeScenarioSession, snapshot: ScenarioProcessSnapshot) = ScenarioProcessEvidence(
            owned.sessionId, snapshot.exitCode, snapshot.stdout?.toByteArray()?.size, snapshot.stderr?.toByteArray()?.size,
            !snapshot.stdoutTruncated && !snapshot.stderrTruncated && snapshot.stdout != null && snapshot.stderr != null,
        )

        private fun report(path: Path, stored: Boolean, inProgress: Boolean = false): ScenarioRunReport {
            val status = when {
                cancelled -> ScenarioRunStatus.CANCELLED
                overallTimedOut || cleanupFailed || evidenceWriteFailed || evidence.any { it.verdict == ScenarioVerdict.FAIL } -> ScenarioRunStatus.FAILED
                inProgress || evidence.size < scenario.steps.size || evidence.any { it.verdict == ScenarioVerdict.SKIPPED } -> ScenarioRunStatus.SKIPPED
                else -> ScenarioRunStatus.PASSED
            }
            return ScenarioRunReport(runId, scenario.id, reviewed.sha256, reviewed.relativePath, scenario.targetId,
                reviewed.sourceArtifacts, reviewed.sourceArtifactHash, started, now(), status, evidence.toList(), path, stored,
                buildList {
                    add("User-trusted local project execution, NOT an operating-system sandbox.")
                    add("No raw process logs, request/response bodies, assertion values, headers or environment values are stored.")
                    add("Hashes cover the scenario plus listed entry/config/explicit artifacts, not all imported code or dependencies.")
                    add("Loopback HTTP and process lifecycle only; no Android app rotation, keyboard, accessibility, root or network reconfiguration.")
                    if (inProgress) add("Run checkpoint only; unfinished tests do not prove a pass.")
                    if (overallTimedOut) add("Overall scenario time limit reached; unfinished assertions are skipped.")
                    if (cleanupFailed) add("Owned host cleanup did not confirm completion within its limit; investigate before another run.")
                    if (evidenceWriteFailed) add("An intermediate evidence checkpoint could not be written; execution was stopped.")
                }, executionComplete = !inProgress && !cancelled && !overallTimedOut && !cleanupFailed && !evidenceWriteFailed &&
                    evidence.size == scenario.steps.size && evidence.all { it.executed })
        }
    }
}

private fun now(): String = Instant.now().toString()
private fun elapsed(startNanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
private fun assertion(key: String, passed: Boolean, expected: String, observed: String, test: Boolean = true) =
    ScenarioAssertionEvidence(key, if (passed) ScenarioVerdict.PASS else ScenarioVerdict.FAIL, expected, observed, now(), test)
private fun skippedAssertion(key: String, expected: String, reason: String, test: Boolean = true) =
    ScenarioAssertionEvidence(key, ScenarioVerdict.SKIPPED, expected, reason, now(), test)
private fun operationalFailure(code: String) = assertion("operation", false, "completed bounded operation", code, test = false)
private fun textAssertions(key: String, expected: TextExpectation?, actual: String?, truncated: Boolean, output: MutableList<ScenarioAssertionEvidence>) {
    if (expected == null) return
    val checks = buildList {
        expected.equals?.let { add("$key.equals" to (actual == it)) }
        expected.contains.forEachIndexed { index, value -> add("$key.contains[$index]" to (actual?.contains(value) == true)) }
    }
    checks.forEach { (name, matches) ->
        if (actual == null || truncated) output += skippedAssertion(name, "reviewed text [redacted]", "distinct complete stream unavailable")
        else output += assertion(name, matches, "reviewed text [redacted]", "${actual.toByteArray().size} bytes; matches=$matches; contents [redacted]")
    }
}
private fun expectedKeys(step: ScenarioStep): List<String> = buildList {
    fun textKeys(name: String, expectation: TextExpectation?) {
        if (expectation?.equals != null) add("$name.equals")
        expectation?.contains?.indices?.forEach { add("$name.contains[$it]") }
    }
    when (step) {
        is ScenarioStep.Command -> { add("exitCode"); textKeys("stdout", step.expect.stdout); textKeys("stderr", step.expect.stderr) }
        is ScenarioStep.Http -> { add("status"); textKeys("body", step.expect.body); step.expect.json.indices.forEach { add("json[$it]") }; if (step.expect.maxResponseTimeMs != null) add("responseTimeMs") }
        else -> Unit
    }
}
private fun addUnexecutedAssertions(step: ScenarioStep, assertions: MutableList<ScenarioAssertionEvidence>, reason: String) {
    val completed = assertions.map { it.key }.toSet()
    expectedKeys(step).filter { it !in completed }.forEach { assertions += skippedAssertion(it, "reviewed assertion [redacted]", reason) }
}
private fun skippedStep(step: ScenarioStep, reason: String): ScenarioStepEvidence {
    val checks = mutableListOf(skippedAssertion("execution", "step executed", reason, test = false))
    addUnexecutedAssertions(step, checks, reason)
    return ScenarioStepEvidence(step.id, step.kind(), ScenarioVerdict.SKIPPED, now(), now(), 0, false, checks)
}
private fun boundedJson(body: String): JsonElement? = try { StrictScenarioJson.validate(body); Json.parseToJsonElement(body) } catch (_: Exception) { null }
private fun jsonType(value: JsonElement): String = when (value) {
    JsonNull -> "null"
    is JsonArray -> "array"
    is JsonObject -> "object"
    is JsonPrimitive -> when { value.isString -> "string"; value.booleanOrNull != null -> "boolean"; else -> "number" }
}
private fun pointer(root: JsonElement, pointer: String): JsonElement? {
    if (pointer.isEmpty()) return root
    var current: JsonElement = root
    for (part in pointer.substring(1).split('/')) {
        val key = part.replace("~1", "/").replace("~0", "~")
        current = when (val node = current) {
            is JsonObject -> node[key]
            is JsonArray -> if (key.matches(Regex("0|[1-9][0-9]*"))) key.toIntOrNull()?.let { node.getOrNull(it) } else null
            else -> null
        } ?: return null
    }
    return current
}
