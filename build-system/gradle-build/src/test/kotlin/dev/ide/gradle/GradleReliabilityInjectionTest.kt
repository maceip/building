package dev.ide.gradle

import dev.ide.build.ReliabilitySourceSnapshot
import dev.ide.platform.JsonReader
import dev.ide.platform.JsonWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GradleReliabilityInjectionTest {
    @Test fun missingConsentNeverTouchesRuntimeOrCallsProvider() = fixture { root, cache ->
        var calls = 0
        val provider = GradleReliabilityConfigurationProvider { calls++; pairing }
        assertTrue(GradleReliabilityInjection.launchArgs(root, cache, snapshot(), listOf(":app:assembleDebug"), null, provider).isEmpty())
        consent(root, enabled = true, granted = false)
        assertTrue(GradleReliabilityInjection.launchArgs(root, cache, snapshot(), listOf(":app:assembleDebug"), null, provider).isEmpty())
        assertEquals(0, calls)
    }

    @Test fun explicitDebugGeneratesPrivateBoundConfigAndChangesBuildIdPerInvocation() = fixture { root, cache ->
        consent(root)
        write(root, "app/src/Main.java", "class Main {}")
        val runtime = runtime(cache.resolve("runtime.jar"))
        val requests = mutableListOf<GradleReliabilityRequest>()
        val provider = GradleReliabilityConfigurationProvider { requests.add(it); pairing }
        val args = GradleReliabilityInjection.launchArgs(root, cache, snapshot(), listOf(":app:assembleDebug"), runtime, provider)
        val descriptor = descriptor(args)
        val row = JsonReader.obj(JsonReader.arr(descriptor["variants"]).single())!!
        val config = JsonReader.obj(JsonReader.parse(Files.readString(Path.of(row["configFile"].toString()))))!!
        assertEquals(":app", requests.single().moduleId)
        assertEquals("com.example.demo.debug", requests.single().packageName)
        assertEquals(ReliabilitySourceSnapshot.capture(root).revision, config["sourceRevision"])
        assertEquals(descriptor["buildId"], config["buildId"])
        assertEquals(nonce, config["pairingNonce"])
        assertFalse(args.any { nonce in it })
        assertTrue(args.containsAll(listOf("--no-configuration-cache", "--no-build-cache")))
        assertTrue(Path.of(row["outputDir"].toString()).startsWith(cache.toRealPath()))
        assertFalse(Path.of(row["outputDir"].toString()).startsWith(root.toRealPath()))
        assertTrue(Files.isRegularFile(Path.of(descriptor["runtimeArtifact"].toString())))
        assertFalse(Files.exists(root.resolve("app/build.gradle")))
        val second = descriptor(GradleReliabilityInjection.launchArgs(root, cache, snapshot(), listOf(":app:assembleDebug"), runtime, provider))
        assertNotEquals(descriptor["buildId"], second["buildId"])
        assertEquals(descriptor["sourceRevision"], second["sourceRevision"])
    }

    @Test fun genericAssembleHasDistinctArtifactIdsEvenWhenVariantsShareApplicationId() = fixture { root, cache ->
        consent(root, production = true)
        val model = snapshot(applicationId = "com.example.demo")
        val args = GradleReliabilityInjection.launchArgs(root, cache, model, listOf(":assemble"), runtime(cache.resolve("runtime.jar")), null)
        val rows = JsonReader.arr(descriptor(args)["variants"]).map { JsonReader.obj(it)!! }
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it["buildId"] }.distinct().size)
        for (row in rows) {
            val config = JsonReader.obj(JsonReader.parse(Files.readString(Path.of(row["configFile"].toString()))))!!
            assertEquals(row["buildId"], config["buildId"])
        }
    }

    @Test fun libraryTestsLintCleanAndUnconsentedReleaseDoNotInject() = fixture { root, cache ->
        consent(root)
        for (task in listOf(":lib:assembleDebug", ":app:testDebugUnitTest", ":app:lintDebug", ":app:clean", ":app:assembleRelease")) {
            assertTrue(GradleReliabilityInjection.launchArgs(root, cache, snapshot(), listOf(task), null,
                GradleReliabilityConfigurationProvider { error("must not call host for $task") }).isEmpty(), task)
        }
    }

    @Test fun productionRequiresSecondOptInAndNeverEmbedsPairingOrCallsHost() = fixture { root, cache ->
        consent(root, production = true)
        val args = GradleReliabilityInjection.launchArgs(root, cache, snapshot(), listOf(":app:bundleRelease"),
            runtime(cache.resolve("runtime.jar")), GradleReliabilityConfigurationProvider { error("release must be local only") })
        val row = JsonReader.obj(JsonReader.arr(descriptor(args)["variants"]).single())!!
        val config = Files.readString(Path.of(row["configFile"].toString()))
        assertTrue("\"enabled\":true" in config)
        assertTrue("\"pairingNonce\":\"\"" in config)
        assertFalse(nonce in config)
        assertFalse("ReliabilitySinkService" in config)
    }

    @Test fun releaseAarFileDependencyCanBeCopiedIntoPrivateCache() = fixture { root, cache ->
        consent(root, production = true)
        val jar = runtime(cache.resolve("classes.jar"))
        val aar = cache.resolve("runtime.aar")
        ZipOutputStream(Files.newOutputStream(aar)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.jar")); Files.copy(jar, zip); zip.closeEntry()
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("<manifest package=\"dev.ide.applog\"/>".toByteArray()); zip.closeEntry()
        }
        val args = GradleReliabilityInjection.launchArgs(root, cache, snapshot(), listOf(":app:assembleRelease"), aar, null)
        val copied = Path.of(descriptor(args)["runtimeArtifact"].toString())
        assertTrue(copied.fileName.toString().endsWith(".aar"))
        assertEquals(ReliabilitySourceSnapshot.sha256(aar), ReliabilitySourceSnapshot.sha256(copied))
    }

    @Test fun malformedOptInUnpairedSnapshotDebugRuntimeAndPrivateProjectCacheFailExplicitly() = fixture { root, cache ->
        write(root, ".building/reliability.json", """{"version":1,"pairingNonce":"private"}""")
        assertFailsWith<IllegalArgumentException> { GradleReliabilityInjection.readOptions(root) }
        consent(root)
        val jar = runtime(cache.resolve("runtime.jar"))
        assertFailsWith<IllegalStateException> {
            GradleReliabilityInjection.launchArgs(root, cache, snapshot(applicationId = null), listOf(":app:assembleDebug"), jar, null)
        }
        assertFailsWith<IllegalArgumentException> {
            GradleReliabilityInjection.launchArgs(root, root.resolve("build/cache"), snapshot(), listOf(":app:assembleDebug"), jar, null)
        }
        val bad = runtime(cache.resolve("bad.jar"), "dev/ide/reliability/test/FaultController.class")
        assertFailsWith<IllegalArgumentException> {
            GradleReliabilityInjection.launchArgs(root, cache, snapshot(), listOf(":app:assembleDebug"), bad, null)
        }
    }

    @Test fun postBuildCanonicalVerificationDetectsNewChangedAndRemovedSources() = fixture { root, cache ->
        consent(root)
        write(root, "app/src/Main.java", "class Main {}")
        val args = GradleReliabilityInjection.launchArgs(root, cache, snapshot(), listOf(":app:assembleDebug"),
            runtime(cache.resolve("runtime.jar")), null)
        GradleReliabilityInjection.verifySources(root, args)
        write(root, "app/src/New.java", "class New {}")
        assertFailsWith<IllegalStateException> { GradleReliabilityInjection.verifySources(root, args) }
        Files.delete(root.resolve("app/src/New.java"))
        GradleReliabilityInjection.verifySources(root, args)
        Files.delete(root.resolve("app/src/Main.java"))
        assertFailsWith<IllegalStateException> { GradleReliabilityInjection.verifySources(root, args) }
    }

    private fun snapshot(applicationId: String? = "com.example.demo.debug") = GradleProjectSnapshot.parse(JsonWriter.value(mapOf(
        "schemaVersion" to 2, "name" to "sample", "gradleVersion" to "8.6", "modules" to listOf(
            mapOf("name" to "app", "path" to ":app", "dir" to "app", "plugins" to listOf("com.android.application"),
                "application" to true, "variants" to listOf(
                    mapOf("name" to "debug", "buildType" to "debug", "assembleTask" to "assembleDebug", "debuggable" to true, "applicationId" to applicationId),
                    mapOf("name" to "release", "buildType" to "release", "assembleTask" to "assembleRelease", "debuggable" to false, "applicationId" to "com.example.demo"),
                )),
            mapOf("name" to "lib", "path" to ":lib", "dir" to "lib", "plugins" to listOf("com.android.library"),
                "application" to false, "variants" to listOf(mapOf("name" to "debug", "debuggable" to true))),
        ),
    )))
    private fun descriptor(args: List<String>) = JsonReader.obj(JsonReader.parse(Files.readString(Path.of(args.first().substringAfter('=')))))!!
    private fun consent(root: Path, enabled: Boolean = true, granted: Boolean = true, production: Boolean = false) =
        write(root, ".building/reliability.json", """{"version":1,"enabled":$enabled,"consentGranted":$granted,"productionEnabled":$production}""")
    private fun write(root: Path, name: String, text: String) = root.resolve(name).also {
        Files.createDirectories(it.parent); Files.writeString(it, text)
    }
    private fun runtime(path: Path, extra: String? = null): Path {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            for (name in listOfNotNull("dev/ide/applog/IdeLogBridgeProvider.class", "dev/ide/applog/Reliability.class", extra)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte(), 0, 0, 0, 52)); zip.closeEntry()
            }
        }
        return path
    }
    private fun fixture(block: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory("gradle-reliability-project")
        val cache = Files.createTempDirectory("gradle-reliability-private")
        try { block(root, cache) } finally { root.toFile().deleteRecursively(); cache.toFile().deleteRecursively() }
    }
    private val nonce = "a".repeat(64)
    private val pairing get() = """{"idePackage":"dev.ide.android","ideService":"dev.ide.android.reliability.ReliabilitySinkService","pairingNonce":"$nonce","ideSignerSha256":"${"b".repeat(64)}"}"""
}
