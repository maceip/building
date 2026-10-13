package dev.ide.android.workbench

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.AndroidIde
import dev.ide.android.MainActivity
import dev.ide.ui.backend.BuildService
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.ProjectService
import dev.ide.ui.backend.RunTaskOption
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Real picker navigation: a permissive fake with ProjectInfo("", "", 0) missed the original crash. */
@RunWith(AndroidJUnit4::class)
class WorkbenchNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun adapterHandlesTheThrowingProjectGetterWithoutQueryingProjectTasks() {
        var active: ProjectInfo? = null
        val epoch = MutableStateFlow(0)
        val projects = object : ProjectService { override val projectEpoch = epoch }
        var supportedCalls = 0
        var taskQueries = 0
        var requestedTask: String? = null
        val tasks = listOf(RunTaskOption("test:app", "Test app", "Tests"))
        val build = object : BuildService {
            override val buildState = MutableStateFlow(BuildState())
            override fun supported(): Boolean {
                check(active != null) { "No-project discovery must not reach the engine" }
                supportedCalls++
                return true
            }
            override fun runTasks(): List<RunTaskOption> {
                check(active != null) { "No-project discovery must not reach the engine" }
                taskQueries++
                return tasks
            }
            override fun runTask(id: String) { requestedTask = id }
            override fun runBuild() = throw AssertionError("Rendering must not start a build")
            override fun stopBuild() = Unit
        }
        val delegate = strictBackend(build, projects) { active ?: error("No project is open") }
        val backend = WorkbenchBackend(delegate)
        assertEquals(ProjectInfo("", "", 0), backend.project)
        assertFalse(backend.build.supported())
        assertTrue(backend.build.runTasks().isEmpty())
        assertEquals(0, supportedCalls)
        assertEquals(0, taskQueries)
        assertSame(build.buildState, backend.build.buildState)
        assertSame(projects, backend.projects)

        // The epoch is a generation, not proof that a project getter is currently usable.
        epoch.value = 1
        assertEquals("", backend.project.rootPath)
        active = ProjectInfo("Opened", "/opened", 1)
        assertSame(active, backend.project)
        assertTrue(backend.build.supported())
        assertEquals(tasks, backend.build.runTasks())
        backend.build.runTask(tasks.single().id)
        assertEquals(tasks.single().id, requestedTask)
        active = null
        assertEquals("", backend.project.rootPath)
        assertFalse(backend.build.supported())
    }

    @Test
    fun adapterDoesNotHideAnUnrelatedProjectFailure() {
        val failure = IllegalStateException("Project metadata is corrupt")
        val build = object : BuildService {
            override val buildState = MutableStateFlow(BuildState())
            override fun runBuild() = Unit
            override fun stopBuild() = Unit
        }
        val backend = WorkbenchBackend(strictBackend(build, object : ProjectService {}) { throw failure })
        try {
            backend.project
            fail("Unrelated project failures must not become an empty workspace")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
    }

    @Test
    fun pickerSettingsOpensWorkbenchWithoutAProjectAndBackStillNavigates() {
        // MainActivity installs its own process-wide handler. Do not leak it into subsequent tests.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        var backend: IdeBackend? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitUi("engine bootstrap", { backend }, timeoutMs = 90_000) {
                    scenario.onActivity { activity ->
                        val field = MainActivity::class.java.getDeclaredField("session").apply { isAccessible = true }
                        backend = (field.get(activity) as? AndroidIde.Session)?.backend
                    }
                    backend != null
                }
                val real = requireNotNull(backend)
                assertEquals("Run this picker regression with no resumed project", 0, real.projects.projectEpoch.value)
                try {
                    real.project
                    fail("The real project getter must reproduce the no-project precondition")
                } catch (missing: IllegalStateException) {
                    assertEquals("No project is open", missing.message)
                }

                // Dismiss the actual first-launch surfaces rather than calling navigation/state methods.
                awaitUi("first-launch sheets dismissed", { real }, timeoutMs = 30_000) {
                    when {
                        clickVisible("Got it") -> false
                        clickVisible("Skip for now") -> false
                        clickVisible("Maybe later") -> false
                        clickVisible("No thanks") -> false
                        else -> hasVisible("Settings and tools")
                    }
                }
                click("Settings and tools", real)
                awaitUi("Settings hub", { real }) { hasVisible("Settings") }

                // A control destination proves the hub is live before the workbench is opened.
                clickAfterScroll("Keystore Manager", real)
                awaitUi("Keystore Manager destination", { real }) {
                    hasVisible("Keystore Manager") && hasVisible("Create") && hasVisible("Import")
                }
                click("Back", real)
                awaitUi("back from Keystore Manager", { real }) { hasVisible("Settings") }

                clickAfterScroll("Developer workbench", real)
                // The hub row has the same title; the three tabs and runtime body prove actual navigation.
                awaitUi("workbench Runtime destination", { real }) {
                    hasVisible("Developer workbench") && hasVisible("Runtime") && hasVisible("Quality") &&
                        hasVisible("Publish") && hasVisible("Ready · no session started")
                }
                click("Quality", real)
                awaitUi("workbench Quality destination", { real }) { hasVisible("App Quality & Reliability Lab") }
                click("Publish", real)
                awaitUi("workbench Publish destination", { real }) { hasVisible("Google Play publishing") }
                click("Runtime", real)
                awaitUi("return to Runtime", { real }) { hasVisible("Ready · no session started") }
                click("Back", real)
                awaitUi("back from workbench", { real }) { hasVisible("Settings") && !hasVisible("Runtime") }
                click("Back", real)
                awaitUi("back to project picker", { real }) { hasVisible("Settings and tools") }
                assertNoBackendError(real, "completed navigation")
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    private fun strictBackend(
        build: BuildService,
        projects: ProjectService,
        project: () -> ProjectInfo,
    ): IdeBackend = Proxy.newProxyInstance(
        IdeBackend::class.java.classLoader,
        arrayOf(IdeBackend::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "getProject" -> project()
            "getBuild" -> build
            "getProjects" -> projects
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.firstOrNull()
            "toString" -> "ThrowingNoProjectWorkbenchBackend"
            else -> throw AssertionError("Unexpected backend call ${method.name}")
        }
    } as IdeBackend

    private fun click(label: String, backend: IdeBackend) {
        awaitUi("click '$label'", { backend }) { clickVisible(label) }
    }

    private fun clickAfterScroll(label: String, backend: IdeBackend) {
        repeat(16) {
            assertNoBackendError(backend, "find '$label'")
            if (clickVisible(label)) return
            withNode({ it.isVisibleToUser && it.isScrollable }) {
                it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            SystemClock.sleep(200)
        }
        throw AssertionError("Could not reach '$label'. ${diagnostic(backend)}\nVisible UI: ${visibleLabels()}")
    }

    private fun hasVisible(label: String): Boolean = withNode({ it.matches(label) }) { true }

    private fun clickVisible(label: String): Boolean = withNode({ it.matches(label) }) { node ->
        // Compose often exposes text as a child of the actual clickable semantics node.
        var current: AccessibilityNodeInfo? = node
        var ownedParent: AccessibilityNodeInfo? = null
        try {
            while (current != null) {
                if (current.isEnabled && current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return@withNode true
                }
                val parent = current.parent
                ownedParent?.recycle()
                ownedParent = parent
                current = parent
            }
            false
        } finally {
            ownedParent?.recycle()
        }
    }

    private fun AccessibilityNodeInfo.matches(label: String): Boolean =
        isVisibleToUser && (text?.toString() == label || contentDescription?.toString() == label)

    private fun withNode(
        predicate: (AccessibilityNodeInfo) -> Boolean,
        action: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        val root = automation.rootInActiveWindow ?: return false
        try {
            return visit(root, predicate, action)
        } finally {
            root.recycle()
        }
    }

    private fun visit(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        action: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        if (predicate(node) && action(node)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (visit(child, predicate, action)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun awaitUi(
        stage: String,
        backend: () -> IdeBackend?,
        timeoutMs: Long = 15_000,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            assertNoBackendError(backend(), stage)
            if (condition()) {
                assertNoBackendError(backend(), stage)
                return
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out at $stage. ${diagnostic(backend())}\nVisible UI: ${visibleLabels()}")
    }

    private fun assertNoBackendError(backend: IdeBackend?, stage: String) {
        val error = backend?.diagnostics?.errorEvents?.value ?: return
        val detail = "$stage: ${error.title}: ${error.message}\n${error.detail}"
        // The app's uncaught handler logs to its internal ring, not Android's FATAL logcat buffer.
        Log.e("WorkbenchNavigationTest", detail)
        throw AssertionError(detail)
    }

    private fun diagnostic(backend: IdeBackend?): String {
        if (backend == null) return "MainActivity.session is not ready."
        val error = backend.diagnostics.errorEvents.value
        return if (error == null) "No backend error reported."
        else "${error.title}: ${error.message}\n${error.detail}"
    }

    private fun visibleLabels(): String {
        val labels = linkedSetOf<String>()
        withNode({ it.isVisibleToUser }) { node ->
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(labels::add)
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(labels::add)
            false
        }
        return labels.joinToString(" | ").take(4_000)
    }
}
