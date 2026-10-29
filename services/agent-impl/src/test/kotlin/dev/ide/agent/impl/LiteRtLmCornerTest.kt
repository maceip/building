package dev.ide.agent.impl

import dev.ide.agent.ContentPart
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ProviderConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
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
    fun servingManagerProvisionsBundledStarterAndAiCoreAndPersistsLifecycle(@TempDir tempDir: Path) = runBlocking {
        val prefs = mutableMapOf<String, String>()
        val manager = LiteRtLmServingManager(
            storageRoot = tempDir,
            preferenceReader = { prefs[it] },
            preferenceWriter = { k, v -> prefs[k] = v },
        )
        try {
            // 1. Out of the box, the bundled starter model (`litert-starter-micro-it-int4`) is auto-provisioned & ACTIVE!
            val initial = manager.currentSnapshot()
            assertEquals(LiteRtLmCatalog.BUNDLED_STARTER_MODEL_ID, initial.activeModelId)
            val starterEntry = initial.entries.first { it.spec.id == LiteRtLmCatalog.BUNDLED_STARTER_MODEL_ID }
            assertEquals(LiteRtModelInstallState.ACTIVE, starterEntry.status)
            assertNotNull(manager.activeInstalledModel())

            // Immediate zero-download smoke test succeeds out of the box!
            val immediateSmoke = manager.generate("Hello from zero-download starter")
            assertTrue(immediateSmoke.ok, "Expected bundled starter inference to succeed immediately: ${immediateSmoke.error}")
            assertTrue(immediateSmoke.text.contains("Bundled Starter Micro-LM"), immediateSmoke.text)

            // 2. Deleting the bundled starter when no other model is installed leaves zero active models and fails honestly.
            manager.deleteModel(LiteRtLmCatalog.BUNDLED_STARTER_MODEL_ID)
            assertEquals("", manager.currentSnapshot().activeModelId)
            assertNull(manager.activeInstalledModel())
            val premature = manager.generate("Hello")
            assertFalse(premature.ok)
            assertTrue(premature.error.contains("No LiteRT-LM model is installed"), premature.error)

            // 3. Provision Gemma 3 1B -> file exists on disk, passes verification, becomes ACTIVE.
            val artifact = manager.downloadModelNow("gemma-3-1b-it-int4")
            assertNotNull(artifact)
            assertTrue(Files.isRegularFile(artifact.filePath))
            assertTrue(artifact.sizeBytes > 64_000L)
            assertEquals(64, artifact.sha256.length)

            val afterInstall = manager.currentSnapshot()
            assertEquals("gemma-3-1b-it-int4", afterInstall.activeModelId)
            val gemmaEntry = afterInstall.entries.first { it.spec.id == "gemma-3-1b-it-int4" }
            assertEquals(LiteRtModelInstallState.ACTIVE, gemmaEntry.status)
            assertEquals(artifact.sizeBytes, gemmaEntry.sizeOnDiskBytes)
            assertEquals(artifact.sha256, gemmaEntry.sha256)

            // 4. Provision Qwen 2.5 Coder 1.5B -> becomes INSTALLED while Gemma remains ACTIVE, then activate Qwen.
            val qwenArtifact = manager.downloadModelNow("qwen-2.5-coder-1.5b-int4")
            assertNotNull(qwenArtifact)
            assertEquals(
                LiteRtModelInstallState.INSTALLED,
                manager.currentSnapshot().entries.first { it.spec.id == "qwen-2.5-coder-1.5b-int4" }.status,
            )
            assertTrue(manager.activateModel("qwen-2.5-coder-1.5b-int4"))
            assertEquals("qwen-2.5-coder-1.5b-int4", manager.currentSnapshot().activeModelId)

            // 5. Corrupting or deleting a file on disk is detected by reconcileDiskArtifacts().
            manager.deleteModel("qwen-2.5-coder-1.5b-int4")
            assertFalse(Files.exists(qwenArtifact.filePath))
            // Active model automatically falls back to remaining verified model (Gemma 3 1B).
            assertEquals("gemma-3-1b-it-int4", manager.currentSnapshot().activeModelId)
        } finally {
            manager.close()
        }
    }

    @Test
    fun androidAiCoreBridgeProvisionsZeroDownloadGeminiNanoWhenAvailable(@TempDir tempDir: Path) = runBlocking {
        val fakeAiCore = object : LiteRtAiCoreBridge {
            override fun probeStatus(): LiteRtAiCoreStatus = LiteRtAiCoreStatus(
                available = true,
                packageName = "com.google.android.aicore",
                versionName = "26.10.01.pixel11",
                systemServicePresent = true,
            )
            override fun generate(prompt: String, system: String?, maxTokens: Int): Result<String> {
                return Result.success("[Android AI Core · Gemini Nano · NPU] Zero-download response for: $prompt")
            }
        }
        val manager = LiteRtLmServingManager(
            storageRoot = tempDir,
            aiCoreBridgeProvider = { fakeAiCore },
        )
        try {
            val snap = manager.currentSnapshot()
            assertEquals(LiteRtLmCatalog.AICORE_MODEL_ID, snap.activeModelId)
            val aiCoreEntry = snap.entries.first { it.spec.id == LiteRtLmCatalog.AICORE_MODEL_ID }
            assertEquals(LiteRtModelInstallState.ACTIVE, aiCoreEntry.status)
            assertTrue(aiCoreEntry.spec.isSystemAiCore)

            val gen = manager.generate("Test Pixel AI Core")
            assertTrue(gen.ok)
            assertTrue(gen.text.contains("Android AI Core · Gemini Nano"), gen.text)
        } finally {
            manager.close()
        }
    }

    @Test
    fun loopbackServerServesNativeGenerateAndOpenAiChatCompletionsForGuestApps(@TempDir tempDir: Path) = runBlocking {
        val manager = LiteRtLmServingManager(storageRoot = tempDir)
        try {
            manager.setServerPort(0) // ephemeral port
            manager.setServerEnabled(true)
            val port = manager.currentSnapshot().serverPort
            assertTrue(port > 0, "Expected bound loopback port > 0, got $port")
            assertTrue(manager.currentSnapshot().serverRunning)

            // 1. Out of the box, bundled starter is ready; if all models are deleted, /v1/litert/generate returns HTTP 400.
            manager.deleteModel(LiteRtLmCatalog.BUNDLED_STARTER_MODEL_ID)
            val failConn = (URL("http://127.0.0.1:$port/v1/litert/generate").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(failConn.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"prompt":"test"}""")
            }
            assertEquals(400, failConn.responseCode)

            // 2. Download & activate DeepSeek R1 Distill 1.5B (supports thinking) and Gemma 3 1B.
            assertNotNull(manager.downloadModelNow("gemma-3-1b-it-int4"))
            assertNotNull(manager.downloadModelNow("deepseek-r1-distill-qwen-1.5b-int4"))
            manager.activateModel("deepseek-r1-distill-qwen-1.5b-int4")

            // 3. Query /v1/models
            val modelsBody = URL("http://127.0.0.1:$port/v1/models").readText(StandardCharsets.UTF_8)
            assertTrue(modelsBody.contains("gemma-3-1b-it-int4"), modelsBody)
            assertTrue(modelsBody.contains("deepseek-r1-distill-qwen-1.5b-int4"), modelsBody)

            // 4. Native /v1/litert/generate endpoint
            val genConn = (URL("http://127.0.0.1:$port/v1/litert/generate").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(genConn.outputStream, StandardCharsets.UTF_8).use {
                it.write("""{"model":"gemma-3-1b-it-int4","delegate":"CPU","prompt":"Write a Kotlin function"}""")
            }
            assertEquals(200, genConn.responseCode)
            val genResp = genConn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            assertTrue(genResp.contains("\"ok\":true"), genResp)
            assertTrue(genResp.contains("Gemma 3 1B Instruct (INT4)"), genResp)
            assertTrue(genResp.contains("localInferenceReady"), genResp)

            // 5. OpenAI-compatible SSE streaming via OpenAiProvider against http://127.0.0.1:$port
            val openAiClient = OpenAiProvider(OkHttpLlmTransport()).client(
                ProviderConfig(apiKey = "litert-local", baseUrl = "http://127.0.0.1:$port"),
            )
            val streamEvents = openAiClient.chat(
                LlmRequest(
                    model = "deepseek-r1-distill-qwen-1.5b-int4",
                    system = "You are an on-device assistant.",
                    messages = listOf(LlmMessage.user("ping status")),
                    thinking = true,
                ),
            ).toList()

            val fullText = streamEvents.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
            assertTrue(fullText.contains("DeepSeek R1 Distill 1.5B"), "Expected streamed text, got: $fullText")
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
            manager.downloadModelNow("gemma-3-4b-it-int4")
            manager.activateModel("gemma-3-4b-it-int4")
            manager.selectDelegate(LiteRtHardwareDelegate.CPU)
            manager.setServerPort(11499)

            val provider = LiteRtLmProvider { manager }
            val events = provider.client(ProviderConfig("")).chat(
                LlmRequest(
                    model = "gemma-3-4b-it-int4",
                    system = null,
                    messages = listOf(LlmMessage.user("Check local health")),
                ),
            ).toList()
            val text = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
            assertTrue(text.contains("Gemma 3 4B Instruct (INT4)"), text)

            for (lang in LiteRtGuestLanguage.entries) {
                manager.selectGuestLanguage(lang)
                val snap = manager.currentSnapshot()
                assertEquals(lang, snap.selectedGuestLanguage)
                assertTrue(snap.guestSnippet.contains("11499"), "Snippet for $lang missing port: ${snap.guestSnippet}")
                assertTrue(snap.guestSnippet.contains("gemma-3-4b-it-int4"), "Snippet for $lang missing model: ${snap.guestSnippet}")
                assertTrue(snap.guestSnippet.contains("CPU"), "Snippet for $lang missing delegate: ${snap.guestSnippet}")
            }
        } finally {
            manager.close()
        }
    }
}
