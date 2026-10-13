// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.android.workbench.appengine

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.spike.SpikeComposeActivity
import dev.ide.ui.backend.BuildService
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.ProjectService
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the public entry and real button clicks, never a test-only expanded/review/runtime state. */
@RunWith(AndroidJUnit4::class)
class AppEnginePackageCompositionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun compactEntryOpensAndClosesBoundedDialogWithThrowingNoProjectBackend() {
        val projectReads = AtomicInteger()
        val backend = noProjectBackend(projectReads)
        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { MaterialTheme { AppEnginePackagePanel(backend, FileActions.None) } }
            }
            await { visible("Package / build") }
            assertEquals("Collapsed entry must not query a project", 0, projectReads.get())
            click("Package / build")
            await { visible("Embed an app engine") && visible("Close") }
            assertTrue(projectReads.get() > 0)
            assertTrue("Dialog body must scroll instead of overflowing its host", find { it.isScrollable } != null)
            click("Close")
            await { visible("Package / build") && !visible("Embed an app engine") }
        }
    }

    @Test fun emptyProjectSourceFailsClosedThroughRealReviewButton() {
        val backend = noProjectBackend(AtomicInteger())
        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { MaterialTheme { AppEnginePackagePanel(backend, FileActions.None) } }
            }
            await { visible("Package / build") }
            click("Package / build")
            await { visible("Embed an app engine") }
            scrollTo("Hash inputs and review")
            click("Hash inputs and review")
            // The error appears at the start of the scrollable body; no source/SDK is synthesized.
            repeat(16) { find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); instrumentation.waitForIdleSync() }
            await { visible("Source workspace is required; no open project is not a source folder.") }
            assertTrue(visible("Close"))
        }
    }

    private fun noProjectBackend(projectReads: AtomicInteger): IdeBackend {
        val build = object : BuildService {
            override val buildState = MutableStateFlow(BuildState())
            override fun runBuild() = throw AssertionError("Rendering must not build")
            override fun stopBuild() = throw AssertionError("Rendering must not stop other work")
            override fun runTasks() = throw AssertionError("No-project render must not discover tasks")
            override fun supported() = throw AssertionError("No-project render must not query engine availability")
        }
        val projects = object : ProjectService {
            override val projectEpoch = MutableStateFlow(0)
            override fun projectsRootPath(): String? = null
            override suspend fun openProject(rootPath: String): Boolean = throw AssertionError("Rendering must not switch workspaces")
        }
        return Proxy.newProxyInstance(IdeBackend::class.java.classLoader, arrayOf(IdeBackend::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "getProject" -> { projectReads.incrementAndGet(); error("No project is open") }
                "getProjects" -> projects
                "getBuild" -> build
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                "toString" -> "NoProjectEnginePanelFake"
                else -> throw AssertionError("Unexpected backend call ${method.name}")
            }
        } as IdeBackend
    }

    private fun scrollTo(text: String) {
        repeat(24) {
            instrumentation.waitForIdleSync()
            if (visible(text)) return
            find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        assertTrue("Expected visible control: $text", visible(text))
    }

    private fun click(text: String) {
        var node = find { it.text?.toString() == text || it.contentDescription?.toString() == text }
        while (node != null) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        throw AssertionError("Control not clickable: $text")
    }

    private fun visible(text: String): Boolean = find { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) } != null

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { child -> visit(child)?.let { return it } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue("Panel UI did not settle", condition())
    }
}
