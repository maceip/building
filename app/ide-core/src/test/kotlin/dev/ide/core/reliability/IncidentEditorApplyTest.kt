package dev.ide.core.reliability

import dev.ide.agent.FileChange
import dev.ide.agent.IncidentApplyResult
import dev.ide.agent.IncidentIdentity
import dev.ide.agent.IncidentKnownBuild
import dev.ide.agent.IncidentPatchReview
import dev.ide.agent.IncidentSourceSnapshot
import dev.ide.agent.impl.IncidentDigests
import dev.ide.agent.impl.IncidentRepairPending
import dev.ide.build.ReliabilitySourceSnapshot
import dev.ide.core.IdeServices
import dev.ide.core.IdeServicesBackend
import dev.ide.model.impl.SdkData
import dev.ide.model.PlatformKind
import dev.ide.vfs.VfsEvent
import dev.ide.vfs.VfsListener
import dev.ide.vfs.VfsTopics
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class IncidentEditorApplyTest {
    @TempDir lateinit var temp: Path

    @Test fun activeClosedProjectAppliesOneVerifiedFileAndPublishesOneBatch() = runBlocking<Unit> {
        val fixture = fixture()
        val backend = IdeServicesBackend(fixture.engine)
        try {
            val notifications = mutableListOf<List<VfsEvent>>()
            val connection = fixture.engine.platform.messageBus.connect()
            connection.subscribe(VfsTopics.CHANGES, object : VfsListener {
                override fun onEvents(events: List<VfsEvent>) { notifications += events }
            })
            try {
                val epoch = backend.fileSystemEpoch.value
                val apply = assertNotNull(backend.incidentPatchApplier(fixture.project, fixture.work))
                assertEquals(IncidentApplyResult.Applied, apply(fixture.review) { true })
                assertEquals(AFTER, Files.readString(fixture.project.resolve(TARGET)))
                assertEquals(1, notifications.size)
                assertEquals(listOf(fixture.project.resolve(TARGET).toString()), notifications.single().map { it.file.path })
                assertEquals(epoch + 1, backend.fileSystemEpoch.value)
            } finally {
                connection.dispose()
            }
        } finally {
            backend.close()
        }
    }

    @Test fun tabOpeningBeforeOverlayPreventsApplyUntilItsCapturedLeaseEnds() = runBlocking<Unit> {
        val fixture = fixture()
        val backend = IdeServicesBackend(fixture.engine)
        try {
            val apply = assertNotNull(backend.incidentPatchApplier(fixture.project, fixture.work))
            val release = backend.editor.retainDocument(fixture.project.resolve(TARGET).toString())
            assertIs<IncidentApplyResult.Pending>(apply(fixture.review) { true })
            assertEquals(BEFORE, Files.readString(fixture.project.resolve(TARGET)))
            release()
            assertEquals(IncidentApplyResult.Applied, apply(fixture.review) { true })
        } finally {
            backend.close()
        }
    }

    @Test fun dirtyOverlayOnAnotherVerifiedInputCannotBeOverwrittenOrIgnored() = runBlocking<Unit> {
        val fixture = fixture()
        val backend = IdeServicesBackend(fixture.engine)
        try {
            fixture.engine.updateDocument(fixture.project.resolve(OTHER), "class User { int unsaved; }\n")
            val apply = assertNotNull(backend.incidentPatchApplier(fixture.project, fixture.work))
            assertEquals(IncidentApplyResult.StaleSource, apply(fixture.review) { true })
            assertEquals(BEFORE, Files.readString(fixture.project.resolve(TARGET)))
            assertEquals("class User { int unsaved; }\n", fixture.engine.readCurrentText(fixture.project.resolve(OTHER)))
        } finally {
            backend.close()
        }
    }

    @Test fun oldApplyCallbackIsRejectedAfterProjectSwitch() = runBlocking<Unit> {
        val fixture = fixture()
        val backend = IdeServicesBackend(fixture.engine)
        try {
            val apply = assertNotNull(backend.incidentPatchApplier(fixture.project, fixture.work))
            val other = open(Files.createDirectory(temp.toRealPath().resolve("other-project")))
            backend.swapEngine(other)
            assertEquals(IncidentApplyResult.StaleSource, apply(fixture.review) { true })
            assertEquals(BEFORE, Files.readString(fixture.project.resolve(TARGET)))
        } finally {
            backend.close()
        }
    }

    @Test fun revokedAuthorityAndInFlightWorkspaceWorkCannotApply() = runBlocking<Unit> {
        val fixture = fixture()
        val backend = IdeServicesBackend(fixture.engine)
        try {
            val apply = assertNotNull(backend.incidentPatchApplier(fixture.project, fixture.work))
            assertFailsWith<IncidentRepairPending> { apply(fixture.review) { false } }
            val release = fixture.engine.retainSourceOperation()
            try {
                assertIs<IncidentApplyResult.Pending>(apply(fixture.review) { true })
                assertEquals(BEFORE, Files.readString(fixture.project.resolve(TARGET)))
            } finally {
                release()
            }
            assertEquals(IncidentApplyResult.Applied, apply(fixture.review) { true })
        } finally {
            backend.close()
        }
    }

    @Test fun idlePresentationDoesNotAuthorizeAnUnconfirmedBuildHost() = runBlocking<Unit> {
        val fixture = fixture()
        val runner = object : dev.ide.core.build.BuildRunner by fixture.engine.buildRunner {
            override fun sourceMutationBlocker(): String = "Build worker termination is not confirmed."
        }
        val backend = IdeServicesBackend(fixture.engine, buildRunnerFactory = { runner })
        try {
            val apply = assertNotNull(backend.incidentPatchApplier(fixture.project, fixture.work))
            assertEquals(dev.ide.ui.backend.RunStatus.Idle, runner.buildState.value.status)
            assertFailsWith<IncidentRepairPending> { apply(fixture.review) { true } }
            assertEquals(BEFORE, Files.readString(fixture.project.resolve(TARGET)))
        } finally {
            backend.close()
        }
    }

    private fun fixture(): Fixture {
        val base = temp.toRealPath()
        val root = Files.createDirectory(base.resolve("project"))
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve(TARGET), BEFORE)
        Files.writeString(root.resolve(OTHER), "class User {}\n")
        val engine = open(root)
        val work = Files.createDirectory(base.resolve("private"))
        val copy = Files.createDirectory(work.resolve("incident"))
        val original = snapshot(root)
        original.files.keys.forEach { relative ->
            val target = copy.resolve(relative)
            Files.createDirectories(target.parent)
            Files.copy(root.resolve(relative), target)
        }
        Files.writeString(copy.resolve(TARGET), AFTER)
        val projectId = IncidentDigests.bytes(root.toString().toByteArray(Charsets.UTF_8))
        val identity = IncidentIdentity("test.app", "build", "session", "event")
        val build = IncidentKnownBuild(projectId, identity.packageName, identity.buildId, "debug", original.revision,
            "source", "a".repeat(64), true)
        return Fixture(root, work, engine, IncidentPatchReview("run", identity, build, original, snapshot(copy), copy,
            listOf(FileChange(TARGET, BEFORE, AFTER)), emptyList()))
    }

    private fun open(root: Path) = IdeServices.openAt(root, SdkData("test", emptyList(), buildToolsPath = null, kind = PlatformKind.JVM), buildOnly = true)
    private fun snapshot(root: Path): IncidentSourceSnapshot = ReliabilitySourceSnapshot.capture(root).let {
        IncidentSourceSnapshot(it.revision, it.privateRelativeFiles)
    }
    private data class Fixture(val project: Path, val work: Path, val engine: IdeServices, val review: IncidentPatchReview)

    private companion object {
        const val TARGET = "src/Logic.java"
        const val OTHER = "src/User.java"
        const val BEFORE = "class Logic { int value = 0; }\n"
        const val AFTER = "class Logic { int value = 1; }\n"
    }
}
