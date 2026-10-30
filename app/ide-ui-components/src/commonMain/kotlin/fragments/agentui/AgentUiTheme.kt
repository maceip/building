package fragments.agentui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import fragments.chrome.AgentUiBasePalette
import fragments.chrome.AgentUiBaseTokens

/** One palette for every component in the reusable agent UI kit. */
object AgentUiTheme {
    val Light: AgentUiPalette = AgentUiBaseTokens.Light.toComponentPalette()
    val Dark: AgentUiPalette = AgentUiBaseTokens.Dark.toComponentPalette()

    private val LocalPalette = staticCompositionLocalOf { Light }

    val palette: AgentUiPalette
        @Composable get() = LocalPalette.current

    @Composable
    operator fun invoke(
        darkTheme: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        CompositionLocalProvider(LocalPalette provides if (darkTheme) Dark else Light, content = content)
    }
}

private fun AgentUiBasePalette.toComponentPalette() = AgentUiPalette(
    background = backgroundFull,
    surface = cardPrimary,
    subtle = secondaryBg,
    text = textPrimary,
    muted = textSecondary,
    border = separatorBorder,
    accent = accentBlue,
    accentText = if (isDark) backgroundFull else cardPrimary,
    danger = statusRed,
    success = statusGreen,
    warning = statusOrange,
    radius = AgentUiBaseTokens.RadiusComposer,
    toolRadius = AgentUiBaseTokens.RadiusCard,
    composerSurface = if (isDark) Color(0xFF313131) else secondaryBg,
    userBubble = Color(0xFF447E8E),
    userBubbleText = Color.White,
)
