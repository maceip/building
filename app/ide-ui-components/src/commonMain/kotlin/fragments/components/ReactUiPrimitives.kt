package fragments.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Values transcribed from Agent Elements' agent-ui.css and Creance's tokens.css. */
typealias ReactUiPalette = fragments.agentui.AgentUiPalette

object ReactUiPalettes {
    val AgentElementsLight = ReactUiPalette(
        Color(0xFFFFFFFF), Color(0xFFF8F8F8), Color(0xFFF5F5F5),
        Color(0xFF1A1A1A), Color(0xFF737373), Color(0xFFE4E4E7),
        Color(0xFF3B82F6), Color.White, Color(0xFFDC2626), Color(0xFF15803D),
        16.dp, 10.dp,
    )
    val AgentElementsDark = ReactUiPalette(
        Color(0xFF0A0A0A), Color(0xFF141414), Color(0xFF1A1A1A),
        Color(0xFFFAFAFA), Color(0xFF8C8C8C), Color(0xFF2A2A2A),
        Color(0xFF60A5FA), Color(0xFF0A0A0A), Color(0xFFF87171), Color(0xFF4ADE80),
        16.dp, 10.dp,
    )
    val CreanceLight = ReactUiPalette(
        Color(0xFFE9EBEF), Color.White, Color(0xFFF5F5F7),
        Color(0xFF1D1D1F), Color(0xFF5F6368), Color(0x1A000000),
        Color(0xFF0071E3), Color.White, Color(0xFFD0342C), Color(0xFF198754),
        18.dp, 12.dp,
    )
    val CreanceDark = ReactUiPalette(
        Color(0xFF101216), Color(0xFF1C1E24), Color(0xFF2C2F38),
        Color(0xFFF5F5F7), Color(0xFFA3A8B0), Color(0xFF3A3E48),
        Color(0xFF4DA3FF), Color(0xFF101216), Color(0xFFFF7B72), Color(0xFF52C889),
        18.dp, 12.dp,
    )
}

@Composable
fun UiText(
    value: String,
    palette: ReactUiPalette,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    size: Int = 14,
    weight: FontWeight = FontWeight.Normal,
    mono: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        value,
        modifier,
        style = TextStyle(
            color = if (muted) palette.muted else palette.text,
            fontSize = size.sp,
            lineHeight = (size * 1.45f).sp,
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
    palette: ReactUiPalette,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        primary && enabled -> palette.accent
        hovered && enabled -> palette.subtle
        !enabled -> palette.subtle
        else -> palette.surface
    }
    Box(
        modifier
            .background(background, RoundedCornerShape(8.dp))
            .border(1.dp, if (primary && enabled) background else palette.border, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = when {
                    !enabled -> palette.muted
                    primary -> palette.accentText
                    else -> palette.text
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
fun UiSurface(
    palette: ReactUiPalette,
    modifier: Modifier = Modifier,
    radius: Dp = palette.toolRadius,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .background(palette.surface, RoundedCornerShape(radius))
            .border(1.dp, palette.border, RoundedCornerShape(radius))
            .padding(12.dp),
    ) { content() }
}

@Composable
fun UiDivider(palette: ReactUiPalette, modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth().height(1.dp).background(palette.border))
}

@Composable
fun UiInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    palette: ReactUiPalette,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = palette.text, fontSize = 14.sp),
        cursorBrush = SolidColor(palette.accent),
        modifier = modifier,
        decorationBox = { inner -> UiInputDecoration(value, placeholder, palette, inner) },
    )
}

/** Keeps IME composition and selection available to editors with keyboard shortcuts. */
@Composable
fun UiInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    palette: ReactUiPalette,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = palette.text, fontSize = 14.sp),
        cursorBrush = SolidColor(palette.accent),
        modifier = modifier,
        decorationBox = { inner -> UiInputDecoration(value.text, placeholder, palette, inner) },
    )
}

@Composable
private fun UiInputDecoration(
    value: String,
    placeholder: String,
    palette: ReactUiPalette,
    inner: @Composable () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth()
            .background(palette.background, RoundedCornerShape(8.dp))
            .border(1.dp, palette.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        if (value.isEmpty()) UiText(placeholder, palette, muted = true)
        inner()
    }
}

@Composable
fun UiDisclosure(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    palette: ReactUiPalette,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val chevronRotation by animateFloatAsState(if (expanded) 90f else 0f, tween(180), label = "disclosure chevron")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                UiText("›", palette, Modifier.rotate(chevronRotation), size = 16)
            }
            Spacer(Modifier.width(6.dp))
            UiText(label, palette, weight = FontWeight.Medium)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
        ) { content() }
    }
}
