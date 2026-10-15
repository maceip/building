package dev.ide.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * The read-only half of the on-device clang: headers, one arm64 API level of link libraries, and
 * `lib-clang/18`. The NDK plugin unpacks `assets/toolchain.zip` into its data directory. Gradle
 * copies that tree into the synthetic NDK so CMake's clang finds it next to `bin/clang`.
 */
object NdkPluginTree {

    const val PLUGIN_ID = "dev.codeassist.ndk"

    fun unpacked(home: Path): Path = home.resolve("plugin-data").resolve(PLUGIN_ID).resolve("toolchain")

    fun isReady(root: Path): Boolean = Files.isDirectory(root.resolve("sysroot"))

    fun containsToolchain(apk: Path): Boolean {
        if (!Files.isRegularFile(apk)) return false
        return ZipFile(apk.toFile()).use { zip -> zip.getEntry("assets/toolchain.zip") != null }
    }

    /** Unpack `assets/toolchain.zip` from a plugin APK into [dest]. An entry that escapes [dest] is refused. */
    fun unpackFromApk(apk: Path, dest: Path) {
        val nested = Files.createTempFile("toolchain", ".zip")
        try {
            ZipFile(apk.toFile()).use { zip ->
                val entry = zip.getEntry("assets/toolchain.zip")
                    ?: error("the NDK plugin APK has no assets/toolchain.zip")
                zip.getInputStream(entry).use { input ->
                    Files.copy(input, nested, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
            if (Files.exists(dest)) dest.toFile().deleteRecursively()
            Files.createDirectories(dest)
            ZipInputStream(Files.newInputStream(nested)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = dest.resolve(entry.name).normalize()
                    require(target.startsWith(dest)) { "entry escapes the toolchain directory: ${entry.name}" }
                    if (entry.isDirectory) Files.createDirectories(target)
                    else {
                        Files.createDirectories(target.parent)
                        Files.copy(zip, target)
                    }
                    zip.closeEntry()
                }
            }
        } finally {
            Files.deleteIfExists(nested)
        }
    }
}
