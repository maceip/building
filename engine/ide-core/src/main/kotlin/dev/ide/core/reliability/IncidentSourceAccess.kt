package dev.ide.core.reliability

import dev.ide.agent.IncidentApplyResult
import dev.ide.agent.impl.IncidentDigests
import dev.ide.platform.impl.ModelReadWriteLock
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal class IncidentSourceAccess(private val lock: ModelReadWriteLock, projectRoot: Path? = null) {
    private val shared = projectRoot?.toRealPath()?.toString()?.let { ROOTS.computeIfAbsent(it) { RootState() } } ?: RootState()
    private var closed = false

    // Retiring an engine does not imply its asynchronous writers have stopped using the same root.
    fun <T> write(block: () -> T): T = lock.write { synchronized(shared, block) }

    fun retainDocument(): () -> Unit = retain(document = true)
    fun retainOperation(): () -> Unit = retain(document = false)

    private fun retain(document: Boolean): () -> Unit = write {
        check(!closed) { "The project is closed." }
        if (document) shared.documents++ else shared.operations++
        var released = false
        return@write {
            write {
                if (!released) {
                    released = true
                    if (document) shared.documents-- else shared.operations--
                }
            }
        }
    }

    fun close() = write { closed = true }
    fun isClosed(): Boolean = write { closed }
    fun markExternalWorkUnconfirmed() = write { shared.externalWorkUnconfirmed = true }

    fun checkBuffers(
        root: Path,
        originalInputHashes: Map<String, String>,
        changedFiles: Set<String>,
        overlays: Map<Path, String>,
    ): IncidentApplyResult? = write {
        if (closed) return@write IncidentApplyResult.StaleSource
        // UI text reaches analysis overlays after a debounce; an overlay alone cannot attest to a live tab.
        if (shared.documents != 0) return@write IncidentApplyResult.Pending("Close the project's editor tabs before applying the verified patch.")
        if (shared.operations != 0) return@write IncidentApplyResult.Pending("Wait for the current workspace operation before applying the verified patch.")
        if (shared.externalWorkUnconfirmed) return@write IncidentApplyResult.Pending("A separate build process has no confirmed workspace shutdown; the verified copy is retained.")
        for ((path, text) in overlays) {
            val real = runCatching { path.toRealPath() }.getOrNull() ?: return@write IncidentApplyResult.StaleSource
            if (!real.startsWith(root)) continue
            val name = root.relativize(real).toString().replace('\\', '/')
            val expected = originalInputHashes[name] ?: return@write IncidentApplyResult.Pending("An editor buffer is outside the verified input snapshot.")
            if (IncidentDigests.bytes(text.toByteArray(Charsets.UTF_8)) != expected) return@write IncidentApplyResult.StaleSource
            if (name in changedFiles) return@write IncidentApplyResult.Pending("Close the changed file before applying the verified patch.")
        }
        null
    }

    private class RootState {
        var documents = 0
        var operations = 0
        var externalWorkUnconfirmed = false
    }
    private companion object {
        val ROOTS = ConcurrentHashMap<String, RootState>()
    }
}
