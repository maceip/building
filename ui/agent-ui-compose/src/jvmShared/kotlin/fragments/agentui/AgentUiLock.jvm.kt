package fragments.agentui

internal actual class AgentUiLock {
    actual inline fun <T> withLock(block: () -> T): T = kotlin.synchronized(this, block)
}
