package dev.ide.workbench.runtime

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal val runtimeJson = Json { prettyPrint = true; ignoreUnknownKeys = true }
internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.strings(key: String): List<String> = (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
internal fun readObject(path: Path, maxBytes: Long = 2 * 1024 * 1024): JsonObject {
    require(Files.size(path) <= maxBytes) { "File is too large: ${path.fileName}" }
    return runtimeJson.parseToJsonElement(RuntimeFiles.readUtf8(path)).jsonObject
}

internal fun safeProjectPath(root: Path, relative: String, mustExist: Boolean = true): Path {
    require(relative.isNotBlank() && !relative.contains('\u0000') && !relative.contains('\\')) { "Invalid project path" }
    val base = root.toRealPath()
    val requested = java.nio.file.Paths.get(relative)
    require(!requested.isAbsolute) { "Project paths must be relative" }
    val target = base.resolve(requested).normalize()
    require(target.startsWith(base)) { "Path escapes the project" }
    if (mustExist) require(target.toRealPath().startsWith(base)) { "Symlink escapes the project" }
    else {
        var parent = target.parent
        while (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) parent = parent.parent
        require(parent != null && parent.toRealPath().startsWith(base)) { "Parent escapes the project" }
    }
    return target
}

internal object RuntimeProjects {
    private const val CONFIG = ".building/runtime.json"
    private val reservedEnvironment = setOf("PATH", "HOME", "PREFIX", "LD_PRELOAD", "LD_LIBRARY_PATH", "JAVA_TOOL_OPTIONS", "PYTHONHOME", "PYTHONPATH", "NODE_OPTIONS", "NODE_PATH", "SHELL", "TMPDIR")

    fun targets(root: Path): List<RuntimeTarget> {
        require(Files.isDirectory(root)) { "Choose a project directory" }
        val result = mutableListOf<RuntimeTarget>()
        val packageFile = root.resolve("package.json")
        if (Files.isRegularFile(packageFile)) {
            val manifest = readObject(safeProjectPath(root, "package.json"))
            (manifest["scripts"] as? JsonObject)?.forEach { (name, command) ->
                if (name.isNotBlank() && !name.startsWith('-') && command is JsonPrimitive && command.isString) {
                    result += RuntimeTarget("npm:$name", "npm run $name", RuntimeKind.NPM, "package.json", name)
                }
            }
        }
        val swiftPackage = root.resolve("Package.swift")
        if (Files.isRegularFile(swiftPackage)) {
            result += RuntimeTarget("xtool:build", "xtool build", RuntimeKind.XTOOL, "Package.swift", "dev build --ipa")
            result += RuntimeTarget("xtool:test", "xtool test", RuntimeKind.XTOOL, "Package.swift", "test")
        }
        Files.walk(root, 3).use { files ->
            files.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { p -> p.toString().let { !it.contains("/node_modules/") && !it.contains("/.git/") && !it.contains("/.venv/") && !it.contains("/.building/") && !it.contains("/.build/") } }
                .limit(500).sorted().forEach { p ->
                    val relative = root.relativize(p).toString().replace('\\', '/')
                    val kind = when (p.fileName.toString().substringAfterLast('.', "").lowercase()) {
                        "js", "mjs", "cjs" -> RuntimeKind.NODE
                        "py" -> RuntimeKind.PYTHON
                        "sh" -> RuntimeKind.SHELL
                        "html", "htm" -> RuntimeKind.STATIC
                        "swift" -> RuntimeKind.XTOOL
                        else -> null
                    }
                    if (kind != null) {
                        val prefix = when (kind) {
                            RuntimeKind.NODE -> "node"
                            RuntimeKind.PYTHON -> "python"
                            RuntimeKind.SHELL -> "shell"
                            RuntimeKind.XTOOL -> "xtool"
                            else -> "static"
                        }
                        result += RuntimeTarget("$prefix:$relative", "${if (kind == RuntimeKind.STATIC) "Preview" else prefix} $relative", kind, relative)
                    }
                }
        }
        return result
    }

    fun load(root: Path): RuntimeProjectConfig {
        val path = safeProjectPath(root, CONFIG, false)
        if (!Files.exists(path)) return RuntimeProjectConfig()
        val value = readObject(path, 64 * 1024)
        return validate(RuntimeProjectConfig(
            targetId = value.string("targetId"),
            args = value.strings("args"),
            environment = (value["environment"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
            workingDirectory = value.string("workingDirectory") ?: ".",
            workspaceMode = value.string("workspaceMode")?.let { RuntimeWorkspaceMode.valueOf(it) } ?: RuntimeWorkspaceMode.AUTO,
            previewPort = (value["previewPort"] as? JsonPrimitive)?.intOrNull,
        ))
    }

    fun save(root: Path, config: RuntimeProjectConfig) {
        validate(config)
        safeProjectPath(root, config.workingDirectory)
        val path = safeProjectPath(root, CONFIG, false)
        Files.createDirectories(path.parent)
        val value = buildJsonObject {
            config.targetId?.let { put("targetId", it) }
            put("args", JsonArray(config.args.map(::JsonPrimitive)))
            put("environment", JsonObject(config.environment.mapValues { JsonPrimitive(it.value) }))
            put("workingDirectory", config.workingDirectory)
            put("workspaceMode", config.workspaceMode.name)
            config.previewPort?.let { put("previewPort", it) }
        }
        val temp = Files.createTempFile(path.parent, ".runtime-", ".json")
        try { RuntimeFiles.writeUtf8(temp, runtimeJson.encodeToString(JsonObject.serializer(), value)); Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING) }
        finally { Files.deleteIfExists(temp) }
    }

    private fun validate(config: RuntimeProjectConfig): RuntimeProjectConfig {
        require(config.args.size <= 128 && config.args.all { it.length <= 8192 && !it.contains('\u0000') }) { "Invalid runtime arguments" }
        require(config.environment.size <= 64 && config.environment.all { (key, value) -> key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && key !in reservedEnvironment && !key.startsWith("TERMUX") && !key.lowercase().startsWith("npm_config_") && !key.startsWith("PIP_") && !key.startsWith("LD_") && value.length <= 8192 && !value.contains('\u0000') }) { "Runtime environment contains invalid or reserved variables" }
        require(config.previewPort == null || config.previewPort in 1024..65535) { "Preview port must be 1024–65535" }
        require(!java.nio.file.Paths.get(config.workingDirectory).isAbsolute && config.workingDirectory.isNotBlank()) { "Working directory must be project-relative" }
        return config
    }
}

/** Samples are new child directories. Existing project files are never replaced. */
object RuntimeSamples {
    fun list(): List<RuntimeSample> = listOf(
        RuntimeSample("node-http", "Node HTTP server", "Offline Node HTTP, npm start, file writes and a browser preview; no dependencies."),
        RuntimeSample("python", "Python console", "Offline input, JSON, sqlite3 and a persisted output file."),
        RuntimeSample("python-web", "Python web server", "Offline Python HTTP server with interactive web UI and OAuth/callback support."),
        RuntimeSample("static-web", "Static web", "Offline HTML/CSS/JavaScript preview without a runtime download."),
        RuntimeSample("shell", "POSIX shell", "dash variables, a loop, pipes and an output file. Not Bash."),
    )

    fun seed(projectRoot: Path, sampleId: String): Path {
        require(list().any { it.id == sampleId }) { "Unknown sample" }
        require(Files.isDirectory(projectRoot))
        val destination = Files.createTempDirectory(projectRoot, "sample-$sampleId-")
        val files = when (sampleId) {
            "node-http" -> mapOf(
                "package.json" to """{"name":"building-offline-server","version":"1.0.0","private":true,"scripts":{"start":"node server.cjs","test":"node -e \"console.log('Node test passed')\""}}""",
                "server.cjs" to """
                    const http = require('node:http');
                    const fs = require('node:fs');
                    const server = http.createServer((request, response) => {
                      response.setHeader('Content-Type', 'text/html; charset=utf-8');
                      response.end('<!doctype html><title>Building Node preview</title><h1>Node is running offline</h1><p>Edit server.cjs and rerun.</p>');
                    });
                    server.listen(Number(process.env.PORT || 0), '127.0.0.1', () => {
                      fs.writeFileSync('last-run.json', JSON.stringify({runtime: process.version}));
                      console.log('Preview: http://127.0.0.1:' + server.address().port + '/');
                    });
                    process.on('SIGTERM', () => server.close(() => process.exit(0)));
                """.trimIndent(),
            )
            "python" -> mapOf("main.py" to """
                import json, sqlite3, ssl, sys
                print('Python', sys.version.split()[0], flush=True)
                print('Name (or send EOF): ', end='', flush=True)
                name = sys.stdin.readline().strip() or 'developer'
                connection = sqlite3.connect('sample.sqlite')
                connection.execute('create table if not exists greetings (name text)')
                connection.execute('insert into greetings values (?)', (name,))
                connection.commit()
                with open('result.json', 'w') as output:
                    json.dump({'greeting': 'Hello ' + name, 'rows': connection.execute('select count(*) from greetings').fetchone()[0]}, output)
                print('Hello', name, '— result.json and sample.sqlite saved', flush=True)
                connection.close()
            """.trimIndent())
            "python-web" -> mapOf("main.py" to """
                import http.server, socketserver, os, sys, urllib.parse

                PORT = int(os.environ.get('PORT', '8080'))

                HTML = '''<!doctype html>
                <html>
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Python Web App</title>
                  <style>
                    body { font-family: system-ui, sans-serif; margin: 2rem; background: #0f172a; color: #f8fafc; }
                    .card { background: #1e293b; padding: 1.5rem; border-radius: 12px; max-width: 480px; box-shadow: 0 4px 6px -1px rgb(0 0 0 / 0.1); }
                    button, a.btn { display: inline-block; background: #3b82f6; color: white; padding: 0.75rem 1.25rem; border-radius: 8px; text-decoration: none; border: none; font-size: 1rem; cursor: pointer; margin-top: 1rem; }
                    button:hover, a.btn:hover { background: #2563eb; }
                    pre { background: #020617; padding: 1rem; border-radius: 8px; overflow-x: auto; color: #38bdf8; font-size: 0.875rem; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>Python Web Server</h1>
                    <p>Running on Python <b>''' + sys.version.split()[0] + '''</b> on Android.</p>
                    <p id="status">Interactive loopback UI is active.</p>
                    <a class="btn" href="https://example.com" target="_blank">Test External Link</a>
                    <button id="authBtn" onclick="testOAuth()">Test OAuth Popup & Callback</button>
                    <div id="result" style="margin-top:1rem;"></div>
                  </div>
                  <script>
                    function testOAuth() {
                      const popup = window.open('https://example.com/login?redirect=codeassist://callback?code=mock_oauth_success', '_blank');
                      document.getElementById('status').innerText = 'Browser popup triggered...';
                    }
                  </script>
                </body>
                </html>'''

                class Handler(http.server.SimpleHTTPRequestHandler):
                    def do_GET(self):
                        parsed = urllib.parse.urlparse(self.path)
                        if parsed.path == '/callback':
                            self.send_response(200)
                            self.send_header('Content-Type', 'text/html; charset=utf-8')
                            self.end_headers()
                            self.wfile.write(b'<!doctype html><h1>OAuth Callback Received!</h1><p>Query: ' + parsed.query.encode() + b'</p>')
                            return
                        self.send_response(200)
                        self.send_header('Content-Type', 'text/html; charset=utf-8')
                        self.end_headers()
                        self.wfile.write(HTML.encode('utf-8'))

                server = socketserver.TCPServer(('127.0.0.1', PORT), Handler)
                print(f'Preview: http://127.0.0.1:{server.server_address[1]}/', flush=True)
                try:
                    server.serve_forever()
                except KeyboardInterrupt:
                    server.server_close()
            """.trimIndent())
            "static-web" -> mapOf(
                "index.html" to """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Offline web preview</title><link rel="stylesheet" href="style.css"></head><body><h1>Offline web preview</h1><p>HTML, CSS and JavaScript served from your project.</p><button id="counter">Clicks: 0</button><script src="main.js"></script></body></html>""",
                "style.css" to "body{font:18px system-ui;margin:3rem;background:#fafafa;color:#202124}button{padding:1rem;border-radius:12px;border:1px solid #555}",
                "main.js" to "let count=0;document.querySelector('#counter').onclick=event=>event.target.textContent='Clicks: '+(++count);",
            )
            else -> mapOf("main.sh" to "name=developer\nfor n in 1 2 3; do printf 'Hello %s %s\\n' \"\$name\" \"\$n\"; done | tee result.txt\n")
        }
        files.forEach { (name, content) -> RuntimeFiles.writeUtf8(destination.resolve(name), content + "\n") }
        return destination
    }
}

internal fun hashFile(path: Path): String = Files.newInputStream(path).use { stream ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(32768)
    while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    digest.digest().joinToString("") { "%02x".format(it) }
}
