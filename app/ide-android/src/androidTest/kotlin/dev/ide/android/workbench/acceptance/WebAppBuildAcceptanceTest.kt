package dev.ide.android.workbench.acceptance

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.AndroidIde
import dev.ide.ui.backend.BuildService
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.RunStatus
import dev.ide.workbench.webapp.StaticWebAppPackager
import dev.ide.workbench.webapp.WebAppPackageRequest
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real packager -> native IDE build -> signed debug APK on ART. No host Gradle, downloaded dependency,
 * copied prebuilt APK, PackageInstaller or androidRun task substitutes for the actual assemble task.
 * Main may pull/install/launch the retained APK separately; this test does not claim a UI launch pass.
 */
@RunWith(AndroidJUnit4::class)
class WebAppBuildAcceptanceTest {
    @Test(timeout = 300_000)
    fun buildGeneratedDebugApk() = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            withTimeout(270_000) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val context = instrumentation.targetContext
                val previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
                var bootstrapCrashHandler: Thread.UncaughtExceptionHandler? = null
                var session: AndroidIde.Session? = null
                var generatedRoot: Path? = null
                var buildStarted = false
                var buildFinished = false
                AcceptanceWorkspace("web-native-build").use { source ->
                    try {
                        val html = """
                            <!doctype html><html><head><meta charset="utf-8">
                            <meta name="viewport" content="width=device-width, initial-scale=1">
                            <title>Offline APK acceptance</title><link rel="stylesheet" href="style.css"></head>
                            <body><h1>Offline APK acceptance</h1><p id="status">Loading local JavaScript</p>
                            <button id="counter" type="button">Clicks: 0</button><script src="app.js"></script></body></html>
                        """.trimIndent()
                        source.write("dist/index.html", html)
                        source.write("dist/style.css", "body{font:20px sans-serif;padding:24px;color:#182430;background:#fff}button{font:inherit;padding:16px}\n")
                        source.write("dist/app.js", """
                            'use strict';
                            document.getElementById('status').textContent = 'Local JavaScript ready';
                            let count = Number(localStorage.getItem('acceptance-count') || '0');
                            const button = document.getElementById('counter');
                            const render = () => { button.textContent = 'Clicks: ' + count; };
                            button.onclick = () => { count++; localStorage.setItem('acceptance-count', String(count)); render(); };
                            render();
                        """.trimIndent())

                        // The real manager uses externalHome/codeassist/projects, not externalHome/projects.
                        val projects = Files.createDirectories(AndroidIde.projectsDir(context).toPath()).toRealPath()
                        val packager = StaticWebAppPackager()
                        val review = packager.review(WebAppPackageRequest(
                            workspaceRoot = source.root,
                            staticRoot = "dist",
                            outputParent = projects,
                            projectName = "WorkbenchWebAcceptance" + UUID.randomUUID().toString().replace("-", ""),
                            applicationId = APPLICATION_ID,
                            appLabel = "Offline APK acceptance",
                            versionCode = 17,
                            versionName = "1.0.17",
                            spaFallback = true,
                            allowExternalHttps = false,
                        ))
                        val generated = packager.create(review)
                        generatedRoot = generated.projectRoot
                        assertFalse("A newly generated project must have no prebuilt APK", Files.exists(generated.debugApk))
                        assertEquals(3, generated.assetCount)
                        publish("WORKBENCH_WEBAPP_PROJECT", generated.projectRoot.toString())

                        // SDK/aapt2/D8/debug signing are the installed IDE's real ports. No settings or
                        // existing project are changed to select an easier runner or skip compilation.
                        val openedSession = AndroidIde.bootstrap(context)
                        session = openedSession
                        bootstrapCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
                        val backend = openedSession.backend
                        assertTrue("The generated native project must open in the real IDE backend",
                            backend.projects.openProject(generated.projectRoot.toString()))
                        assertTrue(backend.build.supported())
                        assertEquals(generated.projectRoot.toString(), backend.project.rootPath)
                        assertTrue("The generated app must expose a native debug task", backend.build.runTasks().any {
                            it.id == ASSEMBLE_TASK || it.id == "androidRun:app:debug"
                        })
                        buildStarted = true
                        val completed = assemble(backend.build)
                        buildFinished = true
                        assertEquals(buildSummary(completed), RunStatus.Succeeded, completed.status)
                        assertEquals("app", completed.moduleName)
                        assertTrue("Successful state must include the actual task graph", completed.steps.isNotEmpty())
                        assertTrue("Assemble succeeded but its signed APK is missing", Files.isRegularFile(generated.debugApk, NOFOLLOW_LINKS))
                        assertTrue("Native debug APK must contain actual compiled output", Files.size(generated.debugApk) > 1_024)
                        assertTrue(generated.debugApk.toRealPath().startsWith(generated.projectRoot.toRealPath()))
                        verifyApk(generated.debugApk, html, generated.assetManifestSha256)

                        val exported = retainApk(generated.debugApk, context.cacheDir.toPath())
                        assertEquals(hash(generated.debugApk), hash(exported))
                        publish("WORKBENCH_WEBAPP_APK", exported.toString())
                        publish("WORKBENCH_WEBAPP_APK_SHA256", hash(exported))
                        publish("WORKBENCH_WEBAPP_BUILD_ELAPSED_MS", completed.elapsedMs.toString())
                    } finally {
                        // Never delete source/build files while an owned build could still be writing.
                        // On an unconfirmed timeout retain the unique project for investigation instead.
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
                                generatedRoot?.let { project ->
                                    if (!buildStarted || buildFinished) deleteOwnedProject(project)
                                    else publish("WORKBENCH_WEBAPP_RETAINED_PROJECT", project.toString())
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Subscribe BEFORE launch: neither an old successful state nor an optimistic Running can pass. */
    private suspend fun assemble(build: BuildService): BuildState = coroutineScope {
        assertFalse("This test owns a new idle build session", build.buildState.value.status == RunStatus.Running)
        val before = build.buildState.value
        var sawRunning = false
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            build.buildState.first { state ->
                when (state.status) {
                    RunStatus.Running -> { sawRunning = true; false }
                    RunStatus.Succeeded -> sawRunning
                    RunStatus.Failed -> sawRunning || state != before
                    RunStatus.Idle -> false
                }
            }
        }
        try {
            val result = withTimeout(180_000) {
                build.runTask(ASSEMBLE_TASK) // Build only: androidRun would prompt/install and is forbidden here.
                completion.await()
            }
            assertTrue("No Running -> terminal transition was observed. ${buildSummary(result)}", sawRunning)
            result
        } catch (_: TimeoutCancellationException) {
            throw AssertionError("Native assemble did not finish within 180 seconds. ${buildSummary(build.buildState.value)}")
        } finally {
            completion.cancel()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: Path, html: String, manifestSha256: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageArchiveInfo(apk.toString(),
            PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNATURES)
        assertNotNull("Android PackageManager must parse the built and signed APK", info)
        requireNotNull(info)
        assertEquals(APPLICATION_ID, info.packageName)
        assertEquals(17L, if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
        assertEquals("1.0.17", info.versionName)
        assertTrue("A signed APK must carry a parsed signer", !info.signatures.isNullOrEmpty())
        assertTrue("Generated wrapper activity missing", info.activities.orEmpty().any { it.name == "$APPLICATION_ID.MainActivity" })
        assertFalse("Offline wrapper must not gain Internet permission", info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        ZipFile(apk.toFile()).use { zip ->
            for (name in listOf("AndroidManifest.xml", "classes.dex", "resources.arsc")) {
                assertTrue("Actual compiled APK entry is missing: $name", (zip.getEntry(name)?.size ?: 0) > 0)
            }
            val index = requireNotNull(zip.getEntry("assets/web/index.html"))
            assertEquals(html, zip.getInputStream(index).bufferedReader().use { it.readText() })
            val manifest = requireNotNull(zip.getEntry("assets/webapp-manifest.json"))
            val json = org.json.JSONObject(zip.getInputStream(manifest).bufferedReader().use { it.readText() })
            assertEquals(APPLICATION_ID, json.getString("applicationId"))
            assertEquals(17, json.getInt("versionCode"))
            assertEquals(manifestSha256, json.getString("assetManifestSha256"))
        }
    }

    /** Content-addressed export never overwrites another run's artifact or any unrelated cache file. */
    private fun retainApk(apk: Path, cache: Path): Path {
        var directory = cache.toRealPath()
        for (part in listOf("exports", "workbench-acceptance", "webapp-build-v17")) {
            directory = directory.resolve(part)
            if (!Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory)
            require(Files.isDirectory(directory, NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory))
        }
        val digest = hash(apk)
        val destination = directory.resolve("$APPLICATION_ID-v17-$digest.apk")
        if (Files.exists(destination, NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(destination, NOFOLLOW_LINKS) && Files.size(destination) == Files.size(apk) && hash(destination) == digest)
        } else {
            Files.copy(apk, destination) // No REPLACE_EXISTING: a collision fails rather than overwrites.
        }
        return destination
    }

    private fun deleteOwnedProject(project: Path) {
        require(project.fileName.toString().startsWith("WorkbenchWebAcceptance"))
        Files.walkFileTree(project, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun hash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun buildSummary(state: BuildState): String = "Status=${state.status}\n" +
        state.log.takeLast(40).joinToString("\n") { it.message }.takeLast(12_000)

    private fun publish(key: String, value: String) {
        val line = "$key=$value"
        Log.i("WorkbenchAcceptance", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "\n$line\n") })
    }

    private companion object {
        const val APPLICATION_ID = "dev.fixture.offline"
        const val ASSEMBLE_TASK = "assemble:app:debug"
    }
}
