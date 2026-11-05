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
) {
    /** True when this palette represents a dark surface hierarchy. */
    val isDark: Boolean
        get() = (0.2126f * background.red + 0.7152f * background.green + 0.0722f * background.blue) < 0.35f

    /** Theme-aware terminal/code block surface replacing legacy hardcoded hex colors. */
    val codeBackground: Color
        get() = if (isDark) Color(0xFF141517) else Color(0xFFF3F4F6)

    /** Theme-aware terminal/code foreground text with WCAG AA contrast. */
    val codeText: Color
        get() = if (isDark) Color(0xFFE6E8EC) else Color(0xFF1F2124)
}
