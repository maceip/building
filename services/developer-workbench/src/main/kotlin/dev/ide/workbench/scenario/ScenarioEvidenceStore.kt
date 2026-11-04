package dev.ide.workbench.scenario

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Evidence deliberately contains NO raw stdout/stderr, request/response payloads, headers or env values.
 * Even failed expected/observed text and JSON assertions expose only types, byte counts and match flags.
 * This avoids relying on a regex to recognize arbitrary personal data or provider credentials. */
object ScenarioEvidenceStore {
    fun toJson(report: ScenarioRunReport): String = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("formatVersion", 1)
        put("runId", report.runId)
        put("scenarioId", report.scenarioId)
        put("scenarioSha256", report.scenarioSha256)
        put("scenarioPath", report.scenarioPath)
        put("targetId", report.targetId)
        put("sourceArtifactHash", report.sourceArtifactHash)
        put("sourceArtifacts", JsonArray(report.sourceArtifacts.map { artifact -> buildJsonObject {
            put("path", artifact.relativePath); put("sha256", artifact.sha256); put("bytes", artifact.bytes)
        } }))
        put("startedAt", report.startedAt); put("finishedAt", report.finishedAt)
        put("status", report.status.name)
        put("testActuallyRan", report.testActuallyRan)
        put("evaluatedTestAssertions", report.evaluatedTestAssertions)
        put("comparisonEligible", report.comparisonEligible)
        put("evidenceStored", report.evidenceStored)
        put("executionComplete", report.executionComplete)
        put("notes", JsonArray(report.notes.map(::JsonPrimitive)))
        put("steps", JsonArray(report.steps.map { step -> buildJsonObject {
            put("id", step.id); put("kind", step.kind); put("verdict", step.verdict.name)
            put("startedAt", step.startedAt); put("finishedAt", step.finishedAt); put("durationMs", step.durationMs)
            put("executed", step.executed)
            put("assertions", JsonArray(step.assertions.map { assertion -> buildJsonObject {
                put("key", assertion.key); put("verdict", assertion.verdict.name)
                put("expected", assertion.expected); put("observed", assertion.observed)
                put("timestamp", assertion.timestamp); put("testAssertion", assertion.testAssertion)
            } }))
            step.http?.let { http -> put("http", buildJsonObject {
                http.status?.let { put("status", it) }; put("durationMs", http.durationMs); put("responseBytes", http.responseBytes)
                put("outcome", http.outcome.name); put("structuredError", http.structuredError); put("attempts", http.attempts)
            }) }
            step.process?.let { process -> put("process", buildJsonObject {
                put("sessionId", process.sessionId); process.exitCode?.let { put("exitCode", it) }
                process.stdoutBytes?.let { put("stdoutBytes", it) }; process.stderrBytes?.let { put("stderrBytes", it) }
                put("outputComplete", process.outputComplete)
            }) }
        } }))
    })

    internal fun reserve(root: Path, filename: String): Sink {
        val (path, channel) = ScenarioFiles.reserveEvidence(root, filename)
        return try { Sink(root, path, channel, Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()) }
        catch (error: Exception) { channel.close(); throw error }
    }

    internal class Sink(private val root: Path, val path: Path, private val channel: FileChannel, private val fileKey: Any?) : AutoCloseable {
        fun store(report: ScenarioRunReport) {
            ScenarioFiles.resolve(root, root.relativize(path).toString().replace('\\', '/'))
            val currentKey = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
            requireSafe(fileKey == null || fileKey == currentKey, "Evidence file was replaced during the run")
            val bytes = toJson(report).toByteArray(Charsets.UTF_8)
            requireSafe(bytes.size <= 512 * 1024, "Evidence exceeds storage limit")
            channel.truncate(0)
            channel.position(0)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        override fun close() = channel.close()
    }
}

data class ScenarioAssertionChange(val stepId: String, val assertionKey: String, val before: ScenarioVerdict, val after: ScenarioVerdict)
data class ScenarioComparison(
    val beforeRunId: String,
    val afterRunId: String,
    val scenarioSha256: String,
    val sourceChanged: Boolean,
    val changes: List<ScenarioAssertionChange>,
)

/** No automatic "fixed" claim: callers explicitly choose both runs and pin the SAME reviewed scenario hash. */
object RegressionScenarioComparison {
    fun compare(before: ScenarioRunReport, after: ScenarioRunReport, explicitlyMatchedScenarioSha256: String): ScenarioComparison {
        requireSafe(explicitlyMatchedScenarioSha256.matches(Regex("[0-9a-f]{64}")), "Explicitly select a scenario hash for comparison")
        requireSafe(before.scenarioSha256 == explicitlyMatchedScenarioSha256 && after.scenarioSha256 == explicitlyMatchedScenarioSha256,
            "Before/after comparison requires the exact same reviewed scenario hash")
        requireSafe(before.targetId == after.targetId && before.scenarioId == after.scenarioId && before.runId != after.runId,
            "Select two distinct runs of the same target and scenario")
        requireSafe(before.comparisonEligible && after.comparisonEligible,
            "Both runs must store evidence and execute all test assertions; cancelled, skipped or unexecuted tests cannot prove a fix")
        val old = before.steps.associateBy { it.id }
        requireSafe(old.keys == after.steps.map { it.id }.toSet(), "Step evidence does not match")
        val changes = after.steps.flatMap { next ->
            val previous = old.getValue(next.id).assertions.filter { it.testAssertion }.associateBy { it.key }
            val current = next.assertions.filter { it.testAssertion }
            requireSafe(previous.keys == current.map { it.key }.toSet(), "Assertion evidence does not match")
            current.mapNotNull { assertion ->
                val earlier = previous.getValue(assertion.key).verdict
                if (earlier == assertion.verdict) null else ScenarioAssertionChange(next.id, assertion.key, earlier, assertion.verdict)
            }
        }
        return ScenarioComparison(before.runId, after.runId, explicitlyMatchedScenarioSha256,
            before.sourceArtifactHash != after.sourceArtifactHash, changes)
    }
}
