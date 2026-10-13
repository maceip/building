package dev.ide.workbench.quality

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in empirical audit. No corpus or analyst labels are bundled, and no ID-specific rule is used.
 * QUALITY_EVALUATION_ARCHIVE and QUALITY_EVALUATION_LABELS must name local files explicitly.
 * This is measurement, not an accuracy gate or a claim that analyst labels are objective ground truth.
 */
class QualityCorpusEvaluationTest {
    @Test fun `measure external archive against independently reviewed labels when explicitly supplied`() {
        val archive = System.getenv("QUALITY_EVALUATION_ARCHIVE")
        val labels = System.getenv("QUALITY_EVALUATION_LABELS")
        assumeTrue(!archive.isNullOrBlank() && !labels.isNullOrBlank(), "External corpus evaluation is opt-in; no private archive is bundled.")
        val archivePath = Path.of(archive!!)
        val labelPath = Path.of(labels!!)
        val labelTree = parseBoundedJson(decodeUtf8(readRegularFile(labelPath, 16 * 1024 * 1024))) as JsonArray
        val rows = labelTree.map { it as JsonObject }
        val labelsById = rows.associateBy { it.string("id")!!.lowercase(Locale.ROOT) }
        assertEquals(rows.size, labelsById.size, "Analyst labels must use unique canonical IDs")
        val report = QualityService(QualityConfig(archive = ArchiveImportConfig(includeDeviceSecuritySpecific = true))).importArchive(archivePath)
        val predictions = report.records.associateBy { it.id.lowercase(Locale.ROOT) }
        val commonIds = predictions.keys.intersect(labelsById.keys).sorted()
        assertTrue(commonIds.isNotEmpty(), "No labels matched imported canonical IDs")
        val comparable = commonIds.map { id -> predictions.getValue(id) to labelsById.getValue(id) }
        val classified = comparable.filter { it.first.theme != PrimaryTheme.UNCLASSIFIED }
        val high = comparable.filter { it.first.confidence == Confidence.HIGH }
        val low = comparable.filter { it.first.confidence == Confidence.LOW }
        fun matches(pair: Pair<QualityRecord, JsonObject>) = pair.first.theme.key == pair.second.string("theme")
        fun fraction(numerator: Int, denominator: Int): JsonElement = if (denominator == 0) JsonNull else JsonPrimitive(numerator.toDouble() / denominator)
        fun summary(pairs: List<Pair<QualityRecord, JsonObject>>): JsonObject = JsonObject(mapOf(
            "count" to JsonPrimitive(pairs.size), "primary_agreement" to JsonPrimitive(pairs.count(::matches)),
            "primary_accuracy" to fraction(pairs.count(::matches), pairs.size),
        ))
        val confusion = comparable.groupBy { it.second.string("theme") ?: "missing_label" }.toSortedMap().mapValues { (_, pairs) ->
            JsonObject(pairs.groupingBy { it.first.theme.key }.eachCount().toSortedMap().mapValues { JsonPrimitive(it.value) })
        }
        fun labeledSensitive(row: JsonObject): Boolean = (row["device_security_specific"] as? JsonPrimitive)?.booleanOrNull == true
        val tp = comparable.count { it.first.deviceSecuritySpecific && labeledSensitive(it.second) }
        val fp = comparable.count { it.first.deviceSecuritySpecific && !labeledSensitive(it.second) }
        val fn = comparable.count { !it.first.deviceSecuritySpecific && labeledSensitive(it.second) }
        val tn = comparable.count { !it.first.deviceSecuritySpecific && !labeledSensitive(it.second) }
        val stats = report.statistics!!
        fun counts(value: RecordCounts): JsonObject = JsonObject(mapOf("issues" to JsonPrimitive(value.issues), "prs" to JsonPrimitive(value.pullRequests), "total" to JsonPrimitive(value.total)))
        val output = JsonObject(linkedMapOf(
            "schema_version" to JsonPrimitive(1),
            "method" to JsonPrimitive("Offline pattern-classifier predictions compared with independently reviewed primary-theme labels; no label-driven ID rules."),
            "limitations" to JsonArray(listOf(
                "Agreement is on a selected, repository-skewed archive, not held-out ecosystem validation.",
                "Human labels are review judgments, not verified causes. High confidence is a local rule score, not calibrated correctness probability.",
                "Unclassified is an explicit abstention. Specialized-device sensitivity detection is also heuristic and can miss context.",
                "No issue body/title, private archive contents, labels, or local source paths are copied into this aggregate output.",
            ).map(::JsonPrimitive)),
            "imported" to counts(stats.full), "recent" to (stats.recent?.let(::counts) ?: JsonNull),
            "repository_count" to JsonPrimitive(stats.repositories.size),
            "oldest_created_at" to JsonPrimitive(stats.oldestCreatedAt?.toString()), "newest_created_at" to JsonPrimitive(stats.newestCreatedAt?.toString()),
            "duplicate_record_count" to JsonPrimitive(stats.duplicateRecordCount), "invalid_record_count" to JsonPrimitive(stats.invalidRecordCount),
            "matched_label_count" to JsonPrimitive(comparable.size),
            "missing_label_count" to JsonPrimitive(predictions.keys.subtract(labelsById.keys).size),
            "labels_without_imported_record_count" to JsonPrimitive(labelsById.keys.subtract(predictions.keys).size),
            "overall" to summary(comparable), "classified_only" to summary(classified),
            "classified_coverage" to fraction(classified.size, comparable.size),
            "abstention_count" to JsonPrimitive(comparable.size - classified.size),
            "abstention_rate" to fraction(comparable.size - classified.size, comparable.size),
            "high_confidence" to summary(high), "low_confidence" to summary(low),
            "confusion_actual_rows_predicted_columns" to JsonObject(confusion),
            "per_repository" to JsonObject(comparable.groupBy { it.first.repository }.toSortedMap().mapValues { summary(it.value) }),
            "specialized_device_security" to JsonObject(mapOf(
                "true_positive" to JsonPrimitive(tp), "false_positive" to JsonPrimitive(fp), "false_negative" to JsonPrimitive(fn), "true_negative" to JsonPrimitive(tn),
                "precision" to fraction(tp, tp + fp), "recall" to fraction(tp, tp + fn),
            )),
        ))
        val destination = Path.of("build", "quality-evaluation.json").toAbsolutePath()
        Files.createDirectories(destination.parent)
        Files.writeString(destination, qualityJson.encodeToString(JsonElement.serializer(), output))
        println("Quality evaluation: matched=${comparable.size}; classified=${classified.size}; primary agreement=${comparable.count(::matches)}; high-confidence agreement=${high.count(::matches)}/${high.size}; aggregate=${destination.fileName}")
    }
}
