package dev.ide.ios.guest

import dev.ide.ios.IosFiles

/**
 * Result of patching a Mach-O binary.
 */
data class MachoPatchResult(
    val success: Boolean,
    val originalFileType: Int,
    val newFileType: Int,
    val pageZeroZeroed: Boolean,
    val entryOffset: ULong?,
    val isUniversal: Boolean,
    val patchedSliceOffset: Long,
    val message: String,
    val rewrittenRpaths: Int = 0,
)

/**
 * Clean-room reimplementation of Mach-O binary patching for iOS guest applications.
 *
 * Implements the technique for loading standalone Mach-O executables into an iOS host process:
 * 1. Modifies the Mach-O header filetype from MH_EXECUTE (0x02) to MH_DYLIB (0x06).
 * 2. Modifies LC_SEGMENT_64 "__PAGEZERO" to reduce vmsize, initprot, and maxprot to 0,
 *    preventing dyld from attempting to map a 4GB zero-page that collides with the host.
 * 3. Extracts the LC_MAIN entry point offset (if present) to facilitate entry jumping.
 *
 * This implementation is license-clean (GPL-3.0 compatible) and relies strictly on public
 * Mach-O specifications and dynamic loader mechanics.
 */
object MachoPatcher {

    const val MH_MAGIC_64: UInt = 0xFEEDFACFU
    const val MH_CIGAM_64: UInt = 0xCFFAEDFEU

    const val FAT_MAGIC: UInt = 0xCAFEBABEU
    const val FAT_CIGAM: UInt = 0xBEBAFECAU
    const val FAT_MAGIC_64: UInt = 0xCAFEBABFU
    const val FAT_CIGAM_64: UInt = 0xBFBAFECAU

    const val CPU_TYPE_ARM64: UInt = 0x0100000CU
    const val CPU_TYPE_X86_64: UInt = 0x01000007U

    const val MH_EXECUTE: UInt = 0x02U
    const val MH_DYLIB: UInt = 0x06U

    const val LC_SEGMENT_64: UInt = 0x19U
    const val LC_LOAD_DYLIB: UInt = 0x0CU
    const val LC_LOAD_WEAK_DYLIB: UInt = 0x80000018U
    const val LC_RPATH: UInt = 0x8000001CU
    const val LC_REEXPORT_DYLIB: UInt = 0x8000001FU
    const val LC_MAIN: UInt = 0x80000028U
    const val LC_CODE_SIGNATURE: UInt = 0x1DU

    /**
     * Patches a Mach-O executable at [sourcePath], writing the patched dynamic library to [destPath].
     */
    fun patchExecutable(sourcePath: String, destPath: String): MachoPatchResult {
        val bytes = IosFiles.readBytes(sourcePath)
            ?: return MachoPatchResult(
                success = false,
                originalFileType = 0,
                newFileType = 0,
                pageZeroZeroed = false,
                entryOffset = null,
                isUniversal = false,
                patchedSliceOffset = 0L,
                message = "Failed to read binary at $sourcePath",
            )

        val result = patchBytes(bytes)
        if (result.success) {
            IosFiles.writeBytes(destPath, bytes)
        }
        return result
    }

    /**
     * Patches binary [bytes] in-place.
     */
    fun patchBytes(bytes: ByteArray): MachoPatchResult {
        if (bytes.size < 32) {
            return MachoPatchResult(false, 0, 0, false, null, false, 0L, "Binary too small (< 32 bytes)")
        }

        val magic = readUInt32BE(bytes, 0)
        var sliceOffset = 0
        var isUniversal = false

        if (magic == FAT_MAGIC || magic == FAT_CIGAM || magic == FAT_MAGIC_64 || magic == FAT_CIGAM_64) {
            isUniversal = true
            val nfat = readUInt32BE(bytes, 4).toInt()
            var chosenOffset = -1
            for (i in 0 until nfat) {
                val archOffset = 8 + i * 20
                if (archOffset + 20 > bytes.size) break
                val cputype = readUInt32BE(bytes, archOffset)
                val offset = readUInt32BE(bytes, archOffset + 8).toInt()
                if (cputype == CPU_TYPE_ARM64 || cputype == CPU_TYPE_X86_64) {
                    chosenOffset = offset
                    break
                }
            }
            if (chosenOffset < 0 && nfat > 0 && 8 + 20 <= bytes.size) {
                chosenOffset = readUInt32BE(bytes, 8 + 8).toInt()
            }
            if (chosenOffset < 0 || chosenOffset + 32 > bytes.size) {
                return MachoPatchResult(false, 0, 0, false, null, true, 0L, "Could not locate valid slice in FAT binary")
            }
            sliceOffset = chosenOffset
        }

        val machMagicLE = readUInt32LE(bytes, sliceOffset)
        val is64BitLE = (machMagicLE == MH_MAGIC_64)

        if (!is64BitLE) {
            val machMagicBE = readUInt32BE(bytes, sliceOffset)
            if (machMagicBE != MH_MAGIC_64 && machMagicBE != MH_CIGAM_64) {
                return MachoPatchResult(false, 0, 0, false, null, isUniversal, sliceOffset.toLong(),
                    "Not a supported 64-bit Mach-O binary (magic: 0x${machMagicLE.toString(16)})")
            }
        }

        val fileType = readUInt32LE(bytes, sliceOffset + 12)
        val ncmds = readUInt32LE(bytes, sliceOffset + 16).toInt()
        val sizeofcmds = readUInt32LE(bytes, sliceOffset + 20).toInt()

        // 1. Patch filetype: MH_EXECUTE -> MH_DYLIB
        if (fileType == MH_EXECUTE) {
            writeUInt32LE(bytes, sliceOffset + 12, MH_DYLIB)
        }

        // 2. Iterate load commands
        var currentCmdOffset = sliceOffset + 32
        val endCmdOffset = currentCmdOffset + sizeofcmds
        var pageZeroZeroed = false
        var entryOffset: ULong? = null
        var rewrittenRpaths = 0

        for (i in 0 until ncmds) {
            if (currentCmdOffset + 8 > bytes.size || currentCmdOffset >= endCmdOffset) break
            val cmd = readUInt32LE(bytes, currentCmdOffset)
            val cmdsize = readUInt32LE(bytes, currentCmdOffset + 4).toInt()
            if (cmdsize < 8 || currentCmdOffset + cmdsize > bytes.size) break

            when (cmd) {
                LC_SEGMENT_64 -> {
                    val segname = readAscii(bytes, currentCmdOffset + 8, 16)
                    if (segname == "__PAGEZERO") {
                        // Relocate __PAGEZERO to single page at 0xFFFFC000..0x100000000 so dyld accepts MH_DYLIB
                        writeUInt64LE(bytes, currentCmdOffset + 24, 0xFFFFC000UL)
                        writeUInt64LE(bytes, currentCmdOffset + 32, 0x4000UL)
                        // maxprot at offset + 56 (4 bytes) -> zero out
                        writeUInt32LE(bytes, currentCmdOffset + 56, 0U)
                        // initprot at offset + 60 (4 bytes) -> zero out
                        writeUInt32LE(bytes, currentCmdOffset + 60, 0U)
                        pageZeroZeroed = true
                    }
                }
                LC_RPATH, LC_LOAD_DYLIB, LC_LOAD_WEAK_DYLIB, LC_REEXPORT_DYLIB -> {
                    if (rewriteExecutablePathInCommand(bytes, currentCmdOffset, cmdsize)) {
                        rewrittenRpaths++
                    }
                }
                LC_MAIN -> {
                    if (cmdsize >= 24) {
                        entryOffset = readUInt64LE(bytes, currentCmdOffset + 8)
                    }
                }
            }
            currentCmdOffset += cmdsize
        }

        return MachoPatchResult(
            success = true,
            originalFileType = fileType.toInt(),
            newFileType = MH_DYLIB.toInt(),
            pageZeroZeroed = pageZeroZeroed,
            entryOffset = entryOffset,
            isUniversal = isUniversal,
            patchedSliceOffset = sliceOffset.toLong(),
            message = "Successfully patched Mach-O to MH_DYLIB (PAGEZERO patched: $pageZeroZeroed, rpaths rewritten: $rewrittenRpaths, entryOff: $entryOffset)",
            rewrittenRpaths = rewrittenRpaths,
        )
    }

    private fun rewriteExecutablePathInCommand(bytes: ByteArray, cmdOffset: Int, cmdsize: Int): Boolean {
        if (cmdsize <= 12) return false
        val strOffset = readUInt32LE(bytes, cmdOffset + 8).toInt()
        val start = if (strOffset in 8 until cmdsize) cmdOffset + strOffset else cmdOffset + 8
        val end = (cmdOffset + cmdsize).coerceAtMost(bytes.size)
        if (start >= end) return false
        val currentStr = readAscii(bytes, start, end - start)
        if (!currentStr.contains("@executable_path")) return false
        val replaced = currentStr.replace("@executable_path", "@loader_path")
        val replacedBytes = replaced.encodeToByteArray()
        for (i in start until end) {
            bytes[i] = 0
        }
        val copyLen = replacedBytes.size.coerceAtMost(end - start - 1)
        replacedBytes.copyInto(bytes, destinationOffset = start, startIndex = 0, endIndex = copyLen)
        return true
    }

    fun readUInt32LE(bytes: ByteArray, offset: Int): UInt =
        (bytes[offset].toUInt() and 0xFFU) or
        ((bytes[offset + 1].toUInt() and 0xFFU) shl 8) or
        ((bytes[offset + 2].toUInt() and 0xFFU) shl 16) or
        ((bytes[offset + 3].toUInt() and 0xFFU) shl 24)

    fun writeUInt32LE(bytes: ByteArray, offset: Int, value: UInt) {
        bytes[offset] = (value and 0xFFU).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFFU).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFFU).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFFU).toByte()
    }

    fun readUInt32BE(bytes: ByteArray, offset: Int): UInt =
        ((bytes[offset].toUInt() and 0xFFU) shl 24) or
        ((bytes[offset + 1].toUInt() and 0xFFU) shl 16) or
        ((bytes[offset + 2].toUInt() and 0xFFU) shl 8) or
        (bytes[offset + 3].toUInt() and 0xFFU)

    fun readUInt64LE(bytes: ByteArray, offset: Int): ULong {
        var res = 0UL
        for (i in 0 until 8) {
            res = res or ((bytes[offset + i].toULong() and 0xFFUL) shl (i * 8))
        }
        return res
    }

    fun writeUInt64LE(bytes: ByteArray, offset: Int, value: ULong) {
        for (i in 0 until 8) {
            bytes[offset + i] = ((value shr (i * 8)) and 0xFFUL).toByte()
        }
    }

    fun readAscii(bytes: ByteArray, offset: Int, maxLen: Int): String {
        val sb = StringBuilder()
        for (i in 0 until maxLen) {
            val b = bytes[offset + i]
            if (b.toInt() == 0) break
            sb.append(b.toInt().toChar())
        }
        return sb.toString()
    }
}
