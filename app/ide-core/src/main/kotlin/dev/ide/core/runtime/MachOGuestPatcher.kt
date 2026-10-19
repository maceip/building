package dev.ide.core.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Research spike: patch an iOS guest Mach-O executable so it can be dlopened
 * as a dylib from another iOS process (LiveContainer-style). Host-side logic is
 * pure JVM so we can unit-test the patch without a device; the iOS host then
 * calls dlopen on the patched image.
 *
 * Research only - not a shipping product surface.
 */
object MachOGuestPatcher {

    private const val MH_MAGIC_64 = 0xFEEDFACF.toInt()
    private const val MH_CIGAM_64 = 0xCFFAEDFE.toInt()
    private const val MH_EXECUTE = 0x2
    private const val MH_DYLIB = 0x6
    private const val LC_SEGMENT_64 = 0x19

    data class PatchReport(
        val path: Path,
        val wasExecute: Boolean,
        val pageZeroPatched: Boolean,
        val fileTypePatched: Boolean,
    ) {
        val ok: Boolean get() = pageZeroPatched && fileTypePatched
    }

    fun patchBytes(input: ByteArray): Triple<ByteArray, Boolean, Boolean> {
        require(input.size >= 32) { "not a Mach-O" }
        val buf = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.getInt(0)
        require(magic == MH_MAGIC_64 || magic == MH_CIGAM_64) {
            "not a 64-bit Mach-O"
        }
        val fileTypeOff = 12
        val ncmdsOff = 16
        val fileType = buf.getInt(fileTypeOff)
        var typeChanged = false
        if (fileType == MH_EXECUTE) {
            buf.putInt(fileTypeOff, MH_DYLIB)
            typeChanged = true
        }
        val ncmds = buf.getInt(ncmdsOff)
        var off = 32
        var pageZeroChanged = false
        for (i in 0 until ncmds) {
            if (off + 8 > input.size) break
            val cmd = buf.getInt(off)
            val cmdsize = buf.getInt(off + 4)
            if (cmd == LC_SEGMENT_64 && cmdsize >= 72) {
                val segname = input.copyOfRange(off + 8, off + 24)
                    .toString(Charsets.US_ASCII)
                    .trimEnd(0.toChar())
                if (segname == "__PAGEZERO") {
                    buf.putLong(off + 24, 0xFFFFC000L)
                    buf.putLong(off + 32, 0x4000L)
                    pageZeroChanged = true
                }
            }
            if (cmdsize <= 0) break
            off += cmdsize
        }
        return Triple(input, typeChanged, pageZeroChanged)
    }

    fun patchForDlopen(src: Path, dst: Path): PatchReport {
        val bytes = Files.readAllBytes(src)
        val patched = patchBytes(bytes)
        dst.parent?.let { Files.createDirectories(it) }
        Files.write(dst, patched.first)
        return PatchReport(
            path = dst,
            wasExecute = patched.second,
            pageZeroPatched = patched.third,
            fileTypePatched = patched.second,
        )
    }

    fun synthesizeHelloExecute(): ByteArray {
        val total = 32 + 72
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(MH_MAGIC_64)
        b.putInt(0x0100000C)
        b.putInt(0)
        b.putInt(MH_EXECUTE)
        b.putInt(1)
        b.putInt(72)
        b.putInt(0)
        b.putInt(0)
        b.putInt(LC_SEGMENT_64)
        b.putInt(72)
        val nameBuf = ByteArray(16)
        "__PAGEZERO".toByteArray(Charsets.US_ASCII).copyInto(nameBuf)
        b.put(nameBuf)
        b.putLong(0)
        b.putLong(0x100000000L)
        b.putLong(0)
        b.putLong(0)
        b.putInt(0)
        b.putInt(0)
        b.putInt(0)
        b.putInt(0)
        return b.array()
    }
}
