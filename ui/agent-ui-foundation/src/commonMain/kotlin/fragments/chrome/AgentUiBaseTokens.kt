package fragments.chrome

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/**
 * Authoritative design tokens extracted from `research/agent-ui-base/styles/agents.css`
 * and `research/agent-ui-base/DESIGN.md` (`prompt-kit`, `ai-elements`, `agent-elements`, `beautiful-ui`).
 *
 * Enforces:
 * - High-Craft Light Mode default (`#FAFAFB` cool alabaster canvas, `#FFFFFF` pure crisp cards)
 * - Carbon-Slate Dark Mode (`#17181A` canvas, `#232427` cards)
 * - 1.dp exposed hairline borders (`#ECEDEF` light, `#2E3033` dark)
 * - Strict 4px spacing rhythm and radius scale (`6.dp` chip, `8.dp` control, `10.dp` card, `14.dp` window)
 * - Mathematical WCAG 2.2 AA contrast (`>= 4.5:1`) for all primary and secondary text tokens
 */
data class AgentUiBasePalette(
    val isDark: Boolean,
    val backgroundFull: Color,
    val cardPrimary: Color,
    val secondaryBg: Color,
    val hoverBg: Color,
    val frameBg: Color,
    val sidebarBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val buttonBorder: Color,
    val separatorBorder: Color,
    val accentBlue: Color,
    val statusGreen: Color,
    val statusOrange: Color,
    val statusRed: Color,
)

object AgentUiBaseTokens {
    // Radius Scale (from `research/agent-ui-base/DESIGN.md` §6)
    val RadiusChip: Dp = 6.dp
    val RadiusControl: Dp = 8.dp
    val RadiusCard: Dp = 10.dp
    val RadiusWindow: Dp = 14.dp
    val RadiusComposer: Dp = 16.dp
    val RadiusPill: Dp = 999.dp

    // Border & Stroke Tokens
    val HairlineBorder: Dp = 1.dp

    // Spacing Rhythm (4px base unit)
    val Space1: Dp = 4.dp
    val Space2: Dp = 8.dp
    val Space3: Dp = 12.dp
    val Space4: Dp = 16.dp
    val Space5: Dp = 20.dp
    val Space6: Dp = 24.dp

    val Light = AgentUiBasePalette(
        isDark = false,
        backgroundFull = Color(0xFFFAFAFB),
        cardPrimary = Color(0xFFFFFFFF),
        secondaryBg = Color(0xFFF1F2F3),
        hoverBg = Color(0xFFF4F5F6),
        frameBg = Color(0xFFECEDEF),
        sidebarBg = Color(0xFFF4F5F6),
        textPrimary = Color(0xFF1F2124),
        textSecondary = Color(0xFF5A5D64),
        textTertiary = Color(0xFF62656B),
        buttonBorder = Color(0xFFE0E2E5),
        separatorBorder = Color(0xFFECEDEF),
        accentBlue = Color(0xFF0285FF),
        statusGreen = Color(0xFF199A4D),
        statusOrange = Color(0xFFEF720D),
        statusRed = Color(0xFFE3474C),
    )

    val Dark = AgentUiBasePalette(
        isDark = true,
        backgroundFull = Color(0xFF17181A),
        cardPrimary = Color(0xFF232427),
        secondaryBg = Color(0xFF1C1D1F),
        hoverBg = Color(0xFF2A2B2E),
        frameBg = Color(0xFF1C1D1F),
        sidebarBg = Color(0xFF202124),
        textPrimary = Color(0xFFF2F3F4),
        textSecondary = Color(0xFFA5A8AD),
        textTertiary = Color(0xFF9C9FA3),
        buttonBorder = Color(0xFF3A3C40),
        separatorBorder = Color(0xFF2E3033),
        accentBlue = Color(0xFF3D9AFF),
        statusGreen = Color(0xFF3CBB72),
        statusOrange = Color(0xFFF68F3C),
        statusRed = Color(0xFFEE5C61),
    )

    fun forTheme(darkTheme: Boolean): AgentUiBasePalette = if (darkTheme) Dark else Light

    /**
     * WCAG 2.2 relative luminance calculation for conformance gating.
     */
    fun relativeLuminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    /**
     * WCAG 2.2 contrast ratio (`1.0` to `21.0`).
     */
    fun contrastRatio(foreground: Color, background: Color): Double {
        val l1 = relativeLuminance(foreground)
        val l2 = relativeLuminance(background)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
