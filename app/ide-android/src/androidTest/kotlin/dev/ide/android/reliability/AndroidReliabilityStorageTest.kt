package dev.ide.android.reliability

import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.workbench.reliability.PairingRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AndroidReliabilityStorageTest {
    @Test fun privateEncryptedPairingConfigSurvivesIndependentOpenAndDirectoryFsync() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "reliability-keystore-test-")
        try {
            val path = directory.resolve("pairings.enc")
            val first = PairingRegistry(EncryptedPairingStorage(context, path))
            val second = PairingRegistry(EncryptedPairingStorage(context, path))
            first.setDebugCaptureEnabled(true)
            val grant = first.createPendingDebugPairing("sample.fixture", "test-build", "abc123")
            assertTrue(second.isDebugCaptureEnabled())
            assertEquals("test-build", second.list().single().buildId)
            val raw = Files.readAllBytes(path).toString(Charsets.ISO_8859_1)
            assertFalse(raw.contains("sample.fixture"))
            assertFalse(raw.contains(grant.pairingNonce))
            second.revoke("sample.fixture", "test-build")
            assertEquals(dev.ide.workbench.reliability.PairingStatus.REVOKED, first.list().single().status)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun apkPreviewCopyIsIndependentFromMutableSourceAndCanBeDiscarded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Files.createTempFile(context.cacheDir.toPath(), "reliability-source-", ".apk")
        try {
            Files.write(source, byteArrayOf(1, 2, 3, 4))
            val snapshot = ReliabilityApkSnapshot.create(context, source.toString())
            try {
                Files.write(source, byteArrayOf(9))
                assertTrue(Files.readAllBytes(snapshot).contentEquals(byteArrayOf(1, 2, 3, 4)))
            } finally { ReliabilityApkSnapshot.discard(snapshot.toString()) }
            assertFalse(Files.exists(snapshot))
        } finally { Files.deleteIfExists(source) }
    }
}
