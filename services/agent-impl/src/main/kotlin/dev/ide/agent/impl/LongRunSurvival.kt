package dev.ide.agent.impl

import dev.ide.agent.AgentPermissionGate
import dev.ide.agent.AgentTool
import dev.ide.agent.AgentWorkspace
import dev.ide.agent.PermissionMode
import dev.ide.agent.TokenUsage
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import dev.ide.agent.WriteRequest
import dev.ide.agent.toolSchema
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 32 KB head+tail tool output truncator and spill store (clean-room implementation).
 *
 * Prevents a single huge `run_code`, build log, or file dump from consuming the entire context window
 * by preserving the first [headChars] and last [tailChars] inline and spilling the full text to
 * `.agent/tool_outputs/<callId>.txt` (plus an in-memory LRU cache for workspaces without disk backing).
 */
class ToolResultSpillStore(
    private val workspace: AgentWorkspace? = null,
    val maxInlineChars: Int = DEFAULT_MAX_INLINE_CHARS,
    val headChars: Int = maxInlineChars / 2,
    val tailChars: Int = maxInlineChars / 2,
) {
    private val memorySpills = ConcurrentHashMap<String, String>()

    suspend fun processResult(callId: String, toolName: String, result: ToolExecutionResult): ToolExecutionResult {
        if (toolName == "read_tool_output") return result
        val text = result.content
        if (text.length <= maxInlineChars) return result

        val safeId = callId.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "call_${System.currentTimeMillis()}" }
        memorySpills[safeId] = text
        val spillPath = "$SPILL_DIR/$safeId.txt"
        val ws = workspace
        if (ws != null) {
            runCatching { ws.writeFile(spillPath, text) }
        }

        val head = text.take(headChars)
        val tail = text.takeLast(tailChars)
        val omittedChars = text.length - head.length - tail.length
        val totalLines = text.count { it == '\n' } + 1

        val truncated = buildString {
            append(head)
            append("\n\n... [Spilled $omittedChars characters ($totalLines total lines) to $spillPath. ")
            append("Call read_tool_output(call_id=\"$safeId\", start_line=..., end_line=...) to read slices.] ...\n\n")
            append(tail)
        }
        return result.copy(content = truncated)
    }

    suspend fun readSpilledSlice(callId: String, startLine: Int?, endLine: Int?): String? {
        val safeId = callId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val raw = memorySpills[safeId]
            ?: workspace?.let { runCatching { it.readFile("$SPILL_DIR/$safeId.txt") }.getOrNull() }
            ?: return null
        val lines = raw.split('\n')
        val from = (startLine ?: 1).coerceAtLeast(1)
        val to = (endLine ?: (from + 199)).coerceAtMost(lines.size)
        if (from > to || from > lines.size) return "(line range $from..$to is outside 1..${lines.size})"
        return buildString {
            appendLine("Spilled output '$safeId' (lines $from..$to of ${lines.size}):")
            for (lineNum in from..to) {
                appendLine("$lineNum: ${lines[lineNum - 1]}")
            }
        }.trimEnd()
    }

    fun createReadToolOutputTool(): AgentTool = object : AgentTool {
        override val spec = ToolSpec(
            name = "read_tool_output",
            description = "Read a 1-based line range from a large tool output that was spilled to .agent/tool_outputs/<call_id>.txt.",
            parameters = toolSchema {
                string("call_id", "The call_id reported in the spilled tool output notice.")
                integer("start_line", "First line to read (1-based, default 1).", required = false)
                integer("end_line", "Last line to read (1-based, inclusive, default start_line + 199).", required = false)
            },
        )
        override fun summarize(args: ToolArgs): String = "read_tool_output ${args.optString("call_id").orEmpty()}"

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val id = args.string("call_id")
            val slice = readSpilledSlice(id, args.optInt("start_line"), args.optInt("end_line"))
                ?: return ToolExecutionResult.error("No spilled output found for call_id='$id'.")
            return ToolExecutionResult.ok(slice)
        }
    }

    companion object {
        const val DEFAULT_MAX_INLINE_CHARS = 32 * 1024
        const val SPILL_DIR = ".agent/tool_outputs"
    }
}

/**
 * Automatic tool-error logger and built-in self-improvement skill provisioner
 * (ported from `Mangi-11/Eta`'s `self-improving-agent`, `skill-creator`, and `skill-installer`).
 */
class SelfImprovingErrorHook(
    private val workspace: AgentWorkspace? = null,
) {
    data class ErrorEntry(
        val timestampIso: String,
        val toolName: String,
        val summary: String,
        val errorExcerpt: String,
    )

    private val recentErrors = CopyOnWriteArrayList<ErrorEntry>()
    @Volatile private var builtinSkillsSeeded = false

    suspend fun ensureBuiltinSkillsSeeded() {
        val ws = workspace ?: return
        if (builtinSkillsSeeded) return
        builtinSkillsSeeded = true
        for ((slug, content) in BUILTIN_SKILL_TEMPLATES) {
            val path = ".agent/skills/$slug/SKILL.md"
            val exists = runCatching { ws.exists(path) }.getOrDefault(false)
            if (!exists) {
                runCatching { ws.writeFile(path, content) }
            }
        }
    }

    suspend fun onToolResult(toolName: String, summary: String, result: ToolExecutionResult) {
        if (!result.isError) return
        val excerpt = result.content.trim().take(500)
        val entry = ErrorEntry(
            timestampIso = Instant.now().toString(),
            toolName = toolName,
            summary = summary,
            errorExcerpt = excerpt,
        )
        recentErrors += entry
        while (recentErrors.size > 50) {
            recentErrors.removeAt(0)
        }

        val ws = workspace ?: return
        ensureBuiltinSkillsSeeded()
        runCatching {
            val existing = runCatching { ws.readFile(ERRORS_MD_PATH) }.getOrDefault("# Recurring Tool & Command Errors\n\n")
            val markdownEntry = buildString {
                appendLine("## [${entry.timestampIso}] `$toolName` — $summary")
                appendLine("```")
                appendLine(excerpt)
                appendLine("```")
                appendLine()
            }
            val combined = (existing.trimEnd() + "\n\n" + markdownEntry).takeLast(32_000)
            ws.writeFile(ERRORS_MD_PATH, combined)
        }
    }

    fun recentErrorEntries(): List<ErrorEntry> = recentErrors.toList()

    companion object {
        const val ERRORS_MD_PATH = ".agent/skills/self-improving-agent/data/ERRORS.md"

        val BUILTIN_SKILL_TEMPLATES: Map<String, String> = mapOf(
            "self-improving-agent" to """
                ---
                name: self-improving-agent
                description: Captures tool failures, environment quirks, and recurring fixes in .agent/skills/self-improving-agent/data/ERRORS.md.
                ---

                # Self-Improving Agent Skill

                1. Whenever a command or tool call fails unexpectedly, inspect `.agent/skills/self-improving-agent/data/ERRORS.md`.
                2. Once you identify the root cause and verify the fix, record the durable rule using `skill_save` or `write_memory`.
                3. If a missing CLI or broken symlink caused the error, run `runtime_doctor` or `runtime_extension_install`.
            """.trimIndent(),
            "skill-creator" to """
                ---
                name: skill-creator
                description: Guide for authoring reusable procedural skills under .agent/skills/<id>/SKILL.md.
                ---

                # Skill Creator

                Use `skill_save` to persist domain-specific workflows, build recipes, or device automation scripts:
                - Keep `id` lowercase and hyphenated.
                - State clear trigger conditions in `description`.
                - Include exact verified commands or `register_script_tool` definitions in `instructions`.
            """.trimIndent(),
            "skill-installer" to """
                ---
                name: skill-installer
                description: Installs and manages external or repository-provided agent skills and Termux runtime extensions.
                ---

                # Skill & Extension Installer

                - Use `skill_find` to check existing skills before creating duplicates.
                - Use `runtime_extension_install` to provision language toolchains (python, golang, rust, clang, git, etc.).
            """.trimIndent(),
        )
    }
}

/**
 * Post-run trajectory analyzer (`SkillEvolutionAdvisor` from `wkbin/taixu`).
 *
 * Inspects completed multi-tool turns and logs reusable multi-step tool trajectories so the agent
 * can consolidate repeated patterns into dedicated skills or script tools.
 */
class SkillEvolutionAdvisor(
    private val workspace: AgentWorkspace? = null,
) {
    data class TrajectoryRecord(
        val timestampIso: String,
        val toolSequence: List<String>,
        val recoveredFromErrors: Int,
        val recommendation: String,
    )

    private val records = CopyOnWriteArrayList<TrajectoryRecord>()

    suspend fun analyzeCompletedTurn(
        toolNamesUsed: List<String>,
        errorCount: Int,
    ): TrajectoryRecord? {
        if (toolNamesUsed.size < 3) return null
        val recommendation = when {
            errorCount > 0 ->
                "Recovered after $errorCount tool error(s) across ${toolNamesUsed.size} calls (${toolNamesUsed.distinct().joinToString(" -> ")}). Consider saving the working recipe with skill_save."
            toolNamesUsed.size >= 5 ->
                "Executed ${toolNamesUsed.size}-step workflow (${toolNamesUsed.distinct().joinToString(" -> ")}). Consider encapsulating repeated steps via register_script_tool or use_capability(action='script')."
            else ->
                "Completed multi-tool sequence (${toolNamesUsed.joinToString(" -> ")})."
        }
        val record = TrajectoryRecord(
            timestampIso = Instant.now().toString(),
            toolSequence = toolNamesUsed,
            recoveredFromErrors = errorCount,
            recommendation = recommendation,
        )
        records += record
        val ws = workspace
        if (ws != null) {
            runCatching {
                val existing = runCatching { ws.readFile(TRAJECTORIES_MD_PATH) }
                    .getOrDefault("# Agent Trajectory & Skill Evolution Log\n\n")
                val entry = "- [${record.timestampIso}] `${toolNamesUsed.joinToString(" -> ")}` (errors=$errorCount): $recommendation\n"
                ws.writeFile(TRAJECTORIES_MD_PATH, (existing + entry).takeLast(24_000))
            }
        }
        return record
    }

    fun history(): List<TrajectoryRecord> = records.toList()

    companion object {
        const val TRAJECTORIES_MD_PATH = ".agent/skills/self-improving-agent/data/TRAJECTORIES.md"
    }
}

/**
 * Per-purpose token/cost attribution ledger and orchestration budget manager
 * (clean-room implementation).
 */
enum class UsagePurpose {
    MAIN,
    TOOL_LOOP,
    COMPACTION,
    SUBAGENT,
    CRON,
    WORKFLOW,
    SELF_TOOL,
}

class UsageLedger(
    val tokenBudgetCeiling: Long? = null,
) {
    data class LedgerRecord(
        val purpose: UsagePurpose,
        val model: String,
        val usage: TokenUsage,
        val timestampEpochMs: Long = System.currentTimeMillis(),
    )

    private val entries = CopyOnWriteArrayList<LedgerRecord>()

    fun record(purpose: UsagePurpose, model: String, usage: TokenUsage) {
        entries += LedgerRecord(purpose = purpose, model = model, usage = usage)
    }

    fun totalUsage(purpose: UsagePurpose? = null): TokenUsage {
        var total = TokenUsage()
        for (e in entries) {
            if (purpose == null || e.purpose == purpose) {
                total += e.usage
            }
        }
        return total
    }

    fun totalTokensUsed(): Long {
        val u = totalUsage()
        return u.inputTokens.toLong() + u.outputTokens.toLong()
    }

    fun isBudgetExceeded(): Boolean {
        val ceiling = tokenBudgetCeiling ?: return false
        return totalTokensUsed() >= ceiling
    }

    fun formatReport(): String = buildString {
        val grand = totalUsage()
        appendLine("Usage Ledger Report (total=${grand.inputTokens + grand.outputTokens} tokens, ceiling=${tokenBudgetCeiling ?: "unlimited"}):")
        for (purpose in UsagePurpose.entries) {
            val u = totalUsage(purpose)
            if (u.inputTokens > 0 || u.outputTokens > 0 || u.cacheReadTokens > 0) {
                appendLine(
                    "- ${purpose.name}: input=${u.inputTokens}, output=${u.outputTokens}, " +
                        "cached=${u.cacheReadTokens}, cacheCreated=${u.cacheWriteTokens}",
                )
            }
        }
    }.trimEnd()

    fun createUsageLedgerTool(): AgentTool = object : AgentTool {
        override val spec = ToolSpec(
            name = "usage_ledger_report",
            description = "Report cumulative token usage broken down by purpose (MAIN, TOOL_LOOP, COMPACTION, SUBAGENT, CRON, WORKFLOW, SELF_TOOL) and remaining budget.",
            parameters = toolSchema { },
        )
        override fun summarize(args: ToolArgs): String = "usage_ledger_report"
        override suspend fun execute(args: ToolArgs): ToolExecutionResult =
            ToolExecutionResult.ok(formatReport())
    }
}

/**
 * Safety permission gate for headless/background/cron agent executions where no interactive user
 * is present to approve destructive changes.
 *
 * Allows writes inside `.agent/` (self-authored tools, cards, skills, memory) and safe additive edits
 * when [allowProjectEdits] is true, while blocking destructive deletions outside `.agent/`.
 */
class HeadlessApprovalPolicy(
    private val allowProjectEdits: Boolean = true,
) : AgentPermissionGate {
    override val mode: PermissionMode = PermissionMode.AUTO_ACCEPT

    override suspend fun authorize(request: WriteRequest): Boolean {
        val path = request.path?.replace('\\', '/')?.trimStart('/') ?: ""
        // Always allow writes inside the agent's own state directory (.agent/)
        if (path.startsWith(".agent/")) return true
        // Block destructive deletes outside .agent/ in unattended headless mode
        if (request.tool == "delete_file" || request.tool == "delete_path") {
            return false
        }
        return allowProjectEdits
    }
}
