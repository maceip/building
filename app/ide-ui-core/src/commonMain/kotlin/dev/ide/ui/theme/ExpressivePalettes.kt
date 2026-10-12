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
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    surfaceTint = Color(0xFF2563EB),
    inverseSurface = Color(0xFF1E293B),
    inverseOnSurface = Color(0xFFF1F5F9),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFFCBD5E1),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF8FAFC),
    surfaceDim = Color(0xFFDCE3EC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5F9),
    surfaceContainer = Color(0xFFEAEFF5),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFFCBD5E1),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val NeutralDark = darkColorScheme(
    background = Color(0xFF121316),
    onBackground = Color(0xFFE4E6EB),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE4E6EB),
    surfaceVariant = Color(0xFF2C2E36),
    onSurfaceVariant = Color(0xFF9DA2B0),
    surfaceTint = Color(0xFF7AA2F7),
    inverseSurface = Color(0xFFE4E6EB),
    inverseOnSurface = Color(0xFF1C1D22),
    outline = Color(0xFF6E7382),
    outlineVariant = Color(0xFF32353E),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF343741),
    surfaceDim = Color(0xFF121316),
    surfaceContainerLowest = Color(0xFF0D0E11),
    surfaceContainerLow = Color(0xFF17181C),
    surfaceContainer = Color(0xFF1C1D22),
    surfaceContainerHigh = Color(0xFF23252B),
    surfaceContainerHighest = Color(0xFF2C2E36),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
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
    primaryContainer = Color(0xFFDBEAFE), onPrimaryContainer = Color(0xFF1E3A8A),
    inversePrimary = Color(0xFF7AA2F7),
    secondary = Color(0xFF475569), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E8F0), onSecondaryContainer = Color(0xFF1E293B),
    tertiary = Color(0xFF0D9488), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCFBF1), onTertiaryContainer = Color(0xFF134E4A),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    surfaceTint = Color(0xFF2563EB),
    inverseSurface = Color(0xFF1E293B),
    inverseOnSurface = Color(0xFFF1F5F9),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFFCBD5E1),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF8FAFC),
    surfaceDim = Color(0xFFDCE3EC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5F9),
    surfaceContainer = Color(0xFFEAEFF5),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D),
)

private val LimeDark = darkColorScheme(
    primary = Color(0xFF7AA2F7), onPrimary = Color(0xFF0A192F),
    primaryContainer = Color(0xFF1E3A5F), onPrimaryContainer = Color(0xFFD6E4FF),
    inversePrimary = Color(0xFF2563EB),
    secondary = Color(0xFF9AA5CE), onSecondary = Color(0xFF1A2035),
    secondaryContainer = Color(0xFF282E44), onSecondaryContainer = Color(0xFFDCE2F9),
    tertiary = Color(0xFF73DACA), onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF134E48), onTertiaryContainer = Color(0xFFB2F5EA),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE4E6EB),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE4E6EB),
    surfaceVariant = Color(0xFF2C2E36),
    onSurfaceVariant = Color(0xFF9DA2B0),
    surfaceTint = Color(0xFF7AA2F7),
    inverseSurface = Color(0xFFE4E6EB),
    inverseOnSurface = Color(0xFF1C1D22),
    outline = Color(0xFF6E7382),
    outlineVariant = Color(0xFF32353E),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF343741),
    surfaceDim = Color(0xFF121316),
    surfaceContainerLowest = Color(0xFF0D0E11),
    surfaceContainerLow = Color(0xFF17181C),
    surfaceContainer = Color(0xFF1C1D22),
    surfaceContainerHigh = Color(0xFF23252B),
    surfaceContainerHighest = Color(0xFF2C2E36),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

/** The fixed expressive [ColorScheme] for an [accent] in the given mode (the non-dynamic fallback). */
fun expressiveColorScheme(accent: CaAccent, dark: Boolean): ColorScheme {
    // Lime carries its own neutrals (see above) and returns early; the rest share one ramp.
    if (accent == CaAccent.Lime) return if (dark) LimeDark else LimeLight
    val roles = when (accent) {
        CaAccent.Violet -> if (dark) VioletDark else VioletLight
        CaAccent.Teal -> if (dark) TealDark else TealLight
        CaAccent.Orange -> if (dark) OrangeDark else OrangeLight
        CaAccent.Lime -> error("handled above")
    }
    return (if (dark) NeutralDark else NeutralLight).withAccent(roles)
}
