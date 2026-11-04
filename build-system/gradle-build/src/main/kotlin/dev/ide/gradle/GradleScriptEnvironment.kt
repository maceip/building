package dev.ide.gradle

import java.io.File
import java.nio.file.Path

/**
 * A prepared, host-owned command search path for trusted Gradle scripts. Intentionally cannot
 * replace PREFIX, TERMUX__PREFIX, LD_PRELOAD or LD_LIBRARY_PATH on the Gradle JVM: an APK runtime
 * may need a different prefix and must switch environments in its own child process instead.
 */
class GradleScriptEnvironment(pathEntries: List<Path> = emptyList()) {
    val pathEntries: List<Path> = pathEntries.map { path ->
        require(path.isAbsolute) { "Script command directories must be absolute" }
        require(path.toString().none { it == File.pathSeparatorChar || it == '\u0000' || it == '\n' || it == '\r' }) {
            "Invalid script command directory"
        }
        path.normalize()
    }.distinct()

    /** Return a new map; neither the caller's map nor the IDE process environment is mutated. */
    fun applyTo(environment: Map<String, String>): Map<String, String> {
        if (pathEntries.isEmpty()) return environment.toMap()
        val additions = pathEntries.map(Path::toString)
        val previous = environment["PATH"]?.takeIf { it.isNotEmpty() }
            ?.split(File.pathSeparatorChar).orEmpty().filterNot { it in additions }
        return LinkedHashMap(environment).apply {
            put("PATH", (additions + previous).joinToString(File.pathSeparator))
        }
    }
}

/** The same prepared environment is used for the tooling JVM and the Gradle invocation. */
object GradleProcessEnvironment {
    fun create(host: GradleHost, javaHome: Path, scripts: GradleScriptEnvironment): Map<String, String> {
        val base = if (host.android) {
            val prefix = host.prefix() ?: error("the Android Gradle host has no prefix")
            PrefixProbe.environment(prefix, javaHome, host.libraryPath())
        } else {
            System.getenv()
        }
        val environment = LinkedHashMap(base).apply {
            put("JAVA_HOME", javaHome.toString())
            put("ANDROID_HOME", host.androidHome().toString())
            put("ANDROID_SDK_ROOT", host.androidHome().toString())
            put("GRADLE_USER_HOME", host.gradleUserHome().toString())
        }
        return scripts.applyTo(environment)
    }
}
