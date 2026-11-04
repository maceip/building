package dev.ide.agent.impl

import dev.ide.agent.AgentTool
import dev.ide.agent.AgentWorkspace
import dev.ide.agent.ContentPart
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import dev.ide.agent.toolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.Base64

/**
 * Live UI card with state placeholders (`{{key}}`) and optional scheduled background script refresh
 * (ported from `Past-da-king/agent`'s `CardTools.kt` and `CardScripts.kt`).
 */
@Serializable
data class AgentLiveCard(
    val id: String,
    val title: String,
    val template: String,
    val state: Map<String, String> = emptyMap(),
    val refreshScript: String? = null,
    val refreshLanguage: String = "node",
    val everyMinutes: Int = 0,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    /** Renders [template] by substituting `{{key}}` placeholders from [state]. */
    fun render(): String {
        var out = template
        for ((k, v) in state) {
            out = out.replace("{{$k}}", v)
        }
        return out
    }
}

/**
 * Self-iteration / self-upgrade state record (ported from `wkbin/taixu`'s `CustomIterationBootstrap`
 * and `SelfUpgradeCoordinator`).
 */
@Serializable
data class SelfIterationManifest(
    val iterationId: String,
    val branchName: String,
    val goal: String,
    val buildCommand: String = "./gradlew :ide-android:assembleDebug",
    val artifactPath: String? = null,
    val artifactSha256: String? = null,
    val status: String = "BOOTSTRAPPED",
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

internal suspend fun collectGeneratedImages(
    workspace: AgentWorkspace,
    touchedFiles: List<String>,
    maxImages: Int = 4,
): List<ContentPart.Image> {
    val images = mutableListOf<ContentPart.Image>()
    for (path in touchedFiles) {
        if (images.size >= maxImages) break
        val lower = path.lowercase()
        val mime = when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            else -> null
        } ?: continue
        val bytes = runCatching { workspace.readBytes(path) }.getOrNull() ?: continue
        if (bytes.isEmpty() || bytes.size > 5 * 1024 * 1024) continue
        val b64 = Base64.getEncoder().encodeToString(bytes)
        images += ContentPart.Image(mediaType = mime, data = b64)
    }
    return images
}

/**
 * Constructs the P0 + P1 self-extending agent tools bound to [workspace] and [registry].
 */
fun selfExtendingTools(
    workspace: AgentWorkspace,
    registry: DynamicToolRegistry,
): List<AgentTool> = listOf(
    // 1. run_code — the agent's private on-device code execution workspace
    object : AgentTool {
        override val spec = ToolSpec(
            name = "run_code",
            description = "Execute a Node.js ('node'), Python ('python'), or Shell ('shell') script in the on-device " +
                "workspace. Returns stdout, stderr, exit code, modified files, and attaches any generated images " +
                "(.png/.jpg/.webp) back to the conversation.",
            parameters = toolSchema {
                string("language", "Script runtime: 'node', 'python', or 'shell'.", enum = listOf("node", "python", "shell"))
                string("code", "Full source code of the script to execute.")
                stringArray("args", "Optional command-line arguments passed to the script.", required = false)
                integer("timeout_ms", "Execution timeout in milliseconds (default 60000).", required = false)
            },
        )
        override val mutating: Boolean = true
        override fun summarize(args: ToolArgs): String = "run_code (${args.optString("language") ?: "script"})"

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val lang = args.string("language")
            val code = args.string("code")
            val scriptArgs = args.stringList("args")
            val timeoutMs = (args.optInt("timeout_ms") ?: 60_000).toLong().coerceIn(1_000L, 300_000L)

            val res = workspace.executeScript(
                language = lang,
                code = code,
                args = scriptArgs,
                timeoutMs = timeoutMs,
            )
            val images = collectGeneratedImages(workspace, res.touchedFiles)
            val report = buildString {
                appendLine("exit_code: ${res.exitCode}${if (res.timedOut) " (TIMED OUT)" else ""}")
                if (res.stdout.isNotBlank()) {
                    appendLine("stdout:")
                    appendLine(res.stdout.trimEnd())
                }
                if (res.stderr.isNotBlank()) {
                    appendLine("stderr:")
                    appendLine(res.stderr.trimEnd())
                }
                if (res.touchedFiles.isNotEmpty()) {
                    appendLine("touched_files: ${res.touchedFiles.joinToString(", ")}")
                }
            }.trimEnd()
            return ToolExecutionResult(
                content = report,
                isError = res.exitCode != 0 || res.timedOut,
                images = images,
            )
        }
    },

    // 2. register_script_tool — write & register a persistent callable tool on the phone
    object : AgentTool {
        override val spec = ToolSpec(
            name = "register_script_tool",
            description = "Write and register a new persistent tool in Node.js, Python, or Shell under .agent/tools/. " +
                "Once registered, the tool is immediately callable in the next turn and persists across sessions. " +
                "Inside the script, read arguments from process.env.TOOL_ARGS_JSON / os.environ['TOOL_ARGS_JSON'] or argv[1].",
            parameters = toolSchema {
                string("name", "Unique tool identifier (letters, digits, underscores, hyphens).")
                string("description", "Clear description of what the tool does and when to call it.")
                string("language", "Runtime language for the script.", enum = listOf("node", "python", "shell"))
                string("script", "Source code of the tool script.")
                string("parameters_schema", "Optional JSON Schema object string for the tool's parameters.", required = false)
                boolean("mutating", "Whether calling this tool modifies project files (default true).", required = false)
            },
        )
        override val mutating: Boolean = true
        override fun summarize(args: ToolArgs): String = "register_script_tool ${args.optString("name").orEmpty()}"

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val def = ScriptToolDefinition(
                name = args.string("name").trim(),
                description = args.string("description").trim(),
                language = args.string("language").trim().lowercase(),
                script = args.string("script"),
                parametersSchema = args.optString("parameters_schema")?.takeIf { it.isNotBlank() }
                    ?: ScriptToolDefinition.DEFAULT_SCHEMA,
                mutating = args.optBoolean("mutating") ?: true,
            )
            val registered = registry.registerScriptTool(def, persist = true)
            return ToolExecutionResult.ok(
                "Registered self-authored tool '${registered.spec.name}' (${def.language}). " +
                    "Saved to ${DynamicToolRegistry.TOOLS_DIR}/${def.name}.json and active in tool registry.",
            )
        }
    },

    // 3. list_script_tools
    object : AgentTool {
        override val spec = ToolSpec(
            name = "list_script_tools",
            description = "List all self-authored script tools registered in the workspace.",
            parameters = toolSchema { },
        )
        override fun summarize(args: ToolArgs): String = "list_script_tools"

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val list = registry.listScriptTools()
            if (list.isEmpty()) return ToolExecutionResult.ok("No self-authored script tools registered yet.")
            val text = list.joinToString("\n") { def ->
                "- ${def.name} [${def.language}, mutating=${def.mutating}]: ${def.description}"
            }
            return ToolExecutionResult.ok(text)
        }
    },

    // 4. delete_script_tool
    object : AgentTool {
        override val spec = ToolSpec(
            name = "delete_script_tool",
            description = "Remove a previously registered self-authored script tool.",
            parameters = toolSchema {
                string("name", "Name of the script tool to delete.")
            },
        )
        override val mutating: Boolean = true
        override fun summarize(args: ToolArgs): String = "delete_script_tool ${args.optString("name").orEmpty()}"

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val name = args.string("name").trim()
            val removed = registry.deleteScriptTool(name)
            return if (removed) {
                ToolExecutionResult.ok("Deleted script tool '$name'.")
            } else {
                ToolExecutionResult.error("Script tool '$name' was not found.")
            }
        }
    },

    // 5. learned_webapp_save — persist learned web-app APIs & JS tools per domain
    object : AgentTool {
        override val spec = ToolSpec(
            name = "learned_webapp_save",
            description = "Save learned API endpoints, notes, and reusable WebView JS tool snippets for a web domain " +
                "(stored in .agent/learned/<domain>.json). Also registers any provided JS tool into the tool catalog.",
            parameters = toolSchema {
                string("domain", "Domain name (e.g. 'github.com' or 'localhost').")
                string("notes", "Notes on authentication, DOM structure, or API conventions.", required = false)
                stringArray("apis", "Discovered REST/GraphQL/WebSocket endpoints.", required = false)
                string("tool_name", "Optional JS tool name to save for this domain.", required = false)
                string("tool_description", "Description for the JS tool.", required = false)
                string("tool_js_body", "JavaScript body (runs inside async IIFE with `args` and `window.__aosNet`).", required = false)
            },
        )
        override val mutating: Boolean = true
        override fun summarize(args: ToolArgs): String = "learned_webapp_save ${args.optString("domain").orEmpty()}"

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val domain = args.string("domain").trim().lowercase().replace(Regex("[^a-z0-9._-]"), "_")
            val filePath = "${DynamicToolRegistry.LEARNED_DIR}/$domain.json"
            val existing = runCatching {
                AgentJson.decodeFromString<LearnedWebApp>(workspace.readFile(filePath))
            }.getOrNull()

            val newTools = existing?.tools?.toMutableList() ?: mutableListOf()
            val tName = args.optString("tool_name")?.trim()
            val tJs = args.optString("tool_js_body")
            if (!tName.isNullOrBlank() && !tJs.isNullOrBlank()) {
                newTools.removeAll { it.name == tName }
                newTools += LearnedWebTool(
                    name = tName,
                    description = args.optString("tool_description") ?: "Learned web tool for $domain",
                    jsBody = tJs,
                )
            }
            val mergedApis = ((existing?.apis ?: emptyList()) + args.stringList("apis")).distinct()
            val updated = LearnedWebApp(
                domain = domain,
                notes = args.optString("notes") ?: existing?.notes.orEmpty(),
                apis = mergedApis,
                tools = newTools,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            workspace.writeFile(filePath, AgentJson.encodeToString(updated))
            return ToolExecutionResult.ok(
                "Saved learned web-app profile for '$domain' (${updated.apis.size} APIs, ${updated.tools.size} JS tools).",
            )
        }
    },

    // 6. learned_webapp_list — inspect learned web-app profiles and PageJs network shim
    object : AgentTool {
        override val spec = ToolSpec(
            name = "learned_webapp_list",
            description = "List learned web-app profiles or inspect a domain's saved APIs, JS tools, and the PageJs network shim.",
            parameters = toolSchema {
                string("domain", "Optional domain to inspect in detail.", required = false)
                boolean("include_net_shim", "Include the PageJs window.__aosNet injection script.", required = false)
            },
        )
        override fun summarize(args: ToolArgs): String = "learned_webapp_list ${args.optString("domain").orEmpty()}"

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val domain = args.optString("domain")?.trim()?.lowercase()?.replace(Regex("[^a-z0-9._-]"), "_")
            val includeShim = args.optBoolean("include_net_shim") ?: false
            val sb = StringBuilder()
            if (!domain.isNullOrBlank()) {
                val raw = runCatching { workspace.readFile("${DynamicToolRegistry.LEARNED_DIR}/$domain.json") }.getOrNull()
                if (raw == null) {
                    sb.appendLine("No learned profile found for '$domain'.")
                } else {
                    sb.appendLine(raw)
                }
            } else {
                val entries = runCatching { workspace.listDir(DynamicToolRegistry.LEARNED_DIR) }.getOrDefault(emptyList())
                val files = entries.filter { !it.isDirectory && it.name.endsWith(".json") }
                if (files.isEmpty()) {
                    sb.appendLine("No learned web-app profiles saved yet.")
                } else {
                    sb.appendLine("Learned web-app profiles (${files.size}):")
                    for (f in files) {
                        sb.appendLine("- ${f.name.removeSuffix(".json")}")
                    }
                }
            }
            if (includeShim) {
                sb.appendLine("\nPageJs.NET_SHIM:")
                sb.appendLine(PageJs.NET_SHIM.trim())
            }
            return ToolExecutionResult.ok(sb.toString().trimEnd())
        }
    },

    // 7. card_guide / card_save / card_update / card_list / card_run_refresh
    object : AgentTool {
        override val spec = ToolSpec(
            name = "card_save",
            description = "Create or replace a live UI card stored in .agent/cards/<id>.json. Cards support {{key}} " +
                "state placeholders and an optional background refresh script (Node/Python/Shell) that outputs JSON " +
                "key-value pairs to update card state without spending LLM tokens.",
            parameters = toolSchema {
                string("id", "Card identifier (e.g. 'build-status', 'server-monitor').")
                string("title", "Card title.")
                string("template", "Markdown/UI template containing {{key}} placeholders.")
                string("state_json", "Initial JSON object of key-value state pairs.", required = false)
                string("refresh_script", "Optional background script that prints a JSON object of updated state keys.", required = false)
                string("refresh_language", "Language for refresh_script: 'node', 'python', or 'shell'.", required = false, enum = listOf("node", "python", "shell"))
                integer("every_minutes", "Background refresh interval in minutes (0 = manual only).", required = false)
            },
        )
        override val mutating: Boolean = true
        override fun summarize(args: ToolArgs): String = "card_save ${args.optString("id").orEmpty()}"

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val id = args.string("id").trim().replace(Regex("[^a-zA-Z0-9._-]"), "-")
            val stateMap = parseFlatStringMap(args.optString("state_json"))
            val card = AgentLiveCard(
                id = id,
                title = args.string("title"),
                template = args.string("template"),
                state = stateMap,
                refreshScript = args.optString("refresh_script")?.takeIf { it.isNotBlank() },
                refreshLanguage = args.optString("refresh_language") ?: "node",
                everyMinutes = (args.optInt("every_minutes") ?: 0).coerceAtLeast(0),
            )
            workspace.writeFile("$CARDS_DIR/$id.json", AgentJson.encodeToString(card))
            return ToolExecutionResult.ok("Saved live card '$id'. Rendered preview:\n${card.render()}")
        }
    },

    object : AgentTool {
        override val spec = ToolSpec(
            name = "card_update",
            description = "Update state keys (or run the background refresh script) of an existing live card.",
            parameters = toolSchema {
                string("id", "Card identifier.")
                string("state_json", "JSON object of state key-values to merge into the card.", required = false)
                boolean("run_refresh_script", "If true, executes the card's refresh_script and merges its JSON stdout.", required = false)
            },
        )
        override val mutating: Boolean = true
        override fun summarize(args: ToolArgs): String = "card_update ${args.optString("id").orEmpty()}"

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val id = args.string("id").trim().replace(Regex("[^a-zA-Z0-9._-]"), "-")
            val path = "$CARDS_DIR/$id.json"
            val existing = runCatching {
                AgentJson.decodeFromString<AgentLiveCard>(workspace.readFile(path))
            }.getOrElse {
                return ToolExecutionResult.error("Card '$id' not found.")
            }

            val merged = LinkedHashMap(existing.state)
            merged.putAll(parseFlatStringMap(args.optString("state_json")))

            if (args.optBoolean("run_refresh_script") == true && !existing.refreshScript.isNullOrBlank()) {
                val res = workspace.executeScript(
                    language = existing.refreshLanguage,
                    code = existing.refreshScript,
                )
                if (res.exitCode != 0) {
                    return ToolExecutionResult.error("Card refresh script failed (exit ${res.exitCode}): ${res.stderr}")
                }
                merged.putAll(parseFlatStringMap(res.stdout.trim()))
            }

            val updated = existing.copy(
                state = merged,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            workspace.writeFile(path, AgentJson.encodeToString(updated))
            return ToolExecutionResult.ok("Updated card '$id'. Rendered:\n${updated.render()}")
        }
    },

    object : AgentTool {
        override val spec = ToolSpec(
            name = "card_list",
            description = "List all live UI cards and their rendered states.",
            parameters = toolSchema { },
        )
        override fun summarize(args: ToolArgs): String = "card_list"

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val entries = runCatching { workspace.listDir(CARDS_DIR) }.getOrDefault(emptyList())
            val files = entries.filter { !it.isDirectory && it.name.endsWith(".json") }
            if (files.isEmpty()) return ToolExecutionResult.ok("No live cards saved.")
            val rendered = files.mapNotNull { f ->
                runCatching {
                    val card = AgentJson.decodeFromString<AgentLiveCard>(workspace.readFile("$CARDS_DIR/${f.name}"))
                    "### ${card.title} (${card.id}, refresh=${card.everyMinutes}m)\n${card.render()}"
                }.getOrNull()
            }
            return ToolExecutionResult.ok(rendered.joinToString("\n\n"))
        }
    },

    // 8. skill_save / skill_find — reusable procedural skills in .agent/skills/<id>/SKILL.md
    object : AgentTool {
        override val spec = ToolSpec(
            name = "skill_save",
            description = "Create or update a reusable procedural skill in .agent/skills/<id>/SKILL.md so the agent " +
                "retains domain workflows, runtime recipes, and error recovery patterns across tasks.",
            parameters = toolSchema {
                string("id", "Skill slug (e.g. 'android-ndk-build', 'self-improving-agent').")
                string("description", "Short summary of when to use this skill.")
                string("instructions", "Full Markdown body of the skill instructions.")
            },
        )
        override val mutating: Boolean = true
        override fun summarize(args: ToolArgs): String = "skill_save ${args.optString("id").orEmpty()}"

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val id = args.string("id").trim().lowercase().replace(Regex("[^a-z0-9._-]"), "-")
            val desc = args.string("description").trim()
            val body = args.string("instructions").trim()
            val content = buildString {
                appendLine("---")
                appendLine("name: $id")
                appendLine("description: $desc")
                appendLine("---")
                appendLine()
                appendLine(body)
            }
            val path = "$SKILLS_DIR/$id/SKILL.md"
            workspace.writeFile(path, content)
            return ToolExecutionResult.ok("Saved skill '$id' to $path.")
        }
    },

    object : AgentTool {
        override val spec = ToolSpec(
            name = "skill_find",
            description = "Search or read skills stored in .agent/skills/.",
            parameters = toolSchema {
                string("query", "Skill id or keyword to search for (omit to list all skills).", required = false)
            },
        )
        override fun summarize(args: ToolArgs): String = "skill_find ${args.optString("query").orEmpty()}"

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val query = args.optString("query")?.trim().orEmpty()
            val dirs = runCatching { workspace.listDir(SKILLS_DIR) }.getOrDefault(emptyList())
                .filter { it.isDirectory }
            if (dirs.isEmpty()) return ToolExecutionResult.ok("No skills found in $SKILLS_DIR.")

            if (query.isNotEmpty()) {
                val exact = dirs.firstOrNull { it.name.equals(query, ignoreCase = true) }
                if (exact != null) {
                    val text = runCatching { workspace.readFile("$SKILLS_DIR/${exact.name}/SKILL.md") }.getOrNull()
                    if (text != null) return ToolExecutionResult.ok(text)
                }
            }

            val matches = mutableListOf<String>()
            for (d in dirs) {
                val md = runCatching { workspace.readFile("$SKILLS_DIR/${d.name}/SKILL.md") }.getOrNull() ?: continue
                if (query.isEmpty() || d.name.contains(query, ignoreCase = true) || md.contains(query, ignoreCase = true)) {
                    val descLine = md.lineSequence()
                        .firstOrNull { it.startsWith("description:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.trim()
                        ?: md.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("---") }.orEmpty()
                    matches += "- ${d.name}: $descLine"
                }
            }
            return if (matches.isEmpty()) {
                ToolExecutionResult.ok("No skills matched '$query'.")
            } else {
                ToolExecutionResult.ok(matches.joinToString("\n"))
            }
        }
    },

    // 9. runtime_extension_install & runtime_doctor
    object : AgentTool {
        override val spec = ToolSpec(
            name = "runtime_extension_install",
            description = "Install an on-demand Termux toolchain extension (e.g. 'python', 'golang', 'rust', 'clang', " +
                "'openjdk-17', 'git', 'ruby', 'php', 'perl', 'lua54', 'sqlite', 'ffmpeg', 'imagemagick', 'openssh', " +
                "'android-tools', 'vim', 'cmake', 'ninja', 'jq') into the on-device runtime.",
            parameters = toolSchema {
                string("extension_id", "Extension identifier from the toolchain catalog.")
            },
        )
        override val mutating: Boolean = true
        override fun summarize(args: ToolArgs): String = "runtime_extension_install ${args.optString("extension_id").orEmpty()}"

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val id = args.string("extension_id").trim()
            val status = workspace.installToolchainExtension(id)
            return ToolExecutionResult.ok(status)
        }
    },

    object : AgentTool {
        override val spec = ToolSpec(
            name = "runtime_doctor",
            description = "Diagnose and automatically repair the on-device runtime environment (symlinks, execute bits, " +
                "Termux shebangs, musl loader links, and agent workspace directories).",
            parameters = toolSchema {
                boolean("repair", "Automatically repair any detected issues (default true).", required = false)
            },
        )
        override fun summarize(args: ToolArgs): String = "runtime_doctor"

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val repair = args.optBoolean("repair") ?: true
            return ToolExecutionResult.ok(workspace.diagnoseRuntime(repair))
        }
    },

    // 10. self_iteration_bootstrap — self-compilation & APK upgrade coordinator (from wkbin/taixu)
    object : AgentTool {
        override val spec = ToolSpec(
            name = "self_iteration_bootstrap",
            description = "Bootstrap, inspect, or verify a self-iteration upgrade of the app itself " +
                "(stored in .agent/self_iteration/iteration.json). Computes SHA-256 checksums for built APKs " +
                "and tracks self-upgrade readiness.",
            parameters = toolSchema {
                string("action", "Action: 'bootstrap', 'status', or 'verify_artifact'.", enum = listOf("bootstrap", "status", "verify_artifact"))
                string("branch_name", "Task branch name for 'bootstrap' (e.g. 'self-iter/feature').", required = false)
                string("goal", "Self-iteration goal description.", required = false)
                string("build_command", "Build command (default './gradlew :ide-android:assembleDebug').", required = false)
                string("artifact_path", "Workspace path to built APK or artifact for 'verify_artifact'.", required = false)
            },
        )
        override val mutating: Boolean = true
        override fun summarize(args: ToolArgs): String = "self_iteration_bootstrap (${args.optString("action") ?: "status"})"

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val action = args.string("action")
            val manifestPath = ".agent/self_iteration/iteration.json"
            return when (action) {
                "bootstrap" -> {
                    val branch = args.optString("branch_name")?.takeIf { it.isNotBlank() }
                        ?: "self-iter/${System.currentTimeMillis()}"
                    val goal = args.optString("goal") ?: "Self-evolving capability upgrade"
                    val cmd = args.optString("build_command") ?: "./gradlew :ide-android:assembleDebug"
                    val manifest = SelfIterationManifest(
                        iterationId = "iter-${System.currentTimeMillis()}",
                        branchName = branch,
                        goal = goal,
                        buildCommand = cmd,
                        status = "BOOTSTRAPPED",
                    )
                    workspace.writeFile(manifestPath, AgentJson.encodeToString(manifest))
                    ToolExecutionResult.ok(
                        "Bootstrapped self-iteration '${manifest.iterationId}' on branch '${manifest.branchName}'. " +
                            "Build command: ${manifest.buildCommand}",
                    )
                }
                "verify_artifact" -> {
                    val artifactPath = args.optString("artifact_path")
                        ?: return ToolExecutionResult.error("Missing 'artifact_path' for verify_artifact.")
                    val bytes = runCatching { workspace.readBytes(artifactPath) }.getOrElse {
                        return ToolExecutionResult.error("Could not read artifact '$artifactPath': ${it.message}")
                    }
                    val sha256 = MessageDigest.getInstance("SHA-256")
                        .digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                    val existing = runCatching {
                        AgentJson.decodeFromString<SelfIterationManifest>(workspace.readFile(manifestPath))
                    }.getOrNull()
                    val updated = (existing ?: SelfIterationManifest(
                        iterationId = "iter-${System.currentTimeMillis()}",
                        branchName = "self-iter/verified",
                        goal = "Verified artifact",
                    )).copy(
                        artifactPath = artifactPath,
                        artifactSha256 = sha256,
                        status = "ARTIFACT_VERIFIED",
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                    workspace.writeFile(manifestPath, AgentJson.encodeToString(updated))
                    ToolExecutionResult.ok(
                        "Verified artifact '$artifactPath' (${bytes.size} bytes, sha256=$sha256). Status=ARTIFACT_VERIFIED.",
                    )
                }
                else -> {
                    val raw = runCatching { workspace.readFile(manifestPath) }.getOrNull()
                        ?: return ToolExecutionResult.ok("No active self-iteration manifest.")
                    ToolExecutionResult.ok(raw)
                }
            }
        }
    },
)

/**
 * Builds a complete [DynamicToolRegistry] combining [builtinTools], [selfExtendingTools],
 * [CapabilityOrchestrator] (`use_capability`), [ToolResultSpillStore] (`read_tool_output`),
 * [UsageLedger] (`usage_ledger_report`), and progressive tool exposure (`tool_search` / `tool_open`).
 */
fun createSelfExtendingToolRegistry(
    workspace: AgentWorkspace,
    extraTools: List<AgentTool> = emptyList(),
    deferredTools: List<AgentTool> = emptyList(),
    gateProvider: () -> dev.ide.agent.AgentPermissionGate = { dev.ide.agent.AllowAllGate },
    spillStore: ToolResultSpillStore = ToolResultSpillStore(workspace),
    usageLedger: UsageLedger = UsageLedger(),
): DynamicToolRegistry {
    lateinit var registry: DynamicToolRegistry
    val orchestrator = CapabilityOrchestrator(
        registryProvider = { registry },
        gateProvider = gateProvider,
    )
    registry = DynamicToolRegistry(
        workspace = workspace,
        baseTools = builtinTools(workspace) + extraTools,
        deferredTools = deferredTools,
    )
    for (tool in selfExtendingTools(workspace, registry)) {
        registry.registerCoreTool(tool)
    }
    registry.registerCoreTool(orchestrator.createUseCapabilityTool())
    registry.registerCoreTool(spillStore.createReadToolOutputTool())
    registry.registerCoreTool(usageLedger.createUsageLedgerTool())
    return registry
}

private const val CARDS_DIR = ".agent/cards"
private const val SKILLS_DIR = ".agent/skills"

private fun parseFlatStringMap(rawJson: String?): Map<String, String> {
    if (rawJson.isNullOrBlank()) return emptyMap()
    val obj = runCatching { AgentJson.parseToJsonElement(rawJson) as? JsonObject }.getOrNull() ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    for ((k, v) in obj) {
        out[k] = when (v) {
            is JsonPrimitive -> v.content
            else -> v.toString()
        }
    }
    return out
}
