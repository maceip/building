package dev.ide.android.reliability.repair

import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.agent.FileChange
import dev.ide.agent.IncidentApplyResult
import dev.ide.agent.IncidentIdentity
import dev.ide.agent.IncidentKnownBuild
import dev.ide.agent.IncidentPatchReview
import dev.ide.agent.IncidentSourceSnapshot
import dev.ide.agent.impl.IncidentDigests
import dev.ide.build.ReliabilitySourceSnapshot
import dev.ide.core.reliability.IncidentPatchApplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CancellationException

class IncidentPatchApplierDeviceTest {
    @Test fun realAndroidAtomicRenamePreservesBytesPermissionsAndPublishesOnce() = fixture { root, work, review ->
        val target = root.resolve(TARGET)
        val mode = PosixFilePermissions.fromString("rw-r-----")
        Files.setPosixFilePermissions(target, mode)
        var notifications = 0
        val result = IncidentPatchApplier(root, work).apply(review, {}, { _, _ -> null }) {
            notifications++
            assertEquals(listOf(target), it)
        }
        assertEquals(IncidentApplyResult.Applied, result)
        assertEquals(AFTER, read(target))
        assertEquals(mode, Files.getPosixFilePermissions(target))
        assertEquals(1, notifications)
        assertEquals(review.repairedSnapshot, snapshot(root))
    }

    @Test fun sourceChangedAfterVerificationLeavesOriginalUntouched() = fixture { root, work, review ->
        val target = root.resolve(TARGET)
        val userEdit = "class Logic { int userEdit; }\n"
        Files.write(target, userEdit.toByteArray(Charsets.UTF_8))
        assertEquals(IncidentApplyResult.StaleSource,
            IncidentPatchApplier(root, work).apply(review, {}, { _, _ -> null }, { error("No commit expected") }))
        assertEquals(userEdit, read(target))
    }

    @Test fun cancelledPrecommitRemovesStagingWithoutChangingSource() = fixture { root, work, review ->
        var cancelled = false
        try {
            IncidentPatchApplier(root, work).apply(review, {
                val staged = Files.list(root.resolve("src")).use { files ->
                    files.anyMatch { it.fileName.toString().startsWith(".incident-patch-") }
                }
                if (staged) throw CancellationException()
            }, { _, _ -> null }, { error("No commit expected") })
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(BEFORE, read(root.resolve(TARGET)))
        assertEquals(1L, Files.list(root.resolve("src")).use { it.count() })
    }

    @Test fun openBufferGateKeepsVerifiedCopyPending() = fixture { root, work, review ->
        val result = IncidentPatchApplier(root, work).apply(review, {}, { _, _ ->
            IncidentApplyResult.Pending("An editor tab is open.")
        }, { error("No commit expected") })
        assertTrue(result is IncidentApplyResult.Pending)
        assertEquals(BEFORE, read(root.resolve(TARGET)))
        assertEquals(AFTER, read(review.isolatedRoot.resolve(TARGET)))
    }

    private fun fixture(test: (Path, Path, IncidentPatchReview) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = Files.createTempDirectory(context.cacheDir.toPath().toRealPath(), "incident-apply-test-")
        try {
            val root = Files.createDirectory(base.resolve("project"))
            val work = Files.createDirectory(base.resolve("private"))
            val copy = Files.createDirectory(work.resolve("copy"))
            Files.createDirectory(root.resolve("src"))
            Files.createDirectory(copy.resolve("src"))
            Files.write(root.resolve(TARGET), BEFORE.toByteArray(Charsets.UTF_8))
            Files.write(copy.resolve(TARGET), AFTER.toByteArray(Charsets.UTF_8))
            val original = snapshot(root)
            val identity = IncidentIdentity("fixture.app", "build", "session", "event")
            val known = IncidentKnownBuild(IncidentDigests.bytes(root.toString().toByteArray(Charsets.UTF_8)),
                identity.packageName, identity.buildId, "debug", original.revision, "source", "a".repeat(64), true)
            test(root, work, IncidentPatchReview("run", identity, known, original, snapshot(copy), copy,
                listOf(FileChange(TARGET, BEFORE, AFTER)), emptyList()))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    private fun read(path: Path) = Files.readAllBytes(path).toString(Charsets.UTF_8)
    private fun snapshot(root: Path) = ReliabilitySourceSnapshot.capture(root).let {
        IncidentSourceSnapshot(it.revision, it.privateRelativeFiles)
    }
    private companion object {
        const val TARGET = "src/Logic.java"
        const val BEFORE = "class Logic { int value = 0; }\n"
        const val AFTER = "class Logic { int value = 1; }\n"
    }
}
