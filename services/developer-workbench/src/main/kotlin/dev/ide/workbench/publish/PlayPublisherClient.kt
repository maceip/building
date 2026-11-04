package dev.ide.workbench.publish

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/**
 * Real Android Publisher v3 operations. Network writes are never dry runs.
 * Nothing automatically commits, changes release status, completes declarations, or grants production access.
 * One instance owns its in-memory edit/confirmation state; do not share it between different Google accounts.
 */
class PlayPublisherClient(
    tokenProvider: PlayAccessTokenProvider,
    private val config: PlayPublisherConfig = PlayPublisherConfig(),
) : AutoCloseable {
    private val transport = PlayPublisherTransport(tokenProvider, config)
    private val clientId = UUID.randomUUID().toString()
    private val mutex = Mutex()
    private val edits = mutableMapOf<String, EditState>()
    private class EditState(var edit: PlayEdit) {
        var revision = 0L
        var validatedRevision: Long? = null
        var uncertain = false
        val changes = mutableListOf<String>()
        val tracks = linkedMapOf<String, PlayTrack>()
    }

    suspend fun createEdit(packageName: String): PlayEdit = mutex.withLock {
        PlayPreflight.validatePackageName(packageName)
        // Initiation can invalidate this user's previous edit even if the response is lost.
        edits.entries.removeAll { it.value.edit.packageName == packageName }
        val edit = parseEdit(packageName, json("POST", transport.url("applications", packageName, "edits"), buildJsonObject {}))
        edits[key(edit)] = EditState(edit)
        edit
    }

    suspend fun getEdit(edit: PlayEdit): PlayEdit = mutex.withLock {
        checkEditIdentity(edit)
        val current = parseEdit(edit.packageName, json("GET", editUrl(edit)))
        edits[key(edit)]?.edit = current
        current
    }

    suspend fun deleteEdit(edit: PlayEdit) = mutex.withLock {
        checkEditIdentity(edit)
        // An attempted discard must invalidate outstanding submission confirmations even if its response is lost.
        val local = edits[key(edit)]
        local?.let { it.revision++; it.validatedRevision = null }
        try {
            transport.requireSuccess(transport.request("DELETE", editUrl(edit)))
            edits.remove(key(edit))
        } catch (e: CancellationException) { local?.uncertain = true; throw e }
        catch (e: PlayPublishingException) {
            if (e.kind in setOf(PlayErrorKind.NETWORK, PlayErrorKind.PROTOCOL)) local?.uncertain = true
            throw e
        }
        Unit
    }

    suspend fun listBundles(edit: PlayEdit): List<PlayBundle> = mutex.withLock {
        checkEditIdentity(edit)
        json("GET", editUrl(edit, "bundles")).array("bundles").map { parseBundle(it.asObject()) }
    }

    suspend fun uploadBundle(edit: PlayEdit, artifact: AabArtifact, onProgress: (UploadProgress) -> Unit = {}): PlayBundle = mutex.withLock {
        if (artifact.packageName != edit.packageName) invalid("The selected bundle belongs to a different Play application.")
        withContext(Dispatchers.IO) { PlayPreflight.ensureUnchanged(artifact) }
        mutate(edit, "Upload bundle version ${artifact.versionCode} (SHA-256 ${artifact.sha256}).") {
            val url = transport.url("applications", edit.packageName, "edits", edit.id, "bundles", upload = true, query = mapOf("uploadType" to "resumable"))
            val result = parseBundle(resumableUpload(url, artifact.file, artifact.sizeBytes, artifact.sha256, onProgress))
            if (result.versionCode != artifact.versionCode || !result.sha256.equals(artifact.sha256, ignoreCase = true)) {
                protocol("The uploaded bundle metadata does not match the verified local artifact. Do not commit this edit.")
            }
            result
        }
    }

    suspend fun getListing(edit: PlayEdit, language: String): PlayListing = mutex.withLock {
        PlayPreflight.validateLanguage(language); checkEditIdentity(edit)
        parseListing(json("GET", editUrl(edit, "listings", language)))
    }

    suspend fun listListings(edit: PlayEdit): List<PlayListing> = mutex.withLock {
        checkEditIdentity(edit)
        json("GET", editUrl(edit, "listings")).array("listings").map { parseListing(it.asObject()) }
    }

    suspend fun updateListing(edit: PlayEdit, listing: PlayListing): PlayListing = mutex.withLock {
        PlayPreflight.validateListing(listing)
        mutate(edit, "Replace ${listing.language} store listing: ${listing.title}.") {
            parseListing(json("PUT", editUrl(edit, "listings", listing.language), listing.json()))
        }
    }

    suspend fun updateDetails(edit: PlayEdit, details: PlayAppDetails): PlayAppDetails = mutex.withLock {
        PlayPreflight.validateLanguage(details.defaultLanguage)
        if (!details.contactEmail.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) invalid("Provide a valid support email address.")
        val body = buildJsonObject {
            put("defaultLanguage", details.defaultLanguage); put("contactEmail", details.contactEmail)
            details.contactWebsite?.let { put("contactWebsite", it) }; details.contactPhone?.let { put("contactPhone", it) }
        }
        mutate(edit, "Update default language and public support contact details.") {
            val result = json("PUT", editUrl(edit, "details"), body)
            PlayAppDetails(result.string("defaultLanguage"), result.string("contactEmail"), result.optionalString("contactWebsite"), result.optionalString("contactPhone"))
        }
    }

    suspend fun listImages(edit: PlayEdit, language: String, type: PlayImageType): List<PlayImage> = mutex.withLock {
        checkEditIdentity(edit); PlayPreflight.validateLanguage(language)
        json("GET", editUrl(edit, "listings", language, type.wireValue)).array("images").map { parseImage(it.asObject()) }
    }

    suspend fun uploadImage(edit: PlayEdit, language: String, asset: ImageAsset): PlayImage = mutex.withLock {
        PlayPreflight.validateLanguage(language)
        withContext(Dispatchers.IO) { PlayPreflight.ensureUnchanged(asset) }
        mutate(edit, "Add ${asset.type.wireValue} for $language (${asset.width} by ${asset.height}).") {
            // The documented path does NOT have an /images/ segment. Upload appends; replacement is explicit.
            val url = transport.url("applications", edit.packageName, "edits", edit.id, "listings", language, asset.type.wireValue, upload = true, query = mapOf("uploadType" to "media"))
            val result = transport.requireSuccess(transport.request("POST", url, asset.file.asRequestBody(asset.mimeType.toMediaType())))
            val image = parseImage(parseObject(result.body).obj("image"))
            val unchanged = try { withContext(Dispatchers.IO) { PlayPreflight.ensureUnchanged(asset); true } }
                catch (_: PlayPublishingException) { false }
            if (!unchanged || !image.sha256.equals(asset.sha256, ignoreCase = true)) {
                throw PlayPublishingException(PlayErrorKind.UNCERTAIN_RESULT, "The uploaded image does not match the verified local asset. Discard this edit; do not commit unknown artwork.")
            }
            image
        }
    }

    suspend fun deleteImages(edit: PlayEdit, language: String, type: PlayImageType) = mutex.withLock {
        PlayPreflight.validateLanguage(language)
        mutate(edit, "Delete ALL ${type.wireValue} images for $language.") {
            transport.requireSuccess(transport.request("DELETE", editUrl(edit, "listings", language, type.wireValue)))
            Unit
        }
    }

    suspend fun listTracks(edit: PlayEdit): List<PlayTrack> = mutex.withLock {
        checkEditIdentity(edit)
        json("GET", editUrl(edit, "tracks")).array("tracks").map { parseTrack(it.asObject()) }
    }

    suspend fun getTrack(edit: PlayEdit, track: String): PlayTrack = mutex.withLock {
        checkEditIdentity(edit); PlayPreflight.validateTrackId(track)
        parseTrack(json("GET", editUrl(edit, "tracks", track)))
    }

    suspend fun createClosedTrack(edit: PlayEdit, track: String): PlayTrack = mutex.withLock {
        PlayPreflight.validateTrackId(track)
        mutate(edit, "Create closed testing track $track.") {
            parseTrack(json("POST", editUrl(edit, "tracks"), buildJsonObject { put("track", track); put("type", "CLOSED_TESTING"); put("formFactor", "DEFAULT") }))
        }
    }

    suspend fun updateTrack(edit: PlayEdit, track: PlayTrack): PlayTrack = mutex.withLock {
        PlayPreflight.validateTrack(track)
        val description = "Set track ${track.track}: " + track.releases.joinToString { release ->
            "${release.status.wireValue} version(s) ${release.versionCodes.joinToString()}" +
                release.userFraction?.let { " at ${it * 100}%" }.orEmpty() +
                release.countryTargeting?.let { " targeting ${it.countries.joinToString()}${if (it.includeRestOfWorld) " plus rest of world" else ""}" }.orEmpty()
        } + if (track.additionalFields.isNotEmpty() || track.releases.any { it.additionalFields.isNotEmpty() || it.countryTargeting?.additionalFields?.isNotEmpty() == true }) " (preserves additional server fields)" else ""
        val result = mutate(edit, description) { parseTrack(json("PUT", editUrl(edit, "tracks", track.track), track.json())) }
        state(edit).tracks[track.track] = track
        result
    }

    /** Configures Google GROUP addresses only; does not enroll people or change group membership. */
    suspend fun updateTesterGroups(edit: PlayEdit, track: String, googleGroups: List<String>) = mutex.withLock {
        PlayPreflight.validateTrackId(track)
        if (googleGroups.isEmpty() || googleGroups.any { !it.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) }) invalid("Supply explicit Google Group email addresses, not individual tester addresses.")
        mutate(edit, "Replace the Google Groups permitted on testing track $track; users must still join and opt in themselves.") {
            json("PUT", editUrl(edit, "testers", track), buildJsonObject { put("googleGroups", JsonArray(googleGroups.map(::JsonPrimitive))) })
            Unit
        }
    }

    suspend fun uploadMapping(edit: PlayEdit, versionCode: Long, file: File, nativeSymbols: Boolean = false) = mutex.withLock {
        if (versionCode !in 1..2_100_000_000L || !file.isFile || file.length() !in 1..2_097_152_000L) invalid("Select the matching build's mapping file or native symbols archive and a valid version code.")
        val digest = withContext(Dispatchers.IO) { sha256(file) }
        val type = if (nativeSymbols) "nativeCode" else "proguard"
        mutate(edit, "Attach $type deobfuscation data to version $versionCode.") {
            val url = transport.url("applications", edit.packageName, "edits", edit.id, "apks", versionCode.toString(), "deobfuscationFiles", type, upload = true, query = mapOf("uploadType" to "resumable"))
            resumableUpload(url, file, file.length(), digest) {}
            Unit
        }
    }

    suspend fun validateEdit(edit: PlayEdit): PlayEdit = mutex.withLock {
        val state = state(edit)
        if (state.uncertain) uncertainEdit()
        // Revalidation itself invalidates earlier previews, even if this attempt fails or is cancelled.
        state.revision++
        state.validatedRevision = null
        val validated = parseEdit(edit.packageName, json("POST", actionUrl(edit, "validate"), emptyBody = true))
        if (validated.id != edit.id) protocol("Google returned an unexpected edit identifier.")
        state.edit = validated
        state.validatedRevision = state.revision
        validated
    }

    suspend fun previewCommit(edit: PlayEdit): CommitPreview = mutex.withLock {
        val state = state(edit)
        if (state.uncertain) uncertainEdit()
        if (state.validatedRevision != state.revision) invalid("Validate the edit after its most recent change before confirming submission.")
        if (state.changes.isEmpty()) invalid("There are no changes staged by this client to confirm.")
        val warnings = mutableListOf(
            "This commits all changes in the edit to Google Play and can submit them for review and distribution. API validation is not policy approval.",
            "Existing reviews will not be cancelled automatically. Review, managed publishing, account eligibility, and policy gates may still require Play Console.",
            "Draft releases do not distribute their bundle, but other committed listing changes are not a universal dry run.",
        )
        if (state.tracks.keys.any { it == "production" || it.endsWith(":production") }) warnings += "PRODUCTION TRACK: this can affect real users after Google Play processing. Verify version codes, rollout fraction, and all retained releases."
        CommitPreview(state.edit, state.changes.toList(), warnings, clientId, state.revision)
    }

    suspend fun commitEdit(edit: PlayEdit, confirmation: CommitConfirmation): PlayEdit = mutex.withLock {
        val state = state(edit)
        if (state.uncertain) uncertainEdit()
        if (confirmation.clientId != clientId || confirmation.edit.packageName != edit.packageName || confirmation.edit.id != edit.id || confirmation.revision != state.revision || state.validatedRevision != state.revision || !confirmation.used.compareAndSet(false, true)) {
            confirmationFailure("Review the current validated edit and explicitly confirm it again.")
        }
        try {
            // The API's default cancels reviews. This conservative behavior is never relaxed implicitly.
            val committed = parseEdit(edit.packageName, json("POST", actionUrl(edit, "commit", mapOf("changesInReviewBehavior" to "ERROR_IF_IN_REVIEW")), emptyBody = true))
            edits.remove(key(edit))
            committed
        } catch (e: CancellationException) {
            state.uncertain = true; throw e
        } catch (e: PlayPublishingException) {
            if (e.kind in setOf(PlayErrorKind.NETWORK, PlayErrorKind.PROTOCOL)) {
                state.uncertain = true
                throw PlayPublishingException(PlayErrorKind.UNCERTAIN_RESULT, "The commit result is unknown. Check release status and Play Console; do not automatically retry or create another release.", e.httpStatus)
            }
            throw e
        }
    }

    /** No edit required. A published lifecycle also covers staged or resumably halted rollouts. */
    suspend fun listReleases(packageName: String, track: String): List<PlayReleaseSummary> = mutex.withLock {
        PlayPreflight.validatePackageName(packageName); PlayPreflight.validateTrackId(track)
        json("GET", transport.url("applications", packageName, "tracks", track, "releases")).array("releases").map { item ->
            val value = item.asObject()
            PlayReleaseSummary(value.optionalString("releaseName").orEmpty(), value.optionalString("track") ?: track, value.array("activeArtifacts").map { it.asObject().long("versionCode") }, value.string("releaseLifecycleState"))
        }
    }

    fun previewInternalSharing(artifact: AabArtifact): InternalSharingPreview = InternalSharingPreview(
        artifact.packageName, artifact.versionCode,
        listOf("This immediately uploads a build to Google and creates an install link; it is not a dry run or a Play Store release.", "The account/app must be configured for internal app sharing. Testers need app availability, download permission, and internal app sharing enabled on their device.", "Google re-signs this build with its internal-sharing certificate. Each link permits up to 100 downloaders and expires after 60 days; no processing-time guarantee applies."),
        clientId, artifact.sha256,
    )

    suspend fun uploadInternalSharing(artifact: AabArtifact, confirmation: InternalSharingConfirmation, onProgress: (UploadProgress) -> Unit = {}): InternalSharingArtifact = mutex.withLock {
        if (confirmation.clientId != clientId || confirmation.packageName != artifact.packageName || confirmation.sha256 != artifact.sha256 || !confirmation.used.compareAndSet(false, true)) confirmationFailure("Explicitly confirm the selected build's internal app sharing upload.")
        if (artifact.sizeBytes > 10_737_418_240L) invalid("The bundle exceeds the internal app sharing upload limit.")
        withContext(Dispatchers.IO) { PlayPreflight.ensureUnchanged(artifact) }
        val url = transport.url("applications", "internalappsharing", artifact.packageName, "artifacts", "bundle", upload = true, query = mapOf("uploadType" to "resumable"))
        val result = resumableUpload(url, artifact.file, artifact.sizeBytes, artifact.sha256, onProgress)
        val download = result.string("downloadUrl")
        val parsed = runCatching { java.net.URI(download) }.getOrNull()
        if (parsed?.scheme != "https" || parsed.host != "play.google.com" || parsed.userInfo != null) protocol("Google returned an unexpected internal-sharing link.")
        InternalSharingArtifact(download, result.string("certificateFingerprint"), result.string("sha256"))
    }

    fun previewDataSafety(draft: DataSafetyDraft): DataSafetyPreview {
        PlayPreflight.validateDataSafety(draft)
        return DataSafetyPreview(draft.packageName, listOf("This replaces the app's Data safety answers outside the edit transaction; deleting an edit does not undo it.", "Use the current Play Console CSV template. The developer must verify SDK, server-side, collection, sharing, retention, and deletion practices.", "Static manifest scanning cannot prove these answers. This API does not complete other policy declarations or guarantee review approval."), clientId, sha256(draft.csv.toByteArray(Charsets.UTF_8)))
    }

    suspend fun submitDataSafety(draft: DataSafetyDraft, confirmation: DataSafetyConfirmation) = mutex.withLock {
        PlayPreflight.validateDataSafety(draft)
        if (confirmation.clientId != clientId || confirmation.packageName != draft.packageName || confirmation.digest != sha256(draft.csv.toByteArray(Charsets.UTF_8)) || !confirmation.used.compareAndSet(false, true)) confirmationFailure("Review and explicitly confirm this Data safety declaration before replacing the existing answers.")
        json("POST", transport.url("applications", draft.packageName, "dataSafety"), buildJsonObject { put("safetyLabels", draft.csv) })
        Unit
    }

    override fun close() = transport.close()

    private suspend fun <T> mutate(edit: PlayEdit, description: String, block: suspend () -> T): T {
        val state = state(edit)
        if (state.uncertain) uncertainEdit()
        state.revision++
        state.validatedRevision = null
        return try { block().also { state.changes += description } }
        catch (e: CancellationException) { state.uncertain = true; throw e }
        catch (e: PlayPublishingException) {
            if (e.kind in setOf(PlayErrorKind.NETWORK, PlayErrorKind.PROTOCOL, PlayErrorKind.UNCERTAIN_RESULT)) state.uncertain = true
            throw e
        }
    }

    private suspend fun resumableUpload(initialUrl: HttpUrl, file: File, size: Long, digest: String, onProgress: (UploadProgress) -> Unit): JsonObject {
        val sessionResponse = transport.requireSuccess(transport.request("POST", initialUrl, EMPTY_BODY, mapOf("X-Upload-Content-Type" to "application/octet-stream", "X-Upload-Content-Length" to size.toString())))
        val session = transport.uploadLocation(sessionResponse.location, initialUrl)
        var acknowledged = 0L
        var failures = 0
        var queryStatus = false
        var unresolvedWrite = false
        var lastSentEnd = 0L
        while (true) {
            coroutineContext.ensureActive()
            val statusRequest = queryStatus
            val count = if (statusRequest) 0 else minOf(config.chunkSizeBytes.toLong(), size - acknowledged).toInt()
            if (!statusRequest && count <= 0) { queryStatus = true; continue }
            val body: RequestBody = if (statusRequest) EMPTY_BODY else FileSliceBody(file, acknowledged, count)
            val contentRange = if (statusRequest) "bytes */$size" else "bytes $acknowledged-${acknowledged + count - 1}/$size"
            if (!statusRequest) lastSentEnd = acknowledged + count
            val result = try {
                transport.request("PUT", session, body, mapOf("Content-Range" to contentRange))
            } catch (e: PlayPublishingException) {
                if (e.kind != PlayErrorKind.NETWORK) {
                    if (unresolvedWrite) throw unresolvedUpload(e.httpStatus)
                    throw e
                }
                unresolvedWrite = true
                if (++failures > config.maxUploadRetries) throw PlayPublishingException(PlayErrorKind.UNCERTAIN_RESULT, "Upload retry limit reached. Its remote result is unknown; inspect the edit or discard it before starting again.")
                backoff(failures, null); queryStatus = true; continue
            }
            when {
                result.status == 308 -> {
                    val next = acknowledgedOffset(result.range, size)
                    if (next < acknowledged || next > lastSentEnd) protocol("Google reported an inconsistent upload offset; the upload was stopped.")
                    unresolvedWrite = false
                    if (next == acknowledged) {
                        if (++failures > config.maxUploadRetries) throw PlayPublishingException(PlayErrorKind.UNCERTAIN_RESULT, "The upload made no acknowledged progress; inspect or discard the edit before restarting.")
                        backoff(failures, result.retryAfter)
                    } else {
                        acknowledged = next; failures = 0
                        reportProgress(onProgress, acknowledged, size)
                    }
                    queryStatus = acknowledged == size
                }
                result.status in 200..299 -> {
                    // A status query may discover that the preceding final PUT already completed.
                    if (lastSentEnd != size) protocol("Google reported completion before all bundle bytes were sent.")
                    val unchanged = try { withContext(Dispatchers.IO) { file.length() == size && sha256(file) == digest } }
                        catch (_: PlayPublishingException) { false }
                    if (!unchanged) throw PlayPublishingException(PlayErrorKind.UNCERTAIN_RESULT, "The artifact changed during upload. Discard this edit; do not commit unknown content.")
                    reportProgress(onProgress, size, size)
                    return parseObject(result.body)
                }
                result.status == 429 || result.status in 500..599 -> {
                    if (!statusRequest) unresolvedWrite = true
                    if (++failures > config.maxUploadRetries) throw PlayPublishingException(PlayErrorKind.UNCERTAIN_RESULT, "The upload retry limit was reached. Check the edit before initiating another upload.", result.status)
                    backoff(failures, result.retryAfter); queryStatus = true
                }
                else -> {
                    if (unresolvedWrite) throw unresolvedUpload(result.status)
                    transport.requireSuccess(result); protocol("Unexpected resumable upload response.")
                }
            }
        }
    }

    private fun reportProgress(callback: (UploadProgress) -> Unit, acknowledged: Long, size: Long) {
        try { callback(UploadProgress(acknowledged, size)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw PlayPublishingException(PlayErrorKind.UNCERTAIN_RESULT, "The upload progress observer failed after a remote write. Inspect or discard the edit before proceeding.") }
    }

    private suspend fun backoff(failures: Int, retryAfter: String?) {
        val requested = retryAfter?.toLongOrNull()?.coerceIn(0, 60)?.times(1000)
        val exponential = (config.retryBaseDelayMillis * (1L shl (failures - 1).coerceAtMost(6))).coerceAtMost(60_000)
        delay(maxOf(exponential, requested ?: 0))
    }

    private fun acknowledgedOffset(range: String?, size: Long): Long {
        if (range == null) return 0
        val match = Regex("bytes=0-([0-9]+)").matchEntire(range.trim()) ?: protocol("Google returned an invalid resumable upload range.")
        val last = match.groupValues[1].toLongOrNull() ?: protocol("Google returned an invalid resumable upload offset.")
        if (last < 0 || last >= size) protocol("Google returned an upload range outside the selected artifact.")
        return last + 1
    }

    private suspend fun json(method: String, url: HttpUrl, body: JsonObject? = null, emptyBody: Boolean = false): JsonObject {
        val requestBody = body?.toString()?.toRequestBody(jsonMediaType) ?: if (emptyBody) EMPTY_BODY else null
        return parseObject(transport.requireSuccess(transport.request(method, url, requestBody)).body)
    }
    private fun editUrl(edit: PlayEdit, vararg suffix: String): HttpUrl = transport.url("applications", edit.packageName, "edits", edit.id, *suffix)
    private fun actionUrl(edit: PlayEdit, action: String, query: Map<String, String> = emptyMap()) = transport.url("applications", edit.packageName, "edits", "${edit.id}:$action", query = query)
    private fun state(edit: PlayEdit): EditState {
        checkEditIdentity(edit)
        val state = edits[key(edit)] ?: invalid("This client does not own the edit. Start a new edit and stage the intended changes.")
        if (state.edit.expiryTimeSeconds <= System.currentTimeMillis() / 1000) invalid("The edit has expired. Start a new edit and revalidate the intended changes.")
        return state
    }
    private fun checkEditIdentity(edit: PlayEdit) {
        PlayPreflight.validatePackageName(edit.packageName)
        if (edit.id.isBlank() || edit.id.length > 200 || edit.id.any { it !in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_" }) invalid("The edit identifier is invalid.")
    }
    private fun key(edit: PlayEdit) = "${edit.packageName}/${edit.id}"
    private fun parseEdit(packageName: String, value: JsonObject) = PlayEdit(packageName, value.string("id"), value.long("expiryTimeSeconds")).also(::checkEditIdentity)
    private fun parseBundle(value: JsonObject) = PlayBundle(value.long("versionCode"), value.string("sha256"), value.optionalString("sha1").orEmpty())
    private fun parseImage(value: JsonObject) = PlayImage(value.string("id"), value.optionalString("sha256").orEmpty(), value.optionalString("sha1").orEmpty())
    private fun parseListing(value: JsonObject) = PlayListing(value.string("language"), value.string("title"), value.string("shortDescription"), value.string("fullDescription"), value.optionalString("video"))
    private fun parseTrack(value: JsonObject): PlayTrack = PlayTrack(value.string("track"), value.array("releases").map { element ->
        val item = element.asObject()
        PlayRelease(
            item.array("versionCodes").map { (it as? JsonPrimitive)?.longOrNull ?: protocol("Google returned a malformed version code.") },
            PlayReleaseStatus.fromWire(item.string("status")) ?: protocol("Google returned an unsupported release status; inspect this track in Play Console."),
            item.optionalString("name"), item.array("releaseNotes").map { val note = it.asObject(); PlayReleaseNote(note.string("language"), note.string("text")) },
            (item["userFraction"] as? JsonPrimitive)?.doubleOrNull,
            (item["inAppUpdatePriority"] as? JsonPrimitive)?.intOrNull,
            item["countryTargeting"]?.let { element ->
                val targeting = element.asObject()
                PlayCountryTargeting(targeting.array("countries").map { (it as? JsonPrimitive)?.contentOrNull ?: protocol("Google returned a malformed country code.") }, (targeting["includeRestOfWorld"] as? JsonPrimitive)?.booleanOrNull ?: false, JsonObject(targeting.filterKeys { it !in setOf("countries", "includeRestOfWorld") }))
            },
            JsonObject(item.filterKeys { it !in RELEASE_FIELDS }),
        )
    }, JsonObject(value.filterKeys { it !in setOf("track", "releases") }))
    private fun unresolvedUpload(status: Int?) = PlayPublishingException(PlayErrorKind.UNCERTAIN_RESULT, "A bundle write may have completed, but its status could not be confirmed. Restore account access, inspect Play Console, and discard this edit; do not validate or commit unknown content.", status)
    private fun uncertainEdit(): Nothing = throw PlayPublishingException(PlayErrorKind.UNCERTAIN_RESULT, "A previous write has an uncertain result. Inspect Play Console and discard/recreate the edit rather than committing unknown changes.")
    private fun confirmationFailure(message: String): Nothing = throw PlayPublishingException(PlayErrorKind.CONFIRMATION, message)

    companion object { private val EMPTY_BODY = ByteArray(0).toRequestBody(null) }
}

private class FileSliceBody(private val file: File, private val offset: Long, private val length: Int) : RequestBody() {
    override fun contentType() = binaryMediaType
    override fun contentLength() = length.toLong()
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) {
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            var remaining = length
            val buffer = ByteArray(minOf(length, 64 * 1024))
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read <= 0) throw IOException("Artifact changed during upload")
                sink.write(buffer, 0, read); remaining -= read
            }
        }
    }
}

private fun parseObject(text: String): JsonObject = if (text.isBlank()) buildJsonObject {} else {
    runCatching { playJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: protocol("Google returned malformed JSON; response details were withheld to protect credentials.")
}
private fun JsonObject.string(name: String): String = optionalString(name) ?: protocol("Google omitted a required response field.")
private fun JsonObject.optionalString(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(name: String): Long = (this[name] as? JsonPrimitive)?.longOrNull ?: protocol("Google returned a malformed numeric response field.")
private fun JsonObject.array(name: String): JsonArray = when (val value = this[name]) { null -> JsonArray(emptyList()); is JsonArray -> value; else -> protocol("Google returned a malformed array field.") }
private fun JsonObject.obj(name: String): JsonObject = this[name] as? JsonObject ?: protocol("Google returned a malformed object field.")
private fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: protocol("Google returned a malformed object; response details were withheld to protect credentials.")
