package dev.ide.core.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spike A proof: a hello-world MH_EXECUTE with __PAGEZERO is rewritten into a
 * dlopen-able MH_DYLIB (LiveContainer-style guest patch).
 */
class MachOGuestPatcherTest {

    @Test
    fun patchesHelloExecuteToDlopenableDylib() {
        val src = MachOGuestPatcher.synthesizeHelloExecute()
        val (patched, typeChanged, pageZeroChanged) = MachOGuestPatcher.patchBytes(src.copyOf())

        assertTrue(typeChanged, "MH_EXECUTE must become MH_DYLIB")
        assertTrue(pageZeroChanged, "__PAGEZERO vmaddr/vmsize must be rewritten")

        val buf = ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x6, buf.getInt(12), "filetype is MH_DYLIB")

        // Find __PAGEZERO and check new vmaddr/vmsize
        val ncmds = buf.getInt(16)
        var off = 32
        var sawPageZero = false
        for (i in 0 until ncmds) {
            val cmd = buf.getInt(off)
            val cmdsize = buf.getInt(off + 4)
            if (cmd == 0x19 && cmdsize >= 72) {
                val name = patched.copyOfRange(off + 8, off + 24)
                    .toString(Charsets.US_ASCII).trimEnd(0.toChar())
                if (name == "__PAGEZERO") {
                    sawPageZero = true
                    assertEquals(0xFFFFC000L, buf.getLong(off + 24), "PAGEZERO vmaddr")
                    assertEquals(0x4000L, buf.getLong(off + 32), "PAGEZERO vmsize")
                }
            }
            off += cmdsize
        }
        assertTrue(sawPageZero, "synthetic image must contain __PAGEZERO")
    }

    @Test
    fun isIdempotentOnAlreadyPatchedImage() {
        val src = MachOGuestPatcher.synthesizeHelloExecute()
        val once = MachOGuestPatcher.patchBytes(src.copyOf())
        val twice = MachOGuestPatcher.patchBytes(once.first.copyOf())
        // Second pass: filetype already MH_DYLIB so typeChanged is false
        assertEquals(false, twice.second, "already-dylib should not flip filetype again")
    }

    @Test
    fun rewritesExecutablePathToLoaderPathInRpathAndLoadDylib() {
        // 32-byte header + 72-byte __PAGEZERO + 48-byte LC_RPATH = 152 bytes
        val buf = ByteBuffer.allocate(152).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0xFEEDFACF.toInt())
        buf.putInt(0x0100000C)
        buf.putInt(0)
        buf.putInt(0x2) // MH_EXECUTE
        buf.putInt(2) // ncmds = 2
        buf.putInt(72 + 48)
        buf.putInt(0)
        buf.putInt(0)

        // Cmd 1: __PAGEZERO
        buf.putInt(0x19)
        buf.putInt(72)
        val seg = ByteArray(16)
        "__PAGEZERO".toByteArray(Charsets.US_ASCII).copyInto(seg)
        buf.put(seg)
        buf.putLong(0L)
        buf.putLong(0x100000000L)
        buf.putLong(0L)
        buf.putLong(0L)
        buf.putInt(0)
        buf.putInt(0)
        buf.putInt(0)
        buf.putInt(0)

        // Cmd 2: LC_RPATH (0x8000001C) at offset 104
        buf.putInt(0x8000001C.toInt())
        buf.putInt(48)
        buf.putInt(12) // path offset = 12
        val rpathBytes = ByteArray(36)
        "@executable_path/Frameworks".toByteArray(Charsets.US_ASCII).copyInto(rpathBytes)
        buf.put(rpathBytes)

        val (patched, _, _) = MachOGuestPatcher.patchBytes(buf.array())
        val strSlice = patched.copyOfRange(104 + 12, 104 + 48)
        val nul = strSlice.indexOf(0.toByte()).let { if (it < 0) strSlice.size else it }
        val patchedRpath = String(strSlice, 0, nul, Charsets.US_ASCII)
        assertEquals("@loader_path/Frameworks", patchedRpath)
    }
}
