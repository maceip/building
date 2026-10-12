package dev.ide.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText

class ProjectVersionsTest {
    @Test
    fun onlyTreatsCmakeVersionsAsNativeToolchainRequirements() {
        val root = Files.createTempDirectory("cmake-version-project")
        val script = root.resolve("build.gradle.kts")
        script.writeText("version = \"3.2.1\"\n")
        assertEquals(emptySet<String>(), ProjectVersions.scan(root).cmake)
        for (declaration in listOf("version = \"3.31.6\"", "version '3.31.6'", "version(\"3.31.6\")")) {
            script.writeText("externalNativeBuild { cmake { $declaration } }\n")
            assertEquals(setOf("3.31.6"), ProjectVersions.scan(root).cmake, declaration)
        }
    }

    @Test
    fun readsNdkVersionsFromKotlinAndGroovyBuildScripts() {
        for (declaration in listOf("ndkVersion = \"29.0.13113456\"", "ndkVersion '29.0.13113456'", "ndkVersion(\"29.0.13113456\")")) {
            val root = Files.createTempDirectory("ndk-project")
            Files.createDirectories(root.resolve("app"))
            root.resolve("app/build.gradle.kts").writeText("android { $declaration }\n")
            assertEquals(setOf("29.0.13113456"), ProjectVersions.scan(root).ndk, declaration)
        }
    }

    @Test
    fun readsTheDaemonJvmRequiredByMultiplatformBuildLogic() {
        val root = Files.createTempDirectory("daemon-jvm-project")
        Files.createDirectories(root.resolve("gradle"))
        root.resolve("gradle/gradle-daemon-jvm.properties").writeText(
            "toolchainVersion=21\ntoolchainVendor=JETBRAINS\n",
        )
        root.resolve("build.gradle.kts").writeText("kotlin { jvmToolchain(17) }\n")

        val scan = ProjectVersions.scan(root)
        assertEquals(21, scan.toolchainMajor)
        assertEquals("JETBRAINS", scan.toolchainVendor)
        assertEquals(JdkSelection.Image.JDK_21, JdkSelection.select("9.5.1", scan.toolchainMajor, scan.toolchainVendor).image)
    }
}
