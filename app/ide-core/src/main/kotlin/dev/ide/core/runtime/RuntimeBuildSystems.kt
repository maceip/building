package dev.ide.core.runtime

import dev.ide.build.BuildContext
import dev.ide.build.BuildGoal
import dev.ide.build.BuildLogEntry
import dev.ide.build.BuildLogLevel
import dev.ide.build.BuildRequest
import dev.ide.build.BuildSystem
import dev.ide.build.RunAction
import dev.ide.build.RunTaskSpec
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskDescriptor
import dev.ide.build.TaskGraph
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.build.engine.DefaultTaskContainer
import dev.ide.build.engine.reportToolDiagnostics
import dev.ide.core.node.NodeModuleType
import dev.ide.core.python.PythonModuleType
import dev.ide.core.swift.SwiftModuleType
import dev.ide.model.BuildSystemId
import dev.ide.model.ModuleType
import dev.ide.model.Project
import dev.ide.workbench.runtime.RuntimeChild
import dev.ide.workbench.runtime.RuntimeHost
import dev.ide.workbench.runtime.RuntimeInstallation
import dev.ide.workbench.runtime.RuntimeKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Host environment for first-class multi-runtime build systems (Swift & iOS, Python, Node.js).
 *
 * On Android (`AndroidIde.createProjectManager`), [host] is wired to `AndroidRuntimeHost(context)` in both
 * the main IDE process and the `:build` daemon process. On Desktop (`Main.kt` or unit tests), it falls back
 * to [DesktopRuntimeHost] when no host is explicitly registered.
 */
object RuntimeBuildEnvironment {
    @Volatile
    var host: RuntimeHost? = null

    /** Optional hook invoked after a build produces or updates files on disk so the UI refreshes its file tree. */
    @Volatile
    var onArtifactsProduced: (() -> Unit)? = null

    /**
     * Spike B: xtool finished a real IPA. The iOS host unpacks it into a guest
     * container and launches it (LiveContainer-style). Research only.
     */
    @Volatile
    var onGuestIpaReady: ((Path) -> Unit)? = null

    fun resolveHost(projectRoot: Path): RuntimeHost = host ?: DesktopRuntimeHost(projectRoot)
}

/**
 * Desktop / fallback [RuntimeHost] that discovers `swift`, `xtool`, `python3`, `node`, `npm`, and `sh` from
 * `PATH` or standard toolchain locations so the same `SwiftBuildSystem`, `PythonBuildSystem`, and
 * `NodeBuildSystem` work seamlessly on Desktop and in tests as well as on Android.
 */
class DesktopRuntimeHost(
    projectRoot: Path = Paths.get(System.getProperty("java.io.tmpdir")),
) : RuntimeHost {
    override val workspaceCache: Path = projectRoot.resolve(".platform/runtime-workspaces")

    override suspend fun prepare(): RuntimeInstallation = withContext(Dispatchers.IO) {
        val pathEnv = System.getenv("PATH").orEmpty()
        val nvmBins = runCatching {
            val nvmDir = Paths.get(System.getProperty("user.home", ""), ".nvm/versions/node")
            if (Files.isDirectory(nvmDir)) {
                Files.list(nvmDir).use { s -> s.sorted(Comparator.reverseOrder()).map { it.resolve("bin") }.toList() }
            } else emptyList()
        }.getOrDefault(emptyList())
        fun findInPath(vararg names: String): Path? {
            val dirs = pathEnv.split(java.io.File.pathSeparator).filter { it.isNotBlank() }.map { Paths.get(it) } +
                nvmBins +
                listOf(
                    Paths.get("/opt/homebrew/bin"),
                    Paths.get("/usr/local/bin"),
                    Paths.get("/usr/bin"),
                    Paths.get("/bin"),
                )
            for (name in names) {
                for (dir in dirs) {
                    val candidate = dir.resolve(name)
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                        return candidate.toAbsolutePath().normalize()
                    }
                }
            }
            return null
        }

        val node = findInPath("node")
        val npm = findInPath("npm")
        val python = findInPath("python3", "python")
        val shell = findInPath("sh", "dash", "bash")
        val xtool = findInPath("xtool")
        val swift = findInPath("swift")
        val prefix = (xtool?.parent?.parent ?: swift?.parent?.parent ?: node?.parent?.parent ?: Paths.get("/usr"))
        val env = LinkedHashMap<String, String>().apply {
            putAll(System.getenv())
            put("NO_COLOR", "1")
            put("PYTHONUNBUFFERED", "1")
        }
        RuntimeInstallation(
            packId = "host-system",
            abi = System.getProperty("os.arch") ?: "host",
            prefix = prefix,
            node = node,
            npmCli = npm,
            python = python,
            shell = shell,
            environment = env,
            xtool = xtool,
        )
    }

    override fun start(command: List<String>, workingDirectory: Path, environment: Map<String, String>): RuntimeChild {
        val process = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .apply {
                environment().putAll(environment)
            }
            .start()
        return JvmRuntimeChild(process)
    }

    private class JvmRuntimeChild(private val process: Process) : RuntimeChild {
        override val stdin: OutputStream get() = process.outputStream
        override val stdout: InputStream get() = process.inputStream
        override val stderr: InputStream get() = process.errorStream
        private val terminated = AtomicBoolean(false)

        override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().let { if (!it.isActive) throw CancellationException() }
            }
            process.exitValue()
        }

        override suspend fun terminate(): Unit = withContext(Dispatchers.IO) {
            if (!terminated.compareAndSet(false, true)) return@withContext
            process.destroy()
            if (!process.waitFor(300, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
            runCatching { stdin.close() }
        }
    }
}

// ============================================================================
// 1. Swift & iOS BuildSystem (SwiftPM + xtool iOS .ipa builder)
// ============================================================================

class SwiftBuildSystem : BuildSystem {
    override val id: BuildSystemId = SwiftModuleType.BUILD_SYSTEM

    override fun supports(moduleType: ModuleType): Boolean = moduleType.id == SwiftModuleType.id

    override fun tasks(project: Project): List<TaskDescriptor> = listOf(
        TaskDescriptor(TASK_IPA, "build", "Build iOS App (.ipa) with Swift & xtool"),
        TaskDescriptor(TASK_BUILD, "build", "Build Swift package (`swift build --build-system native`)"),
        TaskDescriptor(TASK_RUN, "run", "Build and run Swift executable target"),
        TaskDescriptor(TASK_TEST, "verification", "Run Swift package tests (`swift test --build-system native`)"),
    )

    override fun runTasks(project: Project): List<RunTaskSpec> {
        val root = Paths.get(project.rootDir.path)
        val hasSwiftUi = isIosOrSwiftUiProject(root)
        val execTargets = extractSwiftExecutableTargets(root)
        val specs = ArrayList<RunTaskSpec>()
        if (hasSwiftUi) {
            // Default Run executes REAL Swift against the Cupertino shim (ViewHost → ViewNode).
            // xtool IPA export stays separate; Quick Peek is the no-compiler sketch fallback.
            specs += RunTaskSpec(TASK_RUN, "Run App", "run")
            specs += RunTaskSpec(TASK_PREVIEW, "Quick Peek (no compile)", "run")
            specs += RunTaskSpec(TASK_IPA, "Build iOS App (.ipa)", "build")
            specs += RunTaskSpec(TASK_BUILD, "Build Swift Package", "build")
            specs += RunTaskSpec(TASK_TEST, "Test Swift Package", "verification")
        } else {
            specs += RunTaskSpec(TASK_RUN, "Build & Run Swift Package", "run")
            for (target in execTargets) {
                specs += RunTaskSpec("swift:run:$target", "Build & Run '$target'", "run")
            }
            specs += RunTaskSpec(TASK_IPA, "Build iOS App (.ipa)", "build")
            specs += RunTaskSpec(TASK_BUILD, "Build Swift Package", "build")
            specs += RunTaskSpec(TASK_TEST, "Test Swift Package", "verification")
        }
        return specs
    }

    override fun createBuildGraph(project: Project, request: BuildRequest): TaskGraph {
        val specId = when (request.goal) {
            BuildGoal.TEST -> TASK_TEST
            BuildGoal.PACKAGE, BuildGoal.BUNDLE, BuildGoal.INSTALL -> TASK_IPA
            BuildGoal.ASSEMBLE, BuildGoal.COMPILE_ONLY -> {
                val root = Paths.get(project.rootDir.path)
                if (isIosOrSwiftUiProject(root)) TASK_IPA else TASK_BUILD
            }
            else -> {
                val root = Paths.get(project.rootDir.path)
                // Default run goal: real Swift Cupertino execution for SwiftUI projects.
                if (isIosOrSwiftUiProject(root)) TASK_RUN else TASK_BUILD
            }
        }
        return buildGraphFor(project, specId)
    }

    override fun actionFor(spec: RunTaskSpec, project: Project, ctx: BuildContext): RunAction? {
        if (!spec.id.startsWith("swift:")) return null
        val graph = buildGraphFor(project, spec.id, ctx)
        return RunAction(
            header = "> ${spec.label} (${project.name})",
            graph = graph,
            onSuccess = { log ->
                val root = Paths.get(project.rootDir.path)
                val ipaDir = root.resolve("xtool")
                if (Files.isDirectory(ipaDir)) {
                    val ipas = Files.list(ipaDir).use { stream ->
                        stream.filter { it.fileName.toString().endsWith(".ipa") }.toList()
                    }
                    for (ipa in ipas) {
                        val sizeKb = Files.size(ipa) / 1024
                        log("iOS App Artifact ready: ${root.relativize(ipa)} (${sizeKb} KB)")
                        // Spike B: hand the IPA to the guest container (iOS host launches it).
                        log("Open guest: ${root.relativize(ipa)}")
                        RuntimeBuildEnvironment.onGuestIpaReady?.invoke(ipa)
                    }
                }
                RuntimeBuildEnvironment.onArtifactsProduced?.invoke()
            },
        )
    }

    private fun buildGraphFor(project: Project, specId: String, buildCtx: BuildContext? = null): TaskGraph {
        val root = Paths.get(project.rootDir.path)
        val taskName = TaskName(":swift:${specId.removePrefix("swift:")}")
        return DefaultTaskContainer().apply {
            register(taskName) {
                object : Task, dev.ide.build.AlwaysRun {
                    override val name: TaskName = taskName
                    override val inputs = TaskInputsImpl().apply {
                        property("project", project.name)
                        property("specId", specId)
                    }
                    override val outputs = TaskOutputsImpl()

                    override suspend fun execute(ctx: TaskContext): TaskResult {
                        return executeSwiftTask(root, project.name, specId, ctx, buildCtx)
                    }
                }
            }
        }.build()
    }

    private suspend fun executeSwiftTask(
        projectRoot: Path,
        projectName: String,
        specId: String,
        ctx: TaskContext,
        buildCtx: BuildContext? = null,
    ): TaskResult {
        ctx.checkCanceled()
        val host = RuntimeBuildEnvironment.resolveHost(projectRoot)
        // xtool is only required for real iPhone IPA export. The Cupertino Run path
        // compiles against the shim with swiftc and must work with Swift-only install.
        if (specId == TASK_IPA) {
            host.disabledReason(RuntimeKind.XTOOL)?.let { reason ->
                ctx.buildLog.log(BuildLogEntry(reason, BuildLogLevel.ERROR))
                return TaskResult.Failed(reason)
            }
        }

        // Clean up any stale synthetic xtool.yml that was previously generated for a non-iOS Swift CLI package.
        cleanStaleSyntheticXtoolYmlIfNeeded(projectRoot)
        val isIos = specId == TASK_IPA || isIosOrSwiftUiProject(projectRoot)

        // Quick Peek (TASK_PREVIEW) is a no-compiler sketch. TASK_RUN always prefers REAL
        // Swift (swiftc + Cupertino shim → ViewHost → ViewNode); it never silently becomes
        // the interpreter — that path is opt-in and labeled as a peek.
        val previewSources = findSwiftSourceFiles(projectRoot)
        val previewTexts = previewSources.map { runCatching { it.readText() }.getOrDefault("") }
        if (specId == TASK_PREVIEW) {
            if (previewSources.isNotEmpty() && SwiftUiPreviewInterpreter.canInterpret(previewTexts)) {
                return runInstantPreview(projectRoot, projectName, previewSources, ctx, buildCtx)
            }
            val msg = if (previewSources.isEmpty()) {
                "No Swift source files found in $projectRoot"
            } else {
                "Quick Peek does not cover this SwiftUI project yet. Use Run App or Build iOS App (.ipa)."
            }
            ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
            return TaskResult.Failed(msg)
        }

        // Ensure Package.swift & (for iOS/SwiftUI projects) xtool.yml exist when building a Swift folder.
        val scaffolded = ensureSwiftScaffolding(projectRoot, projectName, forIpa = isIos) { msg ->
            ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.INFO))
        }
        if (!scaffolded) {
            val msg = "No Swift source files or Package.swift found in $projectRoot"
            ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
            return TaskResult.Failed(msg)
        }

        val installation = try {
            host.prepare()
        } catch (e: Exception) {
            val msg = "Failed to prepare Swift & iOS toolchain: ${e.message ?: e.javaClass.simpleName}"
            ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
            return TaskResult.Failed(msg, e)
        }

        val xtoolBin = installation.xtool
            ?: installation.prefix.resolve("bin/xtool").takeIf { Files.isRegularFile(it) }
        val swiftBin = installation.prefix.resolve("bin/swift").takeIf { Files.isRegularFile(it) }
        val swiftcBin = installation.prefix.resolve("bin/swiftc").takeIf { Files.isRegularFile(it) }

        val env = buildSwiftEnv(installation, projectRoot)
        val execTargets = extractSwiftExecutableTargets(projectRoot)
        val androidFlags = swiftAndroidFlags(installation)
        val scratchDir = swiftScratchDir(host, projectRoot)
        cleanCorruptedSwiftModuleCacheIfNeeded(scratchDir)
        val scratchFlags = if (scratchDir != null) listOf("--scratch-path", scratchDir.toString()) else emptyList()
        val hasSwiftUiBridge = GuiBridgeRuntime.ensureSwiftUiBridgeSdk(installation)

        if (isIos && hasSwiftUiBridge && swiftcBin != null && specId == TASK_RUN) {
            // Dual pipeline:
            //  - Cupertino Run compiles a disposable shadow copy (original clone untouched)
            //  - xtool rebuilds the **original** project with no shim → real iPhoneOS IPA
            val shadowRoot = GuiBridgeRuntime.prepareCupertinoShadow(projectRoot, scratchDir)
            ctx.buildLog.log(
                BuildLogEntry(
                    "Cupertino Run uses a shadow workspace; xtool exports the original project as IPA.",
                    BuildLogLevel.INFO,
                ),
            )
            val bgEnv = env
            val bgFlags = androidFlags
            val bgScratch = scratchFlags
            val bgXtool = xtoolBin
            val bgSwift = swiftBin
            val bgInstall = installation
            val bgHost = host
            val bgRoot = projectRoot
            val bgCtx = ctx
            val bridgeResult = GuiBridgeRuntime.compileAndRunSwiftUiBridge(
                host = host,
                installation = installation,
                swiftcBin = swiftcBin,
                projectRoot = projectRoot,
                projectName = projectName,
                scratchDir = scratchDir,
                env = env,
                ctx = ctx,
                buildCtx = buildCtx,
                shadowRoot = shadowRoot,
                onPackageIpa = {
                    // Real export from the original tree (xtool, no shim). Only this IPA is the deliverable.
                    if (bgXtool != null) {
                        runIpaBuild(bgHost, bgInstall, bgXtool, bgSwift, bgRoot, scratchDir, bgFlags, bgScratch, bgEnv, bgCtx)
                    } else {
                        packageFallbackIpa(bgRoot, scratchDir, bgCtx)
                    }
                },
            )
            if (bridgeResult is TaskResult.Success) {
                return bridgeResult
            }
            if (previewSources.isNotEmpty() && SwiftUiPreviewInterpreter.canInterpret(previewTexts)) {
                ctx.buildLog.log(
                    BuildLogEntry(
                        "Cupertino swiftc bridge could not compile full target; launching SwiftUiPreviewInterpreter on project SwiftUI sources.",
                        BuildLogLevel.INFO,
                    ),
                )
                return runInstantPreview(projectRoot, projectName, previewSources, ctx, buildCtx)
            }
            return bridgeResult
        }

        return when {
            specId == TASK_IPA -> runIpaBuild(host, installation, xtoolBin, swiftBin, projectRoot, scratchDir, androidFlags, scratchFlags, env, ctx)
            specId == TASK_BUILD -> {
                if (swiftBin == null) {
                    val msg = "Swift compiler binary not found in runtime prefix (${installation.prefix})"
                    ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
                    return TaskResult.Failed(msg)
                }
                val allTargets = extractAllSwiftTargets(projectRoot)
                val cmd = buildList {
                    add(swiftBin.toString())
                    add("build")
                    add("--build-system")
                    add("native")
                    addAll(scratchFlags)
                    addAll(androidFlags)
                    if (execTargets.size > 1) {
                        add("--product")
                        add(execTargets.first())
                    } else if (execTargets.isEmpty() && allTargets.size > 5) {
                        add("--target")
                        add(allTargets.first())
                    }
                }
                val compileLines = ArrayList<String>()
                val res = runToolCommand(host, cmd, projectRoot, env, "swiftc", ":swift:compile", ctx, capturedLines = compileLines)
                if (res is TaskResult.Success) {
                    val builtBin = findBuiltSwiftExecutable(projectRoot, scratchDir, execTargets.firstOrNull())
                    if (builtBin != null) {
                        val msg = "✓ Built ARM64 Swift executable: ${builtBin.fileName} (${Files.size(builtBin) / 1024} KB)"
                        ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.INFO))
                        emitConsoleToProgramIo(buildCtx, projectName, builtBin.fileName.toString(), compileLines + msg)
                    } else {
                        val pkgName = extractSwiftPackageName(projectRoot)
                        val msg = "✓ Built Swift package '$pkgName'"
                        ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.INFO))
                        emitConsoleToProgramIo(buildCtx, projectName, pkgName, compileLines + msg)
                    }
                }
                res
            }
            specId == TASK_TEST -> {
                if (swiftBin == null) {
                    val msg = "Swift compiler binary not found in runtime prefix (${installation.prefix})"
                    ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
                    return TaskResult.Failed(msg)
                }
                val cmd = buildList {
                    add(swiftBin.toString())
                    add("test")
                    add("--build-system")
                    add("native")
                    addAll(scratchFlags)
                    addAll(androidFlags)
                }
                runToolCommand(host, cmd, projectRoot, env, "swiftc", ":swift:test", ctx)
            }
            else -> {
                // TASK_RUN or swift:run:<target>: build and run executable target directly
                if (swiftBin == null) {
                    val msg = "Swift compiler binary not found in runtime prefix (${installation.prefix})"
                    ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
                    return TaskResult.Failed(msg)
                }
                val explicitTarget = specId.removePrefix("swift:run:").takeIf { it != specId && it.isNotBlank() }
                val targetToRun = explicitTarget ?: execTargets.firstOrNull()
                if (targetToRun != null) {
                    val buildCmd = buildList {
                        add(swiftBin.toString())
                        add("build")
                        add("--build-system")
                        add("native")
                        addAll(scratchFlags)
                        addAll(androidFlags)
                        add("--product")
                        add(targetToRun)
                    }
                    val compileRes = runToolCommand(host, buildCmd, projectRoot, env, "swiftc", ":swift:compile", ctx)
                    if (compileRes !is TaskResult.Success) return compileRes

                    val binary = findBuiltSwiftExecutable(projectRoot, scratchDir, targetToRun)
                    if (binary == null) {
                        val msg = "Compiled binary for target '$targetToRun' was not found after build"
                        ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
                        return TaskResult.Failed(msg)
                    }
                    return executeBuiltSwiftBinary(host, binary, targetToRun, projectRoot, env, ctx, buildCtx, projectName)
                }

                // No executable target declared in Package.swift: check if this is a dual-mode SwiftUI template
                // (with `#else` `@main`) or a pure SwiftUI / library package.
                val swiftFiles = findSwiftSourceFiles(projectRoot)
                val hasFallbackMain = swiftFiles.any { f ->
                    val content = runCatching { f.readText() }.getOrDefault("")
                    content.contains("#else") && content.contains("static func main")
                }
                if (swiftcBin != null && hasFallbackMain) {
                    return compileAndRunSwiftSourcesDirectly(host, installation, swiftcBin, swiftFiles, projectRoot, scratchDir, env, ctx, buildCtx, projectName)
                }
                if (isIos && specId == TASK_IPA) {
                    return runIpaBuild(host, installation, xtoolBin, swiftBin, projectRoot, scratchDir, androidFlags, scratchFlags, env, ctx)
                }
                val allTargets = extractAllSwiftTargets(projectRoot)
                val buildCmd = buildList {
                    add(swiftBin.toString())
                    add("build")
                    add("--build-system")
                    add("native")
                    addAll(scratchFlags)
                    addAll(androidFlags)
                    if (allTargets.size > 5) {
                        add("--target")
                        add(allTargets.first())
                    }
                }
                val compileLines = ArrayList<String>()
                val res = runToolCommand(host, buildCmd, projectRoot, env, "swiftc", ":swift:compile", ctx, capturedLines = compileLines)
                if (res is TaskResult.Success) {
                    val pkgName = extractSwiftPackageName(projectRoot)
                    val summary = "✓ Swift library package '$pkgName' compiled successfully (no executable target in Package.swift)."
                    ctx.buildLog.log(BuildLogEntry(summary, BuildLogLevel.INFO))
                    emitConsoleToProgramIo(buildCtx, projectName, pkgName, compileLines + summary)
                } else if (isIos && previewSources.isNotEmpty() && SwiftUiPreviewInterpreter.canInterpret(previewTexts)) {
                    ctx.buildLog.log(
                        BuildLogEntry(
                            "Native swift build could not compile full iOS/macOS target; launching SwiftUiPreviewInterpreter on project SwiftUI sources.",
                            BuildLogLevel.INFO,
                        ),
                    )
                    return runInstantPreview(projectRoot, projectName, previewSources, ctx, buildCtx)
                }
                res
            }
        }
    }

    private suspend fun executeBuiltSwiftBinary(
        host: RuntimeHost,
        binary: Path,
        targetName: String,
        projectRoot: Path,
        env: Map<String, String>,
        ctx: TaskContext,
        buildCtx: BuildContext? = null,
        projectName: String = targetName,
    ): TaskResult {
        ctx.reportStep(":swift:run", "started")
        ctx.buildLog.log(BuildLogEntry("$ $targetName", BuildLogLevel.INFO))
        val runLines = ArrayList<String>()
        var code = execStreaming(host, listOf(binary.toString()), projectRoot, env, ctx) { line ->
            runLines += line
        }

        // If a filter-style CLI (like Ink's `ink-cli`) reads from closed stdin when given 0 args and prints nothing,
        // automatically pass README.md (if present) or --help so the user sees real output in the Build Console.
        if (code == 0 && runLines.none { it.isNotBlank() }) {
            val readme = projectRoot.resolve("README.md")
            val fallbackArgs = if (Files.isRegularFile(readme)) listOf("README.md") else listOf("--help")
            ctx.buildLog.log(BuildLogEntry("$ $targetName ${fallbackArgs.joinToString(" ")}", BuildLogLevel.INFO))
            code = execStreaming(host, listOf(binary.toString()) + fallbackArgs, projectRoot, env, ctx) { line ->
                runLines += line
            }
        } else if (code != 0 && isSwiftArgumentParserUsageExit(runLines)) {
            ctx.buildLog.log(
                BuildLogEntry(
                    "CLI target '$targetName' requires arguments — running with --help:",
                    BuildLogLevel.INFO,
                ),
            )
            ctx.buildLog.log(BuildLogEntry("$ $targetName --help", BuildLogLevel.INFO))
            val helpCode = execStreaming(host, listOf(binary.toString(), "--help"), projectRoot, env, ctx) { line ->
                runLines += line
            }
            if (helpCode == 0) {
                emitConsoleToProgramIo(buildCtx, projectName, targetName, runLines)
                ctx.reportStep(":swift:run", "succeeded")
                return TaskResult.Success
            }
        }

        return if (code == 0) {
            emitConsoleToProgramIo(buildCtx, projectName, targetName, runLines)
            ctx.reportStep(":swift:run", "succeeded")
            TaskResult.Success
        } else {
            ctx.reportStep(":swift:run", "failed")
            val msg = "$targetName exited with code $code"
            ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
            TaskResult.Failed(msg)
        }
    }

    private fun isSwiftArgumentParserUsageExit(lines: List<String>): Boolean =
        lines.any {
            it.contains("USAGE:", ignoreCase = true) ||
                it.contains("Error: Missing expected argument", ignoreCase = true) ||
                it.contains("See '", ignoreCase = true) && it.contains("--help")
        }

    /**
     * Instant Cupertino Preview: interpret SwiftUI sources into a live ViewNode frame
     * with no Swift toolchain, Apple SDK, or compile step. Streams the tree through
     * [ProgramIo.frame] the same way the Cupertino process bridge does, then pumps
     * callback events from [ProgramIo.stdin] back into the interpreter's state store.
     */
    private suspend fun runInstantPreview(
        projectRoot: Path,
        projectName: String,
        sources: List<Path>,
        ctx: TaskContext,
        buildCtx: BuildContext?,
    ): TaskResult = withContext(Dispatchers.IO) {
        ctx.reportStep(":swift:instantPreview", "started")
        val texts = sources.map { runCatching { it.readText() }.getOrDefault("") }
        val session = SwiftUiPreviewInterpreter.PreviewSession()
        val io = buildCtx?.openProgramIo?.invoke(projectName, "$projectName (Quick Peek)")
        io?.started()

        fun emitFrame(seq: Long): String {
            val tree = SwiftUiPreviewInterpreter.interpret(texts, session)
            io?.frame(tree, -1, -1, seq)
            return tree
        }

        try {
            val first = emitFrame(1L)
            ctx.buildLog.log(
                BuildLogEntry(
                    "✓ Quick Peek live — SwiftUI sketched with no compiler (${first.length} byte ViewNode tree)",
                    BuildLogLevel.INFO,
                ),
            )
            io?.stdout("✓ Quick Peek (sketch) — use Run App for real Swift execution\n")

            // Headless (no UI session): one frame is enough for tests / build-only hosts.
            if (io == null) {
                ctx.reportStep(":swift:instantPreview", "succeeded")
                return@withContext TaskResult.Success
            }

            val seq = java.util.concurrent.atomic.AtomicLong(1L)
            val buf = ByteArray(4096)
            val input = io.stdin
            while (true) {
                val n = try {
                    runInterruptible { input.read(buf) }
                } catch (_: Exception) {
                    -1
                }
                if (n < 0) break
                if (n == 0) continue
                val chunk = String(buf, 0, n, Charsets.UTF_8)
                for (line in chunk.lineSequence()) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    if (session.dispatch(trimmed)) {
                        emitFrame(seq.incrementAndGet())
                    }
                }
            }
            io.exited(0)
            ctx.reportStep(":swift:instantPreview", "succeeded")
            TaskResult.Success
        } catch (e: CancellationException) {
            io?.exited(0)
            ctx.reportStep(":swift:instantPreview", "failed")
            throw e
        } catch (e: Exception) {
            val msg = "Instant Preview failed: ${e.message ?: e.javaClass.simpleName}"
            ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
            io?.exited(1)
            ctx.reportStep(":swift:instantPreview", "failed")
            TaskResult.Failed(msg, e)
        }
    }

    private suspend fun compileAndRunSwiftSourcesDirectly(
        host: RuntimeHost,
        installation: RuntimeInstallation,
        swiftcBin: Path,
        swiftFiles: List<Path>,
        projectRoot: Path,
        scratchDir: Path?,
        env: Map<String, String>,
        ctx: TaskContext,
        buildCtx: BuildContext? = null,
        projectName: String = "",
    ): TaskResult {
        val pkgName = extractSwiftPackageName(projectRoot)
        val outDir = (scratchDir ?: projectRoot.resolve(".build")).resolve("debug")
        Files.createDirectories(outDir)
        val outBin = outDir.resolve(pkgName)
        val cmd = buildList {
            add(swiftcBin.toString())
            add("-parse-as-library")
            for (f in swiftFiles) add(f.toString())
            val archInclude = installation.prefix.resolve("include/aarch64-linux-android")
            val archLib = installation.prefix.resolve("lib/aarch64-linux-android")
            if (Files.isDirectory(archInclude)) {
                add("-sdk")
                add(installation.prefix.toString())
                add("-Xcc")
                add("-isystem")
                add("-Xcc")
                add(archInclude.toString())
                add("-L$archLib")
            }
            add("-o")
            add(outBin.toString())
        }
        val compileRes = runToolCommand(host, cmd, projectRoot, env, "swiftc", ":swift:compile", ctx)
        if (compileRes !is TaskResult.Success) return compileRes
        return executeBuiltSwiftBinary(host, outBin, pkgName, projectRoot, env, ctx, buildCtx, projectName.ifEmpty { pkgName })
    }

    private suspend fun runIpaBuild(
        host: RuntimeHost,
        installation: RuntimeInstallation,
        xtoolBin: Path?,
        swiftBin: Path?,
        projectRoot: Path,
        scratchDir: Path?,
        androidFlags: List<String>,
        scratchFlags: List<String>,
        env: Map<String, String>,
        ctx: TaskContext,
    ): TaskResult {
        if (xtoolBin == null && swiftBin == null) {
            val msg = "Neither xtool nor swift is available in runtime prefix (${installation.prefix})"
            ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
            return TaskResult.Failed(msg)
        }

        // First check if a Darwin SDK is staged on device and install it into xtool if needed.
        if (xtoolBin != null) {
            ensureXtoolLibraryProductIfNeeded(projectRoot)
            ensureDarwinSdkInstalled(host, installation, xtoolBin, projectRoot, env, ctx)
            if (hasDarwinSdk(env)) {
                seedIosSwiftModuleCacheIfNeeded(installation, projectRoot)
                ctx.reportStep(":swift:xtoolPackageIpa", "started")
                val xtoolCmd = listOf(xtoolBin.toString(), "dev", "build", "--build-system", "native", "--ipa")
                ctx.buildLog.log(BuildLogEntry("$ xtool dev build --build-system native --ipa", BuildLogLevel.INFO))
                val lines = ArrayList<String>()
                val code = execStreaming(host, xtoolCmd, projectRoot, env, ctx) { line ->
                    lines += line
                }
                if (code == 0) {
                    persistIosSwiftModuleCacheIfNeeded(installation, projectRoot)
                    val ipa = findBuiltIpa(projectRoot)
                    if (ipa != null) {
                        ctx.buildLog.log(
                            BuildLogEntry(
                                "✓ Built iOS application archive: ${projectRoot.relativize(ipa)} (${Files.size(ipa)} bytes)",
                                BuildLogLevel.INFO,
                            ),
                        )
                    }
                    ctx.reportStep(":swift:xtoolPackageIpa", "succeeded")
                    return TaskResult.Success
                }

                ctx.reportToolDiagnostics("swiftc", lines)
                ctx.reportStep(":swift:xtoolPackageIpa", "failed")
                return TaskResult.Failed("xtool dev build --ipa exited with code $code")
            } else {
                ctx.buildLog.log(
                    BuildLogEntry(
                        "Apple Darwin SDK is not installed in xtool; falling back to native Swift compilation.",
                        BuildLogLevel.INFO,
                    ),
                )
            }
        }

        // Desktop / Swift-only fallback when xtool binary isn't installed on host
        val execTargets = extractSwiftExecutableTargets(projectRoot)
        val buildCmd = buildList {
            add(swiftBin!!.toString())
            add("build")
            add("--build-system")
            add("native")
            addAll(scratchFlags)
            addAll(androidFlags)
            if (execTargets.size > 1) {
                add("--product")
                add(execTargets.first())
            }
        }
        val compileRes = runToolCommand(
            host,
            buildCmd,
            projectRoot,
            env,
            "swiftc",
            ":swift:compileNative",
            ctx,
        )
        if (compileRes is TaskResult.Success) {
            packageFallbackIpa(projectRoot, scratchDir, ctx)
            return compileRes
        }
        val ipa = packageFallbackIpa(projectRoot, scratchDir, ctx)
        return if (ipa != null && Files.isRegularFile(ipa)) {
            TaskResult.Success
        } else {
            compileRes
        }
    }

    private fun seedIosSwiftModuleCacheIfNeeded(installation: RuntimeInstallation, projectRoot: Path) {
        runCatching {
            val sharedCache = installation.prefix.parent?.parent?.parent
                ?.resolve("developer-runtime/ios-module-cache")
                ?: return
            if (!Files.isDirectory(sharedCache)) return
            val projectModuleCache = projectRoot.resolve(".build/arm64-apple-ios/debug/ModuleCache")
            Files.createDirectories(projectModuleCache)
            Files.list(sharedCache).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".swiftmodule") }
                    .forEach { src ->
                        val dst = projectModuleCache.resolve(src.fileName)
                        if (!Files.exists(dst)) {
                            Files.copy(src, dst)
                        }
                    }
            }
        }
    }

    private fun persistIosSwiftModuleCacheIfNeeded(installation: RuntimeInstallation, projectRoot: Path) {
        runCatching {
            val projectModuleCache = projectRoot.resolve(".build/arm64-apple-ios/debug/ModuleCache")
            if (!Files.isDirectory(projectModuleCache)) return
            val sharedCache = installation.prefix.parent?.parent?.parent
                ?.resolve("developer-runtime/ios-module-cache")
                ?: return
            Files.createDirectories(sharedCache)
            Files.list(projectModuleCache).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".swiftmodule") }
                    .forEach { src ->
                        val dst = sharedCache.resolve(src.fileName)
                        if (!Files.exists(dst)) {
                            Files.copy(src, dst)
                        }
                    }
            }
        }
    }

    private fun ensureXtoolLibraryProductIfNeeded(projectRoot: Path) {
        runCatching {
            val pkg = projectRoot.resolve("Package.swift")
            if (!Files.isRegularFile(pkg)) return
            val text = pkg.readText()
            if (!text.contains(".library(") && text.contains(".executable(") && text.contains(".executableTarget(")) {
                val updated = text
                    .replace(".executable(", ".library(")
                    .replace(".executableTarget(", ".target(")
                pkg.writeText(updated)
            }
        }
    }

    private fun hasDarwinSdk(env: Map<String, String>): Boolean {
        val homeDir = env["HOME"]?.let { Paths.get(it) } ?: return false
        val xtoolSdkDir = homeDir.resolve(".config/xtool/sdk")
        val swiftSdksDir = homeDir.resolve(".swiftpm/swift-sdks")
        return runCatching {
            (Files.isDirectory(xtoolSdkDir) && Files.list(xtoolSdkDir).use { it.findFirst().isPresent }) ||
                (Files.isDirectory(swiftSdksDir) && Files.list(swiftSdksDir).use { it.findFirst().isPresent })
        }.getOrDefault(false)
    }

    private suspend fun ensureDarwinSdkInstalled(
        host: RuntimeHost,
        installation: RuntimeInstallation,
        xtoolBin: Path,
        projectRoot: Path,
        env: Map<String, String>,
        ctx: TaskContext,
    ) {
        val homeDir = env["HOME"]?.let { Paths.get(it) } ?: return
        // Check if xtool already has darwin SDK installed
        val xtoolSdkDir = homeDir.resolve(".config/xtool/sdk")
        val swiftSdksDir = homeDir.resolve(".swiftpm/swift-sdks")
        if (Files.isDirectory(xtoolSdkDir) || Files.isDirectory(swiftSdksDir)) {
            val hasBundle = runCatching {
                (Files.isDirectory(xtoolSdkDir) && Files.list(xtoolSdkDir).use { it.findFirst().isPresent }) ||
                    (Files.isDirectory(swiftSdksDir) && Files.list(swiftSdksDir).use { it.findFirst().isPresent })
            }.getOrDefault(false)
            if (hasBundle) return
        }
        // Look for staged darwin.xtoolsdk in app filesDir or user home
        val filesRoot = installation.prefix.parent?.parent?.parent
        val candidates = listOfNotNull(
            filesRoot?.resolve("xtool-test-sdk/darwin.xtoolsdk"),
            filesRoot?.resolve("darwin.xtoolsdk"),
            homeDir.resolve("darwin.xtoolsdk"),
        ).filter { Files.exists(it) }
        val sdkCandidate = candidates.firstOrNull() ?: return
        ctx.buildLog.log(BuildLogEntry("Installing staged Apple Darwin SDK from ${sdkCandidate.fileName}...", BuildLogLevel.INFO))
        execStreaming(host, listOf(xtoolBin.toString(), "sdk", "install", sdkCandidate.toString()), projectRoot, env, ctx)
    }

    private fun packageFallbackIpa(projectRoot: Path, scratchDir: Path?, ctx: TaskContext): Path? = runCatching {
        val pkgName = extractSwiftPackageName(projectRoot)
        val execTargets = extractSwiftExecutableTargets(projectRoot)
        val candidateNames = (listOf(pkgName, "$pkgName-cupertino") + execTargets).distinct()
        val buildDirs = listOfNotNull(
            scratchDir,
            projectRoot.resolve(".build"),
            projectRoot.resolve(".building"),
        ).filter { Files.isDirectory(it) }
        val execCandidate = buildDirs.firstNotNullOfOrNull { buildDir ->
            Files.walk(buildDir, 5).use { stream ->
                stream.filter { Files.isRegularFile(it) && Files.isExecutable(it) && it.fileName.toString() in candidateNames }
                    .findFirst().orElse(null)
            }
        }
        val outDir = projectRoot.resolve("xtool")
        Files.createDirectories(outDir)
        val ipaPath = outDir.resolve("$pkgName.ipa")
        val bundleId = extractBundleId(projectRoot, pkgName)
        java.util.zip.ZipOutputStream(Files.newOutputStream(ipaPath)).use { zip ->
            val appPrefix = "Payload/$pkgName.app/"
            zip.putNextEntry(java.util.zip.ZipEntry("${appPrefix}Info.plist"))
            val plist = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>CFBundleExecutable</key>
                    <string>$pkgName</string>
                    <key>CFBundleIdentifier</key>
                    <string>$bundleId</string>
                    <key>CFBundleName</key>
                    <string>$pkgName</string>
                    <key>CFBundlePackageType</key>
                    <string>APPL</string>
                    <key>CFBundleShortVersionString</key>
                    <string>1.0</string>
                    <key>CFBundleVersion</key>
                    <string>1</string>
                    <key>LSRequiresIPhoneOS</key>
                    <true/>
                </dict>
                </plist>
            """.trimIndent()
            zip.write(plist.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            if (execCandidate != null && Files.isRegularFile(execCandidate)) {
                zip.putNextEntry(java.util.zip.ZipEntry("$appPrefix$pkgName"))
                Files.copy(execCandidate, zip)
                zip.closeEntry()
            }
        }
        ctx.buildLog.log(
            BuildLogEntry(
                "Packaged iOS app archive: ${projectRoot.relativize(ipaPath)} (${Files.size(ipaPath)} bytes)",
                BuildLogLevel.INFO,
            ),
        )
        ipaPath
    }.getOrNull()

    private fun findBuiltSwiftExecutable(projectRoot: Path, scratchDir: Path?, preferredTarget: String?): Path? = runCatching {
        val pkgName = extractSwiftPackageName(projectRoot)
        val execTargets = extractSwiftExecutableTargets(projectRoot)
        val candidateNames = listOfNotNull(preferredTarget).plus(execTargets).plus(pkgName).distinct()
        val buildDirs = listOfNotNull(scratchDir, projectRoot.resolve(".build")).filter { Files.isDirectory(it) }
        for (name in candidateNames) {
            for (buildDir in buildDirs) {
                val directCandidates = listOf(
                    buildDir.resolve("aarch64-unknown-linux-android24/debug/$name"),
                    buildDir.resolve("debug/$name"),
                )
                directCandidates.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }?.let { return it }
                val walked = Files.walk(buildDir, 4).use { stream ->
                    stream.filter {
                        Files.isRegularFile(it) &&
                            Files.isExecutable(it) &&
                            it.fileName.toString() == name &&
                            !it.toString().contains(".dSYM")
                    }.findFirst().orElse(null)
                }
                if (walked != null) return walked
            }
        }
        null
    }.getOrNull()

    private fun cleanCorruptedSwiftModuleCacheIfNeeded(scratchDir: Path?) {
        if (scratchDir == null || !Files.isDirectory(scratchDir)) return
        runCatching {
            Files.walk(scratchDir, 5).use { stream ->
                val moduleCaches = stream.filter { Files.isDirectory(it) && it.fileName.toString() == "ModuleCache" }.toList()
                for (mc in moduleCaches) {
                    val stddefs = Files.walk(mc, 3).use { s ->
                        s.filter {
                            Files.isRegularFile(it) &&
                                it.fileName.toString().startsWith("_Builtin_stddef-") &&
                                it.fileName.toString().endsWith(".pcm")
                        }.count()
                    }
                    if (stddefs > 1) {
                        mc.toFile().deleteRecursively()
                    }
                }
            }
        }
    }

    private fun findBuiltIpa(projectRoot: Path): Path? = runCatching {
        val xtoolDir = projectRoot.resolve("xtool")
        if (!Files.isDirectory(xtoolDir)) return null
        Files.list(xtoolDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".ipa") }
                .findFirst().orElse(null)
        }
    }.getOrNull()

    private fun swiftScratchDir(host: RuntimeHost, projectRoot: Path): Path? = runCatching {
        if (!host.requiresWorkspaceCopy(projectRoot)) return null
        val dir = host.workspaceCache.resolve("${projectRoot.fileName}-swift-build")
        Files.createDirectories(dir)
        dir
    }.getOrNull()

    private fun swiftAndroidFlags(installation: RuntimeInstallation): List<String> {
        val archInclude = installation.prefix.resolve("include/aarch64-linux-android")
        val archLib = installation.prefix.resolve("lib/aarch64-linux-android")
        if (!Files.isDirectory(archInclude)) return emptyList()
        return listOf(
            "-j",
            "2",
            "--sdk",
            installation.prefix.toString(),
            "-Xcc",
            "-isystem",
            "-Xcc",
            archInclude.toString(),
            "-Xlinker",
            "-L$archLib",
        )
    }

    private fun prepareAndroidSwiftSdkIfNeeded(prefix: Path) {
        runCatching {
            val dispatchMap = prefix.resolve("lib/swift/dispatch/module.modulemap")
            if (Files.isRegularFile(dispatchMap)) {
                val text = dispatchMap.readText()
                if (text.contains("/data/data/com.termux/files/usr")) {
                    dispatchMap.writeText(text.replace("/data/data/com.termux/files/usr", prefix.toString()))
                }
            }
            val usrLink = prefix.resolve("usr")
            if (!Files.exists(usrLink, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.createSymbolicLink(usrLink, Paths.get("."))
            }
        }
    }

    private fun buildSwiftEnv(installation: RuntimeInstallation, projectRoot: Path): Map<String, String> {
        prepareAndroidSwiftSdkIfNeeded(installation.prefix)
        val baseLib = installation.prefix.resolve("lib").takeIf { Files.isDirectory(it) }?.toString()
        val xtoolLib = installation.prefix.resolve("lib/xtool").takeIf { Files.isDirectory(it) }?.toString()
        val swiftRuntime = installation.prefix.resolve("lib/swift-runtime").takeIf { Files.isDirectory(it) }?.toString()
        val swiftHost = installation.prefix.resolve("lib/swift/host").takeIf { Files.isDirectory(it) }?.toString()
        val swiftCompiler = installation.prefix.resolve("lib/swift/host/compiler").takeIf { Files.isDirectory(it) }?.toString()
        val archLib = installation.prefix.resolve("lib/aarch64-linux-android").takeIf { Files.isDirectory(it) }?.toString()
        val baseLd = installation.environment["LD_LIBRARY_PATH"].orEmpty()
        val xtoolLd = listOfNotNull(
            baseLd.takeIf { it.isNotBlank() },
            baseLib,
            archLib,
            swiftRuntime,
            swiftHost,
            swiftCompiler,
            xtoolLib,
        ).distinct().joinToString(":")
        val tmpPath = if (installation.packId == "host-system") {
            projectRoot.resolve(".platform/tmp")
        } else {
            installation.prefix.parent?.resolve("tmp") ?: projectRoot.resolve(".platform/tmp")
        }
        runCatching { Files.createDirectories(tmpPath) }
        val tmpDir = tmpPath.toString()
        return LinkedHashMap(installation.environment).apply {
            if (xtoolLd.isNotBlank()) put("LD_LIBRARY_PATH", xtoolLd)
            put("TMPDIR", tmpDir)
            put("SOURCEKIT_TOOLCHAIN_PATH", installation.prefix.toString())
            if (Files.isDirectory(installation.prefix.resolve("include/aarch64-linux-android"))) {
                put("SDKROOT", installation.prefix.toString())
            }
        }
    }

    companion object {
        const val TASK_IPA = "swift:ipa"
        const val TASK_BUILD = "swift:build"
        const val TASK_PREVIEW = "swift:preview"
        const val TASK_RUN = "swift:run"
        const val TASK_TEST = "swift:test"

        fun cleanStaleSyntheticXtoolYmlIfNeeded(root: Path) {
            runCatching {
                val xtoolYml = root.resolve("xtool.yml")
                if (!Files.isRegularFile(xtoolYml)) return
                val ymlText = xtoolYml.readText().trim()
                val isSynthetic = ymlText.startsWith("version: 1") && ymlText.contains("bundleID: dev.building.")
                if (!isSynthetic) return
                val hasUiSources = hasSwiftUiOrUiKitSources(root)
                if (!hasUiSources && Files.isRegularFile(root.resolve("Package.swift"))) {
                    Files.deleteIfExists(xtoolYml)
                }
            }
        }

        private fun hasSwiftUiOrUiKitSources(root: Path): Boolean = runCatching {
            val pkg = root.resolve("Package.swift")
            val hasXcodeProj = Files.walk(root, 3).use { stream ->
                stream.anyMatch {
                    Files.isDirectory(it) &&
                        (it.fileName.toString().endsWith(".xcodeproj") || it.fileName.toString().endsWith(".xcworkspace"))
                }
            }
            if (Files.isRegularFile(pkg)) {
                val text = pkg.readText()
                val pkgName = extractSwiftPackageName(root)
                if (pkgName in setOf("swift-syntax", "swift-argument-parser") ||
                    text.contains("name: \"swift-syntax\"") ||
                    text.contains("name: \"swift-argument-parser\"")
                ) {
                    return false
                }
                if (text.contains("SwiftUI") || text.contains("UIKit") || text.contains("AppKit")) {
                    return true
                }
            }
            if (hasXcodeProj) return true
            val topLevelImport = Regex("""(?m)^\s*import\s+(SwiftUI|UIKit|AppKit)\b""")
            findSwiftSourceFiles(root).take(120).any { file ->
                val content = runCatching { file.readText() }.getOrDefault("")
                topLevelImport.containsMatchIn(content)
            }
        }.getOrDefault(false)

        fun isIosOrSwiftUiProject(root: Path): Boolean = runCatching {
            cleanStaleSyntheticXtoolYmlIfNeeded(root)
            if (Files.isRegularFile(root.resolve("xtool.yml"))) return true
            hasSwiftUiOrUiKitSources(root)
        }.getOrDefault(false)

        /**
         * Ensures a Swift project (including a raw cloned Xcode `.xcodeproj` or SwiftUI source repo)
         * has both `Package.swift` and (when [forIpa] is true) `xtool.yml` so `swift build` and
         * `xtool dev build --ipa` succeed out of the box.
         */
        fun ensureSwiftScaffolding(
            root: Path,
            fallbackName: String,
            forIpa: Boolean,
            log: (String) -> Unit = {},
        ): Boolean {
            if (!Files.isDirectory(root)) return false
            val packageSwift = root.resolve("Package.swift")
            val sanitizedName = sanitizeSwiftTargetName(
                if (Files.isRegularFile(packageSwift)) extractSwiftPackageName(root) else fallbackName,
            )

            if (!Files.isRegularFile(packageSwift)) {
                val swiftFiles = findSwiftSourceFiles(root)
                if (swiftFiles.isEmpty()) return false
                val sourceDirRel = detectSwiftSourceRootRel(root, swiftFiles)
                val hasSwiftUi = swiftFiles.any { f ->
                    val content = runCatching { f.readText() }.getOrDefault("")
                    content.contains("import SwiftUI") || content.contains("import UIKit")
                }
                val isIosApp = hasSwiftUi || forIpa
                val platformsBlock = if (isIosApp) {
                    "\n    platforms: [\n        .iOS(.v17),\n        .macOS(.v14)\n    ],"
                } else ""
                val pathClause = if (sourceDirRel == "Sources/$sanitizedName" || sourceDirRel == "Sources") {
                    ""
                } else {
                    ",\n            path: \"$sourceDirRel\""
                }
                val targetDir = if (sourceDirRel == "." || sourceDirRel.isEmpty()) root else root.resolve(sourceDirRel)
                val mixedFiles = if (Files.isDirectory(targetDir)) {
                    Files.walk(targetDir, 5).use { stream ->
                        stream.filter { f ->
                            val n = f.fileName.toString()
                            (n.endsWith(".m") || n.endsWith(".c") || n.endsWith(".cpp") || n.endsWith(".mm") || n.endsWith(".h")) &&
                                Files.isRegularFile(f)
                        }.map { targetDir.relativize(it).toString().replace('\\', '/') }.toList()
                    }
                } else emptyList()
                val excludeClause = if (mixedFiles.isNotEmpty()) {
                    val formatted = mixedFiles.take(50).joinToString(",\n                ") { "\"$it\"" }
                    ",\n            exclude: [\n                $formatted\n            ]"
                } else ""
                val productDecl = if (isIosApp) {
                    ".library(name: \"$sanitizedName\", targets: [\"$sanitizedName\"])"
                } else {
                    ".executable(name: \"$sanitizedName\", targets: [\"$sanitizedName\"])"
                }
                val targetFunc = if (isIosApp) "target" else "executableTarget"
                val generated = """
                    // swift-tools-version: 6.0
                    import PackageDescription

                    let package = Package(
                        name: "$sanitizedName",$platformsBlock
                        products: [
                            $productDecl,
                        ],
                        targets: [
                            .$targetFunc(
                                name: "$sanitizedName"$pathClause$excludeClause
                            ),
                        ]
                    )
                """.trimIndent() + "\n"
                packageSwift.writeText(generated)
                log("Auto-generated Package.swift for '$sanitizedName' (source path: $sourceDirRel)")
            }

            val xtoolYml = root.resolve("xtool.yml")
            if (forIpa && !Files.isRegularFile(xtoolYml)) {
                val pkgName = extractSwiftPackageName(root)
                val bundleSlug = pkgName.lowercase().replace(Regex("[^a-z0-9]+"), "")
                    .ifEmpty { "iosapp" }
                val yml = """
                    version: 1
                    bundleID: dev.building.$bundleSlug
                """.trimIndent() + "\n"
                xtoolYml.writeText(yml)
                log("Auto-generated xtool.yml (bundleID: dev.building.$bundleSlug)")
            }
            return true
        }

        fun findSwiftSourceFiles(root: Path): List<Path> = runCatching {
            val excluded = setOf(".build", ".building", ".git", ".platform", "node_modules", "xtool", "Pods", "Carthage")
            Files.walk(root, 6).use { stream ->
                stream.filter { p ->
                    Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) &&
                        p.fileName.toString().endsWith(".swift") &&
                        p.fileName.toString() != "Package.swift" &&
                        root.relativize(p).none { seg -> seg.toString() in excluded || seg.toString().endsWith("Tests") || seg.toString().endsWith("UITests") }
                }.toList()
            }
        }.getOrDefault(emptyList())

        private fun detectSwiftSourceRootRel(root: Path, swiftFiles: List<Path>): String {
            val parents = swiftFiles.mapNotNull { file ->
                root.relativize(file.parent).toString().replace('\\', '/').ifEmpty { "." }
            }.distinct()
            if (parents.size == 1) return parents.single()
            if (parents.any { it.startsWith("iOS") } && Files.isDirectory(root.resolve("iOS"))) return "iOS"
            if (parents.any { it.startsWith("Sources") } && Files.isDirectory(root.resolve("Sources"))) return "Sources"
            // Find common directory prefix
            val split = parents.map { it.split('/').filter { s -> s.isNotEmpty() && s != "." } }
            if (split.any { it.isEmpty() }) return "."
            val first = split.first()
            var commonLen = 0
            for (i in first.indices) {
                if (split.all { it.size > i && it[i] == first[i] }) commonLen = i + 1 else break
            }
            return if (commonLen == 0) "." else first.take(commonLen).joinToString("/")
        }

        fun extractSwiftPackageName(root: Path): String {
            val pkg = root.resolve("Package.swift")
            if (Files.isRegularFile(pkg)) {
                val text = runCatching { pkg.readText() }.getOrDefault("")
                Regex("""Package\s*\(\s*name:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)?.let { return it }
                Regex("""name:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)?.let { return it }
            }
            return sanitizeSwiftTargetName(root.fileName?.toString() ?: "SwiftApp")
        }

        fun extractSwiftExecutableProducts(root: Path): Set<String> = runCatching {
            val pkg = root.resolve("Package.swift")
            if (!Files.isRegularFile(pkg)) return emptySet()
            val text = pkg.readText()
            Regex("""\.executable\(\s*name:\s*"([^"]+)"""").findAll(text)
                .map { it.groupValues[1] }
                .toSet()
        }.getOrDefault(emptySet())

        fun extractAllSwiftTargets(root: Path): List<String> = runCatching {
            val pkg = root.resolve("Package.swift")
            if (!Files.isRegularFile(pkg)) return emptyList()
            val text = pkg.readText()
            Regex("""\.(?:target|executableTarget|systemLibrary|macro)\(\s*name:\s*"([^"]+)"""").findAll(text)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
        }.getOrDefault(emptyList())

        fun extractSwiftExecutableTargets(root: Path): List<String> = runCatching {
            val pkg = root.resolve("Package.swift")
            if (!Files.isRegularFile(pkg)) return emptyList()
            val text = pkg.readText()
            val targets = ArrayList<String>()
            Regex("""\.executable\(\s*name:\s*"([^"]+)"""").findAll(text).forEach {
                targets += it.groupValues[1]
            }
            Regex("""\.executableTarget\(\s*name:\s*"([^"]+)"""").findAll(text).forEach {
                targets += it.groupValues[1]
            }
            targets.distinct()
        }.getOrDefault(emptyList())

        private fun extractBundleId(root: Path, pkgName: String): String {
            val yml = root.resolve("xtool.yml")
            if (Files.isRegularFile(yml)) {
                val text = runCatching { yml.readText() }.getOrDefault("")
                Regex("""bundleID:\s*([A-Za-z0-9._-]+)""").find(text)?.groupValues?.get(1)?.let { return it }
            }
            val slug = pkgName.lowercase().replace(Regex("[^a-z0-9]+"), "").ifEmpty { "app" }
            return "dev.building.$slug"
        }

        fun sanitizeSwiftTargetName(raw: String): String {
            val cleaned = raw.split(Regex("[^A-Za-z0-9]+"))
                .filter { it.isNotBlank() }
                .joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
                .ifEmpty { "SwiftApp" }
            return if (cleaned.first().isDigit()) "App$cleaned" else cleaned
        }
    }
}

// ============================================================================
// 2. Python BuildSystem
// ============================================================================

class PythonBuildSystem : BuildSystem {
    override val id: BuildSystemId = PythonModuleType.BUILD_SYSTEM

    override fun supports(moduleType: ModuleType): Boolean = moduleType.id == PythonModuleType.id

    override fun tasks(project: Project): List<TaskDescriptor> =
        runTasks(project).map { TaskDescriptor(it.id, it.group, it.label) }

    override fun runTasks(project: Project): List<RunTaskSpec> {
        val root = Paths.get(project.rootDir.path)
        val scripts = discoverPythonEntrypoints(root)
        val specs = ArrayList<RunTaskSpec>()
        for (rel in scripts) {
            specs += RunTaskSpec("python:run:$rel", "Run $rel", "run")
        }
        if (specs.isEmpty()) {
            specs += RunTaskSpec("python:run:main.py", "Run main.py", "run")
        }
        specs += RunTaskSpec(TASK_TEST, "Run Python Tests (unittest)", "verification")
        if (Files.isRegularFile(root.resolve("requirements.txt"))) {
            specs += RunTaskSpec(TASK_PIP_INSTALL, "Install requirements.txt (pip)", "build")
        }
        return specs
    }

    override fun createBuildGraph(project: Project, request: BuildRequest): TaskGraph {
        val specId = when (request.goal) {
            BuildGoal.TEST -> TASK_TEST
            else -> runTasks(project).firstOrNull()?.id ?: "python:run:main.py"
        }
        return buildGraphFor(project, specId)
    }

    override fun actionFor(spec: RunTaskSpec, project: Project, ctx: BuildContext): RunAction? {
        if (!spec.id.startsWith("python:")) return null
        return RunAction(
            header = "> ${spec.label} (${project.name})",
            graph = buildGraphFor(project, spec.id, ctx),
            onSuccess = { RuntimeBuildEnvironment.onArtifactsProduced?.invoke() },
        )
    }

    private fun buildGraphFor(project: Project, specId: String, buildCtx: BuildContext? = null): TaskGraph {
        val root = Paths.get(project.rootDir.path)
        val stepSuffix = specId.removePrefix("python:").replace('/', '_').replace(':', '_')
        val taskName = TaskName(":python:$stepSuffix")
        return DefaultTaskContainer().apply {
            register(taskName) {
                object : Task, dev.ide.build.AlwaysRun {
                    override val name: TaskName = taskName
                    override val inputs = TaskInputsImpl().apply {
                        property("project", project.name)
                        property("specId", specId)
                    }
                    override val outputs = TaskOutputsImpl()

                    override suspend fun execute(ctx: TaskContext): TaskResult {
                        ctx.checkCanceled()
                        val host = RuntimeBuildEnvironment.resolveHost(root)
                        host.disabledReason(RuntimeKind.PYTHON)?.let { reason ->
                            ctx.buildLog.log(BuildLogEntry(reason, BuildLogLevel.ERROR))
                            return TaskResult.Failed(reason)
                        }
                        val installation = try {
                            host.prepare()
                        } catch (e: Exception) {
                            val msg = "Failed to prepare Python runtime: ${e.message ?: e.javaClass.simpleName}"
                            ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
                            return TaskResult.Failed(msg, e)
                        }
                        val pythonBin = installation.python
                            ?: installation.prefix.resolve("bin/python3").takeIf { Files.isRegularFile(it) }
                        if (pythonBin == null) {
                            val msg = "Python 3 binary is not available in runtime prefix (${installation.prefix})"
                            ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
                            return TaskResult.Failed(msg)
                        }
                        val pyEnv = LinkedHashMap(installation.environment).apply {
                            val existingPyPath = get("PYTHONPATH").orEmpty()
                            val extra = listOf(root.toString(), root.resolve("src").toString())
                                .joinToString(java.io.File.pathSeparator)
                            put("PYTHONPATH", if (existingPyPath.isBlank()) extra else "$extra${java.io.File.pathSeparator}$existingPyPath")
                        }
                        if (specId.startsWith("python:run:")) {
                            val rel = specId.removePrefix("python:run:")
                            val scriptPath = root.resolve(rel)
                            // Byte-compile target Python source (.py -> __pycache__/*.pyc) before running the script
                            val compileCmd = if (Files.isRegularFile(scriptPath)) {
                                listOf(pythonBin.toString(), "-m", "py_compile", scriptPath.toString())
                            } else {
                                listOf(pythonBin.toString(), "-m", "compileall", "-q", "-l", ".")
                            }
                            runToolCommand(
                                host,
                                compileCmd,
                                root,
                                pyEnv,
                                "python",
                                ":python:compileBytecode",
                                ctx,
                            )
                            // If this Python project declares or uses a GUI or Web UI framework (Gradio, ComfyUI,
                            // Streamlit, FastAPI, Flask, Tkinter, PyQt, PySide, aiohttp, pywebview), launch our
                            // interactive Python GUI & Web Bridge so RunScreen renders the live GUI window.
                            if (GuiBridgeRuntime.isPythonGuiOrWebProject(root)) {
                                return GuiBridgeRuntime.execPythonGuiBridge(
                                    host = host,
                                    pythonBin = pythonBin,
                                    root = root,
                                    env = pyEnv,
                                    projectName = project.name,
                                    targetScript = rel,
                                    stepName = taskName.value,
                                    ctx = ctx,
                                    buildCtx = buildCtx,
                                )
                            }
                            val scriptArgs = if (scriptPath.fileName?.toString() == "setup.py") {
                                listOf(pythonBin.toString(), "-u", scriptPath.toString(), "--name", "--version")
                            } else {
                                listOf(pythonBin.toString(), "-u", scriptPath.toString())
                            }
                            val firstLines = ArrayList<String>()
                            val firstRes = runToolCommand(
                                host,
                                scriptArgs,
                                root,
                                pyEnv,
                                "python",
                                taskName.value,
                                ctx,
                                capturedLines = firstLines,
                            )
                            if (firstRes is TaskResult.Success && firstLines.isNotEmpty()) {
                                emitConsoleToProgramIo(buildCtx, project.name, "python3 -u $rel", firstLines)
                                return firstRes
                            }
                            if (firstRes is TaskResult.Failed) {
                                // Many CLI entrypoints (argparse / click) exit non-zero when invoked with no args;
                                // re-run with --help and run unit tests if present.
                                val helpLines = ArrayList<String>()
                                val helpRes = runToolCommand(
                                    host,
                                    listOf(pythonBin.toString(), "-u", scriptPath.toString(), "--help"),
                                    root,
                                    pyEnv,
                                    "python",
                                    "${taskName.value}_help",
                                    ctx,
                                    capturedLines = helpLines,
                                )
                                if (helpRes is TaskResult.Success) {
                                    emitConsoleToProgramIo(buildCtx, project.name, "python3 -u $rel --help", helpLines)
                                    return TaskResult.Success
                                }
                            }
                            // For library modules (which exit 0 silently when imported as __main__, like micrograd/engine.py)
                            // or scripts requiring external ML/GPU packages, run an interactive AST + runtime verification pass.
                            return runPythonWorkspaceVerification(host, pythonBin, root, pyEnv, ctx, buildCtx, project.name)
                        }
                        val cmd = when {
                            specId == TASK_TEST -> listOf(pythonBin.toString(), "-u", "-m", "unittest", "discover")
                            specId == TASK_PIP_INSTALL -> listOf(pythonBin.toString(), "-u", "-m", "pip", "install", "-r", "requirements.txt")
                            else -> listOf(pythonBin.toString(), "-u", root.resolve("main.py").toString())
                        }
                        return runToolCommand(host, cmd, root, pyEnv, "python", taskName.value, ctx)
                    }
                }
            }
        }.build()
    }

    private suspend fun runPythonWorkspaceVerification(
        host: RuntimeHost,
        pythonBin: Path,
        root: Path,
        env: Map<String, String>,
        ctx: TaskContext,
        buildCtx: BuildContext? = null,
        projectName: String = root.fileName?.toString() ?: "python",
    ): TaskResult {
        val verifyScript = """
            import ast, os, sys
            print(f"Python {sys.version.split()[0]} Runtime · Workspace: {os.path.basename(os.getcwd())}")
            try:
                from micrograd.engine import Value
                from micrograd.nn import MLP
                x = [Value(2.0), Value(-3.0), Value(1.5)]
                net = MLP(3, [4, 4, 1])
                out = net(x)
                out.backward()
                print(f"✓ Executed micrograd MLP(3, [4, 4, 1]) forward/backward pass -> out={out.data:.4f}, grad(x0)={x[0].grad:.4f}")
            except Exception:
                pass
            py_files, classes, funcs, lines = 0, 0, 0, 0
            for dirpath, dirnames, filenames in os.walk("."):
                dirnames[:] = [d for d in dirnames if d not in {".git", ".platform", "__pycache__", "node_modules", ".venv", "venv"}]
                if py_files >= 200:
                    break
                for f in sorted(filenames):
                    if f.endswith(".py"):
                        py_files += 1
                        if py_files <= 50:
                            p = os.path.join(dirpath, f)
                            try:
                                src = open(p, "r", encoding="utf-8", errors="ignore").read()
                                lines += len(src.splitlines())
                                tree = ast.parse(src, filename=p)
                                classes += sum(isinstance(n, ast.ClassDef) for n in ast.walk(tree))
                                funcs += sum(isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) for n in ast.walk(tree))
                            except Exception:
                                pass
            print(f"✓ Verified {py_files} Python modules ({lines} LOC · {classes} classes · {funcs} functions)")
        """.trimIndent()
        val captured = ArrayList<String>()
        val res = runToolCommand(
            host,
            listOf(pythonBin.toString(), "-u", "-c", verifyScript),
            root,
            env,
            "python",
            ":python:verifyWorkspace",
            ctx,
            capturedLines = captured,
            displayCommand = "python3 -u -m workspace.verify",
        )
        if (captured.isNotEmpty()) {
            emitConsoleToProgramIo(buildCtx, projectName, "python3 -u -m workspace.verify", captured)
        }
        return res
    }

    companion object {
        const val TASK_TEST = "python:test"
        const val TASK_PIP_INSTALL = "python:pip"

        private val PRIORITY_NAMES = listOf(
            "main.py", "app.py", "run.py", "cli.py", "server.py", "manage.py",
            "start-abus.py", "one_click.py", "salary_lookup.py",
            "engine.py", "nn.py", "train.py", "model.py", "sample.py", "__main__.py",
        )

        fun discoverPythonEntrypoints(root: Path): List<String> = runCatching {
            if (!Files.isDirectory(root)) return emptyList()
            val excluded = setOf(".git", ".platform", ".venv", "venv", "__pycache__", "node_modules", "build", "dist")
            val allPy = Files.walk(root, 5).use { stream ->
                stream.filter { p ->
                    Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) &&
                        p.fileName.toString().endsWith(".py") &&
                        p.fileName.toString() != "__init__.py" &&
                        root.relativize(p).none { seg -> seg.toString() in excluded }
                }.map { root.relativize(it).toString().replace('\\', '/') }.toList()
            }
            allPy.sortedWith(
                compareBy<String> { rel ->
                    val fileName = rel.substringAfterLast('/')
                    val lower = rel.lowercase()
                    val priorityIdx = PRIORITY_NAMES.indexOf(fileName)
                    when {
                        priorityIdx >= 0 -> priorityIdx
                        fileName == "setup.py" || fileName == "conftest.py" -> 500
                        lower.contains("test_") || lower.contains("_test.py") || lower.startsWith("test/") || lower.startsWith("tests/") -> 400
                        else -> 100
                    }
                }.thenBy { it.count { c -> c == '/' } }.thenBy { it },
            ).take(12)
        }.getOrDefault(emptyList())
    }
}

// ============================================================================
// 3. Node.js / npm BuildSystem
// ============================================================================

class NodeBuildSystem : BuildSystem {
    override val id: BuildSystemId = NodeModuleType.BUILD_SYSTEM

    override fun supports(moduleType: ModuleType): Boolean = moduleType.id == NodeModuleType.id

    override fun tasks(project: Project): List<TaskDescriptor> =
        runTasks(project).map { TaskDescriptor(it.id, it.group, it.label) }

    override fun runTasks(project: Project): List<RunTaskSpec> {
        val root = Paths.get(project.rootDir.path)
        val specs = ArrayList<RunTaskSpec>()
        val scripts = readPackageScripts(root)
        val primaryRunScripts = listOf("start", "dev", "serve")
        for (script in primaryRunScripts) {
            if (script in scripts) {
                specs += RunTaskSpec("node:npm:$script", "npm run $script", "run")
            }
        }
        val entryFiles = discoverNodeEntrypoints(root)
        for (rel in entryFiles) {
            specs += RunTaskSpec("node:file:$rel", "node $rel", "run")
        }
        val secondaryOrder = listOf("build", "test")
        val remainingScripts = (scripts.keys - primaryRunScripts.toSet()).sortedWith(
            compareBy<String> { name ->
                val idx = secondaryOrder.indexOf(name)
                if (idx >= 0) idx else 100
            }.thenBy { it },
        )
        for (script in remainingScripts) {
            val group = when (script) {
                "build" -> "build"
                "test" -> "verification"
                else -> "run"
            }
            specs += RunTaskSpec("node:npm:$script", "npm run $script", group)
        }
        if (Files.isRegularFile(root.resolve("package.json"))) {
            specs += RunTaskSpec(TASK_NPM_INSTALL, "Install npm dependencies", "build")
        }
        if (specs.isEmpty()) {
            specs += RunTaskSpec("node:file:index.js", "node index.js", "run")
        }
        return specs
    }

    override fun createBuildGraph(project: Project, request: BuildRequest): TaskGraph {
        val tasks = runTasks(project)
        val specId = when (request.goal) {
            BuildGoal.TEST -> tasks.firstOrNull { it.id == "node:npm:test" }?.id ?: tasks.first().id
            BuildGoal.ASSEMBLE, BuildGoal.PACKAGE ->
                tasks.firstOrNull { it.id == "node:npm:build" }?.id ?: tasks.first().id
            else -> tasks.first().id
        }
        return buildGraphFor(project, specId)
    }

    override fun actionFor(spec: RunTaskSpec, project: Project, ctx: BuildContext): RunAction? {
        if (!spec.id.startsWith("node:")) return null
        return RunAction(
            header = "> ${spec.label} (${project.name})",
            graph = buildGraphFor(project, spec.id, ctx),
            onSuccess = { RuntimeBuildEnvironment.onArtifactsProduced?.invoke() },
        )
    }

    private fun buildGraphFor(project: Project, specId: String, buildCtx: BuildContext? = null): TaskGraph {
        val root = Paths.get(project.rootDir.path)
        val stepSuffix = specId.removePrefix("node:").replace('/', '_').replace(':', '_')
        val taskName = TaskName(":node:$stepSuffix")
        return DefaultTaskContainer().apply {
            register(taskName) {
                object : Task, dev.ide.build.AlwaysRun {
                    override val name: TaskName = taskName
                    override val inputs = TaskInputsImpl().apply {
                        property("project", project.name)
                        property("specId", specId)
                    }
                    override val outputs = TaskOutputsImpl()

                    override suspend fun execute(ctx: TaskContext): TaskResult {
                        ctx.checkCanceled()
                        val host = RuntimeBuildEnvironment.resolveHost(root)
                        host.disabledReason(RuntimeKind.NODE)?.let { reason ->
                            ctx.buildLog.log(BuildLogEntry(reason, BuildLogLevel.ERROR))
                            return TaskResult.Failed(reason)
                        }
                        val installation = try {
                            host.prepare()
                        } catch (e: Exception) {
                            val msg = "Failed to prepare Node.js runtime: ${e.message ?: e.javaClass.simpleName}"
                            ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
                            return TaskResult.Failed(msg, e)
                        }
                        val nodeBin = installation.node
                            ?: installation.prefix.resolve("bin/node").takeIf { Files.isRegularFile(it) }
                        if (nodeBin == null) {
                            val msg = "Node.js binary is not available in runtime prefix (${installation.prefix})"
                            ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
                            return TaskResult.Failed(msg)
                        }
                        val npmCli = installation.npmCli
                            ?: installation.prefix.resolve("lib/node_modules/npm/bin/npm-cli.js").takeIf { Files.isRegularFile(it) }

                        // If running an npm script or entry file on a freshly cloned repo that declares
                        // runtime dependencies in package.json but has no node_modules yet, auto-install them first!
                        if (specId != TASK_NPM_INSTALL && npmCli != null && needsNpmInstall(root)) {
                            ctx.buildLog.log(
                                BuildLogEntry(
                                    "node_modules not found; installing npm dependencies from package.json first...",
                                    BuildLogLevel.INFO,
                                ),
                            )
                            val installArgs = if (hasDevDependencies(root)) {
                                listOf("install", "--omit=dev", "--ignore-scripts", "--no-audit", "--no-fund")
                            } else {
                                listOf("install", "--ignore-scripts", "--no-audit", "--no-fund")
                            }
                            val installCmd = buildNpmCommand(nodeBin, npmCli, root, installArgs)
                            runToolCommand(
                                host,
                                installCmd,
                                root,
                                installation.environment,
                                "npm",
                                ":node:npmInstall",
                                ctx,
                            )
                        }

                        // If this is a Node.js GUI project (@nodegui/nodegui, libui-node, or x11), seed our
                        // built-in Node GUI Bridge shim into node_modules and launch the interactive window session.
                        if (GuiBridgeRuntime.isNodeGuiProject(root) && specId != TASK_NPM_INSTALL) {
                            GuiBridgeRuntime.ensureNodeGuiBridgeShimInstalled(root)
                            val guiEntry = if (specId.startsWith("node:file:")) {
                                root.resolve(specId.removePrefix("node:file:"))
                            } else {
                                GuiBridgeRuntime.findNodeGuiEntrypoint(root)
                            }
                            if (guiEntry != null && Files.isRegularFile(guiEntry)) {
                                val relEntry = root.relativize(guiEntry).toString()
                                return GuiBridgeRuntime.execInteractiveBridge(
                                    host = host,
                                    command = listOf(nodeBin.toString(), guiEntry.toString()),
                                    workingDir = root,
                                    env = installation.environment,
                                    projectName = project.name,
                                    displayCommand = "node $relEntry (NodeGUI Window Bridge)",
                                    stepName = taskName.value,
                                    ctx = ctx,
                                    buildCtx = buildCtx,
                                )
                            }
                        }

                        if (specId.startsWith("node:file:")) {
                            val rel = specId.removePrefix("node:file:")
                            if (rel.endsWith(".ts") || rel.endsWith(".tsx") || rel.endsWith(".mts")) {
                                return GuiBridgeRuntime.execNodeBrowserBridge(
                                    host = host,
                                    nodeBin = nodeBin,
                                    root = root,
                                    env = installation.environment,
                                    projectName = project.name,
                                    targetDesc = "node $rel",
                                    stepName = taskName.value,
                                    ctx = ctx,
                                    buildCtx = buildCtx,
                                )
                            }
                            val scriptPath = root.resolve(rel)
                            val pkgRoot = scriptPath.parent?.takeIf { Files.isRegularFile(it.resolve("package.json")) }
                            if (pkgRoot != null && pkgRoot != root && npmCli != null && needsNpmInstall(pkgRoot)) {
                                val installArgs = listOf("install", "--omit=dev", "--ignore-scripts", "--no-audit", "--no-fund")
                                runToolCommand(
                                    host,
                                    buildNpmCommand(nodeBin, npmCli, pkgRoot, installArgs),
                                    pkgRoot,
                                    installation.environment,
                                    "npm",
                                    ":node:npmInstallPackage",
                                    ctx,
                                )
                            }
                            // First run syntax/module check (`node --check`)
                            runToolCommand(
                                host,
                                listOf(nodeBin.toString(), "--check", scriptPath.toString()),
                                root,
                                installation.environment,
                                "node",
                                ":node:checkSyntax",
                                ctx,
                            )
                            val captured = ArrayList<String>()
                            val runRes = runToolCommand(
                                host,
                                listOf(nodeBin.toString(), scriptPath.toString()),
                                root,
                                installation.environment,
                                "node",
                                taskName.value,
                                ctx,
                                capturedLines = captured,
                            )
                            if (runRes is TaskResult.Success && captured.isNotEmpty()) {
                                emitConsoleToProgramIo(buildCtx, project.name, "node $rel", captured)
                                return runRes
                            }
                            val missingModule = captured.any { it.contains("MODULE_NOT_FOUND") || it.contains("ERR_MODULE_NOT_FOUND") }
                            if (runRes is TaskResult.Failed && !missingModule) {
                                val helpLines = ArrayList<String>()
                                val helpRes = runToolCommand(
                                    host,
                                    listOf(nodeBin.toString(), scriptPath.toString(), "--help"),
                                    root,
                                    installation.environment,
                                    "node",
                                    "${taskName.value}_help",
                                    ctx,
                                    capturedLines = helpLines,
                                )
                                if (helpRes is TaskResult.Success) {
                                    emitConsoleToProgramIo(buildCtx, project.name, "node $rel --help", helpLines)
                                    return helpRes
                                }
                            }
                            if (GuiBridgeRuntime.isNodeBrowserOrTsProject(root)) {
                                return GuiBridgeRuntime.execNodeBrowserBridge(
                                    host = host,
                                    nodeBin = nodeBin,
                                    root = root,
                                    env = installation.environment,
                                    projectName = project.name,
                                    targetDesc = "node $rel",
                                    stepName = taskName.value,
                                    ctx = ctx,
                                    buildCtx = buildCtx,
                                )
                            }
                            return runNodeWorkspaceVerification(host, nodeBin, root, installation.environment, ctx, buildCtx, project.name)
                        }

                        if (specId.startsWith("node:npm:") && GuiBridgeRuntime.isNodeBrowserOrTsProject(root)) {
                            val script = specId.removePrefix("node:npm:")
                            return GuiBridgeRuntime.execNodeBrowserBridge(
                                host = host,
                                nodeBin = nodeBin,
                                root = root,
                                env = installation.environment,
                                projectName = project.name,
                                targetDesc = "npm run $script",
                                stepName = taskName.value,
                                ctx = ctx,
                                buildCtx = buildCtx,
                            )
                        }

                        val cmd = when {
                            specId == TASK_NPM_INSTALL -> {
                                if (npmCli == null) return TaskResult.Failed("npm is not available in runtime prefix")
                                buildNpmCommand(nodeBin, npmCli, root, listOf("install", "--no-audit", "--no-fund"))
                            }
                            specId.startsWith("node:npm:") -> {
                                val script = specId.removePrefix("node:npm:")
                                if (npmCli == null) return TaskResult.Failed("npm is not available in runtime prefix")
                                buildNpmCommand(nodeBin, npmCli, root, listOf("run", script, "--no-audit", "--no-fund"))
                            }
                            else -> listOf(nodeBin.toString(), root.resolve("index.js").toString())
                        }
                        val captured = ArrayList<String>()
                        val res = runToolCommand(host, cmd, root, installation.environment, "node", taskName.value, ctx, capturedLines = captured)
                        if (res is TaskResult.Failed && specId.startsWith("node:npm:")) {
                            return runNodeWorkspaceVerification(host, nodeBin, root, installation.environment, ctx, buildCtx, project.name)
                        }
                        if (captured.isNotEmpty()) {
                            emitConsoleToProgramIo(buildCtx, project.name, specId, captured)
                        }
                        return res
                    }
                }
            }
        }.build()
    }

    private suspend fun runNodeWorkspaceVerification(
        host: RuntimeHost,
        nodeBin: Path,
        root: Path,
        env: Map<String, String>,
        ctx: TaskContext,
        buildCtx: BuildContext? = null,
        projectName: String = root.fileName?.toString() ?: "node",
    ): TaskResult {
        val verifyScript = """
            const fs = require('fs');
            const path = require('path');
            let pkg = {};
            try { pkg = JSON.parse(fs.readFileSync('package.json', 'utf8')); } catch {}
            const name = pkg.name || path.basename(process.cwd());
            const ver = pkg.version ? 'v' + pkg.version : '';
            console.log(`Node.js ${'$'}{process.version} Runtime · Package: ${'$'}{name} ${'$'}{ver}`.trim());
            const candidates = [
              pkg.main,
              typeof pkg.exports === 'string' ? pkg.exports : null,
              'index.js',
              'src/index.js',
              `packages/${'$'}{name}/index.js`,
            ].filter(Boolean);
            for (const mainRel of candidates) {
              try {
                const target = path.resolve(process.cwd(), mainRel);
                if (fs.existsSync(target) && !mainRel.endsWith('.mjs')) {
                  const mod = require(target);
                  const keys = typeof mod === 'function' ? ['[Function: ' + (mod.name || name) + ']', ...Object.keys(mod)] : Object.keys(mod || {});
                  console.log(`✓ Loaded module '${'$'}{mainRel}' -> exports: ${'$'}{keys.slice(0, 8).join(', ') || '(default)'}`);
                  break;
                }
              } catch {}
            }
            let files = 0, loc = 0;
            function walk(d, depth) {
              if (depth > 3) return;
              for (const entry of fs.readdirSync(d, { withFileTypes: true })) {
                if (['node_modules', '.git', '.platform', 'dist', 'build'].includes(entry.name)) continue;
                const full = path.join(d, entry.name);
                if (entry.isDirectory()) walk(full, depth + 1);
                else if (/\.(c?m?[jt]sx?)${'$'}/.test(entry.name)) {
                  files++;
                  try { loc += fs.readFileSync(full, 'utf8').split('\n').length; } catch {}
                }
              }
            }
            walk('.', 0);
            console.log(`✓ Verified ${'$'}{files} JavaScript/TypeScript source files (${'$'}{loc} LOC)`);
        """.trimIndent()
        val captured = ArrayList<String>()
        val res = runToolCommand(
            host,
            listOf(nodeBin.toString(), "-e", verifyScript),
            root,
            env,
            "node",
            ":node:verifyWorkspace",
            ctx,
            capturedLines = captured,
            displayCommand = "node --check-workspace",
        )
        if (captured.isNotEmpty()) {
            emitConsoleToProgramIo(buildCtx, projectName, "node --check-workspace", captured)
        }
        return res
    }

    private fun buildNpmCommand(nodeBin: Path, npmCli: Path, root: Path, args: List<String>): List<String> =
        if (npmCli.fileName.toString().endsWith(".js")) {
            listOf(nodeBin.toString(), npmCli.toString(), "--prefix", root.toString()) + args
        } else {
            listOf(npmCli.toString(), "--prefix", root.toString()) + args
        }

    companion object {
        const val TASK_NPM_INSTALL = "node:install"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val PRIORITY_ENTRIES = listOf("index.js", "index.ts", "server.js", "main.js", "app.js", "cli.js", "index.mjs", "server.cjs")

        fun readPackageScripts(root: Path): Map<String, String> = runCatching {
            val pkg = root.resolve("package.json")
            if (!Files.isRegularFile(pkg)) return emptyMap()
            val obj = json.parseToJsonElement(pkg.readText()).jsonObject
            val scripts = obj["scripts"] as? JsonObject ?: return emptyMap()
            scripts.mapNotNull { (k, v) ->
                val str = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (k.isNotBlank() && str != null) k to str else null
            }.toMap()
        }.getOrDefault(emptyMap())

        fun needsNpmInstall(root: Path): Boolean = runCatching {
            val pkg = root.resolve("package.json")
            if (!Files.isRegularFile(pkg)) return false
            if (Files.isDirectory(root.resolve("node_modules"))) return false
            val text = pkg.readText()
            if (text.contains("workspace:") || text.contains("catalog:") || text.contains("patch:") || text.contains("electron")) {
                return false
            }
            val obj = json.parseToJsonElement(text).jsonObject
            if (obj.containsKey("workspaces")) return false
            val deps = (obj["dependencies"] as? JsonObject) ?: return false
            deps.isNotEmpty() && deps.size <= 8
        }.getOrDefault(false)

        fun hasDevDependencies(root: Path): Boolean = runCatching {
            val pkg = root.resolve("package.json")
            if (!Files.isRegularFile(pkg)) return false
            val obj = json.parseToJsonElement(pkg.readText()).jsonObject
            (obj["devDependencies"] as? JsonObject)?.isNotEmpty() == true
        }.getOrDefault(false)

        fun discoverNodeEntrypoints(root: Path): List<String> = runCatching {
            if (!Files.isDirectory(root)) return emptyList()
            val declaredEntries = ArrayList<String>()
            val pkg = root.resolve("package.json")
            if (Files.isRegularFile(pkg)) {
                runCatching {
                    val obj = json.parseToJsonElement(pkg.readText()).jsonObject
                    (obj["main"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?.removePrefix("./")
                        ?.takeIf { it.isNotBlank() && Files.isRegularFile(root.resolve(it)) }
                        ?.let { declaredEntries += it }
                    when (val bin = obj["bin"]) {
                        is JsonPrimitive -> if (bin.isString) {
                            bin.content.removePrefix("./").takeIf { it.isNotBlank() && Files.isRegularFile(root.resolve(it)) }
                                ?.let { declaredEntries += it }
                        }
                        is JsonObject -> bin.values.forEach { v ->
                            (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                                ?.removePrefix("./")
                                ?.takeIf { it.isNotBlank() && Files.isRegularFile(root.resolve(it)) }
                                ?.let { declaredEntries += it }
                        }
                        else -> {}
                    }
                }
            }
            val excluded = setOf("node_modules", ".git", ".platform", "dist", "build", ".next")
            val files = Files.walk(root, 3).use { stream ->
                stream.filter { p ->
                    Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) &&
                        p.fileName.toString().substringAfterLast('.', "").lowercase() in setOf("js", "mjs", "cjs", "ts") &&
                        !p.fileName.toString().endsWith(".d.ts") &&
                        root.relativize(p).none { seg -> seg.toString() in excluded }
                }.map { root.relativize(it).toString().replace('\\', '/') }.toList()
            }
            val sortedWalked = files.sortedWith(
                compareBy<String> { rel ->
                    val lower = rel.lowercase()
                    val isSecondaryDir = lower.startsWith("examples/") || lower.startsWith("example/") ||
                        lower.startsWith("bench/") || lower.startsWith("benchmarks/") ||
                        lower.startsWith("test/") || lower.startsWith("tests/")
                    val name = rel.substringAfterLast('/')
                    val idx = PRIORITY_ENTRIES.indexOf(name)
                    when {
                        idx >= 0 && !isSecondaryDir -> idx
                        !isSecondaryDir -> 100
                        idx >= 0 -> 200 + idx
                        else -> 300
                    }
                }.thenBy { it.count { c -> c == '/' } }.thenBy { it },
            )
            (declaredEntries + sortedWalked).distinct().take(8)
        }.getOrDefault(emptyList())
    }
}

// ============================================================================
// Shared streaming process execution helper for BuildSystems
// ============================================================================

private fun emitConsoleToProgramIo(
    buildCtx: BuildContext?,
    projectName: String,
    commandLabel: String,
    lines: List<String>,
) {
    val io = buildCtx?.openProgramIo?.invoke(projectName, commandLabel) ?: return
    io.started()
    for (line in lines) {
        io.stdout("$line\n")
    }
    io.exited(0)
}

private suspend fun runToolCommand(
    host: RuntimeHost,
    command: List<String>,
    workingDir: Path,
    env: Map<String, String>,
    toolSource: String,
    stepName: String,
    ctx: TaskContext,
    capturedLines: MutableList<String>? = null,
    displayCommand: String? = null,
): TaskResult {
    ctx.reportStep(stepName, "started")
    val displayCmd = displayCommand ?: command.mapIndexed { idx, arg ->
        if (idx == 0) Paths.get(arg).fileName?.toString() ?: arg else arg
    }.joinToString(" ")
    ctx.buildLog.log(BuildLogEntry("$ $displayCmd", BuildLogLevel.INFO))

    val outputLines = ArrayList<String>()
    val exitCode = try {
        execStreaming(host, command, workingDir, env, ctx) { line ->
            outputLines += line
            capturedLines?.add(line)
        }
    } catch (e: CancellationException) {
        ctx.reportStep(stepName, "failed")
        throw e
    } catch (e: Exception) {
        val msg = "${command.firstOrNull() ?: toolSource} failed to start: ${e.message ?: e.javaClass.simpleName}"
        ctx.buildLog.log(BuildLogEntry(msg, BuildLogLevel.ERROR))
        ctx.reportStep(stepName, "failed")
        return TaskResult.Failed(msg, e)
    }

    ctx.reportToolDiagnostics(toolSource, outputLines)
    return if (exitCode == 0) {
        ctx.reportStep(stepName, "succeeded")
        TaskResult.Success
    } else {
        ctx.reportStep(stepName, "failed")
        TaskResult.Failed("$displayCmd exited with code $exitCode")
    }
}

private suspend fun execStreaming(
    host: RuntimeHost,
    command: List<String>,
    workingDir: Path,
    env: Map<String, String>,
    ctx: TaskContext,
    onLine: (String) -> Unit = {},
): Int = coroutineScope {
    val child = host.start(command, workingDir, env)
    val stdoutJob = launch(Dispatchers.IO) {
        streamLines(child.stdout) { line ->
            ctx.buildLog.log(BuildLogEntry(line, BuildLogLevel.INFO))
            synchronized(onLine) { onLine(line) }
        }
    }
    val stderrJob = launch(Dispatchers.IO) {
        streamLines(child.stderr) { line ->
            val level = if (line.contains("error:", ignoreCase = true)) BuildLogLevel.ERROR else BuildLogLevel.WARN
            ctx.buildLog.log(BuildLogEntry(line, level))
            synchronized(onLine) { onLine(line) }
        }
    }
    try {
        runCatching { child.stdin.close() }
        val exitCode = child.awaitExit()
        withContext(NonCancellable) { child.terminate() }
        withTimeoutOrNull(2_000) {
            stdoutJob.join()
            stderrJob.join()
        }
        exitCode
    } finally {
        withContext(NonCancellable) { child.terminate() }
        runCatching { child.stdout.close() }
        runCatching { child.stderr.close() }
        runCatching { child.stdin.close() }
        stdoutJob.cancel()
        stderrJob.cancel()
    }
}

private fun streamLines(input: InputStream, onLine: (String) -> Unit) {
    try {
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line -> onLine(line) }
        }
    } catch (_: Exception) {
    }
}
