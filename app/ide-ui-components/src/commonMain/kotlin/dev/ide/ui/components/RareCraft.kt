package dev.ide.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Tactile mechanical craft primitives adapted from Rare UI and restyled for the compact coss / Linear
 * zinc-and-hairline design system:
 * - Zero hardcoded magic hex values (all surfaces, sheets, borders, and accents derive from theme tokens).
 * - Compact density (32–34dp control heights, 1px hairline borders, no 48dp touch-target inflation).
 * - Pure Compose Multiplatform canvas & draw-phase animations.
 */

/**
 * Fanning 3D card-stack folder drawn on [Canvas] that tilts and opens using a tuned spring
 * (`spring(0.6f, 120f)`). Colors are derived from the active theme tokens rather than hardcoded hexes.
 */
@Composable
fun RareFolder(
    expanded: Boolean,
    modifier: Modifier = Modifier.size(112.dp, 104.dp),
    folderColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    sheetColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    sheetBorderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    sheetLineColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
    rimColor: Color = Ide.colors.glassEdgeTop,
) {
    val open by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 120f),
        label = "folderFan",
    )
    Canvas(modifier) {
        val scale = size.width / 380f
        withTransform({
            translate(size.width * 0.08f, size.height * 0.18f)
            scale(scale, scale, Offset.Zero)
        }) {
            drawRoundRect(folderColor, Offset(0f, 6f), Size(321f, 255f), CornerRadius(25f))
            drawRoundRect(
                rimColor,
                Offset(1f, 7f),
                Size(319f, 253f),
                CornerRadius(25f),
                style = Stroke(2f),
            )
            repeat(3) { index ->
                val i = index - 1
                val x = 48f + i * (12f + open * 28f)
                val y = 24f + index * 6f - open * (65f + index * 12f)
                rotate(i * (6f + open * 10f), Offset(160f, 220f)) {
                    drawRoundRect(
                        sheetColor,
                        Offset(x, y),
                        Size(226f, 205f),
                        CornerRadius(17f),
                    )
                    drawRoundRect(
                        sheetBorderColor,
                        Offset(x, y),
                        Size(226f, 205f),
                        CornerRadius(17f),
                        style = Stroke(2f),
                    )
                    repeat(4) { row ->
                        drawRoundRect(
                            if (row == 0) sheetLineColor else sheetBorderColor,
                            Offset(x + 20f, y + 28f + row * 24f),
                            Size(if (row == 0) 90f else 170f, 9f),
                            CornerRadius(4f),
                        )
                    }
                }
            }
            val flap = Path().apply {
                moveTo(0f, 45f)
                cubicTo(0f, 31.2f, 11.2f, 20f, 25f, 20f)
                lineTo(136f, 20f)
                cubicTo(143f, 20f, 149.7f, 22.9f, 154.4f, 28f)
                lineTo(178.1f, 53.5f)
                cubicTo(182.8f, 58.6f, 189.5f, 61.5f, 196.4f, 61.5f)
                lineTo(296f, 61.5f)
                cubicTo(309.8f, 61.5f, 321f, 72.7f, 321f, 86.5f)
                lineTo(321f, 236f)
                cubicTo(321f, 249.8f, 309.8f, 261f, 296f, 261f)
                lineTo(25f, 261f)
                cubicTo(11.2f, 261f, 0f, 249.8f, 0f, 236f)
                close()
            }
            withTransform({ scale(1f, 1f - open * 0.17f, Offset(160f, 261f)) }) {
                drawPath(
                    flap,
                    Brush.linearGradient(
                        listOf(
                            folderColor.copy(alpha = 0.96f),
                            folderColor,
                        ),
                        Offset(0f, 20f),
                        Offset(321f, 261f),
                    ),
                )
                drawPath(flap, rimColor, style = Stroke(2f))
                clipPath(flap) {
                    drawRoundRect(
                        Brush.horizontalGradient(
                            listOf(
                                rimColor,
                                Color.Transparent,
                                rimColor.copy(alpha = 0.06f),
                            ),
                        ),
                        Offset(7f, 25f),
                        Size(307f, 230f),
                        CornerRadius(20f),
                    )
                }
            }
        }
    }
}

/**
 * Odometer-style rolling number counter with vertical sliding transitions and tabular numerals (`tnum`).
 */
@Composable
fun RareCounter(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    Row(modifier.animateContentSize(), verticalAlignment = Alignment.CenterVertically) {
        val digits = value.coerceAtLeast(0).toString()
        digits.forEachIndexed { index, digit ->
            key(digits.length - index) {
                AnimatedContent(
                    targetState = digit,
                    transitionSpec = {
                        val sign = if (targetState > initialState) 1 else -1
                        (slideInVertically { it * sign } + fadeIn()).togetherWith(
                            slideOutVertically { -it * sign } + fadeOut(),
                        )
                    },
                    label = "rollingDigit",
                ) { d ->
                    Text(
                        text = d.toString(),
                        color = color,
                        style = style.copy(fontFeatureSettings = "tnum"),
                    )
                }
            }
        }
    }
}

/**
 * Compact inline two-stage delete button: the trashcan lid rotates open by `-35°` on a [Canvas] path,
 * expanding horizontally with a spring (`spring(0.8f, 300f)`) to reveal Cancel and Confirm controls,
 * and morphs into a confirmation checkmark.
 *
 * State is hoisted via [expanded] and [onExpandedChange]; see the convenience overload below for
 * self-managed state.
 */
@Composable
fun RareDeleteButton(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Delete",
    boxSize: Dp = 32.dp,
    onCancel: () -> Unit = {},
) {
    val c = MaterialTheme.colorScheme
    var confirmed by remember { mutableStateOf(false) }
    LaunchedEffect(confirmed) {
        if (confirmed) {
            delay(1200)
            confirmed = false
        }
    }
    val lid by animateFloatAsState(
        targetValue = if (expanded) -35f else 0f,
        animationSpec = tween(320),
        label = "binLid",
    )
    val shape = RoundedCornerShape(Ca.radius.control)
    Row(
        modifier
            .animateContentSize(spring(0.8f, 300f))
            .clip(shape)
            .background(c.surfaceContainerHigh)
            .border(1.dp, if (expanded) c.error.copy(alpha = 0.45f) else c.outlineVariant, shape)
            .onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyUp && expanded) {
                    onExpandedChange(false)
                    onCancel()
                    true
                } else {
                    false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(boxSize)
                .clickable(
                    enabled = !confirmed,
                    role = Role.Button,
                    onClick = { onExpandedChange(!expanded) },
                )
                .semantics {
                    contentDescription = label
                    stateDescription = when {
                        confirmed -> "Deleted"
                        expanded -> "Confirmation open"
                        else -> "Closed"
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val iconTint = if (expanded) c.error else c.onSurfaceVariant
            val accentTint = Ide.colors.success
            Canvas(Modifier.size(boxSize * 0.54f)) {
                val s = size.width / 24f
                val stroke = Stroke(1.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                if (confirmed) {
                    val check = Path().apply {
                        moveTo(5 * s, 12 * s)
                        lineTo(10 * s, 17 * s)
                        lineTo(19 * s, 7 * s)
                    }
                    drawPath(check, accentTint, style = stroke)
                    return@Canvas
                }
                val bin = Path().apply {
                    moveTo(5 * s, 8 * s)
                    lineTo(6 * s, 20 * s)
                    lineTo(18 * s, 20 * s)
                    lineTo(19 * s, 8 * s)
                }
                drawPath(bin, iconTint, style = stroke)
                rotate(lid, Offset(3 * s, 6 * s)) {
                    drawLine(
                        iconTint,
                        Offset(3 * s, 6 * s),
                        Offset(21 * s, 6 * s),
                        1.8f * s,
                        StrokeCap.Round,
                    )
                    drawLine(
                        iconTint,
                        Offset(9 * s, 3 * s),
                        Offset(15 * s, 3 * s),
                        1.8f * s,
                        StrokeCap.Round,
                    )
                }
                drawLine(iconTint, Offset(10 * s, 10 * s), Offset(10 * s, 16 * s), 1.5f * s)
                drawLine(iconTint, Offset(14 * s, 10 * s), Offset(14 * s, 16 * s), 1.5f * s)
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandHorizontally() + fadeIn(),
            exit = shrinkHorizontally() + fadeOut(),
        ) {
            Row(
                Modifier
                    .background(c.surfaceContainerHighest)
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(boxSize)
                        .clickable(role = Role.Button) {
                            onExpandedChange(false)
                            onCancel()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CaIcons.close, "Cancel deletion", Modifier.size(14.dp), tint = c.onSurfaceVariant)
                }
                Box(
                    Modifier
                        .size(boxSize)
                        .clickable(role = Role.Button) {
                            onExpandedChange(false)
                            confirmed = true
                            onConfirm()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CaIcons.check, "Confirm deletion", Modifier.size(14.dp), tint = c.error)
                }
            }
        }
    }
}

/** Convenience overload of [RareDeleteButton] that manages its own expansion state via `rememberSaveable`. */
@Composable
fun RareDeleteButton(
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Delete",
    boxSize: Dp = 32.dp,
    onCancel: () -> Unit = {},
) {
    var open by rememberSaveable { mutableStateOf(false) }
    RareDeleteButton(
        expanded = open,
        onExpandedChange = { open = it },
        onConfirm = onConfirm,
        modifier = modifier,
        label = label,
        boxSize = boxSize,
        onCancel = onCancel,
    )
}

/**
 * Draws a 13-point dotted ring on [Canvas] that fills with the theme's accent color while dynamically
 * measuring the [TextLayoutResult] to draw an animated strike-through across each line of text.
 * Compacted to a dense 34dp minimum row height with a crisp 1px border.
 */
@Composable
fun RareTaskRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    val fill by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(220),
        label = "taskFill",
    )
    val strike by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(320, delayMillis = if (checked) 200 else 0),
        label = "taskStrike",
    )
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val shape = RoundedCornerShape(Ca.radius.control)
    Row(
        modifier
            .clip(shape)
            .background(c.surfaceContainerLow)
            .border(1.dp, c.outlineVariant, shape)
            .toggleable(checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .heightIn(min = 34.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val mutedColor = c.onSurfaceVariant
        val accentColor = c.primary
        val checkColor = c.onPrimary
        Canvas(Modifier.size(18.dp)) {
            val r = size.minDimension / 2f
            repeat(13) { i ->
                val angle = i * 2 * PI / 13
                drawCircle(
                    mutedColor.copy(alpha = (1f - fill) * 0.45f),
                    1.dp.toPx(),
                    center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (r - 1.dp.toPx()),
                )
            }
            drawCircle(accentColor, r * fill)
            val path = Path().apply {
                moveTo(size.width * 0.31f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.65f)
                lineTo(size.width * 0.69f, size.height * 0.37f)
            }
            drawPath(
                path,
                checkColor.copy(alpha = fill),
                style = Stroke(1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (checked) mutedColor else c.onSurface,
            onTextLayout = { textLayout = it },
            modifier = Modifier
                .weight(1f, fill = false)
                .drawWithContent {
                    drawContent()
                    textLayout?.let { layout ->
                        repeat(layout.lineCount) { line ->
                            val left = layout.getLineLeft(line)
                            val right = layout.getLineRight(line)
                            val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
                            drawLine(
                                mutedColor,
                                Offset(left, y),
                                Offset(left + (right - left) * strike, y),
                                1.25.dp.toPx(),
                            )
                        }
                    }
                },
        )
    }
}

/**
 * Spring-loaded vertical bounce navigation indicator (`spring(0.65f, 300f)`) paired with compact 34dp
 * selectable rows.
 */
@Composable
fun RareBounceNavigation(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    val centers = remember { mutableStateMapOf<Int, Float>() }
    val density = LocalDensity.current
    val safeIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val target = centers[safeIndex] ?: (safeIndex * 34f + 14f)
    val y by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 300f),
        label = "navigationMarker",
    )
    Box(modifier.selectableGroup()) {
        Box(
            Modifier
                .offset { IntOffset(6.dp.roundToPx(), y.dp.roundToPx()) }
                .size(5.dp)
                .background(c.primary, CircleShape),
        )
        Column(Modifier.padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEachIndexed { index, title ->
                val selected = index == selectedIndex
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 34.dp)
                        .clip(RoundedCornerShape(Ca.radius.sm))
                        .background(if (selected) c.secondaryContainer.copy(alpha = 0.55f) else Color.Transparent)
                        .onGloballyPositioned { coordinates ->
                            centers[index] = with(density) {
                                (coordinates.positionInParent().y + coordinates.size.height / 2f).toDp().value - 2.5f
                            }
                        }
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = title,
                        color = if (selected) c.onSurface else c.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
