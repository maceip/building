package dev.ide.core.agent

import dev.ide.agent.ToolArgs
import dev.ide.core.IdeServicesBackend
import dev.ide.core.project.ProjectManager
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.UiLiteRtDelegate
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
 * - Verifies downloading/activating/deleting `.litertlm` models through `AgentService` and `litert_corner` tool.
 * - Verifies `useLiteRtInIdeAgent` switches the active IDE coding agent provider to `litert` and marks it configured.
 * - Verifies `scaffoldLiteRtGuestClient` writes real guest SDK files into the workspace for Kotlin, Swift, Node.js, and Python.
 * - Verifies [LiteRtIpcBridge] lists installed models, resolves verified `.litertlm` files for mmap, and executes local inference.
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
                // 1. Initially, 'litert' is listed in providers, and when selected before any model is installed,
                // configured must be FALSE (zero fabrication).
                val initialCfg = agent.config()
                val litertProvider = initialCfg.providers.firstOrNull { it.id == "litert" }
                assertNotNull(litertProvider, "Expected 'litert' in AgentBackend providers")
                agent.selectProvider("litert")
                assertFalse(
                    agent.config().configured,
                    "LiteRT-LM provider must NOT report configured=true before a verified .litertlm model is installed on disk",
                )

                // 2. Provision gemma-3-1b-it-int4 via the litert_corner tool
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
                        "model_id" to "gemma-3-1b-it-int4",
                    ),
                )
                assertFalse(downloadRes.isError, "download_model failed: ${downloadRes.content}")

                // 3. Verify AgentService.liteRtCorner() reflects the installed & active model, and config().configured is now TRUE!
                val corner = agent.liteRtCorner()
                assertEquals("gemma-3-1b-it-int4", corner.activeModelId)
                val gemmaEntry = corner.models.first { it.id == "gemma-3-1b-it-int4" }
                assertEquals(UiLiteRtModelStatus.ACTIVE, gemmaEntry.status)
                assertTrue(gemmaEntry.sizeOnDiskBytes > 1024L)
                assertTrue(gemmaEntry.sha256.length == 64)
                assertTrue(agent.config().configured, "LiteRT-LM provider must report configured=true once a verified .litertlm model is active")

                // 4. Select delegate and run smoke test via AgentService
                val targetDelegate = corner.hardware.availableDelegates.last()
                agent.selectLiteRtDelegate(targetDelegate)
                assertEquals(targetDelegate, agent.liteRtCorner().hardware.selectedDelegate)

                agent.runLiteRtSmokeTest("Check local runtime health")
                val afterSmoke = agent.liteRtCorner()
                assertTrue(
                    afterSmoke.lastSmokeTestResponse.contains("LiteRT-LM On-Device"),
                    "Unexpected smoke test response: ${afterSmoke.lastSmokeTestResponse}",
                )

                // 5. Scaffold Guest SDK clients for all 4 languages and verify real files on disk
                for (lang in UiLiteRtGuestLanguage.entries) {
                    agent.selectLiteRtGuestLanguage(lang)
                    assertEquals(lang, agent.liteRtCorner().selectedGuestLanguage)
                    val writtenPath = Paths.get(agent.scaffoldLiteRtGuestClient(lang))
                    assertTrue(Files.isRegularFile(writtenPath), "Expected scaffolded client file at $writtenPath")
                    val content = Files.readString(writtenPath)
                    assertTrue(content.contains("gemma-3-1b-it-int4"), "Expected active model ID in $writtenPath")
                }

                // 6. Verify LiteRtIpcBridge (backing LiteRtLmContentProvider on Android)
                val storageRoot = manager.storageRoot
                val ipcModels = LiteRtIpcBridge.listInstalledModels(storageRoot)
                assertEquals(1, ipcModels.size)
                assertEquals("gemma-3-1b-it-int4", ipcModels.first().id)
                assertTrue(ipcModels.first().active)

                val mmapFile = LiteRtIpcBridge.resolveVerifiedModelFile(storageRoot, "gemma-3-1b-it-int4")
                assertNotNull(mmapFile)
                assertTrue(mmapFile.isFile && mmapFile.length() > 1024L)

                val ipcGen = LiteRtIpcBridge.generate(
                    storageRoot = storageRoot,
                    prompt = "Generate a Kotlin helper function",
                    modelId = "gemma-3-1b-it-int4",
                )
                assertTrue(ipcGen.ok)
                assertTrue(ipcGen.text.contains("Gemma 3 1B Instruct"))

                // 7. Delete the model and verify configured returns to FALSE
                agent.deleteLiteRtModel("gemma-3-1b-it-int4")
                assertEquals("", agent.liteRtCorner().activeModelId)
                assertFalse(agent.config().configured)
            } finally {
                backend.close()
            }
        }
    }
}
