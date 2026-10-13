package dev.ide.agent.impl

import dev.ide.agent.IncidentAiConfiguration
import dev.ide.agent.IncidentAiProvider
import dev.ide.agent.IncidentApplyResult
import dev.ide.agent.IncidentIdentity
import dev.ide.agent.IncidentKnownBuild
import dev.ide.agent.IncidentPatchReview
import dev.ide.agent.IncidentRecord
import dev.ide.agent.IncidentRepairHost
import dev.ide.agent.IncidentRepairPhase
import dev.ide.agent.IncidentRepairPolicy
import dev.ide.agent.IncidentSource
import dev.ide.agent.IncidentSourceSnapshot
import dev.ide.agent.IncidentTask
import dev.ide.agent.IncidentTaskKind
import dev.ide.agent.IncidentTaskRunner
import dev.ide.agent.IncidentVerificationPlan
import dev.ide.agent.LlmClient
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.StopReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Scripted MODEL only. All build/repro/function outcomes below come from real external processes. */
class IncidentRepairTest {
    @TempDir lateinit var temp: Path

    @Test fun disabledPolicyMakesNoLlmOrTaskCalls() = runBlocking {
        val fixture = fixture()
        fixture.policy = fixture.policy.copy(enabled = false)
        val result = fixture.engine().repair(fixture.record) {}
        assertEquals(IncidentRepairPhase.DISABLED, result.phase)
        assertEquals(0, fixture.ai.requests.size)
        assertEquals(0, fixture.executions)
        fixture.assertOriginal()
    }

    @Test fun enablingRepairDoesNotImplyCloudSharingOrConfiguredProviderApproval() = runBlocking {
        val fixture = fixture()
        fixture.policy = fixture.policy.copy(cloudAiDataSharing = false)
        assertEquals(IncidentRepairPhase.PENDING, fixture.engine().repair(fixture.record) {}.phase)
        fixture.policy = fixture.policy.copy(cloudAiDataSharing = true, approvedAiConfiguration = "different-endpoint")
        assertEquals(IncidentRepairPhase.PENDING, fixture.engine().repair(fixture.record) {}.phase)
        assertEquals(0, fixture.ai.requests.size)
        assertEquals(0, fixture.executions)
        fixture.assertOriginal()
    }

    @Test fun staleSourceIsRefusedBeforeTaskOrModelCalls() = runBlocking {
        val fixture = fixture()
        Files.writeString(fixture.project.resolve("src/Logic.java"), "edited by user after incident\n")
        val expected = Files.readAllBytes(fixture.project.resolve("src/Logic.java"))
        assertEquals(IncidentRepairPhase.REFUSED, fixture.engine().repair(fixture.record) {}.phase)
        assertEquals(0, fixture.ai.requests.size)
        assertEquals(0, fixture.executions)
        assertTrue(Files.readAllBytes(fixture.project.resolve("src/Logic.java")).contentEquals(expected))
    }

    @Test fun dirtyOriginalIsPreservedAndSameImmutableReproFailsThenPassesInCopy() = runBlocking {
        val fixture = fixture()
        fixture.ai.respond = { request -> patchTurn(request, "fixed") }
        val result = fixture.engine().repair(fixture.record) {}
        assertEquals(IncidentRepairPhase.AWAITING_REVIEW, result.phase, result.detail)
        val review = requireNotNull(result.review)
        assertEquals("fixed\n", review.changes.single().after)
        assertNotEquals(review.originalSnapshot.revision, review.repairedSnapshot.revision)
        assertEquals(6, fixture.executions)
        assertEquals(listOf(1, 0), review.evidence.filter { it.receipt.taskId == "repro" }.map { it.receipt.exitCode })
        assertEquals(1, review.evidence.map { it.reproducerHash }.toSet().size)
        assertEquals(1, review.evidence.map { it.immutableInputsHash }.toSet().size)
        val names = fixture.ai.requests.first().tools.map { it.name }
        assertTrue("read_incident" in names && "list_incidents" in names && "edit_file" in names)
        assertTrue(names.none { it in setOf("http_request", "fetch_url", "run_task", "run_program", "delete_path", "add_dependency", "rename_symbol", "move_path", "ftp_server") })
        assertTrue(fixture.ai.requests.all { !it.webSearch })
        fixture.assertOriginal()
        assertEquals("USER DIRTY\n", Files.readString(review.isolatedRoot.resolve("src/User.java")))
    }

    @Test fun cancellationAfterCopyEditNeverTouchesOriginalAndDeletesCopy() = runBlocking {
        val fixture = fixture()
        val editing = CompletableDeferred<Unit>()
        fixture.ai.respond = { request ->
            if (fixture.ai.requests.size == 1) patchTurn(request, "fixed") else {
                editing.complete(Unit)
                awaitCancellation()
            }
        }
        val run = async { fixture.engine().repair(fixture.record) {} }
        editing.await()
        run.cancelAndJoin()
        fixture.assertOriginal()
        assertEquals(0L, Files.list(fixture.privateRoot).use { it.count() })
    }

    @Test fun protectedReproducerWriteIsDeniedAndOriginalIsUntouched() = runBlocking {
        val fixture = fixture()
        fixture.ai.respond = {
            if (fixture.ai.requests.size == 1) listOf(
                LlmStreamEvent.ToolCallCompleted("deny", "write_file", """{"path":"tests/repro.sh","content":"exit 0"}"""),
                LlmStreamEvent.Completed(StopReason.TOOL_USE),
            ) else listOf(LlmStreamEvent.Completed(StopReason.END_TURN))
        }
        val result = fixture.engine().repair(fixture.record) {}
        assertEquals(IncidentRepairPhase.FAILED, result.phase)
        fixture.assertOriginal()
        assertEquals(0L, Files.list(fixture.privateRoot).use { it.count() })
    }

    @Test fun modelSelfReportedPassCannotReplaceFailingTaskEvidenceAndAttemptsStopAtTwo() = runBlocking {
        val fixture = fixture()
        fixture.ai.respond = { request ->
            if (fixture.ai.requests.size % 2 == 1) patchTurn(request, "still-broken-${fixture.ai.requests.size}")
            else listOf(LlmStreamEvent.TextDelta("All tests passed, verified!"), LlmStreamEvent.Completed(StopReason.END_TURN))
        }
        val result = fixture.engine().repair(fixture.record) {}
        assertEquals(IncidentRepairPhase.FAILED, result.phase)
        assertEquals(2, result.attempt)
        assertEquals(4, fixture.ai.requests.size)
        assertEquals(9, fixture.executions)
        fixture.assertOriginal()
    }

    @Test fun aPassingBaselineCannotBeReportedAsARepair() = runBlocking {
        val fixture = fixture(initial = "fixed\n")
        val result = fixture.engine().repair(fixture.record) {}
        assertEquals(IncidentRepairPhase.PENDING, result.phase)
        assertEquals(0, fixture.ai.requests.size)
        assertEquals(2, fixture.executions)
        fixture.assertOriginal()
    }

    @Test fun sourceChangedWhileReviewingRefusesApplyAcrossAllFilenames() = runBlocking {
        val fixture = fixture()
        fixture.ai.respond = { request -> patchTurn(request, "fixed") }
        val engine = fixture.engine()
        val result = engine.repair(fixture.record) {}
        val review = requireNotNull(result.review)
        Files.move(fixture.project.resolve("src/User.java"), fixture.project.resolve("src/UserRenamed.java"))
        val applied = engine.apply(review, result)
        assertEquals(IncidentRepairPhase.REFUSED, applied.phase)
        assertEquals(0, fixture.applies)
        assertEquals("broken\n", Files.readString(fixture.project.resolve("src/Logic.java")))
        assertEquals("USER DIRTY\n", Files.readString(fixture.project.resolve("src/UserRenamed.java")))
    }

    @Test fun autoPatchCannotPretendThatMissingAtomicApplySucceeded() = runBlocking {
        val fixture = fixture()
        fixture.policy = fixture.policy.copy(autoApplyVerifiedPatch = true)
        fixture.ai.respond = { request -> patchTurn(request, "fixed") }
        val result = fixture.engine().repair(fixture.record) {}
        assertEquals(IncidentRepairPhase.VERIFIED_COPY, result.phase)
        assertEquals(1, fixture.applies)
        assertTrue(result.review != null)
        fixture.assertOriginal()
    }

    @Test fun taskThatChangesAnImmutableTestIsRejected() = runBlocking {
        val fixture = fixture()
        fixture.afterTask = { root, task ->
            if (task.id == "repro") Files.writeString(root.resolve("tests/repro.sh"), "different invariant\n")
        }
        val result = fixture.engine().repair(fixture.record) {}
        assertEquals(IncidentRepairPhase.REFUSED, result.phase)
        assertEquals(0, fixture.ai.requests.size)
        fixture.assertOriginal()
    }

    @Test fun cloudConsentRevocationStopsFollowupRequestsWithoutOriginalChanges() = runBlocking {
        val fixture = fixture()
        fixture.ai.respond = { request ->
            fixture.policy = fixture.policy.copy(cloudAiDataSharing = false)
            patchTurn(request, "fixed")
        }
        val result = fixture.engine().repair(fixture.record) {}
        assertEquals(IncidentRepairPhase.REFUSED, result.phase)
        assertEquals(1, fixture.ai.requests.size)
        fixture.assertOriginal()
    }

    @Test fun incidentPairingRevocationStopsFollowupRequestsAndPreservesOriginal() = runBlocking {
        val fixture = fixture()
        fixture.ai.respond = { request ->
            fixture.incidentAuthorized = false
            patchTurn(request, "fixed")
        }
        val result = fixture.engine().repair(fixture.record) {}
        assertEquals(IncidentRepairPhase.REFUSED, result.phase)
        assertEquals(1, fixture.ai.requests.size)
        fixture.assertOriginal()
    }

    @Test fun nativeHarnessHashMismatchIsRefusedBeforeLlm() = runBlocking {
        val fixture = fixture()
        fixture.plan = fixture.plan.copy(immutableHarnessSha256 = "a".repeat(64))
        val result = fixture.engine().repair(fixture.record) {}
        assertEquals(IncidentRepairPhase.REFUSED, result.phase)
        assertEquals(0, fixture.ai.requests.size)
        fixture.assertOriginal()
    }

    @Test fun unknownBuildAndReleaseBuildCannotGrantRepairAuthority() = runBlocking {
        val fixture = fixture()
        fixture.known = fixture.known.copy(debuggable = false)
        assertEquals(IncidentRepairPhase.REFUSED, fixture.engine().repair(fixture.record) {}.phase)
        fixture.known = fixture.known.copy(debuggable = true, packageName = "other.package")
        assertEquals(IncidentRepairPhase.REFUSED, fixture.engine().repair(fixture.record) {}.phase)
        assertEquals(0, fixture.executions)
        fixture.assertOriginal()
    }

    private fun patchTurn(request: LlmRequest, value: String): List<LlmStreamEvent> {
        val last = request.messages.lastOrNull()
        if (last?.role == dev.ide.agent.LlmRole.TOOL) return listOf(LlmStreamEvent.Completed(StopReason.END_TURN))
        return listOf(LlmStreamEvent.ToolCallCompleted("patch-${request.messages.size}", "write_file",
            """{"path":"src/Logic.java","content":"$value\n"}"""), LlmStreamEvent.Completed(StopReason.TOOL_USE))
    }

    private fun fixture(initial: String = "broken\n"): Fixture {
        val root = temp.toRealPath()
        val project = Files.createDirectory(root.resolve("project"))
        val privateRoot = Files.createDirectory(root.resolve("private"))
        fun write(name: String, content: String) {
            val path = project.resolve(name)
            Files.createDirectories(path.parent)
            Files.writeString(path, content)
        }
        write("src/Logic.java", initial)
        write("src/User.java", "USER DIRTY\n")
        write("tests/build.sh", "#!/bin/sh\nset -eu\ntest -s src/Logic.java\ntest -s src/User.java\n")
        write("tests/repro.sh", """
            #!/bin/sh
            set -eu
            mkdir -p build/results
            if grep -qx fixed src/Logic.java; then
              printf '%s' '<testsuite><testcase classname="fixture.State" name="sameReproducer"/></testsuite>' > build/results/repro.xml
              exit 0
            fi
            printf '%s' '<testsuite><testcase classname="fixture.State" name="sameReproducer"><failure/></testcase></testsuite>' > build/results/repro.xml
            exit 1
        """.trimIndent() + "\n")
        write("tests/functional.sh", """
            #!/bin/sh
            set -eu
            mkdir -p build/results
            if grep -qx 'USER DIRTY' src/User.java; then
              printf '%s' '<testsuite><testcase classname="fixture.Function" name="independentFunction"/></testsuite>' > build/results/function.xml
              exit 0
            fi
            printf '%s' '<testsuite><testcase classname="fixture.Function" name="independentFunction"><failure/></testcase></testsuite>' > build/results/function.xml
            exit 1
        """.trimIndent() + "\n")
        return Fixture(project, privateRoot)
    }

    private class TestAi : IncidentAiProvider {
        val requests = mutableListOf<LlmRequest>()
        var respond: suspend (LlmRequest) -> List<LlmStreamEvent> = { listOf(LlmStreamEvent.Completed(StopReason.END_TURN)) }
        override fun configuration() = IncidentAiConfiguration("approved", "Unit test scripted model")
        override fun model(expectedConfigurationId: String): String = "test-model"
        override fun client(expectedConfigurationId: String) = LlmClient { request -> flow {
            requests += request
            respond(request).forEach { emit(it) }
        } }
    }

    private class Fixture(val project: Path, val privateRoot: Path) {
        val ai = TestAi()
        var policy = IncidentRepairPolicy("project", enabled = true, cloudAiDataSharing = true, approvedAiConfiguration = "approved")
        val original = allInputs(project).associateWith { Files.readAllBytes(project.resolve(it)) }
        var known = IncidentKnownBuild("project", "fixture.app", "build-1", "brokenDebug", snapshot(project).revision,
            "source-uuid", "a".repeat(64), debuggable = true)
        val record = IncidentRecord(IncidentIdentity("fixture.app", "build-1", "session-1", "event-1"), known.sourceRevision, 1, "fatal_java")
        var incidentAuthorized = true
        val source = object : IncidentSource {
            override suspend fun list(limit: Int) = if (incidentAuthorized) listOf(record) else emptyList()
            override suspend fun read(identity: IncidentIdentity) = record.takeIf { incidentAuthorized && it.identity == identity }
            override fun observe() = MutableStateFlow(listOf(record))
        }
        private val inputs = setOf("tests/build.sh", "tests/repro.sh", "tests/functional.sh")
        var plan = IncidentVerificationPlan(
            IncidentTask("build", IncidentTaskKind.BUILD, listOf("/bin/sh", "tests/build.sh")),
            IncidentTask("repro", IncidentTaskKind.REPRODUCER, listOf("/bin/sh", "tests/repro.sh"), setOf("build/results/repro.xml"), setOf("fixture.State#sameReproducer")),
            IncidentTask("function", IncidentTaskKind.FUNCTIONAL, listOf("/bin/sh", "tests/functional.sh"), setOf("build/results/function.xml"), setOf("fixture.Function#independentFunction")),
            setOf("src/Logic.java"), inputs.associateWith { IncidentDigests.file(project.resolve(it)) },
            setOf("tests/repro.sh"), setOf("tests/functional.sh"), additionalCopyFiles = inputs,
        )
        var executions = 0
        var applies = 0
        var afterTask: (Path, IncidentTask) -> Unit = { _, _ -> }
        // Unit fixture scripts are owned, fixed offline commands. This is NOT a production sandbox claim.
        private val processes = IncidentProcessTaskRunner(object : IncidentProcessLauncher {
            override fun start(copyRoot: Path, task: IncidentTask): Process {
                executions++
                return ProcessBuilder(task.command).directory(copyRoot.toFile()).apply {
                    environment().clear(); environment()["PATH"] = "/usr/bin:/bin"
                }.start()
            }
            override fun stop(process: Process) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        })
        val host = object : IncidentRepairHost {
            override val projectId = "project"
            override val projectRoot = project
            override val privateWorkRoot = privateRoot
            override val tasks = IncidentTaskRunner { root, task -> processes.run(root, task).also { afterTask(root, task) } }
            override suspend fun knownBuild(incident: IncidentRecord) = known
            override suspend fun snapshot(root: Path) = Fixture.snapshot(root)
            override suspend fun verificationPlan(build: IncidentKnownBuild) = plan
            override suspend fun applyAtomically(review: IncidentPatchReview): IncidentApplyResult {
                applies++
                return IncidentApplyResult.Pending("No atomic editor transaction in the unit fixture.")
            }
        }
        fun engine() = IncidentRepairEngine(host, source, ai) { policy }
        fun assertOriginal() {
            assertEquals(original.keys, allInputs(project).toSet())
            original.forEach { (name, bytes) -> assertTrue(Files.readAllBytes(project.resolve(name)).contentEquals(bytes), name) }
        }
        companion object {
            fun snapshot(root: Path): IncidentSourceSnapshot {
                val files = allInputs(root).filter { it.endsWith(".java") }.associateWith { IncidentDigests.file(root.resolve(it)) }
                // Test-only aggregate; production host reuses dev.ide.build.ReliabilitySourceSnapshot.
                return IncidentSourceSnapshot(IncidentDigests.entries(files), files)
            }
            fun allInputs(root: Path): List<String> = Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) }.map { root.relativize(it).toString().replace('\\', '/') }
                    .filter { !it.startsWith("build/") }.sorted().toList()
            }
        }
    }
}
