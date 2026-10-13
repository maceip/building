package dev.ide.android.workbench.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ide.workbench.webapp.StaticWebAppPackager
import dev.ide.workbench.webapp.WebAppPackageRequest
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On-device project generation only. Compiling, installing and exercising the wrapper APK is separate. */
@RunWith(AndroidJUnit4::class)
class WebAppPackagingAcceptanceTest {
    @Test(timeout = 45_000)
    fun reviewedStaticAssetsGenerateAnOfflineNativeProjectWithVerifiedManifest() {
        AcceptanceWorkspace("web-package").use { workspace ->
            val request = fixture(workspace)
            val before = snapshot(request.workspaceRoot)
            val packager = StaticWebAppPackager()
            val review = packager.review(request)
            assertEquals(setOf("index.html", "app.js", "style.css"), review.assets.map { it.path }.toSet())
            assertTrue(review.assetManifestSha256.matches(Regex("[0-9a-f]{64}")))
            assertFalse("Review must be read-only and must not reserve output", Files.exists(review.outputDirectory))
            assertEquals(before, snapshot(request.workspaceRoot))

            val result = packager.create(review)
            assertEquals(review.outputDirectory, result.projectRoot)
            assertEquals(3, result.assetCount)
            assertEquals(review.totalBytes, result.assetBytes)
            assertEquals(review.assetManifestSha256, result.assetManifestSha256)
            assertEquals("dev.ide.fixture.offlineweb", result.applicationId)
            assertEquals(result, packager.generatedProject(result.projectRoot))
            assertTrue(Files.isRegularFile(result.projectRoot.resolve(".platform/workspace.json")))
            assertTrue(Files.isRegularFile(result.projectRoot.resolve("app/src/main/AndroidManifest.xml")))
            assertFalse("Packaging must not silently build an APK", Files.exists(result.debugApk))
            assertFalse("Packaging must not silently sign a release bundle", Files.exists(result.releaseBundle))
            val manifest = JSONObject(Files.readAllBytes(result.assetManifest).toString(Charsets.UTF_8))
            assertEquals(1, manifest.getInt("schema"))
            assertEquals(StaticWebAppPackager.ORIGIN, manifest.getString("origin"))
            assertEquals("index.html", manifest.getString("entryPoint"))
            assertEquals(result.applicationId, manifest.getString("applicationId"))
            assertEquals(7, manifest.getInt("versionCode"))
            assertEquals(result.assetManifestSha256, manifest.getString("assetManifestSha256"))
            val entries = manifest.getJSONArray("files")
            assertEquals(review.assets.size, entries.length())
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                val relative = item.getString("path")
                val included = review.assets.single { it.path == relative }
                val copied = result.projectRoot.resolve("app/src/main/assets/web").resolve(relative)
                assertEquals(included.bytes, item.getLong("bytes"))
                assertEquals(included.sha256, item.getString("sha256"))
                assertEquals(included.bytes, Files.size(copied))
                assertEquals(included.sha256, hash(copied))
                assertTrue(Files.readAllBytes(review.staticDirectory.resolve(relative)).contentEquals(Files.readAllBytes(copied)))
            }
            assertEquals("Source bytes and modification times must remain unchanged", before, snapshot(request.workspaceRoot))
            assertFalse(Files.exists(workspace.resolve("must-not-exist")))
        }
    }

    @Test(timeout = 45_000)
    fun privateFilesLinksAndServerCodeNeverEnterTheGeneratedPublicAssets() {
        AcceptanceWorkspace("web-exclusions").use { workspace ->
            val request = fixture(workspace)
            workspace.write("source/dist/.env", "TOKEN=$SECRET\n")
            workspace.write("source/dist/access-token.json", "{\"value\":\"$SECRET\"}")
            workspace.write("source/dist/package.json", "{\"private\":true}")
            workspace.write("source/dist/server.cjs", "require('node:fs').writeFileSync('${workspace.resolve("must-not-exist")}', '$SECRET');")
            workspace.write("source/dist/app.js.map", "{\"sourcesContent\":[\"$SECRET\"]}")
            workspace.write("source/dist/node_modules/fixture/index.js", "module.exports='$SECRET';")
            workspace.write("source/dist/public.json", "{\"type\":\"service_account\",\"private_key\":\"$SECRET\"}")
            val outside = workspace.write("outside.txt", SECRET)
            Files.createSymbolicLink(workspace.resolve("source/dist/linked.txt"), outside)
            val before = snapshot(request.workspaceRoot)
            val packager = StaticWebAppPackager()
            val review = packager.review(request)
            assertEquals(3, review.assets.size)
            val excluded = review.excluded.map { it.path }.toSet()
            assertTrue("Credential and executable files must be reported, not silently copied", excluded.containsAll(setOf(
                ".env", "access-token.json", "package.json", "server.cjs", "app.js.map", "node_modules/", "public.json", "linked.txt",
            )))
            val result = packager.create(review)
            for (relative in listOf(".env", "access-token.json", "package.json", "server.cjs", "app.js.map", "node_modules", "public.json", "linked.txt")) {
                assertFalse("Excluded input reached the offline asset directory", Files.exists(result.projectRoot.resolve("app/src/main/assets/web").resolve(relative)))
            }
            Files.walkFileTree(result.projectRoot, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    assertFalse("A generated file retained synthetic private content", Files.readAllBytes(file).toString(Charsets.UTF_8).contains(SECRET))
                    return FileVisitResult.CONTINUE
                }
            })
            assertEquals(before, snapshot(request.workspaceRoot))
            assertFalse("Packaging must never run excluded server/source code", Files.exists(workspace.resolve("must-not-exist")))
        }
    }

    @Test(timeout = 45_000)
    fun staleReviewAndExistingDestinationFailWithoutReplacingUserData() {
        AcceptanceWorkspace("web-safety").use { workspace ->
            val request = fixture(workspace)
            val packager = StaticWebAppPackager()
            val stale = packager.review(request)
            workspace.write("source/dist/app.js", "document.body.dataset.changed = 'review-again';\n")
            rejection { packager.create(stale) }
            assertFalse("Stale asset review must leave no generated project", Files.exists(stale.outputDirectory))
            val fresh = packager.review(request)
            val generated = packager.create(fresh)
            val before = snapshot(generated.projectRoot)
            rejection { packager.create(fresh) }
            assertEquals("A second create must not overwrite the generated project", before, snapshot(generated.projectRoot))

            val existing = Files.createDirectories(request.outputParent.resolve("existing"))
            val sentinel = existing.resolve("keep.txt")
            Files.write(sentinel, "Existing user data stays untouched".toByteArray(Charsets.UTF_8))
            val existingBefore = snapshot(existing)
            rejection { packager.review(request.copy(projectName = "existing")) }
            assertEquals(existingBefore, snapshot(existing))
            rejection { packager.review(request.copy(projectName = "inside", outputParent = request.workspaceRoot)) }
            rejection { packager.review(request.copy(projectName = "escape", staticRoot = "../outside")) }
            assertFalse(Files.exists(request.workspaceRoot.resolve("inside")))
            assertFalse(Files.exists(request.outputParent.resolve("escape")))
        }
    }

    private fun fixture(workspace: AcceptanceWorkspace): WebAppPackageRequest {
        workspace.write("source/dist/index.html", """
            <!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="style.css"></head>
            <body><h1>Offline packaged fixture</h1><script src="app.js"></script></body></html>
        """.trimIndent())
        workspace.write("source/dist/app.js", "document.body.dataset.offline = 'true';\n")
        workspace.write("source/dist/style.css", "body { color: #123; background: #fff; }\n")
        val output = Files.createDirectories(workspace.resolve("generated"))
        return WebAppPackageRequest(
            workspaceRoot = workspace.resolve("source"),
            staticRoot = "dist",
            outputParent = output,
            projectName = "OfflineFixture",
            applicationId = "dev.ide.fixture.offlineweb",
            appLabel = "Offline fixture",
            versionCode = 7,
            versionName = "1.0.7",
            spaFallback = true,
            allowExternalHttps = false,
        )
    }

    private fun rejection(action: () -> Unit) {
        try { action() } catch (_: IllegalArgumentException) { return }
        throw AssertionError("Unsafe or stale static packaging input was not rejected")
    }

    private fun hash(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun snapshot(root: Path): Map<String, String> {
        val files = sortedMapOf<String, String>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                files[root.relativize(file).toString()] = if (attrs.isSymbolicLink) "link:${Files.readSymbolicLink(file)}"
                else "${attrs.size()}:${attrs.lastModifiedTime().toMillis()}:${hash(file)}"
                return FileVisitResult.CONTINUE
            }
        })
        return files
    }

    private companion object { const val SECRET = "offline-webapp-private-fixture-marker" }
}
