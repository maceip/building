package dev.ide.agent.impl

import dev.ide.agent.IncidentTask
import dev.ide.agent.IncidentTaskKind
import dev.ide.agent.IncidentTestOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncidentTaskRunnerTest {
    @TempDir lateinit var temp: Path

    private val test = IncidentTask("test", IncidentTaskKind.REPRODUCER, listOf("/bin/sh", "repro.sh"),
        setOf("build/result.xml"), setOf("Proof#unchanged"), timeoutMillis = 2000)

    @Test fun realTaskExitAndStructuredCasesAreNotInferredFromStdout() = runBlocking {
        val root = temp.toRealPath()
        Files.writeString(root.resolve("repro.sh"), """
            #!/bin/sh
            mkdir -p build
            printf '%s' '<testsuite><testcase classname="Proof" name="unchanged"><failure/></testcase></testsuite>' > build/result.xml
            printf '%s' 'ALL TESTS PASSED'
            exit 7
        """.trimIndent())
        val receipt = runner().run(root, test)
        assertEquals(7, receipt.exitCode)
        assertTrue(receipt.completed)
        assertEquals(IncidentTestOutcome.FAILED, receipt.tests.single().outcome)
        assertEquals(IncidentDigests.task(test), receipt.taskFingerprint)
        assertEquals(64, receipt.reportHashes.getValue("build/result.xml").length)
    }

    @Test fun oldPassingReportIsDeletedBeforeExecutingTestThatDoesNotProduceOne() = runBlocking {
        val root = temp.toRealPath()
        Files.createDirectories(root.resolve("build"))
        Files.writeString(root.resolve("build/result.xml"), "<testsuite><testcase classname=\"Proof\" name=\"unchanged\"/></testsuite>")
        Files.writeString(root.resolve("repro.sh"), "exit 0\n")
        val receipt = runner().run(root, test)
        assertEquals(0, receipt.exitCode)
        assertTrue(receipt.tests.isEmpty() && receipt.reportHashes.isEmpty())
    }

    @Test fun cancellationKillsActualProcessInsteadOfReturningPass() = runBlocking {
        val root = temp.toRealPath()
        Files.writeString(root.resolve("repro.sh"), "exec sleep 30\n")
        val started = CompletableDeferred<Process>()
        val runner = IncidentProcessTaskRunner(object : IncidentProcessLauncher {
            override fun start(copyRoot: Path, task: IncidentTask): Process = ProcessBuilder(task.command).directory(copyRoot.toFile()).start().also { started.complete(it) }
            override fun stop(process: Process) { process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS) }
        })
        val task = async { runner.run(root, test) }
        val process = started.await()
        task.cancelAndJoin()
        assertFalse(process.isAlive)
    }

    @Test fun xmlEntitiesDuplicateIdentityAndSymlinkReportsAreRejected() {
        val root = temp.toRealPath()
        Files.createDirectories(root.resolve("build"))
        val result = root.resolve("build/result.xml")
        Files.writeString(result, "<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><testsuite><testcase classname=\"Proof\" name=\"unchanged\">&secret;</testcase></testsuite>")
        assertFailsWith<IllegalArgumentException> { IncidentJUnitEvidence.receipt(root, test, 0) }
        Files.writeString(result, "<testsuite><testcase classname=\"Proof\" name=\"unchanged\"/><testcase classname=\"Proof\" name=\"unchanged\"/></testsuite>")
        assertFailsWith<IllegalArgumentException> { IncidentJUnitEvidence.receipt(root, test, 0) }
        Files.delete(result)
        Files.createSymbolicLink(result, root.resolve("private.xml"))
        assertFailsWith<IllegalArgumentException> { IncidentJUnitEvidence.clear(root, test) }
    }

    private fun runner() = IncidentProcessTaskRunner(object : IncidentProcessLauncher {
        override fun start(copyRoot: Path, task: IncidentTask): Process = ProcessBuilder(task.command).directory(copyRoot.toFile()).apply {
            environment().clear(); environment()["PATH"] = "/usr/bin:/bin"
        }.start()
        override fun stop(process: Process) { process.destroy(); if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly() }
    })
}
