package dev.ide.workbench.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuntimeWebUiAndCallbackTest {

    @TempDir
    lateinit var temp: Path

    private val children = CopyOnWriteArrayList<Process>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun tearDown() {
        children.forEach { runCatching { it.destroyForcibly() } }
    }

    private fun realHost(workingCache: Path = temp.resolve("cache")): RuntimeHost = object : RuntimeHost {
        override val workspaceCache: Path = workingCache

        override suspend fun prepare(): RuntimeInstallation {
            val nodePath = Path.of("/usr/local/bin/node").takeIf { Files.isExecutable(it) }
                ?: Path.of("/opt/homebrew/bin/node").takeIf { Files.isExecutable(it) }
                ?: Path.of("/usr/bin/node").takeIf { Files.isExecutable(it) }
                ?: findExecutable("node")

            val pythonPath = Path.of("/usr/local/bin/python3").takeIf { Files.isExecutable(it) }
                ?: Path.of("/opt/homebrew/bin/python3").takeIf { Files.isExecutable(it) }
                ?: Path.of("/usr/bin/python3").takeIf { Files.isExecutable(it) }
                ?: findExecutable("python3")

            val shPath = Path.of("/bin/sh")

            return RuntimeInstallation(
                packId = "real-mac-host",
                abi = "arm64",
                prefix = temp,
                node = nodePath,
                npmCli = null,
                python = pythonPath,
                shell = shPath,
                environment = mapOf("PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin")),
                xtool = null,
            )
        }

        override fun start(command: List<String>, workingDirectory: Path, environment: Map<String, String>): RuntimeChild {
            val pb = ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .apply {
                    environment().clear()
                    environment().putAll(System.getenv())
                    environment().putAll(environment)
                }
            val process = pb.start()
            children.add(process)
            return object : RuntimeChild {
                override val stdin: OutputStream get() = process.outputStream
                override val stdout: InputStream get() = process.inputStream
                override val stderr: InputStream get() = process.errorStream
                override suspend fun awaitExit(): Int = withTimeout(30_000) {
                    while (process.isAlive) {
                        kotlinx.coroutines.delay(50)
                    }
                    process.exitValue()
                }
                override suspend fun terminate() {
                    process.destroy()
                    kotlinx.coroutines.delay(100)
                    if (process.isAlive) process.destroyForcibly()
                }
            }
        }

        private fun findExecutable(name: String): Path? {
            val pathDirs = (System.getenv("PATH") ?: "").split(':')
            for (dir in pathDirs) {
                val candidate = Path.of(dir).resolve(name)
                if (Files.isExecutable(candidate)) return candidate
            }
            return null
        }
    }

    @Test
    fun nodeWebAppSpawnsUiOnLoopbackAndReceivesBrowserCallback() = runBlocking<Unit> {
        val host = realHost()
        val installation = host.prepare()
        val nodeBin = installation.node
        org.junit.jupiter.api.Assumptions.assumeTrue(nodeBin != null && Files.isExecutable(nodeBin), "Node.js executable is required")

        val projectRoot = temp.resolve("node-app")
        Files.createDirectories(projectRoot)

        // Write a Node.js web server that serves an HTML UI and handles OAuth redirect callbacks
        val serverScript = """
            const http = require('http');
            const fs = require('fs');
            const url = require('url');

            const server = http.createServer((req, res) => {
                const parsed = url.parse(req.url, true);
                if (parsed.pathname === '/callback') {
                    const code = parsed.query.code || 'missing';
                    fs.writeFileSync('callback-received.txt', 'code=' + code);
                    res.writeHead(200, {'Content-Type': 'text/html'});
                    res.end('<!doctype html><h1>Authentication Successful</h1>');
                    return;
                }
                res.writeHead(200, {'Content-Type': 'text/html; charset=utf-8'});
                res.end('<!doctype html><html><head><title>Node Web App</title></head><body><h1>Node Web UI</h1><a href="/login">Login</a></body></html>');
            });

            server.listen(0, '127.0.0.1', () => {
                const port = server.address().port;
                console.log('Preview: http://127.0.0.1:' + port + '/');
            });

            process.on('SIGTERM', () => server.close(() => process.exit(0)));
        """.trimIndent()
        RuntimeFiles.writeUtf8(projectRoot.resolve("server.cjs"), serverScript)

        val controller = RuntimeController(host, scope)
        try {
            // Run server target
            controller.run(RuntimeRunRequest(projectRoot, "node:server.cjs"))

            // Wait for previewUrl to be detected from stdout
            val runningState = withTimeout(10_000) {
                controller.state.first { it.previewUrl != null }
            }
            val previewUrl = assertNotNull(runningState.previewUrl)
            assertTrue(previewUrl.startsWith("http://127.0.0.1:"))

            // 1) Verify that the embedded preview can connect to the loopback UI
            val conn = URL(previewUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            assertEquals(200, conn.responseCode)
            val html = conn.inputStream.reader().readText()
            assertTrue(html.contains("<title>Node Web App</title>"))
            assertTrue(html.contains("Node Web UI"))
            conn.disconnect()

            // 2) Simulate the browser popup redirecting back with OAuth code
            val callbackUrl = URL(previewUrl + "callback?code=oauth_auth_token_xyz987")
            val callbackConn = callbackUrl.openConnection() as HttpURLConnection
            callbackConn.connectTimeout = 3_000
            callbackConn.readTimeout = 3_000
            assertEquals(200, callbackConn.responseCode)
            val callbackHtml = callbackConn.inputStream.reader().readText()
            assertTrue(callbackHtml.contains("Authentication Successful"))
            callbackConn.disconnect()

            // 3) Verify the local server received and processed the callback
            val receivedPath = projectRoot.resolve("callback-received.txt")
            assertTrue(Files.isRegularFile(receivedPath))
            assertEquals("code=oauth_auth_token_xyz987", Files.readString(receivedPath))

            controller.stop()
        } finally {
            controller.close()
        }
    }

    @Test
    fun pythonWebSampleSpawnsUiOnLoopbackAndReceivesBrowserCallback() = runBlocking<Unit> {
        val host = realHost()
        val installation = host.prepare()
        val pythonBin = installation.python
        org.junit.jupiter.api.Assumptions.assumeTrue(pythonBin != null && Files.isExecutable(pythonBin), "Python 3 executable is required")

        val projectRoot = temp.resolve("py-app")
        Files.createDirectories(projectRoot)

        // Seed the built-in python-web sample into projectRoot
        val seededDir = RuntimeSamples.seed(projectRoot, "python-web")
        val mainPy = seededDir.resolve("main.py")
        assertTrue(Files.isRegularFile(mainPy))

        val controller = RuntimeController(host, scope)
        try {
            controller.run(RuntimeRunRequest(seededDir, "python:main.py"))

            val runningState = withTimeout(10_000) {
                controller.state.first { it.previewUrl != null }
            }
            val previewUrl = assertNotNull(runningState.previewUrl)
            assertTrue(previewUrl.startsWith("http://127.0.0.1:"))

            // 1) Verify that the Python Web UI serves interactive HTML
            val conn = URL(previewUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            assertEquals(200, conn.responseCode)
            val html = conn.inputStream.reader().readText()
            assertTrue(html.contains("<title>Python Web App</title>"))
            assertTrue(html.contains("Python Web Server"))
            conn.disconnect()

            // 2) Simulate OAuth redirect callback to /callback
            val callbackUrl = URL(previewUrl + "callback?code=mock_python_success&state=secure_state_123")
            val callbackConn = callbackUrl.openConnection() as HttpURLConnection
            callbackConn.connectTimeout = 3_000
            callbackConn.readTimeout = 3_000
            assertEquals(200, callbackConn.responseCode)
            val callbackBody = callbackConn.inputStream.reader().readText()
            assertTrue(callbackBody.contains("OAuth Callback Received!"))
            assertTrue(callbackBody.contains("mock_python_success"))
            callbackConn.disconnect()

            controller.stop()
        } finally {
            controller.close()
        }
    }
}
