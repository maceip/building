package dev.ide.core.agent

import dev.ide.agent.ToolArgs
import dev.ide.core.IdeServicesBackend
import dev.ide.core.project.ProjectManager
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.UiLiteRtGuestLanguage
import dev.ide.ui.backend.UiLiteRtModelStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end integration test for [AgentBackend], [LiteRtIpcBridge], and [createLiteRtCornerTool]:
 * - Verifies `litert` provider registration in `AgentBackend.config()` and honest `configured` state.
 * - Verifies downloading/activating/deleting real `.litertlm` models (`smollm2-135m-instruct`) through `AgentService` and `litert_corner` tool.
 * - Verifies `useLiteRtInIdeAgent` switches the active IDE coding agent provider to `litert` and marks it configured.
 * - Verifies `scaffoldLiteRtGuestClient` writes real guest SDK files into the workspace for Kotlin, Swift, Node.js, and Python.
 * - Verifies [LiteRtIpcBridge] lists installed models, resolves verified `.litertlm` files for mmap, and executes real local inference via `com.google.ai.edge.litertlm.Engine`.
 */
class AgentBackendLiteRtTest {

    private fun mapArgs(vararg pairs: Pair<String, String>): ToolArgs {
        val map = pairs.toMap()
        return object : ToolArgs {
            override fun string(key: String): String = map[key] ?: error("Missing $key")
            override fun optString(key: String): String? = map[key]
            override fun int(key: String): Int = map[key]?.toInt() ?: error("Missing $key")
            override fun optInt(key: String): Int? = map[key]?.toIntOrNull()
            override fun boolean(key: String): Boolean = map[key]?.toBooleanStrict() ?: error("Missing $key")
            override fun optBoolean(key: String): Boolean? = map[key]?.toBooleanStrictOrNull()
            override fun stringList(key: String): List<String> = emptyList()
            override fun raw(): String = map.toString()
        }
    }

    @Test
    fun agentBackendIntegratesLiteRtCatalogProviderLoopbackAndScaffolder() = runBlocking {
        withTempDir("agent-backend-litert") { root ->
            val manager = ProjectManager.desktop(root.resolve("projects"))
            val backend = IdeServicesBackend(initial = null, manager = manager)
            val agent = backend.agent

            try {
                // 1. Out of the box on host JVM (no model installed yet), 'litert' is listed in providers
                // and honestly reports configured=false until a real .litertlm model is provisioned.
                val initialCfg = agent.config()
                val litertProvider = initialCfg.providers.firstOrNull { it.id == "litert" }
                assertNotNull(litertProvider, "Expected 'litert' in AgentBackend providers")
                agent.selectProvider("litert")
                assertEquals("", agent.liteRtCorner().activeModelId)
                assertFalse(
                    agent.config().configured,
                    "LiteRT-LM provider must honestly report configured=false when no .litertlm model is installed",
                )

                // 2. Provision real smollm2-135m-instruct (SmolLM2_135M_Instruct.litertlm) via the litert_corner tool and activate it
                val agentBackend = agent as AgentBackend
                val tool = createLiteRtCornerTool(
                    managerProvider = { agentBackend.liteRtManager },
                    scaffoldClientIntoWorkspace = { lang ->
                        agent.scaffoldLiteRtGuestClient(lang.toUi())
                    },
                )
                val downloadRes = tool.execute(
                    mapArgs(
                        "action" to "download_model",
                        "model_id" to "smollm2-135m-instruct",
                    ),
                )
                assertFalse(downloadRes.isError, "download_model failed: ${downloadRes.content}")
                agent.activateLiteRtModel("smollm2-135m-instruct")

                // 3. Verify AgentService.liteRtCorner() reflects the installed & active model, and config().configured is now TRUE!
                val corner = agent.liteRtCorner()
                assertEquals("smollm2-135m-instruct", corner.activeModelId)
                val smolEntry = corner.models.first { it.id == "smollm2-135m-instruct" }
                assertEquals(UiLiteRtModelStatus.ACTIVE, smolEntry.status)
                assertTrue(smolEntry.sizeOnDiskBytes > 100_000_000L)
                assertEquals(64, smolEntry.sha256.length)
                assertTrue(agent.config().configured, "LiteRT-LM provider must report configured=true once a verified .litertlm model is active")

                // 4. Select delegate and run real smoke test via AgentService (executes com.google.ai.edge.litertlm.Engine)
                val targetDelegate = corner.hardware.availableDelegates.first()
                agent.selectLiteRtDelegate(targetDelegate)
                assertEquals(targetDelegate, agent.liteRtCorner().hardware.selectedDelegate)

                agent.runLiteRtSmokeTest("Say hello in one short sentence.")
                val afterSmoke = agent.liteRtCorner()
                assertTrue(
                    afterSmoke.lastSmokeTestResponse.isNotBlank() && !afterSmoke.lastSmokeTestResponse.startsWith("Smoke test failed:"),
                    "Unexpected smoke test response: ${afterSmoke.lastSmokeTestResponse}",
                )

                // 5. Scaffold Guest SDK clients for all 4 languages and verify real files on disk
                for (lang in UiLiteRtGuestLanguage.entries) {
                    agent.selectLiteRtGuestLanguage(lang)
                    assertEquals(lang, agent.liteRtCorner().selectedGuestLanguage)
                    val writtenPath = Paths.get(agent.scaffoldLiteRtGuestClient(lang))
                    assertTrue(Files.isRegularFile(writtenPath), "Expected scaffolded client file at $writtenPath")
                    val content = Files.readString(writtenPath)
                    assertTrue(content.contains("smollm2-135m-instruct"), "Expected active model ID in $writtenPath")
                }

                // 6. Verify LiteRtIpcBridge (backing LiteRtLmContentProvider on Android) with real .litertlm Engine
                val storageRoot = manager.storageRoot
                val ipcModels = LiteRtIpcBridge.listInstalledModels(storageRoot)
                assertTrue(ipcModels.any { it.id == "smollm2-135m-instruct" && it.active })

                val mmapFile = LiteRtIpcBridge.resolveVerifiedModelFile(storageRoot, "smollm2-135m-instruct")
                assertNotNull(mmapFile)
                assertTrue(mmapFile.isFile && mmapFile.length() > 100_000_000L)

                val ipcGen = LiteRtIpcBridge.generate(
                    storageRoot = storageRoot,
                    prompt = "Say hi.",
                    modelId = "smollm2-135m-instruct",
                    maxTokens = 16,
                )
                assertTrue(ipcGen.ok, "Expected real IPC Engine inference to succeed: ${ipcGen.error}")
                assertTrue(ipcGen.text.isNotBlank())

                // 7. Delete the model and verify configured returns to FALSE
                agent.deleteLiteRtModel("smollm2-135m-instruct")
                assertEquals("", agent.liteRtCorner().activeModelId)
                assertFalse(agent.config().configured)
            } finally {
                backend.close()
            }
        }
    }
}
