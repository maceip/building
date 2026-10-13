package dev.ide.workbench.webapp

import dev.ide.model.ContentRole
import dev.ide.model.LanguageLevel
import dev.ide.model.impl.ModelPersistence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StaticWebAppPackagerTest {
    @TempDir lateinit var temp: Path

    private fun fixture(): WebAppPackageRequest {
        val source = Files.createDirectories(temp.resolve("source/dist"))
        Files.writeString(source.resolve("index.html"), "<!doctype html><title>Offline fixture</title><script src=\"main.js\"></script>")
        Files.writeString(source.resolve("main.js"), "document.body.dataset.ready='yes';")
        return WebAppPackageRequest(
            workspaceRoot = temp.resolve("source"), staticRoot = "dist",
            outputParent = Files.createDirectories(temp.resolve("projects")), projectName = "OfflineFixture",
            applicationId = "dev.fixture.offline", appLabel = "Offline <&> O'Brien \"app\"", versionCode = 17, versionName = "2.1-beta",
        )
    }

    @Test fun `real native model loads assets sources and Android facet without Gradle`() {
        val request = fixture()
        val sourceBefore = tree(request.workspaceRoot)
        val packager = StaticWebAppPackager()
        val review = packager.review(request)
        val result = packager.create(review)
        val model = ModelPersistence.load(result.projectRoot.toString())
        val project = model.projects.single()
        val module = project.modules.single()
        assertEquals("native", project.buildSystemId)
        assertEquals(".", project.rootRelPath)
        assertEquals("app", module.dirRelPath)
        assertEquals("android-app", module.typeId)
        assertEquals(LanguageLevel.JAVA_8, module.languageLevel)
        assertEquals(listOf("main", "debug", "release"), module.sourceSets.map { it.name })
        assertTrue(module.sourceSets.first().contentRoots.any { it.dirRelPath == "src/main/assets" && ContentRole.ASSETS in it.roles })
        assertTrue(module.sourceSets.first().contentRoots.any { it.dirRelPath == "src/main/java" && ContentRole.SOURCE in it.roles })
        val facet = module.facets.single { it.tomlTable == "android" }.values
        assertEquals(request.applicationId, facet["namespace"])
        assertEquals(17L, facet["versionCode"])
        assertEquals("2.1-beta", facet["versionName"])
        assertEquals(26L, facet["minSdk"])
        assertEquals(36L, facet["compileSdk"])
        assertEquals(36L, facet["targetSdk"])
        assertEquals("src/main/AndroidManifest.xml", facet["manifest"])
        val types = facet["buildTypes"] as List<*>
        assertEquals(listOf("debug", "release"), types.map { (it as Map<*, *>)["name"] })
        assertTrue(types.none { (it as Map<*, *>).containsKey("signingConfig") })
        assertTrue(module.dependencies.isEmpty())
        assertTrue(model.sdks.isEmpty()) // Host seeds its already-bundled SDK on open.
        assertFalse(Files.exists(result.projectRoot.resolve("gradlew")))
        assertFalse(Files.exists(result.projectRoot.resolve("build.gradle")))
        assertEquals(sourceBefore, tree(request.workspaceRoot))
        assertEquals(result, packager.generatedProject(result.projectRoot))
        assertEquals(result.projectRoot.resolve("app/build/outputs/bundle/release/app-release.aab"), result.releaseBundle)
    }

    @Test fun `review records actual bytes and stable SHA-256 manifest and copies binary exactly`() {
        val request = fixture()
        val binary = byteArrayOf(0, 1, 2, 127, -1, 0, 42)
        Files.write(request.workspaceRoot.resolve("dist/image.png"), binary)
        val packager = StaticWebAppPackager()
        val first = packager.review(request)
        assertEquals(first.assets, packager.review(request).assets)
        assertEquals(first.assetManifestSha256, packager.review(request).assetManifestSha256)
        val result = packager.create(first)
        assertTrue(binary.contentEquals(Files.readAllBytes(result.projectRoot.resolve("app/src/main/assets/web/image.png"))))
        val manifest = Json.parseToJsonElement(Files.readString(result.assetManifest)).jsonObject
        val assets = manifest.getValue("files").jsonArray
        assertEquals(first.assetManifestSha256, hash(assets.toString().toByteArray()))
        assertEquals(first.assetManifestSha256, manifest.getValue("assetManifestSha256").jsonPrimitive.content)
        first.assets.forEach { asset ->
            val bytes = Files.readAllBytes(result.projectRoot.resolve("app/src/main/assets/web/${asset.path}"))
            assertEquals(asset.bytes, bytes.size.toLong())
            assertEquals(asset.sha256, hash(bytes))
        }
        assertFalse(Files.readString(result.assetManifest).contains(request.workspaceRoot.toString()))
    }

    @Test fun `excludes hidden secrets server sources dependencies sourcemaps symlinks and hardlinks`() {
        val request = fixture()
        val dist = request.workspaceRoot.resolve("dist")
        listOf(".env", ".env.production", "upload.jks", "server.py", "server.js", "main.js.map", "package.json", "service-account.json", "access_token.json").forEach {
            Files.writeString(dist.resolve(it), "credential")
        }
        listOf(".git", "node_modules", "secrets").forEach {
            Files.createDirectory(dist.resolve(it))
            Files.writeString(dist.resolve(it).resolve("do-not-ship.txt"), "credential")
        }
        Files.writeString(dist.resolve("innocent.json"), "{\"type\": \"service_account\",\"private_key\":\"private\"}")
        Files.writeString(dist.resolve("certificate.txt"), "x".repeat(32 * 1024 - 10) + "-----BEGIN RSA PRIVATE KEY-----")
        val external = Files.writeString(temp.resolve("external.txt"), "private source")
        Files.createSymbolicLink(dist.resolve("link.txt"), external)
        Files.createSymbolicLink(dist.resolve("folder"), request.outputParent)
        Files.createLink(dist.resolve("hard.txt"), external)
        val packager = StaticWebAppPackager()
        val review = packager.review(request)
        assertEquals(listOf("index.html", "main.js"), review.assets.map { it.path })
        assertTrue(review.excluded.any { it.path == "link.txt" && "Symbolic" in it.reason })
        assertTrue(review.excluded.any { it.path == "hard.txt" && "Hard-linked" in it.reason })
        assertTrue(review.excluded.any { it.path == "certificate.txt" && "credential" in it.reason })
        val result = packager.create(review)
        assertEquals(setOf("index.html", "main.js"), tree(result.projectRoot.resolve("app/src/main/assets/web")).keys)
        assertEquals("private source", Files.readString(external))
    }

    @Test fun `rejects traversal absolute paths hidden roots and symlinked static roots`() {
        val request = fixture()
        val packager = StaticWebAppPackager()
        for (path in listOf("../projects", "dist/../dist", "dist//", "dist\\other", "dist/%2e%2e", "dist/./", "", ".git", "/tmp", "node_modules")) {
            assertFailsWith<IllegalArgumentException>(path) { packager.review(request.copy(staticRoot = path)) }
        }
        Files.createSymbolicLink(request.workspaceRoot.resolve("alias"), request.workspaceRoot.resolve("dist"))
        assertFailsWith<IllegalArgumentException> { packager.review(request.copy(staticRoot = "alias")) }
        Files.createSymbolicLink(request.workspaceRoot.resolve("outside"), request.outputParent)
        assertFailsWith<IllegalArgumentException> { packager.review(request.copy(staticRoot = "outside")) }
    }

    @Test fun `explicit dot is allowed only when that workspace already has index html`() {
        val request = fixture()
        val packager = StaticWebAppPackager()
        assertFailsWith<IllegalArgumentException> { packager.review(request.copy(staticRoot = ".")) }
        val result = packager.review(request.copy(workspaceRoot = request.workspaceRoot.resolve("dist"), staticRoot = "."))
        assertEquals(listOf("index.html", "main.js"), result.assets.map { it.path })
    }

    @Test fun `missing empty directory or linked index html does not masquerade as static output`() {
        val request = fixture()
        val index = request.workspaceRoot.resolve("dist/index.html")
        val packager = StaticWebAppPackager()
        Files.delete(index)
        assertFailsWith<IllegalArgumentException> { packager.review(request) }
        Files.createDirectory(index)
        assertFailsWith<IllegalArgumentException> { packager.review(request) }
        Files.delete(index)
        Files.writeString(index, "")
        assertFailsWith<IllegalArgumentException> { packager.review(request) }
        Files.delete(index)
        Files.createSymbolicLink(index, request.workspaceRoot.resolve("dist/main.js"))
        assertFailsWith<IllegalArgumentException> { packager.review(request) }
    }

    @Test fun `file bytes total bytes count entries and nesting limits fail closed`() {
        val request = fixture()
        assertFailsWith<IllegalArgumentException> {
            StaticWebAppPackager(WebAppPackageLimits(maxFileBytes = 8)).review(request)
        }
        assertFailsWith<IllegalArgumentException> {
            StaticWebAppPackager(WebAppPackageLimits(maxTotalBytes = 90)).review(request)
        }
        assertFailsWith<IllegalArgumentException> {
            StaticWebAppPackager(WebAppPackageLimits(maxFiles = 1)).review(request)
        }
        assertFailsWith<IllegalArgumentException> {
            StaticWebAppPackager(WebAppPackageLimits(maxFiles = 1, maxEntries = 1)).review(request)
        }
        val nested = Files.createDirectories(request.workspaceRoot.resolve("dist/a/b"))
        Files.writeString(nested.resolve("c.txt"), "nested")
        assertFailsWith<IllegalArgumentException> {
            StaticWebAppPackager(WebAppPackageLimits(maxDepth = 2)).review(request)
        }
    }

    @Test fun `invalid names label version and portable asset paths cannot inject generated source`() {
        val request = fixture()
        val packager = StaticWebAppPackager()
        for (name in listOf("../source", "", "1project", "with/slash", "a\"\nb", "-option", "a".repeat(65))) {
            assertFailsWith<IllegalArgumentException>(name) { packager.review(request.copy(projectName = name)) }
        }
        for (pkg in listOf("app", "com.class.app", "com.Example", "com.a-b", "com.1app", "com.example;evil", "a.")) {
            assertFailsWith<IllegalArgumentException>(pkg) { packager.review(request.copy(applicationId = pkg)) }
        }
        assertFailsWith<IllegalArgumentException> { packager.review(request.copy(appLabel = " ")) }
        assertFailsWith<IllegalArgumentException> { packager.review(request.copy(appLabel = "x\ny")) }
        assertFailsWith<IllegalArgumentException> { packager.review(request.copy(versionCode = 0)) }
        assertFailsWith<IllegalArgumentException> { packager.review(request.copy(versionName = "\"\ninjected")) }
        for (name in listOf("bad\\name.js", "bad%2fname.js", "bad#hash.js", "bad?query.js", "bad:colon.js")) {
            val file = Files.writeString(request.workspaceRoot.resolve("dist/$name"), "bad")
            assertFailsWith<IllegalArgumentException>(name) { packager.review(request) }
            Files.delete(file)
        }
    }

    @Test fun `never overlaps source and never overwrites even an empty directory or dangling link`() {
        val request = fixture()
        val packager = StaticWebAppPackager()
        assertFailsWith<IllegalArgumentException> { packager.review(request.copy(outputParent = request.workspaceRoot)) }
        assertFailsWith<IllegalArgumentException> { packager.review(request.copy(outputParent = temp, projectName = "source")) }
        val review = packager.review(request)
        val target = Files.createDirectory(review.outputDirectory)
        assertFailsWith<IllegalArgumentException> { packager.create(review) }
        assertTrue(Files.isDirectory(target))
        assertTrue(Files.list(target).use { it.count() == 0L })
        Files.writeString(target.resolve("user.txt"), "keep")
        assertFailsWith<IllegalArgumentException> { packager.review(request) }
        assertEquals("keep", Files.readString(target.resolve("user.txt")))
        Files.delete(target.resolve("user.txt"))
        Files.delete(target)
        Files.createSymbolicLink(target, temp.resolve("missing"))
        assertFailsWith<IllegalArgumentException> { packager.review(request) }
        assertTrue(Files.isSymbolicLink(target))
    }

    @Test fun `source or root changes after review demand fresh approval and leave no output`() {
        val request = fixture()
        val packager = StaticWebAppPackager()
        val review = packager.review(request)
        Files.writeString(request.workspaceRoot.resolve("dist/main.js"), "changed bytes")
        assertFailsWith<IllegalArgumentException> { packager.create(review) }
        assertFalse(Files.exists(review.outputDirectory))
        val current = packager.review(request)
        Files.writeString(request.workspaceRoot.resolve("dist/new.js"), "added after approval")
        assertFailsWith<IllegalArgumentException> { packager.create(current) }
        val latest = packager.review(request)
        Files.move(request.workspaceRoot.resolve("dist"), request.workspaceRoot.resolve("old-dist"), StandardCopyOption.ATOMIC_MOVE)
        Files.createSymbolicLink(request.workspaceRoot.resolve("dist"), request.workspaceRoot.resolve("old-dist"))
        assertFailsWith<IllegalArgumentException> { packager.create(latest) }
        assertFalse(Files.exists(review.outputDirectory))
    }

    @Test fun `review rejects asset links swapped in after approval`() {
        val request = fixture()
        val packager = StaticWebAppPackager()
        val review = packager.review(request)
        val script = request.workspaceRoot.resolve("dist/main.js")
        Files.delete(script)
        Files.createSymbolicLink(script, Files.writeString(temp.resolve("secret.js"), "secret"))
        assertFailsWith<IllegalArgumentException> { packager.create(review) }
        assertFalse(Files.exists(review.outputDirectory))
    }

    private fun tree(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate { root.relativize(it).toString() to hash(Files.readAllBytes(it)) }
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
