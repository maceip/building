package dev.ide.workbench.webapp

import dev.ide.model.impl.ModelPersistence
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeWebAppProjectTest {
    @TempDir lateinit var temp: Path

    @Test fun `generated Java compiles against actual bundled Android platform with Java 8`() {
        val result = generate(temp)
        val java = result.projectRoot.resolve("app/src/main/java/dev/fixture/offline")
        val androidJar = repository().resolve("app/ide-android/src/main/sdk/android.jar")
        assertTrue(Files.isRegularFile(androidJar), "Use the IDE's real bundled Android API, not permissive test stubs")
        val classes = Files.createDirectory(temp.resolve("classes"))
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler())
        val errors = ByteArrayOutputStream()
        val exit = compiler.run(null, errors, errors,
            "-source", "8", "-target", "8", "-Xlint:-options", "-classpath", androidJar.toString(),
            "-d", classes.toString(), java.resolve("BundlePath.java").toString(), java.resolve("MainActivity.java").toString(),
        )
        assertEquals(0, exit, errors.toString())
        assertTrue(Files.isRegularFile(classes.resolve("dev/fixture/offline/MainActivity.class")))
        val source = Files.readString(java.resolve("MainActivity.java"))
        for (defense in listOf(
            "setAllowFileAccess(false)", "setAllowContentAccess(false)",
            "setAllowFileAccessFromFileURLs(false)", "setAllowUniversalAccessFromFileURLs(false)",
            "setBlockNetworkLoads(true)", "setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW)",
            "setWebContentsDebuggingEnabled(false)", "handler.cancel()", "request.hasGesture()", "confirmExternal(url)",
            "Content-Security-Policy", "Referrer-Policy", "ServiceWorkerClient", "assets.contains(path)",
        )) assertTrue(defense in source, "Missing wrapper defense: $defense")
        assertFalse(source.contains("addJavascriptInterface"))
        assertFalse(source.contains("loadDataWithBaseURL"))
        assertFalse(source.contains("file://"))
        assertFalse(source.contains("handler.proceed"))
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(result.projectRoot.resolve("app/src/main/AndroidManifest.xml").toFile())
        assertEquals(0, xml.getElementsByTagName("uses-permission").length)
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(result.projectRoot.resolve("app/src/main/res/values/strings.xml").toFile())
        assertTrue(strings.getElementsByTagName("string").item(0).textContent.contains("Offline"))
    }

    @Test fun `execute the exact generated URI policy and reject origin path and scheme attacks`() {
        val source = Files.writeString(temp.resolve("BundlePath.java"), NativeWebAppProject.pathPolicy("fixture"))
        val classes = Files.createDirectory(temp.resolve("policy-classes"))
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "--release", "8", "-d", classes.toString(), source.toString()))
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            val policy = loader.loadClass("fixture.BundlePath")
            fun path(url: String) = policy.getMethod("assetPath", String::class.java).invoke(null, url) as String?
            fun own(url: String) = policy.getMethod("ownOrigin", String::class.java).invoke(null, url) as Boolean
            fun external(url: String) = policy.getMethod("externalHttps", String::class.java).invoke(null, url) as Boolean
            fun spa(path: String) = policy.getMethod("spaDocument", String::class.java).invoke(null, path) as Boolean
            val origin = StaticWebAppPackager.ORIGIN
            assertEquals("index.html", path("$origin/"))
            assertEquals("index.html", path("$origin/index.html?v=17#app"))
            assertEquals("assets/main.js", path("$origin/assets/main.js"))
            assertEquals("hello world.txt", path("$origin/hello%20world.txt"))
            assertEquals("a+b.txt", path("$origin/a+b.txt"))
            assertEquals("docs/", path("$origin/docs/"))
            assertTrue(own("$origin:443/index.html"))
            for (attack in listOf(
                "$origin/../secret", "$origin/%2e%2e/secret", "$origin/%252e%252e/secret",
                "$origin/assets/%2f..%2fprivate", "$origin/assets/%5c..%5cprivate", "$origin/.env",
                "$origin/a//b.js", "$origin/a%00b", "$origin/a%3ab", "$origin/a%23b", "$origin/a%3fb",
                "$origin:444/index.html", "https://user@appassets.androidplatform.net/index.html",
                "https://appassets.androidplatform.net.evil.test/index.html", "https://evil.test/index.html",
                "http://appassets.androidplatform.net/index.html", "file:///android_asset/web/index.html",
                "content://secrets/1", "javascript:alert(1)", "data:text/html,<script>alert(1)</script>", "intent://external",
            )) assertNull(path(attack), "Accepted $attack")
            assertTrue(external("https://example.com/a?q=1"))
            assertFalse(external("$origin/index.html"))
            assertFalse(external("http://example.com"))
            assertFalse(external("https://user:pass@example.com"))
            assertFalse(external("https://example.com:8443"))
            assertTrue(spa("todos/123"))
            assertTrue(spa("todos/123/"))
            assertFalse(spa("assets/missing.js"))
            assertFalse(spa("api/session"))
            assertFalse(spa("_next/image"))
            assertFalse(spa("static/missing"))
        }
    }

    @Test fun `emit native fixture for coordinated device acceptance without building an APK here`() {
        val fixtureParent = Files.createDirectories(repository().resolve("services/developer-workbench/build/webapp-device-fixture"))
        // Each run gets its own directory. Never clobber an old acceptance fixture or a developer project.
        val run = Files.createTempDirectory(fixtureParent, "run-")
        val result = generate(run)
        assertEquals("native", ModelPersistence.load(result.projectRoot.toString()).projects.single().buildSystemId)
        println("WEBAPP_DEVICE_FIXTURE=${result.projectRoot}")
        assertTrue(Files.isRegularFile(result.assetManifest))
        assertFalse(Files.exists(result.debugApk)) // Native Android build is deliberately left to the device host.
    }

    private fun generate(root: Path): WebAppPackageResult {
        val workspace = Files.createDirectories(root.resolve("source"))
        val dist = Files.createDirectory(workspace.resolve("dist"))
        Files.writeString(dist.resolve("index.html"), """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Offline Android fixture</title><link rel="stylesheet" href="style.css"></head>
            <body><h1>Offline Android fixture</h1><p id="origin"></p><button id="counter">Clicks: 0</button>
            <a href="/details/42">SPA route</a><a href="https://example.com">External HTTPS (confirm)</a>
            <p id="status">Loading local JavaScript…</p><script src="main.js"></script></body></html>
        """.trimIndent())
        Files.writeString(dist.resolve("style.css"), "body{font:18px system-ui;margin:2rem}button,a{display:block;margin:1rem 0;padding:1rem}")
        Files.writeString(dist.resolve("main.js"), """
            document.querySelector('#origin').textContent=location.origin;
            document.querySelector('#status').textContent='Local JavaScript ready';
            let n=0;document.querySelector('#counter').onclick=e=>e.target.textContent='Clicks: '+(++n);
            if('serviceWorker' in navigator)navigator.serviceWorker.register('/sw.js').catch(()=>{});
        """.trimIndent())
        Files.writeString(dist.resolve("sw.js"), "self.addEventListener('install',()=>self.skipWaiting());self.addEventListener('activate',e=>e.waitUntil(clients.claim()));")
        Files.writeString(dist.resolve(".env"), "SECRET_DO_NOT_PACKAGE=true")
        val packager = StaticWebAppPackager()
        return packager.create(packager.review(WebAppPackageRequest(
            workspace, "dist", Files.createDirectory(root.resolve("projects")), "OfflineFixture",
            "dev.fixture.offline", "Offline <&> O'Brien \"fixture\"", versionCode = 17, versionName = "2.1-beta",
            spaFallback = true, allowExternalHttps = true,
        )))
    }

    private fun repository(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
}
