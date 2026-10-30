package fragments.agentui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ide.ui.concurrent.UiLock
import dev.ide.ui.theme.AgentUi

class File(val path: String, private val sizeBytes: Long = 0L) {
    val name: String get() = path.substringAfterLast('/').substringAfterLast('\\')
    val extension: String get() = name.substringAfterLast('.', "")
    fun length(): Long = sizeBytes
}

internal fun <R> synchronized(lock: UiLock, block: () -> R): R = lock.withLock(block)

@Composable
fun rememberIdeAgentUiPalette(): AgentUiPalette {
    val chrome = AgentUi.chrome
    return remember(chrome) {
        AgentUiPalette(
            background = chrome.bg,
            surface = chrome.surface,
            subtle = chrome.surfaceAlt,
            text = chrome.text,
            muted = chrome.textMuted,
            border = chrome.hairlineStrong,
            accent = chrome.accent,
            accentText = chrome.accentInk,
            danger = chrome.danger,
            success = chrome.accent,
            warning = chrome.warn,
            radius = AgentUi.radius.card,
            toolRadius = AgentUi.radius.tile,
            composerSurface = chrome.surface,
            userBubble = chrome.surfaceAlt,
            userBubbleText = chrome.text,
        )
    }
}

