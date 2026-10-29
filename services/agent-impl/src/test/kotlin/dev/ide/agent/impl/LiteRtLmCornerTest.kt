package dev.ide.agent.impl

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
}
