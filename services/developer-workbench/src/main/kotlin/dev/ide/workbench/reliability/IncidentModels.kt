package dev.ide.workbench.reliability

import kotlinx.coroutines.flow.StateFlow

/** Local SDK protocol only. This package has no network or analytics dependency. */
object IncidentWire {
    const val VERSION = 1
    const val BINDER_DESCRIPTOR = "dev.ide.reliability.IIncidentSink"
    const val TXN_SUBMIT = 1
    const val SERVICE_CLASS = "dev.ide.android.reliability.ReliabilitySinkService"
    const val MAX_JSON_BYTES = 65_536
    const val MAX_JSON_CHARS = 32_768
    const val MAX_PARCEL_BYTES = MAX_JSON_CHARS * 2 + 1_024
    const val MAX_EVENTS = 16
    const val MAX_TEXT_CHARS = 24_000
}

/** Origin is part of identity, not a user-controlled label in the SDK wire protocol. */
enum class IncidentOrigin { SDK_APPLICATION, HOST_RUNTIME }

/** The original four-argument SDK constructor stays source-compatible. Runtime keys never invent an app/build. */
data class IncidentKey(
    val packageName: String,
    val buildId: String,
    val sessionId: String,
    val eventId: String,
    val origin: IncidentOrigin = IncidentOrigin.SDK_APPLICATION,
    val workspaceId: String? = null,
)
enum class IncidentState { OPEN, RESOLVED }

/** SDK evidence is untrusted, already redacted text; the host redacts it again before persistence. */
data class IncomingIncident(
    val key: IncidentKey,
    val sourceRevision: String,
    val timestamp: Long,
    val type: String,
    val redactedText: String,
)

data class IncidentSummary(
    val key: IncidentKey,
    val sourceRevision: String,
    val timestamp: Long,
    val receivedAt: Long,
    val type: String,
    val preview: String,
    val state: IncidentState,
    val hostRedacted: Boolean,
    /** Host-only typed context. The nullable exact pre-run hash is authoritative here, not an attestation. */
    val runtimeContext: HostRuntimeContext? = null,
)

data class Incident(val summary: IncidentSummary, val redactedText: String)

data class IncidentFilter(val packageName: String? = null, val state: IncidentState? = null, val limit: Int = 200) {
    init { require(limit in 1..2_048) }
}

/** Shared directly by the inbox and future local AI tools. Calls that mutate/read disk belong on IO. */
interface IncidentRepository {
    fun list(filter: IncidentFilter = IncidentFilter()): List<IncidentSummary>
    fun read(key: IncidentKey): Incident?
    fun observe(): StateFlow<List<IncidentSummary>>
    fun setState(key: IncidentKey, state: IncidentState)
}

data class IncidentLimits(
    val maxEventsPerApp: Int = 256,
    val maxEventsTotal: Int = 1_024,
    val maxTextBytesPerApp: Int = 2 * 1_024 * 1_024,
    val maxTextBytesTotal: Int = 8 * 1_024 * 1_024,
) {
    init {
        require(maxEventsPerApp in 1..2_048 && maxEventsTotal in maxEventsPerApp..2_048)
        require(maxTextBytesPerApp in 1..16_777_216 && maxTextBytesTotal in maxTextBytesPerApp..16_777_216)
    }
}

enum class ReliabilityError { INVALID, UNAUTHORIZED, ID_CONFLICT, QUOTA, RETRY_LATER, STORAGE_UNAVAILABLE }

/** Deliberately never embeds untrusted text, tokens, package-manager errors, or filesystem paths. */
class ReliabilityException(val code: ReliabilityError) : IllegalStateException(when (code) {
    ReliabilityError.INVALID -> "Invalid local incident request."
    ReliabilityError.UNAUTHORIZED -> "Local delivery is not paired or has been revoked."
    ReliabilityError.ID_CONFLICT -> "A stable identifier is already in use."
    ReliabilityError.QUOTA -> "The local incident limit has been reached."
    ReliabilityError.RETRY_LATER -> "Local delivery is busy; retry later."
    ReliabilityError.STORAGE_UNAVAILABLE -> "The private incident store is unavailable; existing data was not discarded."
})

/** These values must come from Binder and PackageManager, never from the SDK payload. */
data class IncidentCaller(
    val uid: Int,
    val pid: Int,
    val packages: Set<String>,
    val signerSha256: Set<String>,
    val installerPackage: String?,
    /** Android-verified signing lineage permits a legitimate single-signer key rotation. */
    val signerHistorySha256: Set<String> = signerSha256,
)

internal fun invalid(): Nothing = throw ReliabilityException(ReliabilityError.INVALID)
internal fun unauthorized(): Nothing = throw ReliabilityException(ReliabilityError.UNAUTHORIZED)
internal val incidentIdentifier = Regex("[A-Za-z0-9_.:${'$'}@+\\-]{1,128}")
internal val incidentPackage = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
internal val sha256Pattern = Regex("[a-f0-9]{64}")
internal fun validatePackage(value: String) { if (value.length > 255 || !incidentPackage.matches(value)) invalid() }
internal fun validateId(value: String) { if (!incidentIdentifier.matches(value)) invalid() }
internal fun validateRevision(value: String) { if (value.isNotEmpty()) validateId(value) }
internal fun validateKey(key: IncidentKey) {
    when (key.origin) {
        IncidentOrigin.SDK_APPLICATION -> {
            if (key.workspaceId != null) invalid()
            validatePackage(key.packageName)
            validateId(key.buildId)
            validateId(key.sessionId)
            validateId(key.eventId)
        }
        IncidentOrigin.HOST_RUNTIME -> {
            if (key.packageName.isNotEmpty() || key.buildId.isNotEmpty()) invalid()
            validateRuntimeUuid(key.workspaceId ?: invalid())
            validateRuntimeUuid(key.sessionId)
            validateRuntimeUuid(key.eventId)
        }
    }
}
