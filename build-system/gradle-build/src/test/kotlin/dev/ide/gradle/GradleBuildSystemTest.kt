package dev.ide.gradle

import dev.ide.build.BuildContext
import dev.ide.build.BuildGoal
import dev.ide.build.BuildLogSink
import dev.ide.build.BuildRequest
import dev.ide.build.RunAction
import dev.ide.build.RunTaskSpec
import dev.ide.build.TaskContext
import dev.ide.build.TaskResult
import dev.ide.build.VariantSelector
import dev.ide.build.engine.DefaultBuildEnv
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.model.BuildSystemId
import dev.ide.model.ModuleId
import dev.ide.model.Project
import dev.ide.platform.JsonReader
import dev.ide.testkit.TestModuleType
import dev.ide.testkit.withWorkspace
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradleBuildSystemTest {
    private val system = GradleBuildSystem()

    @Test
    fun pickerKeepsLegacyRunFirstAndEnumeratesBuildOnlyGoalsPerModuleAndVariant() = withProject { _, project ->
        val rows = system.runTasks(project)
        val ids = rows.map { it.id }
        assertEquals("gradle:assemble", ids.first())
        assertEquals(ids.size, ids.distinct().size)
        for (goal in listOf("build", "test", "lint", "bundle")) {
            assertTrue("gradle:$goal" in ids)
            assertTrue("gradle:$goal:app:android:defaultRelease" in ids, ids.toString())
        }
        assertTrue("gradle:run:app:android:defaultDebug" in ids)
        assertTrue("gradle:lint:shared:defaultDebug" in ids)
        assertTrue("gradle:build::main" in ids)
        assertTrue("gradle:test::main" in ids)
        assertFalse(ids.any { it.startsWith("gradle:bundle:shared:") || it.startsWith("gradle:run:shared:") })
        assertFalse(ids.any { it.startsWith("gradle:lint::") || it.startsWith("gradle:bundle::") })
        assertEquals(setOf("assemble", "test", "lint", "bundle", "clean"), system.tasks(project).map { it.name }.toSet())
    }

    @Test
    fun qualifiedRowsMapToTheExactGradleGoalsAndOnlyRunHasAnInstallCallback() = withProject { root, project ->
        for ((goal, task) in mapOf(
            "build" to "assembleSelectedRelease",
            "bundle" to "bundleDefaultRelease",
            "lint" to "lintDefaultRelease",
            "test" to "testDefaultReleaseUnitTest",
        )) {
            val action = action(project, "gradle:$goal:app:android:defaultRelease")
            assertEquals(listOf(":app:android:$task"), session(action).requestedTasks)
            assertEquals("> gradle :app:android:$task", action.header)
            assertNull(action.onSuccess, "$goal is durably build-only")
        }
        for (id in listOf("gradle:assemble", "gradle:run:app:android:defaultDebug")) {
            val run = action(project, id)
            assertEquals(listOf(":app:android:assembleDefaultDebug"), session(run).requestedTasks)
            assertNotNull(run.onSuccess)
        }
        assertEquals(listOf(":test"), session(action(project, "gradle:test::main")).requestedTasks)
        assertNull(system.actionFor(RunTaskSpec("gradle:lint:app:android:missing", "", ""), project, context(root)))
    }

    @Test
    fun buildRequestsRespectTargetsRemappedDirectoriesAndVariants() = withProject { _, project ->
        val mobile = project.modules.single { it.name == "mobile" }
        val shared = project.modules.single { it.name == "shared" }
        for ((goal, task) in mapOf(
            BuildGoal.ASSEMBLE to "assembleSelectedRelease",
            BuildGoal.BUNDLE to "bundleDefaultRelease",
            BuildGoal.LINT to "lintDefaultRelease",
            BuildGoal.TEST to "testDefaultReleaseUnitTest",
        )) {
            val graph = system.createBuildGraph(project, BuildRequest(listOf(mobile.id), VariantSelector("defaultRelease"), goal))
            assertEquals(listOf(":app:android:$task"), (graph.tasks.single() as GradleSessionTask).requestedTasks)
        }
        val graph = system.createBuildGraph(project,
            BuildRequest(listOf(mobile.id, shared.id), VariantSelector("defaultRelease"), BuildGoal.TEST))
        assertEquals(listOf(":app:android:testDefaultReleaseUnitTest", ":shared:testDefaultReleaseUnitTest"),
            (graph.tasks.single() as GradleSessionTask).requestedTasks)
        val clean = system.createBuildGraph(project,
            BuildRequest(listOf(shared.id), VariantSelector("main"), BuildGoal.CLEAN))
        assertEquals(listOf(":shared:clean"), (clean.tasks.single() as GradleSessionTask).requestedTasks)
        val cleanAll = system.createBuildGraph(project, BuildRequest(emptyList(), VariantSelector("main"), BuildGoal.CLEAN))
        assertEquals(listOf(":clean"), (cleanAll.tasks.single() as GradleSessionTask).requestedTasks)
    }

    @Test
    fun staleAndUnsupportedRequestsNeverFallBackToTheDefaultApp() = withProject { _, project ->
        val mobile = project.modules.single { it.name == "mobile" }
        val shared = project.modules.single { it.name == "shared" }
        val requests = listOf(
            BuildRequest(listOf(mobile.id), VariantSelector("removedRelease"), BuildGoal.ASSEMBLE),
            BuildRequest(listOf(ModuleId("removed-module")), VariantSelector("defaultDebug"), BuildGoal.TEST),
            BuildRequest(listOf(shared.id), VariantSelector("defaultDebug"), BuildGoal.BUNDLE),
            BuildRequest(listOf(mobile.id), VariantSelector("defaultDebug"), BuildGoal.INSTALL),
            BuildRequest(listOf(mobile.id), VariantSelector("defaultDebug"), BuildGoal.COMPILE_ONLY),
            BuildRequest(listOf(mobile.id), VariantSelector("defaultDebug"), BuildGoal.DEX),
        )
        for (request in requests) assertFailsWith<IllegalStateException>(request.toString()) {
            system.createBuildGraph(project, request)
        }
        assertFailsWith<IllegalArgumentException> {
            system.actionFor(project,
                BuildRequest(listOf(mobile.id), VariantSelector("defaultDebug"), BuildGoal.BUNDLE), runAfterBuild = true)
        }
    }

    @Test
    fun unsyncedDefaultsStillDispatchRealGoalsButQualifiedRequestsRequireSync() = withProject { root, project ->
        Files.delete(GradleProjectSnapshot.file(root))
        for ((id, task) in mapOf("build" to "assemble", "test" to "test", "lint" to "lint", "bundle" to "bundle")) {
            assertEquals(listOf(":$task"), session(action(project, "gradle:$id")).requestedTasks)
        }
        assertFailsWith<IllegalArgumentException> {
            system.createBuildGraph(project,
                BuildRequest(listOf(project.modules.first().id), VariantSelector("defaultDebug"), BuildGoal.TEST))
        }
    }

    @Test
    fun executionPassesExactGoalsToToolingStreamsOutputAndReportsOnlyRelevantArtifacts() = withProject { root, project ->
        val apk = write(root, "client/mobile/build/outputs/apk/default/debug/app-default-debug.apk")
        val wrongApk = write(root, "client/mobile/build/outputs/apk/default/release/app-default-release.apk")
        // A newer APK in a different variant must not be installed when Run targets defaultDebug.
        Files.setLastModifiedTime(wrongApk, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000))
        val aab = write(root, "client/mobile/build/outputs/bundle/defaultDebug/app-default-debug.aab")
        val lint = write(root, "client/mobile/build/reports/lint-results-defaultDebug.html")
        val tests = write(root, "client/mobile/build/reports/tests/testDefaultDebugUnitTest/index.html")
        val cases = listOf(
            Triple("build", ":app:android:assembleDefaultDebug", apk),
            Triple("bundle", ":app:android:bundleDefaultDebug", aab),
            Triple("lint", ":app:android:lintDefaultDebug", lint),
            Triple("test", ":app:android:testDefaultDebugUnitTest", tests),
        )
        var installs = 0
        val oldInstall = GradleEnvironment.installAndLaunch
        try {
            GradleEnvironment.installAndLaunch = { _, _, _ -> installs++ }
            for ((id, expected, artifact) in cases) {
                val process = ToolingProcess(expected)
                withHost(root, process) {
                    val action = action(project, "gradle:$id:app:android:defaultDebug")
                    val logs = ArrayList<String>()
                    val steps = ArrayList<Pair<String, String>>()
                    val result = runBlocking {
                        session(action).execute(SimpleTaskContext(onLog = { logs += it.message }, onStep = { name, state -> steps += name to state }))
                            .also { if (it == TaskResult.Success) action.onSuccess?.invoke { line -> logs += line } }
                    }
                    assertEquals(TaskResult.Success, result)
                    assertEquals(listOf(expected), process.executedTasks())
                    assertEquals(listOf(artifact), session(action).artifacts)
                    assertTrue(logs.any { it == "stdout from $expected" }, logs.toString())
                    assertTrue(logs.any { artifact.toString() in it }, logs.toString())
                    assertTrue(expected to "started" in steps)
                    assertTrue(expected to "succeeded" in steps)
                    if (id != "build") {
                        assertNull(session(action).apk)
                        assertFalse(logs.any { it.startsWith("APK:") }, logs.toString())
                    }
                    assertEquals(0, installs, "$id must not install even with an existing APK and an installer")
                }
            }
            withHost(root, ToolingProcess(":app:android:assembleDefaultDebug")) {
                val run = action(project, "gradle:assemble")
                GradleEnvironment.installAndLaunch = { built, packageName, _ ->
                    assertEquals(apk, built)
                    assertEquals("org.example.mobile.debug", packageName)
                    installs++
                }
                runBlocking {
                    assertEquals(TaskResult.Success, session(run).execute(SimpleTaskContext()))
                    run.onSuccess!!.invoke {}
                }
                assertEquals(1, installs, "legacy Run still installs the requested app")
            }
        } finally {
            GradleEnvironment.installAndLaunch = oldInstall
        }
    }

    @Test
    fun libraryAndJvmBuildsReportAarAndJarWithoutInstalling() = withProject { root, project ->
        val aar = write(root, "shared/build/outputs/aar/shared-default-debug.aar")
        val jar = write(root, "build/libs/fixture.jar")
        for ((id, task, artifact) in listOf(
            Triple("gradle:build:shared:defaultDebug", ":shared:assembleDefaultDebug", aar),
            Triple("gradle:build::main", ":assemble", jar),
        )) {
            withHost(root, ToolingProcess(task)) {
                val action = action(project, id)
                assertNull(action.onSuccess)
                assertEquals(TaskResult.Success, runBlocking { session(action).execute(SimpleTaskContext()) })
                assertEquals(listOf(artifact), session(action).artifacts)
                assertNull(session(action).apk)
            }
        }
    }

    @Test
    fun failedTestsAreFailuresNotSuccessfulProductionBuilds() = withProject { root, project ->
        write(root, "client/mobile/build/outputs/apk/default/debug/stale.apk")
        val task = ":app:android:testDefaultDebugUnitTest"
        val process = ToolingProcess(task, error = "There were failing tests")
        withHost(root, process) {
            val action = action(project, "gradle:test")
            val result = runBlocking { session(action).execute(SimpleTaskContext()) }
            assertTrue(result is TaskResult.Failed && result.message.contains("failing tests"), result.toString())
            assertEquals(listOf(task), process.executedTasks())
            assertNull(action.onSuccess)
            assertNull(session(action).apk)
            assertTrue(session(action).artifacts.isEmpty())
        }
    }

    @Test
    fun cancellationReachesToolingAndCannotCompleteAsSuccess() = withProject { root, project ->
        val task = ":app:android:lintDefaultDebug"
        val process = ToolingProcess(task, error = "Build cancelled")
        val cancel = AtomicBoolean(false)
        val watcherObserved = AtomicReference<Thread?>()
        val taskContext = object : TaskContext {
            override val progress = SimpleTaskContext().progress
            override fun checkCanceled() {
                if (cancel.get()) {
                    watcherObserved.compareAndSet(null, Thread.currentThread())
                    throw CancellationException("stopped")
                }
            }
            override val buildLog = BuildLogSink {
                if (it.message.startsWith("stdout")) {
                    cancel.set(true)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (watcherObserved.get() == null && System.nanoTime() < deadline) Thread.sleep(10)
                    val watcher = assertNotNull(watcherObserved.get(), "the session must observe Stop while tooling is running")
                    watcher.join(5_000) // cancellation has reached the predicate passed to ToolingClient
                    assertFalse(watcher.isAlive)
                }
            }
        }
        withHost(root, process) {
            val action = action(project, "gradle:lint")
            assertFailsWith<CancellationException> { runBlocking { session(action).execute(taskContext) } }
            assertTrue(process.requests().any { it["method"] == "cancel" })
            assertNull(session(action).apk)
            assertTrue(session(action).artifacts.isEmpty())
        }
    }

    private fun action(project: Project, id: String): RunAction {
        val row = system.runTasks(project).single { it.id == id }
        return assertNotNull(system.actionFor(row, project, context(Path.of(project.rootDir.path))))
    }

    private fun session(action: RunAction) = action.graph.tasks.single() as GradleSessionTask
    private fun context(root: Path) = BuildContext(env = DefaultBuildEnv(root))

    private fun withProject(block: (Path, Project) -> Unit) = withWorkspace("gradle-build-system") { _, store ->
        val root = Path.of(store.vfs.root().path)
        store.workspace.beginModification().apply {
            addProject("fixture", BuildSystemId.GRADLE, store.vfs.root())
            commit()
        }
        store.workspace.projects.single().beginModification().apply {
            addModule("mobile", TestModuleType("java-lib")).dirRelPath = "client/mobile"
            addModule("shared", TestModuleType("java-lib")).dirRelPath = "shared"
            addModule("root", TestModuleType("java-lib")).dirRelPath = "."
            commit()
        }
        GradleProjectSnapshot.store(root, """
            {"schemaVersion":2,"name":"fixture","modules":[
              {"name":"android","path":":app:android","dir":"client/mobile","application":true,
               "plugins":["com.android.application"],"variants":[
                 {"name":"defaultDebug","buildType":"debug","flavors":["default"],"assembleTask":"assembleDefaultDebug","applicationId":"org.example.mobile.debug"},
                 {"name":"defaultRelease","buildType":"release","flavors":["default"],"assembleTask":"assembleSelectedRelease","applicationId":"org.example.mobile"}]},
              {"name":"shared","path":":shared","dir":"shared","application":false,
               "plugins":["com.android.library"],"variants":[
                 {"name":"defaultDebug","buildType":"debug","flavors":["default"],"assembleTask":"assembleDefaultDebug"},
                 {"name":"defaultRelease","buildType":"release","flavors":["default"],"assembleTask":"assembleDefaultRelease"}]},
              {"name":"fixture","path":":","dir":"","application":false,
               "plugins":["java-library"],"variants":[{"name":"main","buildType":"main","assembleTask":"assemble"}]}
            ]}
        """.trimIndent())
        block(root, store.workspace.projects.single())
    }

    private fun write(root: Path, relative: String): Path = root.resolve(relative).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, "fixture")
    }

    private fun withHost(root: Path, process: ToolingProcess, block: () -> Unit) {
        val old = GradleEnvironment.host
        try {
            GradleEnvironment.host = object : GradleHost by DesktopGradleHost(root.resolve("host")) {
                override fun initScript(): Path = root.resolve("host/fixture.init.gradle")
                override fun openClient(javaHome: Path) = ToolingClient(process)
            }
            block()
        } finally {
            GradleEnvironment.host = old
        }
    }

    /** A protocol peer, not a fake build graph: capture the exact execute request written by ToolingClient. */
    private class ToolingProcess(task: String, error: String? = null) : Process() {
        private val written = ByteArrayOutputStream()
        private val incoming = PipedInputStream(64 * 1024)
        private val sender = PipedOutputStream(incoming).apply {
            val out = this
            val frames = listOf(
                mapOf("id" to 1L, "result" to emptyMap<String, Any?>()),
                mapOf("event" to "progress", "task" to task, "state" to "started"),
                mapOf("event" to "log", "line" to "stdout from $task"),
                mapOf("event" to "progress", "task" to task, "state" to if (error == null) "succeeded" else "failed"),
                if (error == null) mapOf("id" to 2L, "result" to emptyMap<String, Any?>())
                else mapOf("id" to 2L, "error" to mapOf("message" to error)),
                mapOf("id" to 3L, "result" to emptyMap<String, Any?>()),
            )
            val data = DataOutputStream(out)
            for (frame in frames) {
                val bytes = ToolingClient.encode(frame).toByteArray()
                data.writeInt(bytes.size)
                data.write(bytes)
            }
        }

        override fun getOutputStream() = written
        override fun getInputStream() = incoming
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() { sender.close(); incoming.close() }

        fun requests(): List<Map<String, Any?>> = buildList {
            val input = DataInputStream(ByteArrayInputStream(written.toByteArray()))
            while (input.available() > 0) {
                val bytes = ByteArray(input.readInt())
                input.readFully(bytes)
                add(JsonReader.obj(JsonReader.parse(bytes.toString(Charsets.UTF_8)))!!)
            }
        }

        fun executedTasks(): List<String> {
            val execute = requests().single { it["method"] == "execute" }
            return JsonReader.strings(JsonReader.obj(execute["params"])!!, "tasks")
        }
    }
}
