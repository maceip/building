package dev.ide.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readText

class ToolchainLayoutTest {

    @Test
    fun aFakeDriverBecomesClangUnderTheRequestedRevision() {
        val home = Files.createTempDirectory("sdk-layout")
        val driver = home.resolve("libllvmtools.so")
        Files.write(driver, byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1, 2, 3))
        val cmake = home.resolve("cmake-bin")
        val ninja = home.resolve("ninja-bin")
        val aapt2 = home.resolve("libaapt2.so")
        Files.write(cmake, byteArrayOf(1))
        Files.write(ninja, byteArrayOf(2))
        Files.write(aapt2, byteArrayOf(3))

        val revision = "28.2.13676358"
        ToolchainLayout.installNdk(home, revision, driver, null)
        ToolchainLayout.installCmake(home, "3.22.1", cmake, ninja)
        ToolchainLayout.installBuildTools(home, "35.0.0", aapt2, null, null)

        val ndk = home.resolve("ndk").resolve(revision)
        val props = ndk.resolve("source.properties").readText()
        assertTrue(props.startsWith("Pkg.Desc = Android NDK\n"), props)
        assertTrue(props.contains("Pkg.Revision = $revision"), props)
        val bin = ndk.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
        assertEquals(
            byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1, 2, 3).toList(),
            Files.readAllBytes(bin.resolve("clang")).toList(),
        )
        assertTrue(Files.isSymbolicLink(bin.resolve("clang++")))
        assertEquals("clang", Files.readSymbolicLink(bin.resolve("clang++")).toString())
        assertTrue(Files.isRegularFile(ndk.resolve("build/cmake/android.toolchain.cmake")))
        assertTrue(ndk.resolve("build/cmake/compiler_id.cmake").readText().contains("18.1.8"))
        assertTrue(Files.isRegularFile(ndk.resolve("meta/abis.json")))

        val cmakeRoot = home.resolve("cmake/3.22.1")
        assertEquals("Pkg.Revision = 3.22.1\n", cmakeRoot.resolve("source.properties").readText())
        assertTrue(Files.isRegularFile(cmakeRoot.resolve("bin/cmake")))
        assertTrue(Files.isRegularFile(cmakeRoot.resolve("bin/ninja")))

        val tools = home.resolve("build-tools/35.0.0")
        assertEquals("Pkg.Revision = 35.0.0\n", tools.resolve("source.properties").readText())
        assertTrue(Files.isRegularFile(tools.resolve("aapt2")))
        assertEquals("aapt2", tools.resolve("aapt2").fileName.toString())
        assertTrue(!Files.exists(tools.resolve("libaapt2.so")))
        assertTrue(!Files.exists(tools.resolve("zipalign")))
    }

    @Test
    fun anNdkBuildComponentInstallsMakeTheLinkerAndTheSysroot() {
        val home = Files.createTempDirectory("ndk-build-layout")
        val driver = home.resolve("libllvmtools.so")
        val linker = home.resolve("libld-gnu-lld.so")
        val make = home.resolve("make-bin")
        Files.write(driver, byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1))
        Files.write(linker, byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 9))
        Files.write(make, "make-bytes".toByteArray())
        val out = home.resolve("toolchain-out")
        writeFile(out.resolve("sysroot/usr/lib/aarch64-linux-android/26/libc.so"), byteArrayOf(26))
        writeFile(out.resolve("lib-clang/18/include/stddef.h"), "stddef".toByteArray())
        writeFile(out.resolve("native_app_glue/android_native_app_glue.h"), "glue".toByteArray())

        val revision = "28.2.13676358"
        ToolchainLayout.installNdk(home, revision, driver, out, linker)
        ToolchainLayout.installMake(home, revision, make)

        val ndk = home.resolve("ndk").resolve(revision)
        val bin = ndk.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
        val lld = bin.resolve("ld.lld")
        assertTrue(Files.isRegularFile(lld))
        assertTrue(!Files.isSymbolicLink(lld))
        assertEquals(
            byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 9).toList(),
            Files.readAllBytes(lld).toList(),
        )
        assertTrue(Files.isSymbolicLink(bin.resolve("llvm-strip")))
        assertEquals("clang", Files.readSymbolicLink(bin.resolve("llvm-strip")).toString())
        assertEquals(
            listOf<Byte>(26),
            Files.readAllBytes(
                ndk.resolve("toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/26/libc.so"),
            ).toList(),
        )
        assertEquals(
            "stddef",
            ndk.resolve("toolchains/llvm/prebuilt/linux-x86_64/lib/clang/18/include/stddef.h").readText(),
        )
        assertEquals(
            "glue",
            ndk.resolve("sources/android/native_app_glue/android_native_app_glue.h").readText(),
        )
        val ndkBuild = ndk.resolve("build/ndk-build")
        assertTrue(Files.isRegularFile(ndkBuild))
        assertTrue(Files.isExecutable(ndkBuild))
        assertTrue(Files.isRegularFile(ndk.resolve("build/core/build-local.mk")))
        assertEquals(
            "make-bytes",
            ndk.resolve("prebuilt/linux-x86_64/bin/make").readText(),
        )
    }

    private fun writeFile(path: java.nio.file.Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
    }
}
