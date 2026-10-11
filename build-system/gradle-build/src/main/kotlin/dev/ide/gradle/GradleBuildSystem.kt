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
        TaskDescriptor("assemble", "build", "Assemble with Gradle"),
    )

    override fun runTasks(project: Project): List<RunTaskSpec> {
        val snapshot = GradleProjectSnapshot.load(Path.of(project.rootDir.path))
        val chosen = snapshot?.let { VariantChoice.choose(it.variants()) }
        val label = when {
            chosen == null -> "Build with Gradle"
            chosen.variant.application -> "Run ${chosen.variant.name}"
            else -> chosen.variant.assembleTask
        }
        return listOf(RunTaskSpec(TASK_ID, label, if (chosen?.variant?.application == true) "android" else "build"))
    }

    override fun actionFor(spec: RunTaskSpec, project: Project, ctx: BuildContext): RunAction? {
        if (spec.id != TASK_ID) return null
        val root = Path.of(project.rootDir.path)
        val snapshot = GradleProjectSnapshot.load(root)
        val chosen = snapshot?.let { VariantChoice.choose(it.variants()) }
        val goal = BuildGoal.ASSEMBLE
        val session = GradleSessionTask(root, goal, chosen)
        val tasks = DefaultTaskContainer()
        tasks.register(session.name) { session }
        val header = chosen?.let { "> ${it.taskPath}" } ?: "> gradle assemble"
        return RunAction(
            header = header,
            graph = tasks.build(),
            onSuccess = { log ->
                val apk = session.apk
                val applicationId = session.applicationId
                val install = GradleEnvironment.installAndLaunch
                if (apk != null && applicationId != null && install != null) install(apk, applicationId, log)
                else if (apk != null) log("APK: $apk")
            },
        )
    }

    override fun createBuildGraph(project: Project, request: BuildRequest): TaskGraph =
        createBuildGraph(project, request, BuildContext(env = dev.ide.build.engine.DefaultBuildEnv(Path.of(project.rootDir.path))))

    override fun createBuildGraph(project: Project, request: BuildRequest, ctx: BuildContext): TaskGraph {
        val root = Path.of(project.rootDir.path)
        val snapshot = GradleProjectSnapshot.load(root)
        val chosen = snapshot?.let { VariantChoice.choose(it.variants()) }
        val session = GradleSessionTask(root, request.goal, chosen)
        val tasks = DefaultTaskContainer()
        tasks.register(session.name) { session }
        return tasks.build()
    }

    companion object {
        const val TASK_ID = "gradle:assemble"
    }
}

class GradleSessionTask(
    private val projectDir: Path,
    private val goal: BuildGoal,
    private val chosen: VariantChoice.Chosen?,
) : Task, AlwaysRun {

    override val name: TaskName = TaskName(":gradle:execute")
    override val inputs: TaskInputs = TaskInputsImpl().apply { property("goal", goal.name) }
    override val outputs: TaskOutputs = TaskOutputsImpl()

    @Volatile var apk: Path? = null
    @Volatile var applicationId: String? = null

    override suspend fun execute(ctx: TaskContext): TaskResult {
        val host = GradleEnvironment.host
            ?: return TaskResult.Failed("This project builds with Gradle. Sync it once the JDK is installed.")
        if (!host.available()) {
            return TaskResult.Failed("This project builds with Gradle. Sync it once the JDK is installed.")
        }
        val tasks = gradleTasks()
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
            val snapshot = GradleToolchain.execute(projectDir, host, tasks, isCanceled = { canceled.get() }) { event ->
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
            parser.flush { ctx.diagnostics.report(it) }
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
            val variant = chosen?.variant
            if (variant != null) {
                apk = findApk(projectDir, variant.moduleDir, variant.name)
                applicationId = variant.namespace
            }
            if (canceled.get()) throw java.util.concurrent.CancellationException("canceled")
            TaskResult.Success
        } catch (c: java.util.concurrent.CancellationException) {
            throw c
        } catch (e: GradleToolchain.UnsupportedWrapper) {
            TaskResult.Failed(e.message ?: "This project stays on the static Gradle reader.")
        } catch (e: ToolingClient.ToolingException) {
            val tail = e.stderr.lineSequence().filter { it.isNotBlank() }.takeLast(40).joinToString("\n")
            TaskResult.Failed(listOfNotNull(e.message, tail.takeIf { it.isNotBlank() }).joinToString("\n"))
        } catch (e: Exception) {
            TaskResult.Failed(e.message ?: e.toString())
        } finally {
            watcher.interrupt()
        }
    }

    private fun gradleTasks(): List<String> {
        val assemble = chosen?.taskPath ?: ":assemble"
        return when (goal) {
            BuildGoal.CLEAN -> listOf(":clean")
            BuildGoal.TEST -> listOf(":test")
            BuildGoal.BUNDLE -> listOf(VariantChoice.bundleTask(assemble))
            else -> listOf(assemble)
        }
    }

    private fun findApk(root: Path, moduleDir: String, variant: String): Path? {
        val base = if (moduleDir.isBlank() || moduleDir == ".") root else root.resolve(moduleDir)
        val outputs = base.resolve("build/outputs/apk")
        if (!Files.isDirectory(outputs)) return null
        return Files.walk(outputs).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".apk") }
                .filter { variant.isEmpty() || it.toString().contains(variant, ignoreCase = true) }
                .max(compareBy<Path> { Files.getLastModifiedTime(it).toMillis() })
                .orElse(null)
        }
    }
}
