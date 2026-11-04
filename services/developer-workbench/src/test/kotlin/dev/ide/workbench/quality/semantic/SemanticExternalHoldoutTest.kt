package dev.ide.workbench.quality.semantic

import dev.ide.workbench.quality.ArchiveImportConfig
import dev.ide.workbench.quality.PrimaryTheme
import dev.ide.workbench.quality.QualityConfig
import dev.ide.workbench.quality.QualityRecord
import dev.ide.workbench.quality.QualityService
import dev.ide.workbench.quality.RecordKind
import dev.ide.workbench.quality.decodeUtf8
import dev.ide.workbench.quality.readRegularFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale
import kotlin.test.assertTrue

/**
 * Read-only, opt-in scorer. No provider/client, credentials, network, split generation or output files.
 * Required environment: QUALITY_SEMANTIC_EVALUATE=1; QUALITY_SEMANTIC_ARCHIVE, _LABELS, _MANIFEST,
 * _PREDICTIONS name explicit local files under Downloads; QUALITY_SEMANTIC_HOLDOUT_SHA256 is the observer's
 * previously frozen manifest digest (retained outside this repository). QUALITY_SEMANTIC_PROVIDER names
 * the provider/model used for the saved predictions; QUALITY_SEMANTIC_RUN_CONSENT=1 and
 * QUALITY_SEMANTIC_INDEPENDENT_REVIEW=1 assert the external prerequisites. These flags are NOT proof of
 * consent or reviewer independence and never authorize this test to make a model request.
 *
 * An independent observer chooses the seed and issue IDs BEFORE reading hidden holdout labels/tuning.
 * Frozen manifest: {schema_version:1, seed:"predeclared seed", holdout_ids:[canonical issue IDs],
 * strata:[{id,repository,language,theme_stratum,sensitivity,duplicate_cluster}]}.
 * Strata cover the 625-issue/38-PR pool. Theme/sensitivity metadata must be predeclared without hidden
 * holdout labels. Duplicate clusters are observer-supplied (including near-duplicates); exact normalized
 * title/body duplicates are checked below. This test never chooses a split using labels.
 *
 * Predictions are strict schema_version/records output for supplied issue holdout IDs + all 38 PRs.
 * Labels are a local array with id/theme, independently reviewed, not our LLM-generated 'ground truth'.
 * Per-run record-sharing confirmation happens in the real classification UI/host, NOT in this scorer.
 * The gate stays BLOCKED until that consent/provider and independent review actually exist. Human review
 * and no training/test leakage cannot be established by flags, hashes, or synthetic fixtures alone.
 */
class SemanticExternalHoldoutTest {
    @Test fun `external issue holdout gate requires independently supplied frozen IDs and predictions`() {
        assumeTrue(System.getenv("QUALITY_SEMANTIC_EVALUATE") == "1",
            "BLOCKED: external evaluation is opt-in; provider consent and independent holdout review are not established.")
        gate(System.getenv("QUALITY_SEMANTIC_RUN_CONSENT") == "1" &&
            System.getenv("QUALITY_SEMANTIC_INDEPENDENT_REVIEW") == "1" &&
            !System.getenv("QUALITY_SEMANTIC_PROVIDER").isNullOrBlank(),
            "explicit run consent, provider/model and independent review are required externally")
        val paths = listOf("ARCHIVE", "LABELS", "MANIFEST", "PREDICTIONS").associateWith { externalPath(System.getenv("QUALITY_SEMANTIC_$it")) }
        val manifestBytes = readRegularFile(paths.getValue("MANIFEST"), 2 * 1024 * 1024)
        val expectedHash = System.getenv("QUALITY_SEMANTIC_HOLDOUT_SHA256")
        gate(expectedHash?.matches(Regex("[0-9a-f]{64}")) == true && semanticSha256(manifestBytes) == expectedHash,
            "observer's frozen manifest digest is missing or different")
        val frozen = FrozenSemanticHoldout.parse(decodeUtf8(manifestBytes))
        val report = QualityService(QualityConfig(archive = ArchiveImportConfig(includeDeviceSecuritySpecific = true)))
            .importArchive(paths.getValue("ARCHIVE"))
        frozen.validatePopulation(report.records)
        val prs = report.records.filter { it.kind == RecordKind.PULL_REQUEST }.map { it.id }.toSet()
        val evaluationIds = frozen.holdoutIds + prs
        val rows = StrictSemanticJson.parse(decodeUtf8(readRegularFile(paths.getValue("PREDICTIONS"), 4 * 1024 * 1024)), 4 * 1024 * 1024)
            .objectWith("schema_version", "records")
        gate(rows.integer("schema_version") == 1, "unsupported prediction schema")
        val predictions = (rows["records"] as? JsonArray ?: blocked("predictions missing")).map {
            it as? JsonObject ?: blocked("invalid prediction row")
        }
        val byId = predictions.associateBy { it.text("canonical_id") }
        gate(byId.size == predictions.size && byId.keys == evaluationIds, "predictions must cover holdout issues and 38 separate PR diagnostics exactly")
        val validated = linkedMapOf<String, SemanticLabel>()
        // Metadata-only port for deterministic preparation. It is impossible to reach a real client here.
        val service = SemanticGroupingService(object : SemanticLabeler {
            override val provider = syntheticProvider
            override suspend fun label(request: SemanticLabelRequest): String = error("Scorer never invokes a model")
        })
        val eligible = report.records.filter { it.id in evaluationIds && !it.deviceSecuritySpecific }
        for (source in eligible.sortedBy { it.id }) {
            // Validate the identical bounded fields individually: scorer batching must not invent request-cap failures.
            val prepared = service.prepare(report, SemanticSelection(setOf(source.id), RecordKind.entries.toSet()))
            val output = outputRows(byId.getValue(source.id))
            SemanticOutputValidator.validate(output, prepared).forEach { validated[it.canonicalId] = it }
        }
        for (source in report.records.filter { it.id in evaluationIds && it.deviceSecuritySpecific }) {
            val prepared = SemanticPreparedRecord(source, source.title, source.body, source.textTruncated)
            val label = SemanticOutputValidator.validate(outputRows(byId.getValue(source.id)), listOf(prepared), 96 * 1024).single()
            gate(label.review == SemanticReview.UNKNOWN, "withheld sensitive records must abstain and remain in coverage denominator")
            validated[source.id] = label
        }
        // Only now read labels: they cannot influence the frozen IDs, strata, duplicate clusters or predictions.
        val labelRows = StrictSemanticJson.parse(decodeUtf8(readRegularFile(paths.getValue("LABELS"), 16 * 1024 * 1024)), 16 * 1024 * 1024)
            as? JsonArray ?: blocked("labels must be a local JSON array")
        val labels = linkedMapOf<String, PrimaryTheme>()
        for (item in labelRows) {
            val row = item as? JsonObject ?: blocked("invalid reviewed label row")
            val theme = PrimaryTheme.entries.firstOrNull { it.key == row.text("theme") } ?: blocked("unknown reviewed theme")
            gate(labels.put(row.text("id"), theme) == null, "duplicate reviewed IDs")
        }
        gate(labels.keys.containsAll(evaluationIds), "reviewed labels do not cover frozen IDs")
        val issues = semanticAgreement(frozen.holdoutIds, validated, labels)
        val diagnostic = semanticAgreement(prs, validated, labels)
        println("Issue holdout n=${issues.total}/625, agreement=${issues.agreed}/${issues.total}, coverage=${issues.classified}/${issues.total}; PR diagnostics ONLY n=${diagnostic.total}/38, agreement=${diagnostic.agreed}/${diagnostic.total}, coverage=${diagnostic.classified}/${diagnostic.total}. Abstentions count in denominators; reviewed judgments are not objective ground truth.")
        assertTrue(issues.passes, "External gate FAILED: issue primary agreement and coverage must each be >=80%; PR diagnostics cannot rescue it.")
    }
}

internal data class SemanticAgreement(val total: Int, val classified: Int, val agreed: Int) {
    val passes: Boolean get() = total > 0 && classified * 100L >= total * 80L && agreed * 100L >= total * 80L
}
internal fun semanticAgreement(ids: Set<String>, predictions: Map<String, SemanticLabel>, labels: Map<String, PrimaryTheme>): SemanticAgreement =
    SemanticAgreement(ids.size, ids.count { predictions[it]?.isClassified == true },
        ids.count { predictions[it]?.let { label -> label.isClassified && label.primaryTheme == labels[it] } == true })

internal data class FrozenStratum(val id: String, val repository: String, val language: String, val theme: String,
    val sensitivity: String, val duplicateCluster: String) {
    val stratum: List<String> get() = listOf(repository, language, theme, sensitivity)
}
internal data class FrozenSemanticHoldout(val seed: String, val holdoutIds: Set<String>, val strata: List<FrozenStratum>) {
    fun validatePopulation(records: List<QualityRecord>) {
        gate(records.count { it.kind == RecordKind.ISSUE } == 625 && records.count { it.kind == RecordKind.PULL_REQUEST } == 38,
            "external population must be 625 issues and 38 separate PR diagnostics")
        validateSplit(records)
    }

    internal fun validateSplit(records: List<QualityRecord>) {
        val byId = records.associateBy { it.id }
        val byStratum = strata.associateBy { it.id }
        gate(byId.size == records.size && byStratum.size == strata.size && byStratum.keys == byId.keys,
            "frozen strata must cover the input population exactly")
        gate(holdoutIds.isNotEmpty() && holdoutIds.all { byId[it]?.kind == RecordKind.ISSUE }, "holdout must contain supplied issue IDs only")
        gate(strata.all { it.repository == byId.getValue(it.id).repository }, "repository strata mismatch")
        val issueStrata = records.filter { it.kind == RecordKind.ISSUE }.map { byStratum.getValue(it.id).stratum }.toSet()
        gate(holdoutIds.map { byStratum.getValue(it).stratum }.toSet() == issueStrata, "holdout must represent predeclared repo language theme and sensitivity strata")
        for (cluster in strata.filter { byId.getValue(it.id).kind == RecordKind.ISSUE }.groupBy { it.duplicateCluster }.values) {
            gate(cluster.none { it.id in holdoutIds } || cluster.all { it.id in holdoutIds }, "duplicate cluster crosses development and holdout")
        }
        // No label features here. Near-duplicates still require the independent observer's textual review.
        val copies = records.filterNot { it.deviceSecuritySpecific || (it.title.isBlank() && it.body.isBlank()) }
            .groupBy { semanticSha256(normalizeDuplicateText(it.title) + "\n" + normalizeDuplicateText(it.body)) }
        for (duplicates in copies.values.filter { it.size > 1 }) {
            gate(duplicates.map { byStratum.getValue(it.id).duplicateCluster }.distinct().size == 1,
                "normalized textual duplicates were not joined before choosing holdout IDs")
        }
    }

    companion object {
        fun parse(raw: String): FrozenSemanticHoldout {
            val row = StrictSemanticJson.parse(raw, 2 * 1024 * 1024).objectWith("schema_version", "seed", "holdout_ids", "strata")
            gate(row.integer("schema_version") == 1 && row.text("seed").isNotBlank(), "predeclared seed required")
            val ids = row.textList("holdout_ids")
            gate(ids.isNotEmpty() && ids.distinct().size == ids.size && ids.all(::canonicalId), "unique frozen canonical IDs required")
            val strata = (row["strata"] as? JsonArray ?: blocked("frozen strata missing")).map {
                val value = it.objectWith("id", "repository", "language", "theme_stratum", "sensitivity", "duplicate_cluster")
                FrozenStratum(value.text("id"), value.text("repository"), value.text("language"), value.text("theme_stratum"),
                    value.text("sensitivity"), value.text("duplicate_cluster"))
            }
            gate(strata.all { canonicalId(it.id) && it.repository.isNotBlank() && it.language.isNotBlank() && it.theme.isNotBlank() &&
                it.sensitivity in setOf("general", "sensitive", "unknown") && it.duplicateCluster.isNotBlank() }, "predeclared strata fields missing")
            return FrozenSemanticHoldout(row.text("seed"), ids.toSet(), strata)
        }
    }
}

private fun externalPath(raw: String?): Path {
    gate(!raw.isNullOrBlank(), "explicit external file paths required")
    val path = Path.of(raw!!).toAbsolutePath().normalize()
    gate(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "external inputs must be regular non-symlink files")
    val actual = path.toRealPath()
    gate(actual.startsWith(Path.of(System.getProperty("user.home"), "Downloads").toRealPath()) &&
        !actual.startsWith(Path.of("").toAbsolutePath().toRealPath()), "frozen inputs must remain in Downloads outside project")
    return actual
}
private fun normalizeDuplicateText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
private fun canonicalId(id: String): Boolean = id.matches(Regex("[a-z0-9_.-]+/[a-z0-9_.-]+#[1-9][0-9]*"))
private fun JsonObject.textList(key: String): List<String> = (this[key] as? JsonArray ?: blocked("explicit list missing")).map {
    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: blocked("invalid list entry")
}
internal fun gate(condition: Boolean, reason: String) { if (!condition) blocked(reason) }
internal fun blocked(reason: String): Nothing = throw IllegalStateException("External gate BLOCKED: $reason.")
