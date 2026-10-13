package dev.ide.android.workbench.publish

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Pure URL-policy checks: no accounts, credentials, browser launches or network requests. */
@RunWith(AndroidJUnit4::class)
class PlayConsoleNavigationTest {
    @Test
    fun ordinaryConsolePathsAndQueriesAreAllowed() {
        listOf(
            PLAY_CONSOLE_URL,
            "https://play.google.com/console",
            "https://play.google.com/console/u/0/developers/123/app/456/tracks/internal-testing",
            "https://play.google.com/console/u/1/developers?hl=en#all-apps",
            "https://PLAY.GOOGLE.COM:443/console/",
        ).forEach { assertEquals(it, PlayConsoleDestination.CONSOLE, playConsoleDestination(it)) }
    }

    @Test
    fun spoofedHostsSiblingPathsAndParserAmbiguitiesAreBlocked() {
        listOf(
            "https://play.google.com.evil.example/console/",
            "https://evilplay.google.com/console/",
            "https://evil.example/console/?next=https://play.google.com/console/",
            "https://play.google.com./console/",
            "https://play%2egoogle.com/console/",
            "https://play.google.com/store/",
            "https://play.google.com/console-other/",
            "https://play.google.com/Console/",
            "https://play.google.com/console/../store/",
            "https://play.google.com/console/%2e%2e/store/",
            "https://play.google.com/console/%252e%252e/store/",
            "https://play.google.com/console/%2F..%2Fstore/",
            "https://play.google.com/console/%5c..%5cstore/",
            "https://play.google.com/console/%00",
            "https://play.google.com:8443/console/",
            "https://play.google.com:/console/",
            "https://play.google.com\\@evil.example/console/",
            " https://play.google.com/console/",
            "https://play.google.com/console/\n",
        ).forEach { assertEquals(it, PlayConsoleDestination.BLOCKED, playConsoleDestination(it)) }
    }

    @Test
    fun credentialsUnsafeSchemesAndMalformedUrlsAreNeverEmbedded() {
        listOf(
            "https://user:password@play.google.com/console/",
            "https://user@play.google.com/console/",
            "https://play.google.com@evil.example/console/",
            "https://user@accounts.google.com/o/oauth2/v2/auth",
            "http://play.google.com/console/",
            "//play.google.com/console/",
            "javascript:location='https://play.google.com/console/'",
            "intent://play.google.com/console/#Intent;scheme=https;end",
            "file:///console/index.html",
            "content://play.google.com/console/",
            "data:text/html,Console",
            "about:blank",
            "mailto:publisher@example.invalid",
            "https:///play.google.com/console/",
            "https://play.google.com/console/%not-encoded",
            "",
        ).forEach { assertEquals(it, PlayConsoleDestination.BLOCKED, playConsoleDestination(it)) }
    }

    @Test
    fun googleAccountRedirectsRequireManualHandoffNotEmbeddedSuccess() {
        listOf(
            "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fplay.google.com%2Fconsole%2F",
            "https://accounts.google.com/o/oauth2/v2/auth?client_id=fixture-only",
            "https://accounts.google.com/v3/signin/identifier",
        ).forEach {
            assertEquals(PlayConsoleDestination.GOOGLE_SIGN_IN, playConsoleDestination(it))
            assertFalse(playConsoleResourceAllowed(it))
        }
        assertEquals(PlayConsoleDestination.BLOCKED, playConsoleDestination("https://accounts.google.com.evil.example/signin"))
        assertEquals(PlayConsoleDestination.BLOCKED, playConsoleDestination("http://accounts.google.com/ServiceLogin"))
    }

    @Test
    fun everyRedirectTargetMustPassItsOwnMainFrameCheck() {
        // A Console source URL does not confer authority on any redirect's final destination.
        assertEquals(PlayConsoleDestination.CONSOLE, playConsoleDestination("https://play.google.com/console/?continue=https%3A%2F%2Fevil.example"))
        assertEquals(PlayConsoleDestination.BLOCKED, playConsoleDestination("https://evil.example/"))
        assertEquals(PlayConsoleDestination.GOOGLE_SIGN_IN, playConsoleDestination("https://accounts.google.com/ServiceLogin"))
        assertEquals(PlayConsoleDestination.CONSOLE, playConsoleDestination(PLAY_CONSOLE_URL))
    }

    @Test
    fun httpsAssetsDoNotExpandTheMainFrameAllowlist() {
        val asset = "https://www.gstatic.com/fixture-console-script.js"
        assertTrue(playConsoleResourceAllowed(asset))
        assertEquals(PlayConsoleDestination.BLOCKED, playConsoleDestination(asset))
        assertFalse(playConsoleResourceAllowed("http://www.gstatic.com/fixture.js"))
        assertFalse(playConsoleResourceAllowed("https://user:password@www.gstatic.com/fixture.js"))
        assertFalse(playConsoleResourceAllowed("file:///sdcard/fixture.js"))
    }
}
