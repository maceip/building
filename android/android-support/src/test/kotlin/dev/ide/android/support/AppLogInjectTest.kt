package dev.ide.android.support

import dev.ide.android.support.tools.AndroidAppLogRuntime
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.model.BuildSystemId
import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleId
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.ProjectModelStore
import dev.ide.platform.impl.PlatformCore
import dev.ide.testkit.testEnv
import dev.ide.testkit.writeSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.ide.model.impl.open

/**
 * Proves the debug-only app-log injection: when an [AndroidAppLogRuntime] is supplied, a DEBUG build weaves
 * the log-bridge `<provider>` into the linked manifest and dexes the runtime classes into the APK, while a
 * RELEASE build (debuggable = false) is left byte-for-byte untouched even though the same runtime is supplied.
 *
 * SDK-gated (skipped, not failed, when no Android SDK is installed) and requires the compiled `:applog-runtime`
 * jar handed in via `-Dapplog.runtime.jar` (wired by the build script).
 */
class AppLogInjectTest {

    @Test
    fun instrumentsDebugButNeverRelease() {
        val sdk = assumeAndroidSdk()
        val runtimeJar = resolveRuntimeJar()
        assumeTrue(runtimeJar != null, "applog-runtime jar not provided (-Dapplog.runtime.jar); skipping")
        runtimeJar!!

        val runtime = AndroidAppLogRuntime(
            runtimeJar,
            AndroidAppLogRuntime.DEFAULT_PROVIDER_CLASS,
            AndroidAppLogRuntime.DEFAULT_AUTHORITY_SUFFIX,
        )
        testEnv("android-applog") { env ->
            val dir = env.dir
            val platform = env.platform
            val store = buildAppWorkspace(dir, platform)
            val project = store.workspace.projects.single()
            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            // The runtime is supplied unconditionally; only the build type's debuggable flag decides injection.
            val buildSystem = AndroidBuildSystem.subprocess(sdk, signing, appLogRuntime = { runtime })
            val cache = BuildCache(dir.resolve(".caches/build"))

            // --- Debug: injected. ---
            buildVariant(buildSystem, project, "debug", cache)
            val debugApk = dir.resolve("app/build/outputs/apk/debug/app-debug.apk")
            val instrumented = dir.resolve("app/build/intermediates/android/debug/instrumented-manifest/AndroidManifest.xml")
            assertTrue(Files.isRegularFile(instrumented), "debug build should produce an instrumented manifest")
            val manifestXml = Files.readString(instrumented)
            assertTrue(PROVIDER in manifestXml, "instrumented manifest lacks the log-bridge provider:\n$manifestXml")
            assertTrue("$APPLICATION_ID.${AndroidAppLogRuntime.DEFAULT_AUTHORITY_SUFFIX}" in manifestXml,
                "instrumented manifest lacks the per-app authority:\n$manifestXml")
            assertTrue(AndroidAppLogRuntime.DEFAULT_SINK_ACTION in manifestXml,
                "instrumented manifest lacks the sink-service <queries> intent (API 30+ visibility):\n$manifestXml")
            assertTrue(dexContainsType(debugApk, PROVIDER_DESC), "debug APK dex lacks $PROVIDER")
            assertApkDebuggable(sdk.aapt2, debugApk, true)

            // --- Release: never touched (debuggable = false), even with the runtime present. ---
            buildVariant(buildSystem, project, "release", cache)
            val releaseApk = dir.resolve("app/build/outputs/apk/release/app-release.apk")
            val relInstrumented = dir.resolve("app/build/intermediates/android/release/instrumented-manifest/AndroidManifest.xml")
            assertTrue(!Files.exists(relInstrumented), "release build must not instrument the manifest")
            assertTrue(!dexContainsType(releaseApk, PROVIDER_DESC), "release APK must not contain the log bridge")
            assertApkDebuggable(sdk.aapt2, releaseApk, false)
        }
    }

    /**
     * A NativeActivity app has no sources of its own and declares `hasCode="false"`. Its debug APK must
     * still carry the bridge in a dex the platform loads, or the Logcat tab stays empty for it.
     */
    @Test
    fun instrumentsAnAppWithNoCodeOfItsOwn() {
        val sdk = assumeAndroidSdk()
        val runtimeJar = resolveRuntimeJar()
        assumeTrue(runtimeJar != null, "applog-runtime jar not provided (-Dapplog.runtime.jar); skipping")
        runtimeJar!!

        val runtime = AndroidAppLogRuntime(
            runtimeJar,
            AndroidAppLogRuntime.DEFAULT_PROVIDER_CLASS,
            AndroidAppLogRuntime.DEFAULT_AUTHORITY_SUFFIX,
        )
        testEnv("android-applog-native") { env ->
            val dir = env.dir
            val store = buildAppWorkspace(dir, env.platform, manifest = NATIVE_MANIFEST, activity = null)
            val project = store.workspace.projects.single()
            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val buildSystem = AndroidBuildSystem.subprocess(sdk, signing, appLogRuntime = { runtime })
            val cache = BuildCache(dir.resolve(".caches/build"))

            buildVariant(buildSystem, project, "debug", cache)
            val debugApk = dir.resolve("app/build/outputs/apk/debug/app-debug.apk")
            val instrumented = dir.resolve("app/build/intermediates/android/debug/instrumented-manifest/AndroidManifest.xml")
            val manifestXml = Files.readString(instrumented)
            assertTrue(PROVIDER in manifestXml, "instrumented manifest lacks the log-bridge provider:\n$manifestXml")
            assertTrue("hasCode=\"false\"" !in manifestXml, "the bridge's dex must be loadable:\n$manifestXml")
            assertTrue(dexContainsType(debugApk, PROVIDER_DESC), "debug APK dex lacks $PROVIDER")
        }
    }

    /** Real D8/APK packaging of the consent-aware single Java-8 JAR, also for production local collection. */
    @Test
    fun reliabilityOptInPackagesBoundAssetsAndNoDebugControlsInRelease() {
        val sdk = assumeAndroidSdk()
        val runtimeJar = resolveRuntimeJar()
        assumeTrue(runtimeJar != null, "applog-runtime jar not provided (-Dapplog.runtime.jar); skipping")
        runtimeJar!!
        testEnv("android-reliability-apk") { env ->
            val dir = env.dir
            // Reproduce an imported/generated source manifest that explicitly disables debugging.
            val sourceManifest = NATIVE_MANIFEST.replace("<application ", "<application android:debuggable=\"false\" ")
            val store = buildAppWorkspace(dir, env.platform, manifest = sourceManifest, activity = null)
            val project = store.workspace.projects.single()
            val manifestPath = dir.resolve("app/src/main/AndroidManifest.xml")
            val originalManifest = Files.readString(manifestPath)
            val privateCache = Files.createTempDirectory("reliability-apk-private")
            try {
                val runtime = ReliabilityBuildConfiguration(runtimeJar, privateCache)
                val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
                val system = AndroidBuildSystem.subprocess(sdk, signing, reliabilityConfiguration = { runtime })
                val cache = BuildCache(dir.resolve(".caches/build"))
                buildVariant(system, project, "debug", cache)
                val debugApk = dir.resolve("app/build/outputs/apk/debug/app-debug.apk")
                assertTrue(!dexContainsType(debugApk, "Ldev/ide/applog/Reliability;"), "default-off APK must not contain SDK")
                assertApkDebuggable(sdk.aapt2, debugApk, true)
                val options = dir.resolve(ReliabilityProjectOptions.PROJECT_FILE)
                Files.createDirectories(options.parent)
                Files.writeString(options, """{"version":1,"enabled":true,"consentGranted":true,"productionEnabled":true}""")
                for (variant in listOf("debug", "release")) {
                    buildVariant(system, project, variant, cache)
                    val apk = dir.resolve("app/build/outputs/apk/$variant/app-$variant.apk")
                    assertApkDebuggable(sdk.aapt2, apk, variant == "debug")
                    assertEquals(originalManifest, Files.readString(manifestPath), "Variant flags must not rewrite source")
                    assertTrue(dexContainsType(apk, "Ldev/ide/applog/Reliability;"), "$variant APK lacks SDK")
                    assertTrue(!dexContainsType(apk, "Ldev/ide/reliability/test/FaultController;"), "fault controls must not leak into SDK")
                    ZipFile(apk.toFile()).use { zip ->
                        val config = zip.getInputStream(zip.getEntry("assets/ide-reliability.json")).bufferedReader().use { it.readText() }
                        val sources = zip.getInputStream(zip.getEntry("assets/ide-reliability-sources.json")).bufferedReader().use { it.readText() }
                        assertTrue("\"enabled\":true" in config)
                        assertTrue("\"pairingNonce\":\"\"" in config, "unpaired/local-only builds contain no recipient nonce")
                        assertTrue("sourceRevision" in config && "sourceRevision" in sources)
                        assertTrue(dir.toString() !in config && dir.toString() !in sources)
                    }
                    val manifest = Files.readString(dir.resolve("app/build/intermediates/android/$variant/instrumented-manifest/AndroidManifest.xml"))
                    assertTrue("dev.ide.reliability.CONFIG_ASSET" in manifest)
                    assertTrue("$APPLICATION_ID.ide-reliability" in manifest)
                    assertTrue("<queries" !in manifest, "local-only production has no generated IPC query")
                    assertTrue("android:debuggable=\"${variant == "debug"}\"" in manifest)
                }
                // The inverse conflict matters for safety: production must override a source manifest
                // with debugging left on, including when the reliability SDK itself is opted in.
                val staleDebugManifest = originalManifest.replace("android:debuggable=\"false\"", "android:debuggable=\"true\"")
                Files.writeString(manifestPath, staleDebugManifest)
                buildVariant(system, project, "release", cache)
                assertApkDebuggable(sdk.aapt2, dir.resolve("app/build/outputs/apk/release/app-release.apk"), false)
                assertEquals(staleDebugManifest, Files.readString(manifestPath))
                Files.writeString(manifestPath, originalManifest)
                // Returning to default OFF must remove both classes and private config from the next APK.
                Files.delete(options)
                buildVariant(system, project, "debug", cache)
                assertTrue(!dexContainsType(debugApk, "Ldev/ide/applog/Reliability;"))
                assertApkDebuggable(sdk.aapt2, debugApk, true)
                assertEquals(originalManifest, Files.readString(manifestPath))
                ZipFile(debugApk.toFile()).use { assertTrue(it.getEntry("assets/ide-reliability.json") == null) }
            } finally {
                privateCache.toFile().deleteRecursively()
            }
        }
    }

    private fun buildVariant(buildSystem: AndroidBuildSystem, project: dev.ide.model.Project, variant: String, cache: BuildCache) {
        val graph = buildSystem.createBuildGraph(
            project, BuildRequest(listOf(ModuleId("app")), VariantSelector(variant), BuildGoal.PACKAGE),
        )
        val log = StringBuilder()
        val outcome = runBlocking { TaskExecutorImpl(cache).execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2) }
        assertTrue(outcome.succeeded, "$variant APK build failed:\n$log")
    }

    /** Inspect the linked binary APK, not just the intermediate XML or selected variant name. */
    private fun assertApkDebuggable(aapt2: Path, apk: Path, expected: Boolean) {
        val process = ProcessBuilder(aapt2.toString(), "dump", "badging", apk.toString()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), "aapt2 dump badging failed:\n$output")
        assertEquals(expected, output.lineSequence().any { it.trim() == "application-debuggable" },
            "Compiled APK debuggable flag differs from selected build type (${apk.fileName}):\n$output")
    }

    private fun resolveRuntimeJar(): Path? =
        System.getProperty("applog.runtime.jar")?.split(File.pathSeparator)
            ?.map { it.trim() }?.firstOrNull { "applog-runtime" in it && it.endsWith(".jar") }
            ?.let { Path.of(it) }?.takeIf { Files.isRegularFile(it) }

    /** True if any `classes*.dex` in [apk] contains the type [descriptor] (dex stores it verbatim as a string). */
    private fun dexContainsType(apk: Path, descriptor: String): Boolean {
        if (!Files.isRegularFile(apk)) return false
        val needle = descriptor.toByteArray(Charsets.UTF_8)
        ZipFile(apk.toFile()).use { zf ->
            val dexEntries = zf.entries().toList().filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
            for (e in dexEntries) {
                val bytes = zf.getInputStream(e).use { it.readBytes() }
                if (indexOf(bytes, needle) >= 0) return true
            }
        }
        return false
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun buildAppWorkspace(
        dir: Path,
        platform: PlatformCore,
        manifest: String = MANIFEST,
        activity: String? = ACTIVITY,
    ): ProjectModelStore {
        val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
        ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
        val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")

        store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        store.workspace.projects.single().beginModification().apply {
            addModule("app", appType).apply {
                languageLevel = LanguageLevel.JAVA_17
                putFacet(
                    AndroidFacet(namespace = APPLICATION_ID, compileSdk = 34, minSdk = 24, targetSdk = 34, isApplication = true),
                )
            }
            commit()
        }

        dir.writeSource("app/src/main/AndroidManifest.xml", manifest)
        dir.writeSource("app/src/main/res/values/strings.xml", STRINGS)
        if (activity != null) dir.writeSource("app/src/main/java/com/example/app/MainActivity.java", activity)
        return store
    }

    private companion object {
        const val APPLICATION_ID = "com.example.app"
        const val PROVIDER = "dev.ide.applog.IdeLogBridgeProvider"
        const val PROVIDER_DESC = "Ldev/ide/applog/IdeLogBridgeProvider;"

        val MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true"/>
                </application>
            </manifest>
        """

        val NATIVE_MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="@string/app_name" android:hasCode="false">
                    <activity android:name="android.app.NativeActivity" android:exported="true">
                        <meta-data android:name="android.app.lib_name" android:value="demo"/>
                    </activity>
                </application>
            </manifest>
        """

        val STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">CodeAssist Demo</string>
            </resources>
        """

        val ACTIVITY = """
            package com.example.app;

            import android.app.Activity;
            import android.os.Bundle;
            import android.widget.TextView;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    TextView tv = new TextView(this);
                    tv.setText(R.string.app_name);
                    setContentView(tv);
                }
            }
        """
    }
}
