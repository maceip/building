package dev.ide.gradle

import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class PrefixTest {

    @Test
    fun aDebUnpacksOntoThePrefixAndKeepsTheShSymlink() {
        val dash = "#!/bin/echo\n".toByteArray()
        val deb = deb(
            files = listOf("data/data/com.termux/files/usr/bin/dash" to dash),
            links = listOf("data/data/com.termux/files/usr/bin/sh" to "dash"),
        )
        val dest = Files.createTempDirectory("prefix")
        DebUnpacker.unpack(deb, dest)
        assertEquals(dash.toList(), Files.readAllBytes(dest.resolve("bin/dash")).toList())
        assertTrue(Files.isSymbolicLink(dest.resolve("bin/sh")))
        assertEquals("dash", Files.readSymbolicLink(dest.resolve("bin/sh")).toString())
    }

    @Test
    fun aChecksumMismatchFailsAndIsNotRetried() {
        val deb = deb(files = listOf("data/data/com.termux/files/usr/bin/dash" to byteArrayOf(1)))
        val catalog = Prefix.Catalog(
            "https://packages.example.invalid",
            listOf(Prefix.Package("dash", "1", "dash.deb", Prefix.sha256(deb), deb.size.toLong())),
        )
        val calls = AtomicInteger()
        val dest = Files.createTempDirectory("prefix-mismatch")
        assertThrows(Prefix.ChecksumMismatch::class.java) {
            Prefix.install(catalog, dest) {
                calls.incrementAndGet()
                byteArrayOf(9, 9, 9)
            }
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun aNetworkFailureIsRetriedThreeTimes() {
        val catalog = Prefix.Catalog(
            "https://packages.example.invalid",
            listOf(Prefix.Package("dash", "1", "dash.deb", "aa", 1)),
        )
        val calls = AtomicInteger()
        val dest = Files.createTempDirectory("prefix-net")
        assertThrows(IOException::class.java) {
            Prefix.install(catalog, dest) {
                calls.incrementAndGet()
                throw IOException("offline")
            }
        }
        assertEquals(3, calls.get())
    }

    private fun deb(files: List<Pair<String, ByteArray>>, links: List<Pair<String, String>> = emptyList()): ByteArray {
        val tarGz = ByteArrayOutputStream()
        GzipCompressorOutputStream(tarGz).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                for ((name, bytes) in files) {
                    val entry = TarArchiveEntry(name)
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
                for ((name, target) in links) {
                    val entry = TarArchiveEntry(name, TarConstants.LF_SYMLINK)
                    entry.linkName = target
                    tar.putArchiveEntry(entry)
                    tar.closeArchiveEntry()
                }
            }
        }
        val payload = tarGz.toByteArray()
        val out = ByteArrayOutputStream()
        ArArchiveOutputStream(out).use { ar ->
            val entry = ArArchiveEntry("data.tar.gz", payload.size.toLong())
            ar.putArchiveEntry(entry)
            ar.write(payload)
            ar.closeArchiveEntry()
            ar.finish()
        }
        return out.toByteArray()
    }
}
