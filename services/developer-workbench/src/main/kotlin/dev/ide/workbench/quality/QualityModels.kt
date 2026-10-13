package dev.ide.workbench.quality

import java.nio.file.Path
import java.time.Instant
import java.util.Locale

/** Limits apply before parsing, classification, or rendering. No network or model configuration is needed. */
data class QualityConfig(
    val archive: ArchiveImportConfig = ArchiveImportConfig(),
    val logs: LogAnalysisConfig = LogAnalysisConfig(),
    val scanner: ProjectScanConfig = ProjectScanConfig(),
    val maxExportBytes: Int = 24 * 1024 * 1024,
) {
    init { require(maxExportBytes in 1..(128 * 1024 * 1024)) }
}

data class ArchiveImportConfig(
    val maxCompressedBytes: Int = 8 * 1024 * 1024,
    val maxExpandedBytes: Int = 16 * 1024 * 1024,
    val maxEntries: Int = 32,
    val maxRecords: Int = 10_000,
    val maxTitleChars: Int = 600,
    val maxBodyChars: Int = 24_000,
    val maxTotalTextChars: Int = 6_000_000,
    val maxJsonDepth: Int = 64,
    val maxJsonTokens: Int = 400_000,
    val maxCompressionRatio: Int = 200,
    /** Required only for REST list entries that carry no repository or GitHub URL. */
    val defaultRepository: String? = null,
    /** Overrides the declared archive period. Boundaries are inclusive and use created_at, not updated_at. */
    val recentWindow: ArchiveWindow? = null,
    val recentOnly: Boolean = false,
    /** Detected/configured specialized records are excluded by default; detection is heuristic, not exhaustive. */
    val includeDeviceSecuritySpecific: Boolean = false,
    /** Optional user-reviewed repository scope; use when terse records do not name the specialized domain. */
    val deviceSecurityRepositories: Set<String> = emptySet(),
) {
    init {
        require(maxCompressedBytes in 1..(64 * 1024 * 1024))
        require(maxExpandedBytes in 1..(64 * 1024 * 1024))
        require(maxEntries in 1..1024 && maxRecords in 1..100_000)
        require(maxTitleChars in 1..10_000 && maxBodyChars in 1..1_000_000)
        require(maxTotalTextChars in 1..64_000_000)
        require(maxJsonDepth in 1..128 && maxJsonTokens in 1..2_000_000)
        require(maxCompressionRatio in 1..10_000)
    }
}

data class ArchiveWindow(val start: Instant, val endInclusive: Instant) {
    init { require(!endInclusive.isBefore(start)) { "Window end precedes its start" } }
    fun contains(instant: Instant): Boolean = !instant.isBefore(start) && !instant.isAfter(endInclusive)
}

data class LogAnalysisConfig(
    val maxInputChars: Int = 1_000_000,
    val maxLines: Int = 20_000,
    val maxLineChars: Int = 4_096,
    val maxEvents: Int = 2_000,
    val maxExcerptChars: Int = 1_800,
) {
    init {
        require(maxInputChars in 1..16_000_000 && maxLines in 1..200_000)
        require(maxLineChars in 64..64_000 && maxEvents in 1..20_000)
        require(maxExcerptChars in 64..16_000)
    }
}

data class ProjectScanConfig(
    val maxEntries: Int = 20_000,
    val maxDepth: Int = 20,
    val maxFileBytes: Int = 512 * 1024,
    val maxTotalBytes: Int = 12 * 1024 * 1024,
    val maxFindings: Int = 500,
    val excludedDirectories: Set<String> = setOf(
        ".git", ".gradle", ".idea", "build", "node_modules", "dist", "vendor", ".next", ".venv",
    ),
) {
    init {
        require(maxEntries in 1..200_000 && maxDepth in 1..64)
        require(maxFileBytes in 1..(4 * 1024 * 1024) && maxTotalBytes in 1..(64 * 1024 * 1024))
        require(maxFindings in 1..10_000)
        require(excludedDirectories.all { it.isNotBlank() && '/' !in it && '\\' !in it })
    }
}

enum class ReportKind { ARCHIVE, LOGS, PROJECT }
enum class RecordKind { ISSUE, PULL_REQUEST }
enum class RecordState { OPEN, CLOSED, UNKNOWN }
enum class MergeStatus { MERGED, NOT_MERGED, UNKNOWN, NOT_APPLICABLE }
enum class Confidence { HIGH, MEDIUM, LOW }
enum class PrimaryTheme(val key: String, val label: String) {
    PLATFORM_COMPATIBILITY("platform_compatibility", "Platform compatibility"),
    RUNTIME_LIFECYCLE("runtime_lifecycle", "Runtime and lifecycle"),
    EXTERNAL_SERVICES("external_services", "External services and API failures"),
    DATA_STATE("data_state", "Data and state integrity"),
    UI_UX("ui_ux", "UI and usability"),
    DELIVERY_BUILD("delivery_build", "Delivery and build reproducibility"),
    PERFORMANCE("performance", "Performance and resource use"),
    SECURITY_PRIVACY("security_privacy", "Security and privacy"),
    PRODUCT_CAPABILITY("product_capability", "Product-specific capability"),
    UNCLASSIFIED("unclassified", "Unclassified — review needed"),
}
enum class RecordIntent { BUG_REPORT, FEATURE_REQUEST, SUPPORT_QUESTION, POSITIVE_COMPATIBILITY_REPORT, BUG_FIX, MAINTENANCE, UNCLEAR }
/** No value means independently confirmed cause; even MECHANISM_DESCRIBED is an author's assertion. */
enum class CauseStatus { SYMPTOM_ONLY, REPORTER_HYPOTHESIS, MECHANISM_DESCRIBED, NOT_APPLICABLE }
enum class Transferability { GENERIC_APP_QUALITY, DEVELOPER_TOOLING, APP_SPECIFIC, UNCLEAR }
enum class EvidenceSource { TITLE, BODY, LOG }
enum class GroupKind { THEME, STACK_FINGERPRINT, EXCEPTION_SIGNATURE }
enum class Severity { INFO, WARNING, HIGH }
enum class FindingStatus { OBSERVED, REVIEW_NEEDED }

data class EvidenceCitation(
    val recordId: String,
    val url: String?,
    val snippet: String,
    val source: EvidenceSource,
    val line: Int? = null,
    val redacted: Boolean = false,
)

data class ExplicitReference(val id: String, val url: String, val kind: RecordKind? = null)

data class QualityRecord(
    val id: String,
    val repository: String,
    val number: Long,
    val kind: RecordKind,
    val state: RecordState,
    val mergeStatus: MergeStatus,
    val title: String,
    /** Bounded, best-effort redacted text. Detected/configured specialized-device text is withheld. */
    val body: String,
    val createdAt: Instant?,
    val url: String,
    val intent: RecordIntent,
    val theme: PrimaryTheme,
    val secondaryThemes: List<PrimaryTheme>,
    val problemKey: String,
    val confidence: Confidence,
    val causeStatus: CauseStatus,
    val transferability: Transferability,
    val deviceSecuritySpecific: Boolean,
    val evidence: EvidenceCitation,
    val classificationReason: String,
    val references: List<ExplicitReference>,
    val textTruncated: Boolean = false,
)

data class QualityGroup(
    val id: String,
    val title: String,
    val theme: PrimaryTheme,
    val confidence: Confidence,
    val kind: GroupKind,
    val issueCount: Int,
    val pullRequestCount: Int,
    val recordIds: List<String>,
    val evidence: List<EvidenceCitation>,
    /** Only explicit textual references, never a link inferred from theme or stack similarity. */
    val linkedPullRequestIds: List<String> = emptyList(),
    val fingerprint: String? = null,
    val occurrenceCount: Int = issueCount + pullRequestCount,
    val explanation: String,
)

data class RecordCounts(val issues: Int = 0, val pullRequests: Int = 0) {
    val total: Int get() = issues + pullRequests
}

data class ArchiveMetadata(
    val source: String,
    val generated: Instant?,
    val declaredPeriod: String?,
    val declaredWindow: ArchiveWindow?,
)

data class RepositoryStatistics(
    val repository: String,
    val full: RecordCounts,
    val recent: RecordCounts?,
    val included: RecordCounts,
    val deviceSecuritySpecific: RecordCounts,
)

data class ArchiveStatistics(
    val inputRecordCount: Int,
    val invalidRecordCount: Int,
    val duplicateRecordCount: Int,
    val truncatedRecordCount: Int,
    val full: RecordCounts,
    val recent: RecordCounts?,
    val included: RecordCounts,
    val recentWindow: ArchiveWindow?,
    val oldestCreatedAt: Instant?,
    val newestCreatedAt: Instant?,
    val missingCreatedAtCount: Int,
    val outsideWindowCount: Int?,
    val repositories: List<RepositoryStatistics>,
    val deviceSecuritySpecific: RecordCounts,
    val withoutDeviceSecuritySpecific: RecordCounts,
    val fullByTheme: Map<PrimaryTheme, RecordCounts>,
    val includedByTheme: Map<PrimaryTheme, RecordCounts>,
    val withoutDeviceSecurityByTheme: Map<PrimaryTheme, RecordCounts>,
    val metadata: List<ArchiveMetadata>,
    val issuesByState: Map<RecordState, Int>,
    val pullRequestsByState: Map<RecordState, Int>,
    val pullRequestsByMergeStatus: Map<MergeStatus, Int>,
)

data class LogEvent(
    val startLine: Int,
    val endLine: Int,
    val exceptionClass: String,
    val frames: List<String>,
    val fingerprint: String,
    val excerpt: String,
    val redacted: Boolean,
)

data class LogSummary(
    val lineCount: Int,
    val redacted: Boolean,
    val truncatedLines: Int,
    val events: List<LogEvent>,
)

data class QualityFinding(
    val ruleId: String,
    val severity: Severity,
    val status: FindingStatus,
    /** Absolute source path; line is one-based. An absence check anchors to a relevant configuration file. */
    val file: String,
    val line: Int,
    val reason: String,
    val evidence: String,
    val remediation: String,
)

data class ScanSummary(
    val root: String,
    val visitedEntries: Int,
    val inspectedFiles: Int,
    val inspectedBytes: Long,
    val skippedSymlinks: Int,
    val skippedFiles: Int,
    val excludedDirectories: List<String>,
    val partial: Boolean,
    val testFilesObserved: Int,
    val lockfilesObserved: Int,
    val buildConfigurationsObserved: Int,
)

data class VerificationChecklist(
    val theme: PrimaryTheme,
    val title: String,
    val steps: List<String>,
    val status: String = "Suggested verification — not executed",
)

data class QualityReport(
    val kind: ReportKind,
    val title: String,
    val generatedAt: Instant,
    val records: List<QualityRecord> = emptyList(),
    val groups: List<QualityGroup> = emptyList(),
    val statistics: ArchiveStatistics? = null,
    val findings: List<QualityFinding> = emptyList(),
    val logSummary: LogSummary? = null,
    val scanSummary: ScanSummary? = null,
    val checklists: List<VerificationChecklist> = emptyList(),
    val warnings: List<String> = emptyList(),
    val caveats: List<String> = emptyList(),
    val schemaVersion: Int = 1,
)

enum class ExportFormat {
    MARKDOWN, JSON;
    companion object {
        fun fromPath(path: Path): ExportFormat = when (path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)) {
            "json" -> JSON
            "md", "markdown" -> MARKDOWN
            else -> throw QualityException(QualityErrorCode.UNSUPPORTED_FORMAT, "Choose a .json or .md export destination, or specify the format.")
        }
    }
}

data class ExportResult(val path: Path, val format: ExportFormat, val bytesWritten: Long)
enum class QualityErrorCode { INVALID_INPUT, UNSUPPORTED_FORMAT, LIMIT_EXCEEDED, UNSAFE_PATH, IO_ERROR, DESTINATION_EXISTS }
/** Error text never echoes issue content, logs, XML parser excerpts, or credentials. */
class QualityException(val code: QualityErrorCode, message: String) : IllegalArgumentException(message)
