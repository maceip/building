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
}
