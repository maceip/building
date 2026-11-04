package dev.ide.workbench.quality.semantic

import dev.ide.workbench.quality.CauseStatus
import dev.ide.workbench.quality.Confidence
import dev.ide.workbench.quality.EvidenceCitation
import dev.ide.workbench.quality.EvidenceSource
import dev.ide.workbench.quality.MergeStatus
import dev.ide.workbench.quality.PrimaryTheme
import dev.ide.workbench.quality.QualityRecord
import dev.ide.workbench.quality.QualityReport
import dev.ide.workbench.quality.RecordIntent
import dev.ide.workbench.quality.RecordKind
import dev.ide.workbench.quality.RecordState
import dev.ide.workbench.quality.ReportKind
import dev.ide.workbench.quality.Transferability
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

internal val syntheticProvider = SemanticProvider("fake", "Synthetic fake (no network)", "fixture", "offline fake", SemanticLocality.LOCAL)

internal fun syntheticRecord(number: Long = 1, title: String = "Button overlap", body: String = "Buttons overlap on the small screen.",
    kind: RecordKind = RecordKind.ISSUE, sensitive: Boolean = false): QualityRecord = QualityRecord(
    id = "synthetic/app#$number", repository = "synthetic/app", number = number, kind = kind, state = RecordState.CLOSED,
    mergeStatus = if (kind == RecordKind.PULL_REQUEST) MergeStatus.UNKNOWN else MergeStatus.NOT_APPLICABLE,
    title = title, body = body, createdAt = Instant.parse("2026-10-01T00:00:00Z"),
    url = "https://github.com/synthetic/app/${if (kind == RecordKind.ISSUE) "issues" else "pull"}/$number",
    intent = RecordIntent.UNCLEAR, theme = PrimaryTheme.UNCLASSIFIED, secondaryThemes = emptyList(),
    problemKey = "unclassified", confidence = Confidence.LOW, causeStatus = CauseStatus.SYMPTOM_ONLY,
    transferability = Transferability.UNCLEAR, deviceSecuritySpecific = sensitive,
    evidence = EvidenceCitation("synthetic/app#$number", null, title, EvidenceSource.TITLE),
    classificationReason = "Synthetic placeholder; never accuracy evidence.", references = emptyList(),
)
internal fun syntheticReport(vararg records: QualityRecord): QualityReport = QualityReport(
    ReportKind.ARCHIVE, "Synthetic contract fixture — not accuracy evidence", Instant.parse("2026-10-01T00:00:00Z"), records.toList())

internal class FakeSemanticLabeler(
    override var provider: SemanticProvider = syntheticProvider,
    var operation: suspend (SemanticLabelRequest) -> String = { "{}" },
) : SemanticLabeler {
    var calls = 0
    val requests = mutableListOf<SemanticLabelRequest>()
    override suspend fun label(request: SemanticLabelRequest): String {
        calls++
        requests += request
        return operation(request)
    }
}

internal fun labelRow(record: SemanticPreparedRecord, unknown: Boolean = false): JsonObject = buildJsonObject {
    put("canonical_id", record.source.id)
    put("primary_theme", if (unknown) "unclassified" else "ui_ux")
    put("intent", if (unknown) "unclear" else "bug_report")
    put("problem_family", if (unknown) "unknown" else "layout_rendering")
    put("citations", JsonArray(if (unknown) emptyList() else listOf(buildJsonObject {
        put("field", "title"); put("start", 0); put("end_exclusive", record.redactedTitle.length)
        put("evidence", record.redactedTitle)
    })))
    put("confidence", if (unknown) 0.0 else 0.9)
    put("review", if (unknown) "unknown" else "classified")
}
internal fun outputRows(vararg rows: JsonObject): String = buildJsonObject {
    put("schema_version", 1); put("records", JsonArray(rows.toList()))
}.toString()
internal fun validOutput(prepared: SemanticPreparedRun): String = outputRows(*prepared.records.map(::labelRow).toTypedArray())
