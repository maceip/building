package dev.ide.agent.impl

import dev.ide.agent.AgentEvent
import dev.ide.agent.AgentEventSink
import dev.ide.agent.AgentPermissionGate
import dev.ide.agent.AllowAllGate
import dev.ide.agent.ContentPart
import dev.ide.agent.LlmClient
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.PermissionMode
import dev.ide.agent.ScriptExecutionResult
import dev.ide.agent.StopReason
import dev.ide.agent.TokenUsage
import dev.ide.agent.WorkspaceEntry
import dev.ide.agent.WriteRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SelfExtendingAgentP0P1Test {

    /**
     * In-memory workspace that also supports directory listing, binary files, and simulated script execution
     * so we can test the full self-extending tool lifecycle deterministically.
     */
    private class SelfExtendingFakeWorkspace(
        private val delegate: FakeWorkspace = FakeWorkspace(),
    ) : dev.ide.agent.AgentWorkspace by delegate {
        val textFiles = LinkedHashMap<String, String>()
        val binaryFiles = LinkedHashMap<String, ByteArray>()

        override suspend fun exists(path: String): Boolean =
            path in textFiles || path in binaryFiles

        override suspend fun readFile(path: String, startLine: Int?, endLine: Int?): String =
            textFiles[path] ?: delegate.readFile(path, startLine, endLine)

        override suspend fun writeFile(path: String, content: String) {
            textFiles[path] = content
            delegate.writeFile(path, content)
        }

        override suspend fun createFile(path: String, content: String): String {
            textFiles[path] = content
            return delegate.createFile(path, content)
        }

        override suspend fun deletePath(path: String): Boolean {
            val removed = textFiles.remove(path) != null || binaryFiles.remove(path) != null
            delegate.deletePath(path)
            return removed
        }

        override suspend fun readBytes(path: String): ByteArray =
            binaryFiles[path] ?: textFiles[path]?.toByteArray(Charsets.UTF_8)
                ?: throw IllegalArgumentException("No binary file at $path")

        override suspend fun listDir(path: String): List<WorkspaceEntry> {
            val prefix = path.trimEnd('/') + "/"
            val directChildren = LinkedHashMap<String, WorkspaceEntry>()
            for (k in (textFiles.keys + binaryFiles.keys)) {
                if (k.startsWith(prefix)) {
                    val rest = k.removePrefix(prefix)
                    if ('/' in rest) {
                        val dirName = rest.substringBefore('/')
                        directChildren.putIfAbsent(dirName, WorkspaceEntry(dirName, "$prefix$dirName", isDirectory = true))
                    } else {
                        directChildren[rest] = WorkspaceEntry(rest, k, isDirectory = false)
                    }
                }
            }
            return directChildren.values.toList()
        }

        override suspend fun executeScript(
            language: String,
            code: String,
            args: List<String>,
            env: Map<String, String>,
            timeoutMs: Long,
        ): ScriptExecutionResult {
            val touched = mutableListOf<String>()
            if (code.contains("GENERATE_PNG")) {
                binaryFiles["chart.png"] = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
                touched += "chart.png"
            }
            if (code.contains("REFRESH_CARD_JSON")) {
                return ScriptExecutionResult(
                    exitCode = 0,
                    stdout = """{"status":"ONLINE","latency_ms":"12"}""",
                    stderr = "",
                )
            }
            val argsJson = env["TOOL_ARGS_JSON"] ?: args.firstOrNull().orEmpty()
            return ScriptExecutionResult(
                exitCode = 0,
                stdout = "executed [$language]: args=$argsJson code_len=${code.length}",
                stderr = "",
                touchedFiles = touched,
            )
        }
    }

    @Test
    fun agentRegistersScriptToolOnTurn1AndCallsItOnTurn2() = runBlocking {
        val ws = SelfExtendingFakeWorkspace()
        val registry = createSelfExtendingToolRegistry(ws)
        val exposedToolsPerTurn = mutableListOf<List<String>>()

        val turns = listOf(
            listOf(
                LlmStreamEvent.ToolCallCompleted(
                    id = "call_reg",
                    name = "register_script_tool",
                    arguments = """{"name":"device_battery_probe","description":"Probes battery stats","language":"node","script":"console.log('battery=98%')"}""",
                ),
                LlmStreamEvent.Usage(TokenUsage(inputTokens = 100, outputTokens = 40)),
                LlmStreamEvent.Completed(StopReason.TOOL_USE),
            ),
            listOf(
                LlmStreamEvent.ToolCallCompleted(
                    id = "call_use",
                    name = "device_battery_probe",
                    arguments = """{"input":"detailed"}""",
                ),
                LlmStreamEvent.Usage(TokenUsage(inputTokens = 140, outputTokens = 30)),
                LlmStreamEvent.Completed(StopReason.TOOL_USE),
            ),
            listOf(
                LlmStreamEvent.TextDelta("Battery probe tool created and executed!"),
                LlmStreamEvent.Usage(TokenUsage(inputTokens = 170, outputTokens = 20)),
                LlmStreamEvent.Completed(StopReason.END_TURN),
            ),
        )
        var turnIdx = 0
        val client = object : LlmClient {
            override fun chat(request: LlmRequest): Flow<LlmStreamEvent> {
                exposedToolsPerTurn += request.tools.map { it.name }
                return turns[turnIdx++].asFlow()
            }
        }

        val loop = AgentLoop(
            client = client,
            model = "claude-sonnet-5",
            tools = registry,
            gate = AllowAllGate,
            systemPrompt = { "sys" },
        )

        val events = mutableListOf<AgentEvent>()
        loop.send("Create a battery probe tool and run it", AgentEventSink { events += it })

        // On Turn 1, device_battery_probe was not yet registered; on Turn 2, it is automatically in tools.specs()!
        assertFalse("device_battery_probe" in exposedToolsPerTurn[0])
        assertTrue("device_battery_probe" in exposedToolsPerTurn[1])

        // Verify persistence in .agent/tools/device_battery_probe.json
        assertTrue(ws.exists(".agent/tools/device_battery_probe.json"))

        // Verify a fresh registry loads the persisted tool from disk
        val reloadedRegistry = createSelfExtendingToolRegistry(ws)
        reloadedRegistry.ensurePersistedToolsLoaded()
        assertNotNull(reloadedRegistry.find("device_battery_probe"))
    }

    @Test
    fun runCodeAttachesGeneratedImagesAndCapabilityOrchestratorChainsStepsWithSecretRedaction() = runBlocking {
        val ws = SelfExtendingFakeWorkspace()
        ws.writeFile("config.txt", "target=alpha\napi_key=sk-1234567890abcdefghijklmnop\n")
        val registry = createSelfExtendingToolRegistry(ws)

        // 1. Test run_code image attachment
        val runCodeTool = registry.find("run_code")!!
        val codeRes = runCodeTool.execute(
            JsonToolArgs(parseArgsObject("""{"language":"python","code":"# GENERATE_PNG\nprint('plot done')"}""")),
        )
        assertFalse(codeRes.isError)
        assertEquals(1, codeRes.images.size)
        assertEquals("image/png", codeRes.images.single().mediaType)

        // 2. Test use_capability(action="script") multi-step pipeline with variable interpolation,
        // conditional execution, and secret redaction
        val useCapTool = registry.find("use_capability")!!
        val scriptJson = """
            {
              "steps": [
                {
                  "id": "read_cfg",
                  "tool": "read_file",
                  "args": { "path": "config.txt" }
                },
                {
                  "id": "write_summary",
                  "tool": "create_file",
                  "if_contains": { "source": "${'$'}{read_cfg.output}", "substring": "target=alpha" },
                  "args": {
                    "path": "summary.txt",
                    "content": "Loaded config:\n${'$'}{read_cfg.output}"
                  }
                }
              ]
            }
        """.trimIndent()

        val capArgs = JsonToolArgs(
            parseArgsObject(
                """{"action":"script","script_json":${kotlinx.serialization.json.JsonPrimitive(scriptJson)}}""",
            ),
        )
        val capRes = useCapTool.execute(capArgs)
        assertFalse(capRes.isError, capRes.content)
        // SecretRedactor must redact the sk-... key in the returned output
        assertFalse(capRes.content.contains("sk-1234567890abcdefghijklmnop"), capRes.content)
        assertTrue(capRes.content.contains("...[REDACTED]"), capRes.content)
        assertTrue(ws.exists("summary.txt"))
    }

    @Test
    fun liveCardsSkillsLearnedWebAppsAndSelfIterationBootstrapWorkEndToEnd() = runBlocking {
        val ws = SelfExtendingFakeWorkspace()
        val registry = createSelfExtendingToolRegistry(ws)

        // 1. Live Card creation + background refresh script execution
        registry.find("card_save")!!.execute(
            JsonToolArgs(
                parseArgsObject(
                    """{"id":"gateway","title":"Gateway Health","template":"Status: {{status}} ({{latency_ms}} ms)","state_json":"{\"status\":\"INIT\",\"latency_ms\":\"0\"}","refresh_script":"// REFRESH_CARD_JSON","refresh_language":"node","every_minutes":5}""",
                ),
            ),
        )
        val updatedCard = registry.find("card_update")!!.execute(
            JsonToolArgs(parseArgsObject("""{"id":"gateway","run_refresh_script":true}""")),
        )
        assertTrue(updatedCard.content.contains("Status: ONLINE (12 ms)"), updatedCard.content)

        // 2. Skill save & find
        registry.find("skill_save")!!.execute(
            JsonToolArgs(
                parseArgsObject(
                    """{"id":"ndk-16kb-align","description":"Link native binaries with 16KB page alignment","instructions":"Use -Wl,-z,max-page-size=16384"}""",
                ),
            ),
        )
        val foundSkill = registry.find("skill_find")!!.execute(
            JsonToolArgs(parseArgsObject("""{"query":"ndk-16kb-align"}""")),
        )
        assertTrue(foundSkill.content.contains("-Wl,-z,max-page-size=16384"), foundSkill.content)

        // 3. Learned WebApp + PageJs.NET_SHIM
        registry.find("learned_webapp_save")!!.execute(
            JsonToolArgs(
                parseArgsObject(
                    """{"domain":"api.example.com","notes":"Uses Bearer auth","apis":["/v1/tasks"],"tool_name":"fetch_tasks","tool_js_body":"return await fetch('/v1/tasks').then(r => r.json());"}""",
                ),
            ),
        )
        val learnedList = registry.find("learned_webapp_list")!!.execute(
            JsonToolArgs(parseArgsObject("""{"domain":"api.example.com","include_net_shim":true}""")),
        )
        assertTrue(learnedList.content.contains("fetch_tasks"))
        assertTrue(learnedList.content.contains("window.__aosNet"))

        // 4. Self-iteration bootstrap + artifact SHA-256 verification
        ws.binaryFiles["build/app-debug.apk"] = "fake-apk-payload".toByteArray(Charsets.UTF_8)
        registry.find("self_iteration_bootstrap")!!.execute(
            JsonToolArgs(parseArgsObject("""{"action":"bootstrap","branch_name":"self-iter/v2","goal":"Add new tool"}""")),
        )
        val verifyRes = registry.find("self_iteration_bootstrap")!!.execute(
            JsonToolArgs(parseArgsObject("""{"action":"verify_artifact","artifact_path":"build/app-debug.apk"}""")),
        )
        assertTrue(verifyRes.content.contains("ARTIFACT_VERIFIED"), verifyRes.content)
    }

    @Test
    fun spillStoreErrorHookUsageLedgerAndHeadlessPolicyEnforceLongRunSurvival() = runBlocking {
        val ws = SelfExtendingFakeWorkspace()
        val spillStore = ToolResultSpillStore(ws, maxInlineChars = 400, headChars = 150, tailChars = 150)
        val errorHook = SelfImprovingErrorHook(ws)
        val usageLedger = UsageLedger(tokenBudgetCeiling = 500L)
        val registry = createSelfExtendingToolRegistry(
            workspace = ws,
            spillStore = spillStore,
            usageLedger = usageLedger,
        )

        // 1. Test 32KB (here 400-char) head+tail spill store and read_tool_output slice reader
        val hugeText = (1..100).joinToString("\n") { "Line $it: " + "x".repeat(20) }
        val spilled = spillStore.processResult(
            callId = "call_big_1",
            toolName = "run_code",
            result = dev.ide.agent.ToolExecutionResult.ok(hugeText),
        )
        assertTrue(spilled.content.contains("read_tool_output(call_id=\"call_big_1\""))
        assertTrue(ws.exists(".agent/tool_outputs/call_big_1.txt"))

        val sliceRes = registry.find("read_tool_output")!!.execute(
            JsonToolArgs(parseArgsObject("""{"call_id":"call_big_1","start_line":10,"end_line":12}""")),
        )
        assertFalse(sliceRes.isError)
        assertTrue(sliceRes.content.contains("10: Line 10:"))
        assertTrue(sliceRes.content.contains("12: Line 12:"))

        // 2. Test SelfImprovingErrorHook logs errors to ERRORS.md and seeds built-in skills
        errorHook.onToolResult(
            toolName = "run_code",
            summary = "run_code (python)",
            result = dev.ide.agent.ToolExecutionResult.error("ModuleNotFoundError: No module named 'foo'"),
        )
        assertTrue(ws.exists(SelfImprovingErrorHook.ERRORS_MD_PATH))
        assertTrue(ws.readFile(SelfImprovingErrorHook.ERRORS_MD_PATH).contains("ModuleNotFoundError"))
        assertTrue(ws.exists(".agent/skills/self-improving-agent/SKILL.md"))
        assertTrue(ws.exists(".agent/skills/skill-creator/SKILL.md"))

        // 3. Test UsageLedger accounting
        usageLedger.record(UsagePurpose.MAIN, "claude-sonnet-5", TokenUsage(inputTokens = 200, outputTokens = 100))
        usageLedger.record(UsagePurpose.TOOL_LOOP, "claude-sonnet-5", TokenUsage(inputTokens = 150, outputTokens = 80))
        assertEquals(530L, usageLedger.totalTokensUsed())
        assertTrue(usageLedger.isBudgetExceeded())
        val reportRes = registry.find("usage_ledger_report")!!.execute(JsonToolArgs(parseArgsObject("{}")))
        assertTrue(reportRes.content.contains("MAIN: input=200, output=100"))
        assertTrue(reportRes.content.contains("TOOL_LOOP: input=150, output=80"))

        // 4. Test HeadlessApprovalPolicy blocks destructive deletes outside .agent/ while allowing .agent/ writes
        val headlessGate: AgentPermissionGate = HeadlessApprovalPolicy(allowProjectEdits = true)
        assertTrue(headlessGate.authorize(WriteRequest("write_file", "save tool", ".agent/tools/t.json")))
        assertFalse(headlessGate.authorize(WriteRequest("delete_file", "delete src", "src/Main.kt")))
    }
}
