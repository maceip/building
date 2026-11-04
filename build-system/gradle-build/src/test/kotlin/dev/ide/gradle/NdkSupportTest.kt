package dev.ide.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

class NdkSupportTest {

    @Test
    fun theLowestMinSdkInTheScriptsIsKept() {
        val root = Files.createTempDirectory("minsdk")
        root.resolve("build.gradle").writeText(
            """
            android {
                defaultConfig {
                    minSdkVersion 26
                    minSdk = 24
                }
            }
            """.trimIndent(),
        )
        assertEquals(24, ProjectVersions.scan(root).minSdk)
    }

    @Test
    fun aCmakeVersionMismatchIsRetriedAndAnUnrelatedFailureIsNot() {
        assertEquals("4.4.4", CmakeFallback.parseVersion("cmake version 4.4.4\nCMake suite maintained."))
        assertTrue(CmakeFallback.rejected("CMake '3.22.1' was not found", "version does not match 4.4.4"))
        assertFalse(CmakeFallback.rejected("Could not resolve com.example:lib:1.0"))
    }

    @Test
    fun anNdkPluginApkUnpacksTheSysrootAndDropsNothingOutsideIt() {
        val root = Files.createTempDirectory("plugin-apk")
        val inner = root.resolve("toolchain.zip")
        zip(inner, mapOf("sysroot/usr/include/stdio.h" to "int", "../escape.h" to "no"))
        val apk = root.resolve("plugin.apk")
        zipBytes(apk, mapOf("assets/toolchain.zip".toByteArray() to inner.readBytes()))
        assertTrue(NdkPluginTree.containsToolchain(apk))
        val dest = root.resolve("out")
        assertThrows(IllegalArgumentException::class.java) {
            NdkPluginTree.unpackFromApk(apk, dest)
        }
    }

    @Test
    fun anNdkPluginApkUnpacksAContainedSysroot() {
        val root = Files.createTempDirectory("plugin-apk-ok")
        val inner = root.resolve("toolchain.zip")
        zipBytes(inner, mapOf("sysroot/usr/include/stdio.h".toByteArray() to "int".toByteArray()))
        val apk = root.resolve("plugin.apk")
        zipBytes(apk, mapOf("assets/toolchain.zip".toByteArray() to inner.readBytes()))
        val dest = root.resolve("toolchain")
        NdkPluginTree.unpackFromApk(apk, dest)
        assertEquals("int", dest.resolve("sysroot/usr/include/stdio.h").readText())
        assertTrue(NdkPluginTree.isReady(dest))
        assertEquals(
            root.resolve("home/plugin-data/dev.codeassist.ndk/toolchain"),
            NdkPluginTree.unpacked(root.resolve("home")),
        )
    }

    @Test
    fun arm64LibrariesAreKeptAndTheBundledClangIsDiscarded() {
        val root = Files.createTempDirectory("sysroot-zip")
        val zip = root.resolve("ndk.zip")
        val marker = "android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/"
        zipBytes(
            zip,
            mapOf(
                (marker + "24/libc.so").toByteArray() to byteArrayOf(24),
                (marker + "26/libc.so").toByteArray() to byteArrayOf(99),
                "android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/bin/clang".toByteArray() to byteArrayOf(1, 2, 3),
            ),
        )
        val cache = root.resolve("cache")
        val catalog = Arm64Sysroot.Catalog(url = "unused", size = Files.size(zip), algorithm = "SHA-1", checksum = sha1(zip))
        var calls = 0
        Arm64Sysroot.ensure(cache, catalog) { _, dest ->
            calls++
            Files.copy(zip, dest)
        }
        assertEquals(1, calls)
        assertEquals(listOf<Byte>(24), cache.resolve("usr/lib/aarch64-linux-android/24/libc.so").readBytes().toList())
        assertTrue(!Files.exists(cache.resolve("bin/clang")))
        val ndkSysroot = root.resolve("ndk-sysroot")
        val existing = ndkSysroot.resolve("usr/lib/aarch64-linux-android/26/libc.so")
        Files.createDirectories(existing.parent)
        Files.write(existing, byteArrayOf(26))
        Arm64Sysroot.overlay(ndkSysroot, cache)
        assertEquals(listOf<Byte>(26), existing.readBytes().toList())
        assertEquals(listOf<Byte>(24), ndkSysroot.resolve("usr/lib/aarch64-linux-android/24/libc.so").readBytes().toList())
        val pinned = Arm64Sysroot.catalog()
        assertEquals("https://dl.google.com/android/repository/android-ndk-r27c-linux.zip", pinned.url)
        assertEquals("090e8083a715fdb1a3e402d0763c388abb03fb4e", pinned.checksum)
        assertEquals(663987688L, pinned.size)
    }

    @Test
    fun aSysrootChecksumMismatchIsNotRetried() {
        val root = Files.createTempDirectory("sysroot-mismatch")
        val zip = root.resolve("ndk.zip")
        zipBytes(zip, mapOf("readme".toByteArray() to byteArrayOf(1)))
        var calls = 0
        val catalog = Arm64Sysroot.Catalog("unused", Files.size(zip), "SHA-1", "0000000000000000000000000000000000000000")
        assertThrows(Arm64Sysroot.ChecksumMismatch::class.java) {
            Arm64Sysroot.ensure(root.resolve("cache"), catalog) { _, dest ->
                calls++
                Files.copy(zip, dest)
            }
        }
        assertEquals(1, calls)
    }

    private fun sha1(file: java.nio.file.Path): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        md.update(file.readBytes())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun zip(file: java.nio.file.Path, entries: Map<String, String>) {
        zipBytes(file, entries.mapKeys { it.key.toByteArray() }.mapValues { it.value.toByteArray() })
    }

    private fun zipBytes(file: java.nio.file.Path, entries: Map<ByteArray, ByteArray>) {
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(String(name, Charsets.ISO_8859_1)))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
