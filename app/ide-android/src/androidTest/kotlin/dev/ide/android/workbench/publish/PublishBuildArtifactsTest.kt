package dev.ide.android.workbench.publish

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.ui.backend.BuildLogLine
import dev.ide.ui.backend.BuildService
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.RunTaskOption
import dev.ide.workbench.publish.PlayPublishingException
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublishBuildArtifactsTest {
    @Test
    fun onlyExplicitBundleTasksAreEligibleAndReleaseIsPreferred() {
        val tasks = listOf(
            task("gradle:assemble"), task("run:app"), task("assemble:app:release"),
            task("bundle:app:debug"), task("bundle:app:release"), task("gradle:bundle"),
            task("gradle:bundle:app:release"), task("gradle:bundleOther"),
        )
        val selected = publishBundleTasks(tasks)
        assertEquals(4, selected.size)
        assertTrue(selected.all(::isPublishBundleTask))
        assertTrue(selected.take(2).all { it.id.endsWith(":release") })
        assertFalse(selected.any { "assemble" in it.id || it.id == "gradle:bundleOther" })
    }

    @Test
    fun aPreviousSuccessDoesNotCompleteANewBuild() = runBlocking {
        val build = FakeBuild(BuildState(status = RunStatus.Succeeded))
        val result = async { runPublishBundleTask(build, task("bundle:app:release")) }
        delay(60)
        assertFalse(result.isCompleted)
        assertEquals(listOf("bundle:app:release"), build.launched)
        build.buildState.value = BuildState(status = RunStatus.Running, moduleName = "app")
        delay(60)
        build.buildState.value = BuildState(status = RunStatus.Succeeded, moduleName = "app", elapsedMs = 100)
        assertEquals(RunStatus.Succeeded, withTimeout(1000) { result.await() }.status)
    }

    @Test
    fun immediateBuildFailureIsObservedWithoutSelectingStaleArtifacts() = runBlocking {
        val build = FakeBuild(BuildState())
        build.onRun = { build.buildState.value = BuildState(status = RunStatus.Failed, log = listOf(BuildLogLine("Missing build tools"))) }
        val error = try { runPublishBundleTask(build, task("gradle:bundle:app:release")); null }
        catch (failure: PlayPublishingException) { failure }
        assertNotNull(error)
        assertTrue(error!!.message.contains("failed"))
    }

    @Test
    fun anotherRunningBuildCannotBeHijacked() = runBlocking {
        val build = FakeBuild(BuildState(status = RunStatus.Running))
        val error = try { runPublishBundleTask(build, task("bundle:app:release")); null }
        catch (failure: PlayPublishingException) { failure }
        assertNotNull(error)
        assertTrue(build.launched.isEmpty())
    }

    @Test
    fun scanRequiresCompletedSuccessAndRejectsOutsideFilesAndSymlinks() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val root = Files.createTempDirectory(cache.toPath(), "publish-build-root-").toFile()
        val outside = Files.createTempDirectory(cache.toPath(), "publish-build-outside-").toFile()
        try {
            val bundle = File(root, "app/build/outputs/bundle/release/app-release.aab").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            val unrelated = File(root, "downloads/old.aab").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
            val external = File(outside, "foreign.aab").apply { writeBytes(byteArrayOf(1)) }
            File(root, "app/build/outputs/bundle/release/linked.aab").also { Files.createSymbolicLink(it.toPath(), external.toPath()) }
            val successful = BuildState(status = RunStatus.Succeeded, log = listOf(
                BuildLogLine("Signed bundle: ${bundle.absolutePath}"),
                BuildLogLine("Signed bundle: ${external.absolutePath}"),
                BuildLogLine("Signed bundle: ${unrelated.absolutePath}"),
            ))
            assertTrue(findCompletedBundleArtifacts(root.path, successful.copy(status = RunStatus.Failed)).isEmpty())
            assertTrue(findCompletedBundleArtifacts("", successful).isEmpty())
            val candidates = findCompletedBundleArtifacts(root.path, successful)
            assertEquals(1, candidates.size)
            assertEquals(bundle.canonicalFile, candidates.single().file)
            assertTrue(candidates.single().reportedByBuild)
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun cachedArtifactFoundAfterSuccessIsNotMisrepresentedAsBuildReported() = runBlocking {
        val root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "publish-cached-").toFile()
        try {
            File(root, "build/outputs/bundle/release/reused.aab").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)); setLastModified(1_000) }
            val candidate = findCompletedBundleArtifacts(root.path, BuildState(status = RunStatus.Succeeded)).single()
            assertFalse(candidate.reportedByBuild)
            assertEquals(1_000, candidate.modifiedAt)
        } finally { root.deleteRecursively() }
    }

    private fun task(id: String) = RunTaskOption(id, id, "android")
    private class FakeBuild(initial: BuildState) : BuildService {
        override val buildState = MutableStateFlow(initial)
        val launched = mutableListOf<String>()
        var onRun: (() -> Unit)? = null
        override fun runTask(id: String) { launched += id; onRun?.invoke() }
        override fun runBuild() = throw AssertionError("Publisher must never use runBuild")
        override fun stopBuild() = Unit
    }
}
