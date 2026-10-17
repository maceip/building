package dev.ide.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.platform.rememberIdeHaptics
import dev.ide.ui.theme.Ide
import dev.ide.ui.theme.Motion

/** The three liquid-glass materials (translucent fill plus saturation/blur; here a token-based fill). */
enum class GlassMaterial { Thin, Regular, Thick }

@Composable
fun glassFill(material: GlassMaterial): Color = when (material) {
    GlassMaterial.Thin -> Ide.colors.glassThin
    GlassMaterial.Regular -> Ide.colors.glassReg
    GlassMaterial.Thick -> Ide.colors.glassThick
}

/**
 * A chrome surface in liquid glass: translucent fill + directional 45° specular dome sheen + 1px top specular
 * rim and perimeter hairline. Gives floating panels, banners, and popovers physical slab depth without
 * neon glow or heavy shaders.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    material: GlassMaterial = GlassMaterial.Regular,
    shape: Shape = RoundedCornerShape(0.dp),
    content: @Composable () -> Unit,
) {
    val fill = glassFill(material)
    val edgeTop = Ide.colors.glassEdgeTop
    val edge = Ide.colors.glassEdge
    val domeAlpha = when (material) {
        GlassMaterial.Thin -> 0.14f
        GlassMaterial.Regular -> 0.20f
        GlassMaterial.Thick -> 0.26f
    }
    Box(
        modifier
            .background(fill, shape)
            .drawWithContent {
                // Subtle 45° directional dome highlight across the upper face
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            edgeTop.copy(alpha = domeAlpha),
                            Color.Transparent,
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width * 0.65f, size.height * 0.85f),
                    ),
                )
                drawContent()
                val strokePx = 1.dp.toPx()
                when (val outline = shape.createOutline(size, layoutDirection, this)) {
                    is Outline.Rectangle -> {
                        drawLine(edgeTop, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = strokePx)
                        drawLine(edge, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = strokePx)
                    }
                    is Outline.Rounded -> {
                        val path = Path().apply { addRoundRect(outline.roundRect) }
                        drawPath(
                            path = path,
                            brush = Brush.verticalGradient(listOf(edgeTop, edge)),
                            style = Stroke(width = strokePx),
                        )
                    }
                    is Outline.Generic -> {
                        drawPath(
                            path = outline.path,
                            brush = Brush.verticalGradient(listOf(edgeTop, edge)),
                            style = Stroke(width = strokePx),
                        )
                    }
                }
            },
    ) { content() }
}

/**
 * An opaque chrome surface for tool windows (the side and bottom panes, the phone's drawers): the theme's
 * container colour with the same 1px top edge as [GlassSurface]. Tool windows sit over the editor and its
 * code, which a translucent fill lets show through.
 */
@Composable
fun ToolWindowSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val edgeTop = Ide.colors.glassEdgeTop
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .drawBehind { drawLine(edgeTop, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f) },
    ) { content() }
}

/**
 * Scale-to-0.96 press feedback driven by an interaction source. Uses a bouncy spring (the expressive
 * spring feel) so releasing a press settles back past 1.0 for a lively button response.
 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PRESS_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pressScale",
    )
    val lift by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.FAST),
        label = "pressLift",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        translationY = lift * 1.2.dp.toPx()
    }
}

/**
 * COSS UI (`coss.com`) elevated keycap/control treatment:
 * - Layered physical drop shadow that compresses on press ([restElevation] → `1.dp`)
 * - Subtle top-to-bottom luminance gradient across the face
 * - Crisp `1.dp` top specular highlight rim + bottom depth hairline
 */
@Composable
fun Modifier.cossElevation(
    interaction: MutableInteractionSource,
    shape: Shape,
    restElevation: Dp = 4.dp,
    topHighlight: Color = Color.White.copy(alpha = 0.24f),
    bottomRim: Color = Color.Black.copy(alpha = 0.26f),
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val elevFraction by animateFloatAsState(
        targetValue = if (pressed) 0.25f else 1f,
        animationSpec = tween(durationMillis = Motion.FAST),
        label = "cossElev",
    )
    val currentElevation = restElevation * elevFraction
    return this
        .shadow(
            elevation = currentElevation,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = 0.45f),
            spotColor = Color.Black.copy(alpha = 0.55f),
        )
        .drawWithContent {
            // Subtle top-down COSS surface sheen
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        topHighlight.copy(alpha = topHighlight.alpha * 0.45f),
                        Color.Transparent,
                        bottomRim.copy(alpha = bottomRim.alpha * 0.35f),
                    ),
                ),
            )
            drawContent()
            val strokePx = 1.dp.toPx()
            when (val outline = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Rounded -> {
                    val path = Path().apply { addRoundRect(outline.roundRect) }
                    drawPath(
                        path = path,
                        brush = Brush.verticalGradient(listOf(topHighlight, bottomRim)),
                        style = Stroke(width = strokePx),
                    )
                }
                is Outline.Rectangle -> {
                    drawLine(topHighlight, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = strokePx)
                    drawLine(bottomRim, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = strokePx)
                }
                is Outline.Generic -> {
                    drawPath(
                        path = outline.path,
                        brush = Brush.verticalGradient(listOf(topHighlight, bottomRim)),
                        style = Stroke(width = strokePx),
                    )
                }
            }
        }
}

/**
 * One-shot slide-up entrance (transform-only: `translateY(9px) → 0`). Content is fully opaque throughout so
 * it stays visible if motion is disabled. Pass [delayMillis] to stagger a list.
 */
@Composable
fun Modifier.entranceSlideUp(delayMillis: Int = 0): Modifier {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (delayMillis > 0) delay(delayMillis.toLong())
        appeared = true
    }
    val progress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.BASE, easing = Motion.quiet),
        label = "entranceSlideUp",
    )
    return this.graphicsLayer { translationY = (1f - progress) * 9.dp.toPx() }
}

/**
 * One-shot pop entrance (`scale(0.96) translateY(5px) → none`, expressive spring). Used for popovers like
 * the completion list.
 */
@Composable
fun Modifier.entrancePop(): Modifier {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val progress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "entrancePop",
    )
    return this.graphicsLayer {
        val s = 0.96f + 0.04f * progress
        scaleX = s
        scaleY = s
        translationY = (1f - progress) * 5.dp.toPx()
    }
}

/**
 * Square icon button (a compact toolbar control, denser than M3's 48dp default so it fits the top bar).
 * Reads Material roles: an [active] control gets a `secondaryContainer` fill + `onSecondaryContainer` tint;
 * inactive is transparent with an `onSurfaceVariant` glyph.
 */
@Composable
fun IconButtonCa(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    iconSize: Int = 20,
    boxSize: Int = 38,
    tint: Color? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberIdeHaptics()
    val resolvedTint = tint ?: if (active) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    val shape = MaterialTheme.shapes.small
    val clickMod = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = { haptics.tap(); onClick() },
            onLongClick = { haptics.heavyTap(); onLongClick() },
        )
    } else {
        Modifier.clickable(interaction, indication = null, onClick = { haptics.tap(); onClick() })
    }
    val surfaceMod = if (active) {
        Modifier
            .cossElevation(
                interaction = interaction,
                shape = shape,
                restElevation = 3.dp,
                topHighlight = Ide.colors.glassEdgeTop,
                bottomRim = Color.Black.copy(alpha = 0.22f),
            )
            .background(scheme.secondaryContainer, shape)
    } else {
        Modifier.background(Color.Transparent, shape)
    }
    Box(
        modifier
            .size(boxSize.dp)
            .pressScale(interaction)
            .then(surfaceMod)
            .then(clickMod),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize.dp), tint = resolvedTint)
    }
}

/**
 * Filled primary button (e.g. Run), a native M3 [Button] on the expressive shape scale, 38dp tall with an
 * optional leading icon and COSS elevated keycap depth. When [iconOnly] (and [icon] is set) it collapses to a
 * fixed-width square [FilledIconButton] — the label is dropped but kept as the accessibility description.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconOnly: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberIdeHaptics()
    val shape = MaterialTheme.shapes.small
    val collapsed = iconOnly && icon != null
    if (collapsed) {
        FilledIconButton(
            onClick = { haptics.heavyTap(); onClick() },
            interactionSource = interaction,
            modifier = modifier
                .size(width = 44.dp, height = 38.dp)
                .pressScale(interaction)
                .cossElevation(interaction, shape, restElevation = 5.dp),
            shape = shape,
        ) {
            Icon(icon!!, text, Modifier.size(18.dp))
        }
    } else {
        Button(
            onClick = { haptics.heavyTap(); onClick() },
            interactionSource = interaction,
            modifier = modifier
                .height(38.dp)
                .pressScale(interaction)
                .cossElevation(interaction, shape, restElevation = 5.dp),
            shape = shape,
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A soft pill/chip with a tonal fill and subtle COSS top specular rim (used for status, hints, meta). */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val shape = RoundedCornerShape(999.dp)
    val edgeTop = Ide.colors.glassEdgeTop
    Box(
        modifier
            .defaultMinSize(minHeight = 22.dp)
            .shadow(1.5.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.3f))
            .background(fill, shape)
            .drawWithContent {
                drawContent()
                val path = Path().apply {
                    val o = shape.createOutline(size, layoutDirection, this@drawWithContent)
                    if (o is Outline.Rounded) addRoundRect(o.roundRect)
                }
                drawPath(
                    path = path,
                    brush = Brush.verticalGradient(listOf(edgeTop, Color.Black.copy(alpha = 0.16f))),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor, style = MaterialTheme.typography.labelSmall)
    }
}
