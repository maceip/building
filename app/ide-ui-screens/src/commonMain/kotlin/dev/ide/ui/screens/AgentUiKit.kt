package dev.ide.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.AgentUi

/**
 * Agent-first chrome primitives shared by Home, Session, Settings, and split docks.
 * Paints with [AgentUi.chrome] so iOS and Android stay on the redesign identity.
 */

@Composable
fun AgentSurface(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(AgentUi.radius.card),
    fill: Color = AgentUi.chrome.surface,
    stroke: Color = AgentUi.chrome.hairline,
    contentPadding: PaddingValues = PaddingValues(AgentUi.space.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, stroke, shape)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun AgentStatusDot(
    active: Boolean = true,
    modifier: Modifier = Modifier,
    size: Dp = 7.dp,
    color: Color = if (active) AgentUi.chrome.accent else AgentUi.chrome.textFaint,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(3.dp))
            .background(color),
    )
}

@Composable
fun AgentSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            letterSpacing = 0.06.sp,
            fontWeight = FontWeight.Medium,
        ),
        color = AgentUi.chrome.textFaint,
        modifier = modifier,
    )
}

@Composable
fun AgentChip(
    label: String,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) AgentUi.chrome.surfaceAlt else Color.Transparent
    val fg = if (selected) AgentUi.chrome.text else AgentUi.chrome.textMuted
    val stroke = if (selected) AgentUi.chrome.hairlineStrong else AgentUi.chrome.hairline
    val clickModifier = if (onClick != null) {
        Modifier
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(AgentUi.radius.chip))
            .background(bg)
            .border(1.dp, stroke, RoundedCornerShape(AgentUi.radius.chip))
            .then(clickModifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
    }
}

@Composable
fun AgentPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val bg = if (enabled) AgentUi.chrome.accent else AgentUi.chrome.surfaceAlt
    val fg = if (enabled) AgentUi.chrome.accentInk else AgentUi.chrome.textFaint
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
fun AgentGhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(AgentUi.chrome.hairline.copy(alpha = 0.35f))
            .border(1.dp, AgentUi.chrome.hairlineStrong, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = AgentUi.chrome.text)
    }
}

@Composable
fun AgentIconCircle(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    size: Dp = 44.dp,
) {
    val bg = if (accent) AgentUi.chrome.accent else Color.Transparent
    val fg = if (accent) AgentUi.chrome.accentInk else AgentUi.chrome.textMuted
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = fg, modifier = Modifier.size(20.dp))
    }
}

/**
 * Pill composer from the redesign: attach, free-text field, optional voice, lime send.
 */
@Composable
fun AgentComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Ask for a change…",
    onAttach: (() -> Unit)? = null,
    onVoice: (() -> Unit)? = null,
    attachIcon: ImageVector? = null,
    voiceIcon: ImageVector? = null,
    sendIcon: ImageVector? = null,
    trailingMeta: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val chrome = AgentUi.chrome
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(chrome.surface)
                .border(1.dp, chrome.hairlineStrong, RoundedCornerShape(22.dp))
                .drawBehind {
                    // inset top sheen
                    drawLine(
                        color = chrome.sheen,
                        start = Offset(12f, 1f),
                        end = Offset(size.width - 12f, 1f),
                        strokeWidth = 1f,
                    )
                }
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onAttach != null && attachIcon != null) {
                AgentIconCircle(attachIcon, "Attach file or screenshot", onAttach, Modifier.size(40.dp))
            }
            Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, color = chrome.textMuted, fontSize = 15.sp)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = TextStyle(
                        color = chrome.text,
                        fontSize = 15.sp,
                    ),
                    cursorBrush = SolidColor(chrome.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (onVoice != null && voiceIcon != null) {
                AgentIconCircle(voiceIcon, "Voice input", onVoice, Modifier.size(40.dp))
            }
            if (sendIcon != null) {
                AgentIconCircle(sendIcon, "Send", onSend, Modifier.size(40.dp), accent = true)
            } else {
                AgentIconCircle(dev.ide.ui.icons.CaIcons.arrowRight, "Send", onSend, Modifier.size(40.dp), accent = true)
            }
        }
        if (trailingMeta != null) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = trailingMeta,
            )
        }
    }
}

@Composable
fun AgentUserBubble(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth(0.84f)
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp))
            .background(AgentUi.chrome.surfaceAlt)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text, fontSize = 15.sp, lineHeight = 21.sp, color = AgentUi.chrome.text)
    }
}

@Composable
fun AgentReplyBody(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth(),
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = AgentUi.chrome.text,
    )
}

/**
 * Multiplatform animated text shimmer brush that sweeps a specular highlight across [baseColor].
 */
@Composable
fun rememberTextShimmerBrush(
    baseColor: Color,
    highlightColor: Color = Color.White,
): Brush {
    val transition = rememberInfiniteTransition(label = "textShimmer")
    val phase by transition.animateFloat(
        initialValue = -240f,
        targetValue = 720f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = LinearEasing),
        ),
        label = "shimmerOffset",
    )
    return Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(phase, 0f),
        end = Offset(phase + 220f, 0f),
    )
}

@Composable
fun AgentSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AgentUi.chrome.surface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) AgentUi.chrome.surfaceRaised else Color.Transparent)
                    .semantics { this.selected = selected }
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) AgentUi.chrome.text else AgentUi.chrome.textMuted,
                )
            }
        }
    }
}

@Composable
fun AgentRadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val shape = RoundedCornerShape(10.dp)
        Box(
            Modifier
                .size(20.dp)
                .clip(shape)
                .then(
                    if (selected) Modifier.background(AgentUi.chrome.accent)
                    else Modifier.border(1.5.dp, AgentUi.chrome.textFaint, shape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(8.dp).clip(shape).background(AgentUi.chrome.accentInk))
            }
        }
        Text(label, fontSize = 15.sp, color = AgentUi.chrome.text)
    }
}

/** Top bar used by session / settings / split docks. */
@Composable
fun AgentTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backIcon: ImageVector? = CaIcons.arrowLeft,
    actions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            val icon = backIcon
            if (icon != null) {
                AgentIconCircle(icon, "Back", onBack, Modifier.size(44.dp))
            } else {
                Box(
                    Modifier
                        .size(44.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onBack,
                        ),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AgentUi.chrome.text)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = AgentUi.chrome.textFaint)
            }
        }
        actions()
    }
}
