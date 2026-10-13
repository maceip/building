package dev.ide.android.workbench.publish

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.RunTaskOption
import dev.ide.workbench.publish.AabArtifact
import dev.ide.workbench.publish.CommitPreview
import dev.ide.workbench.publish.DataSafetyDraft
import dev.ide.workbench.publish.DataSafetyPreview
import dev.ide.workbench.publish.ImageAsset
import dev.ide.workbench.publish.InternalSharingArtifact
import dev.ide.workbench.publish.InternalSharingPreview
import dev.ide.workbench.publish.PlayAccessTokenProvider
import dev.ide.workbench.publish.PlayAccountAttestations
import dev.ide.workbench.publish.PlayDistributionGoal
import dev.ide.workbench.publish.PlayEdit
import dev.ide.workbench.publish.PlayErrorKind
import dev.ide.workbench.publish.PlayImageType
import dev.ide.workbench.publish.PlayListing
import dev.ide.workbench.publish.PlayPreflight
import dev.ide.workbench.publish.PlayPublisherClient
import dev.ide.workbench.publish.PlayPublishingException
import dev.ide.workbench.publish.PlayRelease
import dev.ide.workbench.publish.PlayReleaseNote
import dev.ide.workbench.publish.PlayReleaseStatus
import dev.ide.workbench.publish.PlayReleaseSummary
import dev.ide.workbench.publish.PlayTrack
import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class PublishDestination(val label: String) {
    DRAFT("Draft"), INTERNAL("Internal testing"), CLOSED("Closed testing"), PRODUCTION("Production")
}

internal data class PublishForm(
    val packageName: String = "",
    val language: String = "en-US",
    val title: String = "",
    val shortDescription: String = "",
    val fullDescription: String = "",
    val privacyPolicyUrl: String = "",
    val destination: PublishDestination = PublishDestination.DRAFT,
    val trackId: String = "internal",
    val status: PlayReleaseStatus = PlayReleaseStatus.DRAFT,
    val rolloutPercent: String = "10",
    val releaseName: String = "",
    val releaseNotes: String = "",
    val retainExistingReleases: Boolean = true,
    val replaceSelectedImageTypes: Boolean = false,
    val attestations: PlayAccountAttestations = PlayAccountAttestations(),
) {
    val goal: PlayDistributionGoal get() = when {
        destination == PublishDestination.PRODUCTION || trackId.substringAfterLast(':') == "production" -> PlayDistributionGoal.PRODUCTION
        trackId.substringAfterLast(':') == "beta" -> PlayDistributionGoal.OPEN_TESTING
        destination == PublishDestination.CLOSED || trackId.substringAfterLast(':') != "internal" -> PlayDistributionGoal.CLOSED_TESTING
        else -> PlayDistributionGoal.INTERNAL_TESTING
    }
    fun listing(): PlayListing = PlayListing(language.trim(), title.trim(), shortDescription.trim(), fullDescription.trim())
    fun release(version: Long): PlayRelease = PlayRelease(
        versionCodes = listOf(version),
        status = if (destination == PublishDestination.DRAFT) PlayReleaseStatus.DRAFT else status,
        name = releaseName.trim().takeIf { it.isNotBlank() },
        releaseNotes = releaseNotes.trim().takeIf { it.isNotBlank() }?.let { listOf(PlayReleaseNote(language.trim(), it)) }.orEmpty(),
        userFraction = if (destination != PublishDestination.DRAFT && status == PlayReleaseStatus.IN_PROGRESS) {
            rolloutPercent.toDoubleOrNull()?.div(100.0) ?: publishInvalid("Enter a rollout percentage greater than 0 and less than 100.")
        } else null,
    )
}

/** Immutable, nonsecret snapshot displayed before the first upload and again before commit. */
internal data class PublishStageReview(
    val accountLabel: String,
    val identityKey: String,
    val edit: PlayEdit,
    val artifact: AabArtifact,
    val listing: PlayListing,
    val images: List<ImageAsset>,
    val previousTrack: PlayTrack,
    val replacementTrack: PlayTrack,
    val replaceImageTypes: Boolean,
    val privacyPolicyUrl: String,
)

internal data class PublishRecoveryEdit(val edit: PlayEdit, val accountLabel: String)
internal data class PublishDataSafetyFile(val fileName: String, val draft: DataSafetyDraft, val sha256: String)

/** All state is panel-local memory. A process/lifecycle destroy clears credentials; it cannot undo Google writes. */
internal class PlayPublishState(
    private val auth: PlayAuthorizationState,
    private val scope: CoroutineScope,
    private val clientFactory: (PlayAccessTokenProvider) -> PlayPublisherClient = { PlayPublisherClient(it) },
) : AutoCloseable {
    var form by mutableStateOf(PublishForm())
    var artifact by mutableStateOf<AabArtifact?>(null)
        private set
    var images by mutableStateOf<List<ImageAsset>>(emptyList())
        private set
    var bundleCandidates by mutableStateOf<List<PublishBundleCandidate>>(emptyList())
        private set
    var edit by mutableStateOf<PlayEdit?>(null)
        private set
    var tracks by mutableStateOf<List<PlayTrack>>(emptyList())
        private set
    var stagedReview by mutableStateOf<PublishStageReview?>(null)
        private set
    var stagedCompletely by mutableStateOf(false)
        private set
    var commitPreview by mutableStateOf<CommitPreview?>(null)
        private set
    var resultUncertain by mutableStateOf(false)
        private set
    var recoveryEdits by mutableStateOf<List<PublishRecoveryEdit>>(emptyList())
        private set
    var committedEdit by mutableStateOf<PlayEdit?>(null)
        private set
    var releases by mutableStateOf<List<PlayReleaseSummary>?>(null)
        private set
    var internalSharing by mutableStateOf<InternalSharingArtifact?>(null)
        private set
    var dataSafetyFile by mutableStateOf<PublishDataSafetyFile?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var canCancel by mutableStateOf(false)
        private set
    var feedback by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    internal var operationJob: Job? = null
        private set
    private var client: PlayPublisherClient? = null
    private var clientIdentity = ""
    private var clientAccount = ""
    private var closed = false
    private var mutationInFlight = false
    val locked: Boolean get() = busy != null || stagedReview != null

    fun clearFeedback() { error = null; feedback = null }
    fun removeImage(asset: ImageAsset) { if (!locked) images = images - asset }
    fun clearArtifact() { if (!locked && edit == null) artifact = null }

    /** Native renewal of the exact same selected account is safe; arbitrary token replacement is not. */
    fun synchronizeIdentity() {
        if (!auth.connected || clientIdentity == auth.identityKey) return
        check(busy == null) { "Cannot change publishing account while an operation is running." }
        edit?.let { recoveryEdits = recoveryEdits + PublishRecoveryEdit(it, clientAccount) }
        client?.close()
        client = null
        clientIdentity = auth.identityKey
        clientAccount = auth.accountLabel
        clearEditState()
        releases = null
        internalSharing = null
        if (recoveryEdits.isNotEmpty()) feedback = "The authorization identity changed. Previous edit confirmations were invalidated. Old edit IDs remain below for explicit inspection/discard; no edit was committed or deleted automatically."
    }

    private fun api(): PlayPublisherClient {
        if (!auth.connected || auth.authorizing || !auth.tokens.hasToken) publishInvalid("Finish authorizing a Google Play account or provide a fresh session access token first.")
        if (clientIdentity != auth.identityKey) {
            // Account controls are disabled during operations; also enforce at the service boundary.
            edit?.let { recoveryEdits = recoveryEdits + PublishRecoveryEdit(it, clientAccount) }
            client?.close()
            client = null
            clientIdentity = auth.identityKey
            clientAccount = auth.accountLabel
            clearEditState()
        }
        return client ?: clientFactory(auth.tokens).also { client = it }
    }

    fun inspectBundle(path: String) = perform("Verifying signed release bundle", cancelAllowed = true) {
        val expected = form.packageName.trim().takeIf { it.isNotBlank() }
        val verified = withContext(Dispatchers.IO) { PlayPreflight.inspectAab(File(path), expected) }
        artifact = verified
        form = form.copy(packageName = verified.packageName)
        feedback = "Verified the AAB signature, manifest, package, version and SHA-256 locally. Upload-key registration and Play policy eligibility still need human review."
    }

    fun inspectImage(path: String, type: PlayImageType) = perform("Verifying store image", cancelAllowed = true) {
        val verified = withContext(Dispatchers.IO) { PlayPreflight.inspectImage(File(path), type) }
        if (images.any { it.sha256 == verified.sha256 && it.type == verified.type }) publishInvalid("That image is already selected for this asset type.")
        val max = if (type in setOf(PlayImageType.ICON, PlayImageType.FEATURE_GRAPHIC, PlayImageType.TV_BANNER)) 1 else 8
        if (images.count { it.type == type } >= max) publishInvalid("Remove an existing selected image before adding more of this type (limit $max).")
        images = images + verified
        feedback = "Image format, dimensions and SHA-256 verified. Supply genuine screenshots of your app; nothing is generated or uploaded automatically."
    }

    fun buildBundle(backend: IdeBackend, task: RunTaskOption) = perform("Building ${task.label}; waiting for completion", cancelAllowed = true) {
        bundleCandidates = emptyList()
        val root = backend.project.rootPath
        if (root.isBlank()) publishInvalid("Open an Android project before building a bundle. You can still select an existing AAB.")
        val finished = runPublishBundleTask(backend.build, task)
        if (backend.project.rootPath != root) publishInvalid("The active project changed during the build. Select the intended artifact explicitly.")
        bundleCandidates = findCompletedBundleArtifacts(root, finished)
        if (bundleCandidates.isEmpty()) publishInvalid("The build succeeded but no safe .aab output was found. Inspect the Build console or select the release AAB using the file picker.")
        feedback = "Bundle build completed successfully. Choose an output below and verify it; cached outputs may have older timestamps. No bundle is selected or uploaded automatically."
    }

    /** Called only by the create-edit confirmation button. Creating an edit is itself a Google write. */
    fun createEditAfterConfirmation(packageName: String, expectedIdentity: String) = perform("Creating edit and reading actual tracks", mutation = true) {
        verifyIdentity(expectedIdentity)
        PlayPreflight.validatePackageName(packageName)
        if (edit != null) publishInvalid("Discard the current edit before starting another.")
        val active = api()
        edit = active.createEdit(packageName)
        tracks = active.listTracks(requireNotNull(edit))
        feedback = "Edit created; no bundle, listing, image or track has been changed yet. Select a real track, then review the exact changes before staging."
    }

    fun refreshEdit() = perform("Reading edit and tracks", cancelAllowed = true) {
        val active = api()
        val current = edit ?: publishInvalid("Create an edit first.")
        edit = active.getEdit(current)
        tracks = active.listTracks(current)
        val bundles = active.listBundles(current)
        feedback = "Edit exists. Google reports ${bundles.size} bundle(s) and ${tracks.size} track(s). Reading does not clear an uncertain-write restriction."
    }

    fun makeStageReview(): PublishStageReview {
        if (busy != null || stagedReview != null) publishInvalid("Finish or discard the current staged edit before preparing new changes.")
        if (!auth.connected || auth.authorizing) publishInvalid("Finish authorizing the publishing account first.")
        synchronizeIdentity()
        val current = edit ?: publishInvalid("Create an edit and load actual tracks before staging.")
        val verified = artifact ?: publishInvalid("Select and verify a release AAB first.")
        if (current.packageName != form.packageName.trim() || verified.packageName != current.packageName) publishInvalid("The app, edit and verified bundle package names must match.")
        validatePublishAttestations(form)
        val listing = form.listing()
        PlayPreflight.validateListing(listing)
        if (form.releaseName.codePointCount(0, form.releaseName.length) > 50) publishInvalid("Use a release name of at most 50 characters.")
        val oldTrack = tracks.firstOrNull { it.track == form.trackId } ?: publishInvalid("Choose a track ID returned by Google. Create missing closed tracks in Play Console, then refresh.")
        if (oldTrack.releases.any { verified.versionCode in it.versionCodes }) publishInvalid("This version code is already on the selected track. Build a new version; this screen does not blindly re-upload existing versions.")
        val release = form.release(verified.versionCode)
        if (form.trackId.substringAfterLast(':') == "internal" && release.status !in setOf(PlayReleaseStatus.DRAFT, PlayReleaseStatus.COMPLETED)) publishInvalid("Use draft or completed for the internal testing track; staged percentages are not offered there.")
        val target = oldTrack.copy(releases = (if (form.retainExistingReleases) oldTrack.releases else emptyList()) + release)
        PlayPreflight.validateTrack(target)
        return PublishStageReview(auth.accountLabel, auth.identityKey, current, verified, listing, images.toList(), oldTrack, target, form.replaceSelectedImageTypes, form.privacyPolicyUrl.trim())
    }

    /** Called only after the user has reviewed the immutable artifact/listing/track snapshot. */
    fun stageAfterConfirmation(review: PublishStageReview) = perform("Staging confirmed bundle, listing, images and track", mutation = true, cancelAllowed = true) {
        verifyIdentity(review.identityKey)
        val active = api()
        if (edit?.id != review.edit.id || stagedReview != null) publishInvalid("The edit changed. Review the current changes again.")
        // Read before the first content mutation, rather than overwriting a changed track snapshot.
        if (active.getTrack(review.edit, review.previousTrack.track) != review.previousTrack) publishInvalid("The target track changed since review. Refresh it and review the release again.")
        if (active.listBundles(review.edit).any { it.versionCode == review.artifact.versionCode }) publishInvalid("Google already has this bundle version in the edit. Discard/review the edit rather than blindly uploading it again.")
        stagedReview = review
        stagedCompletely = false
        commitPreview = null
        resultUncertain = false
        active.uploadBundle(review.edit, review.artifact)
        active.updateListing(review.edit, review.listing)
        if (review.replaceImageTypes) {
            review.images.map { it.type }.distinct().forEach { active.deleteImages(review.edit, review.listing.language, it) }
        }
        review.images.forEach { active.uploadImage(review.edit, review.listing.language, it) }
        active.updateTrack(review.edit, review.replacementTrack)
        stagedCompletely = true
        feedback = "All confirmed changes are staged in edit ${review.edit.id}. Nothing has been committed. Validate the edit, review the final confirmation, and commit only when ready."
    }

    fun validateEdit() = perform("Validating staged edit with Google", cancelAllowed = true) {
        if (!stagedCompletely || resultUncertain) publishInvalid("Only a completely staged edit with a known result can be validated for commit. Inspect/discard a partial or uncertain edit.")
        val current = edit ?: publishInvalid("There is no edit to validate.")
        val active = api()
        edit = active.validateEdit(current)
        commitPreview = active.previewCommit(current)
        feedback = "Google API validation succeeded. This is not policy approval, review completion, or proof of a live Play listing. Final commit still needs separate confirmation."
    }

    /** No cancellation button and no automatic retry: a committed edit cannot be undone by coroutine cancellation. */
    fun commitAfterConfirmation(preview: CommitPreview, expectedIdentity: String) = perform("Committing the confirmed edit; do not close this screen", mutation = true) {
        verifyIdentity(expectedIdentity)
        if (commitPreview !== preview || resultUncertain) publishInvalid("Validate and review the current edit again.")
        val active = api()
        // Consume confirmation only inside the explicit final button's action.
        commitPreview = null
        val committed = active.commitEdit(preview.edit, preview.confirmAfterUserReview())
        committedEdit = committed
        clearEditState()
        feedback = "Google accepted the commit for edit ${committed.id}. This is NOT proof the app is live. Check release lifecycle below and Play Console review / managed-publishing status. Do not resubmit this release automatically."
    }

    fun discardAfterConfirmation(target: PlayEdit, expectedIdentity: String) = perform("Deleting the explicitly selected edit", mutation = true) {
        verifyIdentity(expectedIdentity)
        api().deleteEdit(target)
        if (edit?.id == target.id && edit?.packageName == target.packageName) clearEditState()
        recoveryEdits = recoveryEdits.filterNot { it.edit.id == target.id && it.edit.packageName == target.packageName }
        feedback = "Google confirmed deletion of edit ${target.id}. This does not undo an already committed release, an internal-sharing upload, or a Data safety submission."
    }

    fun monitorReleases() = perform("Reading release lifecycle", cancelAllowed = true) {
        releases = api().listReleases(form.packageName.trim(), form.trackId)
        feedback = "Release lifecycle read from Google. A completed track status or accepted commit alone does not mean live distribution to all users."
    }

    fun sharingPreview(): InternalSharingPreview = api().previewInternalSharing(artifact ?: publishInvalid("Select and verify a release AAB first."))
    fun shareAfterConfirmation(verified: AabArtifact, preview: InternalSharingPreview, expectedIdentity: String) = perform("Uploading to internal app sharing", mutation = true, cancelAllowed = true) {
        verifyIdentity(expectedIdentity)
        internalSharing = api().uploadInternalSharing(verified, preview.confirmAfterUserReview())
        feedback = "Google returned an internal-sharing install link. This is a separate upload, not a store release; deleting an edit cannot undo it."
    }

    fun readDataSafety(path: String) = perform("Reading selected Data safety CSV", cancelAllowed = true) {
        val selected = withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.isFile || file.length() !in 1..4_194_304L || !file.name.endsWith(".csv", true)) publishInvalid("Choose the completed Play Console Data safety CSV, up to 4 MiB.")
            val bytes = file.inputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > 4_194_304) publishInvalid("The selected CSV exceeds 4 MiB.")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            val csv = bytes.toString(Charsets.UTF_8)
            PublishDataSafetyFile(file.name, DataSafetyDraft(form.packageName.trim(), csv), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
        }
        // Local preview validation; no API submission here.
        api().previewDataSafety(selected.draft)
        dataSafetyFile = selected
        feedback = "CSV loaded locally. Its answers are not inferred or certified by this app. Review it against SDK/backend behavior before the separate submission confirmation."
    }

    fun safetyPreview(file: PublishDataSafetyFile): DataSafetyPreview = api().previewDataSafety(file.draft)
    fun submitSafetyAfterConfirmation(file: PublishDataSafetyFile, preview: DataSafetyPreview, expectedIdentity: String) = perform("Submitting separately confirmed Data safety declaration", mutation = true) {
        verifyIdentity(expectedIdentity)
        if (dataSafetyFile !== file || file.draft.packageName != form.packageName.trim()) publishInvalid("Reselect the Data safety CSV for the current package.")
        api().submitDataSafety(file.draft, preview.confirmAfterUserReview())
        feedback = "Google accepted the Data safety submission. This independent write is not rolled back by edit deletion and does not complete other Play policy declarations."
    }

    fun cancel() {
        if (canCancel) operationJob?.cancel()
    }

    fun reportLocalError(failure: Exception) {
        error = safePublishError(failure)
    }

    private fun verifyIdentity(expected: String) {
        if (!auth.connected || auth.authorizing || auth.identityKey != expected) publishInvalid("The authorization identity changed or expired. Finish authorizing and review the action again.")
    }

    private fun perform(label: String, mutation: Boolean = false, cancelAllowed: Boolean = false, block: suspend () -> Unit) {
        if (closed || busy != null) return
        busy = label
        canCancel = cancelAllowed
        mutationInFlight = mutation
        error = null
        feedback = null
        operationJob = scope.launch {
            try {
                block()
            } catch (_: TimeoutCancellationException) {
                error = "The operation did not complete within its waiting window. Inspect the Build console or remote edit before trying again. Nothing was retried automatically."
                if (mutation) resultUncertain = true
            } catch (cancelled: CancellationException) {
                if (mutation) {
                    resultUncertain = true
                    commitPreview = null
                    feedback = "The local request was cancelled. Google may already have received some or all writes; cancellation is not rollback. Inspect or explicitly discard the edit; do not blindly retry."
                } else feedback = "Stopped waiting. A build may continue in the Build console. No Google write was initiated by this read/local check."
                throw cancelled
            } catch (failure: Exception) {
                if (failure is PlayPublishingException && failure.kind == PlayErrorKind.AUTHORIZATION) {
                    auth.disconnect("Google rejected or expired this token. Authorize again. The nonsecret form and current edit are retained; no write is retried automatically.")
                }
                if (mutation && (failure !is PlayPublishingException || failure.kind in setOf(PlayErrorKind.NETWORK, PlayErrorKind.PROTOCOL, PlayErrorKind.UNCERTAIN_RESULT))) {
                    resultUncertain = true
                    commitPreview = null
                }
                error = safePublishError(failure) + if (stagedReview != null && !stagedCompletely) " The edit may be partially staged. Inspect or discard it; uploads are not retried automatically." else ""
            } finally {
                mutationInFlight = false
                busy = null
                canCancel = false
                operationJob = null
            }
        }
    }

    private fun clearEditState() {
        edit = null
        tracks = emptyList()
        stagedReview = null
        stagedCompletely = false
        commitPreview = null
        resultUncertain = false
    }

    override fun close() {
        closed = true
        if (mutationInFlight) resultUncertain = true
        operationJob?.cancel()
        client?.close()
        client = null
        // The auth composable owns permanent close on lifecycle/disposal. A backend replacement also
        // closes this controller, but must leave the still-mounted auth UI able to start a fresh session.
        auth.disconnect()
    }
}

internal fun validatePublishAttestations(form: PublishForm) {
    val answers = form.attestations
    if (!answers.accountRegisteredAndVerified || !answers.appCreatedAndInitializedInConsole || !answers.uploadKeyAndPlayAppSigningConfigured) {
        publishInvalid("Confirm account registration, existing Play app initialization, and upload-key/Play App Signing setup in the manual checklist.")
    }
    val privacy = form.privacyPolicyUrl.trim()
    if (privacy.isNotBlank()) {
        val parsed = runCatching { URI(privacy) }.getOrNull()
        if (parsed?.scheme != "https" || parsed.host.isNullOrBlank() || parsed.userInfo != null) publishInvalid("Use a public HTTPS privacy policy URL without embedded credentials.")
    }
    if (form.goal != PlayDistributionGoal.INTERNAL_TESTING) {
        if (!answers.requiredPolicyDeclarationsReviewed || !answers.storeListingAndDistributionConfigured || !answers.dataSafetyReviewed || privacy.isBlank()) {
            publishInvalid("For closed/production distribution, review every applicable Console declaration, listing/distribution setting and Data safety answer, and record the privacy policy URL. These are user attestations, not API-certified facts.")
        }
    }
    if (form.goal in setOf(PlayDistributionGoal.PRODUCTION, PlayDistributionGoal.OPEN_TESTING) && answers.hasNewPersonalAccountTestingRequirement != false && !answers.productionAccessApproved) {
        publishInvalid("Confirm Google has approved production access (if this personal-account requirement applies). Tester numbers or elapsed time alone do not unlock production.")
    }
}

internal fun safePublishError(failure: Exception): String = if (failure is PlayPublishingException) failure.message
else "The operation could not complete. Check the selected files, connectivity and Play Console. Technical details were withheld to protect credentials; nothing is retried automatically."

private fun publishInvalid(message: String): Nothing = throw PlayPublishingException(PlayErrorKind.VALIDATION, message)
