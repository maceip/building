package dev.ide.android.support

import dev.ide.android.support.tasks.GenerateReliabilityAssetsTask
import dev.ide.android.support.tasks.InjectAppLogProviderTask
import dev.ide.android.support.tasks.ReliabilityAssets
import dev.ide.android.support.tasks.VerifyReliabilitySourcesTask
import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.SigningConfig
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.ReliabilitySourceSnapshot
import dev.ide.build.TaskName
import dev.ide.build.TaskResult
import dev.ide.build.VariantSelector
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.model.BuildSystemId
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.ModuleId
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.open
import dev.ide.testkit.testEnv
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** SDK-free consent, graph, privacy and asset tests; D8 integration remains in AppLogInjectTest. */
class ReliabilityInjectionTest {
    @Test fun absentAndLegacySettingsAreNotConsent() = temp { root ->
        assertFalse(ReliabilityProjectOptions.read(root).appliesTo(true))
        write(root, ".platform/preferences.json", "{\"injectAppLog\":true}")
        assertFalse(ReliabilityProjectOptions.read(root).appliesTo(true))
        write(root, ReliabilityProjectOptions.PROJECT_FILE, """{"version":1,"enabled":true}""")
        assertFalse(ReliabilityProjectOptions.read(root).appliesTo(true))
        consent(root)
        assertTrue(ReliabilityProjectOptions.read(root).appliesTo(true))
        assertFalse(ReliabilityProjectOptions.read(root).appliesTo(false))
        consent(root, production = true)
        assertTrue(ReliabilityProjectOptions.read(root).appliesTo(false))
    }

    @Test fun projectConfigurationRejectsSecretsAndNonBooleans() = temp { root ->
        write(root, ReliabilityProjectOptions.PROJECT_FILE, """{"version":1,"pairingNonce":"secret"}""")
        assertFailsWith<IllegalArgumentException> { ReliabilityProjectOptions.read(root) }
        write(root, ReliabilityProjectOptions.PROJECT_FILE, """{"version":1,"enabled":"true"}""")
        assertFailsWith<IllegalArgumentException> { ReliabilityProjectOptions.read(root) }
    }

    @Test fun buildGraphIsDefaultOffDebugOptInAndSeparatelyProductionOptIn() {
        testEnv("reliability-graph") { env ->
            val root = env.dir
            val codecs = FacetCodecRegistry().register(AndroidFacetCodec)
            val store = ProjectModel.open(root, env.platform, codecs)
            val types = ModuleTypeRegistry(env.platform.extensions)
            AndroidSupport.register(types, FacetCodecRegistry())
            store.workspace.beginModification().apply {
                addProject("sample", BuildSystemId.NATIVE, store.vfs.root()); commit()
            }
            val project = store.workspace.projects.single()
            project.beginModification().apply {
                addModule("app", types.resolve("android-app")).apply {
                    putFacet(AndroidFacet("com.example.demo", 34))
                }
                addModule("library", types.resolve("android-lib")).apply {
                    putFacet(AndroidFacet("com.example.library", 34, isApplication = false))
                }
                commit()
            }
            val privateCache = Files.createTempDirectory("reliability-private")
            try {
                var calls = 0
                val system = AndroidBuildSystem.subprocess(
                    AndroidSdk(root.resolve("fake/android.jar"), root.resolve("fake/build-tools")),
                    SigningConfig(root.resolve("fake/debug.ks"), "android", "android", "android"),
                    reliabilityConfiguration = {
                        calls++
                        ReliabilityBuildConfiguration(privateCache.resolve("runtime.jar"), privateCache)
                    },
                )
                fun graph(module: String = "app", variant: String = "debug", goal: BuildGoal = BuildGoal.PACKAGE) =
                    system.createBuildGraph(store.workspace.projects.single(), BuildRequest(listOf(ModuleId(module)), VariantSelector(variant), goal))
                fun names(module: String = "app", variant: String = "debug", goal: BuildGoal = BuildGoal.PACKAGE): List<String> {
                    val graph = graph(module, variant, goal)
                    graph.topologicalLevels() // Includes generated assets -> manifest -> compilation -> source check.
                    return graph.tasks.map { it.name.value }
                }
                assertFalse(names().any { "Reliability" in it })
                assertEquals(0, calls)
                consent(root)
                assertTrue(names().any { "injectReliabilityProvider" in it })
                assertFalse(names(variant = "release").any { "Reliability" in it })
                assertFalse(names(goal = BuildGoal.DEX).any { "Reliability" in it })
                assertFalse(names(goal = BuildGoal.COMPILE_ONLY).any { "Reliability" in it })
                assertFalse(names(module = "library").any { "Reliability" in it })
                consent(root, production = true)
                assertTrue(names(variant = "release").any { "injectReliabilityProvider" in it })
            } finally {
                privateCache.toFile().deleteRecursively()
            }
        }
    }

    @Test fun sourceRevisionIsDeterministicDirtyAwareAndDoesNotExposeSensitivePaths() = temp { root ->
        write(root, "private-company/app/src/Main.java", "class Main {}")
        write(root, "other/app/src/Main.java", "class Other {}")
        write(root, "build.gradle.kts", "plugins {}")
        write(root, "app/build/generated/Ignored.java", "class Ignored {}")
        write(root, ".git/config", "secret")
        write(root, "credentials.xml", "secret")
        write(root, "local.properties", "sdk.dir=/private/sdk")
        val before = ReliabilitySourceSnapshot.capture(root)
        assertEquals(3, before.files.size)
        assertTrue(before.privateRelativeFiles.containsKey("private-company/app/src/Main.java"))
        assertFalse(ReliabilityAssets.json(before.files).contains("private-company"))
        assertEquals(before.revision, ReliabilitySourceSnapshot.capture(root).revision)
        write(root, "credentials.xml", "changed secret")
        write(root, "app/build/generated/Ignored.java", "changed output")
        assertEquals(before.revision, ReliabilitySourceSnapshot.capture(root).revision)
        write(root, "private-company/app/src/Main.java", "class Main { int changed; }")
        assertNotEquals(before.revision, ReliabilitySourceSnapshot.capture(root).revision)
    }

    @Test fun debugAssetPairsExplicitlyWhileReleaseNeverCallsHostAndSourceDriftFails() = temp { root ->
        consent(root, production = true)
        write(root, "app/src/Main.java", "class Main {}")
        val cache = Files.createTempDirectory("reliability-private")
        try {
            val runtime = runtime(cache.resolve("runtime.jar"))
            var hostCalls = 0
            val config = ReliabilityBuildConfiguration(runtime, cache) { request ->
                hostCalls++
                assertEquals(ReliabilitySourceSnapshot.capture(root).revision, request.sourceRevision)
                pairingJson
            }
            fun task(debug: Boolean, id: String) = GenerateReliabilityAssetsTask(
                TaskName(":app:generate$id"), root, "app", "com.example.app", if (debug) "debug" else "release",
                debug, id, ReliabilityProjectOptions.read(root), config, cache.resolve("$id/assets"),
                cache.resolve("$id/recipient.txt"), emptyList(),
            )
            assertEquals(TaskResult.Success, runBlocking { task(true, "debug-id").execute(SimpleTaskContext()) })
            val debugAsset = Files.readString(cache.resolve("debug-id/assets/ide-reliability.json"))
            assertTrue(nonce in debugAsset)
            assertTrue("\"ideService\":\"dev.ide.android.reliability.ReliabilitySinkService\"" in debugAsset)
            assertEquals(TaskResult.Success, runBlocking { task(false, "release-id").execute(SimpleTaskContext()) })
            assertEquals(1, hostCalls)
            val releaseAsset = Files.readString(cache.resolve("release-id/assets/ide-reliability.json"))
            assertFalse(nonce in releaseAsset)
            assertTrue("\"pairingNonce\":\"\"" in releaseAsset)
            assertFalse(Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).anyMatch { nonce in Files.readString(it) } })
            write(root, "app/src/Main.java", "class Main { int changed; }")
            val result = runBlocking {
                VerifyReliabilitySourcesTask(TaskName(":app:verify"), root,
                    cache.resolve("debug-id/assets/ide-reliability-sources.json"), ReliabilityProjectOptions.read(root))
                    .execute(SimpleTaskContext())
            }
            assertTrue(result is TaskResult.Failed)
        } finally { cache.toFile().deleteRecursively() }
    }

    @Test fun generatedAssetsCannotUseProjectCacheAndRuntimeCannotContainDebugControls() = temp { root ->
        assertFailsWith<IllegalArgumentException> { ReliabilityAssets.privateDirectory(root.resolve("build/reliability"), root) }
        val jar = runtime(root.resolve("bad.jar"), "dev/ide/reliability/test/FaultController.class")
        assertFailsWith<IllegalArgumentException> { ReliabilityAssets.validateRuntime(jar) }
    }

    @Test fun reliabilityProviderUsesExplicitPackageQueryAndMetadataNotActionDiscovery() = temp { root ->
        val manifest = write(root, "input.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:hasCode="false"><provider android:name="dev.ide.applog.IdeLogBridgeProvider" android:exported="true" android:process=":other"/></application></manifest>""")
        val recipient = write(root, "recipient.txt", "dev.ide.android")
        val output = root.resolve("out.xml")
        val result = runBlocking {
            InjectAppLogProviderTask(TaskName(":app:inject"), manifest, ReliabilityProjectOptions.PROVIDER_CLASS,
                "com.example.app.ide-reliability", null, output, "ide-reliability.json", recipient)
                .execute(SimpleTaskContext())
        }
        assertEquals(TaskResult.Success, result)
        val xml = Files.readString(output)
        assertTrue("android:exported=\"false\"" in xml)
        assertTrue("android:initOrder=\"100\"" in xml)
        assertTrue("android:hasCode=\"true\"" in xml)
        assertTrue("dev.ide.reliability.CONFIG_ASSET" in xml)
        assertTrue("<package android:name=\"dev.ide.android\"" in xml)
        assertFalse("<intent" in xml)
        assertFalse("android:process" in xml)
        assertFalse("pairingNonce" in xml)
    }

    private fun runtime(file: Path, extraClass: String? = null): Path {
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            for (name in listOfNotNull("dev/ide/applog/IdeLogBridgeProvider.class", "dev/ide/applog/Reliability.class", extraClass)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte(), 0, 0, 0, 52))
                zip.closeEntry()
            }
        }
        return file
    }
    private fun consent(root: Path, production: Boolean = false) = write(root, ReliabilityProjectOptions.PROJECT_FILE,
        """{"version":1,"enabled":true,"consentGranted":true,"productionEnabled":$production}""")
    private fun write(root: Path, relative: String, value: String): Path = root.resolve(relative).also {
        Files.createDirectories(it.parent); Files.writeString(it, value)
    }
    private fun temp(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("reliability-test")
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }
    private val nonce = "a".repeat(64)
    private val pairingJson get() = """{"idePackage":"dev.ide.android","ideService":"dev.ide.android.reliability.ReliabilitySinkService","pairingNonce":"$nonce","ideSignerSha256":"${"b".repeat(64)}"}"""
}
