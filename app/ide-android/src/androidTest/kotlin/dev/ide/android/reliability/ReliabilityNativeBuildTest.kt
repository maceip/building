package dev.ide.android.reliability

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.AndroidIde
import dev.ide.android.support.ReliabilityBuildEnvironment
import dev.ide.android.support.ReliabilityProjectOptions
import dev.ide.android.workbench.acceptance.AcceptanceWorkspace
import dev.ide.build.ReliabilitySourceSnapshot
import dev.ide.ui.backend.BuildService
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.StepStatus
import dev.ide.workbench.reliability.IncidentWire
import dev.ide.workbench.reliability.PairingStatus
import dev.ide.workbench.webapp.StaticWebAppPackager
import dev.ide.workbench.webapp.WebAppPackageRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Real on-device native compiler/dexer/signer plus the production SDK injection and host pairing port.
 * This test only builds and pairs: main owns installation, separate-UID launch, crash and replay checks.
 * Success deliberately leaves this fixture's grant and local debug delivery enabled for those checks.
 * A completed build that fails APK verification retains only its private APK for inspection; no gate is relaxed.
 */
@RunWith(AndroidJUnit4::class)
class ReliabilityNativeBuildTest {
    @Test(timeout = 300_000)
    fun buildAndPairNativeSdkFixture() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit disposable native fixture authorization is required",
            arguments.getString("authorizedFixture") == APPLICATION_ID &&
                arguments.getString("allowNativeReliabilityFixtureBuild") == "true")
        runBlocking {
            withContext(Dispatchers.IO) {
                withTimeout(270_000) { buildFixture() }
            }
        }
    }

    private suspend fun buildFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val host = ReliabilityHostProvider.get(context)
        val previouslyEnabled = host.debugCaptureAllowed()
        val previousBuilds = host.pairings.list().filter { it.packageName == APPLICATION_ID }.map { it.buildId }.toSet()
        val previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        var bootstrapCrashHandler: Thread.UncaughtExceptionHandler? = null
        var session: AndroidIde.Session? = null
        var generatedRoot: Path? = null
        var retainedApk: Path? = null
        var retainedApkSha256: String? = null
        var buildStarted = false
        var buildFinished = false
        var successful = false
        AcceptanceWorkspace("native-reliability").use { source ->
            try {
                source.write("dist/index.html", "<!doctype html><html><body><h1>Native SDK test fixture</h1></body></html>\n")
                val projects = Files.createDirectories(AndroidIde.projectsDir(context).toPath()).toRealPath()
                val packager = StaticWebAppPackager()
                val generated = packager.create(packager.review(WebAppPackageRequest(
                    workspaceRoot = source.root,
                    staticRoot = "dist",
                    outputParent = projects,
                    projectName = PROJECT_PREFIX + UUID.randomUUID().toString().replace("-", ""),
                    applicationId = APPLICATION_ID,
                    appLabel = "Native SDK test fixture",
                    versionCode = 1,
                    versionName = "1-fixture",
                    allowExternalHttps = false,
                )))
                generatedRoot = generated.projectRoot
                assertFalse("A new native fixture must not contain a prebuilt APK", Files.exists(generated.debugApk))
                writeFixtureInputs(generated.projectRoot)
                val manifestPath = generated.projectRoot.resolve("app/src/main/AndroidManifest.xml")
                val originalManifest = readUtf8(manifestPath)
                assertFalse("SDK declaration must come from the real native build, not this fixture", originalManifest.contains(ReliabilityProjectOptions.PROVIDER_CLASS))
                assertFalse(originalManifest.contains(ReliabilityProjectOptions.CONFIG_METADATA))

                host.pairings.setDebugCaptureEnabled(true)
                session = AndroidIde.bootstrap(context)
                bootstrapCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
                val backend = requireNotNull(session).backend
                assertTrue("The real IDE must open the generated native workspace", backend.projects.openProject(generated.projectRoot.toString()))
                assertEquals(generated.projectRoot.toString(), backend.project.rootPath)
                assertTrue(backend.build.supported())
                assertNotNull("Production SDK runtime must be installed by AndroidIde", ReliabilityBuildEnvironment.runtimeJar)
                assertNotNull("Production host pairing callback must be installed, never mocked by this test", ReliabilityBuildEnvironment.provider)
                val privateCache = requireNotNull(ReliabilityBuildEnvironment.privateCacheRoot).toAbsolutePath().normalize()
                assertTrue("Generated credentials belong in the IDE's no-backup private storage", privateCache.startsWith(context.noBackupFilesDir.toPath().toAbsolutePath().normalize()))
                assertFalse(privateCache.startsWith(generated.projectRoot.toRealPath()))
                val snapshot = ReliabilitySourceSnapshot.capture(generated.projectRoot)
                buildStarted = true
                val completed = assemble(backend.build)
                buildFinished = true
                assertEquals(buildStatus(completed), RunStatus.Succeeded, completed.status)
                assertTrue("A real native task graph must execute", completed.steps.isNotEmpty())
                assertTrue(Files.isRegularFile(generated.debugApk, NOFOLLOW_LINKS))
                assertEquals("Source changed during fixture compilation", snapshot.revision, ReliabilitySourceSnapshot.capture(generated.projectRoot).revision)
                assertEquals("Injection must not rewrite the developer manifest", originalManifest, readUtf8(manifestPath))
                assertFalse("No pairing asset may be copied into project source", Files.exists(generated.projectRoot.resolve("app/src/main/assets/ide-reliability.json")))
                assertFalse(Files.exists(generated.projectRoot.resolve("app/src/debug/assets/ide-reliability.json")))

                // Freeze and verify the actual native output, not a prebuilt SDK sample or a Gradle APK.
                val outputDirectory = Files.createTempDirectory(context.cacheDir.toPath(), "reliability-native-apk-")
                val digest = ReliabilitySourceSnapshot.sha256(generated.debugApk)
                val output = outputDirectory.resolve("$APPLICATION_ID-$digest.apk")
                retainedApk = output
                Files.copy(generated.debugApk, output)
                java.io.FileOutputStream(output.toFile(), true).use { it.fd.sync() }
                output.toFile().setReadOnly()
                assertEquals(digest, ReliabilitySourceSnapshot.sha256(output))
                retainedApkSha256 = digest // Only a complete, fsynced, matching copy may survive a failed verification.
                val identity = verifyApk(output, snapshot)
                val pending = host.pairings.list().single { it.packageName == APPLICATION_ID && it.buildId == identity.buildId }
                assertTrue("This real build must create a fresh pending grant", identity.buildId !in previousBuilds)
                assertEquals(PairingStatus.PENDING, pending.status)
                assertEquals(identity.sourceRevision, pending.sourceRevision)
                val preview = host.inspectDebugPairingApk(APPLICATION_ID, identity.buildId, output.toString())
                assertEquals(identity.sourceRevision, preview.sourceRevision)
                assertEquals(identity.signers, preview.signerSha256)
                // Deliberately restricted adb fixture policy, never a product installer fallback.
                val allowUnknownInstaller = InstrumentationRegistry.getArguments().getString("allowUnknownFixtureInstaller") == "true"
                host.activateDebugPairing(APPLICATION_ID, identity.buildId, output.toString(),
                    expectedInstallerPackage = "com.android.shell", allowUnknownInstaller = allowUnknownInstaller,
                    confirmedSignerSha256 = identity.signers)
                val activated = host.pairings.list().single { it.packageName == APPLICATION_ID && it.buildId == identity.buildId }
                assertEquals(PairingStatus.ACTIVE, activated.status)
                assertEquals("com.android.shell", activated.expectedInstallerPackage)
                assertEquals(allowUnknownInstaller, activated.allowUnknownInstaller)
                assertEquals(digest, ReliabilitySourceSnapshot.sha256(output))
                successful = true
                // Never print the config, nonce, a build transcript, or an SDK private journal.
                publish("RELIABILITY_NATIVE_APK", output.toString())
                publish("RELIABILITY_NATIVE_APK_SHA256", digest)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        session?.backend?.let { backend ->
                            if (buildStarted && !buildFinished) backend.build.stopBuild()
                            backend.close()
                        }
                    } finally {
                        if (bootstrapCrashHandler != null && Thread.getDefaultUncaughtExceptionHandler() === bootstrapCrashHandler) {
                            Thread.setDefaultUncaughtExceptionHandler(previousCrashHandler)
                        }
                        if (!successful) {
                            try {
                                // Only this run's explicitly authorized fixture grants may be revoked.
                                host.pairings.list().filter { it.packageName == APPLICATION_ID && it.buildId !in previousBuilds }
                                    .forEach { host.revoke(it.packageName, it.buildId) }
                                host.pairings.setDebugCaptureEnabled(previouslyEnabled)
                            } finally {
                                retainedApk?.let { apk ->
                                    val digest = retainedApkSha256
                                    if (digest != null) {
                                        // Diagnostic artifact only: a failed debug/SDK gate never becomes a pass.
                                        // Keep just our unique private APK, never print its capability-bearing assets.
                                        publish("RELIABILITY_NATIVE_FAILED_APK", apk.toString())
                                        publish("RELIABILITY_NATIVE_FAILED_APK_SHA256", digest)
                                    } else {
                                        Files.deleteIfExists(apk)
                                        Files.deleteIfExists(apk.parent)
                                    }
                                }
                            }
                        }
                        // Never delete a timed-out native build's input while it may still be running.
                        if (!buildStarted || buildFinished) generatedRoot?.let(::deleteOwnedProject)
                    }
                }
            }
        }
    }

    // ART exposes the API-26 byte-based NIO methods, not Java 11's Files.readString/writeString.
    private fun readUtf8(path: Path): String = String(Files.readAllBytes(path), Charsets.UTF_8)
    private fun writeUtf8(path: Path, text: String) { Files.write(path, text.toByteArray(Charsets.UTF_8)) }

    private fun writeFixtureInputs(root: Path) {
        val flags = root.resolve(ReliabilityProjectOptions.PROJECT_FILE)
        Files.createDirectories(flags.parent)
        writeUtf8(flags, """{"version":1,"enabled":true,"consentGranted":true,"captureCrashes":true,"captureLifecycle":true,"capturePreviousExitInfo":true,"captureNetwork":false,"teeStdout":false,"productionEnabled":false}""" + "\n")
        val activity = root.resolve("app/src/main/java/${APPLICATION_ID.replace('.', '/')}/MainActivity.java")
        val original = readUtf8(activity)
        val anchor = "super.onCreate(state);"
        require(original.indexOf(anchor) >= 0 && original.indexOf(anchor) == original.lastIndexOf(anchor)) { "Generated activity fixture anchor changed" }
        // Only this disposable test project accepts the explicit crash extra. The production generator,
        // SDK runtime and normal app templates acquire no fault-control class or exported service.
        writeUtf8(activity, original.replace(anchor, """
            super.onCreate(state);
            if (getIntent() != null && getIntent().getBooleanExtra("crash", false)) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        throw new IllegalStateException("Native SDK fixture uncaught crash");
                    }
                }, 2000L);
            }
        """.trimIndent()))
    }

    private data class BuiltIdentity(val buildId: String, val sourceRevision: String, val signers: Set<String>)

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: Path, expectedSources: ReliabilitySourceSnapshot.Snapshot): BuiltIdentity {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val flags = PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS or
            (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val info = requireNotNull(context.packageManager.getPackageArchiveInfo(apk.toString(), flags)) { "Native APK could not be inspected" }
        assertEquals(APPLICATION_ID, info.packageName)
        val applicationFlags = requireNotNull(info.applicationInfo).flags
        assertTrue(
            "Native debug APK lacks FLAG_DEBUGGABLE; actual ApplicationInfo.flags=$applicationFlags (0x${Integer.toHexString(applicationFlags)}), required bit=0x${Integer.toHexString(ApplicationInfo.FLAG_DEBUGGABLE)}",
            applicationFlags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
        assertFalse("The fixture must remain offline", "android.permission.INTERNET" in info.requestedPermissions.orEmpty())
        val provider = info.providers.orEmpty().single { it.name == ReliabilityProjectOptions.PROVIDER_CLASS }
        assertEquals("$APPLICATION_ID.${ReliabilityProjectOptions.AUTHORITY_SUFFIX}", provider.authority)
        assertFalse("Injected SDK provider must not be exported", provider.exported)
        assertEquals(APPLICATION_ID, provider.processName)
        assertEquals(ReliabilityProjectOptions.ASSET_NAME, info.applicationInfo!!.metaData.getString(ReliabilityProjectOptions.CONFIG_METADATA))
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val signers = signatures.orEmpty().map { signature ->
            java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }.toSet()
        assertTrue("The actual native output must be signed", signers.isNotEmpty())
        return ZipFile(apk.toFile()).use { zip ->
            val config = readJson(zip, "assets/${ReliabilityProjectOptions.ASSET_NAME}")
            val sources = readJson(zip, "assets/${ReliabilityProjectOptions.SOURCE_ASSET_NAME}")
            assertEquals(1, (config["version"] as? JsonPrimitive)?.intOrNull)
            for (name in listOf("enabled", "consentGranted", "captureCrashes", "captureLifecycle", "capturePreviousExitInfo")) {
                assertTrue("Missing explicit collection flag: $name", (config[name] as? JsonPrimitive)?.booleanOrNull == true)
            }
            for (name in listOf("captureNetwork", "teeStdout", "productionEnabled")) {
                assertFalse("Unexpected collection flag: $name", (config[name] as? JsonPrimitive)?.booleanOrNull == true)
            }
            assertEquals(context.packageName, config.text("idePackage"))
            assertEquals(IncidentWire.SERVICE_CLASS, config.text("ideService"))
            assertTrue("Private debug grant nonce must be generated", config.text("pairingNonce").matches(Regex("[a-f0-9]{64}")))
            assertTrue("IDE recipient certificate must be pinned", config.text("ideSignerSha256").matches(Regex("[a-f0-9]{64}")))
            val buildId = config.text("buildId")
            val revision = config.text("sourceRevision")
            assertTrue(buildId.isNotEmpty())
            assertEquals(expectedSources.revision, revision)
            assertEquals(buildId, sources.text("buildId"))
            assertEquals(revision, sources.text("sourceRevision"))
            assertEquals(setOf("version", "buildId", "sourceRevision", "files"), sources.keys)
            val expected = JsonArray(expectedSources.files.map { fields -> buildJsonObject { fields.forEach { (name, value) -> put(name, value) } } })
            assertEquals("Source metadata must be basename/hash evidence, not source paths or invented revisions", expected, sources["files"])
            verifySdkDex(zip)
            BuiltIdentity(buildId, revision, signers)
        }
    }

    /** Native D8 may put dependency bytecode in later DEX files; bound the whole scan, not each file alone. */
    private fun verifySdkDex(zip: ZipFile) {
        val dexName = Regex("classes[0-9]*\\.dex")
        val names = HashSet<String>()
        var remainingBytes = 16 * 1_024 * 1_024
        var providerFound = false
        var runtimeFound = false
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!dexName.matches(entry.name)) continue
            require(!entry.isDirectory && names.add(entry.name)) { "Invalid or duplicate DEX entry" }
            require(names.size <= 100) { "Native APK exceeds the 100-DEX test limit" }
            val bytes = boundedEntry(zip, entry.name, remainingBytes)
            remainingBytes -= bytes.size
            val dex = bytes.toString(Charsets.ISO_8859_1)
            providerFound = providerFound || "Ldev/ide/applog/IdeLogBridgeProvider;" in dex
            runtimeFound = runtimeFound || "Ldev/ide/applog/Reliability;" in dex
            // Do not stop once found: all DEX entries must remain within the aggregate safety bounds.
        }
        assertTrue("Native APK has no primary DEX", "classes.dex" in names)
        assertTrue("Real SDK provider bytecode is absent from all APK DEX files", providerFound)
        assertTrue("Real durable SDK bytecode is absent from all APK DEX files", runtimeFound)
    }

    private suspend fun assemble(build: BuildService): BuildState = coroutineScope {
        val before = build.buildState.value
        assertFalse("Native fixture requires a fresh idle session", before.status == RunStatus.Running)
        var running = false
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            build.buildState.first { state ->
                when (state.status) {
                    RunStatus.Running -> { running = true; false }
                    RunStatus.Succeeded -> running
                    RunStatus.Failed -> running || state != before
                    RunStatus.Idle -> false
                }
            }
        }
        try {
            val completed = withTimeout(180_000) { build.runTask("assemble:app:debug"); completion.await() }
            assertTrue("A real native Running-to-terminal transition is required", running)
            completed
        } finally { completion.cancel() }
    }

    private fun readJson(zip: ZipFile, name: String): JsonObject = try {
        Json.parseToJsonElement(boundedEntry(zip, name, 16_384).toString(Charsets.UTF_8)) as? JsonObject ?: error("Expected generated object")
    } catch (_: Exception) { throw AssertionError("Invalid generated SDK asset: $name") }

    private fun boundedEntry(zip: ZipFile, name: String, limit: Int): ByteArray {
        val entry = requireNotNull(zip.getEntry(name)) { "Required compiled entry is missing: $name" }
        require(entry.size in 1..limit.toLong()) { "Compiled entry exceeds test bounds: $name" }
        return zip.getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= limit) { "Compiled entry exceeds test bounds: $name" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun JsonObject.text(name: String): String = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw AssertionError("Missing generated identity field: $name")

    private fun buildStatus(state: BuildState): String = "Native build status=${state.status}; failed tasks=" +
        state.steps.filter { it.status == StepStatus.Failed }.joinToString { it.name }.take(500)

    private fun deleteOwnedProject(project: Path) {
        require(project.fileName.toString().startsWith(PROJECT_PREFIX))
        Files.walkFileTree(project, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun publish(name: String, value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "\n$name=$value\n") })
    }

    companion object {
        private const val APPLICATION_ID = "dev.ide.reliability.nativefixture"
        private const val PROJECT_PREFIX = "ReliabilityNativeFixture"
    }
}
