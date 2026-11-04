package dev.ide.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Versions a configure needs before the tooling model exists: the wrapper, the toolchain pin,
 * and the SDK/NDK/CMake strings named in the scripts. A version that only a convention plugin
 * computes shows up in the tooling model instead, and configure is retried once.
 *
 * This is not [dev.ide] GradleImport. That reader stays the editor's pre-toolchain path and its
 * module spec is unchanged.
 */
object ProjectVersions {

    data class Scan(
        val gradleVersion: String?,
        val agpVersion: String?,
        val toolchainMajor: Int?,
        val toolchainVendor: String?,
        val buildTools: Set<String>,
        val ndk: Set<String>,
        val platforms: Set<String>,
        val cmake: Set<String>,
        val nativeBuild: Boolean = false,
        val minSdk: Int? = null,
    )

    fun scan(root: Path): Scan {
        val texts = buildTexts(root)
        val properties = readIfExists(root.resolve("gradle.properties"))
        val daemonJvm = readIfExists(root.resolve("gradle/gradle-daemon-jvm.properties"))
        val catalog = readIfExists(root.resolve("gradle/libs.versions.toml"))
        val wrapper = wrapperVersion(root)
        val majors = mutableListOf<Int>()
        property(daemonJvm, "toolchainVersion")?.toIntOrNull()?.let { majors += it }
        property(properties, "jvm.toolchain.version")?.toIntOrNull()?.let { majors += it }
        Regex("""jvmToolchain\s*\(\s*(\d+)\s*\)""").findAll(texts).forEach { majors += it.groupValues[1].toInt() }
        Regex("""JavaLanguageVersion\.of\s*\(\s*(\d+)\s*\)""").findAll(texts).forEach { majors += it.groupValues[1].toInt() }
        val buildTools = linkedSetOf<String>()
        Regex("""buildToolsVersion(?:\s*\(|\s*=|\s+)\"?([0-9][0-9.]*)\"?""").findAll(texts).forEach {
            buildTools += it.groupValues[1]
        }
        val ndk = linkedSetOf<String>()
        Regex("""ndkVersion\s*(?:=\s*|\(\s*)?["']([0-9][0-9.]*)["']""").findAll(texts).forEach { ndk += it.groupValues[1] }
        val platforms = linkedSetOf<String>()
        Regex("""compileSdk(?:Version)?(?:\s*=|\s+)\s*(\d{2})""").findAll(texts).forEach { platforms += it.groupValues[1] }
        Regex("""release\(\s*\"(\d+(?:\.\d+)?)\"\s*\)""").findAll(texts).forEach { platforms += it.groupValues[1] }
        val cmake = linkedSetOf<String>()
        Regex("""cmake\s*\{[^{}]*?\bversion\s*(?:=\s*|\(\s*)?["'](\d+\.\d+\.\d+)["']""").findAll(texts).forEach { match ->
            cmake += match.groupValues[1]
        }
        val minSdks = Regex("""minSdk(?:Version)?\s*(?:=)?\s*(\d+)""").findAll(texts)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
        return Scan(
            gradleVersion = wrapper,
            agpVersion = agpVersion(texts, catalog),
            toolchainMajor = majors.maxOrNull(),
            toolchainVendor = property(daemonJvm, "toolchainVendor") ?: property(properties, "jvm.toolchain.vendor"),
            buildTools = buildTools,
            ndk = ndk,
            platforms = platforms,
            cmake = cmake,
            nativeBuild = Regex("""(?:externalNativeBuild|ndkBuild)\s*[({]""").containsMatchIn(texts),
            minSdk = minSdks.minOrNull(),
        )
    }

    fun wrapperVersion(root: Path): String? {
        val text = readIfExists(root.resolve("gradle/wrapper/gradle-wrapper.properties")) ?: return null
        val url = Regex("""distributionUrl=.*gradle-([0-9]+(?:\.[0-9]+)*)-""").find(text) ?: return null
        return url.groupValues[1]
    }

    /** AGP 9's default NDK is r28c. AGP 8.4's default NDK is 26.1.10909125 and build-tools 34.0.0. */
    fun defaultNdk(agp: String?): String {
        val major = majorOf(agp)
        return if (major != null && major < 9) "26.1.10909125" else "28.2.13676358"
    }

    fun defaultBuildTools(agp: String?): String {
        val major = majorOf(agp)
        return if (major != null && major < 9) "34.0.0" else "35.0.0"
    }

    fun defaultCmake(@Suppress("UNUSED_PARAMETER") agp: String?): String = "3.22.1"

    private fun agpVersion(texts: String, catalog: String?): String? {
        val patterns = listOf(
            Regex("""com\.android\.(?:application|library)[^\n]{0,80}?version\s*[\"']([0-9][0-9.]*)"""),
            Regex("""agp\s*=\s*\"([0-9][0-9.]*)\""""),
        )
        val haystack = texts + "\n" + (catalog ?: "")
        for (pattern in patterns) {
            pattern.find(haystack)?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun majorOf(version: String?): Int? =
        version?.substringBefore('.')?.toIntOrNull()

    private fun property(text: String?, key: String): String? {
        if (text == null) return null
        val line = text.lineSequence().firstOrNull { raw ->
            val t = raw.trim()
            !t.startsWith("#") && t.substringBefore("=").trim() == key
        } ?: return null
        return line.substringAfter("=").trim().trim('"')
    }

    private fun buildTexts(root: Path): String {
        val files = ArrayList<Path>()
        fun consider(path: Path) {
            if (!Files.isRegularFile(path)) return
            val name = path.fileName.toString()
            if (name == "build.gradle" || name == "build.gradle.kts" || name == "settings.gradle" ||
                name == "settings.gradle.kts" || name == "gradle.properties"
            ) files.add(path)
        }
        consider(root.resolve("settings.gradle"))
        consider(root.resolve("settings.gradle.kts"))
        consider(root.resolve("build.gradle"))
        consider(root.resolve("build.gradle.kts"))
        consider(root.resolve("gradle.properties"))
        if (Files.isDirectory(root)) {
            Files.list(root).use { stream ->
                stream.filter { Files.isDirectory(it) }.forEach { dir ->
                    val name = dir.fileName.toString()
                    if (name == "build" || name.startsWith(".")) return@forEach
                    consider(dir.resolve("build.gradle"))
                    consider(dir.resolve("build.gradle.kts"))
                }
            }
        }
        return files.joinToString("\n") { readIfExists(it).orEmpty() }
    }

    private fun readIfExists(path: Path): String? =
        if (Files.isRegularFile(path)) runCatching { path.readText() }.getOrNull() else null
}
