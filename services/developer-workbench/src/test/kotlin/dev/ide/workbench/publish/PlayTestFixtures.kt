package dev.ide.workbench.publish

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Offline protocol/preflight fixture; not claimed to be a runnable or Play-acceptable application. */
internal object PlayTestFixtures {
    const val PACKAGE = "dev.ide.fixture.publisher"
    private const val ANDROID = "http://schemas.android.com/apk/res/android"

    fun signedBundle(directory: File, versionCode: Int = 42, debuggable: Boolean = false, paddingBytes: Int = 650_000, debugSigner: Boolean = false): File {
        directory.mkdirs()
        val key = File(directory, if (debugSigner) "android-debug.p12" else "fixture.p12")
        if (!key.exists()) command("keytool", "-genkeypair", "-alias", "upload", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365", "-storetype", "PKCS12", "-keystore", key.absolutePath, "-storepass", "fixture-password", "-keypass", "fixture-password", "-dname", if (debugSigner) "CN=Android Debug,O=Android,C=US" else "CN=Offline Publisher Fixture", "-noprompt")
        val bundle = File(directory, "fixture-$versionCode${if (debuggable) "-debug" else ""}.aab")
        ZipOutputStream(bundle.outputStream()).use { zip ->
            fun entry(path: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
            entry("BundleConfig.pb", byteArrayOf(0x0a, 0x00))
            entry("base/manifest/AndroidManifest.xml", manifest(versionCode, debuggable))
            entry("base/assets/protocol-fixture.bin", Random(12).nextBytes(paddingBytes))
        }
        command("jarsigner", "-keystore", key.absolutePath, "-storepass", "fixture-password", "-keypass", "fixture-password", "-sigalg", "SHA256withRSA", "-digestalg", "SHA-256", bundle.absolutePath, "upload")
        return bundle
    }

    fun manifest(version: Int = 42, debuggable: Boolean = false): ByteArray {
        fun attr(name: String, text: String? = null, integer: Long? = null, bool: Boolean? = null, ns: String = ANDROID): ByteArray {
            val result = ByteArrayOutputStream()
            if (ns.isNotEmpty()) result.write(message(1, ns.toByteArray()))
            result.write(message(2, name.toByteArray()))
            text?.let { result.write(message(3, it.toByteArray())) }
            integer?.let { result.write(message(6, message(7, numeric(6, it)))) }
            bool?.let { result.write(message(6, message(7, numeric(8, if (it) 1 else 0)))) }
            return result.toByteArray()
        }
        fun element(name: String, attributes: List<ByteArray>, children: List<ByteArray> = emptyList()): ByteArray {
            val body = ByteArrayOutputStream()
            body.write(message(3, name.toByteArray()))
            attributes.forEach { body.write(message(4, it)) }
            children.forEach { body.write(message(5, it)) }
            return message(1, body.toByteArray())
        }
        return element("manifest", listOf(attr("package", PACKAGE, ns = ""), attr("versionCode", integer = version.toLong()), attr("versionName", "1.0")), listOf(
            element("uses-sdk", listOf(attr("minSdkVersion", integer = 26), attr("targetSdkVersion", integer = 35))),
            element("uses-permission", listOf(attr("name", "android.permission.INTERNET"))),
            element("application", listOf(attr("debuggable", bool = debuggable))),
        ))
    }

    fun png(directory: File, width: Int, height: Int, rgba: Boolean, name: String = "fixture.png"): File {
        val file = File(directory, name)
        file.parentFile.mkdirs()
        java.io.DataOutputStream(file.outputStream()).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
            fun chunk(type: String, payload: ByteArray) {
                val nameBytes = type.toByteArray(Charsets.US_ASCII)
                out.writeInt(payload.size); out.write(nameBytes); out.write(payload)
                val crc = java.util.zip.CRC32().apply { update(nameBytes); update(payload) }
                out.writeInt(crc.value.toInt())
            }
            val ihdr = java.nio.ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(if (rgba) 6 else 2).put(0).put(0).put(0).array()
            chunk("IHDR", ihdr)
            val compressed = ByteArrayOutputStream()
            java.util.zip.DeflaterOutputStream(compressed).use { deflater ->
                val row = ByteArray(1 + width * if (rgba) 4 else 3)
                repeat(height) { deflater.write(row) }
            }
            chunk("IDAT", compressed.toByteArray()); chunk("IEND", byteArrayOf())
        }
        return file
    }

    private fun message(field: Int, bytes: ByteArray) = varint((field * 8 + 2).toLong()) + varint(bytes.size.toLong()) + bytes
    private fun numeric(field: Int, value: Long) = varint((field * 8).toLong()) + varint(value)
    private fun varint(number: Long): ByteArray {
        var value = number; val out = ByteArrayOutputStream()
        while (value >= 128) { out.write((value.toInt() and 127) or 128); value = value ushr 7 }
        out.write(value.toInt()); return out.toByteArray()
    }
    private fun command(tool: String, vararg args: String) {
        val executable = File(System.getProperty("java.home"), "bin/$tool")
        require(executable.isFile) { "JDK $tool is required only for this offline test fixture." }
        val process = ProcessBuilder(listOf(executable.absolutePath) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "Fixture signing tool failed: $output" }
    }

    /** Run this test helper's main with one output path to stage an ART instrumentation fixture. */
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Supply the absolute output .aab path." }
        val dir = Files.createTempDirectory("publisher-offline-fixture").toFile()
        try { signedBundle(dir).copyTo(File(args[0]), overwrite = true) } finally { dir.deleteRecursively() }
    }
}
