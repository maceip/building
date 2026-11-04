package dev.ide.workbench.quality.semantic

import dev.ide.workbench.quality.PrimaryTheme
import dev.ide.workbench.quality.RecordIntent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.Locale

/** Strict syntax (including duplicate keys), exact record membership and mechanically verifiable citations. */
object SemanticOutputValidator {
    const val MIN_CLASSIFIED_CONFIDENCE = 0.65
    private val REDACTION_MARKER = Regex("\\[REDACTED[^\\]]*\\]", RegexOption.IGNORE_CASE)

    val responseSchemaJson: String = buildJsonObject {
        put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        put("type", "object")
        put("additionalProperties", false)
        put("required", strings("schema_version", "records"))
        put("properties", buildJsonObject {
            put("schema_version", buildJsonObject { put("const", 1); put("type", "integer") })
            put("records", buildJsonObject {
                put("type", "array"); put("minItems", 1); put("maxItems", 100)
                put("items", buildJsonObject {
                    put("type", "object"); put("additionalProperties", false)
                    put("required", strings("canonical_id", "primary_theme", "intent", "problem_family", "citations", "confidence", "review"))
                    put("properties", buildJsonObject {
                        put("canonical_id", buildJsonObject {
                            put("type", "string"); put("pattern", "^[a-z0-9_.-]+/[a-z0-9_.-]+#[1-9][0-9]*$")
                        })
                        put("primary_theme", enumSchema(PrimaryTheme.entries.map { it.key }))
                        put("intent", enumSchema(RecordIntent.entries.map { it.name.lowercase(Locale.ROOT) }))
                        put("problem_family", enumSchema(SemanticProblemFamily.entries.map { it.key }))
                        put("review", enumSchema(SemanticReview.entries.map { it.name.lowercase(Locale.ROOT) }))
                        put("confidence", buildJsonObject { put("type", "number"); put("minimum", 0); put("maximum", 1) })
                        put("citations", buildJsonObject {
                            put("type", "array"); put("maxItems", 4)
                            put("items", buildJsonObject {
                                put("type", "object"); put("additionalProperties", false)
                                put("required", strings("field", "start", "end_exclusive", "evidence"))
                                put("properties", buildJsonObject {
                                    put("field", enumSchema(listOf("title", "body")))
                                    put("start", buildJsonObject { put("type", "integer"); put("minimum", 0) })
                                    put("end_exclusive", buildJsonObject { put("type", "integer"); put("minimum", 1) })
                                    put("evidence", buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 400) })
                                })
                            })
                        })
                    })
                })
            })
        })
    }.toString()

    fun validate(raw: String, prepared: SemanticPreparedRun): List<SemanticLabel> =
        validate(raw, prepared.records, prepared.request.maxResponseChars)

    internal fun validate(raw: String, records: List<SemanticPreparedRecord>, maxChars: Int): List<SemanticLabel> {
        val root = StrictSemanticJson.parse(raw, maxChars).objectWith("schema_version", "records")
        checkOutput(root.integer("schema_version") == 1)
        val rows = root["records"] as? JsonArray ?: invalidOutput()
        checkOutput(rows.size == records.size && rows.size in 1..100)
        val sources = records.associateBy { it.source.id }
        checkOutput(sources.size == records.size)
        val seen = HashSet<String>()
        val labels = rows.map { value ->
            val row = value.objectWith("canonical_id", "primary_theme", "intent", "problem_family", "citations", "confidence", "review")
            val id = row.text("canonical_id")
            val source = sources[id] ?: invalidOutput()
            checkOutput(seen.add(id))
            val theme = PrimaryTheme.entries.firstOrNull { it.key == row.text("primary_theme") } ?: invalidOutput()
            val intent = RecordIntent.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == row.text("intent") } ?: invalidOutput()
            val family = SemanticProblemFamily.entries.firstOrNull { it.key == row.text("problem_family") } ?: invalidOutput()
            val review = SemanticReview.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == row.text("review") } ?: invalidOutput()
            val confidence = (row["confidence"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: invalidOutput()
            checkOutput(confidence.isFinite() && confidence in 0.0..1.0)
            val citations = row["citations"] as? JsonArray ?: invalidOutput()
            checkOutput(citations.size <= 4)
            val verified = citations.map { citation ->
                val obj = citation.objectWith("field", "start", "end_exclusive", "evidence")
                val field = when (obj.text("field")) {
                    "title" -> SemanticCitationField.TITLE
                    "body" -> SemanticCitationField.BODY
                    else -> invalidOutput()
                }
                val text = if (field == SemanticCitationField.TITLE) source.redactedTitle else source.redactedBody
                val start = obj.integer("start")
                val end = obj.integer("end_exclusive")
                val evidence = obj.text("evidence")
                checkOutput(start >= 0 && end > start && end <= text.length && end - start <= 400)
                checkOutput(text.isCodePointBoundary(start) && text.isCodePointBoundary(end))
                checkOutput(evidence.isNotBlank() && text.substring(start, end) == evidence)
                // A redaction marker (including a substring of it) is not product evidence.
                checkOutput(REDACTION_MARKER.findAll(text).none { it.range.first < end && it.range.last >= start })
                SemanticCitation(field, start, end, evidence)
            }
            checkOutput(verified.distinct().size == verified.size)
            if (review == SemanticReview.CLASSIFIED) {
                checkOutput(theme != PrimaryTheme.UNCLASSIFIED && family.theme == theme)
                checkOutput(intent != RecordIntent.UNCLEAR && confidence >= MIN_CLASSIFIED_CONFIDENCE && verified.isNotEmpty())
            } else {
                checkOutput(theme == PrimaryTheme.UNCLASSIFIED && family == SemanticProblemFamily.UNKNOWN)
                checkOutput(confidence < MIN_CLASSIFIED_CONFIDENCE)
                if (review == SemanticReview.UNKNOWN) checkOutput(confidence == 0.0 && intent == RecordIntent.UNCLEAR && verified.isEmpty())
            }
            SemanticLabel(id, theme, intent, family, immutableList(verified), confidence, review)
        }
        checkOutput(seen == sources.keys)
        return immutableList(labels.sortedBy { it.canonicalId })
    }

    private fun strings(vararg values: String): JsonArray = JsonArray(values.map(::JsonPrimitive))
    private fun enumSchema(values: List<String>): JsonObject = buildJsonObject {
        put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive)))
    }
}

internal fun invalidOutput(): Nothing = throw SemanticException(SemanticFailure.INVALID_OUTPUT)
internal fun checkOutput(valid: Boolean) { if (!valid) invalidOutput() }
internal fun JsonElement.objectWith(vararg fields: String): JsonObject {
    val obj = this as? JsonObject ?: invalidOutput()
    checkOutput(obj.keys == fields.toSet())
    return obj
}
internal fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content ?: invalidOutput()
internal fun JsonObject.integer(key: String): Int = (this[key] as? JsonPrimitive)
    ?.takeUnless { it.isString }?.intOrNull ?: invalidOutput()
internal fun String.isCodePointBoundary(offset: Int): Boolean = offset == 0 || offset == length ||
    !(this[offset - 1].isHighSurrogate() && this[offset].isLowSurrogate())

/** Small bounded reader: kotlinx's tree reader alone accepts repeated object keys (last value wins). */
internal object StrictSemanticJson {
    fun parse(text: String, maxChars: Int): JsonElement {
        if (text.length > maxChars) throw SemanticException(SemanticFailure.LIMIT_EXCEEDED)
        return Reader(text).read()
    }

    private class Reader(private val input: String) {
        private var cursor = 0
        private var nodes = 0
        fun read(): JsonElement {
            val result = value(0)
            space()
            checkOutput(cursor == input.length)
            return result
        }
        private fun value(depth: Int): JsonElement {
            checkOutput(depth <= 16 && ++nodes <= 20_000)
            space()
            return when (peek()) {
                '{' -> {
                    cursor++
                    val fields = linkedMapOf<String, JsonElement>()
                    space()
                    if (!eat('}')) {
                        do {
                            space()
                            val key = string()
                            checkOutput(!fields.containsKey(key))
                            space(); expect(':')
                            fields[key] = value(depth + 1)
                            space()
                        } while (eat(','))
                        expect('}')
                    }
                    JsonObject(fields)
                }
                '[' -> {
                    cursor++
                    val items = arrayListOf<JsonElement>()
                    space()
                    if (!eat(']')) {
                        do { items += value(depth + 1); space() } while (eat(','))
                        expect(']')
                    }
                    JsonArray(items)
                }
                '"' -> JsonPrimitive(string())
                't' -> { literal("true"); JsonPrimitive(true) }
                'f' -> { literal("false"); JsonPrimitive(false) }
                'n' -> { literal("null"); JsonNull }
                '-', in '0'..'9' -> number()
                else -> invalidOutput()
            }
        }
        private fun string(): String {
            val start = cursor
            expect('"')
            var escaped = false
            while (cursor < input.length) {
                val ch = input[cursor++]
                checkOutput(ch >= ' ')
                if (escaped) {
                    if (ch == 'u') {
                        checkOutput(cursor + 4 <= input.length)
                        repeat(4) { checkOutput(input[cursor++] in "0123456789abcdefABCDEF") }
                    } else checkOutput(ch in "\"\\/bfnrt")
                    escaped = false
                } else when (ch) {
                    '\\' -> escaped = true
                    '"' -> {
                        val decoded = try { (Json.parseToJsonElement(input.substring(start, cursor)) as JsonPrimitive).content }
                        catch (_: IllegalArgumentException) { invalidOutput() }
                        var index = 0
                        while (index < decoded.length) {
                            val current = decoded[index++]
                            if (current.isHighSurrogate()) checkOutput(index < decoded.length && decoded[index++].isLowSurrogate())
                            else checkOutput(!current.isLowSurrogate())
                        }
                        return decoded
                    }
                }
            }
            invalidOutput()
        }
        private fun number(): JsonPrimitive {
            val start = cursor
            eat('-')
            if (!eat('0')) {
                checkOutput(peek() in '1'..'9')
                while (peek() in '0'..'9') cursor++
            }
            if (eat('.')) {
                checkOutput(peek() in '0'..'9')
                while (peek() in '0'..'9') cursor++
            }
            if (peek() == 'e' || peek() == 'E') {
                cursor++
                if (peek() == '+' || peek() == '-') cursor++
                checkOutput(peek() in '0'..'9')
                while (peek() in '0'..'9') cursor++
            }
            return try { Json.parseToJsonElement(input.substring(start, cursor)) as JsonPrimitive }
            catch (_: IllegalArgumentException) { invalidOutput() }
        }
        private fun literal(value: String) {
            checkOutput(input.startsWith(value, cursor))
            cursor += value.length
        }
        private fun space() { while (peek() in " \t\r\n") cursor++ }
        private fun peek(): Char = input.getOrNull(cursor) ?: '\u0000'
        private fun eat(ch: Char): Boolean = (peek() == ch).also { if (it) cursor++ }
        private fun expect(ch: Char) { checkOutput(eat(ch)) }
    }
}
