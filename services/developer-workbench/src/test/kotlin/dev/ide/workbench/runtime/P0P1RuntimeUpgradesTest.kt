package dev.ide.workbench.runtime

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class P0P1RuntimeUpgradesTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun muslAndBionicLinkerCreatesInterpreterAndSonameLinksAndInspectsElf() {
        val prefix = temp.resolve("usr")
        val nativeDir = temp.resolve("native")
        Files.createDirectories(prefix)
        Files.createDirectories(nativeDir)

        // Create fake native libraries in nativeDir
        Files.write(nativeDir.resolve("libldmusl.so"), byteArrayOf(1, 2, 3))
        Files.write(nativeDir.resolve("libtxz.so"), byteArrayOf(4, 5, 6))
        Files.write(nativeDir.resolve("libcrypto3.so"), byteArrayOf(7, 8, 9))

        val provision = MuslAndBionicRuntimeLinker.ensureLinks(prefix, nativeDir)
        assertNotNull(provision.muslLoaderLink)
        assertTrue(Files.isSymbolicLink(prefix.resolve("bin/ld-musl-aarch64.so.1")))
        assertTrue(Files.isSymbolicLink(prefix.resolve("lib/libz.so.1")))
        assertTrue(Files.isSymbolicLink(prefix.resolve("lib/libcrypto.so.3")))

        // Synthesize a 64-bit ELF binary with PT_INTERP = "/lib/ld-musl-aarch64.so.1" and PT_LOAD p_align = 16384
        val muslBinary = prefix.resolve("bin/rg")
        Files.write(muslBinary, buildSyntheticElf64("/lib/ld-musl-aarch64.so.1", 16384L))
        muslBinary.toFile().setExecutable(true, false)

        val inspection = MuslAndBionicRuntimeLinker.inspectElf(muslBinary)
        assertEquals(MuslAndBionicRuntimeLinker.ElfRuntimeFlavor.MUSL, inspection.flavor)
        assertEquals("/lib/ld-musl-aarch64.so.1", inspection.interpreter)
        assertTrue(inspection.is16KiBAligned)

        val wrapped = MuslAndBionicRuntimeLinker.wrapCommandIfNeeded(
            command = listOf(muslBinary.toString(), "--version"),
            prefix = prefix,
            nativeDir = nativeDir,
        )
        assertEquals(nativeDir.resolve("libldmusl.so").toString(), wrapped[0])
        assertEquals("--library-path", wrapped[1])
        assertEquals(muslBinary.toString(), wrapped[3])
    }

    @Test
    fun shellProcessSupervisorReapsStalePidFilesAndSupervisesSessions() = runBlocking {
        val pidDir = temp.resolve("processes")
        val signalled = mutableListOf<Pair<Int, Int>>()
        val supervisor = ShellProcessSupervisor(pidDir) { pgid, sig ->
            signalled += pgid to sig
            true
        }

        // Write a stale .pid file from a crashed session
        Files.createDirectories(pidDir)
        val staleFile = pidDir.resolve("orphan-session.pid")
        RuntimeFiles.writeUtf8(staleFile, "42424\n")

        val reaped = supervisor.reapStalePidFiles()
        assertEquals(1, reaped)
        assertFalse(Files.exists(staleFile))
        assertTrue(signalled.contains(42424 to ShellProcessSupervisor.SIGTERM))
        assertTrue(signalled.contains(42424 to ShellProcessSupervisor.SIGKILL))

        // Supervise a real short-lived process
        val token = supervisor.allocateToken("live-session")
        RuntimeFiles.writeUtf8(token.pidFile, "51515\n")
        val proc = ProcessBuilder("/bin/sh", "-c", "sleep 5").start()
        val handle = supervisor.register(token, proc)
        assertEquals(setOf("live-session"), supervisor.activeSessions())
        assertEquals(51515, handle.resolveProcessGroupId())

        handle.terminate(gracePeriodMs = 50L)
        assertTrue(supervisor.activeSessions().isEmpty())
        assertFalse(Files.exists(token.pidFile))
    }

    @Test
    fun termuxExtensionManagerResolvesClosureAndExtractsDebWithShebangRewrite() = runBlocking {
        val debBytes = buildSyntheticTermuxDeb(
            scriptRelPath = "data/data/com.termux/files/usr/bin/jq-wrapper",
            scriptContent = "#!/data/data/com.termux/files/usr/bin/sh\necho jq-ok\n",
        )
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(debBytes)
            .joinToString("") { "%02x".format(it) }

        val packagesIndex = """
            Package: oniguruma
            Version: 6.9.9
            Filename: pool/main/o/oniguruma/oniguruma_6.9.9_aarch64.deb
            SHA256: $sha256
            Size: ${debBytes.size}

            Package: jq
            Version: 1.7.1
            Depends: oniguruma (>= 6.9.0)
            Filename: pool/main/j/jq/jq_1.7.1_aarch64.deb
            SHA256: $sha256
            Size: ${debBytes.size}
        """.trimIndent() + "\n\n"

        val parsed = TermuxExtensionManager.parsePackagesIndex(packagesIndex)
        val closure = TermuxExtensionManager.resolveDependencyClosure(listOf("jq"), parsed)
        assertEquals(listOf("oniguruma", "jq"), closure.map { it.name })

        val server = MockWebServer()
        server.start()
        try {
            // 1st request: Packages.gz fails (404), 2nd request: plain Packages succeeds, 3rd & 4th: .deb downloads
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(200).setBody(packagesIndex))
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(debBytes)))
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(debBytes)))

            val extRoot = temp.resolve("extensions")
            val runtimePrefix = temp.resolve("dr/p/0123456789abcdef/usr")
            Files.createDirectories(runtimePrefix)

            val manager = TermuxExtensionManager(
                extensionsRoot = extRoot,
                runtimePrefix = runtimePrefix,
                repositoryBaseUrl = server.url("/apt/termux-main").toString(),
            )
            assertEquals(19, manager.catalog().size)

            val installed = manager.installExtension("jq")
            assertEquals("jq", installed.id)
            assertEquals(listOf("oniguruma", "jq"), installed.installedPackages)

            val extractedScript = installed.binDir.resolve("jq-wrapper")
            assertTrue(Files.isRegularFile(extractedScript))
            assertTrue(Files.isExecutable(extractedScript))
            val rewrittenText = Files.readString(extractedScript)
            assertTrue(rewrittenText.startsWith("#!${runtimePrefix}/bin/sh"), "Shebang was: $rewrittenText")

            val (binDirs, _) = manager.activeEnvironmentPaths()
            assertTrue(installed.binDir in binDirs)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun prootCommandBuilderAndEnvironmentDoctorRepairIssues() {
        val prefix = temp.resolve("usr")
        val home = temp.resolve("home-root")
        val nativeDir = temp.resolve("native")
        Files.createDirectories(prefix.resolve("bin"))
        Files.createDirectories(nativeDir)

        // Create 16 KiB-aligned proot + loader + musl in nativeDir
        val prootBin = nativeDir.resolve("libproot_exec.so")
        val prootLoader = nativeDir.resolve("libproot_loader.so")
        val muslSo = nativeDir.resolve("libldmusl.so")
        Files.write(prootBin, byteArrayOf(1))
        Files.write(prootLoader, byteArrayOf(2))
        Files.write(muslSo, byteArrayOf(3))

        val foundProot = ProotCommandBuilder.findProotBinary(prefix, nativeDir)
        assertEquals(prootBin, foundProot)

        val plan = ProotCommandBuilder.build(
            config = ProotCommandBuilder.ProotMountConfig(
                prootBinary = prootBin,
                rootfsDir = prefix,
                workDir = temp.resolve("workspace"),
                homeDir = home.resolve("home"),
                tmpDir = home.resolve("tmp"),
                nativeLibraryDir = nativeDir,
            ),
            innerCommand = listOf("/bin/sh", "-c", "echo inside-proot"),
        )
        assertTrue(plan.command.contains("--kill-on-exit"))
        assertTrue(plan.command.contains("--link2symlink"))
        assertEquals(prootLoader.toString(), plan.environment["PROOT_LOADER"])
        assertEquals(prefix.resolve(".l2s").toString(), plan.environment["PROOT_L2S_DIR"])

        // Create deliberate runtime issues for EnvironmentDoctor:
        // 1. Non-executable script with unrewritten Termux shebang
        val badScript = prefix.resolve("bin/pip3")
        Files.writeString(badScript, "#!/data/data/com.termux/files/usr/bin/python3\nprint('ok')\n")
        badScript.toFile().setExecutable(false)

        // 2. Stale symlink pointing to an old APK native dir that no longer exists, while nativeDir has the library
        val realNode = nativeDir.resolve("libnode.so")
        Files.write(realNode, byteArrayOf(9, 9))
        val staleLink = prefix.resolve("bin/node")
        Files.createSymbolicLink(staleLink, temp.resolve("old-apk-native/lib/arm64/libnode.so"))

        val report = EnvironmentDoctor.diagnoseAndRepair(
            prefix = prefix,
            home = home,
            nativeDir = nativeDir,
            repair = true,
        )
        assertTrue(report.healthy, report.summaryText())
        assertTrue(report.repairedCount >= 3, report.summaryText())
        assertTrue(Files.isExecutable(badScript))
        assertTrue(Files.readString(badScript).startsWith("#!$prefix/bin/python3"))
        assertEquals(realNode, Files.readSymbolicLink(staleLink))
        assertTrue(Files.exists(prefix.resolve("bin/ld-musl-aarch64.so.1")))
    }

    private fun buildSyntheticElf64(interpreter: String, pAlign: Long): ByteArray {
        val interpBytes = (interpreter + "\u0000").toByteArray(Charsets.UTF_8)
        val totalSize = 64 + 56 * 2 + interpBytes.size
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // ELF magic + 64-bit (2) + little-endian (1) + version (1)
        buf.put(0, 0x7F.toByte())
        buf.put(1, 'E'.code.toByte())
        buf.put(2, 'L'.code.toByte())
        buf.put(3, 'F'.code.toByte())
        buf.put(4, 2.toByte())
        buf.put(5, 1.toByte())
        buf.put(6, 1.toByte())

        // e_phoff = 64, e_phentsize = 56, e_phnum = 2
        buf.putLong(32, 64L)
        buf.putShort(54, 56.toShort())
        buf.putShort(56, 2.toShort())

        // Program header 0: PT_LOAD (1) with p_align = pAlign
        val ph0 = 64
        buf.putInt(ph0, 1) // p_type = PT_LOAD
        buf.putLong(ph0 + 48, pAlign)

        // Program header 1: PT_INTERP (3) pointing to offset 176
        val ph1 = 64 + 56
        val interpOffset = 64L + 56L * 2L
        buf.putInt(ph1, 3) // p_type = PT_INTERP
        buf.putLong(ph1 + 8, interpOffset) // p_offset
        buf.putLong(ph1 + 32, interpBytes.size.toLong()) // p_filesz

        buf.position(interpOffset.toInt())
        buf.put(interpBytes)
        return buf.array()
    }

    private fun buildSyntheticTermuxDeb(scriptRelPath: String, scriptContent: String): ByteArray {
        val tarXzBytes = ByteArrayOutputStream().use { rawOut ->
            XZCompressorOutputStream(rawOut).use { xzOut ->
                TarArchiveOutputStream(xzOut).use { tarOut ->
                    tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    val contentBytes = scriptContent.toByteArray(Charsets.UTF_8)
                    val entry = TarArchiveEntry(scriptRelPath)
                    entry.size = contentBytes.size.toLong()
                    entry.mode = 0b111_101_101 // 0755
                    tarOut.putArchiveEntry(entry)
                    tarOut.write(contentBytes)
                    tarOut.closeArchiveEntry()
                }
            }
            rawOut.toByteArray()
        }

        return ByteArrayOutputStream().use { arRaw ->
            ArArchiveOutputStream(arRaw).use { arOut ->
                val entry = ArArchiveEntry("data.tar.xz", tarXzBytes.size.toLong())
                arOut.putArchiveEntry(entry)
                arOut.write(tarXzBytes)
                arOut.closeArchiveEntry()
            }
            arRaw.toByteArray()
        }
    }
}
