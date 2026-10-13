package dev.ide.android.workbench.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.workbench.publish.AabArtifact
import dev.ide.workbench.publish.PlayErrorKind
import dev.ide.workbench.publish.PlayListing
import dev.ide.workbench.publish.PlayPreflight
import dev.ide.workbench.publish.PlayPublisherClient
import dev.ide.workbench.publish.PlayPublisherConfig
import dev.ide.workbench.publish.PlayPublishingException
import dev.ide.workbench.publish.PlayRelease
import dev.ide.workbench.publish.PlayReleaseNote
import dev.ide.workbench.publish.PlayReleaseStatus
import dev.ide.workbench.publish.PlayTrack
import dev.ide.workbench.publish.SessionAccessTokenProvider
import dev.ide.workbench.publish.UploadProgress
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Offline ART acceptance, not evidence of Google approval, a published release or a runnable fixture.
 * All HTTP requests use the device's 127.0.0.1 server; tokens and upload IDs below are synthetic.
 */
@RunWith(AndroidJUnit4::class)
class PlayPublisherAcceptanceTest {
    @Test(timeout = 90_000)
    fun signedBundleResumesThenStagesValidatesAndExplicitlyCommits() = runBlocking<Unit> {
        withTimeout(60_000) {
            AcceptanceWorkspace("publish").use { workspace ->
                val artifact = fixture(workspace)
                val bytes = artifact.file.readBytes()
                LoopbackHttpServer().use { server ->
                    client(server).use { publisher ->
                        server.expect("create edit") { request ->
                            verify(request, server, "POST", "/androidpublisher/v3/applications/$PACKAGE/edits")
                            require(request.bodyText() == "{}")
                            LoopbackHttpServer.Reply(body = editJson())
                        }
                        val edit = publisher.createEdit(PACKAGE)
                        assertEquals("edit1", edit.id)
                        assertEquals(4_102_444_800L, edit.expiryTimeSeconds)

                        expectUploadStart(server, artifact)
                        expectChunk(server, artifact, bytes, 0, CHUNK, 308, "bytes=0-131071")
                        expectChunk(server, artifact, bytes, 131_072, CHUNK, 308, "bytes=0-393215")
                        expectChunk(server, artifact, bytes, 393_216, bytes.size - 393_216, 200)
                        val progress = mutableListOf<UploadProgress>()
                        val uploaded = publisher.uploadBundle(edit, artifact, progress::add)
                        assertEquals(42L, uploaded.versionCode)
                        assertEquals(artifact.sha256, uploaded.sha256)
                        assertEquals(listOf(131_072L, 393_216L, artifact.sizeBytes), progress.map { it.bytesAcknowledged })
                        assertTrue(progress.all { it.totalBytes == artifact.sizeBytes })

                        expectListing(server, listing)
                        assertEquals(listing, publisher.updateListing(edit, listing))
                        expectTrack(server)
                        assertEquals(track, publisher.updateTrack(edit, track))
                        assertEquals(PlayErrorKind.VALIDATION, failureOf { publisher.previewCommit(edit) }.kind)
                        assertEquals("No implicit validation or submission", 7, server.requestCount)

                        expectValidation(server)
                        publisher.validateEdit(edit)
                        val preview = publisher.previewCommit(edit)
                        assertEquals(3, preview.changes.size)
                        assertTrue(preview.warnings.isNotEmpty())
                        assertEquals("Preview and confirmation must not send requests", 8, server.requestCount)
                        val confirmation = preview.confirmAfterUserReview()
                        assertEquals(8, server.requestCount)
                        server.expect("explicit conservative commit") { request ->
                            verify(request, server, "POST", "$BASE:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW")
                            require(request.body.isEmpty())
                            require("changesNotSentForReview" !in request.target)
                            LoopbackHttpServer.Reply(body = editJson())
                        }
                        assertEquals(edit.id, publisher.commitEdit(edit, confirmation).id)
                        failureOf { publisher.commitEdit(edit, confirmation) }
                        server.assertSatisfied(9)
                    }
                }
            }
        }
    }

    @Test(timeout = 90_000)
    fun temporaryUploadFailureQueriesAuthoritativeOffsetBeforeRetry() = runBlocking<Unit> {
        withTimeout(60_000) {
            AcceptanceWorkspace("resume").use { workspace ->
                val artifact = fixture(workspace)
                val bytes = artifact.file.readBytes()
                LoopbackHttpServer().use { server ->
                    client(server).use { publisher ->
                        expectCreate(server)
                        val edit = publisher.createEdit(PACKAGE)
                        expectUploadStart(server, artifact)
                        expectChunk(server, artifact, bytes, 0, CHUNK, 503)
                        server.expect("query saved upload offset") { request ->
                            verify(request, server, "PUT", "$UPLOAD?upload_id=$UPLOAD_ID")
                            require(request.header("Content-Range") == "bytes */${artifact.sizeBytes}")
                            require(request.body.isEmpty())
                            LoopbackHttpServer.Reply(308, headers = mapOf("Range" to "bytes=0-262143"))
                        }
                        expectChunk(server, artifact, bytes, CHUNK, CHUNK, 308, "bytes=0-524287")
                        expectChunk(server, artifact, bytes, 2 * CHUNK, bytes.size - 2 * CHUNK, 200)
                        val progress = mutableListOf<UploadProgress>()
                        publisher.uploadBundle(edit, artifact, progress::add)
                        assertEquals(listOf(CHUNK.toLong(), 2L * CHUNK, artifact.sizeBytes), progress.map { it.bytesAcknowledged })
                        server.assertSatisfied(6)
                    }
                }
            }
        }
    }

    @Test(timeout = 45_000)
    fun changingAValidatedEditRejectsStaleConfirmationWithoutSendingCommit() = runBlocking<Unit> {
        withTimeout(30_000) {
            LoopbackHttpServer().use { server ->
                client(server).use { publisher ->
                    expectCreate(server)
                    val edit = publisher.createEdit(PACKAGE)
                    expectListing(server, listing)
                    publisher.updateListing(edit, listing)
                    expectValidation(server)
                    publisher.validateEdit(edit)
                    val stale = publisher.previewCommit(edit).confirmAfterUserReview()
                    val changed = listing.copy(title = "Changed offline fixture")
                    expectListing(server, changed)
                    publisher.updateListing(edit, changed)
                    assertEquals(PlayErrorKind.CONFIRMATION, failureOf { publisher.commitEdit(edit, stale) }.kind)
                    assertEquals(PlayErrorKind.VALIDATION, failureOf { publisher.previewCommit(edit) }.kind)
                    server.assertSatisfied(4)
                }
            }
        }
    }

    @Test(timeout = 45_000)
    fun expiredTokenPermissionDenialAndRedirectErrorsWithholdResponseSecrets() = runBlocking<Unit> {
        withTimeout(30_000) {
            LoopbackHttpServer().use { forbiddenDestination ->
                LoopbackHttpServer().use { server ->
                    val tokens = SessionAccessTokenProvider(TOKEN)
                    client(server, tokens).use { publisher ->
                        for ((status, kind, reason) in listOf(
                            Triple(401, PlayErrorKind.AUTHORIZATION, "UNAUTHENTICATED"),
                            Triple(403, PlayErrorKind.PERMISSION, "PERMISSION_DENIED"),
                            Triple(302, PlayErrorKind.PROTOCOL, "UNRECOGNIZED_PRIVATE_REASON"),
                        )) {
                            server.expect("redacted HTTP error $status") { request ->
                                verify(request, server, "POST", "/androidpublisher/v3/applications/$PACKAGE/edits")
                                val errorBody = JSONObject().put("error", JSONObject()
                                    .put("message", "expiredtoken $TOKEN https://private.invalid/?upload_id=$UPLOAD_ID")
                                    .put("reason", reason)).toString()
                                LoopbackHttpServer.Reply(status, errorBody, mapOf("Location" to forbiddenDestination.origin))
                            }
                            val error = failureOf { publisher.createEdit(PACKAGE) }
                            assertEquals(kind, error.kind)
                            assertEquals(status, error.httpStatus)
                            assertNull(error.cause)
                            assertSecretFree(error.toString() + error.stackTraceToString())
                            if (status == 302) assertNull(error.reason) else assertEquals(reason, error.reason)
                        }
                        assertSecretFree(tokens.toString())
                        tokens.clear()
                        assertFalse(tokens.hasToken)
                        assertEquals(PlayErrorKind.AUTHORIZATION, failureOf { publisher.createEdit(PACKAGE) }.kind)
                        server.assertSatisfied(3)
                        forbiddenDestination.assertSatisfied(0)
                    }
                }
            }
        }
    }

    @Test(timeout = 60_000)
    fun resumableLocationCannotForwardCredentialsToAnotherOrigin() = runBlocking<Unit> {
        withTimeout(40_000) {
            AcceptanceWorkspace("upload-origin").use { workspace ->
                val artifact = fixture(workspace)
                LoopbackHttpServer().use { forbiddenDestination ->
                    LoopbackHttpServer().use { server ->
                        client(server).use { publisher ->
                            expectCreate(server)
                            val edit = publisher.createEdit(PACKAGE)
                            server.expect("refuse changed upload origin") { request ->
                                verify(request, server, "POST", "$UPLOAD?uploadType=resumable")
                                LoopbackHttpServer.Reply(headers = mapOf("Location" to "${forbiddenDestination.origin.removeSuffix("/")}$UPLOAD?upload_id=$UPLOAD_ID"))
                            }
                            val error = failureOf { publisher.uploadBundle(edit, artifact) }
                            assertEquals(PlayErrorKind.PROTOCOL, error.kind)
                            assertSecretFree(error.toString())
                            assertEquals(PlayErrorKind.UNCERTAIN_RESULT, failureOf { publisher.validateEdit(edit) }.kind)
                            server.assertSatisfied(2)
                            forbiddenDestination.assertSatisfied(0)
                        }
                    }
                }
            }
        }
    }

    @Test(timeout = 15_000)
    fun loopbackRequiresExplicitOptInAndArbitraryOriginsAreRejectedBeforeRequests() = runBlocking<Unit> {
        for (config in listOf(
            PlayPublisherConfig(apiOrigin = "http://127.0.0.1:1/"),
            PlayPublisherConfig(apiOrigin = "https://not-google.invalid/", allowLoopbackForTests = true),
            PlayPublisherConfig(apiOrigin = "https://androidpublisher.googleapis.com@not-google.invalid/"),
        )) {
            val error = failureOf { PlayPublisherClient(SessionAccessTokenProvider(TOKEN), config).use { } }
            assertEquals(PlayErrorKind.VALIDATION, error.kind)
            assertSecretFree(error.toString())
        }
    }

    private fun fixture(workspace: AcceptanceWorkspace): AabArtifact {
        val destination = workspace.resolve("fixture.aab").toFile()
        val supplied = InstrumentationRegistry.getArguments().getString("workbenchAcceptanceAabPath")
        val input = if (supplied.isNullOrBlank()) {
            try {
                InstrumentationRegistry.getInstrumentation().context.assets.open("workbench-acceptance/fixture.aab")
            } catch (_: Exception) {
                throw AssertionError("Supply the signed offline fixture asset workbench-acceptance/fixture.aab or workbenchAcceptanceAabPath")
            }
        } else {
            val source = File(supplied)
            require(source.isFile && source.length() in (2L * CHUNK + 1)..(3L * CHUNK)) { "Expected the bounded, three-chunk offline AAB fixture" }
            source.inputStream()
        }
        input.use { from ->
            destination.outputStream().use { to ->
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val count = from.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 3 * CHUNK) { "Offline AAB fixture exceeds the test limit" }
                    to.write(buffer, 0, count)
                }
            }
        }
        val artifact = PlayPreflight.inspectAab(destination, PACKAGE)
        assertEquals(PACKAGE, artifact.packageName)
        assertEquals(42L, artifact.versionCode)
        assertEquals("1.0", artifact.versionName)
        assertEquals(26, artifact.minSdk)
        assertEquals(35, artifact.targetSdk)
        assertTrue(artifact.permissions.contains("android.permission.INTERNET"))
        assertTrue("The signed fixture must span three upload chunks", artifact.sizeBytes in (2L * CHUNK + 1)..(3L * CHUNK))
        assertEquals(Files.size(destination.toPath()), artifact.sizeBytes)
        assertTrue(artifact.uploadCertificateSha256.matches(Regex("[0-9a-f]{64}")))
        val digest = MessageDigest.getInstance("SHA-256").digest(destination.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(digest, artifact.sha256)
        return artifact
    }

    private fun client(server: LoopbackHttpServer, tokens: SessionAccessTokenProvider = SessionAccessTokenProvider(TOKEN)) =
        PlayPublisherClient(tokens, PlayPublisherConfig(
            apiOrigin = server.origin,
            allowLoopbackForTests = true,
            chunkSizeBytes = CHUNK,
            maxUploadRetries = 2,
            retryBaseDelayMillis = 0,
            requestTimeoutMillis = 5_000,
        ))

    private fun verify(request: LoopbackHttpServer.Request, server: LoopbackHttpServer, method: String, target: String) {
        require(request.method == method) { "Unexpected request method" }
        require(request.target == target) { "Unexpected request target" }
        require(request.header("Host") == "127.0.0.1:${server.port}") { "Request is not addressed to the test origin" }
        require(request.header("Authorization") == "Bearer $TOKEN") { "Authorization mismatch; credential withheld" }
        require(request.header("Accept") == "application/json")
    }

    private fun expectCreate(server: LoopbackHttpServer) = server.expect("create edit") { request ->
        verify(request, server, "POST", "/androidpublisher/v3/applications/$PACKAGE/edits")
        require(request.bodyText() == "{}")
        LoopbackHttpServer.Reply(body = editJson())
    }

    private fun expectUploadStart(server: LoopbackHttpServer, artifact: AabArtifact) = server.expect("start resumable upload") { request ->
        verify(request, server, "POST", "$UPLOAD?uploadType=resumable")
        require(request.body.isEmpty())
        require(request.header("X-Upload-Content-Length") == artifact.sizeBytes.toString())
        require(request.header("X-Upload-Content-Type") == "application/octet-stream")
        LoopbackHttpServer.Reply(headers = mapOf("Location" to "${server.origin.removeSuffix("/")}$UPLOAD?upload_id=$UPLOAD_ID"))
    }

    private fun expectChunk(server: LoopbackHttpServer, artifact: AabArtifact, bytes: ByteArray, offset: Int, length: Int, status: Int, range: String? = null) =
        server.expect("upload fixture slice at $offset") { request ->
            verify(request, server, "PUT", "$UPLOAD?upload_id=$UPLOAD_ID")
            require(request.header("Content-Range") == "bytes $offset-${offset + length - 1}/${artifact.sizeBytes}")
            require(request.header("Content-Type") == "application/octet-stream")
            require(request.body.contentEquals(bytes.copyOfRange(offset, offset + length))) { "Uploaded slice differs from signed fixture" }
            LoopbackHttpServer.Reply(status,
                if (status == 200) JSONObject().put("versionCode", 42).put("sha256", artifact.sha256).toString() else "{}",
                range?.let { mapOf("Range" to it) }.orEmpty())
        }

    private fun expectListing(server: LoopbackHttpServer, expected: PlayListing) = server.expect("update listing") { request ->
        verify(request, server, "PUT", "$BASE/listings/en-US")
        val body = JSONObject(request.bodyText())
        require(body.getString("language") == expected.language && body.getString("title") == expected.title)
        require(body.getString("shortDescription") == expected.shortDescription && body.getString("fullDescription") == expected.fullDescription)
        LoopbackHttpServer.Reply(body = body.toString())
    }

    private fun expectTrack(server: LoopbackHttpServer) = server.expect("update internal draft track") { request ->
        verify(request, server, "PUT", "$BASE/tracks/internal")
        val body = JSONObject(request.bodyText())
        require(body.getString("track") == "internal")
        val releases: JSONArray = body.getJSONArray("releases")
        require(releases.length() == 1)
        val release = releases.getJSONObject(0)
        val versions = release.getJSONArray("versionCodes")
        require(versions.length() == 1 && versions.get(0) is String && versions.getString(0) == "42")
        require(release.getString("status") == "draft")
        require(release.getString("name") == "Offline acceptance")
        require(!release.has("userFraction"))
        val note = release.getJSONArray("releaseNotes").getJSONObject(0)
        require(note.getString("language") == "en-US" && note.getString("text") == "Offline acceptance only")
        LoopbackHttpServer.Reply(body = body.toString())
    }

    private fun expectValidation(server: LoopbackHttpServer) = server.expect("validate latest edit revision") { request ->
        verify(request, server, "POST", "$BASE:validate")
        require(request.body.isEmpty())
        LoopbackHttpServer.Reply(body = editJson())
    }

    private suspend fun failureOf(action: suspend () -> Unit): PlayPublishingException {
        try { action() } catch (error: PlayPublishingException) { return error }
        throw AssertionError("Expected a sanitized publishing failure")
    }

    private fun assertSecretFree(text: String) {
        for (secret in listOf(TOKEN, UPLOAD_ID, "expiredtoken", "private.invalid", "UNRECOGNIZED_PRIVATE_REASON")) {
            assertFalse("Publishing diagnostics leaked synthetic confidential input", text.contains(secret))
        }
    }

    private fun editJson(): String = """{"id":"edit1","expiryTimeSeconds":"4102444800"}"""

    private companion object {
        const val PACKAGE = "dev.ide.fixture.publisher"
        const val BASE = "/androidpublisher/v3/applications/$PACKAGE/edits/edit1"
        const val UPLOAD = "/upload$BASE/bundles"
        const val CHUNK = 262_144
        const val TOKEN = "offline-acceptance-oauth-token"
        const val UPLOAD_ID = "offline-private-upload-capability"
        val listing = PlayListing("en-US", "Offline fixture", "Offline protocol acceptance", "A synthetic listing that never reaches Google Play.")
        val track = PlayTrack("internal", listOf(PlayRelease(listOf(42L), PlayReleaseStatus.DRAFT,
            name = "Offline acceptance", releaseNotes = listOf(PlayReleaseNote("en-US", "Offline acceptance only")))))
    }
}
