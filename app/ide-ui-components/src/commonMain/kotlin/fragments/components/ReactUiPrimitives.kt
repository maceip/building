package fragments.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fragments.agentui.AgentUiTheme
import fragments.chrome.AgentUiBaseTokens

/** Unified palette alias across all converted and native Agent UI components. */
typealias ReactUiPalette = fragments.agentui.AgentUiPalette

/** Semantic button/control variants modeled after cosscom/coss with Light & Dark pseudo-elevation. */
enum class UiButtonVariant {
    Default,
    Primary,
    Secondary,
    Destructive,
    DestructiveOutline,
    Ghost,
}

object ReactUiPalettes {
    val AgentElementsLight: ReactUiPalette = AgentUiTheme.Light
    val AgentElementsDark: ReactUiPalette = AgentUiTheme.Dark
    val CreanceLight: ReactUiPalette = AgentUiTheme.Light
    val CreanceDark: ReactUiPalette = AgentUiTheme.Dark
}

/**
 * Applies `cosscom/coss` pseudo-elevation to interactive buttons:
 * - Unpressed: subtle 1.5dp drop shadow + 1dp outer border + 1dp top inner specular highlight
 *   (`white/16%` on Primary/Destructive, `white/7%` in Dark outline/secondary, `white/70%` + `black/4%` bottom rim in Light).
 * - Pressed: flattens outer drop shadow and flips the top rim into a subtle inset shadow (`black/10%`).
 */
fun Modifier.cossButtonElevation(
    palette: ReactUiPalette,
    variant: UiButtonVariant,
    radius: Dp = AgentUiBaseTokens.RadiusControl,
    pressed: Boolean = false,
    hovered: Boolean = false,
    enabled: Boolean = true,
): Modifier {
    val shape = RoundedCornerShape(radius)
    val baseFill = when (variant) {
        UiButtonVariant.Primary -> palette.accent
        UiButtonVariant.Destructive -> palette.danger
        UiButtonVariant.Secondary -> palette.subtle
        UiButtonVariant.DestructiveOutline -> if (hovered || pressed) palette.danger.copy(alpha = if (palette.isDark) 0.14f else 0.08f) else palette.surface
        UiButtonVariant.Ghost -> if (pressed) palette.border.copy(alpha = 0.55f) else if (hovered) palette.subtle else Color.Transparent
        UiButtonVariant.Default -> if (palette.isDark) lerp(palette.surface, Color.White, 0.03f) else palette.surface
    }
    val fill = when {
        !enabled -> if (variant == UiButtonVariant.Ghost) Color.Transparent else palette.subtle.copy(alpha = 0.65f)
        pressed && (variant == UiButtonVariant.Primary || variant == UiButtonVariant.Destructive) -> lerp(baseFill, Color.Black, 0.12f)
        hovered && (variant == UiButtonVariant.Primary || variant == UiButtonVariant.Destructive) -> lerp(baseFill, Color.White, 0.07f)
        pressed && (variant == UiButtonVariant.Default || variant == UiButtonVariant.Secondary) -> lerp(palette.subtle, palette.border, 0.35f)
        hovered && variant == UiButtonVariant.Default -> if (palette.isDark) lerp(palette.surface, Color.White, 0.07f) else palette.subtle
        hovered && variant == UiButtonVariant.Secondary -> lerp(palette.subtle, palette.border, 0.22f)
        else -> baseFill
    }
    val borderColor = when {
        variant == UiButtonVariant.Ghost -> Color.Transparent
        !enabled -> palette.border.copy(alpha = 0.6f)
        variant == UiButtonVariant.Primary -> lerp(palette.accent, Color.Black, if (palette.isDark) 0.10f else 0.08f)
        variant == UiButtonVariant.Destructive -> lerp(palette.danger, Color.Black, if (palette.isDark) 0.10f else 0.08f)
        variant == UiButtonVariant.DestructiveOutline -> palette.danger.copy(alpha = if (hovered || pressed) 0.62f else 0.38f)
        else -> if (palette.isDark) lerp(palette.border, Color.White, 0.06f) else lerp(palette.border, Color.Black, 0.05f)
    }
    return this
        .drawWithContent {
            val rPx = radius.toPx()
            val strokePx = 1.dp.toPx()
            if (enabled && !pressed && variant != UiButtonVariant.Ghost) {
                val shadowColor = when (variant) {
                    UiButtonVariant.Primary -> palette.accent.copy(alpha = if (palette.isDark) 0.28f else 0.22f)
                    UiButtonVariant.Destructive -> palette.danger.copy(alpha = if (palette.isDark) 0.28f else 0.22f)
                    else -> Color.Black.copy(alpha = if (palette.isDark) 0.28f else 0.06f)
                }
                drawRoundRect(
                    color = shadowColor,
                    topLeft = Offset(0f, 1.25.dp.toPx()),
                    size = Size(size.width, size.height),
                    cornerRadius = CornerRadius(rPx, rPx),
                )
            }
            drawRoundRect(
                color = fill,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(rPx, rPx),
            )
            drawContent()
            if (variant != UiButtonVariant.Ghost && size.width > strokePx * 4 && size.height > strokePx * 4) {
                val inset = strokePx * 1.2f
                val innerWidth = (size.width - rPx * 0.9f).coerceAtLeast(2f)
                val startX = (size.width - innerWidth) / 2f
                if (pressed && enabled) {
                    drawLine(
                        color = Color.Black.copy(alpha = if (palette.isDark) 0.24f else 0.10f),
                        start = Offset(startX, inset),
                        end = Offset(startX + innerWidth, inset),
                        strokeWidth = strokePx,
                    )
                } else if (enabled) {
                    val topHighlight = when (variant) {
                        UiButtonVariant.Primary, UiButtonVariant.Destructive -> Color.White.copy(alpha = 0.20f)
                        else -> if (palette.isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.75f)
                    }
                    drawLine(
                        color = topHighlight,
                        start = Offset(startX, inset),
                        end = Offset(startX + innerWidth, inset),
                        strokeWidth = strokePx,
                    )
                    if (!palette.isDark && variant != UiButtonVariant.Primary && variant != UiButtonVariant.Destructive) {
                        drawLine(
                            color = Color.Black.copy(alpha = 0.045f),
                            start = Offset(startX, size.height - inset),
                            end = Offset(startX + innerWidth, size.height - inset),
                            strokeWidth = strokePx,
                        )
                    }
                }
                drawRoundRect(
                    color = borderColor,
                    topLeft = Offset(strokePx / 2f, strokePx / 2f),
                    size = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = CornerRadius((rPx - strokePx / 2f).coerceAtLeast(0f)),
                    style = Stroke(width = strokePx),
                )
            }
        }
        .clip(shape)
}

/**
 * Applies `cosscom/coss` card/surface pseudo-elevation:
 * - Crisp 1dp hairline border + subtle ambient drop shadow
 * - 1dp inset top specular highlight (`white/6%` in Dark mode, `white/80%` in Light mode)
 * - 1dp bottom inner rim bevel (`black/4%` in Light mode, `black/22%` in Dark mode).
 */
fun Modifier.cossSurfaceElevation(
    palette: ReactUiPalette,
    radius: Dp = palette.toolRadius,
    borderColor: Color = palette.border,
    surfaceColor: Color = palette.surface,
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .drawWithContent {
            val rPx = radius.toPx()
            val strokePx = 1.dp.toPx()
            drawRoundRect(
                color = Color.Black.copy(alpha = if (palette.isDark) 0.26f else 0.05f),
                topLeft = Offset(0f, 1.5.dp.toPx()),
                size = size,
                cornerRadius = CornerRadius(rPx, rPx),
            )
            drawRoundRect(
                color = surfaceColor,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(rPx, rPx),
            )
            drawContent()
            if (size.width > strokePx * 4 && size.height > strokePx * 4) {
                val inset = strokePx * 1.2f
                val innerWidth = (size.width - rPx * 0.9f).coerceAtLeast(2f)
                val startX = (size.width - innerWidth) / 2f
                drawLine(
                    color = if (palette.isDark) Color.White.copy(alpha = 0.065f) else Color.White.copy(alpha = 0.80f),
                    start = Offset(startX, inset),
                    end = Offset(startX + innerWidth, inset),
                    strokeWidth = strokePx,
                )
                drawLine(
                    color = if (palette.isDark) Color.Black.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.04f),
                    start = Offset(startX, size.height - inset),
                    end = Offset(startX + innerWidth, size.height - inset),
                    strokeWidth = strokePx,
                )
                drawRoundRect(
                    color = borderColor,
                    topLeft = Offset(strokePx / 2f, strokePx / 2f),
                    size = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = CornerRadius((rPx - strokePx / 2f).coerceAtLeast(0f)),
                    style = Stroke(width = strokePx),
                )
            }
        }
        .clip(shape)
}

/**
 * Applies `cosscom/coss` input field recessed bevel treatment.
 */
fun Modifier.cossInputElevation(
    palette: ReactUiPalette,
    radius: Dp = AgentUiBaseTokens.RadiusControl,
    focused: Boolean = false,
): Modifier {
    val shape = RoundedCornerShape(radius)
    val borderColor = if (focused) palette.accent else palette.border
    val fill = if (palette.isDark) lerp(palette.background, Color.White, 0.02f) else palette.background
    return this
        .drawWithContent {
            val rPx = radius.toPx()
            val strokePx = 1.dp.toPx()
            drawRoundRect(
                color = fill,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(rPx, rPx),
            )
            drawContent()
            if (size.width > strokePx * 4 && size.height > strokePx * 4) {
                val inset = strokePx * 1.2f
                val innerWidth = (size.width - rPx * 0.9f).coerceAtLeast(2f)
                val startX = (size.width - innerWidth) / 2f
                drawLine(
                    color = if (palette.isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.04f),
                    start = Offset(startX, inset),
                    end = Offset(startX + innerWidth, inset),
                    strokeWidth = strokePx,
                )
                drawRoundRect(
                    color = borderColor,
                    topLeft = Offset(strokePx / 2f, strokePx / 2f),
                    size = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = CornerRadius((rPx - strokePx / 2f).coerceAtLeast(0f)),
                    style = Stroke(width = strokePx),
                )
            }
        }
        .clip(shape)
}

@Composable
fun UiText(
    value: String,
    palette: ReactUiPalette = AgentUiTheme.palette,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    size: Int = 14,
    weight: FontWeight = FontWeight.Normal,
    mono: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val effectiveSize = size + AgentUiTheme.sizing.fontDeltaSp
    BasicText(
        value,
        modifier,
        style = TextStyle(
            color = if (muted) palette.muted else palette.text,
            fontSize = effectiveSize.sp,
            lineHeight = (effectiveSize * 1.45f).sp,
            fontWeight = weight,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        ),
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
fun UiButton(
    label: String,
    onClick: () -> Unit,
    palette: ReactUiPalette = AgentUiTheme.palette,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    variant: UiButtonVariant = if (primary) UiButtonVariant.Primary else UiButtonVariant.Default,
) {
    val sizing = AgentUiTheme.sizing
    val effectiveVariant = if (primary && variant == UiButtonVariant.Default) UiButtonVariant.Primary else variant
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val textColor by animateColorAsState(
        targetValue = when {
            !enabled -> palette.muted
            effectiveVariant == UiButtonVariant.Primary -> palette.accentText
            effectiveVariant == UiButtonVariant.Destructive -> Color.White
            effectiveVariant == UiButtonVariant.DestructiveOutline -> palette.danger
            else -> palette.text
        },
        animationSpec = tween(110),
        label = "button text color",
    )
    val fontSizeSp = (13 + sizing.fontDeltaSp).sp
    Box(
        modifier
            .cossButtonElevation(
                palette = palette,
                variant = effectiveVariant,
                radius = if (sizing.isMobile) 10.dp else AgentUiBaseTokens.RadiusControl,
                pressed = pressed,
                hovered = hovered,
                enabled = enabled,
            )
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = sizing.buttonHorizontalPadding, vertical = sizing.buttonVerticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = textColor,
                fontSize = fontSizeSp,
                lineHeight = (fontSizeSp.value * 1.35f).sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
fun UiSurface(
    palette: ReactUiPalette = AgentUiTheme.palette,
    modifier: Modifier = Modifier,
    radius: Dp = palette.toolRadius,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .cossSurfaceElevation(palette = palette, radius = radius)
            .padding(12.dp),
    ) { content() }
}

@Composable
fun UiDivider(palette: ReactUiPalette = AgentUiTheme.palette, modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth().height(1.dp).background(palette.border))
}

@Composable
fun UiInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    palette: ReactUiPalette = AgentUiTheme.palette,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = palette.text, fontSize = 14.sp, lineHeight = 20.sp),
        cursorBrush = SolidColor(palette.accent),
        interactionSource = interaction,
        modifier = modifier,
        decorationBox = { inner -> UiInputDecoration(value, placeholder, palette, focused, inner) },
    )
}

/** Keeps IME composition and selection available to editors with keyboard shortcuts. */
@Composable
fun UiInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    palette: ReactUiPalette = AgentUiTheme.palette,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = palette.text, fontSize = 14.sp, lineHeight = 20.sp),
        cursorBrush = SolidColor(palette.accent),
        interactionSource = interaction,
        modifier = modifier,
        decorationBox = { inner -> UiInputDecoration(value.text, placeholder, palette, focused, inner) },
    )
}

@Composable
private fun UiInputDecoration(
    value: String,
    placeholder: String,
    palette: ReactUiPalette,
    focused: Boolean,
    inner: @Composable () -> Unit,
) {
    val sizing = AgentUiTheme.sizing
    Box(
        Modifier
            .fillMaxWidth()
            .cossInputElevation(palette = palette, radius = AgentUiBaseTokens.RadiusControl, focused = focused)
            .padding(horizontal = 12.dp, vertical = if (sizing.isMobile) 11.dp else 9.dp),
    ) {
        if (value.isEmpty()) UiText(placeholder, palette, muted = true, size = 14)
        inner()
    }
}

@Composable
fun UiDisclosure(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    palette: ReactUiPalette = AgentUiTheme.palette,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val sizing = AgentUiTheme.sizing
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val rowBg by animateColorAsState(
        targetValue = when {
            pressed -> palette.border.copy(alpha = 0.45f)
            hovered -> palette.subtle.copy(alpha = 0.7f)
            else -> Color.Transparent
        },
        animationSpec = tween(110),
        label = "disclosure row hover",
    )
    val chevronRotation by animateFloatAsState(if (expanded) 90f else 0f, tween(180), label = "disclosure chevron")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(rowBg, RoundedCornerShape(AgentUiBaseTokens.RadiusChip))
                .clickable(role = Role.Button, interactionSource = interaction, indication = null, onClick = onToggle)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(vertical = if (sizing.isMobile) 10.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(if (sizing.isMobile) 20.dp else 16.dp), contentAlignment = Alignment.Center) {
                UiText("›", palette, Modifier.rotate(chevronRotation), size = 15, weight = FontWeight.Medium)
            }
            Spacer(Modifier.width(6.dp))
            UiText(label, palette, size = 13, weight = FontWeight.Medium)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
        ) { content() }
    }
}
