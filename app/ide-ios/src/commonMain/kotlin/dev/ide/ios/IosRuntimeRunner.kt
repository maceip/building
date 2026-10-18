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
            val scripts = JsonReader.obj(json?.get("scripts"))
            if (!scripts.isNullOrEmpty()) {
                for ((scriptName, scriptCmd) in scripts) {
                    val cmdStr = scriptCmd as? String ?: continue
                    tasks.add(
                        DiscoveredTask(
                            option = RunTaskOption("node:script:$scriptName", "npm run $scriptName", "Node.js (npm)"),
                            runtime = RuntimeKind.NODE,
                            command = "npm run $scriptName",
                            targetFile = null,
                        ),
                    )
                }
            }
            val mainFile = JsonReader.str(json, "main")
            if (!mainFile.isNullOrBlank() && IosFiles.exists(IosFiles.join(root, mainFile))) {
                tasks.add(
                    DiscoveredTask(
                        option = RunTaskOption("node:main", "node $mainFile", "Node.js"),
                        runtime = RuntimeKind.NODE,
                        command = "node $mainFile",
                        targetFile = IosFiles.join(root, mainFile),
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

        // 3. Swift detection
        if (IosFiles.exists(IosFiles.join(root, "Package.swift"))) {
            tasks.add(
                DiscoveredTask(
                    option = RunTaskOption("swift:run", "swift run", "Swift & iOS"),
                    runtime = RuntimeKind.SWIFT,
                    command = "swift run",
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
        val swiftFiles = files.filter { it.endsWith(".swift") }
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

    /**
     * In-process JavaScript execution using platform JavaScriptCore.
     * Captures console.log / console.error and feeds chunks into the console.
     */
    fun runJavaScriptInProcess(
        script: String,
        scriptName: String,
        cwd: String,
        onChunk: (ConsoleChunk) -> Unit,
    ): Boolean {
        val context = JSContext()
        val jsBootstrap = """
            var _logs = [];
            var console = {
                log: function() {
                    var args = Array.prototype.slice.call(arguments);
                    _logs.push(args.join(' '));
                },
                info: function() {
                    var args = Array.prototype.slice.call(arguments);
                    _logs.push(args.join(' '));
                },
                warn: function() {
                    var args = Array.prototype.slice.call(arguments);
                    _logs.push('[WARN] ' + args.join(' '));
                },
                error: function() {
                    var args = Array.prototype.slice.call(arguments);
                    _logs.push('[ERROR] ' + args.join(' '));
                }
            };
            var process = {
                env: {},
                argv: ['node', '$scriptName'],
                cwd: function() { return '$cwd'; }
            };
        """.trimIndent()

        context.evaluateScript(jsBootstrap)
        val result = context.evaluateScript(script)

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
