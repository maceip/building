package dev.ide.gradle

import dev.ide.build.AlwaysRun
import dev.ide.build.BuildContext
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.BuildSystem
import dev.ide.build.RunAction
import dev.ide.build.RunTaskSpec
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskDescriptor
import dev.ide.build.TaskGraph
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.build.engine.CompilerOutputParser
import dev.ide.build.engine.DefaultTaskContainer
import dev.ide.model.BuildSystemId
import dev.ide.model.ModuleType
import dev.ide.model.Project
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * One Gradle invocation is one session task. Gradle's own up-to-date checks decide what runs;
 * this task is [AlwaysRun] so the engine does not skip it, and [TaskContext.reportStep] feeds
 * the Steps list without a synthetic task per Gradle task.
 *
 * Contributed [dev.ide.build.BuildPlugin]s are not applied. A project bound to Gradle is built
 * by AGP, including `externalNativeBuild`.
 */
class GradleBuildSystem : BuildSystem {

    override val id: BuildSystemId = BuildSystemId.GRADLE

    override fun supports(moduleType: ModuleType): Boolean = false

    override fun tasks(project: Project): List<TaskDescriptor> = listOf(
        TaskDescriptor("assemble", "build", "Assemble with Gradle (without installing)"),
        TaskDescriptor("test", "verification", "Run Gradle unit tests"),
        TaskDescriptor("lint", "verification", "Run Android lint with Gradle"),
        TaskDescriptor("bundle", "build", "Build an Android App Bundle with Gradle"),
        TaskDescriptor("clean", "build", "Clean Gradle outputs"),
    )

    /**
     * [TASK_ID] stays first and keeps its historical Run behavior. All other rows encode their intent:
     * `gradle:build/test/lint/bundle` never install. Qualified IDs append the Gradle module path (without
     * its leading colon) and the variant, e.g. `gradle:bundle:app:android:release`. A root module uses an
     * empty path (`gradle:build::main`). Callers should enumerate these IDs, not infer variants from facets.
     */
    override fun runTasks(project: Project): List<RunTaskSpec> = options(project).map { it.spec }

    override fun actionFor(spec: RunTaskSpec, project: Project, ctx: BuildContext): RunAction? {
        val option = options(project).firstOrNull { it.spec.id == spec.id } ?: return null
        return action(Path.of(project.rootDir.path), option.goal, option.targets, option.runAfterBuild)
    }

    /** The host's legacy per-module rows retain their exact goal/variant and opt into Run explicitly. */
    fun actionFor(project: Project, request: BuildRequest, runAfterBuild: Boolean = false): RunAction =
        action(Path.of(project.rootDir.path), request.goal, targets(project, request), runAfterBuild)

    override fun createBuildGraph(project: Project, request: BuildRequest): TaskGraph =
        graph(GradleSessionTask(Path.of(project.rootDir.path), request.goal, targets(project, request)))

    override fun createBuildGraph(project: Project, request: BuildRequest, ctx: BuildContext): TaskGraph =
        createBuildGraph(project, request)

    private fun action(root: Path, goal: BuildGoal, targets: List<GradleTarget>, runAfterBuild: Boolean): RunAction {
        require(!runAfterBuild || goal == BuildGoal.ASSEMBLE) { "Only an assemble Run can install an APK." }
        val session = GradleSessionTask(root, goal, targets)
        return RunAction(
            header = "> gradle ${session.requestedTasks.joinToString(" ")}",
            graph = graph(session),
            // Build-only intent is part of the action, never a temporary change to the global installer.
            // Artifacts/reports are logged by the session, also for createBuildGraph callers.
            onSuccess = if (runAfterBuild) { log ->
                val apk = session.apk
                val applicationId = session.applicationId
                val install = GradleEnvironment.installAndLaunch
                if (apk != null && applicationId != null && install != null) install(apk, applicationId, log)
            } else null,
        )
    }

    private fun graph(session: GradleSessionTask): TaskGraph = DefaultTaskContainer().apply {
        register(session.name) { session }
    }.build()

    private data class Option(
        val spec: RunTaskSpec,
        val goal: BuildGoal,
        val targets: List<GradleTarget>,
        val runAfterBuild: Boolean = false,
    )

    private fun options(project: Project): List<Option> {
        val snapshot = GradleProjectSnapshot.load(Path.of(project.rootDir.path))
        val all = snapshot?.targets().orEmpty()
        val chosen = choose(all)
        return buildList {
            val legacyLabel = when {
                chosen == null -> "Build with Gradle"
                chosen.choice.variant.application -> "Run ${chosen.choice.variant.name}"
                else -> chosen.choice.variant.assembleTask
            }
            add(Option(RunTaskSpec(TASK_ID, legacyLabel, if (chosen?.choice?.variant?.application == true) "android" else "build"),
                BuildGoal.ASSEMBLE, listOfNotNull(chosen), runAfterBuild = true))
            for ((taskId, goal, label) in listOf(
                Triple(BUILD_TASK_ID, BuildGoal.ASSEMBLE, "Build with Gradle"),
                Triple(TEST_TASK_ID, BuildGoal.TEST, "Test with Gradle"),
                Triple(LINT_TASK_ID, BuildGoal.LINT, "Lint with Gradle"),
                Triple(BUNDLE_TASK_ID, BuildGoal.BUNDLE, "Bundle with Gradle (.aab)"),
            )) {
                val eligible = all.filter { eligible(it, goal) }
                if (snapshot != null && eligible.isEmpty() && goal in setOf(BuildGoal.LINT, BuildGoal.BUNDLE)) continue
                add(Option(RunTaskSpec(taskId, label, "build"), goal, listOfNotNull(choose(eligible))))
                for (target in eligible) {
                    val variant = target.choice.variant
                    val suffix = "${variant.modulePath.removePrefix(":")}:${variant.name}"
                    val title = "${label.substringBefore(" with")} ${variant.name} · ${variant.modulePath}"
                    add(Option(RunTaskSpec("$taskId:$suffix", title, "build"), goal, listOf(target)))
                }
            }
            for (target in all.filter { it.choice.variant.application }) {
                val variant = target.choice.variant
                add(Option(
                    RunTaskSpec("gradle:run:${variant.modulePath.removePrefix(":")}:${variant.name}",
                        "Run ${variant.name} · ${variant.modulePath}", "android"),
                    BuildGoal.ASSEMBLE, listOf(target), runAfterBuild = true,
                ))
            }
        }
    }

    private fun GradleProjectSnapshot.targets(): List<GradleTarget> = variants().map { variant ->
        GradleTarget(
            VariantChoice.Chosen(variant, VariantChoice.taskPath(variant.modulePath, variant.assembleTask)),
            android = variant.application || module(variant.modulePath)?.plugins.orEmpty().any { it.startsWith("com.android.") },
        )
    }

    private fun choose(targets: List<GradleTarget>): GradleTarget? {
        val chosen = VariantChoice.choose(targets.map { it.choice.variant }) ?: return null
        return targets.first { it.choice == chosen }
    }

    private fun eligible(target: GradleTarget, goal: BuildGoal): Boolean = when (goal) {
        BuildGoal.BUNDLE -> target.choice.variant.application
        BuildGoal.LINT -> target.android
        else -> true
    }

    private fun targets(project: Project, request: BuildRequest): List<GradleTarget> {
        if (request.goal == BuildGoal.CLEAN && request.targets.isEmpty()) return emptyList()
        val root = Path.of(project.rootDir.path).toAbsolutePath().normalize()
        val snapshot = GradleProjectSnapshot.load(root)
        if (snapshot == null) {
            require(request.targets.isEmpty() && request.variant.name in setOf("", "main")) {
                "Sync this Gradle project before selecting a module and variant."
            }
            return emptyList()
        }
        val all = snapshot.targets()
        val requestedVariant = request.variant.name
        fun select(candidates: List<GradleTarget>): GradleTarget {
            val matching = candidates.filter { eligible(it, request.goal) }
                .filter { requestedVariant.isBlank() || it.choice.variant.name == requestedVariant || request.goal == BuildGoal.CLEAN }
            return choose(matching) ?: error(
                "No Gradle ${request.goal.name.lowercase()} target for variant '$requestedVariant'. Sync the project and choose an available task.",
            )
        }
        if (request.targets.isEmpty()) return listOf(select(all))
        return request.targets.distinct().map { id ->
            val module = project.modules.firstOrNull { it.id == id } ?: error("No module '${id.value}' in this Gradle project.")
            val dir = Path.of(module.dir.path).toAbsolutePath().normalize()
            // Directories, not display names: nested projects can share a leaf name or remap projectDir.
            val model = snapshot.modules.singleOrNull { root.resolve(it.dir.ifBlank { "." }).normalize() == dir }
                ?: error("No synced Gradle module for '${module.name}'. Sync the project before building.")
            select(all.filter { it.choice.variant.modulePath == model.path })
        }
    }

    companion object {
        /** Historical default Run ID. Unlike [BUILD_TASK_ID], this may install and launch. */
        const val TASK_ID = "gradle:assemble"
        const val BUILD_TASK_ID = "gradle:build"
        const val TEST_TASK_ID = "gradle:test"
        const val LINT_TASK_ID = "gradle:lint"
        const val BUNDLE_TASK_ID = "gradle:bundle"
    }
}

internal data class GradleTarget(val choice: VariantChoice.Chosen, val android: Boolean)

class GradleSessionTask internal constructor(
    private val projectDir: Path,
    private val goal: BuildGoal,
    private val targets: List<GradleTarget>,
) : Task, AlwaysRun {

    /** Kept for callers that already chose a single variant. */
    constructor(projectDir: Path, goal: BuildGoal, chosen: VariantChoice.Chosen?) : this(
        projectDir, goal, listOfNotNull(chosen?.let { GradleTarget(it, it.variant.application || it.variant.name != "main") }),
    )

    internal val requestedTasks: List<String> = if (targets.isEmpty()) listOf(taskFor(null))
        else targets.map { taskFor(it) }.distinct()

    override val name: TaskName = TaskName(":gradle:execute")
    override val inputs: TaskInputs = TaskInputsImpl().apply {
        property("goal", goal.name)
        property("tasks", requestedTasks)
    }
    override val outputs: TaskOutputs = TaskOutputsImpl()

    @Volatile var apk: Path? = null
    @Volatile var applicationId: String? = null
    @Volatile var artifacts: List<Path> = emptyList()
        private set

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        apk = null
        applicationId = null
        artifacts = emptyList()
        val host = GradleEnvironment.host
            ?: return TaskResult.Failed("This project builds with Gradle. Sync it once the JDK is installed.")
        if (!host.available()) {
            return TaskResult.Failed("This project builds with Gradle. Sync it once the JDK is installed.")
        }
        val parser = CompilerOutputParser("gradle")
        val canceled = java.util.concurrent.atomic.AtomicBoolean(false)
        val watcher = thread(isDaemon = true, name = "gradle-cancel") {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    ctx.checkCanceled()
                } catch (_: java.util.concurrent.CancellationException) {
                    canceled.set(true)
                    return@thread
                }
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
        return try {
            val snapshot = GradleToolchain.execute(projectDir, host, requestedTasks, isCanceled = { canceled.get() }) { event ->
                when (event["event"]) {
                    "progress" -> {
                        val task = event["task"] as? String ?: return@execute
                        val state = event["state"] as? String ?: return@execute
                        ctx.reportStep(task, state)
                    }
                    "log" -> {
                        val line = event["line"] as? String ?: return@execute
                        ctx.buildLog.log(dev.ide.build.BuildLogEntry(line))
                        parser.accept(line) { ctx.diagnostics.report(it) }
                    }
                }
            }
            ctx.checkCanceled()
            if (canceled.get()) throw java.util.concurrent.CancellationException("canceled")
            snapshot?.issues?.forEach { issue ->
                ctx.diagnostics.report(
                    dev.ide.build.BuildDiagnostic(
                        severity = if (issue.severity.equals("error", true)) dev.ide.build.BuildSeverity.ERROR
                        else dev.ide.build.BuildSeverity.WARNING,
                        message = issue.message,
                        source = "gradle",
                    ),
                )
            }
            if (snapshot != null) GradleProjectSnapshot.store(projectDir, snapshot.raw)
            collectArtifacts(ctx)
            TaskResult.Success
        } catch (c: java.util.concurrent.CancellationException) {
            throw c
        } catch (e: Exception) {
            // Tooling API cancellation can arrive as an error response rather than a CancellationException.
            ctx.checkCanceled()
            if (canceled.get()) throw java.util.concurrent.CancellationException("canceled")
            when (e) {
                is GradleToolchain.UnsupportedWrapper -> TaskResult.Failed(e.message ?: "This project stays on the static Gradle reader.")
                is ToolingClient.ToolingException -> {
                    val tail = e.stderr.lineSequence().filter { it.isNotBlank() }.toList().takeLast(40).joinToString("\n")
                    TaskResult.Failed(listOfNotNull(e.message, tail.takeIf { it.isNotBlank() }).joinToString("\n"))
                }
                else -> TaskResult.Failed(e.message ?: e.toString())
            }
        } finally {
            parser.flush { ctx.diagnostics.report(it) }
            watcher.interrupt()
        }
    }

    private fun taskFor(target: GradleTarget?): String {
        val variant = target?.choice?.variant
        val cap = variant?.name?.replaceFirstChar { it.uppercase() }.orEmpty()
        val task = when (goal) {
            BuildGoal.ASSEMBLE, BuildGoal.PACKAGE -> return target?.choice?.taskPath ?: ":assemble"
            BuildGoal.CLEAN -> "clean"
            BuildGoal.TEST -> if (target?.android == true) "test${cap}UnitTest" else "test"
            BuildGoal.LINT -> {
                require(target == null || target.android) { "Android lint is not available for '${variant?.modulePath}'." }
                if (target == null) "lint" else "lint$cap"
            }
            BuildGoal.BUNDLE -> {
                require(target == null || variant?.application == true) { "Only Android application modules produce an App Bundle." }
                if (target == null) "bundle" else "bundle$cap"
            }
            // Never silently replace a different goal with a production assemble.
            BuildGoal.COMPILE_ONLY, BuildGoal.INSTALL, BuildGoal.DEX ->
                error("Gradle goal ${goal.name} is not supported here. Choose Build, Test, Lint, Bundle, or Run.")
        }
        return VariantChoice.taskPath(variant?.modulePath ?: ":", task)
    }

    private fun collectArtifacts(ctx: TaskContext) {
        val found = ArrayList<Path>()
        for (target in targets) {
            val variant = target.choice.variant
            val build = projectDir.resolve(variant.moduleDir.ifBlank { "." }).resolve("build").normalize()
            val files = when (goal) {
                BuildGoal.ASSEMBLE, BuildGoal.PACKAGE -> when {
                    variant.application -> outputFiles(build.resolve("outputs/apk"), "apk", variant)
                    target.android -> outputFiles(build.resolve("outputs/aar"), "aar", variant)
                    else -> outputFiles(build.resolve("libs"), "jar", null)
                }
                BuildGoal.BUNDLE -> outputFiles(build.resolve("outputs/bundle"), "aab", variant)
                BuildGoal.TEST -> {
                    val task = taskFor(target).substringAfterLast(':')
                    listOf(build.resolve("reports/tests/$task/index.html"), build.resolve("test-results/$task"))
                        .filter { Files.exists(it) }
                }
                BuildGoal.LINT -> listOf("html", "xml", "txt")
                    .map { build.resolve("reports/lint-results-${variant.name}.$it") }
                    .filter { Files.isRegularFile(it) }
                else -> emptyList()
            }
            found += files
            for (file in files) {
                val label = when (goal) {
                    BuildGoal.TEST -> "Test report"
                    BuildGoal.LINT -> "Lint report"
                    else -> file.fileName.toString().substringAfterLast('.').uppercase()
                }
                ctx.buildLog.log(dev.ide.build.BuildLogEntry("$label: $file"))
            }
            if (goal == BuildGoal.ASSEMBLE && variant.application && targets.size == 1) {
                apk = files.maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
                applicationId = variant.applicationId
            }
        }
        artifacts = found.distinct()
    }

    private fun outputFiles(outputs: Path, extension: String, variant: VariantChoice.Variant?): List<Path> {
        if (!Files.isDirectory(outputs)) return emptyList()
        return Files.walk(outputs).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".$extension") }
                .filter { file ->
                    if (variant == null) true
                    else {
                        // Match within this module's output directory, never a substring of the project path.
                        // APK flavors occupy separate directories (free/debug), bundles use freeDebug, and
                        // AARs use a flat name (library-free-debug.aar).
                        val relative = outputs.relativize(file)
                        val directory = relative.parent?.joinToString("").orEmpty()
                        val suffix = (variant.flavors + variant.buildType).joinToString("-")
                        directory.equals(variant.name, ignoreCase = true) ||
                            (extension == "aar" && (file.fileName.toString().endsWith("-$suffix.aar", ignoreCase = true) ||
                                file.fileName.toString().endsWith("-${variant.name}.aar", ignoreCase = true)))
                    }
                }
                .sorted().toList()
        }
    }
}
