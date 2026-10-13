// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.workbench.appengine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppEnginePackagerTest {
    @TempDir lateinit var temp: Path

    @Test fun `GPL and Node support consent are required instead of silent release claims`() {
        assertFailsWith<IllegalArgumentException> { AppEnginePackager().create(request().copy(acknowledgeGplDistribution = false)) }
        assertFailsWith<IllegalArgumentException> { AppEnginePackager().create(request().copy(engine = AppEngine.Node)) }
        assertTrue(AppEngineCapabilities.limitations(AppEngine.Node).any { "unsupported upstream" in it })
        assertTrue(AppEngineCapabilities.limitations(AppEngine.Python).any { "sqlite3" in it })
    }

    @Test fun `copies only selected runtime into standalone native app and keeps real source bundle outside assets`() {
        val request = request()
        val result = AppEnginePackager().create(request)
        val root = result.projectRoot
        assertTrue(Files.isRegularFile(root.resolve("libs/runtime-engine.jar")))
        assertTrue(Files.isRegularFile(root.resolve("app/src/main/jniLibs/arm64-v8a/libpython3.14.so")))
        assertFalse(Files.exists(root.resolve("app/src/main/jniLibs/arm64-v8a/libnode.so")))
        assertTrue(Files.isRegularFile(result.sourceBundle))
        assertFalse(Files.walk(root.resolve("app/src/main/assets")).use { files -> files.anyMatch { it.toString().contains("corresponding-source") } })
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(root.resolve("app/src/main/AndroidManifest.xml").toFile())
        assertEquals(0, manifest.getElementsByTagName("uses-permission").length)
        val service = manifest.getElementsByTagName("service").item(0).attributes
        assertEquals(":runtime", service.getNamedItem("android:process").nodeValue)
        assertEquals("false", service.getNamedItem("android:exported").nodeValue)
        assertTrue(Files.readString(root.resolve("app/module.toml")).contains("implementation = [\"runtime-engine\"]"))
        assertTrue(Files.readString(root.resolve("app/src/main/assets/runtime-engine/app/files.sha256")).contains("compute.py"))
        assertFalse(Files.exists(root.resolve("gradlew")))
    }

    @Test fun `script argv limits are UTF8 bounded and never become engine options`() {
        val request = request()
        assertFailsWith<IllegalArgumentException> { AppEnginePackager().create(request.copy(arguments = List(129) { "x" })) }
        assertFailsWith<IllegalArgumentException> { AppEnginePackager().create(request.copy(arguments = listOf("nul\u0000byte"))) }
        assertFailsWith<IllegalArgumentException> { AppEnginePackager().create(request.copy(arguments = listOf("你".repeat(11000)))) }
        val result = AppEnginePackager().create(request.copy(arguments = listOf("--eval", "--require", "; touch not-a-shell")))
        val source = Files.readString(result.projectRoot.resolve("app/src/main/java/dev/fixture/engine/MainActivity.java"))
        assertTrue(source.contains("java.util.Arrays.asList(\"--eval\", \"--require\", \"; touch not-a-shell\")"))
        assertFalse(source.contains("ProcessBuilder"))
    }

    @Test fun `tampered native payload is rejected before destination creation`() {
        val request = request()
        Files.writeString(request.payloadRoot.resolve("jniLibs/arm64-v8a/libpython3.14.so"), "tampered")
        assertFailsWith<IllegalArgumentException> { AppEnginePackager().create(request) }
        assertFalse(Files.exists(request.destination))
    }

    @Test fun `traversal native packages hidden secrets and symlinks fail closed`() {
        val request = request()
        assertFailsWith<IllegalArgumentException> { AppEnginePackager().create(request.copy(entry = "../compute.py")) }
        Files.writeString(request.sourceRoot.resolve(".env"), "DO_NOT_PACKAGE=secret")
        assertFailsWith<IllegalArgumentException> { AppEnginePackager().create(request) }
        Files.delete(request.sourceRoot.resolve(".env"))
        Files.writeString(request.sourceRoot.resolve("extension.so"), "native")
        assertFailsWith<IllegalArgumentException> { AppEnginePackager().create(request) }
        Files.delete(request.sourceRoot.resolve("extension.so"))
        Files.createSymbolicLink(request.sourceRoot.resolve("linked.py"), request.sourceRoot.resolve("compute.py"))
        assertFailsWith<IllegalArgumentException> { AppEnginePackager().create(request) }
    }

    @Test fun `generated Java compiles with actual core source and actual Android API not permissive stubs`() {
        val request = request().copy(arguments = listOf("\" ); throw new RuntimeException(\"injection\"); //", "one\ntwo\rthree\t", "\\u000a", "你好 café", "\\", "\u0001"))
        val root = AppEnginePackager().create(request).projectRoot
        val repository = repo()
        val android = repository.resolve("app/ide-android/src/main/sdk/android.jar")
        assertTrue(Files.isRegularFile(android))
        val core = Files.walk(repository.resolve("android/runtime-engine/core/src/main/java")).use { files -> files.filter { it.toString().endsWith(".java") }.toList() }
        val source = root.resolve("app/src/main/java/dev/fixture/engine/MainActivity.java")
        val classes = Files.createDirectory(temp.resolve("compiled")); val errors = ByteArrayOutputStream()
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler())
        val result = compiler.run(null, errors, errors, "--release", "8", "-Xlint:-options", "-cp", android.toString(),
            "-d", classes.toString(), *core.map(Path::toString).toTypedArray(), source.toString())
        assertEquals(0, result, errors.toString())
        assertTrue(Files.isRegularFile(classes.resolve("dev/fixture/engine/MainActivity.class")))
    }

    /** Byte fixtures test packaging rejection only, never claim a test ELF is a working interpreter. */
    private fun request(): AppEnginePackageRequest {
        val case = Files.createTempDirectory(temp, "case-")
        val scripts = Files.createDirectory(case.resolve("scripts")); Files.writeString(scripts.resolve("compute.py"), "print(6 * 7)\n")
        val payload = Files.createDirectory(case.resolve("payload"))
        val native = Files.createDirectories(payload.resolve("jniLibs/arm64-v8a"))
        for (name in listOf("libengine_host.so", "libengine_python.so", "libpython3.14.so")) {
            val bytes = ByteArray(4096); byteArrayOf(127, 69, 76, 70, 2, 1).copyInto(bytes); bytes[18] = 0xb7.toByte()
            Files.write(native.resolve(name), bytes)
        }
        val assets = Files.createDirectories(payload.resolve("assets/runtime-engine/python"))
        Files.writeString(assets.resolve("engine.properties"), "schema=1\nengine=python\nversion=3.14.8\nabi=arm64-v8a\napi=26\n")
        Files.writeString(assets.resolve("files.sha256"), "# test asset parser not invoked\n")
        Files.write(payload.resolve("python-3.14.8-corresponding-source.tar.xz"), ByteArray(2048))
        val jar = case.resolve("runtime.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            for (name in listOf("EngineClient", "RuntimeService", "NativeBridge")) {
                zip.putNextEntry(ZipEntry("dev/ide/runtime/engine/$name.class")); zip.write(byteArrayOf(0)); zip.closeEntry()
            }
        }
        val checksums = Files.walk(payload).use { files -> files.filter(Files::isRegularFile).sorted().map { file ->
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) } + "  " + payload.relativize(file)
        }.toList().joinToString("\n") + "\n" }
        Files.writeString(payload.resolve("SHA256SUMS"), checksums)
        return AppEnginePackageRequest(scripts, "compute.py", case.resolve("generated"), "dev.fixture.engine", "Engine fixture",
            AppEngine.Python, payload, jar, acknowledgeGplDistribution = true)
    }
    private fun repo(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
}
