package dev.ide.gradle

import dev.ide.platform.JsonReader
import dev.ide.platform.JsonWriter
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real public AGP APIs, SDK/cache gated and offline: no user build files are ever patched. */
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class GradleReliabilityInitScriptTest {
    @Test fun realAgpGeneratedAssetsManifestAndFileRuntimeAreApplicationVariantOnly() {
        val home = Path.of(System.getProperty("user.home"))
        val sdk = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
            .map(Path::of).plus(home.resolve("Library/Android/sdk")).firstOrNull {
                Files.isRegularFile(it.resolve("platforms/android-36/android.jar"))
            }
        assumeTrue(sdk != null, "Android SDK 36 is required for the real AGP reliability fixture")
        val gradleHome = Path.of(System.getProperty("dev.ide.gradle.home") ?: error("dev.ide.gradle.home is not set"))
        val init = System.getProperty("dev.ide.init.script") ?: error("dev.ide.init.script is not set")
        val agpCache = home.resolve(".gradle/caches/modules-2/files-2.1/com.android.tools.build/gradle/9.2.1")
        assumeTrue(Files.isDirectory(agpCache), "AGP 9.2.1 must already be cached; fixture never downloads it")
        val root = Files.createTempDirectory("reliability-agp-app")
        val privateCache = Files.createTempDirectory("reliability-agp-private")
        try {
            write(root, "settings.gradle", """
                pluginManagement {
                    repositories { google(); mavenCentral(); gradlePluginPortal() }
                    resolutionStrategy { eachPlugin {
                        if (requested.id.id.startsWith('com.android.')) useModule('com.android.tools.build:gradle:9.2.1')
                    } }
                }
                dependencyResolutionManagement { repositories { google(); mavenCentral() } }
                rootProject.name = 'reliability-fixture'
                include ':app', ':lib'
            """.trimIndent())
            write(root, "build.gradle", """
                plugins {
                    id 'com.android.application' version '9.2.1' apply false
                    id 'com.android.library' version '9.2.1' apply false
                }
            """.trimIndent())
            val appBuild = """
                plugins { id 'com.android.application' }
                android {
                    namespace 'com.example.fixture'
                    compileSdk 36
                    defaultConfig { applicationId 'com.example.fixture'; minSdk 26; targetSdk 36; versionCode 1; versionName '1' }
                }
                androidComponents.onVariants(androidComponents.selector().all()) { variant ->
                    tasks.register('check' + variant.name.capitalize() + 'Runtime') {
                        doLast {
                            def runtime = variant.runtimeConfiguration.files
                            assert runtime.any { it.name == (variant.name == 'release' ? 'runtime.aar' : 'runtime.jar') }
                            println 'RELIABILITY_RUNTIME=' + variant.name
                        }
                    }
                }
            """.trimIndent()
            write(root, "app/build.gradle", appBuild)
            write(root, "app/src/main/AndroidManifest.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:label="Fixture" android:hasCode="false"/></manifest>""")
            write(root, "lib/build.gradle", """
                plugins { id 'com.android.library' }
                android { namespace 'com.example.library'; compileSdk 36; defaultConfig { minSdk 26 }; testFixtures { enable true } }
                tasks.register('assertNoReliability') {
                    doLast { assert !tasks.names.any { it.startsWith('generateIdeReliability') || it.startsWith('injectIdeReliability') } }
                }
            """.trimIndent())
            write(root, "lib/src/main/AndroidManifest.xml", "<manifest/>")
            write(root, "local.properties", "sdk.dir=${sdk!!.toString().replace('\\', '/')}\n")
            write(root, ".building/reliability.json", """{"version":1,"enabled":true,"consentGranted":true,"productionEnabled":true}""")
            val runtime = javaRuntime(privateCache)
            val productionAar = privateCache.resolve("sdk.aar")
            JarOutputStream(Files.newOutputStream(productionAar)).use { zip ->
                zip.putNextEntry(JarEntry("classes.jar")); Files.copy(runtime, zip); zip.closeEntry()
                zip.putNextEntry(JarEntry("AndroidManifest.xml"))
                zip.write("<manifest package=\"dev.ide.applog\"/>".toByteArray()); zip.closeEntry()
            }
            val model = snapshot()
            val nonce = "a".repeat(64)
            val provider = GradleReliabilityConfigurationProvider {
                """{"idePackage":"dev.ide.android","ideService":"dev.ide.android.reliability.ReliabilitySinkService","pairingNonce":"$nonce"}"""
            }
            for (variant in listOf("debug", "release")) {
                val args = GradleReliabilityInjection.launchArgs(root, privateCache, model,
                    listOf(":app:assemble${variant.replaceFirstChar { it.uppercase() }}"),
                    if (variant == "release") productionAar else runtime, provider)
                val descriptor = JsonReader.obj(JsonReader.parse(Files.readString(Path.of(args.first().substringAfter('=')))))!!
                val row = JsonReader.obj(JsonReader.arr(descriptor["variants"]).single())!!
                val generated = Path.of(row["outputDir"].toString())
                val cap = variant.replaceFirstChar { it.uppercase() }
                val result = runner(root, gradleHome, init, args + listOf(
                    ":app:generateIdeReliability$cap", ":app:injectIdeReliabilityManifest$cap",
                    ":app:check${cap}Runtime", ":lib:assertNoReliability",
                )).build()
                assertTrue("RELIABILITY_RUNTIME=$variant" in result.output, result.output)
                val asset = Files.readString(generated.resolve("assets/ide-reliability.json"))
                val manifest = Files.readString(generated.resolve("manifest/AndroidManifest.xml"))
                assertTrue("dev.ide.reliability.CONFIG_ASSET" in manifest)
                assertTrue("android:exported=\"false\"" in manifest)
                assertTrue("android:hasCode=\"true\"" in manifest)
                assertTrue("com.example.fixture.ide-reliability" in manifest)
                assertEquals(variant == "debug", nonce in asset)
                assertEquals(variant == "debug", "<queries" in manifest)
                assertFalse("dev.ide.applog.SINK" in manifest)
                assertEquals(appBuild, Files.readString(root.resolve("app/build.gradle")))
                GradleReliabilityInjection.verifySources(root, args)
            }
        } finally {
            root.toFile().deleteRecursively()
            privateCache.toFile().deleteRecursively()
        }
    }

    @Test fun descriptorForNonApplicationProjectFailsWithActionableDiagnostic() {
        val root = Files.createTempDirectory("reliability-not-app")
        val cache = Files.createTempDirectory("reliability-not-app-private")
        try {
            write(root, "settings.gradle", "rootProject.name = 'not-android'; include ':app'\n")
            write(root, "app/build.gradle", "plugins { id 'java-library' }\n")
            write(root, ".building/reliability.json", """{"version":1,"enabled":true,"consentGranted":true}""")
            val args = GradleReliabilityInjection.launchArgs(root, cache, snapshot(), listOf(":app:assembleDebug"), javaRuntime(cache), null)
            val result = runner(root, Path.of(System.getProperty("dev.ide.gradle.home")),
                System.getProperty("dev.ide.init.script"), args + "help").buildAndFail()
            assertTrue("opted-in application variants were not found" in result.output, result.output)
        } finally { root.toFile().deleteRecursively(); cache.toFile().deleteRecursively() }
    }

    @Test fun unsupportedAgpIsAnExplicitFailureNotAnIgnoredAutomaticApply() {
        val root = Files.createTempDirectory("reliability-unsupported-agp")
        val cache = Files.createTempDirectory("reliability-unsupported-private")
        try {
            write(root, "settings.gradle", "rootProject.name = 'unsupported'; include ':app'\n")
            write(root, "buildSrc/build.gradle", "plugins { id 'groovy' }; dependencies { implementation gradleApi(); implementation localGroovy() }\n")
            write(root, "buildSrc/src/main/groovy/fixture/LegacyAgp.groovy", """
                package fixture
                import org.gradle.api.Plugin
                import org.gradle.api.Project
                class LegacyAgp implements Plugin<Project> {
                    void apply(Project project) {
                        project.extensions.add('androidComponents', new Expando(pluginVersion: [major: 7, minor: 4, preview: 0]))
                    }
                }
            """.trimIndent())
            write(root, "buildSrc/src/main/resources/META-INF/gradle-plugins/com.android.application.properties", "implementation-class=fixture.LegacyAgp\n")
            write(root, "app/build.gradle", "apply plugin: 'com.android.application'\n")
            write(root, ".building/reliability.json", """{"version":1,"enabled":true,"consentGranted":true}""")
            val args = GradleReliabilityInjection.launchArgs(root, cache, snapshot(), listOf(":app:assembleDebug"), javaRuntime(cache), null)
            val result = runner(root, Path.of(System.getProperty("dev.ide.gradle.home")),
                System.getProperty("dev.ide.init.script"), args + "help").buildAndFail()
            assertTrue("Reliability: unsupported AGP" in result.output, result.output)
            assertFalse(Files.exists(root.resolve("app/src/main/assets/ide-reliability.json")))
        } finally { root.toFile().deleteRecursively(); cache.toFile().deleteRecursively() }
    }

    private fun runner(root: Path, gradleHome: Path, init: String, args: List<String>): GradleRunner =
        GradleRunner.create().withProjectDir(root.toFile()).withGradleInstallation(gradleHome.toFile())
            // Reuse the explicitly prerequisite offline dependency cache, not TestKit's empty user home.
            .withTestKitDir(Path.of(System.getProperty("user.home"), ".gradle").toFile())
            .withArguments(listOf("--offline", "--init-script", init, "--stacktrace") + args)
            .withEnvironment(HashMap(System.getenv()).apply { put("JAVA_HOME", System.getProperty("java.home")) })

    private fun javaRuntime(root: Path): Path {
        val source = root.resolve("runtime-source/dev/ide/applog")
        Files.createDirectories(source)
        Files.writeString(source.resolve("IdeLogBridgeProvider.java"), "package dev.ide.applog; public class IdeLogBridgeProvider {}")
        Files.writeString(source.resolve("Reliability.java"), "package dev.ide.applog; public final class Reliability {}")
        val classes = root.resolve("runtime-classes")
        Files.createDirectories(classes)
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", classes.toString(),
            source.resolve("IdeLogBridgeProvider.java").toString(), source.resolve("Reliability.java").toString()))
        val jar = root.resolve("sdk.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { zip ->
            Files.walk(classes).use { paths -> paths.filter(Files::isRegularFile).sorted().forEach { file ->
                zip.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/')))
                Files.copy(file, zip); zip.closeEntry()
            } }
        }
        return jar
    }

    private fun snapshot() = GradleProjectSnapshot.parse(JsonWriter.value(mapOf(
        "schemaVersion" to 2, "name" to "fixture", "modules" to listOf(mapOf(
            "name" to "app", "path" to ":app", "dir" to "app", "application" to true,
            "plugins" to listOf("com.android.application"), "variants" to listOf("debug", "release").map { variant -> mapOf(
                "name" to variant, "buildType" to variant, "assembleTask" to "assemble${variant.replaceFirstChar { it.uppercase() }}",
                "debuggable" to (variant == "debug"), "applicationId" to "com.example.fixture",
            ) },
        )),
    )))
    private fun write(root: Path, relative: String, text: String) = root.resolve(relative).also {
        Files.createDirectories(it.parent); Files.writeString(it, text)
    }
}
