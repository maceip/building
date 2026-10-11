package dev.ide.gradle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/**
 * The fat jar, on the host JDK, initializes a fixture and returns its module and variant.
 * No prefix and no linker: this is the desktop shape of the same server the phone execs.
 */
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class ToolingModelTest {

    @Test
    fun initializeReturnsTheAppModuleAndMainVariant() {
        val jar = System.getProperty("dev.ide.tooling.jar") ?: error("dev.ide.tooling.jar is not set")
        val gradleHome = System.getProperty("dev.ide.gradle.home") ?: error("dev.ide.gradle.home is not set")
        val init = System.getProperty("dev.ide.init.script") ?: error("dev.ide.init.script is not set")
        val root = Files.createTempDirectory("tooling-fixture")
        root.resolve("settings.gradle").writeText(
            """
            rootProject.name = 'fixture'
            include 'app'
            """.trimIndent() + "\n",
        )
        Files.createDirectories(root.resolve("app"))
        root.resolve("app/build.gradle").writeText(
            """
            plugins { id 'java-library' }
            """.trimIndent() + "\n",
        )
        val userHome = Files.createTempDirectory("gradle-user-home")
        val javaHome = System.getProperty("java.home")
        val process = ProcessBuilder(Path.of(javaHome, "bin", "java").toString(), "-jar", jar)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        val client = ToolingClient(process)
        try {
            client.initialize(
                projectDir = root.toAbsolutePath().toString(),
                distributionType = "LOCAL",
                distributionValue = gradleHome,
                javaHome = javaHome,
                androidHome = null,
                gradleUserHome = userHome.toAbsolutePath().toString(),
                args = listOf("--offline", "--no-daemon", "--init-script", init),
                jvmArgs = listOf("-Xmx512m"),
                env = mapOf("JAVA_HOME" to javaHome),
            )
            val call = client.fetchModel()
            val snapshot = runCatching { GradleProjectSnapshot.parse(call.rawResult) }.getOrElse {
                GradleProjectSnapshot.parse(ToolingClient.encode(call.result))
            }
            val app = snapshot.modules.firstOrNull { it.name == "app" }
                ?: error("modules=${snapshot.modules.map { it.name }} raw=${call.rawResult.take(500)}")
            assertTrue(app.variants.any { it.name == "main" }, app.variants.toString())
        } catch (e: ToolingClient.ToolingException) {
            throw AssertionError(e.message + "\n" + e.stderr, e)
        } finally {
            client.close()
        }
    }
}
