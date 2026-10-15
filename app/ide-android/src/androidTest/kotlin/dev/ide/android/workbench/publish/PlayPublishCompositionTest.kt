package dev.ide.android.workbench.publish

import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.ide.ui.ext.ScreenBackRegistry
import org.junit.Assert.assertEquals
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.spike.SpikeComposeActivity
import dev.ide.ui.backend.BuildService
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.ProjectInfo
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Render/lifecycle smoke checks using the existing debug-only Activity, without Compose-test dependencies. */
@RunWith(AndroidJUnit4::class)
class PlayPublishCompositionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun panelRendersWithNoProjectAndReleasesItsSecureWindowLease() {
        val visible = mutableStateOf(true)
        val composed = AtomicBoolean(false)
        val backend = emptyBackend()
        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent {
                    MaterialTheme {
                        if (visible.value) {
                            PlayPublishPanel(backend, FileActions.None)
                            SideEffect { composed.set(true) }
                        }
                    }
                }
            }
            await { composed.get() }
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                visible.value = false
            }
            await {
                var removed = false
                scenario.onActivity { removed = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0 }
                removed
            }
        }
    }

    @Test
    fun removingNativeAuthComposableClearsManualTokenAndRejectsLateResults() {
        val visible = mutableStateOf(true)
        val state = AtomicReference<PlayAuthorizationState?>()
        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    if (visible.value) {
                        val auth = rememberPlayAuthorization().state
                        SideEffect { state.set(auth) }
                    }
                }
            }
            await { state.get() != null }
            assertNotNull(state.get())
            scenario.onActivity {
                val auth = requireNotNull(state.get())
                auth.manualAccountLabel = "instrumentation-only"
                auth.manualTokenInput = "not-a-real-token"
                auth.acceptManual()
                assertTrue(auth.tokens.hasToken)
                visible.value = false
            }
            await { state.get()?.closed == true }
            assertFalse(requireNotNull(state.get()).tokens.hasToken)
            assertFalse(requireNotNull(state.get()).connected)
        }
    }

    @Test
    fun bundleAccessAndReleaseStepsAreUsableWithoutADeviceAccount() {
        var browserRequests = 0
        val files = object : FileActions by FileActions.None {
            override val canOpenUrl = true
            override fun openUrl(url: String) { assertEquals(PLAY_OAUTH_PLAYGROUND_URL, url); browserRequests++ }
        }
        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { MaterialTheme { PlayPublishPanel(emptyBackend(), files) } }
            }
            await { hasText("1 of 4 · Bundle") }
            assertFalse(hasText("Choose device account"))
            assertEquals(0, browserRequests)
            clickText("More")
            clickText("Access")
            await { hasText("2 of 4 · Access") && hasText("Access token") }
            assertFalse("No device-account chooser on opening Access", hasText("Choose device account"))
            assertTrue(hasText("No token imported"))
            clickText("Get a token in your browser")
            await { browserRequests == 1 }
            assertTrue("Opening a browser never authenticates this app", hasText("No token imported"))
            clickText("Set up release first")
            await { hasText("3 of 4 · Release") }
            scenario.onActivity { assertTrue(ScreenBackRegistry.consumeBack()) }
            await { hasText("2 of 4 · Access") }
            scenario.onActivity { assertTrue(ScreenBackRegistry.consumeBack()) }
            await { hasText("1 of 4 · Bundle") }
            scenario.onActivity { assertFalse(ScreenBackRegistry.consumeBack()) }
        }
    }

    private fun hasText(text: String): Boolean = withUiNode({ it.isVisibleToUser && it.text?.toString() == text }) { true }

    private fun clickText(text: String) {
        await {
            val clicked = withUiNode({ it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }) { node ->
                var current: AccessibilityNodeInfo? = node
                var parent: AccessibilityNodeInfo? = null
                try {
                    while (current != null) {
                        if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withUiNode true
                        val next = current.parent
                        parent?.recycle()
                        parent = next
                        current = next
                    }
                    false
                } finally { parent?.recycle() }
            }
            if (!clicked) withUiNode({ it.isVisibleToUser && it.isScrollable }) { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            clicked
        }
    }

    private fun withUiNode(match: (AccessibilityNodeInfo) -> Boolean, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (match(node) && action(node)) return true
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { if (visit(child)) return true } finally { child.recycle() }
            }
            return false
        }
        return try { visit(root) } finally { root.recycle() }
    }

    private fun emptyBackend(): IdeBackend {
        val build = object : BuildService {
            override val buildState = MutableStateFlow(BuildState())
            override fun supported() = false
            override fun runBuild() = throw AssertionError("A render must not start a build")
            override fun stopBuild() = Unit
        }
        return Proxy.newProxyInstance(IdeBackend::class.java.classLoader, arrayOf(IdeBackend::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "getProject" -> ProjectInfo("", "", 0)
                "getBuild" -> build
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                "toString" -> "EmptyPublishTestBackend"
                else -> throw AssertionError("Unexpected backend call ${method.name}")
            }
        } as IdeBackend
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("Composition/lifecycle condition did not settle", condition())
    }
}
