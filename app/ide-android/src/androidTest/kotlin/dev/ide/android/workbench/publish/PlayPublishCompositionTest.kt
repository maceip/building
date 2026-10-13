package dev.ide.android.workbench.publish

import android.view.WindowManager
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
