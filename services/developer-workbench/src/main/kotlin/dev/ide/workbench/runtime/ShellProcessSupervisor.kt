package dev.ide.workbench.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-group (`setsid` + PGID ownership token) lifecycle supervisor and deterministic process-tree reaper
 * (ported from `Mangi-11/Eta`'s `ShellProcessSupervisor`).
 *
 * Ensures that background or cancelled shell/agent commands never leak orphan child processes (`node`,
 * `python3`, `git`, `proot`, language servers) when a session stops or times out.
 */
class ShellProcessSupervisor(
    private val pidDirectory: Path,
    private val groupSignalSender: (pgid: Int, signal: Int) -> Boolean = ::defaultGroupSignalSender,
) {
    data class ProcessGroupToken(
        val sessionId: String,
        val pidFile: Path,
        val startedAtEpochMs: Long = Instant.now().toEpochMilli(),
        @Volatile var pgid: Int? = null,
    )

    inner class SupervisedProcessHandle(
        val token: ProcessGroupToken,
        val process: Process,
    ) {
        private val closed = AtomicBoolean(false)

        fun resolveProcessGroupId(): Int? {
            token.pgid?.let { return it }
            if (Files.isRegularFile(token.pidFile)) {
                val parsed = runCatching {
                    RuntimeFiles.readUtf8(token.pidFile).trim().toInt()
                }.getOrNull()
                if (parsed != null && parsed > 1) {
                    token.pgid = parsed
                    return parsed
                }
            }
            val directPid = runCatching { process.pid().toInt() }.getOrNull()
            return directPid?.takeIf { it > 1 }
        }

        suspend fun terminate(gracePeriodMs: Long = 300L) = withContext(Dispatchers.IO) {
            if (!closed.compareAndSet(false, true)) return@withContext
            try {
                var pgid = resolveProcessGroupId()
                repeat(15) {
                    if (pgid == null && process.isAlive) {
                        delay(10)
                        pgid = resolveProcessGroupId()
                    }
                }

                // Phase 1: polite SIGTERM to process group and descendants
                if (pgid != null && pgid > 1) {
                    runCatching { groupSignalSender(pgid, SIGTERM) }
                }
                destroyProcessTree(process, forcibly = false)

                val exitedCleanly = runCatching {
                    process.waitFor(gracePeriodMs, TimeUnit.MILLISECONDS)
                }.getOrDefault(false)

                // Phase 2: SIGKILL to entire process group if anything survived
                if (!exitedCleanly || process.isAlive) {
                    if (pgid != null && pgid > 1) {
                        runCatching { groupSignalSender(pgid, SIGKILL) }
                    }
                    destroyProcessTree(process, forcibly = true)
                } else if (pgid != null && pgid > 1) {
                    // Even if the group leader exited, backgrounded grandchildren may still hold sockets
                    runCatching { groupSignalSender(pgid, SIGKILL) }
                }
            } finally {
                activeHandles.remove(token.sessionId)
                runCatching { Files.deleteIfExists(token.pidFile) }
            }
        }

        fun markExited() {
            if (closed.compareAndSet(false, true)) {
                activeHandles.remove(token.sessionId)
                runCatching { Files.deleteIfExists(token.pidFile) }
            }
        }
    }

    private val activeHandles = ConcurrentHashMap<String, SupervisedProcessHandle>()

    fun allocateToken(sessionId: String): ProcessGroupToken {
        Files.createDirectories(pidDirectory)
        val safeName = sessionId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val pidFile = pidDirectory.resolve("$safeName.pid")
        return ProcessGroupToken(sessionId = sessionId, pidFile = pidFile)
    }

    fun register(token: ProcessGroupToken, process: Process): SupervisedProcessHandle {
        val handle = SupervisedProcessHandle(token, process)
        activeHandles[token.sessionId] = handle
        return handle
    }

    fun activeSessions(): Set<String> = activeHandles.keys.toSet()

    suspend fun terminateSession(sessionId: String, gracePeriodMs: Long = 300L): Boolean {
        val handle = activeHandles.remove(sessionId) ?: return false
        handle.terminate(gracePeriodMs)
        return true
    }

    suspend fun terminateAll(gracePeriodMs: Long = 300L): Int {
        val handles = activeHandles.values.toList()
        for (handle in handles) {
            handle.terminate(gracePeriodMs)
        }
        val orphans = reapStalePidFiles()
        return handles.size + orphans
    }

    /**
     * Scans [pidDirectory] for `.pid` files that do not belong to any active tracked session
     * (e.g. from a previous crash or killed worker), signals their process groups, and removes the files.
     */
    fun reapStalePidFiles(): Int {
        if (!Files.isDirectory(pidDirectory)) return 0
        val activeFiles = activeHandles.values.map { it.token.pidFile.toAbsolutePath().normalize() }.toSet()
        var reaped = 0
        val files = runCatching {
            Files.list(pidDirectory).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".pid") }.toList()
            }
        }.getOrDefault(emptyList())

        for (file in files) {
            val normalized = file.toAbsolutePath().normalize()
            if (normalized in activeFiles) continue
            val pgid = runCatching { RuntimeFiles.readUtf8(file).trim().toInt() }.getOrNull()
            if (pgid != null && pgid > 1) {
                runCatching { groupSignalSender(pgid, SIGTERM) }
                runCatching { groupSignalSender(pgid, SIGKILL) }
                reaped++
            }
            runCatching { Files.deleteIfExists(file) }
        }
        return reaped
    }

    companion object {
        const val SIGTERM: Int = 15
        const val SIGKILL: Int = 9

        fun destroyProcessTree(process: Process, forcibly: Boolean) {
            runCatching {
                val handle = process.toHandle()
                handle.descendants().forEach { descendant ->
                    runCatching {
                        if (forcibly) descendant.destroyForcibly() else descendant.destroy()
                    }
                }
                if (forcibly) process.destroyForcibly() else process.destroy()
            }
        }

        fun defaultGroupSignalSender(pgid: Int, signal: Int): Boolean {
            if (pgid <= 1) return false
            // Try Android's android.system.Os.kill(-pgid, signal) via reflection when running on Android,
            // falling back to `/bin/kill -<signal> -- -<pgid>` on JVM/desktop.
            val androidKilled = runCatching {
                val osClass = Class.forName("android.system.Os")
                val killMethod = osClass.getMethod("kill", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                killMethod.invoke(null, -pgid, signal)
                true
            }.getOrDefault(false)
            if (androidKilled) return true

            return runCatching {
                val proc = ProcessBuilder("kill", "-$signal", "--", "-$pgid")
                    .redirectErrorStream(true)
                    .start()
                proc.waitFor(200, TimeUnit.MILLISECONDS) && proc.exitValue() == 0
            }.getOrDefault(false)
        }
    }
}
