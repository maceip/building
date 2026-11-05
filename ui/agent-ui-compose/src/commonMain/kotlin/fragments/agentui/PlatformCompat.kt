package fragments.agentui

class File(val path: String, private val sizeBytes: Long = 0L) {
    val name: String get() = path.substringAfterLast('/').substringAfterLast('\\')
    val extension: String get() = name.substringAfterLast('.', "")
    fun length(): Long = sizeBytes
}

internal expect class AgentUiLock() {
    fun <T> withLock(block: () -> T): T
}

internal fun <R> synchronized(lock: AgentUiLock, block: () -> R): R = lock.withLock(block)
