package dev.ide.agent.impl

import com.sun.net.httpserver.HttpServer
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ProviderConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiteRtLmCornerTest {

    @Test
    fun hardwareProbeDetectsPixelTensorAndSnapdragonNpu() {
        val tensorG4 = LiteRtHardwareProbe.probe(
            osNameOverride = "Linux",
            osArchOverride = "aarch64",
            socModelOverride = "Tensor G4",
            hardwareOverride = "zumapro",
            deviceModelOverride = "Pixel 9 Pro XL",
        )
        assertTrue(LiteRtHardwareDelegate.NPU in tensorG4.availableDelegates)
        assertTrue(LiteRtHardwareDelegate.GPU in tensorG4.availableDelegates)
        assertTrue(LiteRtHardwareDelegate.CPU in tensorG4.availableDelegates)
        assertEquals(LiteRtHardwareDelegate.NPU, tensorG4.recommendedDelegate)
        assertTrue(tensorG4.socName.contains("Tensor G4"), tensorG4.socName)

        val snapdragon = LiteRtHardwareProbe.probe(
            osNameOverride = "Linux",
            osArchOverride = "aarch64",
            socModelOverride = "SM8650",
            hardwareOverride = "qcom",
            deviceModelOverride = "Galaxy S24",
        )
        assertTrue(LiteRtHardwareDelegate.NPU in snapdragon.availableDelegates)
        assertEquals(LiteRtHardwareDelegate.NPU, snapdragon.recommendedDelegate)
    }

    @Test
    fun servingManagerProvisionsRealLiteRtModelsAndPersistsLifecycle(@TempDir tempDir: Path) = runBlocking {
        val prefs = mutableMapOf<String, String>()
        val manager = LiteRtLmServingManager(
            storageRoot = tempDir,
            preferenceReader = { prefs[it] },
            preferenceWriter = { k, v -> prefs[k] = v },
        )
        try {
            // 1. Out of the box on host JVM (no Android AI Core and no pre-installed .litertlm files in tempDir),
            // activeModelId is empty and generate() fails honestly.
            val initial = manager.currentSnapshot()
            assertEquals("", initial.activeModelId)
            assertNull(manager.activeInstalledModel())
            val premature = manager.generate("Hello")
            assertFalse(premature.ok)
            assertTrue(premature.error.contains("No LiteRT-LM model is installed"), premature.error)

            // 2. Provision SmolLM2 135M Instruct (real 136 MB .litertlm model) -> verifies LITERTLM binary header & becomes ACTIVE.
            val artifact = manager.downloadModelNow("smollm2-135m-instruct")
            assertNotNull(artifact, "Expected real SmolLM2_135M_Instruct.litertlm to be provisioned and verified")
            assertTrue(Files.isRegularFile(artifact.filePath))
            assertTrue(artifact.sizeBytes > 100_000_000L, "Expected real 136 MB .litertlm file, got ${artifact.sizeBytes} bytes")
            assertEquals(64, artifact.sha256.length)

            val afterInstall = manager.currentSnapshot()
            assertEquals("smollm2-135m-instruct", afterInstall.activeModelId)
            val smolEntry = afterInstall.entries.first { it.spec.id == "smollm2-135m-instruct" }
            assertEquals(LiteRtModelInstallState.ACTIVE, smolEntry.status)
            assertEquals(artifact.sizeBytes, smolEntry.sizeOnDiskBytes)
            assertEquals(artifact.sha256, smolEntry.sha256)

            // 3. Execute real on-device inference via com.google.ai.edge.litertlm.Engine!
            val realGen = manager.generate("Say hello in one short sentence.", maxTokens = 24)
            assertTrue(realGen.ok, "Expected real LiteRT-LM Engine inference to succeed: ${realGen.error}")
            assertTrue(realGen.text.isNotBlank(), "Expected non-blank output from real LiteRT-LM Engine")
            assertTrue(realGen.completionTokens > 0)

            // 4. Provision SmolLM2 360M Instruct -> becomes INSTALLED while 135M remains ACTIVE, then activate 360M.
            val smol360Artifact = manager.downloadModelNow("smollm2-360m-instruct")
            assertNotNull(smol360Artifact, "Expected real SmolLM2_360M_instruct.litertlm to be provisioned and verified")
            assertEquals(
                LiteRtModelInstallState.INSTALLED,
                manager.currentSnapshot().entries.first { it.spec.id == "smollm2-360m-instruct" }.status,
            )
            assertTrue(manager.activateModel("smollm2-360m-instruct"))
            assertEquals("smollm2-360m-instruct", manager.currentSnapshot().activeModelId)

            // 5. Deleting active model automatically falls back to remaining verified model (smollm2-135m-instruct).
            manager.deleteModel("smollm2-360m-instruct")
            assertFalse(Files.exists(smol360Artifact.filePath))
            assertEquals("smollm2-135m-instruct", manager.currentSnapshot().activeModelId)
        } finally {
            manager.close()
        }
    }

    @Test
    fun androidAiCoreBridgeRoutesToSystemAiCoreAndFailsHonestlyOnError(@TempDir tempDir: Path) = runBlocking {
        var shouldFail = false
        val testAiCore = object : LiteRtAiCoreBridge {
            override fun probeStatus(): LiteRtAiCoreStatus = LiteRtAiCoreStatus(
                available = true,
                packageName = "com.google.android.aicore",
                versionName = "26.10.01.pixel11",
                systemServicePresent = true,
            )
            override fun generate(prompt: String, system: String?, maxTokens: Int): Result<String> {
                return if (shouldFail) {
                    Result.failure(IllegalStateException("AI Core foreground check failed"))
                } else {
                    Result.success("Hello from Gemini Nano on Tensor G6")
                }
            }
        }
        val manager = LiteRtLmServingManager(
            storageRoot = tempDir,
            aiCoreBridgeProvider = { testAiCore },
        )
        try {
            val snap = manager.currentSnapshot()
            assertEquals(LiteRtLmCatalog.AICORE_MODEL_ID, snap.activeModelId)
            val aiCoreEntry = snap.entries.first { it.spec.id == LiteRtLmCatalog.AICORE_MODEL_ID }
            assertEquals(LiteRtModelInstallState.ACTIVE, aiCoreEntry.status)
            assertTrue(aiCoreEntry.spec.isSystemAiCore)

            val gen = manager.generate("Test Pixel AI Core")
            assertTrue(gen.ok)
            assertEquals("Hello from Gemini Nano on Tensor G6", gen.text)

            // Verify honest failure when AI Core returns Result.failure (no fake fallback text!)
            shouldFail = true
            val failedGen = manager.generate("Test failure")
            assertFalse(failedGen.ok)
            assertTrue(failedGen.error.contains("AI Core foreground check failed"), failedGen.error)
        } finally {
            manager.close()
        }
    }

    @Test
    fun loopbackServerServesNativeGenerateAndOpenAiChatCompletionsWithRealEngine(@TempDir tempDir: Path) = runBlocking {
        val manager = LiteRtLmServingManager(storageRoot = tempDir)
        try {
            manager.setServerPort(0) // ephemeral port
            manager.setServerEnabled(true)
            val port = manager.currentSnapshot().serverPort
            assertTrue(port > 0, "Expected bound loopback port > 0, got $port")
            assertTrue(manager.currentSnapshot().serverRunning)

            // 1. With no models installed, /v1/litert/generate returns HTTP 400.
            val failConn = (URL("http://127.0.0.1:$port/v1/litert/generate").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(failConn.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"prompt":"test"}""")
            }
            assertEquals(400, failConn.responseCode)

            // 2. Provision & activate real SmolLM2 135M Instruct (.litertlm)
            assertNotNull(manager.downloadModelNow("smollm2-135m-instruct"))
            manager.activateModel("smollm2-135m-instruct")

            // 3. Query /v1/models
            val modelsBody = URL("http://127.0.0.1:$port/v1/models").readText(StandardCharsets.UTF_8)
            assertTrue(modelsBody.contains("smollm2-135m-instruct"), modelsBody)

            // 4. Native /v1/litert/generate endpoint backed by real com.google.ai.edge.litertlm.Engine
            val genConn = (URL("http://127.0.0.1:$port/v1/litert/generate").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(genConn.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"model":"smollm2-135m-instruct","delegate":"CPU","prompt":"Say hello","max_tokens":16}""")
            }
            assertEquals(200, genConn.responseCode)
            val genResp = genConn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            assertTrue(genResp.contains("\"ok\":true"), genResp)
            assertTrue(genResp.contains("\"model\":\"smollm2-135m-instruct\""), genResp)

            // 5. OpenAI-compatible SSE streaming via OpenAiProvider against http://127.0.0.1:$port
            val openAiClient = OpenAiProvider(OkHttpLlmTransport()).client(
                ProviderConfig(apiKey = "litert-local", baseUrl = "http://127.0.0.1:$port"),
            )
            val streamEvents = openAiClient.chat(
                LlmRequest(
                    model = "smollm2-135m-instruct",
                    system = "You are a concise assistant.",
                    messages = listOf(LlmMessage.user("Reply with the word hello.")),
                    thinking = false,
                ),
            ).toList()

            val fullText = streamEvents.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
            assertTrue(fullText.isNotBlank(), "Expected non-blank streamed text from real Engine, got: '$fullText'")
            assertTrue(manager.currentSnapshot().requestsServed >= 2L)
            assertTrue(manager.currentSnapshot().tokensGenerated > 0L)
        } finally {
            manager.close()
        }
    }

    @Test
    fun liteRtProviderAndGuestSnippetsReflectActiveState(@TempDir tempDir: Path) = runBlocking {
        val manager = LiteRtLmServingManager(storageRoot = tempDir)
        try {
            assertNotNull(manager.downloadModelNow("smollm2-135m-instruct"))
            manager.activateModel("smollm2-135m-instruct")
            manager.selectDelegate(LiteRtHardwareDelegate.CPU)
            manager.setServerPort(11499)

            val provider = LiteRtLmProvider { manager }
            val events = provider.client(ProviderConfig("")).chat(
                LlmRequest(
                    model = "smollm2-135m-instruct",
                    system = null,
                    messages = listOf(LlmMessage.user("Say hi.")),
                ),
            ).toList()
            val text = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
            assertTrue(text.isNotBlank(), "Expected real LiteRtLmProvider output, got blank")

            for (lang in LiteRtGuestLanguage.entries) {
                manager.selectGuestLanguage(lang)
                val snap = manager.currentSnapshot()
                assertEquals(lang, snap.selectedGuestLanguage)
                assertTrue(snap.guestSnippet.contains("11499"), "Snippet for $lang missing port: ${snap.guestSnippet}")
                assertTrue(snap.guestSnippet.contains("smollm2-135m-instruct"), "Snippet for $lang missing model: ${snap.guestSnippet}")
                assertTrue(snap.guestSnippet.contains("CPU"), "Snippet for $lang missing delegate: ${snap.guestSnippet}")
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun ccSwitchCoreModelMappingStackCodecCircuitBreakerAndLiveCliSync(@TempDir tempDir: Path) {
        // 1. Model tier mapping & [1M] stripping (cc-switch model_mapper.rs parity)
        val mapping = CcSwitchModelMapping(
            defaultModel = "deepseek-reasoner",
            haikuModel = "deepseek-chat",
            sonnetModel = "deepseek-v3.2",
            opusModel = "deepseek-r1",
            reasoningEffort = "high",
        )
        assertEquals("deepseek-chat", mapping.resolveModel("claude-haiku-4-5[1M]"))
        assertEquals("deepseek-v3.2", mapping.resolveModel("claude-sonnet-4-6[1m]"))
        assertEquals("deepseek-r1", mapping.resolveModel("claude-opus-4-6"))
        assertEquals("deepseek-reasoner", mapping.resolveModel("claude-3-7"))

        // 2. Stack codec (ccs-<key>/<model> and ccs-claude-<key>--<model> + strict prefix check)
        val encodedStandard = CcSwitchStackCodec.encodeAggregatedModel("openrouter", "deepseek/deepseek-r1")
        assertEquals("ccs-openrouter/deepseek/deepseek-r1", encodedStandard)
        val decodedStandard = CcSwitchStackCodec.decodeAggregatedModel(encodedStandard)
        assertNotNull(decodedStandard)
        assertEquals("openrouter", decodedStandard.first)
        assertEquals("deepseek/deepseek-r1", decodedStandard.second)

        val encodedClaude = CcSwitchStackCodec.encodeClaudeCodeModel("kimi", "kimi-k2.5")
        assertEquals("ccs-claude-kimi--kimi-k2.5", encodedClaude)
        val decodedClaude = CcSwitchStackCodec.decodeAggregatedModel(encodedClaude)
        assertNotNull(decodedClaude)
        assertEquals("kimi", decodedClaude.first)
        assertEquals("kimi-k2.5", decodedClaude.second)
        assertTrue(CcSwitchStackCodec.hasStackPrefix("ccs-unknown"))

        // 3. Circuit breaker state transitions (CLOSED -> OPEN -> HALF_OPEN -> CLOSED)
        var nowMs = 1_000L
        val breaker = CcSwitchCircuitBreaker(
            config = CcSwitchCircuitBreakerConfig(
                failureThreshold = 2,
                successThreshold = 2,
                timeoutSeconds = 1L,
                errorRateThreshold = 0.9,
                minRequests = 100,
            ),
            clockMs = { nowMs },
        )
        assertEquals(CcSwitchCircuitState.CLOSED, breaker.snapshot().state)
        assertTrue(breaker.allowRequest().allowed)
        breaker.recordFailure(usedHalfOpenPermit = false, errorMessage = "HTTP 502 Bad Gateway", latencyMs = 20L)
        assertEquals(CcSwitchCircuitState.CLOSED, breaker.snapshot().state)
        nowMs = 1_100L
        breaker.recordFailure(usedHalfOpenPermit = false, errorMessage = "HTTP 503 Service Unavailable", latencyMs = 20L)
        assertEquals(CcSwitchCircuitState.OPEN, breaker.snapshot().state)
        nowMs = 1_500L
        assertFalse(breaker.allowRequest().allowed, "OPEN circuit must reject requests before timeout")

        // After timeout (1_100 + 1_000 = 2_100ms), transitions to HALF_OPEN
        nowMs = 2_200L
        val halfOpenPermit = breaker.allowRequest()
        assertTrue(halfOpenPermit.allowed, "Circuit should transition to HALF_OPEN after timeout")
        assertTrue(halfOpenPermit.usedHalfOpenPermit)
        assertEquals(CcSwitchCircuitState.HALF_OPEN, breaker.snapshot().state)
        breaker.recordSuccess(usedHalfOpenPermit = true, latencyMs = 45L)
        assertEquals(CcSwitchCircuitState.HALF_OPEN, breaker.snapshot().state)
        breaker.recordSuccess(usedHalfOpenPermit = true, latencyMs = 38L)
        assertEquals(CcSwitchCircuitState.CLOSED, breaker.snapshot().state)

        // 4. Non-destructive Live CLI Config Sync with first-write backup preservation
        val claudeSettings = tempDir.resolve(".claude/settings.json")
        Files.createDirectories(claudeSettings.parent)
        Files.writeString(claudeSettings, """{"customUserFlag":true,"env":{"EXISTING_VAR":"keep"}}""")

        val deepseekPreset = CcSwitchPresetCatalog.findPreset("deepseek")
        assertNotNull(deepseekPreset)
        val provider = CcSwitchPresetCatalog.toInitialProfile(deepseekPreset, priority = 1, inFailover = true, inStack = true)
            .copy(apiKey = "sk-deepseek-real-test")

        val syncer = CcSwitchLiveConfigSyncer(
            storageRoot = tempDir,
            targetRootProvider = { tempDir },
        )
        val targetsFirstSync = syncer.syncAll(
            mode = CcSwitchOperatingMode.ROUTING,
            activeProfile = provider,
            proxyPort = 18781,
        )
        assertEquals(3, targetsFirstSync.size)
        val claudeTarget = targetsFirstSync.first { it.toolId == "claude" }
        assertTrue(claudeTarget.hasBackup, "Expected first-write backup to be created for existing .claude/settings.json")
        val backupContent = Files.readString(Path.of(claudeTarget.backupFilePath))
        assertTrue(backupContent.contains("customUserFlag"), "Backup must preserve original user file")

        val syncedClaudeJson = Files.readString(claudeSettings)
        assertTrue(syncedClaudeJson.contains("\"customUserFlag\":true"), "Non-destructive sync must preserve customUserFlag")
        assertTrue(syncedClaudeJson.contains("http://127.0.0.1:18781"), "Routing mode must point ANTHROPIC_BASE_URL at local proxy")
        assertTrue(syncedClaudeJson.contains("EXISTING_VAR"), "Non-destructive sync must preserve unrelated env keys")

        // Second sync in DIRECT mode must NOT overwrite the first-write backup
        Files.writeString(claudeSettings, """{"modifiedLater":true}""")
        syncer.syncAll(
            mode = CcSwitchOperatingMode.DIRECT,
            activeProfile = provider,
            proxyPort = 18781,
        )
        val backupAfterSecondSync = Files.readString(Path.of(claudeTarget.backupFilePath))
        assertTrue(backupAfterSecondSync.contains("customUserFlag") && !backupAfterSecondSync.contains("modifiedLater"))
    }

    @Test
    fun universalProxyHandlesCrossProtocolFailoverAndAggregationStack(@TempDir tempDir: Path) = runBlocking {
        // Spin up two real local HTTP upstream servers to test P1 -> P2 failover, circuit breaker,
        // cross-protocol translation (Anthropic /v1/messages, OpenAI /v1/responses, Gemini /v1beta/models),
        // and Aggregation Stack (ccs-<provider>/<model>).
        var primaryShouldFail = false
        var primaryHits = 0
        var secondaryHits = 0

        val primaryUpstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        primaryUpstream.createContext("/v1/chat/completions") { exchange ->
            primaryHits++
            if (primaryShouldFail) {
                val err = """{"error":{"message":"Primary upstream overloaded"}}""".toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(503, err.size.toLong())
                exchange.responseBody.use { it.write(err) }
            } else {
                val sse = buildString {
                    append("data: {\"choices\":[{\"delta\":{\"content\":\"Hello from Primary P1\"}}]}\n\n")
                    append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\n")
                    append("data: [DONE]\n\n")
                }.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, sse.size.toLong())
                exchange.responseBody.use { it.write(sse) }
            }
        }
        primaryUpstream.start()
        val primaryPort = primaryUpstream.address.port

        val secondaryUpstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        secondaryUpstream.createContext("/v1/chat/completions") { exchange ->
            secondaryHits++
            val sse = buildString {
                append("data: {\"choices\":[{\"delta\":{\"content\":\"Recovered via Failover P2\"}}]}\n\n")
                append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":4}}\n\n")
                append("data: [DONE]\n\n")
            }.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, sse.size.toLong())
            exchange.responseBody.use { it.write(sse) }
        }
        secondaryUpstream.start()
        val secondaryPort = secondaryUpstream.address.port

        val manager = LiteRtLmServingManager(storageRoot = tempDir)
        try {
            // Configure P1 and P2 universal providers pointing at the real local HTTP servers
            manager.upsertProviderProfile(
                CcSwitchProviderProfile(
                    id = "primary-lab",
                    providerKey = "primary",
                    name = "Primary Lab P1",
                    presetId = "custom",
                    category = CcSwitchProviderCategory.OFFICIAL,
                    apiFormat = CcSwitchApiFormat.OPENAI_CHAT,
                    baseUrl = "http://127.0.0.1:$primaryPort",
                    endpointCandidates = listOf("http://127.0.0.1:$primaryPort"),
                    apiKey = "sk-p1",
                    defaultModel = "p1-default",
                    models = listOf(LlmModelInfo("p1-default", "P1 Default")),
                    modelMapping = CcSwitchModelMapping(
                        defaultModel = "p1-default",
                        haikuModel = "p1-haiku",
                        sonnetModel = "p1-sonnet",
                        opusModel = "p1-opus",
                    ),
                    inFailoverQueue = true,
                    failoverPriority = 1,
                    inAggregationStack = true,
                    requiresApiKey = true,
                ),
            )
            manager.upsertProviderProfile(
                CcSwitchProviderProfile(
                    id = "secondary-lab",
                    providerKey = "secondary",
                    name = "Secondary Backup P2",
                    presetId = "custom",
                    category = CcSwitchProviderCategory.AGGREGATOR,
                    apiFormat = CcSwitchApiFormat.OPENAI_CHAT,
                    baseUrl = "http://127.0.0.1:$secondaryPort",
                    endpointCandidates = listOf("http://127.0.0.1:$secondaryPort"),
                    apiKey = "sk-p2",
                    defaultModel = "p2-backup",
                    models = listOf(LlmModelInfo("p2-backup", "P2 Backup")),
                    modelMapping = CcSwitchModelMapping(defaultModel = "p2-backup"),
                    inFailoverQueue = true,
                    failoverPriority = 2,
                    inAggregationStack = true,
                    requiresApiKey = true,
                ),
            )

            // Active provider protection: cannot delete active provider
            assertNotNull(manager.switchUniversalProvider("primary-lab"))
            assertFalse(manager.deleteProviderProfile("primary-lab"), "Must protect active provider from deletion")

            // Switch to ROUTING mode and start local loopback server
            manager.selectSwitchMode(CcSwitchOperatingMode.ROUTING)
            manager.setServerPort(0)
            manager.setServerEnabled(true)
            val proxyPort = manager.currentSnapshot().serverPort
            assertTrue(proxyPort > 0)

            // 1. Send an Anthropic /v1/messages request to the local proxy -> translates to OpenAI upstream P1 -> returns Anthropic JSON
            val anthropicConn = (URL("http://127.0.0.1:$proxyPort/v1/messages").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(anthropicConn.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"model":"claude-sonnet-4-6[1M]","stream":false,"messages":[{"role":"user","content":"Ping"}]}""")
            }
            assertEquals(200, anthropicConn.responseCode)
            assertEquals("primary-lab", anthropicConn.getHeaderField("X-CC-Switch-Provider"))
            assertEquals("p1-sonnet", anthropicConn.getHeaderField("X-CC-Switch-Model"))
            val anthropicResp = anthropicConn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            assertTrue(anthropicResp.contains("Hello from Primary P1"), anthropicResp)

            // 2. Induce P1 failure -> verify automatic failover to P2 on OpenAI /v1/responses endpoint!
            primaryShouldFail = true
            val responsesConn = (URL("http://127.0.0.1:$proxyPort/v1/responses").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(responsesConn.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"model":"gpt-5.4","stream":false,"input":"Test failover"}""")
            }
            assertEquals(200, responsesConn.responseCode)
            assertEquals("secondary-lab", responsesConn.getHeaderField("X-CC-Switch-Provider"))
            val responsesBody = responsesConn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            assertTrue(responsesBody.contains("Recovered via Failover P2"), responsesBody)
            assertTrue(secondaryHits >= 1)

            // 3. Switch to AGGREGATION mode and route explicitly to P2 via stacked model ID `ccs-claude-secondary--p2-backup` on Gemini endpoint
            manager.selectSwitchMode(CcSwitchOperatingMode.AGGREGATION)
            val geminiConn = (URL("http://127.0.0.1:$proxyPort/v1beta/models/ccs-claude-secondary--p2-backup:generateContent").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(geminiConn.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"contents":[{"role":"user","parts":[{"text":"Hello Gemini bridge"}]}]}""")
            }
            assertEquals(200, geminiConn.responseCode)
            assertEquals("secondary-lab", geminiConn.getHeaderField("X-CC-Switch-Provider"))
            val geminiBody = geminiConn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            assertTrue(geminiBody.contains("Recovered via Failover P2"), geminiBody)

            // 4. Verify strict ccs- prefix validation: unknown ccs- provider returns 400 error (no silent fallback)
            val badStackConn = (URL("http://127.0.0.1:$proxyPort/v1/messages").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(badStackConn.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"model":"ccs-nonexistent/some-model","stream":false,"messages":[{"role":"user","content":"Ping"}]}""")
            }
            assertEquals(400, badStackConn.responseCode)
        } finally {
            manager.close()
            primaryUpstream.stop(0)
            secondaryUpstream.stop(0)
        }
    }
}
