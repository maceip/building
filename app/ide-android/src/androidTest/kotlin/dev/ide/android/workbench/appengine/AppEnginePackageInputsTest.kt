// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.android.workbench.appengine

import dev.ide.workbench.appengine.AppEngine
import dev.ide.workbench.appengine.AppEnginePackageRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** File fixtures exercise UI preflight/generator wiring, not runtime execution or legal compliance. */
class AppEnginePackageInputsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root(): Path = temporary.root.toPath().toRealPath()

    @Test fun emptyWorkspaceNeverFallsBackToProcessWorkingDirectory() {
        rejects { EnginePackageInputs.sourceDirectory("", ".") }
        rejects { EnginePackageInputs.sourceDirectory("relative", ".") }
        rejects { EnginePackageInputs.sourceDirectory(root().toString(), "../elsewhere") }
        assertEquals(root(), EnginePackageInputs.sourceDirectory(root().toString(), "."))
    }

    @Test fun pathsRefuseTraversalLinksExistingOutputAndSourceOverlap() {
        val fixture = fixture()
        for (name in listOf("../other", "a/b", ".", "..", "", "/tmp/out", "bad\\name")) {
            rejects { EnginePackageInputs.destination(root().toString(), name) }
        }
        rejects { EnginePackageInputs.destination(fixture.request.sourceRoot.parent.toString(), fixture.request.sourceRoot.fileName.toString()) }
        rejects { EnginePackageInputs.review(fixture.request.copy(entry = "../main.py"), fixture.archive.toString()) }
        rejects { EnginePackageInputs.review(fixture.request.copy(destination = fixture.request.sourceRoot.resolve("out")), fixture.archive.toString()) }
        rejects { EnginePackageInputs.review(fixture.request.copy(destination = fixture.request.payloadRoot.resolve("out")), fixture.archive.toString()) }
        val link = root().resolve("linked-source")
        Files.createSymbolicLink(link, fixture.request.sourceRoot)
        rejects { EnginePackageInputs.sourceDirectory(link.toString(), ".") }
        Files.createSymbolicLink(fixture.request.sourceRoot.resolve("linked.py"), fixture.request.sourceRoot.resolve("main.py"))
        rejects { EnginePackageInputs.review(fixture.request, fixture.archive.toString()) }
        assertFalse(Files.exists(fixture.request.destination))
    }

    @Test fun hiddenInputsAreRejectedInsteadOfSilentlyDroppingPossibleSecrets() {
        val fixture = fixture()
        Files.write(fixture.request.sourceRoot.resolve(".env"), "SYNTHETIC_TEST_ONLY=do-not-publish".toByteArray())
        rejects { EnginePackageInputs.review(fixture.request, fixture.archive.toString()) }
        assertFalse(Files.exists(fixture.request.destination))
    }

    @Test fun emptySourceMissingEntryAndWrongEngineFailBeforeCreation() {
        val fixture = fixture()
        rejects { EnginePackageInputs.review(fixture.request.copy(entry = "main.js"), fixture.archive.toString()) }
        rejects { EnginePackageInputs.review(fixture.request.copy(entry = "absent.py"), fixture.archive.toString()) }
        Files.delete(fixture.request.sourceRoot.resolve("main.py"))
        rejects { EnginePackageInputs.review(fixture.request, fixture.archive.toString()) }
        assertFalse(Files.exists(fixture.request.destination))
    }

    @Test fun exactSourcePackageIsRequiredAndSafCopyMustMatchItsStreamingHash() {
        val fixture = fixture()
        rejects { EnginePackageInputs.review(fixture.request, "") }
        val imported = Files.createDirectories(root().resolve("picked-source")).resolve(fixture.archive.fileName)
        Files.copy(fixture.archive, imported)
        val reviewed = EnginePackageInputs.review(fixture.request, imported.toString())
        assertEquals(imported, reviewed.selectedSourceArchive)
        assertEquals(2048L, reviewed.correspondingSource.bytes)
        Files.write(imported, ByteArray(2048) { 7 })
        rejects { EnginePackageInputs.review(fixture.request, imported.toString()) }
        assertFalse(Files.exists(fixture.request.destination))
    }

    @Test fun reviewIsReadOnlyAndActualPackagerCopiesOnlyReviewedAppInputs() {
        val fixture = fixture()
        val original = EnginePackageInputs.hash(fixture.request.sourceRoot.resolve("main.py"))
        val before = EnginePackageInputs.hash(fixture.archive)
        val review = EnginePackageInputs.review(fixture.request.copy(arguments = listOf("--name", "a b")), fixture.archive.toString())
        assertEquals("3.14.8", review.version)
        assertEquals("GPL-3.0-or-later", review.license)
        assertTrue(review.notice.contains("fixture"))
        assertFalse(Files.exists(fixture.request.destination))
        val generated = EnginePackageInputs.create(review, acknowledgeGpl = true, allowExperimentalNode = false)
        assertEquals(fixture.request.destination, generated.projectRoot)
        assertTrue(Files.isRegularFile(generated.projectRoot.resolve(".platform/workspace.json")))
        assertTrue(Files.isRegularFile(generated.projectRoot.resolve("app/module.toml")))
        assertTrue(Files.isRegularFile(generated.projectRoot.resolve("libs/runtime-engine.jar")))
        assertFalse(Files.exists(generated.projectRoot.resolve("gradlew")))
        assertEquals(original, EnginePackageInputs.hash(fixture.request.sourceRoot.resolve("main.py")))
        assertEquals(original, EnginePackageInputs.hash(generated.projectRoot.resolve("app/src/main/assets/runtime-engine/app/main.py")))
        assertEquals(before, EnginePackageInputs.hash(generated.sourceBundle))
        val java = String(Files.readAllBytes(generated.projectRoot.resolve("app/src/main/java/com/example/testengine/MainActivity.java")))
        assertTrue(java.contains("java.util.Arrays.asList(\"--name\", \"a b\")"))
        rejects { EnginePackageInputs.create(review, acknowledgeGpl = true, allowExperimentalNode = false) }
        assertEquals(original, EnginePackageInputs.hash(fixture.request.sourceRoot.resolve("main.py")))
    }

    @Test fun gplAndNodeAcknowledgementsAreIndependentHardGates() {
        val fixture = fixture(AppEngine.Node)
        val review = EnginePackageInputs.review(fixture.request, fixture.archive.toString())
        rejects { EnginePackageInputs.create(review, acknowledgeGpl = false, allowExperimentalNode = true) }
        rejects { EnginePackageInputs.create(review, acknowledgeGpl = true, allowExperimentalNode = false) }
        assertFalse(Files.exists(fixture.request.destination))
        val result = EnginePackageInputs.create(review, acknowledgeGpl = true, allowExperimentalNode = true)
        assertTrue(Files.isRegularFile(result.projectRoot.resolve("app/src/main/jniLibs/arm64-v8a/libnode.so")))
        assertFalse(Files.exists(result.projectRoot.resolve("app/src/main/jniLibs/arm64-v8a/libpython3.14.so")))
    }

    @Test fun sourcePayloadAndSdkChangesInvalidatePreviousReview() {
        for (kind in listOf("source", "payload", "sdk")) {
            val fixture = fixture()
            val review = EnginePackageInputs.review(fixture.request, fixture.archive.toString())
            when (kind) {
                "source" -> Files.write(fixture.request.sourceRoot.resolve("main.py"), "print('changed')".toByteArray())
                "payload" -> Files.write(fixture.archive, ByteArray(2048) { 9 })
                "sdk" -> Files.write(fixture.request.runtimeClassesJar, sdkJar(extraMarker = "changed"))
            }
            rejects { EnginePackageInputs.create(review, acknowledgeGpl = true, allowExperimentalNode = false) }
            assertFalse(Files.exists(fixture.request.destination))
        }
    }

    @Test fun payloadIndexRejectsTraversalDuplicateNamesAndLinkedAncestors() {
        for (kind in listOf("traversal", "duplicate", "link")) {
            val fixture = fixture()
            val index = fixture.request.payloadRoot.resolve("SHA256SUMS")
            when (kind) {
                "traversal" -> Files.write(index, ("0".repeat(64) + "  ../outside\n").toByteArray())
                "duplicate" -> {
                    val text = String(Files.readAllBytes(index))
                    Files.write(index, (text + text.lineSequence().first() + "\n").toByteArray())
                }
                "link" -> {
                    val external = Files.createDirectories(root().resolve("outside-${fixture.request.payloadRoot.fileName}"))
                    Files.write(external.resolve("file"), "outside".toByteArray())
                    Files.createSymbolicLink(fixture.request.payloadRoot.resolve("linked"), external)
                    Files.write(index, (EnginePackageInputs.hash(external.resolve("file")) + "  linked/file\n").toByteArray())
                }
            }
            rejects { EnginePackageInputs.review(fixture.request, fixture.archive.toString()) }
        }
    }

    @Test fun oversizedSparseArchiveIsRejectedWithoutReadingItIntoMemory() {
        val fixture = fixture()
        RandomAccessFile(fixture.archive.toFile(), "rw").use { it.setLength(EnginePackageInputs.PAYLOAD_BUDGET + 1) }
        rejects { EnginePackageInputs.review(fixture.request, fixture.archive.toString()) }
        assertFalse(Files.exists(fixture.request.destination))
    }

    @Test fun argumentsAreLiteralBoundedUtf8ValuesNotAShellCommand() {
        assertEquals(emptyList<String>(), EnginePackageInputs.arguments(""))
        assertEquals(listOf("a b", "--eval", "$(not a command)", ""), EnginePackageInputs.arguments("a b\n--eval\n$(not a command)\n"))
        assertEquals(listOf("a", "b"), EnginePackageInputs.arguments("a\r\nb"))
        rejects { EnginePackageInputs.arguments("x\u0000y") }
        rejects { EnginePackageInputs.arguments(List(129) { "x" }.joinToString("\n")) }
        rejects { EnginePackageInputs.arguments("你".repeat(11000)) }
        assertEquals(128, EnginePackageInputs.arguments(List(128) { "x" }.joinToString("\n")).size)
    }

    @Test fun sdkCacheUsesApkStampAndNeverOverwritesMismatchedExistingFile() {
        val cache = Files.createDirectory(root().resolve("cache"))
        val asset = sdkJar()
        val path = EngineSdkCache.extract(cache, "123-456") { ByteArrayInputStream(asset) }
        assertEquals(cache.resolve("runtime-sdk/123-456/runtime-engine.jar"), path)
        assertEquals(path, EngineSdkCache.extract(cache, "123-456") { ByteArrayInputStream(asset) })
        Files.write(path, "pre-existing-mismatch".toByteArray())
        rejects { EngineSdkCache.extract(cache, "123-456") { ByteArrayInputStream(asset) } }
        assertEquals("pre-existing-mismatch", String(Files.readAllBytes(path)))
        val updated = EngineSdkCache.extract(cache, "123-789") { ByteArrayInputStream(asset) }
        assertTrue(Files.isRegularFile(updated))
        rejects { EngineSdkCache.extract(cache, "../bad") { ByteArrayInputStream(asset) } }
    }

    @Test fun sdkCacheRejectsNonJava8OrIdeContaminatedLibraries() {
        val cache = Files.createDirectory(root().resolve("cache"))
        rejects { EngineSdkCache.extract(cache, "java17") { ByteArrayInputStream(sdkJar(major = 61)) } }
        rejects { EngineSdkCache.extract(cache, "ide-classes") { ByteArrayInputStream(sdkJar(ideClass = true)) } }
        rejects { EngineSdkCache.extract(cache, "empty") { ByteArrayInputStream(ByteArray(0)) } }
        assertFalse(Files.exists(cache.resolve("runtime-sdk/java17/runtime-engine.jar")))
        assertFalse(Files.exists(cache.resolve("runtime-sdk/ide-classes/runtime-engine.jar")))
    }

    private data class Fixture(val request: AppEnginePackageRequest, val archive: Path)

    private fun fixture(engine: AppEngine = AppEngine.Python): Fixture {
        val base = Files.createTempDirectory(root(), "case-")
        val source = Files.createDirectory(base.resolve("source"))
        val entry = if (engine == AppEngine.Python) "main.py" else "main.js"
        Files.write(source.resolve(entry), "print(42)\n".toByteArray())
        val payload = Files.createDirectory(base.resolve("payload"))
        val libs = Files.createDirectories(payload.resolve("jniLibs/arm64-v8a"))
        for (name in listOf("libengine_host.so", "libengine_${engine.id}.so", if (engine == AppEngine.Python) "libpython3.14.so" else "libnode.so")) {
            val bytes = ByteArray(4096)
            byteArrayOf(127, 69, 76, 70, 2, 1).copyInto(bytes)
            bytes[18] = 0xb7.toByte()
            Files.write(libs.resolve(name), bytes)
        }
        val version = if (engine == AppEngine.Python) "3.14.8" else "24.13.0"
        val assets = Files.createDirectories(payload.resolve("assets/runtime-engine/${engine.id}"))
        Files.write(assets.resolve("engine.properties"), "schema=1\nengine=${engine.id}\nversion=$version\nabi=arm64-v8a\napi=26\nlicense=GPL-3.0-or-later\nqualified=false\nsupport=test-fixture-only\n".toByteArray())
        Files.write(assets.resolve("files.sha256"), "fixture asset index — not runtime execution\n".toByteArray())
        Files.write(Files.createDirectories(assets.resolve("licenses")).resolve("NOTICE.txt"), "Test fixture notice: GPL-3.0-or-later; no runtime linking exception.\n".toByteArray())
        val archive = payload.resolve("${engine.id}-$version-corresponding-source.tar.xz")
        Files.write(archive, ByteArray(2048) { (it % 251).toByte() })
        val jar = base.resolve("runtime.jar")
        Files.write(jar, sdkJar())
        val index = Files.walk(payload).use { paths ->
            paths.filter { Files.isRegularFile(it) }.sorted().map { path ->
                "${EnginePackageInputs.hash(path)}  ${payload.relativize(path).joinToString("/")}"
            }.toArray().joinToString("\n") + "\n"
        }
        Files.write(payload.resolve("SHA256SUMS"), index.toByteArray())
        return Fixture(AppEnginePackageRequest(source, entry, base.resolve("generated"), "com.example.testengine", "Fixture app", engine, payload, jar), archive)
    }

    private fun sdkJar(major: Int = 52, ideClass: Boolean = false, extraMarker: String = ""): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for (name in listOf("EngineClient", "RuntimeService", "NativeBridge")) {
                zip.putNextEntry(ZipEntry("dev/ide/runtime/engine/$name.class"))
                zip.write(byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte(), 0, 0, 0, major.toByte()))
                zip.closeEntry()
            }
            if (ideClass) { zip.putNextEntry(ZipEntry("dev/ide/android/Unexpected.class")); zip.write(1); zip.closeEntry() }
            if (extraMarker.isNotEmpty()) { zip.putNextEntry(ZipEntry("fixture-marker")); zip.write(extraMarker.toByteArray()); zip.closeEntry() }
        }
        return output.toByteArray()
    }

    private fun rejects(action: () -> Unit) {
        try { action() } catch (_: IllegalArgumentException) { return } catch (_: IllegalStateException) { return }
        throw AssertionError("Expected invalid input to be rejected")
    }
}
