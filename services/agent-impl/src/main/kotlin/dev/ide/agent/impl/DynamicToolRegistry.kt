package dev.ide.agent.impl

import dev.ide.agent.AgentTool
import dev.ide.agent.AgentToolRegistry
import dev.ide.agent.AgentWorkspace
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import dev.ide.agent.toolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent definition for an agent-authored script tool (from `Past-da-king/agent`).
 *
 * Stored under `<workspace>/.agent/tools/<name>.json` so tools authored by the agent on the phone
 * survive app restarts and can be invoked like native built-in tools.
 */
@Serializable
data class ScriptToolDefinition(
    val name: String,
    val description: String,
    val language: String = "node",
    val script: String,
    val parametersSchema: String = DEFAULT_SCHEMA,
    val mutating: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
) {
    companion object {
        const val DEFAULT_SCHEMA =
            """{"type":"object","properties":{"input":{"type":"string","description":"Optional input string or JSON payload"}},"required":[],"additionalProperties":true}"""
    }
}

/**
 * Learned web-app tool & recipe store + `PageJs` WebView network shim (from `Past-da-king/agent`'s
 * `Learned.kt` and `PageJs.kt`).
 */
@Serializable
data class LearnedWebTool(
    val name: String,
    val description: String,
    val jsBody: String,
    val parametersSchema: String = ScriptToolDefinition.DEFAULT_SCHEMA,
)

@Serializable
data class LearnedWebApp(
    val domain: String,
    val notes: String = "",
    val apis: List<String> = emptyList(),
    val tools: List<LearnedWebTool> = emptyList(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

object PageJs {
    /**
     * Injected into WebView pages before navigation so the agent can inspect live `fetch`, `XMLHttpRequest`,
     * and `WebSocket` traffic via `window.__aosNet` and synthesize direct JS tools (`LearnedWebTool`).
     */
    const val NET_SHIM: String = """
(function() {
  if (window.__aosNet) return;
  const log = [];
  const push = (entry) => {
    log.push(Object.assign({ ts: Date.now() }, entry));
    if (log.length > 200) log.shift();
  };
  window.__aosNet = {
    entries: log,
    clear: () => { log.length = 0; },
    recent: (n = 20) => log.slice(-n)
  };
  const origFetch = window.fetch;
  if (typeof origFetch === 'function') {
    window.fetch = async function(input, init) {
      const url = typeof input === 'string' ? input : (input && input.url) || '';
      const method = (init && init.method) || 'GET';
      try {
        const res = await origFetch.apply(this, arguments);
        push({ kind: 'fetch', method, url, status: res.status });
        return res;
      } catch (err) {
        push({ kind: 'fetch', method, url, error: String(err) });
        throw err;
      }
    };
  }
  const OrigXHR = window.XMLHttpRequest;
  if (OrigXHR) {
    const open = OrigXHR.prototype.open;
    OrigXHR.prototype.open = function(method, url) {
      this.__aosMethod = method;
      this.__aosUrl = url;
      return open.apply(this, arguments);
    };
    const send = OrigXHR.prototype.send;
    OrigXHR.prototype.send = function() {
      this.addEventListener('loadend', () => {
        push({ kind: 'xhr', method: this.__aosMethod || 'GET', url: this.__aosUrl || '', status: this.status });
      });
      return send.apply(this, arguments);
    };
  }
})();
"""

    /**
     * Wraps a learned JS snippet in an async IIFE that receives `args` and returns a JSON-serialized payload.
     */
    fun wrapScript(jsBody: String, argsJson: String): String = """
(async function(args) {
  try {
    const __res = await (async () => { $jsBody })();
    return JSON.stringify({ ok: true, result: __res ?? null });
  } catch (e) {
    return JSON.stringify({ ok: false, error: String(e && e.stack || e) });
  }
})($argsJson)
""".trimIndent()
}

/**
 * Dynamic, self-extending [AgentToolRegistry] with progressive tool exposure (`tool_search` / `tool_open`).
 *
 * Because [AgentLoop] calls [specs] on every iteration, any tool registered via `register_script_tool`
 * or opened via `tool_open` during iteration `N` is immediately offered in the model's tool declarations
 * on iteration `N + 1`.
 */
class DynamicToolRegistry(
    private val workspace: AgentWorkspace,
    baseTools: List<AgentTool>,
    deferredTools: List<AgentTool> = emptyList(),
) : AgentToolRegistry {

    private val coreByName = LinkedHashMap<String, AgentTool>()
    private val deferredByName = ConcurrentHashMap<String, AgentTool>()
    private val openedDeferredNames = ConcurrentHashMap.newKeySet<String>()
    private val scriptDefinitions = ConcurrentHashMap<String, ScriptToolDefinition>()
    private val scriptToolsByName = ConcurrentHashMap<String, AgentTool>()
    @Volatile private var persistedLoaded = false

    init {
        for (tool in baseTools) {
            coreByName[tool.spec.name] = tool
        }
        for (tool in deferredTools) {
            deferredByName[tool.spec.name] = tool
        }
        val progressiveTools = progressiveExposureTools()
        for (tool in progressiveTools) {
            coreByName[tool.spec.name] = tool
        }
    }

    override val tools: List<AgentTool>
        get() {
            val result = ArrayList<AgentTool>(coreByName.size + openedDeferredNames.size + scriptToolsByName.size)
            result.addAll(coreByName.values)
            for (name in openedDeferredNames.sorted()) {
                deferredByName[name]?.let { if (it.spec.name !in coreByName) result.add(it) }
            }
            for ((name, tool) in scriptToolsByName.entries.sortedBy { it.key }) {
                if (name !in coreByName) result.add(tool)
            }
            return result
        }

    /** All known tools across core, deferred, and self-authored script registries. */
    fun allKnownTools(): List<AgentTool> {
        val map = LinkedHashMap<String, AgentTool>()
        coreByName.forEach { (k, v) -> map[k] = v }
        deferredByName.forEach { (k, v) -> map.putIfAbsent(k, v) }
        scriptToolsByName.forEach { (k, v) -> map[k] = v }
        return map.values.toList()
    }

    override fun find(name: String): AgentTool? =
        scriptToolsByName[name] ?: coreByName[name] ?: deferredByName[name]

    fun registerCoreTool(tool: AgentTool) {
        coreByName[tool.spec.name] = tool
    }

    fun registerDeferredTool(tool: AgentTool) {
        deferredByName[tool.spec.name] = tool
    }

    fun openTool(name: String): AgentTool? {
        val found = deferredByName[name] ?: scriptToolsByName[name] ?: coreByName[name] ?: return null
        if (deferredByName.containsKey(name)) {
            openedDeferredNames.add(name)
        }
        return found
    }

    fun searchTools(query: String, limit: Int = 20): List<ToolSpec> {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val candidates = allKnownTools().map { it.spec }
        if (tokens.isEmpty()) return candidates.take(limit)
        return candidates
            .mapNotNull { spec ->
                val haystack = "${spec.name} ${spec.description}".lowercase()
                val score = tokens.sumOf { token ->
                    when {
                        spec.name.lowercase() == token -> 10
                        spec.name.lowercase().contains(token) -> 5
                        haystack.contains(token) -> 2
                        else -> 0
                    }
                }
                if (score > 0) spec to score else null
            }
            .sortedWith(compareByDescending<Pair<ToolSpec, Int>> { it.second }.thenBy { it.first.name })
            .take(limit)
            .map { it.first }
    }

    suspend fun registerScriptTool(def: ScriptToolDefinition, persist: Boolean = true): AgentTool {
        require(def.name.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]{1,63}"))) {
            "Tool name '${def.name}' must match [a-zA-Z][a-zA-Z0-9_-]{1,63}"
        }
        val tool = createScriptTool(def)
        scriptDefinitions[def.name] = def
        scriptToolsByName[def.name] = tool
        if (persist) {
            runCatching {
                workspace.writeFile(
                    "$TOOLS_DIR/${def.name}.json",
                    AgentJson.encodeToString(def),
                )
            }
        }
        return tool
    }

    suspend fun deleteScriptTool(name: String): Boolean {
        val removed = scriptToolsByName.remove(name) != null
        scriptDefinitions.remove(name)
        runCatching { workspace.deletePath("$TOOLS_DIR/$name.json") }
        return removed
    }

    suspend fun listScriptTools(): List<ScriptToolDefinition> {
        ensurePersistedToolsLoaded()
        return scriptDefinitions.values.sortedBy { it.name }
    }

    suspend fun ensurePersistedToolsLoaded() {
        if (persistedLoaded) return
        persistedLoaded = true
        val entries = runCatching { workspace.listDir(TOOLS_DIR) }.getOrDefault(emptyList())
        for (entry in entries) {
            if (!entry.isDirectory && entry.name.endsWith(".json")) {
                runCatching {
                    val raw = workspace.readFile("$TOOLS_DIR/${entry.name}")
                    val def = AgentJson.decodeFromString<ScriptToolDefinition>(raw)
                    scriptDefinitions[def.name] = def
                    scriptToolsByName[def.name] = createScriptTool(def)
                }
            }
        }
    }

    private fun createScriptTool(def: ScriptToolDefinition): AgentTool = object : AgentTool {
        override val spec = ToolSpec(
            name = def.name,
            description = "[Self-authored ${def.language} tool] ${def.description}",
            parameters = def.parametersSchema.ifBlank { ScriptToolDefinition.DEFAULT_SCHEMA },
        )
        override val mutating: Boolean = def.mutating
        override fun summarize(args: ToolArgs): String = "${def.name} (${def.language})"

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val res = workspace.executeScript(
                language = def.language,
                code = def.script,
                args = listOf(args.raw()),
                env = mapOf(
                    "TOOL_NAME" to def.name,
                    "TOOL_ARGS_JSON" to args.raw(),
                ),
            )
            val combined = buildString {
                if (res.stdout.isNotBlank()) appendLine(res.stdout.trimEnd())
                if (res.stderr.isNotBlank()) {
                    appendLine("stderr:")
                    appendLine(res.stderr.trimEnd())
                }
                if (res.touchedFiles.isNotEmpty()) {
                    appendLine("Touched files: ${res.touchedFiles.joinToString(", ")}")
                }
                if (isEmpty()) append("(exit code ${res.exitCode})")
            }.trimEnd()
            val images = collectGeneratedImages(workspace, res.touchedFiles)
            return ToolExecutionResult(
                content = combined,
                isError = res.exitCode != 0 || res.timedOut,
                images = images,
            )
        }
    }

    private fun progressiveExposureTools(): List<AgentTool> = listOf(
        object : AgentTool {
            override val spec = ToolSpec(
                name = "tool_search",
                description = "Search all available built-in, deferred, MCP, and self-authored tools by keyword.",
                parameters = toolSchema {
                    string("query", "Search keywords (e.g. 'script', 'card', 'diagnostic', 'runtime').", required = false)
                    integer("limit", "Maximum results to return (default 20).", required = false)
                },
            )
            override fun summarize(args: ToolArgs): String = "tool_search \"${args.optString("query").orEmpty()}\""
            override suspend fun execute(args: ToolArgs): ToolExecutionResult {
                ensurePersistedToolsLoaded()
                val query = args.optString("query").orEmpty()
                val limit = (args.optInt("limit") ?: 20).coerceIn(1, 100)
                val hits = searchTools(query, limit)
                if (hits.isEmpty()) return ToolExecutionResult.ok("No matching tools found for '$query'.")
                val activeNames = tools.map { it.spec.name }.toSet()
                val text = hits.joinToString("\n") { s ->
                    val state = if (s.name in activeNames) "active" else "deferred (call tool_open to activate)"
                    "- ${s.name} [$state]: ${s.description}"
                }
                return ToolExecutionResult.ok(text)
            }
        },
        object : AgentTool {
            override val spec = ToolSpec(
                name = "tool_open",
                description = "Activate one or more tools by name so their full JSON schemas are exposed in subsequent turns.",
                parameters = toolSchema {
                    string("name", "Tool name to open (or comma-separated list of tool names).")
                },
            )
            override fun summarize(args: ToolArgs): String = "tool_open ${args.optString("name").orEmpty()}"
            override suspend fun execute(args: ToolArgs): ToolExecutionResult {
                ensurePersistedToolsLoaded()
                val names = args.string("name").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val opened = mutableListOf<ToolSpec>()
                val missing = mutableListOf<String>()
                for (n in names) {
                    val tool = openTool(n)
                    if (tool != null) opened += tool.spec else missing += n
                }
                val sb = StringBuilder()
                if (opened.isNotEmpty()) {
                    sb.appendLine("Activated ${opened.size} tool(s):")
                    for (s in opened) {
                        sb.appendLine("- ${s.name}: ${s.description}")
                        sb.appendLine("  schema: ${s.parameters}")
                    }
                }
                if (missing.isNotEmpty()) {
                    sb.appendLine("Unknown tool(s): ${missing.joinToString(", ")}")
                }
                return ToolExecutionResult(
                    content = sb.toString().trimEnd(),
                    isError = opened.isEmpty() && missing.isNotEmpty(),
                )
            }
        },
    )

    companion object {
        const val TOOLS_DIR = ".agent/tools"
        const val LEARNED_DIR = ".agent/learned"
    }
}
