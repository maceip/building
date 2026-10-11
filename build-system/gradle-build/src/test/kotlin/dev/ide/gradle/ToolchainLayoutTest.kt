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
}
