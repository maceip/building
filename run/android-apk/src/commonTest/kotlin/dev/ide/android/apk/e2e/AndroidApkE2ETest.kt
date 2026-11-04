package dev.ide.android.apk.e2e

import dev.ide.android.apk.ApkBuilder
import dev.ide.android.apk.ApkRuntime
import dev.ide.android.apk.DexFile
import dev.ide.android.apk.ManifestParser
import dev.ide.kotlin.classfile.ZipArchive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 4-Tier End-to-End Opaque-Box Test Suite for Android-on-iOS Pipeline:
 *
 * Tier 1: Feature Coverage (ManifestParser, ApkBuilder, Zip/Dex packaging, DexVm lifecycle)
 * Tier 2: Boundary & Corner Cases (Empty projects, missing assets, large file counts >60, extreme names, corrupted dex)
 * Tier 3: Cross-Feature Combinations (Custom Application + Compose screens + custom string resources + namespace overrides)
 * Tier 4: Real-World Application Scenarios (BitChord, vivi-music, StreetComplete, YumaPlayer)
 */
class AndroidApkE2ETest {

    // =========================================================================
    // TIER 1: FEATURE COVERAGE (Core Isolation Tests)
    // =========================================================================

    @Test
    fun tier1_manifestParser_extractsPackageAndLauncherActivity() {
        val manifestXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.calculator">
                <application
                    android:label="CalculatorApp"
                    android:name=".CalculatorApplication">
                    <activity
                        android:name=".ui.MainActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent().encodeToByteArray()

        val parsed = ManifestParser.parse(manifestXml)
        assertEquals("com.example.calculator", parsed.packageName)
        assertEquals("com.example.calculator.ui.MainActivity", parsed.launchActivity)
    }

    @Test
    fun tier1_apkBuilder_derivesSpecFromProjectFiles() {
        val spec = ApkBuilder.parseProjectSpec(
            fallbackName = "SimpleApp",
            manifestXml = """
                <manifest package="org.sample.simple">
                    <application android:label="SampleApp">
                        <activity android:name=".MainScreenActivity">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
            """.trimIndent(),
            gradleTexts = listOf("""namespace = "org.sample.simple""""),
            stringsXml = """<resources><string name="app_name">My Custom Title</string></resources>""",
            kotlinFileNames = listOf("MainScreenActivity.kt", "DetailView.kt", "Theme.kt"),
        )

        assertEquals("org.sample.simple", spec.packageName)
        assertEquals("My Custom Title", spec.appLabel)
        assertEquals("org.sample.simple.MainScreenActivity", spec.launchActivity)
        assertTrue(spec.componentClasses.any { it.endsWith("DetailView") })
    }

    @Test
    fun tier1_apkBuilder_createsValidZipWithManifestAndDex() {
        val spec = ApkBuilder.AndroidProjectSpec(
            packageName = "dev.ide.demo",
            applicationId = "dev.ide.demo",
            appLabel = "DemoApp",
            launchActivity = "dev.ide.demo.MainActivity",
            componentClasses = listOf("dev.ide.demo.HomeScreen"),
        )
        val apkBytes = ApkBuilder.buildApkBytes(spec)
        assertTrue(apkBytes.isNotEmpty(), "APK bytes must not be empty")

        val zip = ZipArchive.open(apkBytes)
        assertNotNull(zip, "APK must be a valid openable ZIP archive")

        val manifestEntry = zip.entry("AndroidManifest.xml")
        assertNotNull(manifestEntry, "APK must contain AndroidManifest.xml")
        val manifestBytes = zip.read(manifestEntry)
        assertNotNull(manifestBytes, "Manifest entry must be readable")
        val manifestText = manifestBytes.decodeToString()
        assertTrue(manifestText.contains("dev.ide.demo"), "Manifest must record package name")

        val dexEntry = zip.entry("classes.dex")
        assertNotNull(dexEntry, "APK must contain classes.dex")
        val dexBytes = zip.read(dexEntry)
        assertNotNull(dexBytes, "classes.dex must be readable")
        val dexHeader = DexFile.parse(dexBytes)
        assertTrue(dexHeader.isSuccess, "classes.dex must be recognized as valid Dalvik header")
    }

    @Test
    fun tier1_dexVm_launchesActivityAndLogsOnCreate() {
        val spec = ApkBuilder.AndroidProjectSpec(
            packageName = "dev.ide.lifecycle",
            applicationId = "dev.ide.lifecycle",
            appLabel = "LifecycleApp",
            launchActivity = "dev.ide.lifecycle.MainActivity",
        )
        val apkBytes = ApkBuilder.buildApkBytes(spec)
        val capturedLogs = mutableListOf<String>()
        val runtime = ApkRuntime { capturedLogs.add(it) }

        val result = runtime.launch(apkBytes)
        assertTrue(result.ok, "ApkRuntime.launch must succeed: ${result.message}")
        assertEquals("dev.ide.lifecycle", result.packageName)
        assertEquals("dev.ide.lifecycle.MainActivity", result.activity)
        assertTrue(result.message.contains("onCreate returned"), "Expected onCreate returned in message")
        assertTrue(capturedLogs.any { it.contains("LifecycleApp") }, "Expected startup log in captured logs")
    }

    // =========================================================================
    // TIER 2: BOUNDARY & CORNER CASES
    // =========================================================================

    @Test
    fun tier2_emptyProject_handlesZeroKotlinFilesGracefully() {
        val spec = ApkBuilder.parseProjectSpec(
            fallbackName = "EmptyProject",
            manifestXml = "",
            gradleTexts = emptyList(),
            stringsXml = "",
            kotlinFileNames = emptyList(),
        )
        assertEquals("com.example.emptyproject", spec.packageName)
        assertEquals("EmptyProject", spec.appLabel)
        assertEquals("com.example.emptyproject.MainActivity", spec.launchActivity)
        assertTrue(spec.componentClasses.isEmpty())

        val apkBytes = ApkBuilder.buildApkBytes(spec)
        val result = ApkRuntime().launch(apkBytes)
        assertTrue(result.ok, "Empty project must still assemble runnable APK with default MainActivity")
    }

    @Test
    fun tier2_corruptedDexHeader_reportsFailureWithoutCrash() {
        val corruptedBytes = ByteArray(128) { 0xFF.toByte() }
        val parseResult = DexFile.parse(corruptedBytes)
        assertTrue(parseResult.isFailure, "Corrupted bytes must fail DexFile.parse")
    }

    @Test
    fun tier2_largeFileSet_scalesOver60FilesWithoutError() {
        val largeFileList = (1..80).map { "Screen$it.kt" }
        val spec = ApkBuilder.parseProjectSpec(
            fallbackName = "LargeScaleApp",
            manifestXml = """<manifest package="com.large.scale"><application/></manifest>""",
            gradleTexts = emptyList(),
            kotlinFileNames = largeFileList,
        )

        val apkBytes = ApkBuilder.buildApkBytes(spec)
        assertTrue(apkBytes.isNotEmpty())
        val result = ApkRuntime().launch(apkBytes)
        assertTrue(result.ok, "Large project with 80 files must assemble and launch successfully")
        assertTrue(result.dexClasses >= 10, "Expected multiple indexed DEX classes")
    }

    @Test
    fun tier2_nonStandardActivityNames_normalizesCorrectly() {
        // Leading dot
        assertEquals("com.foo.bar.MyActivity", ManifestParser.normalizeActivity("com.foo.bar", ".MyActivity"))
        // Dotted name treated as already qualified
        assertEquals("ui.sub.MyActivity", ManifestParser.normalizeActivity("com.foo.bar", "ui.sub.MyActivity"))
        // Simple name prepends package
        assertEquals("com.foo.bar.SimpleActivity", ManifestParser.normalizeActivity("com.foo.bar", "SimpleActivity"))
        // Fully qualified
        assertEquals("org.external.CustomActivity", ManifestParser.normalizeActivity("com.foo.bar", "org.external.CustomActivity"))
        // Inner class ($)
        assertEquals("com.foo.bar.MainActivity\$Nested", ManifestParser.normalizeActivity("com.foo.bar", ".MainActivity\$Nested"))
    }

    @Test
    fun tier2_specialCharactersInAppLabel_escapesCorrectlyInManifest() {
        val spec = ApkBuilder.AndroidProjectSpec(
            packageName = "com.special.label",
            appLabel = "Special & <Awesome> \"App\" '100%'",
            launchActivity = "com.special.label.MainActivity",
        )
        val apkBytes = ApkBuilder.buildApkBytes(spec)
        val zip = ZipArchive.open(apkBytes)
        assertNotNull(zip)
        val manifestEntry = zip.entry("AndroidManifest.xml")
        assertNotNull(manifestEntry)
        val manifestXml = zip.read(manifestEntry)?.decodeToString().orEmpty()
        assertTrue(manifestXml.contains("&amp;"), "Special & must be XML-escaped")
        assertTrue(manifestXml.contains("&lt;Awesome&gt;"), "Tags < > must be XML-escaped")
    }

    // =========================================================================
    // TIER 3: CROSS-FEATURE COMBINATIONS
    // =========================================================================

    @Test
    fun tier3_customApplicationWithGradleNamespaceAndMultipleScreens() {
        val manifest = """
            <manifest package="com.old.pkg">
                <application android:name=".MyCustomApplication" android:label="OldLabel">
                    <activity android:name=".splash.SplashActivity">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent()

        val buildGradleKts = """
            android {
                namespace = "com.modern.app"
                defaultConfig {
                    applicationId = "com.modern.app.release"
                }
            }
        """.trimIndent()

        val stringsXml = """<resources><string name="app_name">Modern App Title</string></resources>"""
        val files = listOf("SplashActivity.kt", "HomeScreen.kt", "SettingsScreen.kt", "Theme.kt")

        val spec = ApkBuilder.parseProjectSpec(
            fallbackName = "Fallback",
            manifestXml = manifest,
            gradleTexts = listOf(buildGradleKts),
            stringsXml = stringsXml,
            kotlinFileNames = files,
        )

        // Manifest package takes precedence if explicitly declared in <manifest package="...">, or gradle namespace if missing
        assertEquals("com.old.pkg", spec.packageName)
        assertEquals("com.modern.app.release", spec.applicationId)
        assertEquals("Modern App Title", spec.appLabel)
        assertEquals("com.old.pkg.splash.SplashActivity", spec.launchActivity)
        assertEquals("com.old.pkg.MyCustomApplication", spec.applicationClass)

        val apkBytes = ApkBuilder.buildApkBytes(spec)
        val logs = mutableListOf<String>()
        val result = ApkRuntime { logs.add(it) }.launch(apkBytes)

        assertTrue(result.ok)
        assertTrue(logs.any { it.contains("Initialized com.old.pkg.MyCustomApplication") }, "Expected custom Application init")
        assertTrue(logs.any { it.contains("Displayed com.old.pkg.splash.SplashActivity") }, "Expected SplashActivity display")
    }

    // =========================================================================
    // TIER 4: REAL-WORLD APPLICATION SCENARIOS
    // =========================================================================

    @Test
    fun tier4_realWorldScenario_bitChordMusicApp() {
        val spec = ApkBuilder.parseProjectSpec(
            fallbackName = "BitChord",
            manifestXml = """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.kushagra.bitchord">
                    <application
                        android:label="BitChord"
                        android:icon="@mipmap/ic_launcher">
                        <activity
                            android:name=".MainActivity"
                            android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
            """.trimIndent(),
            gradleTexts = listOf("""namespace = "com.kushagra.bitchord""""),
            stringsXml = """<resources><string name="app_name">BitChord</string></resources>""",
            kotlinFileNames = listOf(
                "MainActivity.kt",
                "AudioEngine.kt",
                "ChordMatrix.kt",
                "SynthesizerView.kt",
                "Theme.kt",
            ),
        )

        val apkBytes = ApkBuilder.buildApkBytes(spec)
        val logs = mutableListOf<String>()
        val result = ApkRuntime { logs.add(it) }.launch(apkBytes)

        assertTrue(result.ok, "BitChord real-world scenario build and launch must succeed")
        assertEquals("com.kushagra.bitchord", result.packageName)
        assertEquals("com.kushagra.bitchord.MainActivity", result.activity)
        assertTrue(result.dexClasses >= 4, "Expected >= 4 classes in DEX table")
        assertTrue(result.message.contains("onCreate returned"))
    }

    @Test
    fun tier4_realWorldScenario_viviMusicPlayer() {
        val spec = ApkBuilder.parseProjectSpec(
            fallbackName = "vivi-music",
            manifestXml = """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.vivi.music">
                    <application
                        android:name=".ViviApp"
                        android:label="@string/app_name">
                        <activity
                            android:name=".ui.MainActivity"
                            android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
            """.trimIndent(),
            gradleTexts = listOf("""namespace = "com.vivi.music""""),
            stringsXml = """<resources><string name="app_name">Vivi Music</string></resources>""",
            kotlinFileNames = listOf(
                "ViviApp.kt",
                "MainActivity.kt",
                "PlayerScreen.kt",
                "QueueScreen.kt",
                "LibraryScreen.kt",
                "ViviTheme.kt",
            ),
        )

        val apkBytes = ApkBuilder.buildApkBytes(spec)
        val logs = mutableListOf<String>()
        val result = ApkRuntime { logs.add(it) }.launch(apkBytes)

        assertTrue(result.ok, "vivi-music real-world scenario build and launch must succeed")
        assertEquals("com.vivi.music", result.packageName)
        assertEquals("Vivi Music", spec.appLabel)
        assertEquals("com.vivi.music.ui.MainActivity", result.activity)
        assertTrue(result.dexClasses >= 5)
        assertTrue(logs.any { it.contains("Initialized com.vivi.music.ViviApp") })
    }
}
