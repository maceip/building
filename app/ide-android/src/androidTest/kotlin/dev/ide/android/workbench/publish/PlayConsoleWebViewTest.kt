package dev.ide.android.workbench.publish

import android.app.KeyguardManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.spike.SpikeComposeActivity
import dev.ide.ui.backend.FileActions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Unloaded real WebView + callback tests. No Google requests, accounts, credentials or browser launch. */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class PlayConsoleWebViewTest {
    @Test
    fun entryCompositionAndRecompositionDoNotOpenWebViewOrExternalBrowser() {
        val generation = mutableStateOf(0)
        val composed = AtomicInteger(-1)
        val browserCalls = AtomicInteger(0)
        val actions = object : FileActions by FileActions.None {
            override val canOpenUrl = true
            override fun openUrl(url: String) { browserCalls.incrementAndGet() }
        }
        // Initial composition can run without a frame even when the physical display is locked/off;
        // recomposition cannot. Report that device prerequisite, rather than skipping or weakening the test.
        assertInteractiveUnlockedDevice("before launching composition activity")
        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertCompositionDeviceReady(scenario, "before initial composition")
            scenario.onActivity { activity ->
                // Test-local only; this neither wakes the display nor dismisses/bypasses keyguard.
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            try {
                scenario.onActivity { activity ->
                    activity.setContent {
                        val observed = generation.value
                        MaterialTheme { ManualPlayConsoleEntry(actions) }
                        SideEffect { composed.set(observed) }
                    }
                }
                awaitComposition(scenario, "initial composition", composed, browserCalls) { composed.get() == 0 }
                scenario.onActivity { activity ->
                    assertFalse(containsWebView(activity.window.decorView))
                    assertEquals(0, browserCalls.get())
                    generation.value++
                }
                awaitComposition(scenario, "recomposition after generation change", composed, browserCalls) { composed.get() == 1 }
                scenario.onActivity { activity -> assertFalse(containsWebView(activity.window.decorView)) }
                assertEquals(0, browserCalls.get())
            } finally {
                scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }

    @Test
    fun settingsPermitConsoleScriptsButNotLocalFilesThirdPartyCookiesOrAutofill() = withView { view, _ ->
        assertTrue(view.settings.javaScriptEnabled)
        assertTrue(view.settings.domStorageEnabled)
        assertFalse(view.settings.javaScriptCanOpenWindowsAutomatically)
        assertFalse(view.settings.allowFileAccess)
        assertFalse(view.settings.allowContentAccess)
        assertFalse(view.settings.allowFileAccessFromFileURLs)
        assertFalse(view.settings.allowUniversalAccessFromFileURLs)
        assertFalse(view.settings.saveFormData)
        assertFalse(view.isSaveEnabled)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, view.importantForAutofill)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, view.settings.mixedContentMode)
        assertTrue(view.settings.safeBrowsingEnabled)
        assertTrue(CookieManager.getInstance().acceptCookie())
        assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(view))
        assertEquals(WebSettings.getDefaultUserAgent(view.context), view.settings.userAgentString)
        assertNull("The WebView factory must not issue a request", view.url)
    }

    @Test
    fun mainFrameCallbacksRejectOAuthWithoutOpeningOrLoadingIt() = withView { view, state ->
        val client = view.webViewClient
        val signIn = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fplay.google.com%2Fconsole%2F"
        assertFalse(client.shouldOverrideUrlLoading(view, Request(PLAY_CONSOLE_URL)))
        assertTrue(client.shouldOverrideUrlLoading(view, Request(signIn)))
        assertEquals(PlayConsolePage.SIGN_IN_REQUIRED, state.page)
        assertTrue(state.page.hidesPage)
        // Completion from the old document and a browser-return observation cannot become sign-in proof.
        client.onPageFinished(view, PLAY_CONSOLE_URL)
        assertEquals(PlayConsolePage.SIGN_IN_REQUIRED, state.page)
        assertNull(view.url)
        state.beginLoad() // Only a manual reload/back/forward action does this in the panel.
        client.onPageStarted(view, signIn, null)
        assertEquals(PlayConsolePage.SIGN_IN_REQUIRED, state.page)
        assertNull(view.url)
    }

    @Test
    fun redirectSpoofsCredentialsAndExternalIntentsAreNeverAutomaticallyDispatched() = withView { view, state ->
        listOf(
            "https://play.google.com.evil.example/console/",
            "https://user:password@play.google.com/console/",
            "http://play.google.com/console/",
            "intent://play.google.com/console/#Intent;scheme=https;end",
            "javascript:alert('fixture')",
            "file:///sdcard/fixture.html",
        ).forEach { destination ->
            state.beginLoad()
            assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, Request(destination)))
            assertEquals(PlayConsolePage.NAVIGATION_BLOCKED, state.page)
            assertNull(view.url)
        }
    }

    @Test
    fun accountSubframesAreBlockedButHttpsAssetsAreNotMainFrameDocuments() = withView { view, state ->
        val client = view.webViewClient
        assertFalse(client.shouldOverrideUrlLoading(view, Request("https://www.gstatic.com/fixture.js", mainFrame = false)))
        assertNull(client.shouldInterceptRequest(view, Request("https://www.gstatic.com/fixture.js", mainFrame = false)))
        val blocked = client.shouldInterceptRequest(view, Request("https://accounts.google.com/ServiceLogin", mainFrame = false))
        assertNotNull(blocked)
        assertEquals(403, blocked!!.statusCode)
        blocked.data.close()
        assertTrue(client.shouldOverrideUrlLoading(view, Request("https://accounts.google.com/ServiceLogin", mainFrame = false)))
        assertEquals(PlayConsolePage.SIGN_IN_REQUIRED, state.page)
        assertNull(view.url)
    }

    @Test
    fun normalPageCompletionDoesNotClaimAuthenticationAndPopupsRequireBrowser() = withView { view, state ->
        view.webViewClient.onPageFinished(view, PLAY_CONSOLE_URL)
        assertEquals(PlayConsolePage.PAGE_LOADED, state.page)
        assertTrue(state.page.message.contains("not verified"))
        assertFalse(requireNotNull(view.webChromeClient).onCreateWindow(view, false, true, Message.obtain()))
        assertEquals(PlayConsolePage.BROWSER_REQUIRED, state.page)
        assertNull(view.url)
    }

    @Test
    fun backForwardOrReloadCannotSilentlyResubmitAForm() = withView { view, state ->
        val dontResend = AtomicBoolean(false)
        val resend = AtomicBoolean(false)
        val callback = object : Handler(Looper.getMainLooper()) {
            override fun sendMessageAtTime(message: Message, uptimeMillis: Long): Boolean {
                if (message.what == 1) dontResend.set(true) else resend.set(true)
                return true
            }
        }
        view.webViewClient.onFormResubmission(view, Message.obtain(callback, 1), Message.obtain(callback, 2))
        assertTrue(dontResend.get())
        assertFalse(resend.get())
        assertEquals(PlayConsolePage.BROWSER_REQUIRED, state.page)
        assertNull(view.url)
    }

    private fun withView(block: (WebView, PlayConsoleViewState) -> Unit) {
        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val disposed = AtomicBoolean(false)
                val state = PlayConsoleViewState()
                val view = createPlayConsoleWebView(activity, state, disposed)
                try { block(view, state) } finally {
                    disposed.set(true)
                    destroyPlayConsoleWebView(view)
                }
            }
        }
    }

    private class Request(address: String, private val mainFrame: Boolean = true) : WebResourceRequest {
        private val address = Uri.parse(address)
        override fun getUrl(): Uri = address
        override fun isForMainFrame(): Boolean = mainFrame
        override fun isRedirect(): Boolean = true
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private fun containsWebView(view: View): Boolean = view is WebView ||
        (view is ViewGroup && (0 until view.childCount).any { containsWebView(view.getChildAt(it)) })

    private fun assertInteractiveUnlockedDevice(phase: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val power = requireNotNull(context.getSystemService(PowerManager::class.java))
        val keyguard = requireNotNull(context.getSystemService(KeyguardManager::class.java))
        val interactive = power.isInteractive
        val keyguardLocked = keyguard.isKeyguardLocked
        val deviceLocked = keyguard.isDeviceLocked
        assertTrue(
            "BLOCKED: Console composition test requires an awake, user-unlocked device during $phase. " +
                "interactive=$interactive, keyguardLocked=$keyguardLocked, deviceLocked=$deviceLocked. " +
                "An authorized wake action cannot unlock a secure keyguard; the user must unlock and rerun. " +
                "This is not a passing or skipped UI test; no unlock is attempted.",
            interactive && !keyguardLocked && !deviceLocked,
        )
    }

    private fun assertCompositionDeviceReady(scenario: ActivityScenario<SpikeComposeActivity>, phase: String) {
        assertInteractiveUnlockedDevice(phase)
        assertEquals("BLOCKED: Console composition activity must be RESUMED during $phase", Lifecycle.State.RESUMED, scenario.state)
        scenario.onActivity { activity ->
            assertEquals("BLOCKED: Console composition lifecycle must be RESUMED during $phase", Lifecycle.State.RESUMED, activity.lifecycle.currentState)
        }
    }

    private fun awaitComposition(
        scenario: ActivityScenario<SpikeComposeActivity>,
        phase: String,
        composed: AtomicInteger,
        browserCalls: AtomicInteger,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            assertCompositionDeviceReady(scenario, phase)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals("Composition must not launch an external browser during $phase", 0, browserCalls.get())
            if (condition()) return
            Thread.sleep(20)
        }
        assertCompositionDeviceReady(scenario, phase)
        var windowState = "unavailable"
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            windowState = "focused=${activity.hasWindowFocus()}, attached=${decor.isAttachedToWindow}, " +
                "visible=${decor.windowVisibility == View.VISIBLE}, shown=${decor.isShown}"
        }
        assertTrue("Console $phase did not settle in 5 seconds on an awake/unlocked/resumed activity; composed=${composed.get()}, browserCalls=${browserCalls.get()}, $windowState", condition())
    }
}
