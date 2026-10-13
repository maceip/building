package dev.ide.core.reliability

import dev.ide.agent.IncidentApplyResult
import dev.ide.agent.impl.IncidentDigests
import dev.ide.platform.impl.ModelReadWriteLock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncidentSourceAccessTest {
    @TempDir lateinit var temp: Path

    @Test fun openingDocumentsAndConcurrentLeasesAreIndependentlyRetained() {
        val access = IncidentSourceAccess(ModelReadWriteLock())
        val first = access.retainDocument()
        val second = access.retainDocument()
        assertIs<IncidentApplyResult.Pending>(access.checkBuffers(temp.toRealPath(), emptyMap(), emptySet(), emptyMap()))
        first()
        first()
        assertIs<IncidentApplyResult.Pending>(access.checkBuffers(temp.toRealPath(), emptyMap(), emptySet(), emptyMap()))
        second()
        assertNull(access.checkBuffers(temp.toRealPath(), emptyMap(), emptySet(), emptyMap()))
    }

    @Test fun workspaceOperationExcludesApplyWithoutHoldingTheLockForItsLifetime() {
        val access = IncidentSourceAccess(ModelReadWriteLock())
        val release = access.retainOperation()
        var writeRan = false
        access.write { writeRan = true }
        assertTrue(writeRan)
        assertIs<IncidentApplyResult.Pending>(access.checkBuffers(temp.toRealPath(), emptyMap(), emptySet(), emptyMap()))
        release()
        release()
        assertNull(access.checkBuffers(temp.toRealPath(), emptyMap(), emptySet(), emptyMap()))
    }

    @Test fun dirtyNonTargetOverlayAndUnverifiedOverlayBothRefuse() {
        val root = temp.toRealPath()
        val file = Files.write(root.resolve("Other.kt"), "original".toByteArray())
        val expected = mapOf("Other.kt" to IncidentDigests.bytes("original".toByteArray()))
        val access = IncidentSourceAccess(ModelReadWriteLock())
        assertEquals(IncidentApplyResult.StaleSource, access.checkBuffers(root, expected, setOf("Target.kt"), mapOf(file to "unsaved")))
        assertIs<IncidentApplyResult.Pending>(access.checkBuffers(root, emptyMap(), setOf("Target.kt"), mapOf(file to "original")))
        assertNull(access.checkBuffers(root, expected, setOf("Target.kt"), mapOf(file to "original")))
        assertIs<IncidentApplyResult.Pending>(access.checkBuffers(root, expected, setOf("Other.kt"), mapOf(file to "original")))
    }

    @Test fun aLeaseCannotEnterDuringCheckedApplication() {
        val access = IncidentSourceAccess(ModelReadWriteLock())
        val attempted = CountDownLatch(1)
        val retained = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = access.write {
                val result = executor.submit<() -> Unit> {
                    attempted.countDown()
                    access.retainDocument().also { retained.countDown() }
                }
                assertTrue(attempted.await(5, TimeUnit.SECONDS))
                assertFalse(retained.await(100, TimeUnit.MILLISECONDS))
                assertNull(access.checkBuffers(temp.toRealPath(), emptyMap(), emptySet(), emptyMap()))
                result
            }
            val release = future.get(5, TimeUnit.SECONDS)
            assertIs<IncidentApplyResult.Pending>(access.checkBuffers(temp.toRealPath(), emptyMap(), emptySet(), emptyMap()))
            release()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun reopeningTheSameRootCannotForgetARetiredWriter() {
        val root = temp.toRealPath()
        val old = IncidentSourceAccess(ModelReadWriteLock(), root)
        val release = old.retainOperation()
        old.close()
        val reopened = IncidentSourceAccess(ModelReadWriteLock(), root)
        assertIs<IncidentApplyResult.Pending>(reopened.checkBuffers(root, emptyMap(), emptySet(), emptyMap()))
        release()
        assertNull(reopened.checkBuffers(root, emptyMap(), emptySet(), emptyMap()))
    }

    @Test fun unconfirmedRemoteWorkSurvivesSelectingAFreshEngine() {
        val root = temp.toRealPath()
        val old = IncidentSourceAccess(ModelReadWriteLock(), root)
        old.markExternalWorkUnconfirmed()
        old.close()
        val reopened = IncidentSourceAccess(ModelReadWriteLock(), root)
        assertIs<IncidentApplyResult.Pending>(reopened.checkBuffers(root, emptyMap(), emptySet(), emptyMap()))
    }

    @Test fun releasingAnOldEngineCannotOpenAReplacementEngineGate() {
        val previous = IncidentSourceAccess(ModelReadWriteLock())
        val next = IncidentSourceAccess(ModelReadWriteLock())
        val oldLease = previous.retainDocument()
        val newLease = next.retainDocument()
        previous.close()
        oldLease()
        assertEquals(IncidentApplyResult.StaleSource, previous.checkBuffers(temp.toRealPath(), emptyMap(), emptySet(), emptyMap()))
        assertIs<IncidentApplyResult.Pending>(next.checkBuffers(temp.toRealPath(), emptyMap(), emptySet(), emptyMap()))
        newLease()
        assertNull(next.checkBuffers(temp.toRealPath(), emptyMap(), emptySet(), emptyMap()))
    }
}
