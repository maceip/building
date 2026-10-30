package fragments.agentui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import fragments.chrome.AgentUiBaseTokens

/** Public kit palette shared by the panel, composer, leaves, and semantic icons. */
@Immutable
data class AgentUiPalette(
    val background: Color,
    val surface: Color,
    val subtle: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val accent: Color,
    val accentText: Color,
    val danger: Color,
    val success: Color,
    val radius: Dp,
    val toolRadius: Dp,
    val composerSurface: Color = surface,
    val userBubble: Color = accent,
    val userBubbleText: Color = accentText,
    val warning: Color = AgentUiBaseTokens.Dark.statusOrange,
)
