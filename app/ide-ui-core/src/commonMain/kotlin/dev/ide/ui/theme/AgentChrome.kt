package dev.ide.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Agent-first IDE chrome tokens (from the Agent-first redesign HTML).
 *
 * These sit beside Material 3: M3 still drives platform components; [AgentChrome] is what the agent
 * surfaces (home prompt, session, composer, dock panes, settings) paint with so both iOS and Android
 * share one identity without waiting on every Material role.
 */
@Immutable
data class AgentChrome(
    val isDark: Boolean,
    /** App canvas behind cards. */
    val bg: Color,
    /** Deepest ink — text on lime, recessed wells. */
    val bgDeep: Color,
    /** Modal void / sheet scrim base. */
    val bgVoid: Color,
    /** Primary card / panel / composer fill. */
    val surface: Color,
    /** Elevated chip / user bubble / selected row. */
    val surfaceAlt: Color,
    /** Segmented-control selected fill. */
    val surfaceRaised: Color,
    /** Hairline borders and dividers. */
    val hairline: Color,
    val hairlineStrong: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    /** Lime CTA accent. */
    val accent: Color,
    /** Ink on [accent]. */
    val accentInk: Color,
    /** Soft lime wash for highlighted rows / code lines. */
    val accentSoft: Color,
    val accentSoftLine: Color,
    val warn: Color,
    val danger: Color,
    val info: Color,
    val violet: Color,
    val amber: Color,
    val codeOk: Color,
    /** Inset top highlight used on elevated pills. */
    val sheen: Color,
)

/** Default olive dark identity from the redesign. */
val AgentChromeOliveDark = AgentChrome(
    isDark = true,
    bg = Color(0xFF191A17),
    bgDeep = Color(0xFF141510),
    bgVoid = Color(0xFF070806),
    surface = Color(0xFF1D1E1A),
    surfaceAlt = Color(0xFF282924),
    surfaceRaised = Color(0xFF2C2D27),
    hairline = Color(0x0FFFFFFF), // 6%
    hairlineStrong = Color(0x1AFFFFFF), // 10%
    text = Color(0xFFEDEDE6),
    textMuted = Color(0xFFA3A49B),
    textFaint = Color(0xFF8C8D84),
    accent = Color(0xFFC8F169),
    accentInk = Color(0xFF141510),
    accentSoft = Color(0x0DC8F169), // 5%
    accentSoftLine = Color(0x14C8F169), // 8%
    warn = Color(0xFFF2B661),
    danger = Color(0xFFFF9A80),
    info = Color(0xFF7FD4FF),
    violet = Color(0xFFD6B4FF),
    amber = Color(0xFFFFB86B),
    codeOk = Color(0xFFE8F5C8),
    sheen = Color(0x0FFFFFFF),
)

/** Cool gray light (Machined). */
val AgentChromeMachinedLight = AgentChrome(
    isDark = false,
    bg = Color(0xFFF8F9F7),
    bgDeep = Color(0xFF15171A),
    bgVoid = Color(0xFFE8EAE6),
    surface = Color(0xFFF6F7F5),
    surfaceAlt = Color(0xFFEDEFEA),
    surfaceRaised = Color(0xFFE3E5E2),
    hairline = Color(0x14000000),
    hairlineStrong = Color(0x22000000),
    text = Color(0xFF15171A),
    textMuted = Color(0xFF3D4246),
    textFaint = Color(0xFF5A5F63),
    accent = Color(0xFFC8F169),
    accentInk = Color(0xFF141510),
    accentSoft = Color(0x14C8F169),
    accentSoftLine = Color(0x28C8F169),
    warn = Color(0xFFE89A2C),
    danger = Color(0xFFC2410C),
    info = Color(0xFF2A44D6),
    violet = Color(0xFF5B2A86),
    amber = Color(0xFF8A4B00),
    codeOk = Color(0xFFE8F5C8),
    sheen = Color(0x14FFFFFF),
)

/** Lavender pastel light (Anodized). */
val AgentChromeAnodizedLight = AgentChrome(
    isDark = false,
    bg = Color(0xFFECEAF3),
    bgDeep = Color(0xFF1D1B2E),
    bgVoid = Color(0xFFD9D2F1),
    surface = Color(0xFFE2DCF6),
    surfaceAlt = Color(0xFFD6CFF0),
    surfaceRaised = Color(0xFFCBC2EC),
    hairline = Color(0x1A000000),
    hairlineStrong = Color(0x2A000000),
    text = Color(0xFF1D1B2E),
    textMuted = Color(0xFF3E3A55),
    textFaint = Color(0xFF4A4661),
    accent = Color(0xFF5E8F00),
    accentInk = Color(0xFFFFFFFF),
    accentSoft = Color(0x145E8F00),
    accentSoftLine = Color(0x285E8F00),
    warn = Color(0xFFBFEAD9),
    danger = Color(0xFFB42318),
    info = Color(0xFF2A2638),
    violet = Color(0xFF4A4661),
    amber = Color(0xFF7A4200),
    codeOk = Color(0xFFD4F0E4),
    sheen = Color(0x1AFFFFFF),
)

/** Cream + royal blue (Ceramic drafting). */
val AgentChromeCeramicLight = AgentChrome(
    isDark = false,
    bg = Color(0xFFFBFBF8),
    bgDeep = Color(0xFF16181D),
    bgVoid = Color(0xFFE1E1DB),
    surface = Color(0xFFF4F4F0),
    surfaceAlt = Color(0xFFECEEE8),
    surfaceRaised = Color(0xFFE1E1DB),
    hairline = Color(0x14000000),
    hairlineStrong = Color(0x24000000),
    text = Color(0xFF16181D),
    textMuted = Color(0xFF33363D),
    textFaint = Color(0xFF4E525A),
    accent = Color(0xFF2A44D6),
    accentInk = Color(0xFFFFFFFF),
    accentSoft = Color(0x142A44D6),
    accentSoftLine = Color(0x282A44D6),
    warn = Color(0xFFE89A2C),
    danger = Color(0xFFB42318),
    info = Color(0xFF2A44D6),
    violet = Color(0xFF5B2A86),
    amber = Color(0xFF8A4B00),
    codeOk = Color(0xFFE8F5C8),
    sheen = Color(0x14FFFFFF),
)

/** Named skins offered in appearance settings. */
enum class AgentSkin { Olive, Machined, Anodized, Ceramic, GlassFlood }

fun agentChrome(skin: AgentSkin, dark: Boolean): AgentChrome = when (skin) {
    AgentSkin.Olive -> if (dark) AgentChromeOliveDark else AgentChromeMachinedLight
    AgentSkin.Machined -> if (dark) AgentChromeOliveDark.copy(
        bg = Color(0xFF15171A),
        surface = Color(0xFF1B1D1F),
        surfaceAlt = Color(0xFF2E3236),
        text = Color(0xFFEDEFEA),
        textMuted = Color(0xFFB0B4AD),
        textFaint = Color(0xFF8A8F88),
    ) else AgentChromeMachinedLight
    AgentSkin.Anodized -> if (dark) AgentChromeOliveDark.copy(
        bg = Color(0xFF1D1B2E),
        surface = Color(0xFF2A2638),
        surfaceAlt = Color(0xFF3E3A55),
        text = Color(0xFFECEAF3),
        textMuted = Color(0xFFCFC6F2),
        textFaint = Color(0xFFA69BC8),
        accent = Color(0xFFBFEAD9),
    ) else AgentChromeAnodizedLight
    AgentSkin.Ceramic -> if (dark) AgentChromeOliveDark.copy(
        bg = Color(0xFF16181D),
        surface = Color(0xFF1A1C20),
        surfaceAlt = Color(0xFF33363D),
        accent = Color(0xFF7FD4FF),
    ) else AgentChromeCeramicLight
    AgentSkin.GlassFlood -> AgentChromeOliveDark.copy(
        bg = Color(0xFF15171A),
        surface = Color(0xFF1B1D1F),
        surfaceAlt = Color(0xFF2E3236),
        accent = Color(0xFFC8F169),
        info = Color(0xFF7A9CFF),
        violet = Color(0xFFB794F6),
    )
}

@Immutable
object AgentRadius {
    val chip = 18.dp
    val card = 16.dp
    val panel = 22.dp
    val sheet = 28.dp
    val bubble = 18.dp
    val control = 12.dp
    val tile = 12.dp
}

@Immutable
object AgentSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 22.dp
    val xxl = 28.dp
}

val LocalAgentChrome = staticCompositionLocalOf { AgentChromeOliveDark }

/** Accessor: `AgentUi.chrome`, `AgentUi.radius`, `AgentUi.space`. */
object AgentUi {
    val chrome: AgentChrome
        @androidx.compose.runtime.Composable @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalAgentChrome.current
    val radius get() = AgentRadius
    val space get() = AgentSpacing
}
