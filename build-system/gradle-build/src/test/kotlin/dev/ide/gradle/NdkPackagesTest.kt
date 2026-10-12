package dev.ide.gradle

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NdkPackagesTest {
    @Test
    fun installsNdkDataWithoutDesktopExecutables() {
        val work = Files.createTempDirectory("ndk-data-test")
        val archive = work.resolve("ndk.zip")
        val paths = listOf(
            "build/cmake/android.toolchain.cmake",
            "meta/platforms.json",
            "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/android/api-level.h",
            "toolchains/llvm/prebuilt/linux-x86_64/lib/clang/20/include/stddef.h",
            "toolchains/llvm/prebuilt/linux-x86_64/bin/clang",
            "prebuilt/linux-x86_64/bin/make",
        )
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            for (path in paths) {
                zip.putNextEntry(ZipEntry("android-ndk-r29/$path"))
                zip.write("fixture".toByteArray())
                zip.closeEntry()
            }
        }
        val dest = work.resolve("installed")
        NdkPackages.extractData(archive, dest)
        for (path in paths.take(4)) assertTrue(Files.isRegularFile(dest.resolve(path)), path)
        for (path in paths.drop(4)) assertFalse(Files.exists(dest.resolve(path)), path)
    }

    @Test
    fun rejectsPathsEscapingTheNdkDirectory() {
        val work = Files.createTempDirectory("ndk-data-path-test")
        val archive = work.resolve("ndk.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("android-ndk-r29/build/../../outside"))
            zip.write("bad".toByteArray())
            zip.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) { NdkPackages.extractData(archive, work.resolve("installed")) }
        assertFalse(Files.exists(work.resolve("outside")))
    }
}
