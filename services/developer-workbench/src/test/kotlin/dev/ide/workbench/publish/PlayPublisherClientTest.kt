package dev.ide.workbench.publish

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlayPublisherClientTest {
    private lateinit var temp: File
    private lateinit var artifact: AabArtifact
    private val json = Json
    private val pkg get() = PlayTestFixtures.PACKAGE
    private val base get() = "/androidpublisher/v3/applications/$pkg/edits/edit1"
    private val uploadPath get() = "/upload/androidpublisher/v3/applications/$pkg/edits/edit1/bundles"
    private fun editJson() = """{"id":"edit1","expiryTimeSeconds":"4102444800"}"""
    private fun bundleJson() = """{"versionCode":42,"sha256":"${artifact.sha256}","sha1":"unused"}"""
    private fun track(status: PlayReleaseStatus = PlayReleaseStatus.DRAFT) = PlayTrack("closed-test", listOf(PlayRelease(listOf(42), status)))
    private val listing = PlayListing("en-US", "Fixture", "Offline protocol test", "An offline fixture used exclusively with a loopback server.")

    @BeforeAll fun fixture() {
        temp = java.nio.file.Files.createTempDirectory("play-protocol-tests").toFile()
        artifact = PlayPreflight.inspectAab(PlayTestFixtures.signedBundle(temp))
    }
    @AfterAll fun cleanup() { temp.deleteRecursively() }
    private fun client(server: MockWebServer, retries: Int = 3) = PlayPublisherClient(SessionAccessTokenProvider("session-token"), PlayPublisherConfig(server.url("/").toString(), true, 262_144, retries, 0, 5000))
    private fun response(body: String = "{}", status: Int = 200) = MockResponse().setResponseCode(status).setBody(body).setHeader("Content-Type", "application/json")
    private fun record(server: MockWebServer) = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))

    @Test fun `full pipeline uses real HTTP, correct schemas, explicit commit and authoritative resume`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            client(server).use { client ->
                server.enqueue(response(editJson()))
                val edit = client.createEdit(pkg)
                val create = record(server)
                assertEquals("POST", create.method); assertEquals("/androidpublisher/v3/applications/$pkg/edits", create.path)
                assertEquals("{}", create.body.readUtf8()); assertEquals("Bearer session-token", create.getHeader("Authorization"))

                server.enqueue(response().setHeader("Location", server.url("$uploadPath?upload_id=private-session")))
                server.enqueue(response(status = 308).setHeader("Range", "bytes=0-131071"))
                server.enqueue(response(status = 308).setHeader("Range", "bytes=0-393215"))
                server.enqueue(response(bundleJson()))
                val progress = mutableListOf<UploadProgress>()
                val bundle = client.uploadBundle(edit, artifact, progress::add)
                assertEquals(42, bundle.versionCode)
                val init = record(server)
                assertEquals("$uploadPath?uploadType=resumable", init.path)
                assertEquals("0", init.getHeader("Content-Length")); assertEquals("application/octet-stream", init.getHeader("X-Upload-Content-Type"))
                assertEquals(artifact.sizeBytes.toString(), init.getHeader("X-Upload-Content-Length"))
                val first = record(server); val second = record(server); val last = record(server)
                assertEquals("bytes 0-262143/${artifact.sizeBytes}", first.getHeader("Content-Range"))
                assertEquals("bytes 131072-393215/${artifact.sizeBytes}", second.getHeader("Content-Range"))
                assertEquals("bytes 393216-${artifact.sizeBytes - 1}/${artifact.sizeBytes}", last.getHeader("Content-Range"))
                assertEquals(262144, first.bodySize); assertEquals(262144, second.bodySize)
                assertEquals(131072, progress.first().bytesAcknowledged)
                assertEquals(artifact.sizeBytes, progress.last().bytesAcknowledged)

                server.enqueue(response(listing.json().toString()))
                assertEquals(listing, client.updateListing(edit, listing))
                val listingRequest = record(server)
                assertEquals("$base/listings/en-US", listingRequest.path); assertEquals("PUT", listingRequest.method)
                assertEquals("Fixture", json.parseToJsonElement(listingRequest.body.readUtf8()).jsonObject["title"]!!.jsonPrimitive.content)

                server.enqueue(response(track().json().toString()))
                client.updateTrack(edit, track())
                val trackRequest = record(server)
                assertEquals("$base/tracks/closed-test", trackRequest.path)
                val version = json.parseToJsonElement(trackRequest.body.readUtf8()).jsonObject["releases"]!!.jsonArray[0].jsonObject["versionCodes"]!!.jsonArray[0].jsonPrimitive
                assertTrue(version.isString); assertEquals("42", version.content)
                assertThrows(PlayPublishingException::class.java) { runBlocking { client.previewCommit(edit) } }

                server.enqueue(response(editJson()))
                client.validateEdit(edit)
                val validation = record(server)
                assertEquals("$base:validate", validation.path); assertEquals(0, validation.bodySize)
                val preview = client.previewCommit(edit)
                assertTrue(preview.changes.size >= 3)
                val confirmed = preview.confirmAfterUserReview()
                server.enqueue(response(editJson()))
                client.commitEdit(edit, confirmed)
                val commit = record(server)
                assertEquals("POST", commit.method)
                assertEquals("$base:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW", commit.path)
                assertEquals(0, commit.bodySize)
                assertFalse(commit.path!!.contains("changesNotSentForReview"))
                assertThrows(PlayPublishingException::class.java) { runBlocking { client.commitEdit(edit, confirmed) } }
            }
        }
    }

    @Test fun `server error queries saved offset instead of blindly replaying a chunk`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { client ->
                server.enqueue(response(editJson())); val edit = client.createEdit(pkg); record(server)
                server.enqueue(response().setHeader("Location", server.url("$uploadPath?upload_id=secret")))
                server.enqueue(response(status = 503))
                server.enqueue(response(status = 308).setHeader("Range", "bytes=0-262143"))
                server.enqueue(response(status = 308).setHeader("Range", "bytes=0-524287"))
                server.enqueue(response(bundleJson()))
                client.uploadBundle(edit, artifact)
                record(server); record(server)
                val query = record(server)
                assertEquals("PUT", query.method); assertEquals(0, query.bodySize)
                assertEquals("bytes */${artifact.sizeBytes}", query.getHeader("Content-Range"))
                assertEquals("bytes 262144-524287/${artifact.sizeBytes}", record(server).getHeader("Content-Range"))
                assertEquals("bytes 524288-${artifact.sizeBytes - 1}/${artifact.sizeBytes}", record(server).getHeader("Content-Range"))
            }
        }
    }

    @Test fun `no-progress retry limit stops upload and blocks unknown edit commitment`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server, retries = 1).use { client ->
                server.enqueue(response(editJson())); val edit = client.createEdit(pkg)
                server.enqueue(response().setHeader("Location", server.url("$uploadPath?upload_id=secret")))
                server.enqueue(response(status = 308)); server.enqueue(response(status = 308))
                val failure = assertThrows(PlayPublishingException::class.java) { runBlocking { client.uploadBundle(edit, artifact) } }
                assertEquals(PlayErrorKind.UNCERTAIN_RESULT, failure.kind)
                val gate = assertThrows(PlayPublishingException::class.java) { runBlocking { client.validateEdit(edit) } }
                assertEquals(PlayErrorKind.UNCERTAIN_RESULT, gate.kind)
                assertEquals(4, server.requestCount)
            }
        }
    }

    @Test fun `untrusted upload location is refused before forwarding credentials`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { client ->
                server.enqueue(response(editJson())); val edit = client.createEdit(pkg)
                server.enqueue(response().setHeader("Location", "https://attacker.invalid/upload?secret=session-token"))
                val error = assertThrows(PlayPublishingException::class.java) { runBlocking { client.uploadBundle(edit, artifact) } }
                assertEquals(PlayErrorKind.PROTOCOL, error.kind)
                assertFalse(error.toString().contains("session-token")); assertFalse(error.toString().contains("attacker"))
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun `no redirects and no raw API response error leaks`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { client ->
                server.enqueue(response("""{"error":{"message":"session-token https://private/?upload_id=secret"}}""", 302).setHeader("Location", "https://attacker.invalid"))
                val error = assertThrows(PlayPublishingException::class.java) { runBlocking { client.createEdit(pkg) } }
                assertEquals(PlayErrorKind.PROTOCOL, error.kind)
                assertFalse(error.toString().contains("session-token")); assertFalse(error.toString().contains("upload_id"))
                assertNull(error.cause); assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun `review-in-progress is not cancelled and no draft fallback occurs`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { client ->
                server.enqueue(response(editJson())); val edit = client.createEdit(pkg)
                val production = PlayTrack("production", listOf(PlayRelease(listOf(42), PlayReleaseStatus.COMPLETED)))
                server.enqueue(response(production.json().toString())); client.updateTrack(edit, production)
                server.enqueue(response(editJson())); client.validateEdit(edit)
                val preview = client.previewCommit(edit)
                assertTrue(preview.warnings.any { "PRODUCTION" in it })
                server.enqueue(response("""{"error":{"code":400,"details":[{"reason":"CHANGES_ALREADY_IN_REVIEW"}],"message":"private session-token"}}""", 400))
                val error = assertThrows(PlayPublishingException::class.java) { runBlocking { client.commitEdit(edit, preview.confirmAfterUserReview()) } }
                assertEquals(PlayErrorKind.CONFLICT, error.kind); assertEquals("CHANGES_ALREADY_IN_REVIEW", error.reason)
                assertEquals(4, server.requestCount)
                assertFalse(error.toString().contains("session-token"))
            }
        }
    }

    @Test fun `confirmation is invalidated by changes and cannot cross clients`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { client ->
                server.enqueue(response(editJson())); val edit = client.createEdit(pkg)
                server.enqueue(response(track().json().toString())); client.updateTrack(edit, track())
                server.enqueue(response(editJson())); client.validateEdit(edit)
                val old = client.previewCommit(edit).confirmAfterUserReview()
                server.enqueue(response(listing.json().toString())); client.updateListing(edit, listing)
                val error = assertThrows(PlayPublishingException::class.java) { runBlocking { client.commitEdit(edit, old) } }
                assertEquals(PlayErrorKind.CONFIRMATION, error.kind)
                assertEquals(4, server.requestCount)
            }
        }
    }

    @Test fun `images use correct path and distinguish explicit delete from append`() = runBlocking<Unit> {
        val image = PlayPreflight.inspectImage(PlayTestFixtures.png(temp, 1024, 500, false, "feature.png"), PlayImageType.FEATURE_GRAPHIC)
        MockWebServer().use { server ->
            server.start(); client(server).use { client ->
                server.enqueue(response(editJson())); val edit = client.createEdit(pkg); record(server)
                server.enqueue(response("""{"image":{"id":"image1","sha256":"${image.sha256}"}}"""))
                client.uploadImage(edit, "en-US", image)
                val upload = record(server)
                assertEquals("/upload$base/listings/en-US/featureGraphic?uploadType=media", upload.path)
                assertEquals("image/png", upload.getHeader("Content-Type")); assertArrayEquals(image.file.readBytes(), upload.body.readByteArray())
                server.enqueue(response()); client.deleteImages(edit, "en-US", PlayImageType.FEATURE_GRAPHIC)
                assertEquals("DELETE", record(server).method)
            }
        }
    }

    @Test fun `data safety is a separate confirmed JSON CSV operation and cannot reuse confirmation`() = runBlocking<Unit> {
        val draft = DataSafetyDraft(pkg, "Question ID,Response,Response value\nPSL_DATA_TYPES_PERSONAL,PSL_NAME,FALSE\n")
        MockWebServer().use { server ->
            server.start(); client(server).use { client ->
                val preview = client.previewDataSafety(draft)
                assertEquals(0, server.requestCount)
                val confirmation = preview.confirmAfterUserReview()
                server.enqueue(response())
                client.submitDataSafety(draft, confirmation)
                val request = record(server)
                assertEquals("/androidpublisher/v3/applications/$pkg/dataSafety", request.path)
                assertEquals(draft.csv, json.parseToJsonElement(request.body.readUtf8()).jsonObject["safetyLabels"]!!.jsonPrimitive.content)
                assertThrows(PlayPublishingException::class.java) { runBlocking { client.submitDataSafety(draft, confirmation) } }
                assertFalse(draft.toString().contains("PSL_NAME")); assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun `internal sharing uses separate exact path and explicit confirmation`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { client ->
                val path = "/upload/androidpublisher/v3/applications/internalappsharing/$pkg/artifacts/bundle"
                server.enqueue(response().setHeader("Location", server.url("$path?upload_id=sharing-secret")))
                server.enqueue(response(status = 308).setHeader("Range", "bytes=0-262143"))
                server.enqueue(response(status = 308).setHeader("Range", "bytes=0-524287"))
                server.enqueue(response("""{"downloadUrl":"https://play.google.com/apps/test/example","certificateFingerprint":"certificate","sha256":"artifact-hash"}"""))
                val result = client.uploadInternalSharing(artifact, client.previewInternalSharing(artifact).confirmAfterUserReview())
                assertEquals("$path?uploadType=resumable", record(server).path)
                assertEquals("https://play.google.com/apps/test/example", result.downloadUrl)
                assertEquals("certificate", result.certificateFingerprint)
            }
        }
    }

    @Test fun `release monitoring does not create an edit or equate approved with published`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { client ->
                server.enqueue(response("""{"releases":[{"releaseName":"1.0","track":"production","activeArtifacts":[{"versionCode":42}],"releaseLifecycleState":"RELEASE_LIFECYCLE_STATE_APPROVED_NOT_PUBLISHED"}]}"""))
                val releases = client.listReleases(pkg, "production")
                assertFalse(releases.single().isPublished)
                val request = record(server)
                assertEquals("GET", request.method); assertEquals("/androidpublisher/v3/applications/$pkg/tracks/production/releases", request.path)
            }
        }
    }

    @Test fun `cancellation interrupts in-flight HTTP and no subsequent writes occur`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { client ->
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val operation = async { client.createEdit(pkg) }
                repeat(100) { if (server.requestCount == 0) delay(10) }
                operation.cancelAndJoin()
                assertTrue(operation.isCancelled); assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun `documented resumable prefix location is accepted and retained verbatim`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { client ->
                server.enqueue(response(editJson())); val edit = client.createEdit(pkg); record(server)
                val location = "/resumable$uploadPath?upload_id=private-session"
                server.enqueue(response().setHeader("Location", server.url(location)))
                server.enqueue(response(status = 308).setHeader("Range", "bytes=0-262143"))
                server.enqueue(response(status = 308).setHeader("Range", "bytes=0-524287"))
                server.enqueue(response(bundleJson()))
                client.uploadBundle(edit, artifact)
                record(server)
                repeat(3) { assertEquals(location, record(server).path) }
            }
        }
    }

    @Test fun `disconnect during response body is redacted and blocks uncertain edit commit`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { client ->
                server.enqueue(response(editJson())); val edit = client.createEdit(pkg)
                server.enqueue(response(listing.json().toString()).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
                val error = assertThrows(PlayPublishingException::class.java) { runBlocking { client.updateListing(edit, listing) } }
                assertEquals(PlayErrorKind.NETWORK, error.kind)
                assertNull(error.cause); assertFalse(error.toString().contains("session-token"))
                assertEquals(PlayErrorKind.UNCERTAIN_RESULT, assertThrows(PlayPublishingException::class.java) { runBlocking { client.validateEdit(edit) } }.kind)
            }
        }
    }

    @Test fun `cancellation closes response body without waiting for read timeout`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { client ->
                server.enqueue(response(editJson()).setBodyDelay(3, TimeUnit.SECONDS))
                val operation = async { client.createEdit(pkg) }
                repeat(100) { if (server.requestCount == 0) delay(10) }
                delay(50)
                val start = System.nanoTime()
                operation.cancelAndJoin()
                assertTrue(operation.isCancelled)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000)
            }
        }
    }

    @Test fun `malformed array objects cannot leak raw response bodies`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { client ->
                server.enqueue(response("""{"releases":["private session-token https://example/?upload_id=secret"]}"""))
                val error = assertThrows(PlayPublishingException::class.java) { runBlocking { client.listReleases(pkg, "production") } }
                assertEquals(PlayErrorKind.PROTOCOL, error.kind)
                assertFalse(error.toString().contains("session-token")); assertFalse(error.toString().contains("upload_id"))
            }
        }
    }

    @Test fun `upload completion detected by status query is not uploaded twice`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { client ->
                server.enqueue(response(editJson())); val edit = client.createEdit(pkg); record(server)
                server.enqueue(response().setHeader("Location", server.url("$uploadPath?upload_id=secret")))
                server.enqueue(response(status = 308).setHeader("Range", "bytes=0-262143"))
                server.enqueue(response(status = 308).setHeader("Range", "bytes=0-524287"))
                server.enqueue(response(status = 503))
                server.enqueue(response(bundleJson()))
                assertEquals(42, client.uploadBundle(edit, artifact).versionCode)
                repeat(4) { record(server) }
                val query = record(server)
                assertEquals("bytes */${artifact.sizeBytes}", query.getHeader("Content-Range")); assertEquals(0, query.bodySize)
            }
        }
    }

    @Test fun `ambiguous final PUT followed by failed status never permits validation or commitment`() = runBlocking<Unit> {
        for (status in listOf(401, 403, 404, 410)) {
            MockWebServer().use { server ->
                server.start()
                val tokens = SessionAccessTokenProvider("session-token")
                PlayPublisherClient(tokens, PlayPublisherConfig(server.url("/").toString(), true, 1_048_576, 1, 0, 5000)).use { client ->
                    server.enqueue(response(editJson())); val edit = client.createEdit(pkg)
                    server.enqueue(response().setHeader("Location", server.url("$uploadPath?upload_id=secret")))
                    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
                    server.enqueue(response("""{"error":{"message":"private session-token"}}""", status))
                    val error = assertThrows(PlayPublishingException::class.java) { runBlocking { client.uploadBundle(edit, artifact) } }
                    assertEquals(PlayErrorKind.UNCERTAIN_RESULT, error.kind, "status $status")
                    tokens.setToken("replacement-token")
                    assertEquals(PlayErrorKind.UNCERTAIN_RESULT, assertThrows(PlayPublishingException::class.java) { runBlocking { client.validateEdit(edit) } }.kind)
                    assertEquals(PlayErrorKind.UNCERTAIN_RESULT, assertThrows(PlayPublishingException::class.java) { runBlocking { client.previewCommit(edit) } }.kind)
                    assertEquals(4, server.requestCount)
                }
            }
        }
    }

    @Test fun `image changed while access token is acquired is not commit eligible`() = runBlocking<Unit> {
        val imageFile = PlayTestFixtures.png(temp, 1024, 500, false, "race-image.png")
        val asset = PlayPreflight.inspectImage(imageFile, PlayImageType.FEATURE_GRAPHIC)
        MockWebServer().use { server ->
            server.start()
            var requests = 0
            val provider = PlayAccessTokenProvider {
                requests++
                if (requests == 2) imageFile.writeBytes("different image content".toByteArray())
                "session-token"
            }
            PlayPublisherClient(provider, PlayPublisherConfig(server.url("/").toString(), true, retryBaseDelayMillis = 0)).use { client ->
                server.enqueue(response(editJson())); val edit = client.createEdit(pkg)
                server.enqueue(response("""{"image":{"id":"image1","sha256":"${asset.sha256}"}}"""))
                val error = assertThrows(PlayPublishingException::class.java) { runBlocking { client.uploadImage(edit, "en-US", asset) } }
                assertEquals(PlayErrorKind.UNCERTAIN_RESULT, error.kind)
                assertEquals(PlayErrorKind.UNCERTAIN_RESULT, assertThrows(PlayPublishingException::class.java) { runBlocking { client.validateEdit(edit) } }.kind)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun `failed revalidation invalidates earlier validated revision and confirmation`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { client ->
                server.enqueue(response(editJson())); val edit = client.createEdit(pkg)
                server.enqueue(response(track().json().toString())); client.updateTrack(edit, track())
                server.enqueue(response(editJson())); client.validateEdit(edit)
                val old = client.previewCommit(edit).confirmAfterUserReview()
                server.enqueue(response(status = 400))
                assertThrows(PlayPublishingException::class.java) { runBlocking { client.validateEdit(edit) } }
                assertEquals(PlayErrorKind.CONFIRMATION, assertThrows(PlayPublishingException::class.java) { runBlocking { client.commitEdit(edit, old) } }.kind)
                assertThrows(PlayPublishingException::class.java) { runBlocking { client.previewCommit(edit) } }
                assertEquals(4, server.requestCount)
            }
        }
    }

    @Test fun `retained track fields include country targeting and unknown server fields`() = runBlocking<Unit> {
        val existing = """{"track":"production","futureTrackSetting":{"enabled":true},"releases":[{"versionCodes":["42"],"status":"inProgress","userFraction":0.1,"countryTargeting":{"countries":["US","CA"],"includeRestOfWorld":false,"futureTargeting":"keep"},"futureReleaseSetting":"keep"}]}"""
        MockWebServer().use { server ->
            server.start(); client(server).use { client ->
                server.enqueue(response(editJson())); val edit = client.createEdit(pkg); record(server)
                server.enqueue(response(existing)); val current = client.getTrack(edit, "production"); record(server)
                assertEquals(listOf("US", "CA"), current.releases.single().countryTargeting!!.countries)
                server.enqueue(response(existing)); client.updateTrack(edit, current)
                val request = record(server)
                assertEquals(json.parseToJsonElement(existing), json.parseToJsonElement(request.body.readUtf8()))
            }
        }
    }

    @Test fun `loopback is opt-in and production configuration rejects credential exfiltration origins`() {
        assertThrows(PlayPublishingException::class.java) { PlayPublisherClient(SessionAccessTokenProvider("token"), PlayPublisherConfig("http://127.0.0.1:12345/")) }
        assertThrows(PlayPublishingException::class.java) { PlayPublisherClient(SessionAccessTokenProvider("token"), PlayPublisherConfig("https://evil.invalid/", true)) }
        assertThrows(PlayPublishingException::class.java) { PlayPublisherClient(SessionAccessTokenProvider("token"), PlayPublisherConfig("https://androidpublisher.googleapis.com@evil.invalid/")) }
        val tokens = SessionAccessTokenProvider("session-token")
        assertFalse(tokens.toString().contains("session-token")); assertTrue(tokens.hasToken); tokens.clear(); assertFalse(tokens.hasToken)
        assertThrows(PlayPublishingException::class.java) { runBlocking { tokens.accessToken() } }
    }
}
