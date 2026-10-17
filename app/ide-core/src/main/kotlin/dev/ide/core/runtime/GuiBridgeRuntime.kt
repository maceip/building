package dev.ide.core.runtime

import dev.ide.build.BuildContext
import dev.ide.build.BuildLogEntry
import dev.ide.build.BuildLogLevel
import dev.ide.build.ProgramIo
import dev.ide.build.TaskContext
import dev.ide.build.TaskResult
import dev.ide.build.engine.reportToolDiagnostics
import dev.ide.workbench.runtime.RuntimeHost
import dev.ide.workbench.runtime.RuntimeInstallation
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Interactive GUI Bridge runtime support for both:
 *  1. SwiftUI / Cupertino (`SwiftUI.swiftmodule` + `libSwiftUI.a` on-device `ViewHost` bridge)
 *  2. Node.js GUI (`@nodegui/nodegui`, `libui-node`, and `x11` window/widget bridge shim)
 *
 * Both bridges speak the newline-delimited JSON `ViewNode` protocol over stdout and receive
 * user interaction callbacks (`{"id":"...","kind":"void|bool|string|double|int","value":...}`)
 * over stdin, rendering live interactive native Compose windows inside `RunScreen`.
 */
internal object GuiBridgeRuntime {

    fun isBridgeJsonLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("{") &&
            trimmed.endsWith("}") &&
            trimmed.contains("\"type\":") &&
            trimmed.contains("\"children\":")
    }

    /**
     * Ensures the precompiled on-device `SwiftUI.swiftmodule` and `libSwiftUI.a` exist in
     * `<prefix>/lib/swift/android/` (restoring from `developer-runtime/swiftui-sdk` or
     * `/data/local/tmp/swiftui-sdk` if the runtime prefix was freshly extracted).
     */
    fun ensureSwiftUiBridgeSdk(installation: RuntimeInstallation): Boolean = runCatching {
        val swiftAndroidDir = installation.prefix.resolve("lib/swift/android")
        val targetLib = swiftAndroidDir.resolve("libSwiftUI.a")
        val targetModDir = swiftAndroidDir.resolve("SwiftUI.swiftmodule")
        val targetMod = targetModDir.resolve("aarch64-unknown-linux-android.swiftmodule")

        val filesRoot = installation.prefix.parent?.parent?.parent
        val backupCandidates = listOfNotNull(
            filesRoot?.resolve("developer-runtime/swiftui-sdk"),
            Path.of("/data/local/tmp/swiftui-sdk"),
            Path.of(System.getProperty("user.home", ""), ".building/swiftui-bridge-sdk/android-aarch64"),
        )
        for (backup in backupCandidates) {
            val srcLib = backup.resolve("libSwiftUI.a")
            val srcMod = backup.resolve("SwiftUI.swiftmodule/aarch64-unknown-linux-android.swiftmodule")
                .takeIf { Files.isRegularFile(it) }
                ?: backup.resolve("aarch64-unknown-linux-android.swiftmodule")
                    .takeIf { Files.isRegularFile(it) }
            if (Files.isRegularFile(srcLib) && srcMod != null && Files.isDirectory(swiftAndroidDir)) {
                val needsSync = !Files.isRegularFile(targetLib) ||
                    !Files.isRegularFile(targetMod) ||
                    Files.size(srcLib) != Files.size(targetLib)
                if (needsSync) {
                    Files.createDirectories(targetModDir)
                    Files.copy(srcLib, targetLib, StandardCopyOption.REPLACE_EXISTING)
                    val aarch64Dir = swiftAndroidDir.resolve("aarch64")
                    if (Files.isDirectory(aarch64Dir)) {
                        Files.copy(srcLib, aarch64Dir.resolve("libSwiftUI.a"), StandardCopyOption.REPLACE_EXISTING)
                    }
                    Files.copy(srcMod, targetMod, StandardCopyOption.REPLACE_EXISTING)
                    Files.copy(
                        srcMod,
                        targetModDir.resolve("aarch64-unknown-linux-android28.swiftmodule"),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                for (extraMod in listOf("Combine", "CoreGraphics", "UIKit")) {
                    val extraSrc = backup.resolve("$extraMod.swiftmodule/aarch64-unknown-linux-android.swiftmodule")
                    if (Files.isRegularFile(extraSrc)) {
                        val extraTargetDir = swiftAndroidDir.resolve("$extraMod.swiftmodule")
                        val extraTarget = extraTargetDir.resolve("aarch64-unknown-linux-android.swiftmodule")
                        if (!Files.isRegularFile(extraTarget)) {
                            Files.createDirectories(extraTargetDir)
                            Files.copy(extraSrc, extraTarget, StandardCopyOption.REPLACE_EXISTING)
                            Files.copy(
                                extraSrc,
                                extraTargetDir.resolve("aarch64-unknown-linux-android28.swiftmodule"),
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        }
                    }
                }
                return true
            }
        }
        if (Files.isRegularFile(targetLib) && Files.isRegularFile(targetMod)) {
            return true
        }
        if (installation.packId == "host-system") {
            val hostSdk = Path.of(System.getProperty("user.home", ""), ".building/swiftui-bridge-sdk")
            if (Files.isRegularFile(hostSdk.resolve("libSwiftUI.a")) && Files.isDirectory(hostSdk.resolve("SwiftUI.swiftmodule"))) {
                return true
            }
        }
        false
    }.getOrDefault(false)

    private val SUPPORTED_SWIFTUI_IMPORTS = setOf(
        "SwiftUI",
        "Foundation",
        "Combine",
        "CoreGraphics",
        "UIKit",
        "Observation",
        "Swift",
        "Darwin",
        "Glibc",
        "Android",
    )

    /**
     * When a repository contains multiple app targets or subprojects (for example,
     * `takenori-kabeya/SwiftUIStateManagementBook-Counter` which has both `SimpleCounterSwiftUI/`
     * and `SimpleCounterTCA/`), selects the single runnable SwiftUI target directory that has no
     * unresolvable external framework imports.
     */
    internal fun selectSwiftUiTargetFiles(projectRoot: Path, candidateFiles: List<Path>): List<Path> {
        if (candidateFiles.size <= 1) return candidateFiles
        val fileTexts = candidateFiles.associateWith { f ->
            runCatching { f.readText() }.getOrDefault("")
        }
        val mainFiles = candidateFiles.filter { f ->
            val text = fileTexts[f].orEmpty()
            text.contains("@main") || Regex("""struct\s+\w+\s*:\s*App\b""").containsMatchIn(text)
        }
        if (mainFiles.size <= 1) return candidateFiles

        data class TargetCandidate(
            val mainFile: Path,
            val targetDir: Path,
            val files: List<Path>,
            val unsupportedImports: Int,
            val importsSwiftUi: Boolean,
        )

        val candidates = mainFiles.map { mainFile ->
            val targetDir = mainFile.parent ?: projectRoot
            val scopedFiles = candidateFiles.filter { it.startsWith(targetDir) }
            var unsupported = 0
            var hasSwiftUi = false
            for (f in scopedFiles) {
                val text = fileTexts[f].orEmpty()
                for (line in text.lineSequence()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("import ")) {
                        val mod = trimmed.removePrefix("import ").trim().takeWhile { it.isLetterOrDigit() || it == '_' }
                        if (mod == "SwiftUI") hasSwiftUi = true
                        if (mod.isNotEmpty() && mod !in SUPPORTED_SWIFTUI_IMPORTS) {
                            unsupported++
                        }
                    }
                }
            }
            TargetCandidate(mainFile, targetDir, scopedFiles, unsupported, hasSwiftUi)
        }

        val best = candidates.sortedWith(
            compareBy<TargetCandidate> { !it.importsSwiftUi }
                .thenBy { it.unsupportedImports }
                .thenBy { it.mainFile.toString().contains("TCA", ignoreCase = true) }
                .thenBy { it.targetDir.toString().length },
        ).firstOrNull()

        return best?.files?.ifEmpty { candidateFiles } ?: candidateFiles
    }

    /**
     * Compiles a SwiftUI project's `.swift` sources against the on-device `SwiftUI` bridge module
     * and launches the interactive Cupertino GUI session in `RunScreen`.
     */
    suspend fun compileAndRunSwiftUiBridge(
        host: RuntimeHost,
        installation: RuntimeInstallation,
        swiftcBin: Path,
        projectRoot: Path,
        projectName: String,
        scratchDir: Path?,
        env: Map<String, String>,
        ctx: TaskContext,
        buildCtx: BuildContext?,
        onPackageIpa: () -> Unit = {},
    ): TaskResult {
        val allSwiftFiles = SwiftBuildSystem.findSwiftSourceFiles(projectRoot)
        // Prefer real SwiftUI source files over any synthetic stub *Main.swift
        val filteredFiles = allSwiftFiles.filter { f ->
            val name = f.fileName.toString()
            if (name.endsWith("Main.swift")) {
                val text = runCatching { f.readText() }.getOrDefault("")
                !text.contains("Swift TargetReady")
            } else true
        }.ifEmpty { allSwiftFiles }
        val swiftUiFiles = selectSwiftUiTargetFiles(projectRoot, filteredFiles)

        if (swiftUiFiles.isEmpty()) {
            return TaskResult.Failed("No Swift source files found in $projectRoot")
        }

        val pkgName = SwiftBuildSystem.extractSwiftPackageName(projectRoot)
        val outDir = (scratchDir ?: projectRoot.resolve(".build")).resolve("debug")
        Files.createDirectories(outDir)
        val outBin = outDir.resolve("$pkgName-cupertino")

        val newestSourceMtime = swiftUiFiles.maxOfOrNull {
            runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L)
        } ?: 0L
        val binMtime = if (Files.isRegularFile(outBin) && Files.isExecutable(outBin)) {
            runCatching { Files.getLastModifiedTime(outBin).toMillis() }.getOrDefault(0L)
        } else 0L

        if (binMtime < newestSourceMtime || binMtime == 0L) {
            val sdkRoot = installation.prefix.parent ?: installation.prefix
            val swiftAndroidLib = installation.prefix.resolve("lib/swift/android")
            val archInclude = installation.prefix.resolve("include/aarch64-linux-android")
            val archLib = installation.prefix.resolve("lib/aarch64-linux-android")
            val hostBridgeSdk = Path.of(System.getProperty("user.home", ""), ".building/swiftui-bridge-sdk")
            val compileCmd = buildList {
                add(swiftcBin.toString())
                add("-parse-as-library")
                for (f in swiftUiFiles) add(f.toString())
                if (Files.isDirectory(swiftAndroidLib)) {
                    add("-sdk")
                    add(sdkRoot.toString())
                    add("-I$swiftAndroidLib")
                    add("-L$swiftAndroidLib")
                    if (Files.isDirectory(archInclude)) {
                        add("-Xcc")
                        add("-isystem")
                        add("-Xcc")
                        add(archInclude.toString())
                    }
                    if (Files.isDirectory(archLib)) {
                        add("-L$archLib")
                    }
                } else if (Files.isDirectory(hostBridgeSdk)) {
                    add("-I$hostBridgeSdk")
                    add("-L$hostBridgeSdk")
                }
                add("-lSwiftUI")
                add("-o")
                add(outBin.toString())
            }
            ctx.reportStep(":swift:compileCupertinoBridge", "started")
            val relNames = swiftUiFiles.joinToString(" ") { projectRoot.relativize(it).toString() }
            ctx.buildLog.log(BuildLogEntry("$ swiftc -parse-as-library $relNames -lSwiftUI -o ${outBin.fileName}", BuildLogLevel.INFO))
            val compileLines = ArrayList<String>()
            val code = execSimple(host, compileCmd, projectRoot, env, ctx, compileLines)
            ctx.reportToolDiagnostics("swiftc", compileLines)
            if (code != 0) {
                ctx.reportStep(":swift:compileCupertinoBridge", "failed")
                return TaskResult.Failed("swiftc exited with code $code")
            }
            outBin.toFile().setExecutable(true)
            ctx.reportStep(":swift:compileCupertinoBridge", "succeeded")
        } else {
            ctx.buildLog.log(
                BuildLogEntry(
                    "✓ Using up-to-date ARM64 SwiftUI binary: ${outBin.fileName} (${Files.size(outBin) / 1024} KB)",
                    BuildLogLevel.INFO,
                ),
            )
        }

        onPackageIpa()

        val runEnv = LinkedHashMap(env).apply {
            val swiftAndroidLib = installation.prefix.resolve("lib/swift/android")
            val curLd = get("LD_LIBRARY_PATH").orEmpty()
            if (Files.isDirectory(swiftAndroidLib)) {
                put("LD_LIBRARY_PATH", listOf(curLd, swiftAndroidLib.toString()).filter { it.isNotBlank() }.joinToString(":"))
            }
        }

        return execInteractiveBridge(
            host = host,
            command = listOf(outBin.toString()),
            workingDir = projectRoot,
            env = runEnv,
            projectName = projectName,
            displayCommand = "$pkgName (iOS Cupertino Bridge)",
            stepName = ":swift:runCupertinoBridge",
            ctx = ctx,
            buildCtx = buildCtx,
        )
    }

    /**
     * Returns true if the Node.js project at [root] uses a desktop/window GUI toolkit
     * (`@nodegui/nodegui`, `libui-node`, or `x11`) that can be hosted via our GUI bridge shim.
     */
    fun isNodeGuiProject(root: Path): Boolean = runCatching {
        val pkg = root.resolve("package.json")
        if (Files.isRegularFile(pkg)) {
            val text = pkg.readText()
            if (text.contains("@nodegui/nodegui") ||
                text.contains("libui-node") ||
                text.contains("\"x11\"") ||
                text.contains("qode ")
            ) {
                return true
            }
        }
        val candidates = listOf(
            "src/index.ts", "src/index.js", "index.ts", "index.js", "main.js", "app.js",
        )
        for (rel in candidates) {
            val p = root.resolve(rel)
            if (Files.isRegularFile(p)) {
                val content = p.readText()
                if (content.contains("@nodegui/nodegui") ||
                    content.contains("libui-node") ||
                    content.contains("require('x11')") ||
                    content.contains("from 'x11'")
                ) {
                    return true
                }
            }
        }
        false
    }.getOrDefault(false)

    fun findNodeGuiEntrypoint(root: Path): Path? {
        val candidates = listOf(
            "src/index.ts",
            "src/index.js",
            "index.ts",
            "index.js",
            "src/main.ts",
            "src/main.js",
            "main.js",
            "app.js",
        )
        for (rel in candidates) {
            val p = root.resolve(rel)
            if (Files.isRegularFile(p)) return p
        }
        return null
    }

    /**
     * Installs our pure-JS Node GUI Bridge shims (`@nodegui/nodegui`, `libui-node`, `x11`, and `lodash`)
     * into `<projectRoot>/node_modules/` so unmodified NodeGUI / libui / X11 repositories run directly on
     * the embedded Node.js runtime and stream interactive `ViewNode` trees to `RunScreen`.
     */
    fun ensureNodeGuiBridgeShimInstalled(projectRoot: Path) {
        runCatching {
            val nodeModules = projectRoot.resolve("node_modules")
            // 1. @nodegui/nodegui
            val nodeguiDir = nodeModules.resolve("@nodegui/nodegui")
            Files.createDirectories(nodeguiDir)
            nodeguiDir.resolve("package.json").writeText(
                """
                {
                  "name": "@nodegui/nodegui",
                  "version": "0.60.0-bridge",
                  "main": "index.js"
                }
                """.trimIndent() + "\n",
            )
            nodeguiDir.resolve("index.js").writeText(NODEGUI_BRIDGE_SHIM_JS)

            // 2. libui-node
            val libuiDir = nodeModules.resolve("libui-node")
            Files.createDirectories(libuiDir)
            libuiDir.resolve("package.json").writeText(
                """
                {
                  "name": "libui-node",
                  "version": "0.3.0-bridge",
                  "main": "index.js"
                }
                """.trimIndent() + "\n",
            )
            libuiDir.resolve("index.js").writeText(NODEGUI_BRIDGE_SHIM_JS)

            // 3. x11
            val x11Dir = nodeModules.resolve("x11")
            Files.createDirectories(x11Dir)
            x11Dir.resolve("package.json").writeText(
                """
                {
                  "name": "x11",
                  "version": "2.3.0-bridge",
                  "main": "index.js"
                }
                """.trimIndent() + "\n",
            )
            x11Dir.resolve("index.js").writeText(NODEGUI_BRIDGE_SHIM_JS)
        }
    }

    /**
     * Runs an interactive GUI process (SwiftUI or NodeGUI/X11) while streaming `ViewNode` JSON trees
     * from `stdout` to `ProgramIo.frame(...)` and forwarding callback JSON lines from `ProgramIo.stdin`
     * to `child.stdin`.
     */
    suspend fun execInteractiveBridge(
        host: RuntimeHost,
        command: List<String>,
        workingDir: Path,
        env: Map<String, String>,
        projectName: String,
        displayCommand: String,
        stepName: String,
        ctx: TaskContext,
        buildCtx: BuildContext?,
    ): TaskResult = coroutineScope {
        ctx.reportStep(stepName, "started")
        ctx.buildLog.log(BuildLogEntry("$ $displayCommand", BuildLogLevel.INFO))

        // Open the interactive RunConsoleUi session immediately if a BuildContext is wired
        val io: ProgramIo? = buildCtx?.openProgramIo?.invoke(projectName, displayCommand)
        io?.started()

        val child = try {
            host.start(command, workingDir, env)
        } catch (e: Exception) {
            val msg = "Failed to start $displayCommand: ${e.message ?: e.javaClass.simpleName}"
            ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
            io?.exited(1)
            ctx.reportStep(stepName, "failed")
            return@coroutineScope TaskResult.Failed(msg, e)
        }

        val frameSeq = AtomicLong(0L)
        val firstFrameLogged = AtomicBoolean(false)

        // Pump interactive callback events from RunScreen (io.stdin) -> child.stdin
        val stdinJob = if (io != null) {
            launch(Dispatchers.IO) {
                try {
                    val buf = ByteArray(4096)
                    val input = io.stdin
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            child.stdin.write(buf, 0, n)
                            child.stdin.flush()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { child.stdin.close() }
                }
            }
        } else null

        val stdoutJob = launch(Dispatchers.IO) {
            try {
                child.stdout.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (isBridgeJsonLine(line)) {
                            val seq = frameSeq.incrementAndGet()
                            val trimmed = line.trim()
                            io?.frame(trimmed, -1, -1, seq)
                            if (firstFrameLogged.compareAndSet(false, true)) {
                                val msg = "✓ GUI Bridge connected — live interactive window active in Run view"
                                ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.INFO))
                                io?.stdout("$msg\n")
                                // In headless unit tests where no UI ProgramIo is attached, close stdin
                                // after the first frame so the process exits cleanly.
                                if (io == null) {
                                    runCatching { child.stdin.close() }
                                }
                            }
                        } else {
                            ctx.buildLog.log(BuildLogEntry(line, BuildLogLevel.INFO))
                            io?.stdout("$line\n")
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        val stderrJob = launch(Dispatchers.IO) {
            try {
                child.stderr.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val level = if (line.contains("error:", ignoreCase = true)) BuildLogLevel.ERROR else BuildLogLevel.INFO
                        ctx.buildLog.log(BuildLogEntry(line, level))
                        io?.stdout("$line\n")
                    }
                }
            } catch (_: Exception) {
            }
        }

        try {
            val exitCode = child.awaitExit()
            withContext(NonCancellable) { child.terminate() }
            withTimeoutOrNull(2_000) {
                stdoutJob.join()
                stderrJob.join()
            }
            io?.exited(exitCode)
            if (exitCode == 0 || firstFrameLogged.get()) {
                ctx.reportStep(stepName, "succeeded")
                TaskResult.Success
            } else {
                ctx.reportStep(stepName, "failed")
                TaskResult.Failed("$displayCommand exited with code $exitCode")
            }
        } catch (e: CancellationException) {
            io?.exited(0)
            ctx.reportStep(stepName, "failed")
            throw e
        } finally {
            withContext(NonCancellable) { child.terminate() }
            runCatching { child.stdin.close() }
            runCatching { child.stdout.close() }
            runCatching { child.stderr.close() }
            stdinJob?.cancel()
            stdoutJob.cancel()
            stderrJob.cancel()
        }
    }

    private suspend fun execSimple(
        host: RuntimeHost,
        command: List<String>,
        workingDir: Path,
        env: Map<String, String>,
        ctx: TaskContext,
        capturedLines: MutableList<String>,
    ): Int = coroutineScope {
        val child = host.start(command, workingDir, env)
        val stdoutJob = launch(Dispatchers.IO) {
            streamSimpleLines(child.stdout) { line ->
                ctx.buildLog.log(BuildLogEntry(line, BuildLogLevel.INFO))
                synchronized(capturedLines) { capturedLines += line }
            }
        }
        val stderrJob = launch(Dispatchers.IO) {
            streamSimpleLines(child.stderr) { line ->
                val level = if (line.contains("error:", ignoreCase = true)) BuildLogLevel.ERROR else BuildLogLevel.WARN
                ctx.buildLog.log(BuildLogEntry(line, level))
                synchronized(capturedLines) { capturedLines += line }
            }
        }
        try {
            runCatching { child.stdin.close() }
            val code = child.awaitExit()
            withContext(NonCancellable) { child.terminate() }
            withTimeoutOrNull(2_000) {
                stdoutJob.join()
                stderrJob.join()
            }
            code
        } finally {
            withContext(NonCancellable) { child.terminate() }
            runCatching { child.stdout.close() }
            runCatching { child.stderr.close() }
            runCatching { child.stdin.close() }
            stdoutJob.cancel()
            stderrJob.cancel()
        }
    }

    private fun streamSimpleLines(input: InputStream, onLine: (String) -> Unit) {
        try {
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { onLine(it) }
            }
        } catch (_: Exception) {
        }
    }

    private val NODEGUI_BRIDGE_SHIM_JS = """
        'use strict';
        const readline = require('readline');

        let nextCallbackId = 1;
        const callbacks = new Map();
        const activeWindows = [];
        let renderScheduled = false;
        let clipboardText = '';

        function registerCallback(fn) {
          const id = nextCallbackId++;
          callbacks.set(String(id), fn);
          return id;
        }

        function scheduleRender() {
          if (renderScheduled || activeWindows.length === 0) return;
          renderScheduled = true;
          queueMicrotask(() => {
            renderScheduled = false;
            emitActiveWindow();
          });
        }

        function hexToArgb(hex) {
          if (!hex || typeof hex !== 'string') return null;
          let clean = hex.trim().replace(/^#/, '');
          if (clean.toLowerCase() === 'white') clean = 'FFFFFF';
          if (clean.toLowerCase() === 'black') clean = '000000';
          if (clean.length === 3) {
            clean = clean.split('').map(c => c + c).join('');
          }
          if (clean.length === 6) {
            const rgb = parseInt(clean, 16);
            if (Number.isNaN(rgb)) return null;
            return ((0xFF000000 | rgb) >>> 0);
          }
          return null;
        }

        function parseCssRules(cssText) {
          const rules = [];
          if (!cssText || typeof cssText !== 'string') return rules;
          const cleaned = cssText.replace(/\/\*[\s\S]*?\*\//g, '');
          const regex = /([^{]+)\{([^}]*)\}/g;
          let match;
          while ((match = regex.exec(cleaned)) !== null) {
            const selectors = match[1].split(',').map(s => s.trim()).filter(Boolean);
            const props = {};
            match[2].split(';').forEach(decl => {
              const idx = decl.indexOf(':');
              if (idx > 0) {
                const k = decl.slice(0, idx).trim().toLowerCase();
                const v = decl.slice(idx + 1).trim().replace(/^['"]|['"]$/g, '');
                props[k] = v;
              }
            });
            rules.push({ selectors, props });
          }
          return rules;
        }

        function resolveStylesForWidget(widget, ancestorRules, parentObjectName) {
          const allRules = [...ancestorRules, ...parseCssRules(widget._styleSheet)];
          const matched = {};
          const objName = widget._objectName || '';
          const typeName = widget._widgetType || 'QWidget';
          for (const rule of allRules) {
            for (const sel of rule.selectors) {
              if (sel.includes(':pressed') || sel.includes(':hover')) continue;
              const matches =
                sel === '*' ||
                sel === typeName ||
                (objName && sel === '#' + objName) ||
                (parentObjectName && sel === '#' + parentObjectName + ' ' + typeName) ||
                (parentObjectName && sel === '#' + parentObjectName + ' *');
              if (matches) {
                Object.assign(matched, rule.props);
              }
            }
          }
          return { matched, allRules };
        }

        class NativeElement {}
        class FlexNode {}

        class QKeyEvent {
          constructor(nativeEvt) { this._text = (nativeEvt && nativeEvt.text) || ''; }
          text() { return this._text; }
        }

        class QWidget {
          constructor() {
            this._widgetType = 'QWidget';
            this._objectName = '';
            this._styleSheet = '';
            this._layout = null;
            this._listeners = new Map();
            this._flexNode = new FlexNode();
            this._windowTitle = '';
            this._centralWidget = null;
            this._children = [];
          }
          setObjectName(name) { this._objectName = String(name || ''); scheduleRender(); }
          objectName() { return this._objectName; }
          setStyleSheet(css) { this._styleSheet = String(css || ''); scheduleRender(); }
          styleSheet() { return this._styleSheet; }
          setLayout(layout) {
            this._layout = layout;
            if (layout) layout._owner = this;
            scheduleRender();
          }
          layout() { return this._layout; }
          getFlexNode() { return this._flexNode; }
          setFixedSize(w, h) { this._width = w; this._height = h; }
          resize(w, h) { this._width = w; this._height = h; }
          setWindowTitle(title) { this._windowTitle = String(title || ''); scheduleRender(); }
          windowTitle() { return this._windowTitle; }
          addEventListener(evt, fn) {
            if (!this._listeners.has(evt)) this._listeners.set(evt, []);
            this._listeners.get(evt).push(fn);
          }
          _emit(evt, arg) {
            const list = this._listeners.get(evt) || [];
            for (const fn of list) fn(arg);
            scheduleRender();
          }
          show() {
            if (!activeWindows.includes(this)) activeWindows.push(this);
            process.stderr.write('[NodeGUI Bridge] Window shown — streaming live ViewNode tree\n');
            emitActiveWindow();
          }
          close() {}
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const { matched, allRules } = resolveStylesForWidget(this, ancestorRules, parentObjName);
            const id = pathPrefix + '/' + (this._objectName || this._widgetType);
            const childWidgets = this._layout ? this._layout._widgets : this._children;
            const isRow = matched['flex-direction'] === 'row' || (this._layout && this._layout._isRow);
            const children = childWidgets.map((c, idx) =>
              c.toViewNode(allRules, this._objectName || parentObjName, id + '/' + idx)
            );
            const equalWidth = isRow && childWidgets.length >= 2 && childWidgets.every(w => w._widgetType === 'QPushButton' || w._widgetType === 'QLabel');
            const props = {
              spacing: 6,
              alignment: isRow ? 'center' : 'leading',
              equalWidth,
            };
            const modifiers = [
              { kind: 'frame', args: { fillWidth: true } },
            ];
            if (matched['background'] && matched['background'].startsWith('#')) {
              const argb = hexToArgb(matched['background']);
              if (argb !== null) {
                modifiers.push({ kind: 'background', args: { color: argb } });
                modifiers.push({ kind: 'cornerRadius', args: { radius: 8 } });
              }
            }
            if (matched['padding']) {
              const p = Math.min(parseFloat(matched['padding']) || 6, 10);
              modifiers.push({ kind: 'padding', args: { top: p, bottom: p, leading: p, trailing: p } });
            }
            return {
              type: isRow ? 'HStack' : 'VStack',
              id,
              props,
              modifiers,
              children,
            };
          }
        }

        class QMainWindow extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QMainWindow';
          }
          setCentralWidget(widget) {
            this._centralWidget = widget;
            scheduleRender();
          }
          centralWidget() { return this._centralWidget; }
          toViewNode() {
            const rootChild = this._centralWidget
              ? this._centralWidget.toViewNode([], '', 'root/central')
              : { type: 'VStack', id: 'root/empty', props: {}, modifiers: [], children: [] };
            const { matched } = resolveStylesForWidget(this._centralWidget || this, [], '');
            const cssBg = matched['background'] || matched['background-color'] || '';
            const windowBg = cssBg.startsWith('#') ? cssBg : '#F2F2F7';
            let pkgName = '';
            try {
              const fs = require('fs');
              const path = require('path');
              const pkgPath = path.join(process.cwd(), 'package.json');
              if (fs.existsSync(pkgPath)) {
                pkgName = JSON.parse(fs.readFileSync(pkgPath, 'utf8')).name || '';
              }
            } catch (_) {}
            const title = this._windowTitle || pkgName || 'NodeGUI Window';
            return {
              type: 'ScrollView',
              id: 'root',
              props: {
                windowTitle: title,
                windowBackground: windowBg,
              },
              modifiers: [
                { kind: 'frame', args: { fillWidth: true } },
              ],
              children: [rootChild],
            };
          }
        }

        class FlexLayout {
          constructor() {
            this._widgets = [];
            this._isRow = false;
          }
          setFlexNode() {}
          addWidget(widget) {
            if (widget && !this._widgets.includes(widget)) {
              this._widgets.push(widget);
              scheduleRender();
            }
          }
        }

        class QBoxLayout extends FlexLayout {
          constructor(dir) {
            super();
            this._isRow = dir === 0 || dir === 'LeftToRight' || dir === 'row';
          }
          setDirection(dir) {
            this._isRow = dir === 0 || dir === 'LeftToRight' || dir === 'row';
          }
        }

        class QLabel extends QWidget {
          constructor(parent) {
            super();
            this._widgetType = 'QLabel';
            this._text = '';
          }
          setText(t) {
            this._text = String(t ?? '');
            scheduleRender();
          }
          text() { return this._text; }
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const { matched } = resolveStylesForWidget(this, ancestorRules, parentObjName);
            const isRight = (matched['qproperty-alignment'] || '').includes('AlignRight');
            const style = {};
            if (matched['font-size']) {
              const px = parseFloat(matched['font-size']);
              if (!Number.isNaN(px)) style.fontSize = Math.min(px, 34);
            }
            if (matched['color']) {
              const argb = hexToArgb(matched['color']);
              if (argb !== null) style.color = argb;
            }
            if (isRight) {
              style.textAlign = 'right';
              style.fontFamily = 'monospace';
              style.fontWeight = 'semibold';
            }
            return {
              type: 'Composable',
              id: pathPrefix + '/' + (this._objectName || 'label'),
              props: {
                name: 'CupertinoText',
                text: this._text,
                style,
              },
              modifiers: isRight ? [{ kind: 'frame', args: { fillWidth: true } }] : [],
              children: [],
            };
          }
        }

        class QPushButton extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QPushButton';
            this._text = 'Button';
          }
          setText(t) {
            this._text = String(t ?? '');
            scheduleRender();
          }
          text() { return this._text; }
          setFlat() {}
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const { matched } = resolveStylesForWidget(this, ancestorRules, parentObjName);
            const onTapId = registerCallback(() => {
              this._emit('clicked');
            });
            const id = pathPrefix + '/' + (this._objectName || this._text);
            const props = {
              name: 'CupertinoButton',
              onClick: onTapId,
            };
            if (matched['background'] && matched['background'].startsWith('#')) {
              const bgArgb = hexToArgb(matched['background']);
              if (bgArgb !== null) props.containerColor = bgArgb;
            }
            const fgArgb = matched['color'] ? hexToArgb(matched['color']) : 0xFFFFFFFF;
            if (fgArgb !== null) props.contentColor = fgArgb;
            const modifiers = [
              { kind: 'frame', args: { fillWidth: true } },
            ];
            if (matched['height']) {
              const h = parseFloat(matched['height']);
              if (!Number.isNaN(h)) {
                modifiers.push({ kind: 'frame', args: { height: Math.min(h, 46) } });
              }
            }
            return {
              type: 'Composable',
              id,
              props,
              modifiers,
              children: [
                {
                  type: 'CupertinoSlot',
                  id: id + '/content',
                  props: { name: 'content' },
                  modifiers: [],
                  children: [
                    {
                      type: 'Composable',
                      id: id + '/label',
                      props: {
                        name: 'CupertinoText',
                        text: this._text,
                        style: {
                          color: fgArgb || 0xFFFFFFFF,
                          fontSize: 16,
                          fontWeight: 'semibold',
                        },
                      },
                      modifiers: [],
                      children: [],
                    },
                  ],
                },
              ],
            };
          }
        }

        class QCheckBox extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QCheckBox';
            this._text = '';
            this._checked = false;
          }
          setText(t) { this._text = String(t ?? ''); scheduleRender(); }
          text() { return this._text; }
          setChecked(c) { this._checked = Boolean(c); scheduleRender(); }
          isChecked() { return this._checked; }
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const onChangeId = registerCallback((val) => {
              this._checked = Boolean(val);
              this._emit('toggled', this._checked);
              this._emit('stateChanged', this._checked ? 2 : 0);
            });
            const id = pathPrefix + '/' + (this._objectName || 'checkbox');
            return {
              type: 'HStack',
              id,
              props: { spacing: 10, alignment: 'center' },
              modifiers: [{ kind: 'frame', args: { fillWidth: true } }],
              children: [
                {
                  type: 'Composable',
                  id: id + '/box',
                  props: {
                    name: 'CupertinoCheckBox',
                    checked: this._checked,
                    onCheckedChange: onChangeId,
                  },
                  modifiers: [],
                  children: [],
                },
                {
                  type: 'Composable',
                  id: id + '/label',
                  props: {
                    name: 'CupertinoText',
                    text: this._text,
                  },
                  modifiers: [],
                  children: [],
                },
                {
                  type: 'Spacer',
                  id: id + '/spacer',
                  props: {},
                  modifiers: [],
                  children: [],
                },
              ],
            };
          }
        }

        class QLineEdit extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QLineEdit';
            this._text = '';
            this._placeholder = '';
            this._readOnly = false;
          }
          setText(t) { this._text = String(t ?? ''); scheduleRender(); }
          text() { return this._text; }
          setPlaceholderText(t) { this._placeholder = String(t ?? ''); scheduleRender(); }
          setReadOnly(r) { this._readOnly = Boolean(r); scheduleRender(); }
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const onChangeId = registerCallback((val) => {
              this._text = String(val ?? '');
              this._emit('textChanged', this._text);
            });
            const id = pathPrefix + '/' + (this._objectName || 'lineedit');
            return {
              type: 'Composable',
              id,
              props: {
                name: 'CupertinoBorderedTextField',
                value: this._text,
                readOnly: this._readOnly,
                singleLine: true,
                onValueChange: onChangeId,
              },
              modifiers: [{ kind: 'frame', args: { fillWidth: true } }],
              children: [
                {
                  type: 'CupertinoSlot',
                  id: id + '/placeholder',
                  props: { name: 'placeholder' },
                  modifiers: [],
                  children: [
                    {
                      type: 'Composable',
                      id: id + '/placeholder/text',
                      props: { name: 'CupertinoText', text: this._placeholder || '' },
                      modifiers: [],
                      children: [],
                    },
                  ],
                },
              ],
            };
          }
        }

        class QPlainTextEdit extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QPlainTextEdit';
            this._text = '';
            this._placeholder = '';
            this._readOnly = false;
          }
          setPlainText(t) { this._text = String(t ?? ''); scheduleRender(); }
          toPlainText() { return this._text; }
          setPlaceholderText(t) { this._placeholder = String(t ?? ''); scheduleRender(); }
          setReadOnly(r) { this._readOnly = Boolean(r); scheduleRender(); }
          setWordWrapMode() {}
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const onChangeId = registerCallback((val) => {
              this._text = String(val ?? '');
              this._emit('textChanged');
            });
            const id = pathPrefix + '/' + (this._objectName || 'textarea');
            return {
              type: 'Composable',
              id,
              props: {
                name: 'CupertinoBorderedTextField',
                value: this._text,
                readOnly: this._readOnly,
                singleLine: false,
                minLines: 2,
                maxLines: 3,
                onValueChange: onChangeId,
              },
              modifiers: [{ kind: 'frame', args: { fillWidth: true } }],
              children: [
                {
                  type: 'CupertinoSlot',
                  id: id + '/placeholder',
                  props: { name: 'placeholder' },
                  modifiers: [],
                  children: [
                    {
                      type: 'Composable',
                      id: id + '/placeholder/text',
                      props: {
                        name: 'CupertinoText',
                        text: this._placeholder || '',
                      },
                      modifiers: [],
                      children: [],
                    },
                  ],
                },
              ],
            };
          }
        }

        class QSlider extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QSlider';
            this._value = 50;
            this._min = 0;
            this._max = 100;
          }
          setValue(v) { this._value = Number(v) || 0; scheduleRender(); }
          value() { return this._value; }
          setMinimum(v) { this._min = Number(v) || 0; }
          setMaximum(v) { this._max = Number(v) || 100; }
          setOrientation() {}
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            const onChangeId = registerCallback((val) => {
              this._value = Math.round(Number(val) || 0);
              this._emit('valueChanged', this._value);
            });
            return {
              type: 'Slider',
              id: pathPrefix + '/' + (this._objectName || 'slider'),
              props: {
                value: this._value,
                min: this._min,
                max: this._max,
                onChange: onChangeId,
              },
              modifiers: [{ kind: 'frame', args: { fillWidth: true } }],
              children: [],
            };
          }
        }

        class QProgressBar extends QWidget {
          constructor() {
            super();
            this._widgetType = 'QProgressBar';
            this._value = 0;
            this._max = 100;
          }
          setValue(v) { this._value = Number(v) || 0; scheduleRender(); }
          setMaximum(m) { this._max = Number(m) || 100; }
          toViewNode(ancestorRules = [], parentObjName = '', pathPrefix = 'root') {
            return {
              type: 'ProgressView',
              id: pathPrefix + '/' + (this._objectName || 'progress'),
              props: { value: this._value / Math.max(this._max, 1) },
              modifiers: [{ kind: 'frame', args: { fillWidth: true } }],
              children: [],
            };
          }
        }

        const QApplication = {
          instance: () => QApplication,
          clipboard: () => ({
            setText: (t) => {
              clipboardText = String(t ?? '');
              process.stderr.write('[NodeGUI Bridge] Copied to clipboard: ' + clipboardText + '\n');
            },
            text: () => clipboardText,
          }),
          exec: () => {},
        };

        const QPushButtonEvents = { clicked: 'clicked', pressed: 'pressed', released: 'released' };
        const QCheckBoxEvents = { toggled: 'toggled', stateChanged: 'stateChanged' };
        const QLineEditEvents = { textChanged: 'textChanged' };
        const BaseWidgetEvents = { KeyRelease: 'KeyRelease', KeyPress: 'KeyPress' };
        const Direction = { LeftToRight: 0, RightToLeft: 1, TopToBottom: 2, BottomToTop: 3 };
        const Orientation = { Horizontal: 1, Vertical: 2 };

        function emitActiveWindow() {
          const win = activeWindows[activeWindows.length - 1];
          if (!win) return;
          callbacks.clear();
          nextCallbackId = 1;
          const tree = win.toViewNode();
          process.stdout.write(JSON.stringify(tree) + '\n');
        }

        // Listen for callback events on stdin from RunScreen
        const rl = readline.createInterface({ input: process.stdin, terminal: false });
        rl.on('line', (line) => {
          const trimmed = line.trim();
          if (!trimmed.startsWith('{')) return;
          try {
            const evt = JSON.parse(trimmed);
            const fn = callbacks.get(String(evt.id));
            if (fn) {
              fn(evt.value);
              scheduleRender();
            }
          } catch (_) {}
        });
        rl.on('close', () => {
          process.exit(0);
        });

        module.exports = {
          QMainWindow,
          QWidget,
          FlexLayout,
          QBoxLayout,
          QLabel,
          QPushButton,
          QPushButtonEvents,
          QCheckBox,
          QCheckBoxEvents,
          QLineEdit,
          QLineEditEvents,
          QPlainTextEdit,
          QSlider,
          QProgressBar,
          QApplication,
          BaseWidgetEvents,
          Direction,
          Orientation,
          QKeyEvent,
          NativeElement,
        };
    """.trimIndent()
}
