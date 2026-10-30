package fragments.agentui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fragments.chrome.AgentUiBasePalette
import fragments.chrome.AgentUiBaseTokens

/** Controls whether components render their Desktop or Mobile layout & touch-target variants. */
enum class AgentUiFormFactor {
    Auto,
    Desktop,
    Mobile,
}

/** Responsive sizing and layout tokens for Desktop (`>= 640.dp`) vs. Mobile (`< 640.dp`). */
@Immutable
data class AgentUiSizing(
    val isMobile: Boolean,
    val fontDeltaSp: Int,
    val buttonHorizontalPadding: Dp,
    val buttonVerticalPadding: Dp,
    val pillButtonHeight: Dp,
    val iconButtonSize: Dp,
    val composerActionSize: Dp,
    val timelineHorizontalPadding: Dp,
    val timelineVerticalPadding: Dp,
    val composerHorizontalPadding: Dp,
    val composerVerticalPadding: Dp,
    val cardCornerRadius: Dp,
    val cardMaxWidth: Dp,
) {
    companion object {
        val Desktop = AgentUiSizing(
            isMobile = false,
            fontDeltaSp = 0,
            buttonHorizontalPadding = 12.dp,
            buttonVerticalPadding = 7.dp,
            pillButtonHeight = 40.dp,
            iconButtonSize = 32.dp,
            composerActionSize = 32.dp,
            timelineHorizontalPadding = 22.dp,
            timelineVerticalPadding = 20.dp,
            composerHorizontalPadding = 18.dp,
            composerVerticalPadding = 12.dp,
            cardCornerRadius = AgentUiBaseTokens.RadiusWindow,
            cardMaxWidth = 480.dp,
        )

        val Mobile = AgentUiSizing(
            isMobile = true,
            fontDeltaSp = 1,
            buttonHorizontalPadding = 16.dp,
            buttonVerticalPadding = 10.dp,
            pillButtonHeight = 44.dp,
            iconButtonSize = 38.dp,
            composerActionSize = 38.dp,
            timelineHorizontalPadding = 12.dp,
            timelineVerticalPadding = 14.dp,
            composerHorizontalPadding = 12.dp,
            composerVerticalPadding = 10.dp,
            cardCornerRadius = 20.dp,
            cardMaxWidth = 600.dp,
        )

        fun forWidth(width: Dp): AgentUiSizing = if (width < 640.dp) Mobile else Desktop
    }
}

/** One palette and responsive sizing system for every component in the reusable agent UI kit. */
object AgentUiTheme {
    val Light: AgentUiPalette = AgentUiBaseTokens.Light.toComponentPalette()
    val Dark: AgentUiPalette = AgentUiBaseTokens.Dark.toComponentPalette()

    private val LocalPalette = staticCompositionLocalOf { Light }
    private val LocalFormFactor = staticCompositionLocalOf { AgentUiFormFactor.Auto }
    private val LocalSizing = staticCompositionLocalOf { AgentUiSizing.Desktop }

    val palette: AgentUiPalette
        @Composable get() = LocalPalette.current

    val formFactor: AgentUiFormFactor
        @Composable get() = LocalFormFactor.current

    val sizing: AgentUiSizing
        @Composable get() = LocalSizing.current

    @Composable
    operator fun invoke(
        darkTheme: Boolean = false,
        formFactor: AgentUiFormFactor = AgentUiFormFactor.Auto,
        content: @Composable () -> Unit,
    ) {
        val density = LocalDensity.current
        val containerWidthPx = LocalWindowInfo.current.containerSize.width
        val windowWidthDp = if (containerWidthPx > 0) {
            with(density) { containerWidthPx.toDp() }
        } else {
            1024.dp
        }
        val resolvedSizing = when (formFactor) {
            AgentUiFormFactor.Mobile -> AgentUiSizing.Mobile
            AgentUiFormFactor.Desktop -> AgentUiSizing.Desktop
            AgentUiFormFactor.Auto -> AgentUiSizing.forWidth(windowWidthDp)
        }
        CompositionLocalProvider(
            LocalPalette provides if (darkTheme) Dark else Light,
            LocalFormFactor provides formFactor,
            LocalSizing provides resolvedSizing,
            content = content,
        )
    }

    @Composable
    internal fun ProvideSizing(
        sizing: AgentUiSizing,
        formFactor: AgentUiFormFactor = LocalFormFactor.current,
        content: @Composable () -> Unit,
    ) {
        CompositionLocalProvider(
            LocalSizing provides sizing,
            LocalFormFactor provides formFactor,
            content = content,
        )
    }
}

/**
 * Updates [AgentUiTheme.sizing] for the current viewport width when [AgentUiTheme.formFactor] is [AgentUiFormFactor.Auto],
 * or forces mobile/desktop sizing when [forceMobile] is specified.
 */
@Composable
fun ProvideAgentUiViewportSizing(
    viewportWidth: Dp,
    forceMobile: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val currentFormFactor = AgentUiTheme.formFactor
    val nextSizing = when {
        forceMobile == true || currentFormFactor == AgentUiFormFactor.Mobile -> AgentUiSizing.Mobile
        forceMobile == false || currentFormFactor == AgentUiFormFactor.Desktop -> AgentUiSizing.Desktop
        else -> AgentUiSizing.forWidth(viewportWidth)
    }
    val nextFormFactor = when (forceMobile) {
        true -> AgentUiFormFactor.Mobile
        false -> AgentUiFormFactor.Desktop
        null -> currentFormFactor
    }
    AgentUiTheme.ProvideSizing(sizing = nextSizing, formFactor = nextFormFactor, content = content)
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
