@file:OptIn(ExperimentalForeignApi::class)

package dev.ide.ios

import dev.ide.platform.JsonReader
import dev.ide.ui.backend.BuildDiagnosticUi
import dev.ide.ui.backend.BuildLogLine
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.ConsoleChunk
import dev.ide.ui.backend.ConsoleChunkKind
import dev.ide.ui.backend.RunConsoleUi
import dev.ide.ui.backend.RunPhase
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.RunTaskOption
import dev.ide.ui.backend.UiLogLevel
import dev.ide.ui.backend.UiSeverity
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSValue
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

internal object IosRuntimeRunner {

    data class DiscoveredTask(
        val option: RunTaskOption,
        val runtime: RuntimeKind,
        val command: String?,
        val targetFile: String?,
    )

    enum class RuntimeKind {
        NODE,
        PYTHON,
        SWIFT,
        SHELL,
        WEB,
        KOTLIN,
    }

    /**
     * Inspects [root] directory to discover runnable scripts, tools, and targets across multiple runtimes.
     */
    fun discoverTasks(root: String): List<DiscoveredTask> {
        val tasks = mutableListOf<DiscoveredTask>()

        // 1. Node.js detection (package.json and JS files)
        val packageJsonPath = IosFiles.join(root, "package.json")
        if (IosFiles.exists(packageJsonPath)) {
            val content = IosFiles.readText(packageJsonPath)
            val json = JsonReader.obj(JsonReader.parseOrNull(content))
            val mainFile = JsonReader.str(json, "main")
            val resolvedMainPath = mainFile?.takeIf { it.isNotBlank() }
                ?.let { IosFiles.join(root, it.removePrefix("./")) }
                ?.takeIf { IosFiles.exists(it) }
            val scripts = JsonReader.obj(json?.get("scripts"))
            if (!scripts.isNullOrEmpty()) {
                for ((scriptName, scriptCmd) in scripts) {
                    val cmdStr = scriptCmd as? String ?: continue
                    val scriptTarget = resolveNodeScriptTarget(root, cmdStr, scriptName, resolvedMainPath)
                    tasks.add(
                        DiscoveredTask(
                            option = RunTaskOption("node:script:$scriptName", "npm run $scriptName", "Node.js (npm)"),
                            runtime = RuntimeKind.NODE,
                            command = "npm run $scriptName",
                            targetFile = scriptTarget,
                        ),
                    )
                }
            }
            if (resolvedMainPath != null && mainFile != null) {
                tasks.add(
                    DiscoveredTask(
                        option = RunTaskOption("node:main", "node $mainFile", "Node.js"),
                        runtime = RuntimeKind.NODE,
                        command = "node $mainFile",
                        targetFile = resolvedMainPath,
                    ),
                )
            }
            if (tasks.none { it.runtime == RuntimeKind.NODE }) {
                val fallbackJs = listOf("index.js", "main.js", "app.js", "server.js", "src/index.js")
                    .map { IosFiles.join(root, it) }
                    .firstOrNull { IosFiles.exists(it) }
                tasks.add(
                    DiscoveredTask(
                        option = RunTaskOption("node:workspace", "npm start (Node.js Workspace)", "Node.js"),
                        runtime = RuntimeKind.NODE,
                        command = "npm start",
                        targetFile = fallbackJs,
                    ),
                )
            }
        }

        // Common JS entry points
        val commonJs = listOf("index.js", "main.js", "app.js", "server.js", "index.mjs")
        for (js in commonJs) {
            val full = IosFiles.join(root, js)
            if (IosFiles.exists(full)) {
                val optId = "node:file:$js"
                if (tasks.none { it.option.id == optId }) {
                    tasks.add(
                        DiscoveredTask(
                            option = RunTaskOption(optId, "node $js", "Node.js"),
                            runtime = RuntimeKind.NODE,
                            command = "node $js",
                            targetFile = full,
                        ),
                    )
                }
            }
        }

        // 2. Python detection
        val files = IosFiles.list(root)
        val pyFiles = files.filter { it.endsWith(".py") }
        for (py in pyFiles) {
            tasks.add(
                DiscoveredTask(
                    option = RunTaskOption("python:file:$py", "python3 $py", "Python"),
                    runtime = RuntimeKind.PYTHON,
                    command = "python3 $py",
                    targetFile = IosFiles.join(root, py),
                ),
            )
        }
        val nestedPyCandidates = listOf("webui/Main.py", "app/main.py", "src/main.py")
        for (relPy in nestedPyCandidates) {
            val fullPy = IosFiles.join(root, relPy)
            if (IosFiles.exists(fullPy)) {
                tasks.add(
                    DiscoveredTask(
                        option = RunTaskOption("python:file:$relPy", "python3 $relPy", "Python"),
                        runtime = RuntimeKind.PYTHON,
                        command = "python3 $relPy",
                        targetFile = fullPy,
                    ),
                )
            }
        }
        if (tasks.none { it.runtime == RuntimeKind.PYTHON } &&
            (IosFiles.exists(IosFiles.join(root, "pyproject.toml")) ||
                IosFiles.exists(IosFiles.join(root, "requirements.txt")) ||
                IosFiles.exists(IosFiles.join(root, "setup.py")))) {
            tasks.add(
                DiscoveredTask(
                    option = RunTaskOption("python:run", "python3 main.py", "Python"),
                    runtime = RuntimeKind.PYTHON,
                    command = "python3 main.py",
                    targetFile = null,
                ),
            )
        }

        // 3. Swift / Xcode / SwiftUI detection
        val swiftFiles = files.filter { it.endsWith(".swift") }
        val hasPackageSwift = IosFiles.exists(IosFiles.join(root, "Package.swift"))
        val hasXcodeProj = files.any { it.endsWith(".xcodeproj") || it.endsWith(".xcworkspace") } ||
            files.filter { !it.startsWith(".") }.any { sub ->
                val fullSub = IosFiles.join(root, sub)
                IosFiles.isDirectory(fullSub) && IosFiles.list(fullSub).any {
                    it == "Package.swift" || it.endsWith(".xcodeproj") || it.endsWith(".xcworkspace") || it.endsWith(".swift")
                }
            }
        if (hasPackageSwift || hasXcodeProj || swiftFiles.isNotEmpty()) {
            tasks.add(
                DiscoveredTask(
                    option = RunTaskOption("swift:run", "swift run (Cupertino UI)", "Swift & iOS"),
                    runtime = RuntimeKind.SWIFT,
                    command = "swift run",
                    targetFile = null,
                ),
            )
            tasks.add(
                DiscoveredTask(
                    option = RunTaskOption("swift:preview", "SwiftUI Preview (Cupertino)", "Swift & iOS"),
                    runtime = RuntimeKind.SWIFT,
                    command = "swift preview",
                    targetFile = null,
                ),
            )
            tasks.add(
                DiscoveredTask(
                    option = RunTaskOption("swift:test", "swift test", "Swift & iOS"),
                    runtime = RuntimeKind.SWIFT,
                    command = "swift test",
                    targetFile = null,
                ),
            )
        }
        for (sw in swiftFiles) {
            tasks.add(
                DiscoveredTask(
                    option = RunTaskOption("swift:file:$sw", "swift $sw", "Swift"),
                    runtime = RuntimeKind.SWIFT,
                    command = "swift $sw",
                    targetFile = IosFiles.join(root, sw),
                ),
            )
        }

        // 4. Shell scripts
        val shFiles = files.filter { it.endsWith(".sh") }
        for (sh in shFiles) {
            tasks.add(
                DiscoveredTask(
                    option = RunTaskOption("shell:file:$sh", "./$sh", "Scripts"),
                    runtime = RuntimeKind.SHELL,
                    command = "sh ./$sh",
                    targetFile = IosFiles.join(root, sh),
                ),
            )
        }

        // 5. Static web
        if (IosFiles.exists(IosFiles.join(root, "index.html"))) {
            tasks.add(
                DiscoveredTask(
                    option = RunTaskOption("web:preview", "Preview index.html", "Web Preview"),
                    runtime = RuntimeKind.WEB,
                    command = null,
                    targetFile = IosFiles.join(root, "index.html"),
                ),
            )
        }

        // 6. Markdown / Docs / Prompts workspace fallback (when not a Gradle/Kotlin project)
        val isGradleOrKotlin = IosFiles.exists(IosFiles.join(root, "build.gradle.kts")) ||
            IosFiles.exists(IosFiles.join(root, "build.gradle")) ||
            IosFiles.exists(IosFiles.join(root, "settings.gradle.kts")) ||
            IosFiles.exists(IosFiles.join(root, "settings.gradle")) ||
            IosFiles.exists(IosFiles.join(root, ".platform/workspace.json")) ||
            IosFiles.exists(IosFiles.join(root, "app/src/main/AndroidManifest.xml"))
        if (tasks.isEmpty() && !isGradleOrKotlin && files.isNotEmpty()) {
            tasks.add(
                DiscoveredTask(
                    option = RunTaskOption("workspace:preview", "Run Workspace Preview", "Workspace"),
                    runtime = RuntimeKind.WEB,
                    command = "preview",
                    targetFile = files.firstOrNull { it.endsWith(".md", ignoreCase = true) }?.let { IosFiles.join(root, it) },
                ),
            )
        }

        return tasks
    }

    /**
     * Executes a command via Darwin POSIX popen, capturing stdout & stderr.
     */
    fun runProcess(
        command: String,
        cwd: String,
        onChunk: (ConsoleChunk) -> Unit,
    ): Int {
        val fullCmd = "cd \"$cwd\" && $command 2>&1"
        val fp = popen(fullCmd, "r") ?: run {
            onChunk(ConsoleChunk("Failed to execute process: $command\n", ConsoleChunkKind.OUTPUT))
            return -1
        }
        try {
            memScoped {
                val buffer = allocArray<ByteVar>(2048)
                while (fgets(buffer, 2048, fp) != null) {
                    val line = buffer.toKString()
                    onChunk(ConsoleChunk(line, ConsoleChunkKind.OUTPUT))
                }
            }
        } finally {
            val status = pclose(fp)
            return status
        }
    }

    private fun resolveNodeScriptTarget(
        root: String,
        cmdStr: String,
        scriptName: String,
        resolvedMainPath: String?,
    ): String? {
        val nodeMatch = Regex("""\bnode\s+(?:--?[A-Za-z0-9_-]+\s+)*["']?([^\s"';&|]+\.(?:c?m?js))["']?""").find(cmdStr)
        if (nodeMatch != null) {
            val rel = nodeMatch.groupValues[1].removePrefix("./")
            val full = IosFiles.join(root, rel)
            if (IosFiles.exists(full)) return full
        }
        if (scriptName == "start" || scriptName == "dev") {
            if (resolvedMainPath != null) return resolvedMainPath
            for (cand in listOf("index.js", "main.js", "app.js", "server.js", "src/index.js", "src/main.js")) {
                val full = IosFiles.join(root, cand)
                if (IosFiles.exists(full)) return full
            }
        }
        return null
    }

    private fun collectJsWorkspaceFiles(root: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        fun walk(dir: String, relPrefix: String, depth: Int) {
            if (depth > 5 || result.size >= 150) return
            for (entry in IosFiles.list(dir)) {
                if (entry == ".git" || entry == "build" || entry == ".gradle" || entry == ".platform") continue
                val full = IosFiles.join(dir, entry)
                val rel = if (relPrefix.isEmpty()) entry else "$relPrefix/$entry"
                if (IosFiles.isDirectory(full)) {
                    walk(full, rel, depth + 1)
                } else if (
                    entry.endsWith(".js") ||
                    entry.endsWith(".cjs") ||
                    entry.endsWith(".mjs") ||
                    entry.endsWith(".json")
                ) {
                    result[rel] = IosFiles.readText(full)
                }
            }
        }
        walk(root, "", 0)
        return result
    }

    private fun escapeJsString(s: String): String = buildString(s.length + 16) {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> append(ch)
            }
        }
    }

    /**
     * In-process JavaScript execution using platform JavaScriptCore.
     * Supports multi-file CommonJS (`require`, `module.exports`, `exports`), JSON imports,
     * and captures `console.log` / `console.info` / `console.warn` / `console.error`.
     */
    fun runJavaScriptInProcess(
        script: String,
        scriptName: String,
        cwd: String,
        onChunk: (ConsoleChunk) -> Unit,
    ): Boolean {
        val context = JSContext()
        val workspaceFiles = collectJsWorkspaceFiles(cwd)
        val vfsEntriesJs = workspaceFiles.entries.joinToString(",\n") { (rel, content) ->
            "'${escapeJsString(rel)}': '${escapeJsString(content)}'"
        }
        val escapedCwd = escapeJsString(cwd)
        val escapedScriptName = escapeJsString(scriptName)

        val jsBootstrap = """
            var _logs = [];
            function _formatArg(a) {
                if (a === null) return 'null';
                if (a === undefined) return 'undefined';
                if (typeof a === 'object') {
                    try { return JSON.stringify(a); } catch (e) { return String(a); }
                }
                return String(a);
            }
            var console = {
                log: function() {
                    var args = Array.prototype.slice.call(arguments).map(_formatArg);
                    _logs.push(args.join(' '));
                },
                info: function() {
                    var args = Array.prototype.slice.call(arguments).map(_formatArg);
                    _logs.push(args.join(' '));
                },
                warn: function() {
                    var args = Array.prototype.slice.call(arguments).map(_formatArg);
                    _logs.push('[WARN] ' + args.join(' '));
                },
                error: function() {
                    var args = Array.prototype.slice.call(arguments).map(_formatArg);
                    _logs.push('[ERROR] ' + args.join(' '));
                }
            };
            var process = {
                env: { NODE_ENV: 'development' },
                argv: ['node', '$escapedScriptName'],
                platform: 'darwin',
                version: 'v20.0.0-jsc',
                cwd: function() { return '$escapedCwd'; },
                stdout: { write: function(s) { _logs.push(String(s).replace(/\n$/, '')); } },
                stderr: { write: function(s) { _logs.push('[ERROR] ' + String(s).replace(/\n$/, '')); } }
            };
            var __vfs = {
                $vfsEntriesJs
            };
            var __moduleCache = {};
            function __normalizePath(parts) {
                var stack = [];
                for (var i = 0; i < parts.length; i++) {
                    var p = parts[i];
                    if (!p || p === '.') continue;
                    if (p === '..') { if (stack.length > 0) stack.pop(); }
                    else stack.push(p);
                }
                return stack.join('/');
            }
            function __dirnameOf(relPath) {
                var idx = relPath.lastIndexOf('/');
                return idx === -1 ? '' : relPath.substring(0, idx);
            }
            var __builtins = {
                path: {
                    join: function() {
                        var parts = Array.prototype.slice.call(arguments).join('/').split('/');
                        return __normalizePath(parts);
                    },
                    resolve: function() {
                        var parts = ('$escapedCwd/' + Array.prototype.slice.call(arguments).join('/')).split('/');
                        return '/' + __normalizePath(parts);
                    },
                    dirname: function(p) { return __dirnameOf(String(p)); },
                    basename: function(p) {
                        var s = String(p);
                        var i = s.lastIndexOf('/');
                        return i === -1 ? s : s.substring(i + 1);
                    },
                    extname: function(p) {
                        var b = this.basename(p);
                        var i = b.lastIndexOf('.');
                        return i <= 0 ? '' : b.substring(i);
                    }
                },
                os: {
                    platform: function() { return 'darwin'; },
                    arch: function() { return 'arm64'; },
                    EOL: '\n'
                },
                util: {
                    format: function() { return Array.prototype.slice.call(arguments).map(_formatArg).join(' '); }
                },
                assert: function(cond, msg) {
                    if (!cond) throw new Error(msg || 'AssertionError');
                }
            };
            function __resolveModule(request, fromDir) {
                var req = String(request).replace(/^node:/, '');
                if (__builtins[req]) return { builtin: req };
                var candidates = [];
                if (req.indexOf('./') === 0 || req.indexOf('../') === 0 || req.indexOf('/') === 0) {
                    var baseParts = (fromDir ? fromDir + '/' + req : req).split('/');
                    var norm = __normalizePath(baseParts);
                    candidates.push(norm, norm + '.js', norm + '.cjs', norm + '.mjs', norm + '.json', norm + '/index.js');
                } else {
                    var nmBase = 'node_modules/' + req;
                    var pkgJson = __vfs[nmBase + '/package.json'];
                    if (pkgJson) {
                        try {
                            var parsed = JSON.parse(pkgJson);
                            if (parsed.main) {
                                var m = __normalizePath((nmBase + '/' + parsed.main).split('/'));
                                candidates.push(m, m + '.js', m + '/index.js');
                            }
                        } catch (e) {}
                    }
                    candidates.push(nmBase + '/index.js', nmBase + '.js', req, req + '.js');
                }
                for (var i = 0; i < candidates.length; i++) {
                    var c = candidates[i];
                    if (Object.prototype.hasOwnProperty.call(__vfs, c)) {
                        return { path: c, source: __vfs[c] };
                    }
                }
                throw new Error("Cannot find module '" + request + "'");
            }
            function __makeRequire(fromDir) {
                return function require(id) {
                    var resolved = __resolveModule(id, fromDir);
                    if (resolved.builtin) return __builtins[resolved.builtin];
                    var modPath = resolved.path;
                    if (__moduleCache[modPath]) return __moduleCache[modPath].exports;
                    var mod = { id: modPath, filename: '$escapedCwd/' + modPath, exports: {} };
                    __moduleCache[modPath] = mod;
                    if (modPath.slice(-5) === '.json') {
                        mod.exports = JSON.parse(resolved.source);
                        return mod.exports;
                    }
                    var dir = __dirnameOf(modPath);
                    var fn = new Function('exports', 'require', 'module', '__filename', '__dirname', resolved.source);
                    fn(mod.exports, __makeRequire(dir), mod, '$escapedCwd/' + modPath, '$escapedCwd' + (dir ? '/' + dir : ''));
                    return mod.exports;
                };
            }
            var require = __makeRequire(__dirnameOf('$escapedScriptName'));
            var module = { exports: {} };
            var exports = module.exports;
            var __filename = '$escapedCwd/$escapedScriptName';
            var __dirname = '$escapedCwd';
        """.trimIndent()

        context.evaluateScript(jsBootstrap)
        val result: JSValue? = context.evaluateScript(script)

        val collectedLogs = context.evaluateScript("_logs.join('\\n')")?.toString()
        if (!collectedLogs.isNullOrBlank()) {
            onChunk(ConsoleChunk("$collectedLogs\n", ConsoleChunkKind.OUTPUT))
        }

        if (context.exception != null) {
            val errStr = context.exception?.toString() ?: "JavaScript execution error"
            onChunk(ConsoleChunk("Uncaught Exception: $errStr\n", ConsoleChunkKind.OUTPUT))
            return false
        }
        if (result != null && !result.isUndefined() && !result.isNull()) {
            val resStr = result.toString()
            if (resStr.isNotBlank() && resStr != "undefined") {
                onChunk(ConsoleChunk("Result: $resStr\n", ConsoleChunkKind.OUTPUT))
            }
        }
        return true
    }
}
