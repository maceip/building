package dev.ide.workbench.publish

import java.io.File
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PlayPreflightTest {
    @TempDir lateinit var temp: File

    @Test fun `reads actual protobuf manifest and verifies signed jar entries`() {
        val file = PlayTestFixtures.signedBundle(temp)
        val artifact = PlayPreflight.inspectAab(file, PlayTestFixtures.PACKAGE)
        assertEquals(PlayTestFixtures.PACKAGE, artifact.packageName)
        assertEquals(42, artifact.versionCode)
        assertEquals("1.0", artifact.versionName)
        assertEquals(26, artifact.minSdk)
        assertEquals(35, artifact.targetSdk)
        assertEquals(setOf("android.permission.INTERNET"), artifact.permissions)
        assertEquals(64, artifact.uploadCertificateSha256.length)
        assertEquals(sha256(file), artifact.sha256)
        assertThrows(PlayPublishingException::class.java) { PlayPreflight.inspectAab(file, "some.other.app") }
    }

    @Test fun `unsigned and debuggable bundles cannot pass preflight`() {
        val unsigned = File(temp, "unsigned.aab")
        ZipOutputStream(unsigned.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("BundleConfig.pb")); zip.write(byteArrayOf(1)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("base/manifest/AndroidManifest.xml")); zip.write(PlayTestFixtures.manifest()); zip.closeEntry()
        }
        assertThrows(PlayPublishingException::class.java) { PlayPreflight.inspectAab(unsigned) }
        val debug = PlayTestFixtures.signedBundle(temp, debuggable = true)
        assertThrows(PlayPublishingException::class.java) { PlayPreflight.inspectAab(debug) }
    }

    @Test fun `standard Android debug signer is rejected even when manifest is not debuggable`() {
        val file = PlayTestFixtures.signedBundle(temp, debugSigner = true)
        val error = assertThrows(PlayPublishingException::class.java) { PlayPreflight.inspectAab(file) }
        assertTrue(error.message.contains("debug certificate"))
    }

    @Test fun `tampered signed content does not pass signature checks`() {
        val original = PlayTestFixtures.signedBundle(temp)
        val altered = File(temp, "tampered.aab")
        JarFile(original).use { jar ->
            ZipOutputStream(altered.outputStream()).use { zip ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    zip.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) {
                        val bytes = jar.getInputStream(entry).use { it.readBytes() }
                        if (entry.name.endsWith("protocol-fixture.bin")) bytes[0] = (bytes[0].toInt() xor 1).toByte()
                        zip.write(bytes)
                    }
                    zip.closeEntry()
                }
            }
        }
        assertThrows(PlayPublishingException::class.java) { PlayPreflight.inspectAab(altered) }
    }

    @Test fun `PNG validation is ART-compatible and applies actual asset constraints`() {
        val icon = PlayTestFixtures.png(temp, 512, 512, true, "icon.png")
        assertEquals(512, PlayPreflight.inspectImage(icon, PlayImageType.ICON).width)
        val feature = PlayTestFixtures.png(temp, 1024, 500, false, "feature.png")
        assertEquals("image/png", PlayPreflight.inspectImage(feature, PlayImageType.FEATURE_GRAPHIC).mimeType)
        val alphaFeature = PlayTestFixtures.png(temp, 1024, 500, true, "alpha.png")
        assertThrows(PlayPublishingException::class.java) { PlayPreflight.inspectImage(alphaFeature, PlayImageType.FEATURE_GRAPHIC) }
        val phone = PlayTestFixtures.png(temp, 1080, 1920, false, "phone.png")
        assertEquals(1920, PlayPreflight.inspectImage(phone, PlayImageType.PHONE_SCREENSHOTS).height)
        val tall = PlayTestFixtures.png(temp, 320, 1000, false, "tall.png")
        assertThrows(PlayPublishingException::class.java) { PlayPreflight.inspectImage(tall, PlayImageType.PHONE_SCREENSHOTS) }
        val bytes = feature.readBytes(); bytes[20] = (bytes[20].toInt() xor 1).toByte(); feature.writeBytes(bytes)
        assertThrows(PlayPublishingException::class.java) { PlayPreflight.inspectImage(feature, PlayImageType.FEATURE_GRAPHIC) }
    }

    @Test fun `listing length and track release constraints fail before HTTP`() {
        PlayPreflight.validateListing(PlayListing("en-US", "A".repeat(30), "B".repeat(80), "C".repeat(4000)))
        assertThrows(PlayPublishingException::class.java) { PlayPreflight.validateListing(PlayListing("en-US", "A".repeat(31), "B", "C")) }
        assertThrows(PlayPublishingException::class.java) { PlayPreflight.validatePackageName("../../invalid") }
        assertThrows(PlayPublishingException::class.java) { PlayPreflight.validateLanguage("en-US/evil") }
        assertThrows(PlayPublishingException::class.java) { PlayPreflight.validateTrack(PlayTrack("production", listOf(PlayRelease(listOf(42), PlayReleaseStatus.COMPLETED, userFraction = 0.1)))) }
        assertThrows(PlayPublishingException::class.java) { PlayPreflight.validateTrack(PlayTrack("production", listOf(PlayRelease(listOf(42), PlayReleaseStatus.IN_PROGRESS, userFraction = 1.0)))) }
        PlayPreflight.validateTrack(PlayTrack("production", listOf(PlayRelease(listOf(42), PlayReleaseStatus.IN_PROGRESS, userFraction = 0.1))))
    }

    @Test fun `checklist never infers policy declarations complete from manifest`() {
        val artifact = PlayPreflight.inspectAab(PlayTestFixtures.signedBundle(temp))
        val checklist = PlayReleaseChecklist.evaluate(artifact, PlayDistributionGoal.PRODUCTION)
        assertEquals(PlayGateEvidence.VERIFIED_LOCAL, checklist.single { it.id == "artifact" }.evidence)
        assertEquals(PlayGateEvidence.ACTION_REQUIRED, checklist.single { it.id == "policies" }.evidence)
        assertEquals(PlayGateEvidence.ACTION_REQUIRED, checklist.single { it.id == "production-access" }.evidence)
        assertTrue(checklist.single { it.id == "production-access" }.explanation.contains("approval"))
        val internal = PlayReleaseChecklist.evaluate(artifact, PlayDistributionGoal.INTERNAL_TESTING)
        assertEquals(PlayGateEvidence.NOT_APPLICABLE, internal.single { it.id == "data-safety" }.evidence)
        val attested = PlayReleaseChecklist.evaluate(artifact, PlayDistributionGoal.PRODUCTION, PlayAccountAttestations(requiredPolicyDeclarationsReviewed = true))
        assertEquals(PlayGateEvidence.USER_ATTESTED, attested.single { it.id == "policies" }.evidence)
    }
}
