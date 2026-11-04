package dev.ide.android.support.manifest

import dev.ide.android.support.tasks.ManifestMergeTask
import dev.ide.build.TaskName
import dev.ide.build.TaskResult
import dev.ide.build.engine.SimpleTaskContext
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The edge-to-edge advisory ([ManifestMergeTask.edgeToEdgeAdvisory]): warn when an app relies on the build
 * config for a target that enforces edge-to-edge (Android 15+) but declares no `targetSdkVersion` of its own.
 */
class ManifestMergeTaskTest {

    private val noSdkXml = """<manifest package="com.example.app"><application/></manifest>"""
    private val withTargetXml = """<manifest package="com.example.app"><uses-sdk android:targetSdkVersion="35"/></manifest>"""

    @Test
    fun changedBuildTypeFlagChangesTaskFingerprintAndGeneratedManifestOnly() {
        val root = Files.createTempDirectory("manifest-debuggable")
        try {
            val primary = root.resolve("AndroidManifest.xml")
            val source = """<manifest xmlns:android="${ManifestMerger.ANDROID_NS}" package="com.example.app"><application android:debuggable="false"/></manifest>"""
            Files.writeString(primary, source)
            val out = root.resolve("merged/AndroidManifest.xml")
            fun task(debuggable: Boolean) = ManifestMergeTask(
                TaskName(":app:processManifestCustom"), primary, emptyList(), emptyMap(), 26, 34,
                out, "com.example.app", debuggable = debuggable,
            )
            val debug = task(true)
            val release = task(false)
            assertNotEquals(debug.inputs.fingerprint(), release.inputs.fingerprint(), "Build-type changes must invalidate cached merge output")
            assertEquals(TaskResult.Success, runBlocking { debug.execute(SimpleTaskContext()) })
            assertTrue("android:debuggable=\"true\"" in Files.readString(out))
            assertEquals(TaskResult.Success, runBlocking { release.execute(SimpleTaskContext()) })
            assertTrue("android:debuggable=\"false\"" in Files.readString(out))
            assertEquals(source, Files.readString(primary), "Never rewrite the developer's manifest")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun warnsWhenFacetTargetForcesEdgeToEdgeAndManifestOmitsTarget() {
        val advisory = ManifestMergeTask.edgeToEdgeAdvisory(ManifestMergeTask.EDGE_TO_EDGE_SDK, noSdkXml)
        assertNotNull(advisory)
        assertTrue("edge-to-edge" in advisory)
        assertTrue("35" in advisory)
    }

    @Test
    fun warnsForAnyTargetAtOrAboveTheThreshold() {
        assertNotNull(ManifestMergeTask.edgeToEdgeAdvisory(36, noSdkXml))
    }

    @Test
    fun silentBelowTheThreshold() {
        assertNull(ManifestMergeTask.edgeToEdgeAdvisory(34, noSdkXml))
    }

    @Test
    fun silentWhenTheAppDeclaresItsOwnTargetSdkVersion() {
        // The app owns its target explicitly, so the value is visible in the manifest and not a surprise.
        assertNull(ManifestMergeTask.edgeToEdgeAdvisory(ManifestMergeTask.EDGE_TO_EDGE_SDK, withTargetXml))
    }
}
