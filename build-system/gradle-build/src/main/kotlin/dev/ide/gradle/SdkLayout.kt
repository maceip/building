package dev.ide.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * SDK pieces that are data: the license hash AGP checks so configure does not prompt,
 * `local.properties` `sdk.dir`, and `platforms/android-<api>/android.jar`.
 *
 * `local.properties` gains `sdk.dir` only. iOS, Firebase, and `ndk.dir` are not written:
 * those properties are how a multiplatform project turns on targets this build does not run.
 */
object SdkLayout {

    const val LICENSE_HASH = "24333f8a63b6825ea9c5514f83c2829b004d1fee"

    fun ensureLicense(androidHome: Path) {
        val file = androidHome.resolve("licenses").resolve("android-sdk-license")
        Files.createDirectories(file.parent)
        val existing = if (Files.isRegularFile(file)) file.readText() else ""
        if (!existing.contains(LICENSE_HASH)) {
            file.writeText(existing.trimEnd() + (if (existing.isBlank()) "" else "\n") + LICENSE_HASH + "\n")
        }
    }

    fun ensureSdkDir(projectDir: Path, androidHome: Path) {
        val file = projectDir.resolve("local.properties")
        val lines = if (Files.isRegularFile(file)) Files.readAllLines(file).toMutableList() else mutableListOf()
        val rendered = "sdk.dir=" + escape(androidHome.toAbsolutePath().toString())
        val index = lines.indexOfFirst { line ->
            val trimmed = line.trim()
            !trimmed.startsWith("#") && trimmed.substringBefore("=").trim() == "sdk.dir"
        }
        if (index >= 0) lines[index] = rendered else lines += rendered
        Files.createDirectories(file.parent)
        Files.write(file, lines)
    }

    /** [api] is the directory suffix: `34`, or `37.2` for a minor platform. */
    fun installPlatform(androidHome: Path, api: String, androidJar: Path) {
        val dir = androidHome.resolve("platforms").resolve("android-$api")
        Files.createDirectories(dir)
        Files.copy(androidJar, dir.resolve("android.jar"), StandardCopyOption.REPLACE_EXISTING)
        dir.resolve("source.properties").writeText("Pkg.Revision = $api\n")
    }

    fun hasPlatform(androidHome: Path, api: String): Boolean =
        Files.isRegularFile(androidHome.resolve("platforms").resolve("android-$api").resolve("android.jar"))

    private fun escape(path: String): String = path.replace("\\", "\\\\").replace(":", "\\:")
}
