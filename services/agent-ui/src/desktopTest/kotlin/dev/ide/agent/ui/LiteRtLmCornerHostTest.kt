package dev.ide.agent.ui

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.UiAgentChatState
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentModel
import dev.ide.ui.backend.UiAgentPermissionDecision
import dev.ide.ui.backend.UiAgentPermissionMode
import dev.ide.ui.backend.UiAgentPermissionRequest
import dev.ide.ui.backend.UiLiteRtCornerState
import dev.ide.ui.backend.UiLiteRtDelegate
import dev.ide.ui.backend.UiLiteRtGuestLanguage
import dev.ide.ui.backend.UiLiteRtHardwareInfo
import dev.ide.ui.backend.UiLiteRtModelEntry
import dev.ide.ui.backend.UiLiteRtModelStatus
import dev.ide.ui.backend.UiLiteRtServerStatus
import dev.ide.ui.ext.ScreenContext
import dev.ide.ui.testing.DeviceConfig
import dev.ide.ui.testing.PaparazziSession
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Automated end-to-end Compose UI & Paparazzi audit test for [LiteRtLmCornerScreen] and [LiteRtLmCornerHost]:
 * - Exercises hardware delegate switching (`CPU`, `GPU`, `NPU`).
 * - Exercises model catalog provisioning (`Download`, `Activate`, `Use in IDE Agent`, `Delete`).
 * - Exercises local loopback HTTP server toggle, endpoint copy actions, and live local smoke test execution.
 * - Exercises guest-app SDK language switching (`Kotlin / Android`, `Swift / iOS`, `Node.js`, `Python`),
 *   snippet copy, and project client scaffolding (`Add client to project`).
 * - Runs [PaparazziSession.assertNoDeadClickables] to prove zero dead/mock clickables exist on the screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LiteRtLmCornerHostTest {

    private class FakeLiteRtAgentService : AgentService {
        override val chatState: StateFlow<UiAgentChatState> = MutableStateFlow(UiAgentChatState())
        override val permissionRequest: StateFlow<UiAgentPermissionRequest?> = MutableStateFlow(null)
        override val models: StateFlow<List<UiAgentModel>> = MutableStateFlow(emptyList())

        var selectedProviderId: String = "openai"
        var selectedModelId: String = "gpt-5.4"
        var selectedDelegate: UiLiteRtDelegate = UiLiteRtDelegate.GPU
        var activeLiteRtModelId: String = ""
        var serverEnabled: Boolean = false
        var serverPort: Int = 18088
        var guestLanguage: UiLiteRtGuestLanguage = UiLiteRtGuestLanguage.KOTLIN_ANDROID
        var smokeResponse: String = ""
        var statusBanner: String = ""
        val scaffoldedLanguages = mutableListOf<UiLiteRtGuestLanguage>()
        val modelStatuses = linkedMapOf(
            "gemma-3-1b-it-int4" to UiLiteRtModelStatus.NOT_INSTALLED,
            "qwen-2.5-coder-1.5b-int4" to UiLiteRtModelStatus.NOT_INSTALLED,
        )

        private val _cornerState = MutableStateFlow(buildCornerState())
        override val liteRtCornerState: StateFlow<UiLiteRtCornerState> = _cornerState

        private fun sync() {
            _cornerState.value = buildCornerState()
        }

        private fun buildCornerState(): UiLiteRtCornerState {
            val entries = listOf(
                UiLiteRtModelEntry(
                    id = "gemma-3-1b-it-int4",
                    displayName = "Gemma 3 1B Instruct",
                    family = "Gemma 3",
                    parameterCount = "1B",
                    quantization = "INT4",
                    description = "Ultra-fast compact instruction model for on-device chat and summarization.",
                    estimatedSizeBytes = 690_000_000L,
                    downloadedBytes = if (modelStatuses["gemma-3-1b-it-int4"] == UiLiteRtModelStatus.NOT_INSTALLED) 0L else 690_000_000L,
                    sizeOnDiskBytes = if (modelStatuses["gemma-3-1b-it-int4"] == UiLiteRtModelStatus.NOT_INSTALLED) 0L else 690_000_000L,
                    contextLength = 32768,
                    supportedDelegates = listOf(UiLiteRtDelegate.CPU, UiLiteRtDelegate.GPU, UiLiteRtDelegate.NPU),
                    supportsToolCalling = true,
                    supportsThinking = false,
                    status = modelStatuses["gemma-3-1b-it-int4"] ?: UiLiteRtModelStatus.NOT_INSTALLED,
                    localFilePath = "/data/user/0/dev.ide.android/files/litert-lm/models/gemma-3-1b-it-int4.litertlm",
                    sha256 = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
                ),
                UiLiteRtModelEntry(
                    id = "qwen-2.5-coder-1.5b-int4",
                    displayName = "Qwen 2.5 Coder 1.5B",
                    family = "Qwen 2.5 Coder",
                    parameterCount = "1.5B",
                    quantization = "INT4",
                    description = "Dedicated on-device coding specialist for Kotlin, Swift, TypeScript, and Python.",
                    estimatedSizeBytes = 980_000_000L,
                    downloadedBytes = if (modelStatuses["qwen-2.5-coder-1.5b-int4"] == UiLiteRtModelStatus.NOT_INSTALLED) 0L else 980_000_000L,
                    sizeOnDiskBytes = if (modelStatuses["qwen-2.5-coder-1.5b-int4"] == UiLiteRtModelStatus.NOT_INSTALLED) 0L else 980_000_000L,
                    contextLength = 32768,
                    supportedDelegates = listOf(UiLiteRtDelegate.CPU, UiLiteRtDelegate.GPU, UiLiteRtDelegate.NPU),
                    supportsToolCalling = true,
                    supportsThinking = false,
                    status = modelStatuses["qwen-2.5-coder-1.5b-int4"] ?: UiLiteRtModelStatus.NOT_INSTALLED,
                    localFilePath = "/data/user/0/dev.ide.android/files/litert-lm/models/qwen-2.5-coder-1.5b-int4.litertlm",
                    sha256 = "f0e1d2c3b4a5968778695a4b3c2d1e0ff0e1d2c3b4a5968778695a4b3c2d1e0f",
                ),
            )
            return UiLiteRtCornerState(
                hardware = UiLiteRtHardwareInfo(
                    socName = "Google Tensor G4 (Pixel 9 Pro)",
                    availableDelegates = listOf(UiLiteRtDelegate.CPU, UiLiteRtDelegate.GPU, UiLiteRtDelegate.NPU),
                    recommendedDelegate = UiLiteRtDelegate.NPU,
                    selectedDelegate = selectedDelegate,
                    freeStorageBytes = 42_000_000_000L,
                    usedModelStorageBytes = entries.sumOf { it.sizeOnDiskBytes },
                    modelsDirectoryPath = "/data/user/0/dev.ide.android/files/litert-lm/models",
                ),
                models = entries,
                activeModelId = activeLiteRtModelId,
                server = UiLiteRtServerStatus(
                    enabled = serverEnabled,
                    running = serverEnabled,
                    port = serverPort,
                    openAiEndpoint = "http://127.0.0.1:$serverPort/v1/chat/completions",
                    nativeEndpoint = "http://127.0.0.1:$serverPort/v1/litert/generate",
                    ipcUri = "content://dev.ide.android.litertlm/models",
                    requestsServed = if (smokeResponse.isNotBlank()) 1L else 0L,
                    tokensGenerated = if (smokeResponse.isNotBlank()) 18L else 0L,
                    lastLatencyMs = if (smokeResponse.isNotBlank()) 12L else 0L,
                ),
                selectedGuestLanguage = guestLanguage,
                guestSnippet = "// Guest SDK snippet for ${guestLanguage.name} on port $serverPort (model=${activeLiteRtModelId.ifBlank { "gemma-3-1b-it-int4" }})",
                lastSmokeTestPrompt = "Reply with a one-line status check from LiteRT-LM.",
                lastSmokeTestResponse = smokeResponse,
                lastSmokeTestBusy = false,
                statusBanner = statusBanner,
            )
        }

        override fun liteRtCorner(): UiLiteRtCornerState = _cornerState.value

        override fun selectLiteRtDelegate(delegate: UiLiteRtDelegate) {
            selectedDelegate = delegate
            statusBanner = "Delegate set to ${delegate.name}"
            sync()
        }

        override fun downloadLiteRtModel(modelId: String) {
            if (activeLiteRtModelId.isBlank()) {
                activeLiteRtModelId = modelId
                modelStatuses[modelId] = UiLiteRtModelStatus.ACTIVE
            } else {
                modelStatuses[modelId] = UiLiteRtModelStatus.INSTALLED
            }
            statusBanner = "Installed & verified $modelId"
            sync()
        }

        override fun cancelLiteRtDownload(modelId: String) {
            modelStatuses[modelId] = UiLiteRtModelStatus.NOT_INSTALLED
            sync()
        }

        override fun deleteLiteRtModel(modelId: String) {
            modelStatuses[modelId] = UiLiteRtModelStatus.NOT_INSTALLED
            if (activeLiteRtModelId == modelId) {
                val fallback = modelStatuses.entries.firstOrNull {
                    it.value == UiLiteRtModelStatus.INSTALLED || it.value == UiLiteRtModelStatus.ACTIVE
                }?.key.orEmpty()
                activeLiteRtModelId = fallback
                if (fallback.isNotBlank()) {
                    modelStatuses[fallback] = UiLiteRtModelStatus.ACTIVE
                }
            }
            statusBanner = "Deleted $modelId"
            sync()
        }

        override fun activateLiteRtModel(modelId: String) {
            modelStatuses.keys.forEach { k ->
                if (modelStatuses[k] == UiLiteRtModelStatus.ACTIVE) {
                    modelStatuses[k] = UiLiteRtModelStatus.INSTALLED
                }
            }
            modelStatuses[modelId] = UiLiteRtModelStatus.ACTIVE
            activeLiteRtModelId = modelId
            statusBanner = "Activated $modelId"
            sync()
        }

        override fun useLiteRtInIdeAgent(modelId: String) {
            activateLiteRtModel(modelId)
            selectedProviderId = "litert"
            selectedModelId = modelId
            statusBanner = "Active IDE Agent switched to LiteRT-LM ($modelId)"
            sync()
        }

        override fun setLiteRtServerEnabled(enabled: Boolean) {
            serverEnabled = enabled
            statusBanner = if (enabled) "Loopback server started" else "Loopback server stopped"
            sync()
        }

        override fun setLiteRtServerPort(port: Int) {
            serverPort = port
            sync()
        }

        override fun selectLiteRtGuestLanguage(language: UiLiteRtGuestLanguage) {
            guestLanguage = language
            sync()
        }

        override fun runLiteRtSmokeTest(prompt: String) {
            smokeResponse = "[LiteRT-LM On-Device · ${activeLiteRtModelId.ifBlank { "gemma-3-1b-it-int4" }} · ${selectedDelegate.name}] Local runtime healthy: $prompt"
            statusBanner = "Smoke test completed"
            sync()
        }

        override fun scaffoldLiteRtGuestClient(language: UiLiteRtGuestLanguage): String {
            scaffoldedLanguages += language
            val path = "/workspace/src/LiteRtClient.${language.name.lowercase()}"
            statusBanner = "Added client to project: $path"
            sync()
            return path
        }

        override fun config(): UiAgentConfig = UiAgentConfig(
            selectedProvider = selectedProviderId,
            model = selectedModelId,
            configured = true,
            mode = UiAgentPermissionMode.ASK_EACH,
        )

        override fun selectProvider(id: String) { selectedProviderId = id }
        override fun setProviderKey(providerId: String, key: String) {}
        override fun setProviderBaseUrl(providerId: String, baseUrl: String) {}
        override fun setModel(model: String) { selectedModelId = model }
        override fun setEffort(effort: String) {}
        override fun setPermissionMode(mode: UiAgentPermissionMode) {}
        override fun setGateway(baseUrl: String, model: String, caCert: String) {}
        override fun refreshModels() {}
        override fun send(text: String) {}
        override fun retry() {}
        override fun stop() {}
        override fun newSession() {}
        override fun answerPermission(id: Int, decision: UiAgentPermissionDecision) {}
    }

    private fun collectAllSemanticsNodes(scene: ImageComposeScene): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            out += node
            node.children.forEach(::visit)
        }
        scene.semanticsOwners.forEach { owner ->
            visit(owner.unmergedRootSemanticsNode)
        }
        return out
    }

    private fun collectTexts(scene: ImageComposeScene): List<String> =
        collectAllSemanticsNodes(scene).flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
        }

    private fun findNodeByText(scene: ImageComposeScene, text: String): SemanticsNode? =
        collectAllSemanticsNodes(scene).firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }

    @Test
    fun exercisesDelegatesCatalogLoopbackServerSmokeTestAndGuestSdkScaffolding() {
        val fakeAgent = FakeLiteRtAgentService()
        val backend = object : StubBackend() {
            override val agent: AgentService = fakeAgent
        }
        var backCalled = false
        val ctx = object : ScreenContext {
            override val backend = backend
            override fun back() {
                backCalled = true
            }
            override fun openScreen(id: String) {}
        }

        val scene = ImageComposeScene(width = 860, height = 4800, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                LiteRtLmCornerScreen(ctx)
            }
        }

        var time = 0L
        fun frames(count: Int = 10) {
            Snapshot.sendApplyNotifications()
            repeat(count) {
                time += 16_666_667L
                scene.render(time)
            }
        }

        fun clickText(label: String) {
            val node = findNodeByText(scene, label)
            assertNotNull(node, "Expected semantics node with text '$label' (present: ${collectTexts(scene)})")
            val center = node.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, center)
            scene.sendPointerEvent(PointerEventType.Release, center)
            frames()
        }

        try {
            frames()
            val initialTexts = collectTexts(scene)
            assertTrue("Self-Serve LiteRT-LM Corner" in initialTexts)
            assertTrue("Google Tensor G4 (Pixel 9 Pro)" in initialTexts)
            assertTrue("Gemma 3 1B Instruct" in initialTexts)
            assertTrue("Qwen 2.5 Coder 1.5B" in initialTexts)

            // 1. Switch hardware delegate to NPU
            clickText("NPU")
            assertEquals(UiLiteRtDelegate.NPU, fakeAgent.selectedDelegate)
            assertTrue("NPU active" in collectTexts(scene))

            // 2. Download Gemma 3 1B Instruct -> becomes ACTIVE
            clickText("Download Gemma 3 1B Instruct")
            assertEquals("gemma-3-1b-it-int4", fakeAgent.activeLiteRtModelId)
            assertEquals(UiLiteRtModelStatus.ACTIVE, fakeAgent.modelStatuses["gemma-3-1b-it-int4"])

            // 3. Download Qwen 2.5 Coder 1.5B -> becomes INSTALLED, then Activate it
            clickText("Download Qwen 2.5 Coder 1.5B")
            assertEquals(UiLiteRtModelStatus.INSTALLED, fakeAgent.modelStatuses["qwen-2.5-coder-1.5b-int4"])
            clickText("Activate Qwen 2.5 Coder 1.5B")
            assertEquals("qwen-2.5-coder-1.5b-int4", fakeAgent.activeLiteRtModelId)
            assertEquals(UiLiteRtModelStatus.ACTIVE, fakeAgent.modelStatuses["qwen-2.5-coder-1.5b-int4"])

            // 4. Use Qwen 2.5 Coder 1.5B in IDE Agent -> switches IDE Agent provider to "litert"
            clickText("Use in IDE Agent (Qwen 2.5 Coder 1.5B)")
            assertEquals("litert", fakeAgent.selectedProviderId)
            assertEquals("qwen-2.5-coder-1.5b-int4", fakeAgent.selectedModelId)

            // 5. Delete Gemma 3 1B Instruct -> reverts to NOT_INSTALLED
            clickText("Delete Gemma 3 1B Instruct")
            assertEquals(UiLiteRtModelStatus.NOT_INSTALLED, fakeAgent.modelStatuses["gemma-3-1b-it-int4"])

            // 6. Run local smoke test -> populates live response
            clickText("Run local smoke test")
            assertTrue(fakeAgent.smokeResponse.contains("Local runtime healthy"))
            assertTrue(collectTexts(scene).any { it.contains("Local runtime healthy") })

            // 7. Switch Guest SDK language tabs & scaffold client into project
            clickText("Swift")
            assertEquals(UiLiteRtGuestLanguage.SWIFT, fakeAgent.guestLanguage)
            assertTrue(collectTexts(scene).any { it.contains("Guest SDK snippet for SWIFT") })

            clickText("Python")
            assertEquals(UiLiteRtGuestLanguage.PYTHON, fakeAgent.guestLanguage)

            clickText("Add client to project")
            assertEquals(listOf(UiLiteRtGuestLanguage.PYTHON), fakeAgent.scaffoldedLanguages)
        } finally {
            scene.close()
        }
    }

    @Test
    fun paparazziAccessibilityAuditAndZeroDeadClickablesOnLiteRtCorner() {
        val fakeAgent = FakeLiteRtAgentService()
        val backend = object : StubBackend() {
            override val agent: AgentService = fakeAgent
        }
        var backCalls = 0
        val ctx = object : ScreenContext {
            override val backend = backend
            override fun back() {
                backCalls++
            }
            override fun openScreen(id: String) {}
        }

        val reportDir = File("build/reports/paparazzi/litert-corner")
        PaparazziSession(
            deviceConfig = DeviceConfig.PIXEL_9_PRO.withHeight(4800),
            reportDir = reportDir,
        ) {
            CodeAssistTheme(dark = true) {
                LiteRtLmCornerScreen(ctx)
            }
        }.use { session ->
            val a11yPng = session.snapshot("litert_corner_accessibility", includeAccessibilityOverlay = true)
            assertTrue(a11yPng.exists() && a11yPng.length() > 5_000)

            val cmp = session.verifySnapshot("litert_corner")
            assertTrue(cmp.passed)

            val verified = session.assertNoDeadClickables(
                backendStateSnapshot = {
                    listOf(
                        fakeAgent.selectedDelegate,
                        fakeAgent.activeLiteRtModelId,
                        fakeAgent.modelStatuses.toMap(),
                        fakeAgent.serverEnabled,
                        fakeAgent.guestLanguage,
                        fakeAgent.smokeResponse,
                        fakeAgent.statusBanner,
                        fakeAgent.scaffoldedLanguages.toList(),
                        backCalls,
                    )
                },
            )
            assertTrue(verified.size >= 10, "Expected at least 10 verified interactive controls, found ${verified.size}")
        }
    }
}
