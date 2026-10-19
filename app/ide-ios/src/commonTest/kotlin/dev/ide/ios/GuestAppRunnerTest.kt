package dev.ide.ios

import dev.ide.ios.guest.GuestAppContainer
import dev.ide.ios.guest.GuestAppRunner
import dev.ide.ios.guest.MachoPatcher
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GuestAppRunnerTest {

    @Test
    fun testMachoPatcherTransformsExecuteToDylibAndZeroesPagezero() {
        // Construct synthetic Mach-O 64-bit arm64 binary
        // 32-byte header + 72-byte LC_SEGMENT_64 (__PAGEZERO) + 24-byte LC_MAIN = 128 bytes
        val bytes = ByteArray(128)

        // Mach-O header (mach_header_64)
        MachoPatcher.writeUInt32LE(bytes, 0, MachoPatcher.MH_MAGIC_64) // magic
        MachoPatcher.writeUInt32LE(bytes, 4, MachoPatcher.CPU_TYPE_ARM64) // cputype
        MachoPatcher.writeUInt32LE(bytes, 8, 0U) // cpusubtype
        MachoPatcher.writeUInt32LE(bytes, 12, MachoPatcher.MH_EXECUTE) // filetype = MH_EXECUTE (2)
        MachoPatcher.writeUInt32LE(bytes, 16, 2U) // ncmds = 2
        MachoPatcher.writeUInt32LE(bytes, 20, 96U) // sizeofcmds = 72 + 24 = 96
        MachoPatcher.writeUInt32LE(bytes, 24, 0U) // flags
        MachoPatcher.writeUInt32LE(bytes, 28, 0U) // reserved

        // Cmd 1: LC_SEGMENT_64 (__PAGEZERO) at offset 32
        MachoPatcher.writeUInt32LE(bytes, 32, MachoPatcher.LC_SEGMENT_64) // cmd = 0x19
        MachoPatcher.writeUInt32LE(bytes, 36, 72U) // cmdsize = 72
        // segname = "__PAGEZERO" (16 bytes at offset 40..55)
        val segName = "__PAGEZERO".encodeToByteArray()
        segName.copyInto(bytes, 40)
        MachoPatcher.writeUInt64LE(bytes, 56, 0UL) // vmaddr = 0
        MachoPatcher.writeUInt64LE(bytes, 64, 0x100000000UL) // vmsize = 4GB (0x100000000)
        MachoPatcher.writeUInt64LE(bytes, 72, 0UL) // fileoff = 0
        MachoPatcher.writeUInt64LE(bytes, 80, 0UL) // filesize = 0
        MachoPatcher.writeUInt32LE(bytes, 88, 7U) // maxprot = 7
        MachoPatcher.writeUInt32LE(bytes, 92, 0U) // initprot = 0
        MachoPatcher.writeUInt32LE(bytes, 96, 0U) // nsects = 0
        MachoPatcher.writeUInt32LE(bytes, 100, 0U) // flags = 0

        // Cmd 2: LC_MAIN at offset 104 (32 + 72)
        MachoPatcher.writeUInt32LE(bytes, 104, MachoPatcher.LC_MAIN) // cmd = 0x80000028
        MachoPatcher.writeUInt32LE(bytes, 108, 24U) // cmdsize = 24
        MachoPatcher.writeUInt64LE(bytes, 112, 0x4000UL) // entryoff = 0x4000
        MachoPatcher.writeUInt64LE(bytes, 120, 0UL) // stacksize = 0

        val result = MachoPatcher.patchBytes(bytes)

        assertTrue(result.success, "Mach-O patch must succeed: ${result.message}")
        assertEquals(MachoPatcher.MH_EXECUTE.toInt(), result.originalFileType)
        assertEquals(MachoPatcher.MH_DYLIB.toInt(), result.newFileType)
        assertTrue(result.pageZeroZeroed, "__PAGEZERO must be zeroed")
        assertEquals(0x4000UL, result.entryOffset)

        // Verify patched bytes on disk / in memory
        val patchedFileType = MachoPatcher.readUInt32LE(bytes, 12)
        assertEquals(MachoPatcher.MH_DYLIB, patchedFileType, "Filetype must be updated to MH_DYLIB")

        val patchedVmSize = MachoPatcher.readUInt64LE(bytes, 64)
        assertEquals(0UL, patchedVmSize, "__PAGEZERO vmsize must be 0")

        val patchedMaxProt = MachoPatcher.readUInt32LE(bytes, 88)
        assertEquals(0U, patchedMaxProt, "__PAGEZERO maxprot must be 0")
    }

    @Test
    fun testGuestAppContainerLayoutAndInspection() {
        val tempDir = IosFiles.join(NSTemporaryDirectory().trimEnd('/'), "live-container-test-${nowSuffix()}")
        val testAppPath = IosFiles.join(tempDir, "SampleMusic.app")
        IosFiles.mkdirs(testAppPath)

        val plistXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>CFBundleIdentifier</key>
                <string>dev.sample.music</string>
                <key>CFBundleExecutable</key>
                <string>SampleMusic</string>
                <key>CFBundleDisplayName</key>
                <string>Sample Music</string>
                <key>CFBundleShortVersionString</key>
                <string>1.5.0</string>
            </dict>
            </plist>
        """.trimIndent()
        IosFiles.writeText(IosFiles.join(testAppPath, "Info.plist"), plistXml)

        // Test metadata inspection
        val meta = GuestAppContainer.inspectApp(testAppPath)
        assertNotNull(meta)
        assertEquals("dev.sample.music", meta.bundleId)
        assertEquals("SampleMusic", meta.executableName)
        assertEquals("Sample Music", meta.displayName)
        assertEquals("1.5.0", meta.version)

        // Test container layout preparation
        val container = GuestAppContainer.prepareContainer(meta.bundleId, meta.displayName)
        assertTrue(IosFiles.exists(container.containerRoot), "Container root must exist")
        assertTrue(IosFiles.exists(container.dataDir), "Data dir must exist")
        assertTrue(IosFiles.exists(container.documentsDir), "Documents dir must exist")
        assertTrue(IosFiles.exists(container.libraryDir), "Library dir must exist")
        assertTrue(IosFiles.exists(container.cachesDir), "Caches dir must exist")
        assertTrue(IosFiles.exists(container.preferencesDir), "Preferences dir must exist")
        assertTrue(IosFiles.exists(container.tmpDir), "tmp dir must exist")

        // Cleanup
        IosFiles.delete(tempDir)
    }

    private var counter = 0
    private fun nowSuffix(): String = "${++counter}-${IosFiles.modifiedMs(NSTemporaryDirectory())}"
}
