package dev.ide.android.workbench.publish

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.workbench.publish.PlayAccountAttestations
import dev.ide.workbench.publish.PlayErrorKind
import dev.ide.workbench.publish.PlayImageType
import dev.ide.workbench.publish.PlayPublisherClient
import dev.ide.workbench.publish.PlayPublisherConfig
import dev.ide.workbench.publish.PlayPublishingException
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only a loopback fake receives synthetic credentials. This suite must never call a Google endpoint. */
@RunWith(AndroidJUnit4::class)
class PlayPublishControllerTest {
    @Test
    fun constructingStateDoesNotWriteAndOnlyConfirmedCreateAndDiscardSendRequests() = runBlocking {
        LoopbackPlayServer().use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val auth = session()
            val state = state(auth, scope, server)
            try {
                withContext(Dispatchers.Main) { state.synchronizeIdentity() }
                assertTrue(server.requests.isEmpty())
                create(state, auth, server)
                assertEquals(listOf("POST /androidpublisher/v3/applications/example.app/edits", "GET /androidpublisher/v3/applications/example.app/edits/edit123/tracks"), server.requests.toList())
                assertEquals("edit123", state.edit?.id)
                assertNull(state.commitPreview)
                val edit = requireNotNull(state.edit)
                server.reply(204, "")
                awaitOperation(state) { state.discardAfterConfirmation(edit, auth.identityKey) }
                assertNull(state.edit)
                assertTrue(server.requests.last().startsWith("DELETE "))
                assertFalse(server.requests.any { ":commit" in it || "bundles" in it })
            } finally { withContext(Dispatchers.Main) { state.close() }; scope.cancel() }
        }
    }

    @Test
    fun manualReplacementInvalidatesOldClientWithoutDeletingOldEdit() = runBlocking {
        LoopbackPlayServer().use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val auth = session()
            val state = state(auth, scope, server)
            try {
                create(state, auth, server)
                withContext(Dispatchers.Main) {
                    auth.manualTokenInput = "fake-second-access-token"
                    auth.acceptManual() // Same label is not proof of the same identity.
                    state.synchronizeIdentity()
                }
                assertNull(state.edit)
                assertNull(state.commitPreview)
                assertTrue(state.tracks.isEmpty())
                assertEquals("edit123", state.recoveryEdits.single().edit.id)
                assertEquals(2, server.requests.size)
                assertFalse(server.requests.any { it.startsWith("DELETE") })
            } finally { withContext(Dispatchers.Main) { state.close() }; scope.cancel() }
        }
    }

    @Test
    fun wrongIdentityCannotExecuteAConfirmedAction() = runBlocking {
        LoopbackPlayServer().use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val auth = session()
            val state = state(auth, scope, server)
            try {
                awaitOperation(state) { state.createEditAfterConfirmation("example.app", "a-previous-identity") }
                assertTrue(server.requests.isEmpty())
                assertNull(state.edit)
                assertNotNull(state.error)
                assertFalse(auth.tokens.toString().contains("fake-test-access-token"))
            } finally { withContext(Dispatchers.Main) { state.close() }; scope.cancel() }
        }
    }

    @Test
    fun unauthorizedReadClearsTokenRetainsNonsecretFormAndDoesNotRetry() = runBlocking {
        LoopbackPlayServer().use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val auth = session()
            val state = state(auth, scope, server)
            try {
                withContext(Dispatchers.Main) { state.form = state.form.copy(packageName = "example.app", title = "Retained listing") }
                server.reply(200, """{"releases":[]}""")
                awaitOperation(state) { state.checkAccess() }
                assertTrue(state.accessChecked)
                server.reply(401, """{"error":{"message":"Bearer secret-response-marker"}}""")
                awaitOperation(state) { state.monitorReleases() }
                assertFalse(state.accessChecked)
                assertNull(state.accessCheck)
                assertFalse(auth.connected)
                assertFalse(auth.tokens.hasToken)
                assertEquals("Retained listing", state.form.title)
                assertEquals(2, server.requests.size)
                assertTrue(server.requests.all { it.startsWith("GET ") })
                assertFalse(state.error.orEmpty().contains("secret-response-marker"))
                assertTrue(auth.error.orEmpty().contains("Authorize again"))
            } finally { withContext(Dispatchers.Main) { state.close() }; scope.cancel() }
        }
    }

    @Test
    fun createNetworkFailureIsUncertainAndNeverRetried() = runBlocking {
        LoopbackPlayServer().use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val auth = session()
            val state = state(auth, scope, server)
            try {
                server.reply(503, "{}")
                awaitOperation(state) { state.createEditAfterConfirmation("example.app", auth.identityKey) }
                assertTrue(state.resultUncertain)
                assertEquals(1, server.requests.size)
                assertNull(state.commitPreview)
                assertNotNull(state.error)
            } finally { withContext(Dispatchers.Main) { state.close() }; scope.cancel() }
        }
    }

    @Test
    fun stateCloseCancelsLocalWorkAndClearsCredentialsWithoutSendingDiscard() = runBlocking {
        LoopbackPlayServer().use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val auth = session()
            val state = state(auth, scope, server)
            try {
                create(state, auth, server)
                withContext(Dispatchers.Main) { state.close() }
                assertFalse(auth.tokens.hasToken)
                assertFalse(auth.connected)
                assertEquals(2, server.requests.size)
                assertFalse(server.requests.any { it.startsWith("DELETE") || ":commit" in it })
            } finally { scope.cancel() }
        }
    }

    @Test
    fun bundleFirstPreparationNeedsNoAndroidAccountAndNeverCreatesAnEdit() = runBlocking {
        withController(PlayAuthorizationState()) { state, auth, server ->
            withSignedBundle { bundle ->
                awaitOperation(state) { state.inspectBundle(bundle.absolutePath) }
                assertNull(state.error)
                assertEquals(FIXTURE_PACKAGE, state.artifact?.packageName)
                assertEquals(42L, state.artifact?.versionCode)
                assertEquals(FIXTURE_PACKAGE, state.form.packageName)
                assertEquals("en-US", state.form.language)
                assertFalse(state.form.updateListing)
                withContext(Dispatchers.Main) {
                    state.form = state.form.copy(releaseName = "Next version", releaseNotes = "Fixes")
                    state.synchronizeIdentity()
                    state.cancel()
                }
                assertFalse(auth.connected)
                assertFalse(auth.tokens.hasToken)
                assertFalse(state.accessChecked)
                assertNull(state.edit)
                assertNull(state.commitPreview)
                assertTrue(server.requests.isEmpty())
                awaitOperation(state) { state.checkAccess() }
                assertNotNull(state.error)
                assertFalse(state.resultUncertain)
                assertTrue(server.requests.isEmpty())
            }
        }
    }

    @Test
    fun defaultVersionUpdatePreservesListingImagesAndRetainedTrackUntilExplicitCommit() = runBlocking {
        withController { state, auth, server ->
            withSignedBundle { bundle ->
                prepareBundle(state, auth, server, bundle)
                val review = withContext(Dispatchers.Main) { state.makeStageReview() }
                assertNull(review.listing)
                assertTrue(review.images.isEmpty())
                assertSame(state.artifact, review.artifact)
                assertEquals(listOf(41L, 42L), review.replacementTrack.releases.flatMap { it.versionCodes })
                assertEquals(review.previousTrack.releases, review.replacementTrack.releases.dropLast(1))
                assertEquals(review.previousTrack.additionalFields, review.replacementTrack.additionalFields)
                assertEquals(2, server.requests.size)
                // Changes after confirmation preparation must not add an unreviewed listing/image write.
                withContext(Dispatchers.Main) {
                    state.form = state.form.copy(updateListing = true, language = "de-DE", title = "Unreviewed title", replaceSelectedImageTypes = true)
                }
                queueStage(server, review)
                awaitOperation(state) { state.stageAfterConfirmation(review) }
                assertNull(state.error)
                assertSame(review, state.stagedReview)
                assertTrue(state.stagedCompletely)
                assertNull(state.commitPreview)
                assertFalse(server.requests.any { "/listings/" in it || ":validate" in it || ":commit" in it || it.startsWith("DELETE ") })
                assertEquals(listOf(
                    "POST /androidpublisher/v3/applications/$FIXTURE_PACKAGE/edits",
                    "GET $FIXTURE_EDIT/tracks",
                    "GET $FIXTURE_EDIT/tracks/internal",
                    "GET $FIXTURE_EDIT/bundles",
                    "POST $FIXTURE_UPLOAD?uploadType=resumable",
                    "PUT $FIXTURE_UPLOAD?upload_id=controller-test",
                    "PUT $FIXTURE_EDIT/tracks/internal",
                ), server.requests.toList())
                assertTrue("Upload must use the exact reviewed AAB", server.bodies[5].contentEquals(bundle.readBytes()))
                val trackBody = JSONObject(server.bodies[6].toString(Charsets.UTF_8))
                assertEquals("preserve-track", trackBody.getString("futureTrackField"))
                val retained = trackBody.getJSONArray("releases").getJSONObject(0)
                assertEquals("41", retained.getJSONArray("versionCodes").getString(0))
                assertEquals("preserve-release", retained.getString("futureReleaseField"))
                assertEquals("Existing note", retained.getJSONArray("releaseNotes").getJSONObject(0).getString("text"))
                val added = trackBody.getJSONArray("releases").getJSONObject(1)
                assertEquals("42", added.getJSONArray("versionCodes").getString(0))
                assertEquals("draft", added.getString("status"))
                server.reply(200, editJson())
                awaitOperation(state) { state.validateEdit() }
                assertNull(state.error)
                val preview = requireNotNull(state.commitPreview)
                assertEquals(2, preview.changes.size)
                assertFalse(server.requests.any { ":commit" in it })
                server.reply(200, editJson())
                awaitOperation(state) { state.commitAfterConfirmation(preview, review.identityKey) }
                assertNull(state.error)
                assertEquals("edit123", state.committedEdit?.id)
                assertNull(state.stagedReview)
                assertEquals("POST $FIXTURE_EDIT:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW", server.requests.last())
                val requestsAfterCommit = server.requests.size
                awaitOperation(state) { state.commitAfterConfirmation(preview, review.identityKey) }
                assertNotNull(state.error)
                assertEquals(requestsAfterCommit, server.requests.size)
            }
        }
    }

    @Test
    fun listingIsValidatedOnlyWhenEnabledAndTheReviewedListingIsImmutable() = runBlocking {
        withController { state, auth, server ->
            withSignedBundle { bundle ->
                prepareBundle(state, auth, server, bundle)
                withContext(Dispatchers.Main) {
                    state.form = state.form.copy(language = "")
                    assertNull(state.makeStageReview().listing) // No listing, images, or release notes need a locale.
                    state.form = state.form.copy(updateListing = true)
                    expectValidationFailure { state.makeStageReview() }
                    state.form = state.form.copy(language = "fr-FR")
                    expectValidationFailure { state.makeStageReview() } // Empty listing is not an explicit replacement.
                    state.form = state.form.copy(title = "Reviewed title", shortDescription = "Reviewed summary", fullDescription = "Reviewed description")
                }
                val review = withContext(Dispatchers.Main) { state.makeStageReview() }
                val listing = requireNotNull(review.listing)
                assertEquals("fr-FR", listing.language)
                assertEquals("Reviewed title", listing.title)
                assertEquals(2, server.requests.size)
                withContext(Dispatchers.Main) {
                    state.form = state.form.copy(updateListing = false, language = "de-DE", title = "Changed after review", shortDescription = "", fullDescription = "")
                }
                queueStage(server, review)
                awaitOperation(state) { state.stageAfterConfirmation(review) }
                assertNull(state.error)
                assertTrue(state.stagedCompletely)
                val listingWrites = server.requests.withIndex().filter { it.value.startsWith("PUT ") && "/listings/" in it.value }
                assertEquals(1, listingWrites.size)
                assertEquals("PUT $FIXTURE_EDIT/listings/fr-FR", listingWrites.single().value)
                val body = JSONObject(server.bodies[listingWrites.single().index].toString(Charsets.UTF_8))
                assertEquals(listing.language, body.getString("language"))
                assertEquals(listing.title, body.getString("title"))
                assertEquals(listing.shortDescription, body.getString("shortDescription"))
                assertEquals(listing.fullDescription, body.getString("fullDescription"))
                assertFalse(server.requests.any { "de-DE" in it || ":commit" in it })
            }
        }
    }

    @Test
    fun selectedImagesCanChangeWithoutListingAndUseOnlyTheirReviewedLocale() = runBlocking {
        withController { state, auth, server ->
            withSignedBundle { bundle ->
                withStoreImage { image ->
                    prepareBundle(state, auth, server, bundle)
                    awaitOperation(state) { state.inspectImage(image.absolutePath, PlayImageType.PHONE_SCREENSHOTS) }
                    assertNull(state.error)
                    withContext(Dispatchers.Main) {
                        state.form = state.form.copy(language = "", replaceSelectedImageTypes = true)
                        expectValidationFailure { state.makeStageReview() }
                        state.form = state.form.copy(language = "fr-FR")
                    }
                    val review = withContext(Dispatchers.Main) { state.makeStageReview() }
                    assertNull(review.listing)
                    assertEquals("fr-FR", review.imageLanguage)
                    assertEquals(1, review.images.size)
                    withContext(Dispatchers.Main) {
                        state.form = state.form.copy(language = "de-DE", replaceSelectedImageTypes = false)
                        state.removeImage(state.images.single())
                    }
                    assertTrue(state.images.isEmpty())
                    queueStage(server, review)
                    awaitOperation(state) { state.stageAfterConfirmation(review) }
                    assertNull(state.error)
                    assertTrue(state.stagedCompletely)
                    assertTrue(server.requests.contains("DELETE $FIXTURE_EDIT/listings/fr-FR/phoneScreenshots"))
                    val imageRequest = "POST /upload$FIXTURE_EDIT/listings/fr-FR/phoneScreenshots?uploadType=media"
                    assertTrue(server.requests.contains(imageRequest))
                    assertArrayEquals(image.readBytes(), server.bodies[server.requests.indexOf(imageRequest)])
                    assertFalse(server.requests.any { "de-DE" in it || it == "PUT $FIXTURE_EDIT/listings/fr-FR" || ":commit" in it })
                }
            }
        }
    }

    @Test
    fun explicitAccessCheckOnlyReadsAndIsBoundToSessionPackageAndTrack() = runBlocking {
        withController { state, auth, server ->
            withContext(Dispatchers.Main) { state.form = state.form.copy(packageName = "example.app", trackId = "") }
            assertTrue(auth.connected)
            assertFalse(auth.nativeAccount)
            assertTrue(auth.accountLabel.contains("identity not verified"))
            assertFalse(state.accessChecked)
            assertTrue(server.requests.isEmpty())
            server.reply(200, """{"releases":[]}""")
            awaitOperation(state) { state.checkAccess() }
            assertNull(state.error)
            assertTrue(state.accessChecked)
            assertEquals(PublishAccessCheck(auth.identityKey, "example.app", "internal"), state.accessCheck)
            assertTrue(state.feedback.orEmpty().contains("API read access works"))
            assertTrue(state.feedback.orEmpty().contains("does not verify identity, upload/commit permission"))
            assertNull(state.edit)
            assertNull(state.commitPreview)
            withContext(Dispatchers.Main) {
                state.form = state.form.copy(title = "An unrelated draft field")
                assertTrue(state.accessChecked)
                state.form = state.form.copy(packageName = "another.app")
                assertFalse(state.accessChecked)
                assertNull(state.accessCheck)
                state.form = state.form.copy(packageName = "example.app")
                assertFalse("Returning to an old app must not resurrect a check", state.accessChecked)
            }
            server.reply(200, """{"releases":[]}""")
            awaitOperation(state) { state.checkAccess() }
            assertTrue(state.accessChecked)
            withContext(Dispatchers.Main) { state.form = state.form.copy(trackId = "production") }
            assertFalse(state.accessChecked)
            assertNull(state.accessCheck)
            server.reply(200, """{"releases":[]}""")
            awaitOperation(state) { state.checkAccess() }
            assertEquals("production", state.accessCheck?.trackId)
            assertEquals(listOf(
                "GET /androidpublisher/v3/applications/example.app/tracks/internal/releases",
                "GET /androidpublisher/v3/applications/example.app/tracks/internal/releases",
                "GET /androidpublisher/v3/applications/example.app/tracks/production/releases",
            ), server.requests.toList())
            assertFalse(state.resultUncertain)
        }
    }

    @Test
    fun manualTokenReplacementInvalidatesReadCheckBeforeAnyFurtherRequests() = runBlocking {
        withController { state, auth, server ->
            withContext(Dispatchers.Main) { state.form = state.form.copy(packageName = "example.app") }
            server.reply(200, """{"releases":[]}""")
            awaitOperation(state) { state.checkAccess() }
            val first = requireNotNull(state.accessCheck)
            withContext(Dispatchers.Main) {
                auth.manualTokenInput = "fake-replacement-access-token"
                auth.acceptManual()
                assertNotEquals(first.identityKey, auth.identityKey)
                assertFalse(state.accessChecked)
                assertNull(state.accessCheck)
                state.synchronizeIdentity()
            }
            assertEquals(1, server.requests.size)
            assertFalse(state.accessChecked)
            server.reply(200, """{"releases":[]}""")
            awaitOperation(state) { state.checkAccess() }
            assertTrue(state.accessChecked)
            assertEquals(auth.identityKey, state.accessCheck?.identityKey)
            withContext(Dispatchers.Main) { auth.disconnect() }
            assertFalse(state.accessChecked)
            assertNull(state.accessCheck)
            assertTrue(server.requests.all { it.startsWith("GET ") })
            assertEquals(2, server.requests.size)
        }
    }

    @Test
    fun failedReadRecheckCannotRetainPreviousSuccessOrClaimWritePermission() = runBlocking {
        withController { state, auth, server ->
            withContext(Dispatchers.Main) { state.form = state.form.copy(packageName = "example.app") }
            server.reply(200, """{"releases":[]}""")
            awaitOperation(state) { state.checkAccess() }
            assertTrue(state.accessChecked)
            server.reply(403, """{"error":{"message":"private-response-marker"}}""")
            awaitOperation(state) { state.checkAccess() }
            assertNotNull(state.error)
            assertFalse(state.error.orEmpty().contains("private-response-marker"))
            assertFalse(state.accessChecked)
            assertNull(state.accessCheck)
            assertTrue(auth.connected)
            assertFalse(state.resultUncertain)
            assertNull(state.edit)
            assertEquals(2, server.requests.size)
            assertTrue(server.requests.all { it.startsWith("GET ") })
        }
    }

    @Test
    fun inFlightReadCannotApproveAChangedSessionOrApp() = runBlocking {
        for (changeSession in listOf(false, true)) {
            withController { state, auth, server ->
                withContext(Dispatchers.Main) { state.form = state.form.copy(packageName = "example.app") }
                val received = CountDownLatch(1)
                val release = CountDownLatch(1)
                server.reply(200, """{"releases":[]}""") {
                    received.countDown()
                    check(release.await(4, TimeUnit.SECONDS)) { "Test did not release the read response" }
                }
                val job = withContext(Dispatchers.Main) { state.checkAccess(); requireNotNull(state.operationJob) }
                try {
                    assertTrue(withContext(Dispatchers.IO) { received.await(2, TimeUnit.SECONDS) })
                    withContext(Dispatchers.Main) {
                        if (changeSession) {
                            auth.manualTokenInput = "fake-replaced-during-read"
                            auth.acceptManual()
                        } else state.form = state.form.copy(packageName = "another.app")
                    }
                } finally { release.countDown() }
                withTimeout(5_000) { job.join() }
                assertNull(state.busy)
                assertNotNull(state.error)
                assertFalse(state.accessChecked)
                assertNull(state.accessCheck)
                assertEquals(listOf("GET /androidpublisher/v3/applications/example.app/tracks/internal/releases"), server.requests.toList())
            }
        }
    }

    @Test
    fun changedBundleAfterReviewCannotUploadOrCommit() = runBlocking {
        withController { state, auth, server ->
            withSignedBundle { bundle ->
                prepareBundle(state, auth, server, bundle)
                val review = withContext(Dispatchers.Main) { state.makeStageReview() }
                bundle.appendBytes(byteArrayOf(0))
                server.reply(200, existingTrackJson())
                server.reply(200, """{"bundles":[]}""")
                awaitOperation(state) { state.stageAfterConfirmation(review) }
                assertNotNull(state.error)
                assertFalse(state.stagedCompletely)
                assertNull(state.commitPreview)
                assertEquals(4, server.requests.size)
                assertFalse(server.requests.any { it.startsWith("POST /upload") || it.startsWith("PUT ") || ":commit" in it })
                awaitOperation(state) { state.validateEdit() }
                assertEquals(4, server.requests.size)
            }
        }
    }

    @Test
    fun cancellingAnInFlightStageLeavesUncertainEditAndNeverPublishes() = runBlocking {
        withController { state, auth, server ->
            withSignedBundle { bundle ->
                prepareBundle(state, auth, server, bundle)
                val review = withContext(Dispatchers.Main) { state.makeStageReview() }
                val uploadReceived = CountDownLatch(1)
                val releaseResponse = CountDownLatch(1)
                server.reply(200, existingTrackJson())
                server.reply(200, """{"bundles":[]}""")
                server.reply(200, "", mapOf("Location" to "${server.origin.removeSuffix("/")}$FIXTURE_UPLOAD?upload_id=controller-test"))
                server.reply(200, """{"versionCode":42,"sha256":"${review.artifact.sha256}"}""") {
                    uploadReceived.countDown()
                    check(releaseResponse.await(4, TimeUnit.SECONDS)) { "Test did not release the upload response" }
                }
                val job = withContext(Dispatchers.Main) { state.stageAfterConfirmation(review); requireNotNull(state.operationJob) }
                try {
                    assertTrue(withContext(Dispatchers.IO) { uploadReceived.await(2, TimeUnit.SECONDS) })
                    withContext(Dispatchers.Main) {
                        assertTrue(state.canCancel)
                        state.cancel()
                    }
                    withTimeout(5_000) { job.join() }
                    assertTrue(job.isCancelled)
                    assertTrue(state.resultUncertain)
                    assertFalse(state.stagedCompletely)
                    assertNull(state.commitPreview)
                    assertNotNull(state.edit)
                    assertTrue(state.feedback.orEmpty().contains("cancellation is not rollback"))
                    val requestCount = server.requests.size
                    awaitOperation(state) { state.validateEdit() }
                    assertNotNull(state.error)
                    assertEquals(requestCount, server.requests.size)
                    assertEquals(6, requestCount)
                    assertFalse(server.requests.any { ":validate" in it || ":commit" in it || it == "PUT $FIXTURE_EDIT/tracks/internal" || it.startsWith("DELETE ") })
                } finally { releaseResponse.countDown() }
            }
        }
    }

    @Test
    fun changedPackageOrSessionRequiresFreshAttestationsWithoutWriting() = runBlocking {
        withController { state, auth, server ->
            val answers = PlayAccountAttestations(
                accountRegisteredAndVerified = true, appCreatedAndInitializedInConsole = true,
                uploadKeyAndPlayAppSigningConfigured = true, requiredPolicyDeclarationsReviewed = true,
                storeListingAndDistributionConfigured = true, dataSafetyReviewed = true,
                hasNewPersonalAccountTestingRequirement = false, productionAccessApproved = true,
            )
            withContext(Dispatchers.Main) {
                state.form = state.form.copy(packageName = "example.app", title = "Keep listing input")
                state.form = state.form.copy(attestations = answers)
                state.synchronizeIdentity()
                assertEquals(answers, state.form.attestations)
                state.form = state.form.copy(packageName = "another.app")
                assertEquals(PlayAccountAttestations(), state.form.attestations)
                assertEquals("Keep listing input", state.form.title)
                state.form = state.form.copy(attestations = answers)
                auth.manualTokenInput = "fake-new-attestation-session"
                auth.acceptManual()
                state.synchronizeIdentity()
                assertEquals(PlayAccountAttestations(), state.form.attestations)
                expectValidationFailure { validatePublishAttestations(state.form) }
                assertEquals("Keep listing input", state.form.title)
                state.form = state.form.copy(attestations = answers)
                auth.manualTokenInput = "fake-api-boundary-session"
                auth.acceptManual()
            }
            server.reply(200, """{"releases":[]}""")
            awaitOperation(state) { state.checkAccess() } // API boundary also resets without relying on UI effects.
            assertNull(state.error)
            assertEquals(PlayAccountAttestations(), state.form.attestations)
            assertEquals(listOf("GET /androidpublisher/v3/applications/another.app/tracks/internal/releases"), server.requests.toList())
            assertNull(state.edit)
        }
        withController(PlayAuthorizationState()) { state, auth, server ->
            withContext(Dispatchers.Main) {
                state.form = state.form.copy(attestations = PlayAccountAttestations(accountRegisteredAndVerified = true))
                auth.manualAccountLabel = "First session"
                auth.manualTokenInput = "fake-first-attestation-session"
                auth.acceptManual()
                state.synchronizeIdentity()
                assertEquals(PlayAccountAttestations(), state.form.attestations)
            }
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun changedVerifiedBundleRequiresNewAttestationsButIdenticalBundleDoesNot() = runBlocking {
        withController { state, _, server ->
            withSignedBundle { bundle ->
                val answers = PlayAccountAttestations(
                    accountRegisteredAndVerified = true, appCreatedAndInitializedInConsole = true,
                    uploadKeyAndPlayAppSigningConfigured = true, requiredPolicyDeclarationsReviewed = true,
                    storeListingAndDistributionConfigured = true, dataSafetyReviewed = true, productionAccessApproved = true,
                )
                awaitOperation(state) { state.inspectBundle(bundle.absolutePath) }
                assertNull(state.error)
                val original = requireNotNull(state.artifact)
                withContext(Dispatchers.Main) { state.form = state.form.copy(attestations = answers, title = "Keep title") }
                awaitOperation(state) { state.inspectBundle(bundle.absolutePath) }
                assertNull(state.error)
                assertEquals(answers, state.form.attestations)
                // A ZIP comment changes the exact artifact hash without creating another signing fixture.
                java.io.RandomAccessFile(bundle, "rw").use { zip ->
                    zip.seek(zip.length() - 22)
                    assertEquals(0x504b0506, zip.readInt())
                    zip.seek(zip.length() - 2)
                    assertEquals(0, zip.readUnsignedByte())
                    assertEquals(0, zip.readUnsignedByte())
                    zip.seek(zip.length() - 2)
                    zip.write(byteArrayOf(1, 0, 'x'.code.toByte()))
                }
                awaitOperation(state) { state.inspectBundle(bundle.absolutePath) }
                assertNull(state.error)
                val changed = requireNotNull(state.artifact)
                assertEquals(original.packageName, changed.packageName)
                assertEquals(original.uploadCertificateSha256, changed.uploadCertificateSha256)
                assertNotEquals(original.sha256, changed.sha256)
                assertEquals(PlayAccountAttestations(), state.form.attestations)
                assertEquals("Keep title", state.form.title)
                withContext(Dispatchers.Main) {
                    expectValidationFailure { validatePublishAttestations(state.form) }
                    state.form = state.form.copy(attestations = answers)
                    state.clearArtifact()
                    assertEquals(PlayAccountAttestations(), state.form.attestations)
                }
                assertNull(state.artifact)
                assertTrue(server.requests.isEmpty())
            }
        }
    }

    private fun session() = PlayAuthorizationState().also {
        it.manualAccountLabel = "Loopback test"
        it.manualTokenInput = "fake-test-access-token"
        it.acceptManual()
    }

    private fun state(auth: PlayAuthorizationState, scope: CoroutineScope, server: LoopbackPlayServer) = PlayPublishState(auth, scope) { tokens ->
        PlayPublisherClient(tokens, PlayPublisherConfig(apiOrigin = server.origin, allowLoopbackForTests = true, maxUploadRetries = 0, requestTimeoutMillis = 3_000))
    }

    private suspend fun create(
        state: PlayPublishState,
        auth: PlayAuthorizationState,
        server: LoopbackPlayServer,
        packageName: String = "example.app",
        tracks: String = """{"tracks":[{"track":"internal","releases":[]},{"track":"production","releases":[]}]}""",
    ) {
        server.reply(200, editJson())
        server.reply(200, tracks)
        awaitOperation(state) { state.createEditAfterConfirmation(packageName, auth.identityKey) }
        assertNull(state.error)
    }

    private suspend fun withController(
        auth: PlayAuthorizationState = session(),
        block: suspend (PlayPublishState, PlayAuthorizationState, LoopbackPlayServer) -> Unit,
    ) {
        LoopbackPlayServer().use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val state = state(auth, scope, server)
            try { block(state, auth, server) }
            finally { withContext(Dispatchers.Main) { state.close() }; scope.cancel() }
        }
    }

    private suspend fun withSignedBundle(block: suspend (File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("play-controller-", ".aab", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("workbench-acceptance/fixture.aab").use { source ->
                file.outputStream().use { source.copyTo(it) }
            }
            block(file)
        } finally { file.delete() }
    }

    private suspend fun withStoreImage(block: suspend (File) -> Unit) {
        val file = File.createTempFile("play-controller-", ".jpg", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        val bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.RGB_565)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            block(file)
        } finally { bitmap.recycle(); file.delete() }
    }

    private suspend fun prepareBundle(state: PlayPublishState, auth: PlayAuthorizationState, server: LoopbackPlayServer, bundle: File) {
        awaitOperation(state) { state.inspectBundle(bundle.absolutePath) }
        assertNull(state.error)
        assertEquals(FIXTURE_PACKAGE, state.artifact?.packageName)
        assertEquals(42L, state.artifact?.versionCode)
        withContext(Dispatchers.Main) {
            state.form = state.form.copy(attestations = PlayAccountAttestations(
                accountRegisteredAndVerified = true,
                appCreatedAndInitializedInConsole = true,
                uploadKeyAndPlayAppSigningConfigured = true,
            ))
        }
        create(state, auth, server, FIXTURE_PACKAGE, """{"tracks":[${existingTrackJson()}]}""")
    }

    private fun queueStage(server: LoopbackPlayServer, review: PublishStageReview) {
        server.reply(200, existingTrackJson())
        server.reply(200, """{"bundles":[]}""")
        server.reply(200, "", mapOf("Location" to "${server.origin.removeSuffix("/")}$FIXTURE_UPLOAD?upload_id=controller-test"))
        server.reply(200, """{"versionCode":${review.artifact.versionCode},"sha256":"${review.artifact.sha256}"}""")
        review.listing?.let { listing ->
            server.reply(200, JSONObject().put("language", listing.language).put("title", listing.title)
                .put("shortDescription", listing.shortDescription).put("fullDescription", listing.fullDescription).toString())
        }
        if (review.replaceImageTypes) review.images.map { it.type }.distinct().forEach { server.reply(204, "") }
        review.images.forEach { image -> server.reply(200, """{"image":{"id":"image1","sha256":"${image.sha256}"}}""") }
        val releases = JSONArray()
        review.replacementTrack.releases.forEach { release ->
            releases.put(JSONObject().put("versionCodes", JSONArray(release.versionCodes.map { it.toString() })).put("status", release.status.wireValue))
        }
        server.reply(200, JSONObject().put("track", review.replacementTrack.track).put("releases", releases).toString())
    }

    private fun editJson() = """{"id":"edit123","expiryTimeSeconds":"${System.currentTimeMillis() / 1000 + 3600}"}"""

    private fun existingTrackJson() = """{"track":"internal","futureTrackField":"preserve-track","releases":[{"versionCodes":["41"],"status":"completed","name":"Existing release","inAppUpdatePriority":2,"futureReleaseField":"preserve-release","releaseNotes":[{"language":"en-US","text":"Existing note"}]}]}"""

    private fun expectValidationFailure(block: () -> Unit) {
        val failure = try { block(); null } catch (failure: PlayPublishingException) { failure }
        assertNotNull("An explicit invalid change must fail before any request", failure)
        assertEquals(PlayErrorKind.VALIDATION, failure!!.kind)
    }

    private companion object {
        const val FIXTURE_PACKAGE = "dev.ide.fixture.publisher"
        const val FIXTURE_EDIT = "/androidpublisher/v3/applications/$FIXTURE_PACKAGE/edits/edit123"
        const val FIXTURE_UPLOAD = "/upload$FIXTURE_EDIT/bundles"
    }

    private suspend fun awaitOperation(state: PlayPublishState, operation: () -> Unit) {
        val job = withContext(Dispatchers.Main) { operation(); state.operationJob }
        if (job != null) withTimeout(5_000) { job.join() }
        assertNull("Operation must settle before assertions", state.busy)
    }
}

private class LoopbackPlayServer : AutoCloseable {
    private data class Reply(val status: Int, val body: String, val headers: Map<String, String>, val beforeSend: () -> Unit = {})
    private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val responses = LinkedBlockingQueue<Reply>()
    private val closed = AtomicBoolean(false)
    val requests = CopyOnWriteArrayList<String>()
    val bodies = CopyOnWriteArrayList<ByteArray>()
    val origin = "http://127.0.0.1:${listener.localPort}/"
    private val worker = thread(name = "play-publish-loopback-test", isDaemon = true) {
        while (!closed.get()) {
            val socket = try { listener.accept() } catch (_: Exception) { break }
            socket.use { accepted ->
                runCatching { serve(accepted) }
            }
        }
    }

    fun reply(status: Int, body: String, headers: Map<String, String> = emptyMap(), beforeSend: () -> Unit = {}) {
        responses.put(Reply(status, body, headers, beforeSend))
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 3_000
        val input = BufferedInputStream(socket.getInputStream())
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() < 32768) {
                val b = input.read()
                if (b < 0 || b == 10) break
                if (b != 13) bytes.write(b)
            }
            return bytes.toString("US-ASCII")
        }
        val first = line().split(' ')
        var length = 0
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
        }
        require(length in 0..2_097_152) { "Unexpected loopback test body size" }
        val requestBody = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(requestBody, offset, length - offset)
            require(count > 0) { "Truncated loopback request" }
            offset += count
        }
        bodies += requestBody
        requests += "${first[0]} ${first[1]}"
        val reply = responses.poll() ?: Reply(500, "{}", emptyMap())
        reply.beforeSend()
        val body = reply.body.toByteArray(Charsets.UTF_8)
        val extraHeaders = reply.headers.entries.joinToString("") { (name, value) -> "$name: $value\r\n" }
        socket.getOutputStream().apply {
            write("HTTP/1.1 ${reply.status} Test\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n${extraHeaders}Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    override fun close() {
        closed.set(true)
        listener.close()
        worker.join(4_000)
    }
}
