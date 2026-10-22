package dev.ide.android.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device coverage for [ZCodeAgentService]: the vendored ZCode bundle extracts from
 * assets, provider configuration written from service settings lands in ZCode's
 * registry file, and an unconfigured turn surfaces a real error instead of hanging.
 */
@RunWith(AndroidJUnit4::class)
class ZCodeAgentServiceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun settingsPersistIntoZCodeProviderConfig() {
        val service = ZCodeAgentService(context, workspaceRoot = { null })
        val before = service.config()
        try {
            service.setProviderKey("openai", "sk-test-key-for-config")
            service.setModel("gpt-test-model")

            val config = service.config()
            assertEquals("openai", config.selectedProvider)
            assertEquals("gpt-test-model", config.model)
            assertTrue(config.configured)

            val registry = File(context.filesDir, "developer-runtime/home/.zcode/v2/provider_config.json")
            assertTrue("provider registry written", registry.isFile)
            val text = registry.readText()
            assertTrue(text.contains("gpt-test-model"))
            assertTrue(text.contains("sk-test-key-for-config"))
        } finally {
            // Restore the developer-seeded configuration so other tests (and the
            // developer's own app state) are unaffected by the fake key above.
            service.setProviderKey(before.selectedProvider.ifBlank { "openai" }, before.providers.firstOrNull()?.apiKey ?: "")
            service.setModel(before.model)
        }
    }

    @Test
    fun agentClonesRepositoryIntoWorkspace() = runBlocking {
        // Requires a configured provider registry on the device (seeded by the
        // developer); skipped honestly when the agent is not configured.
        val service = ZCodeAgentService(context, workspaceRoot = { null })
        org.junit.Assume.assumeTrue("agent not configured", service.config().configured)
        service.setModel(service.config().model)
        val workDir = File(context.filesDir, "zcode-clone-test").apply { deleteRecursively(); mkdirs() }
        val cloning = ZCodeAgentService(context, workspaceRoot = { workDir.toPath() })
        cloning.send(
            "Clone the git repository https://github.com/octocat/Hello-World into a new folder " +
                "named demo-clone inside the current directory. Then list the cloned files.",
        )
        val state = withTimeout(300_000) {
            cloning.chatState.first { !it.busy && it.messages.size >= 2 }
        }
        val reply = state.messages.last()
        assertTrue("clone turn failed: ${reply.text}", !reply.isError)
        val clonedReadme = workDir.walkTopDown().firstOrNull { it.isFile && it.name == "README" }
        assertTrue(
            "no cloned README under $workDir; reply was: ${reply.text.take(400)}",
            clonedReadme != null,
        )
    }

    @Test
    fun failingTurnSurfacesError() = runBlocking {
        // A model the provider cannot serve must surface as an error reply, not a hang
        // or a fabricated answer. Independent of any registry already on the device:
        // the service rewrites the registry from these settings before the turn.
        val service = ZCodeAgentService(context, workspaceRoot = { null })
        val before = service.config()
        try {
            service.setProviderKey("openai", "sk-test-key-for-config")
            service.setModel("nonexistent-model-for-error-test")
            service.send("Say hi")
            val state = withTimeout(120_000) {
                service.chatState.first { !it.busy && it.messages.size >= 2 }
            }
            val reply = state.messages.last()
            assertTrue("expected an error reply, got: ${reply.text}", reply.isError)
            assertTrue(reply.text.isNotBlank())
        } finally {
            service.setProviderKey(before.selectedProvider.ifBlank { "openai" }, before.providers.firstOrNull()?.apiKey ?: "")
            service.setModel(before.model)
        }
    }
}
