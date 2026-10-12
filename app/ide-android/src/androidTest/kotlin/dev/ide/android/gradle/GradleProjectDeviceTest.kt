package dev.ide.android.gradle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.gradle.GradleToolchain
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.nio.file.Path

/** Opt-in acceptance test: pass gradleProject and gradleTask instrumentation arguments. */
@RunWith(AndroidJUnit4::class)
class GradleProjectDeviceTest {
    @Test
    fun buildsAndroidProject() {
        val args = InstrumentationRegistry.getArguments()
        val directory = args.getString("gradleProject")
        val task = args.getString("gradleTask")
        assumeTrue("Pass gradleProject and gradleTask to run a real device build", directory != null && task != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val project = Path.of(directory!!)
        val label = args.getString("gradleLabel", "project").replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val report = context.filesDir.resolve("gradle-acceptance/$label.log")
        report.parentFile!!.mkdirs()
        report.writeText("Project: $project\nTask: $task\nPackage: ${context.packageName}\n")
        Files.walk(project).use { paths ->
            paths.filter { it.toString().contains("/build/outputs/apk/") && it.toString().endsWith(".apk") && Files.isRegularFile(it) }
                .forEach { Files.delete(it) }
        }
        val started = System.currentTimeMillis()
        try {
            assertTrue("Project directory is missing", Files.isDirectory(project))
            val host = AndroidGradleHost(context)
            assertTrue("The on-device JDK is not installed", host.available())
            val snapshot = GradleToolchain.execute(project, host, listOf(task!!)) { event ->
                report.appendText("$event\n")
            }
            args.getString("expectedApplicationId")?.let { expected ->
                assertTrue("Imported application ID must be $expected", snapshot?.variants()?.any { it.applicationId == expected } == true)
            }
            val apks = Files.walk(project).use { paths ->
                paths.filter {
                    it.toString().contains("/build/outputs/apk/") &&
                        it.toString().endsWith(".apk") && Files.isRegularFile(it) &&
                        Files.size(it) > 0 && Files.getLastModifiedTime(it).toMillis() >= started
                }.toList()
            }
            assertTrue("Gradle did not produce a fresh APK", apks.isNotEmpty())
            report.appendText("PASS: ${apks.joinToString()}\n")
        } catch (error: Throwable) {
            report.appendText("FAIL: ${error.stackTraceToString()}\n")
            throw error
        }
    }
}
