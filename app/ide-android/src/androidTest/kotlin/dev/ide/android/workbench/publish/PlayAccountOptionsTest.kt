package dev.ide.android.workbench.publish

import android.accounts.Account
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ide.ui.backend.FileActions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Local-only credentials and browser-handoff checks: no account chooser, Google request or upload. */
@RunWith(AndroidJUnit4::class)
class PlayAccountOptionsTest {
    @Test
    fun manualImportNeedsNoDeviceAccountAndMakesNoNativeCacheCall() = runBlocking {
        val nativeCalls = mutableListOf<String>()
        val auth = PlayAuthorizationState(nativeCalls::add)
        auth.manualAccountLabel = "Publishing session"
        auth.manualTokenInput = "opaque-test-access-token"
        auth.acceptManual()

        assertTrue(auth.connected)
        assertFalse(auth.nativeAccount)
        assertEquals("opaque-test-access-token", auth.tokens.accessToken())
        assertEquals("", auth.manualTokenInput)
        assertTrue(auth.accountLabel.contains("identity not verified"))
        assertEquals(emptyList<String>(), nativeCalls)
        auth.close()
        assertFalse(auth.tokens.hasToken)
        assertEquals(emptyList<String>(), nativeCalls)
    }

    @Test
    fun browserHandoffOnlyOpensOfficialToolAndNeverCreatesSession() {
        val auth = PlayAuthorizationState()
        val files = RecordingFiles()

        val notice = openPlayTokenBrowser(files)

        assertEquals(listOf("https://developers.google.com/oauthplayground/"), files.opened)
        assertTrue(notice.contains("does not authorize this app"))
        assertFalse(auth.connected)
        assertFalse(auth.authorizing)
        assertFalse(auth.tokens.hasToken)
        assertEquals("", auth.manualTokenInput)
    }

    @Test
    fun unsupportedOrFailedBrowserHandoffRemainsHonestAndRedacted() {
        val unavailable = RecordingFiles(browserAvailable = false)
        val unavailableNotice = openPlayTokenBrowser(unavailable)
        assertEquals(emptyList<String>(), unavailable.opened)
        assertTrue(unavailableNotice.contains(PLAY_OAUTH_PLAYGROUND_URL))
        assertTrue(unavailableNotice.contains("No token has been imported"))

        val failing = RecordingFiles(failure = IllegalStateException("Bearer sensitive-fixture"))
        val failureNotice = openPlayTokenBrowser(failing)
        assertTrue(failureNotice.contains("could not open"))
        assertFalse(failureNotice.contains("sensitive-fixture"))
        assertEquals(listOf(PLAY_OAUTH_PLAYGROUND_URL), failing.opened)
    }

    @Test
    fun obviousWrongCredentialTypesAreRejectedAndInputCleared() {
        val invalid = listOf(
            "1/refresh-token-fixture",
            "4/authorization-code-fixture",
            "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJmaXh0dXJlIn0.c2lnbmF0dXJl",
            "{\"private_key\":\"fixture-only\"}",
            "-----BEGIN PRIVATE KEY-----fixture-----END PRIVATE KEY-----",
            "Bearer access-token-fixture",
            "access token with whitespace",
        )
        invalid.forEach { candidate ->
            val auth = PlayAuthorizationState()
            auth.manualAccountLabel = "Unverified fixture"
            auth.manualTokenInput = candidate
            val failure = rejection { auth.acceptManual() }
            assertFalse(auth.connected)
            assertFalse(auth.tokens.hasToken)
            assertEquals("", auth.manualTokenInput)
            assertFalse(failure.message.orEmpty().contains(candidate))
        }
    }

    @Test
    fun failedImportDoesNotReplaceWorkingSessionOrExposeCredential() = runBlocking {
        val auth = importedSession("working-fixture")
        val originalIdentity = auth.identityKey
        val originalLabel = auth.accountLabel
        auth.manualTokenInput = "1/rejected-refresh-fixture"
        rejection { auth.acceptManual() }

        assertEquals("working-fixture", auth.tokens.accessToken())
        assertEquals(originalIdentity, auth.identityKey)
        assertEquals(originalLabel, auth.accountLabel)
        assertEquals("", auth.manualTokenInput)
        assertFalse(auth.toString().contains("working-fixture"))
        assertFalse(auth.tokens.toString().contains("working-fixture"))
        auth.close()
    }

    @Test
    fun labelCannotPretendToBeVerifiedAndEveryImportGetsNewIdentity() {
        val auth = importedSession("first-fixture")
        val firstIdentity = auth.identityKey
        auth.manualAccountLabel = "Verified Google account"
        auth.manualTokenInput = "second-fixture"
        auth.acceptManual()
        assertNotEquals(firstIdentity, auth.identityKey)
        assertTrue(auth.accountLabel.endsWith("(identity not verified)"))
        assertFalse(auth.nativeAccount)
        auth.close()
    }

    @Test
    fun missingOrUnsafeLabelRejectsAndClearsInput() {
        listOf("", " ", "name\nverified", "x".repeat(201)).forEach { label ->
            val auth = PlayAuthorizationState()
            auth.manualAccountLabel = label
            auth.manualTokenInput = "fixture-token"
            rejection { auth.acceptManual() }
            assertFalse(auth.tokens.hasToken)
            assertEquals("", auth.manualTokenInput)
        }
    }

    @Test
    fun importCannotRaceNativeAuthorizationOrReviveClosedSession() {
        val auth = PlayAuthorizationState()
        auth.manualAccountLabel = "Fixture"
        auth.startAttempt()
        auth.manualTokenInput = "racing-fixture"
        rejection { auth.acceptManual() }
        assertTrue(auth.authorizing)
        assertFalse(auth.tokens.hasToken)
        assertEquals("", auth.manualTokenInput)

        auth.close()
        auth.manualTokenInput = "closed-fixture"
        rejection { auth.acceptManual() }
        assertFalse(auth.tokens.hasToken)
        assertEquals("", auth.manualTokenInput)
    }

    @Test
    fun lateNativeResultCannotReplaceImportedToken() = runBlocking {
        val cacheClears = mutableListOf<String>()
        val auth = PlayAuthorizationState(cacheClears::add)
        val oldAttempt = auth.startAttempt()
        auth.disconnect()
        auth.manualAccountLabel = "Manual session"
        auth.manualTokenInput = "manual-fixture"
        auth.acceptManual()
        val importedIdentity = auth.identityKey

        auth.acceptNative("late-native-fixture", Account("fixture@example.invalid", "com.google"), oldAttempt)

        assertEquals("manual-fixture", auth.tokens.accessToken())
        assertEquals(importedIdentity, auth.identityKey)
        assertFalse(auth.nativeAccount)
        assertEquals(listOf("late-native-fixture"), cacheClears)
        auth.close()
    }

    private fun importedSession(token: String) = PlayAuthorizationState().also {
        it.manualAccountLabel = "Unverified fixture"
        it.manualTokenInput = token
        it.acceptManual()
    }

    private fun rejection(action: () -> Unit): IllegalArgumentException = try {
        action()
        throw AssertionError("Expected invalid credential import to fail")
    } catch (failure: IllegalArgumentException) {
        failure
    }

    private class RecordingFiles(
        private val browserAvailable: Boolean = true,
        private val failure: Exception? = null,
    ) : FileActions by FileActions.None {
        val opened = mutableListOf<String>()
        override val canOpenUrl: Boolean get() = browserAvailable
        override fun openUrl(url: String) {
            opened += url
            failure?.let { throw it }
        }
    }
}
