package dev.ide.workbench.reliability

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingMultiProcessTest {
    @TempDir lateinit var directory: Path
    private fun registry() = PairingRegistry(AtomicReliabilityFile(directory.resolve("pairings.json"), PairingRegistry.MAX_CONFIG_BYTES), testClock)

    @Test fun `two separately opened registries refresh consent appends activation and revocation`() {
        val ui = registry()
        val daemon = registry()
        assertFalse(daemon.isDebugCaptureEnabled())
        ui.setDebugCaptureEnabled(true)
        assertTrue(daemon.isDebugCaptureEnabled())
        val first = daemon.createPendingDebugPairing(testPackage, "build-1", "abc123")
        ui.createPendingDebugPairing(testPackage, "build-2", "abc456")
        assertEquals(setOf("build-1", "build-2"), daemon.list().map { it.buildId }.toSet())
        ui.activateDebugPairing(testPackage, "build-1", setOf(testSigner), "dev.ide.test")
        assertTrue(daemon.authorizeHello(testCaller, testPackage, "build-1", "abc123", first.pairingNonce) { true })
        ui.revoke(testPackage, "build-1")
        assertFailsWith<ReliabilityException> { daemon.authorizeHello(testCaller, testPackage, "build-1", "abc123", first.pairingNonce) { true } }
        daemon.setDebugCaptureEnabled(false)
        assertFalse(ui.isDebugCaptureEnabled())
        assertEquals(2, ui.list().size)
        assertEquals(PairingStatus.REVOKED, ui.list().single { it.buildId == "build-1" }.status)
    }

    @Test fun `independent file clients concurrently append without lost updates`() {
        val first = registry()
        val second = registry()
        first.setDebugCaptureEnabled(true)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val a = executor.submit {
                start.await()
                repeat(8) { first.createPendingDebugPairing(testPackage, "a-$it", "abc123") }
            }
            val b = executor.submit {
                start.await()
                repeat(8) { second.createPendingDebugPairing(testPackage, "b-$it", "abc123") }
            }
            start.countDown()
            a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS)
            assertEquals(16, registry().list().size)
        } finally { executor.shutdownNow() }
    }

    @Test fun `file lock covers authorized incident commit and completed revocation prevents later delivery`() {
        val ui = registry()
        val daemon = registry()
        ui.setDebugCaptureEnabled(true)
        val secret = ui.prepareDebugPairing(testPackage, "build-1", "abc123", setOf(testSigner), "dev.ide.test")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val revokeStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val commit = executor.submit<Boolean> {
                daemon.authorizeHello(testCaller, testPackage, "build-1", "abc123", secret.pairingNonce) {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val revoke = executor.submit {
                revokeStarted.countDown()
                ui.revoke(testPackage, "build-1")
            }
            assertTrue(revokeStarted.await(10, TimeUnit.SECONDS))
            assertFalse(revoke.isDone)
            release.countDown()
            assertTrue(commit.get(10, TimeUnit.SECONDS))
            revoke.get(10, TimeUnit.SECONDS)
            assertFailsWith<ReliabilityException> { daemon.authorizeHello(testCaller, testPackage, "build-1", "abc123", secret.pairingNonce) { true } }
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
