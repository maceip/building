package dev.ide.android.workbench.publish

import android.accounts.Account
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.spike.SpikeComposeActivity
import dev.ide.workbench.publish.PlayAccountAttestations
import dev.ide.workbench.publish.PlayDistributionGoal
import dev.ide.workbench.publish.PlayErrorKind
import dev.ide.workbench.publish.PlayPublishingException
import dev.ide.workbench.publish.PlayReleaseStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No Google account or production credential is required and no Google API request is sent. */
@RunWith(AndroidJUnit4::class)
class PlayPublishSafetyTest {
    @Test
    fun nativeRequestUsesPublishingScopeAndExplicitChosenAccount() {
        val account = Account("publisher@example.invalid", "com.google")
        val request = publisherAuthorizationRequest(account)
        assertEquals(account, request.account)
        assertEquals(listOf(ANDROID_PUBLISHER_SCOPE), request.requestedScopes.map { it.scopeUri })
    }

    @Test
    fun manualTokensAreMemoryOnlyRedactedAndClearedOnClose() = runBlocking {
        val state = PlayAuthorizationState()
        state.manualAccountLabel = "Device test"
        state.manualTokenInput = "not-a-real-access-token"
        state.acceptManual()
        assertTrue(state.connected)
        assertTrue(state.tokens.hasToken)
        assertEquals("", state.manualTokenInput)
        assertTrue(state.accountLabel.contains("identity not verified"))
        assertFalse(state.toString().contains("not-a-real-access-token"))
        assertFalse(state.tokens.toString().contains("not-a-real-access-token"))
        state.close()
        assertFalse(state.connected)
        assertFalse(state.tokens.hasToken)
        assertEquals("", state.manualTokenInput)
        try {
            state.tokens.accessToken()
            throw AssertionError("A closed session returned a token")
        } catch (expected: PlayPublishingException) {
            assertEquals(PlayErrorKind.AUTHORIZATION, expected.kind)
        }
        assertFalse(PlayAuthorizationState().tokens.hasToken)
    }

    @Test
    fun disconnectedNativeSessionCannotBeResurrectedByLateCallbacks() {
        val cacheClears = mutableListOf<String>()
        val state = PlayAuthorizationState(cacheClears::add)
        val attempt = state.startAttempt()
        state.disconnect()
        state.acceptNative("late-fake-token", Account("publisher@example.invalid", "com.google"), attempt)
        assertFalse(state.connected)
        assertFalse(state.tokens.hasToken)
        assertEquals(listOf("late-fake-token"), cacheClears)
    }

    @Test
    fun nativeRenewalPinsAccountButEveryManualReplacementGetsNewIdentity() {
        val state = PlayAuthorizationState()
        val one = Account("one@example.invalid", "com.google")
        state.acceptNative("fake-one", one, state.startAttempt())
        val firstIdentity = state.identityKey
        state.disconnect()
        state.acceptNative("fake-one-renewed", one, state.startAttempt())
        assertEquals(firstIdentity, state.identityKey)
        state.acceptNative("fake-two", Account("two@example.invalid", "com.google"), state.startAttempt())
        assertNotEquals(firstIdentity, state.identityKey)
        state.manualAccountLabel = "same label"
        state.manualTokenInput = "fake-manual-one"
        state.acceptManual()
        val manualIdentity = state.identityKey
        state.manualTokenInput = "fake-manual-two"
        state.acceptManual()
        assertNotEquals(manualIdentity, state.identityKey)
        state.close()
    }

    @Test
    fun tokenReplacementAndDisconnectClearNativeCachedToken() {
        val cacheClears = mutableListOf<String>()
        val state = PlayAuthorizationState(cacheClears::add)
        val account = Account("publisher@example.invalid", "com.google")
        state.acceptNative("fake-one", account, state.startAttempt())
        state.acceptNative("fake-two", account, state.startAttempt())
        state.disconnect()
        assertEquals(listOf("fake-one", "fake-two"), cacheClears)
    }

    @Test
    fun sensitiveExceptionDetailsAreNeverRendered() {
        val marker = "Bearer sensitive-test-token"
        assertFalse(safePublishError(IllegalStateException(marker)).contains(marker))
        assertFalse(safeGoogleAuthorizationError(IllegalStateException(marker)).contains(marker))
        val safe = PlayPublishingException(PlayErrorKind.PERMISSION, "Check Play app permissions.")
        assertEquals("Check Play app permissions.", safePublishError(safe))
    }

    @Test
    fun draftIsAStatusAndProductionDraftStillRequiresProductionAttestations() {
        val internalDraft = PublishForm(attestations = basicAttestations())
        validatePublishAttestations(internalDraft)
        assertEquals(PlayReleaseStatus.DRAFT, internalDraft.release(123).status)
        val productionDraft = internalDraft.copy(trackId = "production")
        assertEquals(PlayDistributionGoal.PRODUCTION, productionDraft.goal)
        expectValidationFailure { validatePublishAttestations(productionDraft) }
        assertEquals(PlayDistributionGoal.PRODUCTION, internalDraft.copy(trackId = "wear:production").goal)
        expectValidationFailure { validatePublishAttestations(internalDraft.copy(trackId = "wear:production")) }
        assertEquals(PlayDistributionGoal.OPEN_TESTING, internalDraft.copy(trackId = "automotive:beta").goal)
    }

    @Test
    fun closedAndProductionRequireHonestHumanPolicyAnswers() {
        val closed = PublishForm(
            destination = PublishDestination.CLOSED,
            trackId = "closed-testers",
            privacyPolicyUrl = "https://example.invalid/privacy",
            attestations = basicAttestations(),
        )
        expectValidationFailure { validatePublishAttestations(closed) }
        val reviewed = closed.copy(attestations = basicAttestations().copy(
            requiredPolicyDeclarationsReviewed = true,
            storeListingAndDistributionConfigured = true,
            dataSafetyReviewed = true,
        ))
        validatePublishAttestations(reviewed)
        val production = reviewed.copy(destination = PublishDestination.PRODUCTION, trackId = "production")
        expectValidationFailure { validatePublishAttestations(production) }
        validatePublishAttestations(production.copy(attestations = production.attestations.copy(productionAccessApproved = true)))
        expectValidationFailure { validatePublishAttestations(reviewed.copy(privacyPolicyUrl = "https://user:password@example.invalid/privacy")) }
    }

    @Test
    fun installedPackageAndCertificateAreAvailableForOAuthSetup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = readPlayOAuthRegistration(context)
        assertEquals(context.packageName, info.packageName)
        assertTrue(info.signingSha1.isNotEmpty())
        assertTrue(info.signingSha1.all { it.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){19}")) })
    }

    @Test
    fun secureWindowLeasePreservesAnExistingHostFlagAndNests() {
        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val window = activity.window
                val original = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                try {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    PublishSecureWindow.acquire(window)
                    PublishSecureWindow.acquire(window)
                    assertSecure(window.attributes.flags, true)
                    PublishSecureWindow.release(window)
                    assertSecure(window.attributes.flags, true)
                    PublishSecureWindow.release(window)
                    assertSecure(window.attributes.flags, false)
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    PublishSecureWindow.acquire(window)
                    PublishSecureWindow.release(window)
                    assertSecure(window.attributes.flags, true)
                } finally {
                    if (original) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }

    private fun assertSecure(flags: Int, expected: Boolean) {
        assertEquals(expected, flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    private fun basicAttestations() = PlayAccountAttestations(
        accountRegisteredAndVerified = true,
        appCreatedAndInitializedInConsole = true,
        uploadKeyAndPlayAppSigningConfigured = true,
    )

    private fun expectValidationFailure(block: () -> Unit) {
        val failure = try { block(); null } catch (failure: PlayPublishingException) { failure }
        assertNotNull("Missing explicit manual evidence must block staging", failure)
        assertEquals(PlayErrorKind.VALIDATION, failure!!.kind)
    }
}
