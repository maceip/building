package dev.ide.core

import dev.ide.testkit.withTempDir
import dev.ide.build.BUILD_SYSTEM_EP
import dev.ide.build.BuildContext
import dev.ide.build.BuildGoal
import dev.ide.build.BuildLogEntry
import dev.ide.build.BuildRequest
import dev.ide.build.BuildSystem
import dev.ide.build.RUN_TASK_PROVIDER_EP
import dev.ide.build.RunAction
import dev.ide.build.RunTaskProvider
import dev.ide.build.RunTaskSpec
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskDescriptor
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.build.TaskGraph
import dev.ide.build.TaskName
import dev.ide.build.engine.DefaultTaskContainer
import dev.ide.build.engine.LifecycleTask
import dev.ide.gradle.GradleBuildSystem
import dev.ide.gradle.GradleProjectSnapshot
import dev.ide.model.BuildSystemId
import dev.ide.model.Module
import dev.ide.model.ModuleType
import dev.ide.model.Project
import dev.ide.model.UnknownModuleType
import dev.ide.platform.PluginId
import dev.ide.ui.backend.RunStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 2f / 2h: a plugin contributes a [BuildSystem] (selected by [BuildSystem.supports]) and extra Run-picker
 * options ([RunTaskProvider]) through the build extension points, and the engine's own built-ins still win
 * for the module types they support. Registered on the engine's per-project registry (the same one the build
 * service queries).
 */
class BuildSystemExtensionTest {

    /** Only [supports] and identity matter for the selection test; the graph methods are never reached. */
    private class FakeBuildSystem(private val type: String) : BuildSystem {
        override val id = BuildSystemId.NATIVE
        override fun supports(moduleType: ModuleType) = moduleType.id == type
        override fun createBuildGraph(project: Project, request: BuildRequest): TaskGraph =
            throw UnsupportedOperationException("not exercised by the selection test")
        override fun tasks(project: Project): List<TaskDescriptor> = emptyList()
    }

    /**
     * Contributes a row AND executes it: `lint:<module>` carries no built-in id prefix, so the host dispatches
     * it back here through [RunTaskProvider.actionFor].
     */
    private class FakeRunTaskProvider : RunTaskProvider {
        override fun tasksFor(module: Module) =
            listOf(RunTaskSpec("lint:${module.name}", "Lint ${module.name}", "build"))

        override fun actionFor(
            spec: RunTaskSpec,
            project: Project,
            module: Module,
            ctx: BuildContext,
        ): RunAction? {
            if (!spec.id.startsWith("lint:")) return null
            val name = TaskName(":${module.name}:lint")
            val tasks = DefaultTaskContainer()
            tasks.register(name) { LifecycleTask(name) }
            return RunAction("> lint ${module.name}", tasks.build())
        }
    }

    @Test
    fun pluginBuildSystemSelectedForItsTypeButBuiltinsWin() {
        withTempDir("build-system-ep") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                val foo = FakeBuildSystem("custom-foo")
                ide.platform.extensions.register(BUILD_SYSTEM_EP, foo, PluginId("test-plugin"))

                // A novel module type the built-ins reject → the plugin build system is selected.
                assertSame(foo, ide.build.buildSystemFor(UnknownModuleType("custom-foo")))
                // A novel type nobody claims → null (no built-in, no plugin supports it).
                assertNull(ide.build.buildSystemFor(UnknownModuleType("custom-bar")))
                // A real Java module type → the built-in JavaBuildSystem wins, never the plugin.
                val javaType = ide.modules().first().type
                val chosen = ide.build.buildSystemFor(javaType)
                assertTrue(chosen != null && chosen !== foo, "the built-in must win for a Java module type")
            }
        }
    }

    @Test
    fun runTaskProviderOptionsMergedIntoRunTasks() {
        withTempDir("run-task-ep") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                assertTrue(ide.build.runTasks().none { it.id.startsWith("lint:") }, "no lint tasks before the provider")
                ide.platform.extensions.register(RUN_TASK_PROVIDER_EP, FakeRunTaskProvider(), PluginId("test-plugin"))

                val after = ide.build.runTasks()
                for (name in ide.modules().map { it.name }) {
                    assertTrue(
                        after.any { it.id == "lint:$name" && it.label == "Lint $name" },
                        "the provider's option for $name should appear: ${after.map { it.id }}",
                    )
            }
            // The built-in enumeration is untouched (a runnable main still yields a run: task).
            assertTrue(after.any { it.id.startsWith("run:") }, "built-in run tasks still present")
            }
        }
    }

    /** A contributed row with no built-in id prefix is dispatched back to its provider and its graph runs. */
    @Test
    fun contributedRunActionIsExecuted() {
        withTempDir("run-action-ep") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                ide.platform.extensions.register(RUN_TASK_PROVIDER_EP, FakeRunTaskProvider(), PluginId("test-plugin"))
                val module = ide.modules().first().name

                ide.build.runTask("lint:$module")

                val state = runBlocking {
                    withTimeout(30_000) {
                        ide.build.buildState.first { it.status != RunStatus.Running && it.log.isNotEmpty() }
                    }
            }
            assertEquals(RunStatus.Succeeded, state.status, "log:\n${state.log.joinToString("\n") { it.message }}")
            assertTrue(
                state.log.any { "> lint $module" in it.message },
                "the action's header reached the console: ${state.log.map { it.message }}",
            )
            assertTrue(
                state.steps.any { it.name == ":$module:lint" },
                "the action's graph drove the step list: ${state.steps.map { it.name }}",
            )
            }
        }
    }

    /** The Gradle owner receives the original build request, never the install-capable default Run row. */
    @Test
    fun legacyHostBuildOnlyIdsKeepTheirGoalsTargetsAndVariantsOnGradleProjects() {
        withTempDir("gradle-goal-dispatch") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                val owner = RecordingGradleBuildSystem()
                ide.platform.extensions.register(BUILD_SYSTEM_EP, owner, PluginId("test-gradle-owner"))
                bindToGradle(ide)
                val module = ide.modules().first()
                ide.setActiveVariant(module, "chosenRelease")
                for ((id, goal, variant) in listOf(
                    Triple("build:${module.name}", BuildGoal.ASSEMBLE, "chosenRelease"),
                    Triple("assemble:${module.name}:freeRelease", BuildGoal.ASSEMBLE, "freeRelease"),
                    Triple("assembleAar:${module.name}:freeDebug", BuildGoal.ASSEMBLE, "freeDebug"),
                    Triple("bundle:${module.name}:freeRelease", BuildGoal.BUNDLE, "freeRelease"),
                    Triple("test:${module.name}:freeDebug", BuildGoal.TEST, "freeDebug"),
                    Triple("lint:${module.name}:freeDebug", BuildGoal.LINT, "freeDebug"),
                )) {
                    ide.build.runTask(id)
                    val state = terminal(ide)
                    assertEquals(RunStatus.Succeeded, state.status, state.log.toString())
                    val request = owner.requests.last()
                    assertEquals(goal, request.goal, id)
                    assertEquals(listOf(module.id), request.targets, id)
                    assertEquals(variant, request.variant.name, id)
                    assertTrue(state.log.any { it.message == "executing $goal $variant" }, state.log.toString())
                    assertEquals(0, owner.runActions, "$id must not request the default Run action")
                    assertEquals(0, owner.installs, "$id must not install")
                }
                assertEquals(6, owner.requests.size)
            }
        }
    }

    @Test
    fun gradleOwnedConsoleModulesOnlyListTheirOwnersTasksAndDefaultRunIsPreserved() {
        withTempDir("gradle-owned-picker") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                val owner = RecordingGradleBuildSystem()
                ide.platform.extensions.register(BUILD_SYSTEM_EP, owner, PluginId("test-gradle-owner"))
                bindToGradle(ide)
                val rows = ide.build.runTasks()
                assertEquals(listOf(GradleBuildSystem.TASK_ID), rows.map { it.id })
                assertFalse(rows.any { it.id.startsWith("build:") || it.id.startsWith("run:") })
                ide.build.runBuild()
                assertEquals(RunStatus.Succeeded, terminal(ide).status)
                assertEquals(1, owner.runActions)
                assertEquals(1, owner.installs, "the legacy default Run still executes its post-build action")
                val module = ide.modules().first()
                for (id in listOf("run:${module.name}", "androidRun:${module.name}:debug")) {
                    ide.build.runTask(id)
                    assertEquals(RunStatus.Succeeded, terminal(ide).status)
                }
                assertEquals(3, owner.installs, "explicit Run aliases retain their install-capable action")
            }
        }
    }

    @Test
    fun actualGradleOwnerExposesBuildQualityAndBundleRowsWithoutNativeDuplicates() {
        withTempDir("gradle-workbench-picker") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                bindToGradle(ide)
                ide.platform.extensions.register(BUILD_SYSTEM_EP, GradleBuildSystem(), PluginId("test-gradle-owner"))
                GradleProjectSnapshot.store(dir, """
                    {"schemaVersion":2,"name":"fixture","modules":[
                      {"name":"app","path":":app:android","dir":"app","application":true,
                       "plugins":["com.android.application"],"variants":[
                         {"name":"debug","buildType":"debug","assembleTask":"assembleDebug"},
                         {"name":"release","buildType":"release","assembleTask":"assembleRelease"}]}
                    ]}
                """.trimIndent())
                val ids = IdeServicesBackend(ide).build.runTasks().map { it.id }
                assertEquals("gradle:assemble", ids.first())
                for (goal in listOf("build", "test", "lint", "bundle")) {
                    assertTrue("gradle:$goal" in ids, ids.toString())
                    assertTrue("gradle:$goal:app:android:release" in ids, ids.toString())
                }
                assertFalse(ids.any { it.startsWith("build:") || it.startsWith("run:") })
            }
        }
    }

    @Test
    fun nativeTestWithoutATaskProviderRunsToATerminalResult() {
        withTempDir("native-test-unsupported") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                val module = ide.modules().first()
                ide.build.runTask("test:${module.name}:main")
                val state = terminal(ide)
                assertTrue(
                    state.status == RunStatus.Succeeded || state.status == RunStatus.Failed,
                    "a native test run reaches a terminal result, never a hang: ${state.status}",
                )
                assertTrue(state.log.isNotEmpty(), "the test run leaves a transcript")
            }
        }
    }

    @Test
    fun stoppedPresentationKeepsSourceLeaseUntilCancellationFinallyCompletes() = withTempDir("build-stop-quiescence") { dir ->
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val finishCleanup = CompletableDeferred<Unit>()
            ide.platform.extensions.register(RUN_TASK_PROVIDER_EP, object : RunTaskProvider {
                override fun tasksFor(module: Module) = listOf(RunTaskSpec("hold:${module.name}", "Hold", "build"))
                override fun actionFor(spec: RunTaskSpec, project: Project, module: Module, ctx: BuildContext): RunAction =
                    RunAction("> hold", DefaultTaskContainer().apply {
                        register(TaskName(":hold")) {
                            object : Task {
                                override val name = TaskName(":hold")
                                override val inputs = TaskInputsImpl().apply { property("run", true) }
                                override val outputs = TaskOutputsImpl()
                                override suspend fun execute(ctx: TaskContext): TaskResult {
                                    entered.complete(Unit)
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        cleaning.complete(Unit)
                                        withContext(NonCancellable) { finishCleanup.await() }
                                    }
                                }
                            }
                        }
                    }.build())
            }, PluginId("quiescence-test"))
            val access = dev.ide.core.reliability.IncidentSourceAccess(ide.platform.modelLock, dir)
            try {
                assertNull(ide.buildRunner.sourceMutationBlocker())
                ide.buildRunner.runTask("hold:${ide.modules().first().name}")
                runBlocking { withTimeout(30_000) { entered.await() } }
                ide.buildRunner.stopBuild()
                runBlocking { withTimeout(30_000) { cleaning.await() } }
                assertEquals(RunStatus.Failed, ide.build.buildState.value.status)
                assertNotNull(ide.buildRunner.sourceMutationBlocker(), "Failed is presentation, not worker completion")
                assertIs<dev.ide.agent.IncidentApplyResult.Pending>(
                    access.checkBuffers(dir.toRealPath(), emptyMap(), emptySet(), emptyMap()),
                    "the real engine lease must survive cancellation cleanup",
                )
                ide.buildRunner.runTask("lint:${ide.modules().first().name}")
                assertTrue(ide.build.buildState.value.log.any { "prior build work is still finishing" in it.message })
            } finally {
                finishCleanup.complete(Unit)
                runBlocking {
                    withTimeout(30_000) {
                        while (ide.buildRunner.sourceMutationBlocker() != null ||
                            access.checkBuffers(dir.toRealPath(), emptyMap(), emptySet(), emptyMap()) != null) delay(10)
                    }
                }
            }
        }
    }

    @Test
    fun graphConstructionRetainsSourcesWithoutHoldingTheModelLock() = withTempDir("build-start-quiescence") { dir ->
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val entered = CountDownLatch(1)
            val finishStart = CountDownLatch(1)
            ide.platform.extensions.register(RUN_TASK_PROVIDER_EP, object : RunTaskProvider {
                override fun tasksFor(module: Module) = listOf(RunTaskSpec("prepare:${module.name}", "Prepare", "build"))
                override fun actionFor(spec: RunTaskSpec, project: Project, module: Module, ctx: BuildContext): RunAction? {
                    assertFalse(ide.platform.modelLock.isWriteLockedByCurrentThread)
                    entered.countDown()
                    check(finishStart.await(30, TimeUnit.SECONDS))
                    return null
                }
            }, PluginId("quiescence-test"))
            val worker = Executors.newSingleThreadExecutor()
            val access = dev.ide.core.reliability.IncidentSourceAccess(ide.platform.modelLock, dir)
            val start = worker.submit { ide.buildRunner.runTask("prepare:${ide.modules().first().name}") }
            try {
                assertTrue(entered.await(30, TimeUnit.SECONDS))
                assertNotNull(ide.buildRunner.sourceMutationBlocker())
                assertIs<dev.ide.agent.IncidentApplyResult.Pending>(
                    access.checkBuffers(dir.toRealPath(), emptyMap(), emptySet(), emptyMap()),
                )
            } finally {
                finishStart.countDown()
                start.get(30, TimeUnit.SECONDS)
                worker.shutdownNow()
            }
            assertNull(ide.buildRunner.sourceMutationBlocker())
            assertNull(access.checkBuffers(dir.toRealPath(), emptyMap(), emptySet(), emptyMap()))
        }
    }

    private fun bindToGradle(ide: IdeServices) {
        val project = ide.store.workspace.projects.single()
        ide.store.workspace.beginModification().apply {
            setBuildSystem(project.id, BuildSystemId.GRADLE)
            commit()
        }
    }

    private fun terminal(ide: IdeServices) = runBlocking {
        // launch() replaces _buildState with a fresh instance per run (log reset to one header
        // line), so log-size gating can never work. Gate on instance identity instead: the
        // pre-dispatch state object would otherwise satisfy this wait before the new task's
        // graph has recorded anything (runTask dispatches asynchronously).
        val before = ide.build.buildState.value
        withTimeout(30_000) {
            ide.build.buildState.first {
                it !== before && it.status != RunStatus.Running && it.log.isNotEmpty()
            }
        }
    }

    private class RecordingGradleBuildSystem : BuildSystem {
        override val id = BuildSystemId.GRADLE
        val requests = ArrayList<BuildRequest>()
        var runActions = 0
        var installs = 0
        override fun supports(moduleType: ModuleType) = false
        override fun tasks(project: Project) = emptyList<TaskDescriptor>()
        override fun runTasks(project: Project) = listOf(RunTaskSpec(GradleBuildSystem.TASK_ID, "Run", "android"))
        override fun createBuildGraph(project: Project, request: BuildRequest): TaskGraph {
            requests += request
            return graph("executing ${request.goal} ${request.variant.name}")
        }
        override fun actionFor(spec: RunTaskSpec, project: Project, ctx: BuildContext): RunAction {
            runActions++
            return RunAction("> Gradle Run", graph("running app"), onSuccess = { installs++ })
        }
        private fun graph(message: String): TaskGraph = DefaultTaskContainer().apply {
            register(TaskName(":recordGoal")) {
                object : Task {
                    override val name = TaskName(":recordGoal")
                    override val inputs = TaskInputsImpl().apply { property("message", message) }
                    override val outputs = TaskOutputsImpl()
                    override suspend fun execute(ctx: TaskContext): TaskResult {
                        ctx.buildLog.log(BuildLogEntry(message))
                        return TaskResult.Success
                    }
                }
            }
        }.build()
    }

    /** An id nobody claims still fails cleanly rather than throwing or hanging. */
    @Test
    fun unknownTaskIdFailsWithAMessage() {
        withTempDir("run-action-unknown") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                ide.build.runTask("nonsense:whatever")
                val state = ide.build.buildState.value
                assertEquals(RunStatus.Failed, state.status)
                assertTrue(state.log.any { "Unknown task" in it.message }, state.log.map { it.message }.toString())
            }
        }
    }
}
