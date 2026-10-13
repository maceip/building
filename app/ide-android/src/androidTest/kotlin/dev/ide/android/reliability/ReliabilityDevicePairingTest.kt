package dev.ide.android.reliability

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.workbench.reliability.IncidentFilter
import dev.ide.workbench.reliability.PairingStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.security.MessageDigest

/** Explicit adb-fixture preparation only; never called by normal tests, builds or product code. */
@RunWith(AndroidJUnit4::class)
class ReliabilityDevicePairingTest {
    @Test fun prepareFixtureGrant() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in to the disposable physical fixture", arguments.getString("authorizedFixture") == FIXTURE &&
            arguments.getString("allowLocalFixturePairing") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rawPath = requireNotNull(arguments.getString("fixtureApkPath") ?: arguments.getString("fixtureApk")) { "fixtureApkPath is required" }
        val path = Paths.get(rawPath)
        require(path.isAbsolute && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Use a private cached fixture APK" }
        require(path.toRealPath().startsWith(context.cacheDir.toPath().toRealPath())) { "Fixture APK must be in the IDE's private cache" }
        require(Files.size(path) in 1..(512L * 1_024 * 1_024)) { "Fixture APK exceeds test bounds" }
        val buildId = requireNotNull(arguments.getString("fixtureBuildId")) { "fixtureBuildId is required" }
        val revision = arguments.getString("fixtureSourceRevision") ?: "fixture-state-v1"
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(path.toString(),
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            ?: error("Fixture APK cannot be inspected")
        assertEquals(FIXTURE, info.packageName)
        assertTrue("Fixture must be debuggable", info.applicationInfo!!.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo!!.apkContentsSigners else info.signatures!!
        val pins = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }.toSet()
        assertFalse("A real signing identity is required", pins.isEmpty())
        val host = ReliabilityHostProvider.get(context)
        host.pairings.setDebugCaptureEnabled(true)
        val grant = host.prepareDebugPairing(FIXTURE, buildId, revision, pins, "com.android.shell",
            allowUnknownInstaller = arguments.getString("allowUnknownFixtureInstaller") == "true")
        val target = context.cacheDir.toPath().resolve("reliability-fixture-pairing.json")
        androidReliabilityFile(target, 16_384).write(grant.assetJson().toByteArray(Charsets.UTF_8))
        // The nonce is deliberately never put in logcat, stdout or instrumentation arguments.
        println("Fixture pairing asset: $target")
        assertEquals(PairingStatus.ACTIVE, host.pairings.list().single { it.packageName == FIXTURE && it.buildId == buildId }.status)
    }

    /** Non-secret observation of the host inbox after the separate-UID SDK has run. */
    @Test fun snapshotFixtureInbox() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in to the disposable physical fixture", arguments.getString("authorizedFixture") == FIXTURE &&
            arguments.getString("allowLocalFixturePairing") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ReliabilityHostProvider.get(context).repository
        val summaries = repository.list(IncidentFilter(packageName = FIXTURE, limit = 2_048))
        val expectedType = arguments.getString("expectedIncidentType")
        if (expectedType != null) assertTrue("Expected real fixture evidence is absent from the durable host inbox", summaries.any { it.type == expectedType })
        val expectedBuild = arguments.getString("fixtureBuildId")
        if (expectedBuild != null && expectedType != null) assertTrue("Expected fixture build has no matching incident", summaries.any { it.type == expectedType && it.key.buildId == expectedBuild })
        val json = buildJsonObject {
            put("version", 1)
            put("count", summaries.size)
            put("events", JsonArray(summaries.map { item -> buildJsonObject {
                put("packageName", item.key.packageName); put("buildId", item.key.buildId); put("sourceRevision", item.sourceRevision)
                put("sessionId", item.key.sessionId); put("eventId", item.key.eventId); put("type", item.type)
                put("timestamp", item.timestamp); put("state", item.state.name)
            } }))
        }.toString()
        val target = context.cacheDir.toPath().resolve("reliability-fixture-inbox.json")
        androidReliabilityFile(target, 1_048_576).write(json.toByteArray(Charsets.UTF_8))
        println("Fixture inbox snapshot: $target (${summaries.size} events)")
    }

    companion object { private const val FIXTURE = "dev.ide.reliability.fixture" }
}
