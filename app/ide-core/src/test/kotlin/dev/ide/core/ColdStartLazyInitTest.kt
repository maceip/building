package dev.ide.core

import dev.ide.agent.impl.LiteRtAiCoreBridge
import dev.ide.agent.impl.LiteRtAiCoreStatus
import dev.ide.agent.impl.LiteRtLmServingRegistry
import dev.ide.core.project.ProjectManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class ColdStartLazyInitTest {

    @Test
    fun `IdeServicesBackend startup does not probe AI Core or instantiate LiteRT manager eagerly`(
        @TempDir tempDir: Path,
    ) {
        val probeCalls = AtomicInteger(0)
        val previousBridge = LiteRtLmServingRegistry.aiCoreBridge
        try {
            LiteRtLmServingRegistry.registerAiCoreBridge(
                object : LiteRtAiCoreBridge {
                    override fun probeStatus(): LiteRtAiCoreStatus {
                        probeCalls.incrementAndGet()
                        return LiteRtAiCoreStatus(
                            available = true,
                            packageName = "com.google.android.aicore",
                            versionName = "1.0.test (AVAILABLE)",
                            systemServicePresent = true,
                        )
                    }

                    override fun generate(prompt: String, system: String?, maxTokens: Int): Result<String> =
                        Result.success("ok")
                },
            )

            var sdkProvisionCalls = 0
            val manager = ProjectManager.onDevice(
                projectsRoot = tempDir.resolve("projects"),
                bootClasspath = listOf(tempDir.resolve("android.jar").toString()),
                androidToolsDir = tempDir,
                debugKeystore = tempDir.resolve("debug.keystore"),
                storageRoot = tempDir,
                onBeforeSdkUse = { sdkProvisionCalls++ },
            )

            val backend = IdeServicesBackend(initial = null, manager = manager)
            try {
                assertTrue( manager.list().isEmpty() )
                assertEquals(0, sdkProvisionCalls, "SDK provisioning must not block ProjectManager construction or project listing")
                assertEquals(0, probeCalls.get(), "AI Core Binder probe must not run during IdeServicesBackend cold start")

                val corner = backend.agent.liteRtCorner()
                assertTrue(corner.models.isNotEmpty())
                assertTrue(probeCalls.get() in 1..2, "Expected bounded AI Core probes when LiteRT Corner is opened, got ${probeCalls.get()}")
            } finally {
                backend.close()
                manager.dispose()
            }
        } finally {
            LiteRtLmServingRegistry.registerAiCoreBridge(previousBridge)
        }
    }
}
