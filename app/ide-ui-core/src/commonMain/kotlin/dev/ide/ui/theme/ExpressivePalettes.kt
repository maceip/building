package dev.ide.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Material 3 Expressive tonal palettes. These are the fixed fallback schemes used on desktop and on
 * Android below 12 (where wallpaper-derived dynamic color isn't available — see
 * [dev.ide.ui.platform.dynamicColorSchemeOrNull]). One neutral surface ramp is shared across the three
 * accents; each accent layers its own vibrant primary / secondary / tertiary tones on top. Chroma is kept
 * high per the expressive spec — the tertiary role in particular carries a colorful counter-accent.
 */

/** The accent roles that differ per seed. Neutral surfaces are shared. */
private class AccentRoles(
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color, val onSecondary: Color, val secondaryContainer: Color, val onSecondaryContainer: Color,
    val tertiary: Color, val onTertiary: Color, val tertiaryContainer: Color, val onTertiaryContainer: Color,
)

private fun ColorScheme.withAccent(a: AccentRoles): ColorScheme = copy(
    primary = a.primary, onPrimary = a.onPrimary,
    primaryContainer = a.primaryContainer, onPrimaryContainer = a.onPrimaryContainer,
    inversePrimary = a.inversePrimary,
    secondary = a.secondary, onSecondary = a.onSecondary,
    secondaryContainer = a.secondaryContainer, onSecondaryContainer = a.onSecondaryContainer,
    tertiary = a.tertiary, onTertiary = a.onTertiary,
    tertiaryContainer = a.tertiaryContainer, onTertiaryContainer = a.onTertiaryContainer,
    surfaceTint = a.primary,
)

// ---- Shared neutral surface ramps (the M3 surface-container tiers) ----

private val NeutralLight = lightColorScheme(
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF09090B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF09090B),
    surfaceVariant = Color(0xFFE4E4E7),
    onSurfaceVariant = Color(0xFF52525B),
    surfaceTint = Color(0xFF2563EB),
    inverseSurface = Color(0xFF18181B),
    inverseOnSurface = Color(0xFFFAFAFA),
    outline = Color(0xFF71717A),
    outlineVariant = Color(0xFFE4E4E7),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE4E4E7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F4F5),
    surfaceContainer = Color(0xFFEEEEF0),
    surfaceContainerHigh = Color(0xFFE4E4E7),
    surfaceContainerHighest = Color(0xFFD4D4D8),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val NeutralDark = darkColorScheme(
    background = Color(0xFF09090B),
    onBackground = Color(0xFFF4F4F6),
    surface = Color(0xFF09090B),
    onSurface = Color(0xFFF4F4F6),
    surfaceVariant = Color(0xFF222228),
    onSurfaceVariant = Color(0xFFA1A1AA),
    surfaceTint = Color(0xFF6E8EF5),
    inverseSurface = Color(0xFFF4F4F6),
    inverseOnSurface = Color(0xFF111114),
    outline = Color(0xFF71717A),
    outlineVariant = Color(0xFF232329),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF27272E),
    surfaceDim = Color(0xFF09090B),
    surfaceContainerLowest = Color(0xFF060608),
    surfaceContainerLow = Color(0xFF111114),
    surfaceContainer = Color(0xFF16161A),
    surfaceContainerHigh = Color(0xFF1D1D22),
    surfaceContainerHighest = Color(0xFF27272A),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF3B1219),
    onErrorContainer = Color(0xFFFECACA),
)

// ---- Per-accent roles ----

private val VioletLight = AccentRoles(
    primary = Color(0xFF6E4CE0), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9DDFF), onPrimaryContainer = Color(0xFF22005C),
    inversePrimary = Color(0xFFCFBCFF),
    secondary = Color(0xFF625B71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1E192B),
    tertiary = Color(0xFFB0468E), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8EC), onTertiaryContainer = Color(0xFF3B0027),
)
private val VioletDark = AccentRoles(
    primary = Color(0xFFCFBCFF), onPrimary = Color(0xFF38158A),
    primaryContainer = Color(0xFF5335C0), onPrimaryContainer = Color(0xFFE9DDFF),
    inversePrimary = Color(0xFF6E4CE0),
    secondary = Color(0xFFCBC2DB), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFFFAED6), onTertiary = Color(0xFF5E1147),
    tertiaryContainer = Color(0xFF7C2C60), onTertiaryContainer = Color(0xFFFFD8EC),
)

private val TealLight = AccentRoles(
    primary = Color(0xFF00687A), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFACEDFF), onPrimaryContainer = Color(0xFF001F27),
    inversePrimary = Color(0xFF55D6F5),
    secondary = Color(0xFF4B6269), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE7EF), onSecondaryContainer = Color(0xFF061F25),
    tertiary = Color(0xFF5B5C7E), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE1E0FF), onTertiaryContainer = Color(0xFF171937),
)
private val TealDark = AccentRoles(
    primary = Color(0xFF55D6F5), onPrimary = Color(0xFF003641),
    primaryContainer = Color(0xFF004E5D), onPrimaryContainer = Color(0xFFACEDFF),
    inversePrimary = Color(0xFF00687A),
    secondary = Color(0xFFB1CBD3), onSecondary = Color(0xFF1C343A),
    secondaryContainer = Color(0xFF334A51), onSecondaryContainer = Color(0xFFCDE7EF),
    tertiary = Color(0xFFC3C3EB), onTertiary = Color(0xFF2C2E4D),
    tertiaryContainer = Color(0xFF434465), onTertiaryContainer = Color(0xFFE1E0FF),
)

private val OrangeLight = AccentRoles(
    primary = Color(0xFF9A4A00), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCBE), onPrimaryContainer = Color(0xFF2E1500),
    inversePrimary = Color(0xFFFFB77C),
    secondary = Color(0xFF745943), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCBE), onSecondaryContainer = Color(0xFF2A1706),
    tertiary = Color(0xFF5B6236), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDFE7B0), onTertiaryContainer = Color(0xFF191E00),
)
private val OrangeDark = AccentRoles(
    primary = Color(0xFFFFB77C), onPrimary = Color(0xFF4D2700),
    primaryContainer = Color(0xFF6D3900), onPrimaryContainer = Color(0xFFFFDCBE),
    inversePrimary = Color(0xFF9A4A00),
    secondary = Color(0xFFE3C0A4), onSecondary = Color(0xFF422C19),
    secondaryContainer = Color(0xFF5B422D), onSecondaryContainer = Color(0xFFFFDCBE),
    tertiary = Color(0xFFC3CB97), onTertiary = Color(0xFF2D330D),
    tertiaryContainer = Color(0xFF434A21), onTertiaryContainer = Color(0xFFDFE7B0),
)

// ---- Default IDE scheme: neutral slate surfaces with a restrained steel-blue accent ----

private val LimeLight = lightColorScheme(
    primary = Color(0xFF2563EB), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEFF6FF), onPrimaryContainer = Color(0xFF1E3A8A),
    inversePrimary = Color(0xFF6E8EF7),
    secondary = Color(0xFF3F3F46), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF4F4F5), onSecondaryContainer = Color(0xFF18181B),
    tertiary = Color(0xFF0D9488), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF0FDFA), onTertiaryContainer = Color(0xFF134E4A),
    background = Color(0xFFFAFAFB),
    onBackground = Color(0xFF09090B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF09090B),
    surfaceVariant = Color(0xFFF4F4F5),
    onSurfaceVariant = Color(0xFF52525B),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF18181B),
    inverseOnSurface = Color(0xFFFAFAFA),
    outline = Color(0xFF71717A),
    outlineVariant = Color(0xFFE4E4E7),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFF4F4F5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F4F6),
    surfaceContainer = Color(0xFFEEEEF1),
    surfaceContainerHigh = Color(0xFFE4E4E7),
    surfaceContainerHighest = Color(0xFFD4D4D8),
    error = Color(0xFFDC2626), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D),
)

private val LimeDark = darkColorScheme(
    primary = Color(0xFF6E8EF7), onPrimary = Color(0xFF080C1A),
    primaryContainer = Color(0xFF192342), onPrimaryContainer = Color(0xFFDCE6FF),
    inversePrimary = Color(0xFF2563EB),
    secondary = Color(0xFFA1A1AA), onSecondary = Color(0xFF141519),
    secondaryContainer = Color(0xFF1E2028), onSecondaryContainer = Color(0xFFE4E6EE),
    tertiary = Color(0xFF4FD1C5), onTertiary = Color(0xFF042F2E),
    tertiaryContainer = Color(0xFF112E2B), onTertiaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF09090B),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF111215),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF1E2026),
    onSurfaceVariant = Color(0xFF9CA3AF),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFF3F4F6),
    inverseOnSurface = Color(0xFF09090B),
    outline = Color(0xFF636775),
    outlineVariant = Color(0xFF23252C),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF272932),
    surfaceDim = Color(0xFF09090B),
    surfaceContainerLowest = Color(0xFF060608),
    surfaceContainerLow = Color(0xFF111215),
    surfaceContainer = Color(0xFF15161A),
    surfaceContainerHigh = Color(0xFF1C1D22),
    surfaceContainerHighest = Color(0xFF24262D),
    error = Color(0xFFF87171), onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF450A0A), onErrorContainer = Color(0xFFFECACA),
)

private val MidnightDark = darkColorScheme(
    primary = Color(0xFF60A5FA), onPrimary = Color(0xFF090A0F),
    primaryContainer = Color(0xFF1E3A8A), onPrimaryContainer = Color(0xFFDBEAFE),
    inversePrimary = Color(0xFF2563EB),
    secondary = Color(0xFF94A3B8), onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF1E293B), onSecondaryContainer = Color(0xFFE2E8F0),
    tertiary = Color(0xFF38BDF8), onTertiary = Color(0xFF082F49),
    tertiaryContainer = Color(0xFF0369A1), onTertiaryContainer = Color(0xFFE0F2FE),
    background = Color(0xFF090A0F),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF090A0F),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1A1E2C),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceTint = Color(0xFF60A5FA),
    inverseSurface = Color(0xFFF1F5F9),
    inverseOnSurface = Color(0xFF090A0F),
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF212635),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF222738),
    surfaceDim = Color(0xFF090A0F),
    surfaceContainerLowest = Color(0xFF05060A),
    surfaceContainerLow = Color(0xFF0D0F16),
    surfaceContainer = Color(0xFF131620),
    surfaceContainerHigh = Color(0xFF1A1E2C),
    surfaceContainerHighest = Color(0xFF222738),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

/** The fixed expressive [ColorScheme] for an [accent] in the given mode (the non-dynamic fallback). */
fun expressiveColorScheme(accent: CaAccent, dark: Boolean): ColorScheme {
    // Lime and Midnight carry their own neutrals and return early; the rest share one ramp.
    if (accent == CaAccent.Midnight) return if (dark) MidnightDark else LimeLight
    if (accent == CaAccent.Lime) return if (dark) LimeDark else LimeLight
    val roles = when (accent) {
        CaAccent.Violet -> if (dark) VioletDark else VioletLight
        CaAccent.Teal -> if (dark) TealDark else TealLight
        CaAccent.Orange -> if (dark) OrangeDark else OrangeLight
        CaAccent.Lime, CaAccent.Midnight -> error("handled above")
    }
    return (if (dark) NeutralDark else NeutralLight).withAccent(roles)
}
