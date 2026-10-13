package dev.ide.workbench.scenario

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Loads one explicitly selected local file; no issue text, discovery result or download is executable. */
object RegressionScenarioParser {
    fun load(projectRoot: Path, relativePath: String): ReviewedRegressionScenario {
        val root = ScenarioFiles.root(projectRoot)
        requireSafe(relativePath.endsWith(".json"), "Select a local .json scenario file")
        val bytes = ScenarioFiles.read(root, relativePath, ScenarioLimits.MAX_FILE_BYTES)
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { throw ScenarioValidationException("Scenario must be valid UTF-8") }
        val scenario = parse(text)
        val entryPath = when {
            scenario.targetId.startsWith("npm:") -> "package.json"
            else -> scenario.targetId.substringAfter(':')
        }
        val paths = mutableListOf(entryPath)
        paths += scenario.artifacts
        val config = ScenarioFiles.resolve(root, ".building/runtime.json", mustExist = false)
        if (Files.exists(config, NOFOLLOW_LINKS)) paths += ".building/runtime.json"
        val artifacts = ScenarioFiles.artifacts(root, paths)
        val summary = buildString {
            appendLine("${scenario.title} (${scenario.id})")
            appendLine("Target: ${scenario.targetId}")
            appendLine("${scenario.steps.size} steps; ${scenario.limits.runTimeoutMs} ms overall + at most ${ScenarioLimits.CLEANUP_MS} ms cleanup; ${scenario.limits.maxOutputBytes} bytes per response/stream")
            scenario.steps.forEach { step ->
                val action = when (step) {
                    is ScenarioStep.Start -> "start; poll owned preview GET ${step.readyPath.substringBefore('?')} for HTTP ${step.readyStatus}"
                    is ScenarioStep.Stop -> "stop owned process/preview"
                    is ScenarioStep.Command -> "run selected target to exit; expect ${step.expect.exitCode}; check reviewed streams"
                    is ScenarioStep.Http -> "${step.method} ${step.path.substringBefore('?')}; expect HTTP ${step.expect.status}; ${step.expect.json.size} JSON checks"
                }
                appendLine("${step.id}: $action (${step.timeoutMs} ms)")
            }
            appendLine("${artifacts.size} entry/config/explicit artifacts hashed; imports and entire dependency trees are NOT automatically pinned.")
            append("Reviewed project code can access your app's files/network. This is NOT an OS sandbox. Only the runner's own HTTP requests are scoped to its owned loopback preview.")
        }
        return ReviewedRegressionScenario(root, relativePath, ScenarioFiles.sha256(bytes), scenario, artifacts, ScenarioFiles.artifactHash(artifacts), summary)
    }

    private fun parse(text: String): RegressionScenario {
        StrictScenarioJson.validate(text)
        val root = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
            ?: throw ScenarioValidationException("Scenario must be a valid JSON object")
        root.keysOnly("version", "id", "title", "targetId", "limits", "artifacts", "steps")
        requireSafe(root.integer("version") == 1L, "Only scenario version 1 is supported")
        val id = root.string("id", 64).also { identifier(it) }
        val title = root.string("title", 120).also { requireSafe(it.none { c -> c.code < 32 }, "Title must be a single line") }
        val target = root.string("targetId", 240)
        val prefix = target.substringBefore(':')
        val targetPath = target.substringAfter(':', "")
        requireSafe(prefix in setOf("node", "python", "npm", "static") && targetPath.isNotEmpty(), "Select a Node, Python, npm script, or static preview target; shell commands are not supported")
        if (prefix == "npm") requireSafe(targetPath.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,79}")), "Invalid npm script target")
        else ScenarioFiles.validateRelative(targetPath)
        val limitsValue = root.optionalObject("limits") ?: JsonObject(emptyMap())
        limitsValue.keysOnly("runTimeoutMs", "stepTimeoutMs", "maxOutputBytes")
        val limits = ScenarioLimits(
            limitsValue.optionalInteger("runTimeoutMs") ?: 120_000,
            limitsValue.optionalInteger("stepTimeoutMs") ?: 10_000,
            (limitsValue.optionalInteger("maxOutputBytes") ?: 32_768).also { requireSafe(it in 256..ScenarioLimits.MAX_OUTPUT_BYTES.toLong(), "Output bound must be 256–65536 bytes") }.toInt(),
        )
        requireSafe(limits.runTimeoutMs in 100..ScenarioLimits.MAX_RUN_MS, "Run timeout must be 100–180000 ms")
        requireSafe(limits.stepTimeoutMs in 100..minOf(limits.runTimeoutMs, ScenarioLimits.MAX_STEP_MS), "Step timeout exceeds bounds")
        val artifacts = root.optionalArray("artifacts")?.map { stringValue(it, 240).also(ScenarioFiles::validateRelative) }.orEmpty()
        requireSafe(artifacts.size <= 16 && artifacts.distinct().size == artifacts.size, "At most 16 distinct additional source artifacts are allowed")
        val stepValues = root["steps"] as? JsonArray ?: throw ScenarioValidationException("steps must be an array")
        requireSafe(stepValues.size in 1..ScenarioLimits.MAX_STEPS, "Scenario must contain 1–32 steps")
        val steps = stepValues.map { raw ->
            val value = raw as? JsonObject ?: throw ScenarioValidationException("Each step must be an object")
            val stepId = value.string("id", 64).also { identifier(it) }
            val timeout = value.optionalInteger("timeoutMs") ?: limits.stepTimeoutMs
            requireSafe(timeout in 100..minOf(limits.runTimeoutMs, ScenarioLimits.MAX_STEP_MS), "Step timeout exceeds bounds")
            when (value.string("type", 16)) {
                "start" -> {
                    value.keysOnly("id", "type", "timeoutMs", "ready")
                    val ready = value["ready"] as? JsonObject ?: throw ScenarioValidationException("Start requires a reviewed readiness GET")
                    ready.keysOnly("path", "status")
                    ScenarioStep.Start(stepId, timeout, ready.string("path", 2048).also(::validateHttpPath), status(ready.integer("status")))
                }
                "stop" -> {
                    value.keysOnly("id", "type", "timeoutMs")
                    ScenarioStep.Stop(stepId, timeout)
                }
                "command" -> {
                    value.keysOnly("id", "type", "timeoutMs", "expect")
                    val expect = value["expect"] as? JsonObject ?: throw ScenarioValidationException("Command requires assertions")
                    expect.keysOnly("exitCode", "stdout", "stderr")
                    val exit = expect.integer("exitCode")
                    requireSafe(exit in -255..255, "Exit assertion must be in -255–255")
                    ScenarioStep.Command(stepId, timeout, CommandExpectation(exit.toInt(), textExpectation(expect, "stdout"), textExpectation(expect, "stderr")))
                }
                "http" -> {
                    value.keysOnly("id", "type", "timeoutMs", "method", "path", "jsonBody", "expect")
                    val method = value.string("method", 8)
                    requireSafe(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"), "Unsupported HTTP method")
                    val body = value["jsonBody"]
                    requireSafe(body == null || method in setOf("POST", "PUT", "PATCH", "DELETE"), "This HTTP method must not include a request body")
                    requireSafe(body == null || body.toString().toByteArray().size <= 16_384, "Request body exceeds 16 KiB")
                    val expect = value["expect"] as? JsonObject ?: throw ScenarioValidationException("HTTP step requires assertions")
                    expect.keysOnly("status", "body", "json", "maxResponseTimeMs")
                    val json = expect.optionalArray("json")?.map { item ->
                        val entry = item as? JsonObject ?: throw ScenarioValidationException("JSON assertion must be an object")
                        entry.keysOnly("pointer", "equals")
                        val pointer = entry.string("pointer", 256, allowEmpty = true)
                        requireSafe(pointer.isEmpty() || pointer.startsWith('/') && !Regex("~(?![01])").containsMatchIn(pointer), "Use an RFC 6901 JSON pointer")
                        JsonExpectation(pointer, entry["equals"] ?: throw ScenarioValidationException("JSON assertion requires equals (null is allowed)"))
                    }.orEmpty()
                    requireSafe(json.size <= 16 && json.map { it.pointer }.distinct().size == json.size, "At most 16 distinct JSON pointers are allowed")
                    val responseTime = expect.optionalInteger("maxResponseTimeMs")
                    requireSafe(responseTime == null || responseTime in 1..timeout, "Response-time assertion must not exceed the step timeout")
                    ScenarioStep.Http(stepId, timeout, method, value.string("path", 2048).also(::validateHttpPath), body,
                        HttpExpectation(status(expect.integer("status")), textExpectation(expect, "body"), json, responseTime))
                }
                else -> throw ScenarioValidationException("Unsupported step type; only start, stop, command, http are executable")
            }
        }
        requireSafe(steps.map { it.id }.distinct().size == steps.size, "Step IDs must be unique")
        var active = false
        steps.forEach { step ->
            when (step) {
                is ScenarioStep.Start -> { requireSafe(!active, "Stop the session before another start"); active = true }
                is ScenarioStep.Stop -> { requireSafe(active, "Stop requires an active start"); active = false }
                is ScenarioStep.Command -> requireSafe(!active && prefix != "static", "Commands require no active server and a process target")
                is ScenarioStep.Http -> requireSafe(active, "HTTP requires an active owned start")
            }
        }
        requireSafe(steps.any { it is ScenarioStep.Http || it is ScenarioStep.Command }, "A scenario must include an actual command or HTTP assertion")
        return RegressionScenario(id, title, target, limits, steps, artifacts)
    }

    internal fun validateHttpPath(path: String) {
        requireSafe(path.length in 1..2048 && path.startsWith('/') && !path.startsWith("//") &&
            path.all { it in '!'..'~' } && path.none { it in "\\%#@:<>\"'`" } &&
            path.substringBefore('?').split('/').none { it == "." || it == ".." },
            "HTTP paths must be origin-relative ASCII without URLs, encoded paths, credentials or traversal")
    }

    private fun identifier(value: String) = requireSafe(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")), "IDs must use letters, numbers, dash or underscore")
    private fun status(value: Long): Int {
        requireSafe(value in 100..599 && value !in 300..399, "HTTP assertions must be 100–599 excluding redirects")
        return value.toInt()
    }
    private fun textExpectation(owner: JsonObject, key: String): TextExpectation? {
        val value = owner.optionalObject(key) ?: return null
        value.keysOnly("equals", "contains")
        val exact = value["equals"]?.let { stringValue(it, 4096, allowEmpty = true) }
        val contains = value.optionalArray("contains")?.map { stringValue(it, 4096) }.orEmpty()
        requireSafe(contains.size <= 8 && (exact != null || contains.isNotEmpty()), "Text assertions need equals or 1–8 contains literals (no regex/scripts)")
        return TextExpectation(exact, contains)
    }
}

private fun JsonObject.keysOnly(vararg keys: String) = requireSafe(this.keys.all { it in keys }, "Unknown field in scenario schema")
private fun JsonObject.string(key: String, max: Int, allowEmpty: Boolean = false): String = stringValue(this[key], max, allowEmpty)
private fun stringValue(value: JsonElement?, max: Int, allowEmpty: Boolean = false): String {
    val primitive = value as? JsonPrimitive
    requireSafe(primitive != null && primitive.isString, "Expected a JSON string")
    val text = primitive!!.content
    requireSafe(text.length <= max && (allowEmpty || text.isNotEmpty()) && !text.contains('\u0000'), "String exceeds bounds or contains NUL")
    return text
}
private fun JsonObject.optionalInteger(key: String): Long? {
    if (!containsKey(key)) return null
    val value = get(key) as? JsonPrimitive
    requireSafe(value != null && !value.isString && value.longOrNull != null, "Expected an integer")
    return value!!.long
}
private fun JsonObject.integer(key: String): Long = optionalInteger(key) ?: throw ScenarioValidationException("Missing required integer")
private fun JsonObject.optionalObject(key: String): JsonObject? {
    if (!containsKey(key)) return null
    return this[key] as? JsonObject ?: throw ScenarioValidationException("Expected a JSON object")
}
private fun JsonObject.optionalArray(key: String): JsonArray? {
    if (!containsKey(key)) return null
    return this[key] as? JsonArray ?: throw ScenarioValidationException("Expected a JSON array")
}

/** Bound nesting and reject ambiguous duplicate keys before the JSON library materializes the tree. */
internal object StrictScenarioJson {
    fun validate(text: String) {
        var index = 0
        fun whitespace() { while (index < text.length && text[index] in " \r\n\t") index++ }
        fun string(): String {
            val start = index
            requireSafe(index < text.length && text[index++] == '"', "Malformed JSON string")
            var escaped = false
            while (index < text.length) {
                val ch = text[index++]
                if (escaped) { escaped = false; continue }
                if (ch == '\\') { escaped = true; continue }
                if (ch == '"') {
                    return try { Json.parseToJsonElement(text.substring(start, index)).jsonPrimitive.content }
                    catch (_: Exception) { throw ScenarioValidationException("Malformed JSON string") }
                }
                requireSafe(ch.code >= 32, "Malformed JSON string")
            }
            throw ScenarioValidationException("Unterminated JSON string")
        }
        fun value(depth: Int) {
            requireSafe(depth <= 16, "JSON nesting exceeds 16 levels")
            whitespace()
            requireSafe(index < text.length, "Incomplete JSON")
            when (text[index]) {
                '{', '[' -> {
                    val objectValue = text[index++] == '{'
                    val end = if (objectValue) '}' else ']'
                    val keys = mutableSetOf<String>()
                    whitespace()
                    if (index < text.length && text[index] == end) { index++; return }
                    while (true) {
                        whitespace()
                        if (objectValue) {
                            requireSafe(keys.add(string()), "Duplicate JSON object key")
                            whitespace()
                            requireSafe(index < text.length && text[index++] == ':', "Missing JSON colon")
                        }
                        value(depth + 1)
                        whitespace()
                        requireSafe(index < text.length, "Incomplete JSON")
                        if (text[index] == end) { index++; break }
                        requireSafe(text[index++] == ',', "Missing JSON comma")
                    }
                }
                '"' -> string()
                else -> {
                    val start = index
                    while (index < text.length && text[index] !in ",]} \r\n\t") index++
                    val literal = text.substring(start, index)
                    requireSafe(literal in setOf("true", "false", "null") || literal.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")), "Invalid JSON literal")
                }
            }
        }
        value(0)
        whitespace()
        requireSafe(index == text.length, "Trailing JSON content")
    }
}
