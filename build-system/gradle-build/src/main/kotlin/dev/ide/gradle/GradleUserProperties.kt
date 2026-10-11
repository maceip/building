package dev.ide.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * `GRADLE_USER_HOME/gradle.properties`, written by us. A project file that sets
 * `org.gradle.jvmargs=-Xmx6g` must not win: the Tooling API launch also passes the device
 * value as `-Dorg.gradle.jvmargs`, which outranks the project file. The copy here is what
 * a daemon started any other way still sees.
 */
object GradleUserProperties {

    const val IDLE_TIMEOUT_MS = 120_000

    fun heapMb(ramBytes: Long): Int {
        if (ramBytes <= 0L) return 512
        val quarterMb = ramBytes / 4L / (1024L * 1024L)
        return quarterMb.coerceIn(256L, 2048L).toInt()
    }

    fun jvmArgs(ramBytes: Long): String = "-Xmx${heapMb(ramBytes)}m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8"

    fun write(
        gradleUserHome: Path,
        ramBytes: Long,
        jdkHomes: List<Path>,
        aapt2: Path?,
    ) {
        val file = gradleUserHome.resolve("gradle.properties")
        Files.createDirectories(gradleUserHome)
        val lines = ArrayList<String>()
        if (aapt2 != null) lines += "android.aapt2FromMavenOverride=${aapt2.toAbsolutePath()}"
        lines += "org.gradle.jvmargs=${jvmArgs(ramBytes)}"
        lines += "org.gradle.workers.max=2"
        lines += "kotlin.compiler.execution.strategy=in-process"
        lines += "org.gradle.daemon.idletimeout=$IDLE_TIMEOUT_MS"
        lines += "org.gradle.java.installations.auto-download=false"
        if (jdkHomes.isNotEmpty()) {
            lines += "org.gradle.java.installations.paths=" + jdkHomes.joinToString(",") { it.toAbsolutePath().toString() }
        }
        file.writeText(lines.joinToString("\n") + "\n")
    }

    /** Arguments every Tooling API launch passes, in addition to the user properties file. */
    fun launchArgs(ramBytes: Long, jdkHomes: List<Path>, aapt2: Path?, initScript: Path, abiReport: Path?): List<String> {
        val args = ArrayList<String>()
        args += "--init-script"
        args += initScript.toAbsolutePath().toString()
        args += "-Dorg.gradle.jvmargs=${jvmArgs(ramBytes)}"
        args += "-Dorg.gradle.java.installations.auto-download=false"
        if (jdkHomes.isNotEmpty()) {
            args += "-Dorg.gradle.java.installations.paths=" + jdkHomes.joinToString(",") { it.toAbsolutePath().toString() }
        }
        if (aapt2 != null) args += "-Pandroid.aapt2FromMavenOverride=${aapt2.toAbsolutePath()}"
        if (abiReport != null) args += "-Pdev.ide.abi.report=${abiReport.toAbsolutePath()}"
        return args
    }
}
