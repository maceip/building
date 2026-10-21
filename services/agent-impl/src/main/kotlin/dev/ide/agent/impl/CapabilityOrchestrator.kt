package dev.ide.agent.impl

import dev.ide.agent.AgentPermissionGate
import dev.ide.agent.AgentTool
import dev.ide.agent.AgentToolRegistry
import dev.ide.agent.AllowAllGate
import dev.ide.agent.ContentPart
import dev.ide.agent.PermissionMode
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import dev.ide.agent.WriteRequest
import dev.ide.agent.toolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Codemode capability orchestration engine (`use_capability`, ported from `wkbin/taixu`'s
 * `CapabilityScriptRunner` and `CapabilityToolGateway`).
 *
 * Allows the agent to query (`list` / `inspect`), invoke (`call`), or run a multi-step capability
 * orchestration script (`script`) that chains, branches, and loops across workspace, runtime, MCP,
 * and self-authored tools in a single LLM turn while enforcing [AgentPermissionGate] and secret redaction
 * on every inner call.
 */
object SecretRedactor {
    private val SECRET_PATTERNS = listOf(
        Regex("""(sk-[a-zA-Z0-9_-]{20,})"""),
        Regex("""(ghp_[a-zA-Z0-9]{20,})"""),
        Regex("""(AIza[0-9A-Za-z_-]{30,})"""),
        Regex("""(?i)(bearer\s+[a-zA-Z0-9._\-]{20,})"""),
    )

    fun redact(text: String): String {
        var result = text
        for (pattern in SECRET_PATTERNS) {
            result = pattern.replace(result) { match ->
                val raw = match.value
                if (raw.lowercase().startsWith("bearer ")) {
                    "Bearer [REDACTED]"
                } else {
                    raw.take(5) + "...[REDACTED]"
                }
            }
        }
        return result
    }
}

class CapabilityOrchestrator(
    private val registryProvider: () -> AgentToolRegistry,
    private val gateProvider: () -> AgentPermissionGate = { AllowAllGate },
    private val maxStepsPerScript: Int = 32,
) {
    fun createUseCapabilityTool(): AgentTool = object : AgentTool {
        override val spec = ToolSpec(
            name = "use_capability",
            description = "Unified capability gateway and Codemode multi-tool script runner. " +
                "Actions: 'list' (list all capabilities), 'inspect' (inspect parameter schemas), " +
                "'call' (invoke one capability), or 'script' (execute a multi-step JSON pipeline with " +
                "\${stepId.output} variable interpolation, 'if_contains' branching, and 'foreach_line' loops " +
                "in a single turn).",
            parameters = toolSchema {
                string("action", "Gateway action.", enum = listOf("list", "inspect", "call", "script"))
                string("capability", "Capability/tool name for 'inspect' or 'call'.", required = false)
                string("args_json", "JSON object arguments for 'call'.", required = false)
                string(
                    "script_json",
                    "For 'script': JSON array of steps (or {\"steps\":[...]}), where each step is " +
                        "{\"id\":\"s1\",\"tool\":\"read_file\",\"args\":{\"path\":\"...\"},\"if_contains\":{\"source\":\"\${prev.output}\",\"substring\":\"...\"},\"foreach_line\":\"\${prev.output}\",\"item_var\":\"item\"}.",
                    required = false,
                )
            },
        )
        // Mutating inner tools are gated individually via gateProvider() inside executeSingleCall.
        override val mutating: Boolean = false

        override fun summarize(args: ToolArgs): String {
            val action = args.optString("action") ?: "list"
            val cap = args.optString("capability")
            return if (!cap.isNullOrBlank()) "use_capability ($action $cap)" else "use_capability ($action)"
        }

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val registry = registryProvider()
            if (registry is DynamicToolRegistry) {
                registry.ensurePersistedToolsLoaded()
            }
            return when (val action = args.string("action")) {
                "list" -> executeList(registry)
                "inspect" -> executeInspect(registry, args.optString("capability").orEmpty())
                "call" -> {
                    val cap = args.optString("capability")
                        ?: return ToolExecutionResult.error("Missing 'capability' for action='call'.")
                    val rawArgs = args.optString("args_json") ?: "{}"
                    executeSingleCall(registry, cap, parseArgsObject(rawArgs))
                }
                "script" -> {
                    val rawScript = args.optString("script_json")
                        ?: return ToolExecutionResult.error("Missing 'script_json' for action='script'.")
                    executeScriptPipeline(registry, rawScript)
                }
                else -> ToolExecutionResult.error("Unknown use_capability action '$action'.")
            }
        }
    }

    private fun executeList(registry: AgentToolRegistry): ToolExecutionResult {
        val all = if (registry is DynamicToolRegistry) registry.allKnownTools() else registry.tools
        val lines = all
            .filter { it.spec.name != "use_capability" }
            .sortedBy { it.spec.name }
            .map { t ->
                val mode = if (t.mutating) "mutating" else "read-only"
                "- ${t.spec.name} [$mode]: ${t.spec.description}"
            }
        return ToolExecutionResult.ok("Registered capabilities (${lines.size}):\n" + lines.joinToString("\n"))
    }

    private fun executeInspect(registry: AgentToolRegistry, namesRaw: String): ToolExecutionResult {
        val names = namesRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return ToolExecutionResult.error("Provide one or more comma-separated capability names in 'capability'.")
        val sb = StringBuilder()
        for (name in names) {
            val tool = registry.find(name)
            if (tool == null) {
                sb.appendLine("- $name: NOT FOUND")
            } else {
                sb.appendLine("### ${tool.spec.name} (mutating=${tool.mutating})")
                sb.appendLine(tool.spec.description)
                sb.appendLine("parameters: ${tool.spec.parameters}")
            }
        }
        return ToolExecutionResult.ok(sb.toString().trimEnd())
    }

    private suspend fun executeSingleCall(
        registry: AgentToolRegistry,
        capabilityName: String,
        argsObj: JsonObject,
    ): ToolExecutionResult {
        if (capabilityName == "use_capability") {
            return ToolExecutionResult.error("Recursive 'use_capability' invocation is not allowed.")
        }
        val tool = registry.find(capabilityName)
            ?: return ToolExecutionResult.error("Unknown capability '$capabilityName'.")
        val toolArgs = JsonToolArgs(argsObj)

        if (tool.mutating) {
            val gate = gateProvider()
            val summary = runCatching { tool.summarize(toolArgs) }.getOrDefault(capabilityName)
            val preview = if (gate.mode == PermissionMode.ASK_EACH) {
                runCatching { tool.preview(toolArgs) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val allowed = gate.authorize(WriteRequest(capabilityName, summary, toolArgs.optString("path"), preview))
            if (!allowed) {
                return ToolExecutionResult.error("Denied by permission policy for mutating capability '$capabilityName'.")
            }
        }

        val res = runCatching { tool.execute(toolArgs) }
            .getOrElse { ToolExecutionResult.error(it.message ?: "Capability '$capabilityName' failed.") }
        return res.copy(content = SecretRedactor.redact(res.content))
    }

    private suspend fun executeScriptPipeline(
        registry: AgentToolRegistry,
        rawScriptJson: String,
    ): ToolExecutionResult {
        val rootElement = runCatching { AgentJson.parseToJsonElement(rawScriptJson) }.getOrElse {
            return ToolExecutionResult.error("Invalid script_json: ${it.message}")
        }
        val stepsArray: JsonArray = when (rootElement) {
            is JsonArray -> rootElement
            is JsonObject -> rootElement["steps"] as? JsonArray
                ?: return ToolExecutionResult.error("script_json object must contain a 'steps' array.")
            else -> return ToolExecutionResult.error("script_json must be a JSON array or an object with 'steps'.")
        }

        val variables = LinkedHashMap<String, String>()
        val transcript = mutableListOf<String>()
        val collectedImages = mutableListOf<ContentPart.Image>()
        var executedCalls = 0
        var hadError = false

        for ((idx, stepElem) in stepsArray.withIndex()) {
            val stepObj = stepElem as? JsonObject
                ?: return ToolExecutionResult.error("Step ${idx + 1} must be a JSON object.")
            val stepId = stepObj["id"].asStr() ?: "step_${idx + 1}"
            val toolName = stepObj["tool"].asStr() ?: stepObj["capability"].asStr()
                ?: return ToolExecutionResult.error("Step '$stepId' is missing 'tool'.")

            // Optional conditional check: if_contains { source, substring }
            val ifContains = stepObj["if_contains"] as? JsonObject
            if (ifContains != null) {
                val source = interpolateString(ifContains["source"].asStr().orEmpty(), variables)
                val substring = interpolateString(ifContains["substring"].asStr().orEmpty(), variables)
                if (!source.contains(substring)) {
                    transcript += "[$stepId] skipped (condition not met)"
                    variables["$stepId.output"] = ""
                    continue
                }
            }

            val foreachSourceRaw = stepObj["foreach_line"].asStr()
            val itemVar = stepObj["item_var"].asStr() ?: "item"
            val rawArgsObj = (stepObj["args"] as? JsonObject) ?: JsonObject(emptyMap())
            val stopOnError = stepObj["stop_on_error"].asBool() ?: true

            if (foreachSourceRaw != null) {
                val items = interpolateString(foreachSourceRaw, variables)
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                val stepOutputs = mutableListOf<String>()
                for (item in items) {
                    if (executedCalls >= maxStepsPerScript) {
                        return ToolExecutionResult.error("Capability script exceeded maximum step budget ($maxStepsPerScript).")
                    }
                    executedCalls++
                    val scopedVars = LinkedHashMap(variables).apply { put(itemVar, item) }
                    val resolvedArgs = interpolateJsonObject(rawArgsObj, scopedVars)
                    val res = executeSingleCall(registry, toolName, resolvedArgs)
                    stepOutputs += res.content
                    collectedImages += res.images
                    if (res.isError) {
                        hadError = true
                        if (stopOnError) break
                    }
                }
                val combinedOut = stepOutputs.joinToString("\n")
                variables["$stepId.output"] = combinedOut
                variables[stepId] = combinedOut
                transcript += "[$stepId: $toolName x${items.size}]\n$combinedOut"
                if (hadError && stopOnError) break
            } else {
                if (executedCalls >= maxStepsPerScript) {
                    return ToolExecutionResult.error("Capability script exceeded maximum step budget ($maxStepsPerScript).")
                }
                executedCalls++
                val resolvedArgs = interpolateJsonObject(rawArgsObj, variables)
                val res = executeSingleCall(registry, toolName, resolvedArgs)
                variables["$stepId.output"] = res.content
                variables[stepId] = res.content
                collectedImages += res.images
                transcript += "[$stepId: $toolName]\n${res.content}"
                if (res.isError) {
                    hadError = true
                    if (stopOnError) break
                }
            }
        }

        val summary = buildString {
            appendLine("Capability script executed $executedCalls call(s):")
            append(transcript.joinToString("\n\n"))
        }.trimEnd()
        return ToolExecutionResult(
            content = summary,
            isError = hadError,
            images = collectedImages.take(4),
        )
    }

    private fun interpolateJsonObject(obj: JsonObject, vars: Map<String, String>): JsonObject {
        val mapped = LinkedHashMap<String, JsonElement>()
        for ((k, v) in obj) {
            mapped[k] = interpolateElement(v, vars)
        }
        return JsonObject(mapped)
    }

    private fun interpolateElement(elem: JsonElement, vars: Map<String, String>): JsonElement = when (elem) {
        is JsonPrimitive -> if (elem.isString) JsonPrimitive(interpolateString(elem.content, vars)) else elem
        is JsonArray -> JsonArray(elem.map { interpolateElement(it, vars) })
        is JsonObject -> interpolateJsonObject(elem, vars)
    }

    private fun interpolateString(template: String, vars: Map<String, String>): String {
        return VAR_REGEX.replace(template) { match ->
            val key = match.groupValues[1].trim()
            vars[key] ?: match.value
        }
    }

    companion object {
        private val VAR_REGEX = Regex("""\$\{([^}]+)\}""")
    }
}
