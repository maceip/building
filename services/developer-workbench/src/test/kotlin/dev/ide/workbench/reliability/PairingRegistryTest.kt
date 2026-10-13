package dev.ide.workbench.reliability

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PairingRegistryTest {
    @Test fun `consent defaults off and pending nonce is hashed with inactive delivery until activation`() {
        val disk = MemoryReliabilityStorage()
        val store = PairingRegistry(disk, testClock)
        assertFalse(store.debugCaptureEnabled.value)
        assertFailsWith<ReliabilityException> { store.createPendingDebugPairing(testPackage, "build-1", "abc123") }
        store.setDebugCaptureEnabled(true)
        val grant = store.createPendingDebugPairing(testPackage, "build-1", "abc123")
        assertEquals(64, grant.pairingNonce.length)
        assertFalse(disk.bytes!!.toString(Charsets.UTF_8).contains(grant.pairingNonce))
        assertFalse(grant.toString().contains(grant.pairingNonce))
        assertFailsWith<ReliabilityException> { authorized(store, grant) }
        store.verifyPendingAsset(testPackage, "build-1", "abc123", grant.pairingNonce)
        assertFailsWith<ReliabilityException> { store.verifyPendingAsset(testPackage, "build-1", "abc123", "f".repeat(64)) }
        store.activateDebugPairing(testPackage, "build-1", setOf(testSigner), "dev.ide.test")
        assertTrue(authorized(store, grant))
        assertTrue(authorized(PairingRegistry(disk, testClock), grant))
    }

    @Test fun `revocation survives process restart and global pause blocks debug but not explicit consent`() {
        val f = ReceiverFixture()
        f.pairings.setDebugCaptureEnabled(false)
        assertFailsWith<ReliabilityException> { authorized(f.pairings, f.secret) }
        f.pairings.setDebugCaptureEnabled(true)
        f.pairings.revoke(testPackage, "build-1")
        assertFailsWith<ReliabilityException> { authorized(PairingRegistry(f.pairingDisk), f.secret) }
        val explicit = f.pairings.prepareExplicitPairing(testPackage, "release-1", "abc123", setOf(testSigner), "dev.ide.test", true)
        f.pairings.setDebugCaptureEnabled(false)
        assertTrue(authorized(f.pairings, explicit))
    }

    @Test fun `different app UID signer shared UID and actual installer all fail closed`() {
        val f = ReceiverFixture()
        for (caller in listOf(
            testCaller.copy(packages = setOf("other.app")),
            testCaller.copy(packages = setOf(testPackage, "shared.app")),
            testCaller.copy(signerSha256 = setOf("b".repeat(64)), signerHistorySha256 = setOf("b".repeat(64))),
            testCaller.copy(installerPackage = "other.installer"),
            testCaller.copy(installerPackage = null),
            testCaller.copy(pid = 0),
        )) assertFailsWith<ReliabilityException> { authorized(f.pairings, f.secret, caller) }
    }

    @Test fun `verified single signer rotation is accepted without accepting arbitrary or multisigner additions`() {
        val f = ReceiverFixture()
        val rotated = testCaller.copy(signerSha256 = setOf("b".repeat(64)), signerHistorySha256 = setOf(testSigner, "b".repeat(64)))
        assertTrue(authorized(f.pairings, f.secret, rotated))
        assertFailsWith<ReliabilityException> { authorized(f.pairings, f.secret, rotated.copy(signerSha256 = setOf(testSigner, "b".repeat(64)))) }
    }

    @Test fun `new builds retain old grants but same build never silently rotates`() {
        val f = ReceiverFixture()
        val next = f.pairings.createPendingDebugPairing(testPackage, "build-2", "abc456")
        assertNotEquals(f.secret.pairingNonce, next.pairingNonce)
        assertTrue(authorized(f.pairings, f.secret))
        assertEquals(ReliabilityError.ID_CONFLICT, assertFailsWith<ReliabilityException> {
            f.pairings.createPendingDebugPairing(testPackage, "build-1", "abc123")
        }.code)
    }

    @Test fun `missing signing pins never become active and null installer requires deliberate policy`() {
        val f = ReceiverFixture()
        assertFailsWith<ReliabilityException> { f.pairings.prepareDebugPairing(testPackage, "build-2", "", emptySet(), "dev.ide.test") }
        assertFailsWith<ReliabilityException> { f.pairings.prepareDebugPairing(testPackage, "build-2", "", setOf(testSigner), null) }
        val explicitFixture = f.pairings.prepareDebugPairing(testPackage, "build-2", "abc123", setOf(testSigner), null, true)
        assertTrue(authorized(f.pairings, explicitFixture, testCaller.copy(installerPackage = null)))
        assertFailsWith<ReliabilityException> { authorized(f.pairings, explicitFixture) }
    }

    @Test fun `failed revocation persistence fail closes all delivery in current process`() {
        val f = ReceiverFixture()
        f.pairingDisk.failBeforeWrite = true
        assertFailsWith<ReliabilityException> { f.pairings.revoke(testPackage, "build-1") }
        assertFailsWith<ReliabilityException> { authorized(f.pairings, f.secret) }
    }

    private fun authorized(store: PairingRegistry, secret: PairingSecret, caller: IncidentCaller = testCaller) =
        store.authorizeHello(caller, secret.pairing.packageName, secret.pairing.buildId, secret.pairing.sourceRevision, secret.pairingNonce) { true }
}
