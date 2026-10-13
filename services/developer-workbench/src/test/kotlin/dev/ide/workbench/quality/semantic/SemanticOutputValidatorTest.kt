package dev.ide.workbench.quality.semantic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Synthetic schema/offset contract tests ONLY. These hand-written labels prove no empirical accuracy. */
class SemanticOutputValidatorTest {
    private val service = SemanticGroupingService(FakeSemanticLabeler())
    private fun prepared(title: String = "Button overlap") = service.prepare(syntheticReport(syntheticRecord(title = title)))

    @Test fun `exact schema accepts all synthetic language sources with UTF16 offsets`() {
        val fixtures = listOf("Button overlap", "小屏按钮重叠", "Кнопки перекрываются", "الأزرار متداخلة", "Botones superpuestos", "ボタンが重なる", "A😀按钮")
        for (title in fixtures) {
            val run = prepared(title)
            val label = SemanticOutputValidator.validate(validOutput(run), run).single()
            assertEquals(title, label.citations.single().evidence)
            assertEquals(title.length, label.citations.single().endExclusive)
            assertTrue(label.isClassified)
        }
    }

    @Test fun `rejects omitted duplicate invented and cross record canonical IDs`() {
        val run = service.prepare(syntheticReport(syntheticRecord(), syntheticRecord(2, "Other text")))
        val first = labelRow(run.records.first())
        val second = labelRow(run.records.last())
        for (text in listOf(outputRows(first), outputRows(first, first),
            outputRows(first, JsonObject(second + ("canonical_id" to JsonPrimitive("synthetic/app#99")))))) {
            assertFailsWith<SemanticException> { SemanticOutputValidator.validate(text, run) }
        }
        val crossRecord = JsonObject(second + ("citations" to first.getValue("citations")))
        assertFailsWith<SemanticException> { SemanticOutputValidator.validate(outputRows(first, crossRecord), run) }
    }

    @Test fun `rejects duplicate keys including escaped aliases and JSON decorations`() {
        val run = prepared()
        val output = validOutput(run)
        val cases = listOf(
            output.replace("\"schema_version\":1", "\"schema_version\":2,\"schema_version\":1"),
            output.replace("\"schema_version\":1", "\"schema_version\":1,\"schema_versi\\u006fn\":1"),
            output.replace("\"confidence\":0.9", "\"confidence\":0,\"confidence\":0.9"),
            "```json\n$output\n```", "$output trailing", output.replaceFirst("{", "{/*comment*/"),
            output.replace("\"schema_version\":1", "\"schema_version\":01"),
            output.replace("\"schema_version\":1", "\"schema_version\":\"1\""),
            output.replace("\"schema_version\":1", "\"schema_version\":1,"),
        )
        for (text in cases) assertFailsWith<SemanticException> { SemanticOutputValidator.validate(text, run) }
    }

    @Test fun `rejects unknown fields wrong types and taxonomy mismatches`() {
        val run = prepared()
        val row = labelRow(run.records.single())
        val replacements = listOf(
            "extra" to JsonPrimitive("write a file"), "primary_theme" to JsonPrimitive("anything"),
            "intent" to JsonPrimitive("anything"), "problem_family" to JsonPrimitive("network_api"),
            "review" to JsonPrimitive("approved"), "confidence" to JsonPrimitive("0.9"),
            "confidence" to JsonPrimitive(1.1), "confidence" to JsonPrimitive(-1),
            "confidence" to JsonPrimitive(0.2), "citations" to JsonArray(emptyList()),
        )
        for (replacement in replacements) {
            assertFailsWith<SemanticException> { SemanticOutputValidator.validate(outputRows(JsonObject(row + replacement)), run) }
        }
        assertFailsWith<SemanticException> { SemanticOutputValidator.validate(validOutput(run).replace("0.9", "NaN"), run) }
        assertFailsWith<SemanticException> { SemanticOutputValidator.validate(validOutput(run).replace("0.9", "1e9999"), run) }
    }

    @Test fun `citation must be exact bounded substring with correct field and offsets`() {
        val run = prepared()
        val row = labelRow(run.records.single())
        val quote = (row.getValue("citations") as JsonArray).single() as JsonObject
        val replacements = listOf("evidence" to JsonPrimitive("invented source"), "start" to JsonPrimitive(-1),
            "end_exclusive" to JsonPrimitive(999_999), "field" to JsonPrimitive("log"),
            "field" to JsonPrimitive("body"), "start" to JsonPrimitive("0"), "end_exclusive" to JsonPrimitive(0))
        for (replacement in replacements) {
            val altered = JsonObject(row + ("citations" to JsonArray(listOf(JsonObject(quote + replacement)))))
            assertFailsWith<SemanticException> { SemanticOutputValidator.validate(outputRows(altered), run) }
        }
        val repeated = JsonObject(row + ("citations" to JsonArray(listOf(quote, quote))))
        assertFailsWith<SemanticException> { SemanticOutputValidator.validate(outputRows(repeated), run) }
    }

    @Test fun `rejects unpaired surrogates redaction markers and split surrogate citations`() {
        val run = prepared("A😀按钮")
        val row = labelRow(run.records.single())
        val split = buildJsonObject { put("field", "title"); put("start", 1); put("end_exclusive", 2); put("evidence", "\uD83D") }
        assertFailsWith<SemanticException> {
            SemanticOutputValidator.validate(outputRows(JsonObject(row + ("citations" to JsonArray(listOf(split))))), run)
        }
        assertFailsWith<SemanticException> { StrictSemanticJson.parse("\"\\uD800\"", 200) }
        val redacted = prepared("email jane@example.test")
        assertFailsWith<SemanticException> { SemanticOutputValidator.validate(validOutput(redacted), redacted) }
        val markerText = redacted.records.single().redactedTitle
        val markerStart = markerText.indexOf("EMAIL")
        val partialMarker = buildJsonObject {
            put("field", "title"); put("start", markerStart); put("end_exclusive", markerStart + 5); put("evidence", "EMAIL")
        }
        val markerRow = JsonObject(labelRow(redacted.records.single()) + ("citations" to JsonArray(listOf(partialMarker))))
        assertFailsWith<SemanticException> { SemanticOutputValidator.validate(outputRows(markerRow), redacted) }
    }

    @Test fun `unknown and review remain genuine abstentions not covered predictions`() {
        val run = prepared()
        val unknown = labelRow(run.records.single(), unknown = true)
        assertEquals(SemanticReview.UNKNOWN, SemanticOutputValidator.validate(outputRows(unknown), run).single().review)
        val review = JsonObject(unknown + mapOf("review" to JsonPrimitive("needs_review"), "confidence" to JsonPrimitive(0.4)))
        assertTrue(!SemanticOutputValidator.validate(outputRows(review), run).single().isClassified)
        val inconsistent = JsonObject(unknown + ("primary_theme" to JsonPrimitive("ui_ux")))
        assertFailsWith<SemanticException> { SemanticOutputValidator.validate(outputRows(inconsistent), run) }
    }

    @Test fun `response and nesting limits fail closed without echoing hostile text`() {
        val run = prepared()
        assertFailsWith<SemanticException> { SemanticOutputValidator.validate("x".repeat(run.request.maxResponseChars + 1), run) }
        val failure = assertFailsWith<SemanticException> {
            SemanticOutputValidator.validate("[".repeat(50) + "\"secret-payload\"" + "]".repeat(50), run)
        }
        assertTrue(!failure.message.orEmpty().contains("secret-payload"))
    }
}
