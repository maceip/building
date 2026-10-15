package dev.ide.android.workbench.runtime

import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.*
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus as Status
import dev.ide.workbench.runtime.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Run each phase in a fresh instrumentation process; the local-testing APKs contain real tools. */
class RuntimeDeliveryDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test fun baseWorksWithoutNode() = runBlocking {
        assertNotNull("Build with IDE_RUNTIME_DELIVERY=play", RuntimePacks.catalog(app))
        assertFalse(RuntimePacks.nodeInstalled(app))
        val host = AndroidRuntimeHost(app)
        assertTrue(host.disabledReason(RuntimeKind.NODE).orEmpty().contains("Download"))
        val pack = host.prepare()
        assertNull(pack.node)
        assertNull(pack.npmCli)
        assertNotNull(pack.python)
        assertNotNull(pack.shell)
        assertEquals("base-python-ok", run(host, listOf(pack.python.toString(), "-c", "print('base-python-ok')"), pack).trim())
    }

    @Test fun downloadThenExecuteNodeAndChildren() = runBlocking {
        val host = AndroidRuntimeHost(app)
        // Hold a prepared base-only host across installation to catch stale cached prefixes.
        val before = host.prepare()
        assertNull(before.node)
        val manager = SplitInstallManagerFactory.create(app)
        val complete = CompletableDeferred<Unit>()
        val listener = SplitInstallStateUpdatedListener { state ->
            if (RuntimePacks.NODE_MODULE in state.moduleNames()) when (state.status()) {
                Status.INSTALLED -> complete.complete(Unit)
                Status.FAILED, Status.CANCELED, Status.REQUIRES_USER_CONFIRMATION ->
                    complete.completeExceptionally(IllegalStateException("Delivery status ${state.status()}, error ${state.errorCode()}"))
            }
        }
        manager.registerListener(listener)
        try {
            manager.startInstall(SplitInstallRequest.newBuilder().addModule(RuntimePacks.NODE_MODULE).build())
                .addOnFailureListener { complete.completeExceptionally(it) }
            withTimeout(120_000) { complete.await() }
            SplitCompat.install(app)
            assertTrue(RuntimePacks.nodeInstalled(app))
            val after = host.prepare()
            assertNotEquals(before.packId, after.packId)
            assertNotNull(after.node)
            assertNull(host.disabledReason(RuntimeKind.NODE))
            val deliveredBinary = java.nio.file.Paths.get(after.environment.getValue("BUILDING_RUNTIME_NODE_BINARY"))
            assertFalse("SplitCompat payload stays read-only and non-executable", Files.isExecutable(deliveredBinary))
            assertTrue("Child entrypoint belongs to the installed APK", after.node.toString().startsWith(app.applicationInfo.nativeLibraryDir))
            exercise(host, after)
        } finally { manager.unregisterListener(listener) }
    }

    @Test fun failedDownloadLeavesBaseUsableAndCanBeRetried() = runBlocking {
        assertFalse(RuntimePacks.nodeInstalled(app))
        val manager = com.google.android.play.core.splitinstall.testing.FakeSplitInstallManagerFactory.create(app)
        val failure = CompletableDeferred<Int>()
        val listener = SplitInstallStateUpdatedListener { state ->
            if (RuntimePacks.NODE_MODULE in state.moduleNames() && state.status() == Status.FAILED)
                failure.complete(state.errorCode())
        }
        manager.registerListener(listener)
        manager.setShouldNetworkError(true)
        try {
            manager.startInstall(SplitInstallRequest.newBuilder().addModule(RuntimePacks.NODE_MODULE).build())
                .addOnFailureListener { failure.completeExceptionally(it) }
            assertEquals(com.google.android.play.core.splitinstall.model.SplitInstallErrorCode.NETWORK_ERROR, withTimeout(30_000) { failure.await() })
            assertFalse(RuntimePacks.nodeInstalled(app))
            val host = AndroidRuntimeHost(app)
            val pack = host.prepare()
            assertNull(pack.node)
            assertEquals("base-ok", run(host, listOf(pack.shell.toString(), "-c", "printf base-ok"), pack))
        } finally { manager.setShouldNetworkError(false); manager.unregisterListener(listener) }
    }

    @Test fun installedPackWorksAfterColdStart() = runBlocking {
        assertTrue(RuntimePacks.nodeInstalled(app))
        val host = AndroidRuntimeHost(app)
        exercise(host, host.prepare())
    }

    @Test fun cancelDownloadKeepsNodeAbsent() = runBlocking {
        assertFalse(RuntimePacks.nodeInstalled(app))
        val manager = SplitInstallManagerFactory.create(app)
        val canceled = CompletableDeferred<Unit>()
        val listener = SplitInstallStateUpdatedListener { state ->
            if (RuntimePacks.NODE_MODULE in state.moduleNames()) when (state.status()) {
                Status.CANCELED -> canceled.complete(Unit)
                Status.FAILED, Status.INSTALLED -> canceled.completeExceptionally(IllegalStateException("Unexpected cancel result: ${state.status()}"))
            }
        }
        manager.registerListener(listener)
        try {
            manager.startInstall(SplitInstallRequest.newBuilder().addModule(RuntimePacks.NODE_MODULE).build())
                .addOnSuccessListener { id -> manager.cancelInstall(id).addOnFailureListener { canceled.completeExceptionally(it) } }
                .addOnFailureListener { canceled.completeExceptionally(it) }
            withTimeout(30_000) { canceled.await() }
            assertFalse(RuntimePacks.nodeInstalled(app))
            assertNull(AndroidRuntimeHost(app).prepare().node)
        } finally { manager.unregisterListener(listener) }
    }

    private suspend fun exercise(host: AndroidRuntimeHost, pack: RuntimeInstallation) {
        val node = requireNotNull(pack.node).toString()
        val script = """
            const assert = require('node:assert/strict');
            const child = require('node:child_process').spawnSync(process.execPath, ['-e', "process.stdout.write('child-ok')"], {encoding:'utf8'});
            assert.equal(child.status, 0, String(child.error || child.stderr));
            assert.equal(child.stdout, 'child-ok');
            assert.equal(require('node:crypto').createHash('sha256').update('abc').digest('hex'), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
            console.log('delivered-node-child-crypto-ok');
        """.trimIndent()
        assertEquals("delivered-node-child-crypto-ok", run(host, listOf(node, "-e", script), pack).trim())
        assertTrue(run(host, listOf(node, requireNotNull(pack.npmCli).toString(), "--version"), pack).trim().matches(Regex("\\d+\\.\\d+\\.\\d+")))
        assertTrue(run(host, listOf(node, pack.prefix.resolve("lib/node_modules/pnpm/bin/pnpm.mjs").toString(), "--version"), pack).trim().isNotBlank())
    }

    private suspend fun run(host: AndroidRuntimeHost, command: List<String>, pack: RuntimeInstallation): String = withTimeout(30_000) {
        val root = Files.createTempDirectory(app.cacheDir.toPath(), "pack-check-")
        val child = host.start(command, root, pack.environment)
        try {
            coroutineScope {
                val out = async(Dispatchers.IO) { child.stdout.bufferedReader().use { it.readText() } }
                val err = async(Dispatchers.IO) { child.stderr.bufferedReader().use { it.readText() } }
                val exit = child.awaitExit()
                assertEquals("$command\n${err.await()}", 0, exit)
                out.await()
            }
        } finally { child.terminate(); root.toFile().deleteRecursively() }
    }
}
