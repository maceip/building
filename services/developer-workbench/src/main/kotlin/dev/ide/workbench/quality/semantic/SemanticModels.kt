package dev.ide.workbench.quality.semantic

import dev.ide.workbench.quality.ArchiveWindow
import dev.ide.workbench.quality.PrimaryTheme
import dev.ide.workbench.quality.QualityRecord
import dev.ide.workbench.quality.QualityReport
import dev.ide.workbench.quality.RecordCounts
import dev.ide.workbench.quality.RecordIntent
import dev.ide.workbench.quality.RecordKind
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Trusted host metadata, never supplied by imported prose. Unknown/custom destinations are CLOUD. */
enum class SemanticLocality { LOCAL, CLOUD }

data class SemanticProvider(
    val id: String,
    val displayName: String,
    val model: String,
    /** Public destination only; credentials, URL user-info, query strings and fragments must not appear. */
    val destination: String,
    val locality: SemanticLocality = SemanticLocality.CLOUD,
    /** Host-enforced total wire sends, including provider retries/fallbacks; null means not established. */
    val maxWireRequestsPerRun: Int? = null,
) {
    init {
        require(maxWireRequestsPerRun == null || maxWireRequestsPerRun in 1..2)

        require(listOf(id, displayName, model, destination).all { value ->
            value.isNotBlank() && value.length <= 256 && value.none { it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }
        }) { "Provider metadata must be bounded public text." }
        require(!destination.contains('@') && !destination.contains('?') && !destination.contains('#')) {
            "Provider destination must not contain credentials, query strings or fragments."
        }
    }
}

/** Classification only. Implementations must never install tools, browse URLs, save transcripts or write files. */
interface SemanticLabeler {
    val provider: SemanticProvider
    suspend fun label(request: SemanticLabelRequest): String
}

/** Instructions and hostile record data are distinct fields and MUST map to distinct LLM roles. */
data class SemanticLabelRequest internal constructor(
    val instructions: String,
    val untrustedRecordsJson: String,
    val responseSchemaJson: String,
    val maxResponseChars: Int,
    val maxOutputTokens: Int,
)

data class SemanticLimits(
    val maxInputRecords: Int = 10_000,
    val maxRecordsPerRun: Int = 24,
    val maxTitleChars: Int = 600,
    val maxBodyChars: Int = 6_000,
    val maxSourceCharsPerRecord: Int = 30_000,
    val maxRequestBytes: Int = 96 * 1024,
    val maxResponseChars: Int = 96 * 1024,
    val maxOutputTokens: Int = 8_192,
    val attemptTimeoutMillis: Long = 45_000,
    val maxAttempts: Int = 2,
    val retryDelayMillis: Long = 250,
) {
    init {
        require(maxInputRecords in 1..10_000 && maxRecordsPerRun in 1..100)
        require(maxTitleChars in 1..600 && maxBodyChars in 1..24_000)
        require(maxSourceCharsPerRecord in 1..1_000_000)
        require(maxRequestBytes in 1024..1_048_576 && maxResponseChars in 1024..1_048_576)
        require(maxOutputTokens in 256..16_384 && attemptTimeoutMillis in 1..120_000)
        require(maxAttempts in 1..2 && retryDelayMillis in 0..2_000)
    }
}

data class SemanticSelection(
    /** Empty means all records already admitted by the offline import, not the original archive. */
    val recordIds: Set<String> = emptySet(),
    val kinds: Set<RecordKind> = setOf(RecordKind.ISSUE),
    /** Applies to createdAt, inclusive. A record with an unknown date cannot satisfy a date window. */
    val createdWindow: ArchiveWindow? = null,
)

enum class SemanticProblemFamily(val key: String, val theme: PrimaryTheme?) {
    PLATFORM_SUPPORT("platform_support", PrimaryTheme.PLATFORM_COMPATIBILITY),
    BACKGROUND_LIFECYCLE("background_lifecycle", PrimaryTheme.RUNTIME_LIFECYCLE),
    PROCESS_FAILURE("process_failure", PrimaryTheme.RUNTIME_LIFECYCLE),
    NETWORK_API("network_api", PrimaryTheme.EXTERNAL_SERVICES),
    AUTHENTICATION_SERVICE("authentication_service", PrimaryTheme.EXTERNAL_SERVICES),
    DATA_PERSISTENCE("data_persistence", PrimaryTheme.DATA_STATE),
    BACKUP_RESTORE("backup_restore", PrimaryTheme.DATA_STATE),
    SYNC_CONSISTENCY("sync_consistency", PrimaryTheme.DATA_STATE),
    LAYOUT_RENDERING("layout_rendering", PrimaryTheme.UI_UX),
    INTERACTION_ACCESSIBILITY("interaction_accessibility", PrimaryTheme.UI_UX),
    BUILD_DEPENDENCY("build_dependency", PrimaryTheme.DELIVERY_BUILD),
    INSTALL_UPDATE("install_update", PrimaryTheme.DELIVERY_BUILD),
    RESOURCE_USE("resource_use", PrimaryTheme.PERFORMANCE),
    PRIVACY_EXPOSURE("privacy_exposure", PrimaryTheme.SECURITY_PRIVACY),
    ACCESS_CONTROL("access_control", PrimaryTheme.SECURITY_PRIVACY),
    PRODUCT_BEHAVIOR("product_behavior", PrimaryTheme.PRODUCT_CAPABILITY),
    FEATURE_GAP("feature_gap", PrimaryTheme.PRODUCT_CAPABILITY),
    UNKNOWN("unknown", null),
}

/** CLASSIFIED is an unverified model proposal, not human approval or a verified cause. */
enum class SemanticReview { CLASSIFIED, NEEDS_REVIEW, UNKNOWN }
enum class SemanticCitationField { TITLE, BODY }

data class SemanticCitation(
    val field: SemanticCitationField,
    /** Zero-based UTF-16 code-unit offsets into the exact redacted, bounded field in the preview. */
    val start: Int,
    val endExclusive: Int,
    val evidence: String,
)

data class SemanticLabel(
    val canonicalId: String,
    val primaryTheme: PrimaryTheme,
    val intent: RecordIntent,
    val problemFamily: SemanticProblemFamily,
    val citations: List<SemanticCitation>,
    /** A model-reported score, NOT a calibrated probability of correctness. */
    val confidence: Double,
    val review: SemanticReview,
) {
    val isClassified: Boolean get() = review == SemanticReview.CLASSIFIED && primaryTheme != PrimaryTheme.UNCLASSIFIED
}

/** Exact sources used for citation offsets. Original source metadata stays local and unchanged. */
data class SemanticPreparedRecord internal constructor(
    val source: QualityRecord,
    val redactedTitle: String,
    val redactedBody: String,
    val truncated: Boolean,
)

data class SemanticManifest internal constructor(
    val runId: String,
    val provider: SemanticProvider,
    val payloadSha256: String,
    val counts: RecordCounts,
    val fields: List<String>,
    val requestBytes: Int,
    val excludedByFilter: Int,
    val withheldSensitive: Int,
    val truncatedRecords: Int,
    val maxAttempts: Int,
    val attemptTimeoutMillis: Long,
    val createdAt: Instant,
)

/** Opaque preparation: callers can preview it but cannot swap payloads underneath consent. */
class SemanticPreparedRun internal constructor(
    val manifest: SemanticManifest,
    val records: List<SemanticPreparedRecord>,
    val request: SemanticLabelRequest,
    internal val sourceReport: QualityReport,
    internal val owner: Any,
) {
    internal val started = AtomicBoolean(false)
}

/** Not persistable or constructible by the UI. Issued only by an explicit confirmation event. */
class SemanticRunConsent internal constructor(internal val prepared: SemanticPreparedRun) {
    internal val consumed = AtomicBoolean(false)
}

data class SemanticGroup(
    val theme: PrimaryTheme,
    val problemFamily: SemanticProblemFamily,
    val intent: RecordIntent,
    val recordIds: List<String>,
    val counts: RecordCounts,
)

enum class SemanticRunStatus { COMPLETED, ABSTAINED }
enum class SemanticFailure { INVALID_INPUT, LIMIT_EXCEEDED, CONSENT_REQUIRED, PROVIDER_CHANGED, UNAVAILABLE, TIMEOUT, INVALID_OUTPUT }

/** Safe, fixed error text only; provider exceptions and imported/model prose never become UI errors. */
class SemanticException(val reason: SemanticFailure) : IllegalArgumentException(when (reason) {
    SemanticFailure.INVALID_INPUT -> "Select an archive and valid canonical record IDs."
    SemanticFailure.LIMIT_EXCEEDED -> "Semantic input or output exceeds the configured limit; reduce the selection."
    SemanticFailure.CONSENT_REQUIRED -> "Review the current preview and confirm this run once."
    SemanticFailure.PROVIDER_CHANGED -> "The selected provider changed; prepare and confirm a new preview."
    SemanticFailure.UNAVAILABLE -> "The configured model is unavailable; offline grouping is unchanged."
    SemanticFailure.TIMEOUT -> "The semantic request timed out; offline grouping is unchanged."
    SemanticFailure.INVALID_OUTPUT -> "The model response failed schema or citation validation; all selected records need review."
})

/** A trusted adapter may mark a transient failure retryable; malformed JSON/tool output is never retried. */
class SemanticProviderFailure(val retryable: Boolean = false) : Exception("Semantic provider failed.")

data class SemanticGroupingResult(
    val manifest: SemanticManifest,
    /** The offline report is never relabeled or replaced by the model. */
    val sourceReport: QualityReport,
    val labels: List<SemanticLabel>,
    val groups: List<SemanticGroup>,
    val status: SemanticRunStatus,
    val failure: SemanticFailure? = null,
    val attempts: Int,
) {
    val reviewCount: Int get() = labels.count { !it.isClassified }
}

internal fun <T> immutableList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
