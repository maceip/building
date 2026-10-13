package dev.ide.workbench.publish

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** OAuth authorization is separate from Google sign-in. Implementations must never log their result. */
fun interface PlayAccessTokenProvider {
    suspend fun accessToken(): String
}

/** Deliberately memory-only: no service-account key, refresh token, preferences, or serialization. */
class SessionAccessTokenProvider(initialToken: String? = null) : PlayAccessTokenProvider {
    @Volatile private var token: String? = null
    init { initialToken?.let(::setToken) }
    val hasToken: Boolean get() = token != null
    fun setToken(value: String) {
        val candidate = value.trim()
        require(candidate.length in 1..16384 && candidate.all { it.isLetterOrDigit() && it.code < 128 || it in "-._~+/=" }) {
            "Enter an OAuth access token, without whitespace or a Bearer prefix."
        }
        token = candidate
    }
    fun clear() { token = null }
    override suspend fun accessToken(): String = token
        ?: throw PlayPublishingException(PlayErrorKind.AUTHORIZATION, "Authorize your Google Play account or supply a new session access token.")
    override fun toString(): String = "SessionAccessTokenProvider(redacted)"
}

enum class PlayErrorKind { AUTHORIZATION, PERMISSION, NOT_FOUND, VALIDATION, CONFLICT, RATE_LIMIT, NETWORK, PROTOCOL, CONFIRMATION, UNCERTAIN_RESULT }

/** Intentionally carries neither response bodies, request URLs, tokens, nor unsafe exception causes. */
class PlayPublishingException(
    val kind: PlayErrorKind,
    override val message: String,
    val httpStatus: Int? = null,
    val reason: String? = null,
) : Exception(message)

data class PlayEdit(val packageName: String, val id: String, val expiryTimeSeconds: Long)
data class PlayBundle(val versionCode: Long, val sha256: String, val sha1: String = "")
data class PlayImage(val id: String, val sha256: String, val sha1: String = "")
data class UploadProgress(val bytesAcknowledged: Long, val totalBytes: Long)

data class PlayListing(
    val language: String,
    val title: String,
    val shortDescription: String,
    val fullDescription: String,
    val video: String? = null,
)

data class PlayAppDetails(
    val defaultLanguage: String,
    val contactEmail: String,
    val contactWebsite: String? = null,
    val contactPhone: String? = null,
)

enum class PlayImageType(val wireValue: String) {
    ICON("icon"), FEATURE_GRAPHIC("featureGraphic"), PHONE_SCREENSHOTS("phoneScreenshots"),
    SEVEN_INCH_SCREENSHOTS("sevenInchScreenshots"), TEN_INCH_SCREENSHOTS("tenInchScreenshots"),
    TV_SCREENSHOTS("tvScreenshots"), WEAR_SCREENSHOTS("wearScreenshots"), TV_BANNER("tvBanner"),
}

enum class PlayReleaseStatus(val wireValue: String) {
    DRAFT("draft"), IN_PROGRESS("inProgress"), HALTED("halted"), COMPLETED("completed");
    companion object { fun fromWire(value: String): PlayReleaseStatus? = entries.firstOrNull { it.wireValue == value } }
}

data class PlayReleaseNote(val language: String, val text: String)
data class PlayCountryTargeting(
    val countries: List<String>,
    val includeRestOfWorld: Boolean = false,
    val additionalFields: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
)
data class PlayRelease(
    val versionCodes: List<Long>,
    val status: PlayReleaseStatus,
    val name: String? = null,
    val releaseNotes: List<PlayReleaseNote> = emptyList(),
    val userFraction: Double? = null,
    val inAppUpdatePriority: Int? = null,
    val countryTargeting: PlayCountryTargeting? = null,
    /** Preserve server fields not yet modeled when retaining an existing release in a full update. */
    val additionalFields: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
)
data class PlayTrack(
    val track: String,
    val releases: List<PlayRelease>,
    val additionalFields: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
)

data class PlayReleaseSummary(
    val releaseName: String,
    val track: String,
    val versionCodes: List<Long>,
    /** Keep unknown future values instead of treating them as published. */
    val releaseLifecycleState: String,
) {
    val isPublished: Boolean get() = releaseLifecycleState == "RELEASE_LIFECYCLE_STATE_PUBLISHED"
}

data class InternalSharingArtifact(val downloadUrl: String, val certificateFingerprint: String, val sha256: String)

/** Derived from the actual signed AAB, not from user-entered or Gradle source metadata. */
data class AabArtifact internal constructor(
    val file: File,
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val sha256: String,
    val sizeBytes: Long,
    val uploadCertificateSha256: String,
    val permissions: Set<String>,
)

data class ImageAsset internal constructor(
    val file: File,
    val type: PlayImageType,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** This declaration is a separate immediate write; it is never part of an edit rollback. */
class DataSafetyDraft(val packageName: String, val csv: String) {
    override fun toString(): String = "DataSafetyDraft(packageName=$packageName, csv=redacted)"
}

/** A preview is not consent. Call confirmAfterUserReview ONLY from the explicit confirmation action. */
class CommitPreview internal constructor(
    val edit: PlayEdit,
    val changes: List<String>,
    val warnings: List<String>,
    private val clientId: String,
    private val revision: Long,
) {
    fun confirmAfterUserReview(): CommitConfirmation = CommitConfirmation(clientId, edit, revision)
}
class CommitConfirmation internal constructor(internal val clientId: String, internal val edit: PlayEdit, internal val revision: Long) {
    internal val used = AtomicBoolean(false)
    override fun toString(): String = "CommitConfirmation(packageName=${edit.packageName}, redacted)"
}

class InternalSharingPreview internal constructor(
    val packageName: String,
    val versionCode: Long,
    val warnings: List<String>,
    private val clientId: String,
    private val sha256: String,
) {
    fun confirmAfterUserReview(): InternalSharingConfirmation = InternalSharingConfirmation(clientId, packageName, sha256)
}
class InternalSharingConfirmation internal constructor(internal val clientId: String, internal val packageName: String, internal val sha256: String) {
    internal val used = AtomicBoolean(false)
    override fun toString(): String = "InternalSharingConfirmation(redacted)"
}

class DataSafetyPreview internal constructor(
    val packageName: String,
    val warnings: List<String>,
    private val clientId: String,
    private val digest: String,
) {
    fun confirmAfterUserReview(): DataSafetyConfirmation = DataSafetyConfirmation(clientId, packageName, digest)
}
class DataSafetyConfirmation internal constructor(internal val clientId: String, internal val packageName: String, internal val digest: String) {
    internal val used = AtomicBoolean(false)
    override fun toString(): String = "DataSafetyConfirmation(redacted)"
}
