@file:OptIn(io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi::class,
    io.github.alexzhirkevich.cupertino.adaptive.ExperimentalAdaptiveApi::class)
package com.pureswift.swiftui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import io.github.alexzhirkevich.LocalContentColor
import io.github.alexzhirkevich.LocalTextStyle
import io.github.alexzhirkevich.cupertino.*
import io.github.alexzhirkevich.cupertino.adaptive.*
import io.github.alexzhirkevich.cupertino.theme.*
import io.github.alexzhirkevich.cupertino.theme.Shapes as CupertinoShapes
import kotlinx.serialization.json.*

internal fun cupertinoPadding(node: ViewNode, key: String, default: PaddingValues = PaddingValues(0.dp)): PaddingValues {
    val primitive = (node.props[key] as? JsonPrimitive)?.doubleOrNull
    if (primitive != null) return PaddingValues(primitive.toFloat().dp)
    val p = cupertinoObject(node, key) ?: return default
    val all = p.double("all")?.toFloat() ?: 0f
    val horizontal = p.double("horizontal")?.toFloat() ?: all
    val vertical = p.double("vertical")?.toFloat() ?: all
    return PaddingValues(start = (p.double("start")?.toFloat() ?: horizontal).dp,
        top = (p.double("top")?.toFloat() ?: vertical).dp,
        end = (p.double("end")?.toFloat() ?: horizontal).dp,
        bottom = (p.double("bottom")?.toFloat() ?: vertical).dp)
}

internal fun cupertinoShape(node: ViewNode, key: String = "shape", default: Shape = RectangleShape): Shape {
    val p = cupertinoObject(node, key)
    return when (node.string(key)?.lowercase() ?: p?.string("kind")?.lowercase()) {
        "circle", "capsule" -> CircleShape
        "rectangle" -> RectangleShape
        "rounded", "roundedrectangle" -> RoundedCornerShape((p?.double("radius") ?: node.double("cornerRadius") ?: 8.0).toFloat().dp)
        else -> (node.double(key) ?: p?.double("radius"))?.let { RoundedCornerShape(it.toFloat().dp) } ?: default
    }
}

@Composable
internal fun cupertinoBorder(node: ViewNode): BorderStroke? = cupertinoColor(node, "borderColor")?.let {
    BorderStroke((node.double("borderWidth") ?: 1.0).toFloat().dp, it)
}

@Composable
internal fun cupertinoInheritedTextStyle(default: TextStyle): TextStyle = default.copy(
    fontSize = LocalInheritedFontSize.current.takeIf { it.isSpecified } ?: default.fontSize,
    fontWeight = LocalInheritedFontWeight.current ?: default.fontWeight,
    fontFamily = LocalInheritedFontFamily.current ?: default.fontFamily,
    lineHeight = LocalInheritedLineHeight.current.takeIf { it.isSpecified } ?: default.lineHeight,
    letterSpacing = LocalInheritedTracking.current.takeIf { it.isSpecified } ?: default.letterSpacing,
    color = LocalInheritedColor.current.takeIf { it.isSpecified } ?: default.color)

@Composable
internal fun cupertinoTextStyle(node: ViewNode, key: String = "textStyle", default: TextStyle = LocalTextStyle.current): TextStyle {
    val objectValue = cupertinoObject(node, key)
    val source = if (objectValue != null) node.copy(props = objectValue) else node
    val typography = CupertinoTheme.typography
    val base = cupertinoInheritedTextStyle(when (node.string(key)) {
        "largeTitle" -> typography.largeTitle
        "title1" -> typography.title1
        "title2" -> typography.title2
        "title3" -> typography.title3
        "headline" -> typography.headline
        "body" -> typography.body
        "callout" -> typography.callout
        "subhead" -> typography.subhead
        "footnote" -> typography.footnote
        "caption1" -> typography.caption1
        "caption2" -> typography.caption2
        else -> default
    })
    return base.copy(color = cupertinoColor(source, "color") ?: base.color,
        fontSize = source.double("fontSize")?.toFloat()?.sp ?: base.fontSize,
        fontWeight = source.double("fontWeight")?.toInt()?.coerceIn(1, 1000)?.let(::FontWeight) ?: when (source.string("fontWeight")?.lowercase()) {
            "bold" -> FontWeight.Bold; "semibold" -> FontWeight.SemiBold; "medium" -> FontWeight.Medium
            "light" -> FontWeight.Light; "normal", "regular" -> FontWeight.Normal; else -> base.fontWeight
        },
        fontStyle = when (source.string("fontStyle")?.lowercase()) { "italic" -> FontStyle.Italic; "normal" -> FontStyle.Normal; else -> base.fontStyle },
        fontFamily = when (source.string("fontFamily")?.lowercase()) { "monospace" -> FontFamily.Monospace; "serif" -> FontFamily.Serif; "sansserif" -> FontFamily.SansSerif; else -> source.string("fontFamily")?.let { rememberNamedFontFamily(it) } ?: base.fontFamily },
        letterSpacing = source.double("letterSpacing")?.toFloat()?.sp ?: base.letterSpacing,
        lineHeight = source.double("lineHeight")?.toFloat()?.sp ?: base.lineHeight,
        textAlign = when (source.string("textAlign")?.lowercase()) { "left" -> TextAlign.Left; "right" -> TextAlign.Right; "center" -> TextAlign.Center; "start" -> TextAlign.Start; "end" -> TextAlign.End; "justify" -> TextAlign.Justify; else -> base.textAlign },
        textDecoration = when (source.string("textDecoration")?.lowercase()) { "underline" -> TextDecoration.Underline; "linethrough", "strikethrough" -> TextDecoration.LineThrough; "none" -> TextDecoration.None; else -> base.textDecoration })
}

internal fun cupertinoRange(node: ViewNode, key: String, default: ClosedFloatingPointRange<Float> = 0f..1f): ClosedFloatingPointRange<Float> {
    val array = cupertinoArray(node, key)
    val obj = cupertinoObject(node, key)
    val start = (array?.getOrNull(0) as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: obj?.double("start")?.toFloat() ?: default.start
    val end = (array?.getOrNull(1) as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: obj?.double("end")?.toFloat() ?: default.endInclusive
    return if (start.isFinite() && end.isFinite() && start <= end) start..end else default
}

internal val LocalCupertinoRowScope = staticCompositionLocalOf<RowScope?> { null }
private data class SliderSlotContext(val positions: SliderPositions, val interaction: MutableInteractionSource,
    val colors: CupertinoSliderColors, val enabled: Boolean)
private val LocalCupertinoSlider = staticCompositionLocalOf<SliderSlotContext?> { null }

@Composable
private fun buttonColors(node: ViewNode, defaultStyle: String = "filled"): CupertinoButtonColors {
    val requested = node.string("colors") ?: node.string("style")
    val style = requested?.takeIf { it in setOf("filled", "borderedProminent", "gray", "borderedGray", "plain", "borderless", "tinted", "bordered") } ?: defaultStyle
    val accent = CupertinoTheme.colorScheme.accent
    val content = cupertinoColor(node, "contentColor") ?: if (style in listOf("filled", "borderedProminent")) Color.White else accent
    val container = cupertinoColor(node, "containerColor") ?: when (style) {
        "gray", "borderedGray" -> CupertinoTheme.colorScheme.quaternarySystemFill
        "plain", "borderless" -> Color.Transparent
        "tinted", "bordered" -> content.copy(alpha = .15f)
        else -> accent
    }
    val disabledContent = cupertinoColor(node, "disabledContentColor") ?: CupertinoTheme.colorScheme.tertiaryLabel
    val disabledContainer = cupertinoColor(node, "disabledContainerColor") ?: if (style in listOf("plain", "borderless")) Color.Transparent else CupertinoTheme.colorScheme.quaternarySystemFill
    val indication = cupertinoColor(node, "indicationColor") ?: content.copy(alpha = .2f)
    return when (style) {
        "plain", "borderless" -> CupertinoButtonDefaults.plainButtonColors(content, container, disabledContent, disabledContainer, cupertinoColor(node, "indicationColor") ?: Color.Transparent)
        "gray", "borderedGray" -> CupertinoButtonDefaults.grayButtonColors(content, container, disabledContent, disabledContainer, indication)
        "tinted", "bordered" -> CupertinoButtonDefaults.tintedButtonColors(content, container, disabledContent, disabledContainer, indication)
        else -> CupertinoButtonDefaults.filledButtonColors(content, container, disabledContent, disabledContainer, indication)
    }
}

@Composable
private fun sliderColors(node: ViewNode, steps: Int): CupertinoSliderColors {
    val active = cupertinoColor(node, "activeTrackColor") ?: if (steps > 0) CupertinoColors.systemGray else CupertinoTheme.colorScheme.accent
    val inactive = cupertinoColor(node, "inactiveTrackColor") ?: if (steps > 0) active else CupertinoTheme.colorScheme.separator
    val tick = cupertinoColor(node, "activeTickColor") ?: if (steps > 0) active else CupertinoTheme.colorScheme.separator
    val thumb = cupertinoColor(node, "thumbColor") ?: Color.White
    return CupertinoSliderDefaults.colors(thumbColor = thumb, activeTrackColor = active, inactiveTrackColor = inactive,
        activeTickColor = tick, inactiveTickColor = cupertinoColor(node, "inactiveTickColor") ?: tick,
        disabledThumbColor = cupertinoColor(node, "disabledThumbColor") ?: thumb,
        disabledActiveTrackColor = cupertinoColor(node, "disabledActiveTrackColor") ?: if (steps > 0) active else active.copy(alpha = .5f),
        disabledInactiveTrackColor = cupertinoColor(node, "disabledInactiveTrackColor") ?: if (steps > 0) inactive else inactive.copy(alpha = .5f),
        disabledActiveTickColor = cupertinoColor(node, "disabledActiveTickColor") ?: tick,
        disabledInactiveTickColor = cupertinoColor(node, "disabledInactiveTickColor") ?: tick)
}

@Composable
internal fun RenderCupertinoBasic(node: ViewNode): Boolean {
    val name = node.string("name") ?: return false
    val props = Props(node.props)
    when (name) {
        "CupertinoTheme", "AdaptiveTheme" -> {
            val dark = node.bool("isDark") ?: node.bool("dark") ?: LocalAppearanceIsDark.current
            val base = if (dark) darkColorScheme() else lightColorScheme()
            val colorNode = cupertinoObject(node, "colorScheme")?.let { node.copy(props = it) } ?: node
            fun schemeColor(key: String) = resolveAppearanceColor(colorNode.props[key], dark)
            val scheme = base.copy(accent = schemeColor("accent") ?: base.accent,
                label = schemeColor("label") ?: base.label,
                secondaryLabel = schemeColor("secondaryLabel") ?: base.secondaryLabel,
                tertiaryLabel = schemeColor("tertiaryLabel") ?: base.tertiaryLabel,
                quaternaryLabel = schemeColor("quaternaryLabel") ?: base.quaternaryLabel,
                systemFill = schemeColor("systemFill") ?: base.systemFill,
                secondarySystemFill = schemeColor("secondarySystemFill") ?: base.secondarySystemFill,
                tertiarySystemFill = schemeColor("tertiarySystemFill") ?: base.tertiarySystemFill,
                quaternarySystemFill = schemeColor("quaternarySystemFill") ?: base.quaternarySystemFill,
                placeholderText = schemeColor("placeholderText") ?: base.placeholderText,
                separator = schemeColor("separator") ?: base.separator,
                opaqueSeparator = schemeColor("opaqueSeparator") ?: base.opaqueSeparator,
                link = schemeColor("link") ?: base.link,
                systemGroupedBackground = schemeColor("systemGroupedBackground") ?: base.systemGroupedBackground,
                secondarySystemGroupedBackground = schemeColor("secondarySystemGroupedBackground") ?: base.secondarySystemGroupedBackground,
                tertiarySystemGroupedBackground = schemeColor("tertiarySystemGroupedBackground") ?: base.tertiarySystemGroupedBackground,
                systemBackground = schemeColor("systemBackground") ?: base.systemBackground,
                secondarySystemBackground = schemeColor("secondarySystemBackground") ?: base.secondarySystemBackground,
                tertiarySystemBackground = schemeColor("tertiarySystemBackground") ?: base.tertiarySystemBackground)
            val defaults = CupertinoShapes()
            val shapes = CupertinoShapes(extraSmall = props.float("extraSmall")?.let { RoundedCornerShape(it.dp) } ?: defaults.extraSmall,
                small = props.float("small")?.let { RoundedCornerShape(it.dp) } ?: defaults.small,
                medium = props.float("medium")?.let { RoundedCornerShape(it.dp) } ?: defaults.medium,
                large = props.float("large")?.let { RoundedCornerShape(it.dp) } ?: defaults.large,
                extraLarge = props.float("extraLarge")?.let { RoundedCornerShape(it.dp) } ?: defaults.extraLarge)
            val typography = Typography()
            val family = when (node.string("fontFamily")?.lowercase()) { "monospace" -> FontFamily.Monospace; "serif" -> FontFamily.Serif; "sansserif" -> FontFamily.SansSerif; else -> node.string("fontFamily")?.let { rememberNamedFontFamily(it) } }
            val themedType = if (family == null) typography else typography.copy(
                largeTitle = typography.largeTitle.copy(fontFamily = family), title1 = typography.title1.copy(fontFamily = family),
                title2 = typography.title2.copy(fontFamily = family), title3 = typography.title3.copy(fontFamily = family),
                headline = typography.headline.copy(fontFamily = family), body = typography.body.copy(fontFamily = family),
                callout = typography.callout.copy(fontFamily = family), subhead = typography.subhead.copy(fontFamily = family),
                footnote = typography.footnote.copy(fontFamily = family), caption1 = typography.caption1.copy(fontFamily = family), caption2 = typography.caption2.copy(fontFamily = family))
            val themedContent: @Composable () -> Unit = { CompositionLocalProvider(LocalAppearanceIsDark provides dark) { cupertinoSlot(node, "content") } }
            if (name == "CupertinoTheme") CupertinoTheme(colorScheme = scheme, shapes = shapes, typography = themedType, content = themedContent)
            else AdaptiveTheme(target = if (node.string("target")?.lowercase() == "cupertino") Theme.Cupertino else Theme.Material3,
                cupertino = CupertinoThemeSpec(colorScheme = scheme, shapes = shapes, typography = themedType),
                material = MaterialThemeSpec(colorScheme = if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()),
                content = themedContent)
        }
        "AdaptiveWidget" -> Box(node.composeModifiers()) {
            AdaptiveWidget(material = { cupertinoSlot(node, "material") }, cupertino = { cupertinoSlot(node, "cupertino") })
        }
        "ProvideTextStyle" -> ProvideTextStyle(cupertinoTextStyle(node, "value")) { cupertinoSlot(node, "content") }
        "CupertinoText" -> {
            val annotated = cupertinoObject(node, "annotatedJson") ?: cupertinoObject(node, "text") ?: cupertinoObject(node, "annotatedText")
            val raw = annotated?.string("text") ?: node.string("text") ?: ""
            val builder = AnnotatedString.Builder(raw)
            for (value in (annotated?.get("spans") as? JsonArray).orEmpty()) {
                val span = value as? JsonObject ?: continue
                val start = (span.double("start")?.toInt() ?: 0).coerceIn(0, raw.length)
                val end = (span.double("end")?.toInt() ?: raw.length).coerceIn(start, raw.length)
                val styled = node.copy(props = (span["style"] as? JsonObject) ?: span)
                builder.addStyle(cupertinoTextStyle(styled, default = TextStyle.Default).toSpanStyle(), start, end)
            }
            for (value in (annotated?.get("annotations") as? JsonArray).orEmpty()) {
                val annotation = value as? JsonObject ?: continue
                val start = (annotation.double("start")?.toInt() ?: 0).coerceIn(0, raw.length)
                val end = (annotation.double("end")?.toInt() ?: raw.length).coerceIn(start, raw.length)
                val tag = annotation.string("tag") ?: continue
                val text = annotation.string("value") ?: continue
                builder.addStringAnnotation(tag, text, start, end)
            }
            val onLayout = props.stringAction("onTextLayout")
            CupertinoText(text = builder.toAnnotatedString(), modifier = node.composeModifiers(),
                style = cupertinoTextStyle(node, "style"), softWrap = node.bool("softWrap") ?: true,
                minLines = (props.int("minLines") ?: 1).coerceAtLeast(1),
                maxLines = (props.int("maxLines") ?: Int.MAX_VALUE).coerceAtLeast((props.int("minLines") ?: 1).coerceAtLeast(1)),
                overflow = when (node.string("overflow")?.lowercase()) { "ellipsis" -> TextOverflow.Ellipsis; "visible" -> TextOverflow.Visible; else -> TextOverflow.Clip },
                onTextLayout = { result -> onLayout?.invoke(buildJsonObject {
                    put("width", result.size.width); put("height", result.size.height); put("lineCount", result.lineCount)
                    put("hasVisualOverflow", result.hasVisualOverflow)
                }.toString()) })
        }
        "CupertinoIcon", "CupertinoLinkIcon" -> {
            val iconName = node.string("imageVector") ?: node.string("icon")
            val vector = cupertinoIcon(iconName)
            val resource = node.string("painterResource") ?: node.string("imageBitmapResource")
            val painter = resource?.let { rememberAssetPainter(it) }
            val tint = cupertinoColor(node, "tint") ?: LocalContentColor.current
            val density = LocalDensity.current
            val direction = LocalLayoutDirection.current
            val bitmap = if (painter != null && node.string("imageBitmapResource") != null) {
                val intrinsic = painter.intrinsicSize
                val width = (props.int("bitmapWidth") ?: intrinsic.width.takeIf { it.isFinite() }?.toInt() ?: 24).coerceIn(1, 4096)
                val height = (props.int("bitmapHeight") ?: intrinsic.height.takeIf { it.isFinite() }?.toInt() ?: 24).coerceIn(1, 4096)
                remember(painter, width, height, density, direction) {
                    ImageBitmap(width, height).also { image ->
                        CanvasDrawScope().draw(density, direction, Canvas(image), Size(width.toFloat(), height.toFloat())) {
                            with(painter) { draw(size) }
                        }
                    }
                }
            } else null
            if (resource != null && painter == null) CupertinoText("Image unavailable.", color = Color.Red, modifier = node.composeModifiers())
            else if (painter != null) {
                if (name == "CupertinoLinkIcon") {
                    val container = cupertinoColor(node, "containerColor") ?: io.github.alexzhirkevich.cupertino.section.CupertinoLabelIconDefaults.ContainerColor
                    val linkTint = cupertinoColor(node, "tint") ?: io.github.alexzhirkevich.cupertino.section.CupertinoLabelIconDefaults.Tint
                    val shape = cupertinoShape(node, default = io.github.alexzhirkevich.cupertino.section.CupertinoLabelIconDefaults.Shape)
                    if (bitmap != null) io.github.alexzhirkevich.cupertino.section.CupertinoLinkIcon(bitmap, node.composeModifiers(), container, linkTint, shape, node.string("contentDescription"))
                    else io.github.alexzhirkevich.cupertino.section.CupertinoLinkIcon(painter, node.composeModifiers(), container, linkTint, shape, node.string("contentDescription"))
                } else if (bitmap != null) CupertinoIcon(bitmap, node.string("contentDescription"), node.composeModifiers(), tint)
                else CupertinoIcon(painter, node.string("contentDescription"), node.composeModifiers(), tint)
            }
            else if (vector == null) CupertinoText("Icon unavailable.", color = Color.Red, modifier = node.composeModifiers())
            else if (name == "CupertinoLinkIcon") io.github.alexzhirkevich.cupertino.section.CupertinoLinkIcon(
                imageVector = vector, contentDescription = node.string("contentDescription"), modifier = node.composeModifiers(),
                containerColor = cupertinoColor(node, "containerColor") ?: io.github.alexzhirkevich.cupertino.section.CupertinoLabelIconDefaults.ContainerColor,
                tint = cupertinoColor(node, "tint") ?: io.github.alexzhirkevich.cupertino.section.CupertinoLabelIconDefaults.Tint,
                shape = cupertinoShape(node, default = io.github.alexzhirkevich.cupertino.section.CupertinoLabelIconDefaults.Shape))
            else CupertinoIcon(vector, node.string("contentDescription"), node.composeModifiers(), cupertinoColor(node, "tint") ?: LocalContentColor.current)
        }
        "CupertinoButton", "CupertinoIconButton", "CupertinoNavigateBackButton", "AdaptiveButton", "AdaptiveTextButton", "AdaptiveTonalButton", "AdaptiveIconButton", "AdaptiveFilledIconButton" -> {
            val enabled = cupertinoEnabled(node)
            val click = props.voidAction("onClick")
            val action: () -> Unit = { if (enabled) click?.invoke() }
            val defaultButtonStyle = when (name) {
                "CupertinoIconButton", "CupertinoNavigateBackButton", "AdaptiveTextButton", "AdaptiveIconButton" -> "plain"
                "AdaptiveTonalButton" -> "tinted"
                else -> "filled"
            }
            val colors = buttonColors(node, defaultButtonStyle)
            val hasColorOverride = listOf("colors", "style", "containerColor", "contentColor", "disabledContainerColor", "disabledContentColor", "indicationColor").any(node.props::containsKey)
            val containerOverride = cupertinoColor(node, "containerColor") ?: Color.Unspecified
            val contentOverride = cupertinoColor(node, "contentColor") ?: Color.Unspecified
            val disabledContainerOverride = cupertinoColor(node, "disabledContainerColor") ?: Color.Unspecified
            val disabledContentOverride = cupertinoColor(node, "disabledContentColor") ?: Color.Unspecified
            val materialColors = when (name) {
                "AdaptiveTextButton" -> androidx.compose.material3.ButtonDefaults.textButtonColors(containerOverride, contentOverride, disabledContainerOverride, disabledContentOverride)
                "AdaptiveTonalButton" -> androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerOverride, contentOverride, disabledContainerOverride, disabledContentOverride)
                else -> androidx.compose.material3.ButtonDefaults.buttonColors(containerOverride, contentOverride, disabledContainerOverride, disabledContentOverride)
            }
            val materialIconColors = if (name == "AdaptiveFilledIconButton") androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerOverride, contentOverride, disabledContainerOverride, disabledContentOverride)
                else androidx.compose.material3.IconButtonDefaults.iconButtonColors(containerOverride, contentOverride, disabledContainerOverride, disabledContentOverride)
            val size = when (node.string("size")?.lowercase()) { "small" -> CupertinoButtonSize.Small; "large" -> CupertinoButtonSize.Large; "extralarge" -> CupertinoButtonSize.ExtraLarge; else -> CupertinoButtonSize.Regular }
            val content: @Composable () -> Unit = { cupertinoSlot(node, if (name == "CupertinoNavigateBackButton") "title" else "content") }
            val rowContent: @Composable RowScope.() -> Unit = { CompositionLocalProvider(LocalCupertinoRowScope provides this) { content() } }
            val shape = cupertinoShape(node, default = size.shape(CupertinoTheme.shapes))
            val padding = cupertinoPadding(node, "contentPadding", size.contentPadding)
            when (name) {
                "CupertinoButton" -> CupertinoButton(action, node.composeModifiers(), enabled, size, colors, cupertinoBorder(node), shape, padding, content = rowContent)
                "CupertinoIconButton" -> CupertinoIconButton(action, node.composeModifiers(), enabled, colors, cupertinoBorder(node), content = content)
                "CupertinoNavigateBackButton" -> {
                    val icon = cupertinoIcon(node.string("icon"))
                    if (icon != null) CupertinoNavigateBackButton(action, node.composeModifiers(), enabled, size, shape, colors,
                        cupertinoBorder(node), cupertinoPadding(node, "contentPadding", PaddingValues(8.dp, 4.dp)), icon = icon, title = rowContent)
                    else CupertinoNavigateBackButton(action, node.composeModifiers(), enabled, size, shape, colors,
                        cupertinoBorder(node), cupertinoPadding(node, "contentPadding", PaddingValues(8.dp, 4.dp)), title = rowContent)
                }
                "AdaptiveButton" -> AdaptiveButton(action, node.composeModifiers(), enabled, cupertinoBorder(node), adaptation = {
                    cupertino { this.size = size; this.shape = shape; contentPadding = padding; if (hasColorOverride) this.colors = colors }
                    material { this.colors = materialColors; if (node.props.containsKey("shape")) this.shape = shape; if (node.props.containsKey("contentPadding")) this.contentPadding = padding }
                }, content = rowContent)
                "AdaptiveTextButton" -> AdaptiveTextButton(action, node.composeModifiers(), enabled, cupertinoBorder(node), adaptation = {
                    cupertino { this.size = size; this.shape = shape; contentPadding = padding; if (hasColorOverride) this.colors = colors }
                    material { this.colors = materialColors; if (node.props.containsKey("shape")) this.shape = shape; if (node.props.containsKey("contentPadding")) this.contentPadding = padding }
                }, content = rowContent)
                "AdaptiveTonalButton" -> AdaptiveTonalButton(action, node.composeModifiers(), enabled, cupertinoBorder(node), adaptation = {
                    cupertino { this.size = size; this.shape = shape; contentPadding = padding; if (hasColorOverride) this.colors = colors }
                    material { this.colors = materialColors; if (node.props.containsKey("shape")) this.shape = shape; if (node.props.containsKey("contentPadding")) this.contentPadding = padding }
                }, content = rowContent)
                "AdaptiveIconButton" -> AdaptiveIconButton(action, node.composeModifiers(), enabled, adaptation = {
                    cupertino { if (hasColorOverride) this.colors = colors }; material { this.colors = materialIconColors }
                }, content = content)
                else -> AdaptiveFilledIconButton(action, node.composeModifiers(), enabled, adaptation = {
                    cupertino { if (hasColorOverride) this.colors = colors }; material { this.colors = materialIconColors }
                }, content = content)
            }
        }
        "CupertinoSwitch", "AdaptiveSwitch", "CupertinoCheckBox", "CupertinoCheckbox", "AdaptiveCheckbox" -> {
            val external = node.bool("checked") ?: false
            var checked by remember(node.id, external) { mutableStateOf(external) }
            val enabled = cupertinoEnabled(node)
            val callback = props.boolAction("onCheckedChange")
            val change: (Boolean) -> Unit = { if (enabled) { checked = it; callback?.invoke(it) } }
            val thumb: (@Composable () -> Unit)? = if (cupertinoHasSlot(node, "thumbContent")) ({ cupertinoSlot(node, "thumbContent") }) else null
            when (name) {
                "CupertinoSwitch" -> CupertinoSwitch(checked, change, node.composeModifiers(), thumb, enabled = enabled,
                    colors = CupertinoSwitchDefaults.colors(thumbColor = cupertinoColor(node, "thumbColor") ?: Color.White,
                        checkedTrackColor = cupertinoColor(node, "checkedTrackColor") ?: CupertinoColors.systemGreen,
                        uncheckedTrackColor = cupertinoColor(node, "uncheckedTrackColor") ?: CupertinoColors.Gray.copy(alpha = .33f)))
                "AdaptiveSwitch" -> AdaptiveSwitch(checked, change, node.composeModifiers(), thumb, enabled)
                "AdaptiveCheckbox" -> AdaptiveCheckbox(checked, if (callback != null) change else null, node.composeModifiers(), enabled)
                else -> CupertinoCheckBox(checked, if (callback != null) change else null, node.composeModifiers(), enabled,
                    colors = CupertinoCheckboxDefaults.colors(checkedBoxColor = cupertinoColor(node, "checkedBoxColor") ?: CupertinoTheme.colorScheme.accent,
                        checkedCheckmarkColor = cupertinoColor(node, "checkedCheckmarkColor") ?: CupertinoTheme.colorScheme.systemBackground))
            }
        }
        "CupertinoTriStateCheckBox", "CupertinoTriStateCheckbox", "AdaptiveTriStateCheckbox" -> {
            val enabled = cupertinoEnabled(node)
            val state = when (node.string("state")?.lowercase()) { "on" -> ToggleableState.On; "indeterminate" -> ToggleableState.Indeterminate; else -> ToggleableState.Off }
            val action = props.voidAction("onClick")?.let { callback -> { if (enabled) callback() } }
            if (name == "AdaptiveTriStateCheckbox") AdaptiveTriStateCheckbox(state, action, node.composeModifiers(), enabled)
            else CupertinoTriStateCheckBox(state, action, node.composeModifiers(), enabled)
        }
        "CupertinoSlider", "CupertinoRangeSlider", "AdaptiveSlider", "AdaptiveRangeSlider" -> RenderCupertinoSlider(node)
        "CupertinoSliderDefaults.Thumb", "CupertinoSliderDefaults.Track" -> {
            val context = LocalCupertinoSlider.current
            if (context == null) CupertinoText("This slider is unavailable.", color = Color.Red)
            else if (name.endsWith("Thumb")) CupertinoSliderDefaults.Thumb(context.interaction, node.composeModifiers(), context.colors,
                context.enabled && cupertinoEnabled(node), DpSize((node.double("thumbWidth") ?: 28.0).toFloat().dp, (node.double("thumbHeight") ?: 28.0).toFloat().dp))
            else CupertinoSliderDefaults.Track(context.positions, node.composeModifiers(), context.colors, context.enabled && cupertinoEnabled(node))
        }
        "CupertinoActivityIndicator", "AdaptiveCircularProgressIndicator" -> {
            val color = cupertinoColor(node, "color") ?: CupertinoActivityIndicatorDefaults.color
            val size = node.double("size")?.toFloat()?.dp ?: CupertinoActivityIndicatorDefaults.MinSize
            val count = (props.int("count") ?: CupertinoActivityIndicatorDefaults.PathCount).coerceAtLeast(1)
            val alpha = (props.float("minAlpha") ?: CupertinoActivityIndicatorDefaults.MinAlpha).coerceIn(0f, 1f)
            val inner = (props.float("innerRadius") ?: 1f / 3f).coerceIn(0f, 1f)
            val stroke = props.float("strokeWidth")?.dp ?: Dp.Unspecified
            val progress = (props.float("progress") ?: 1f).coerceIn(0f, 1f)
            val animation = infiniteRepeatable<Float>(tween(durationMillis = (props.int("durationMillis") ?: CupertinoActivityIndicatorDefaults.DurationMillis).coerceAtLeast(1), easing = LinearEasing))
            if (name == "CupertinoActivityIndicator") CupertinoActivityIndicator(modifier = node.composeModifiers(), progress = progress,
                size = size, color = color, count = count, innerRadius = inner, strokeWidth = stroke, animationSpec = animation, minAlpha = alpha)
            else AdaptiveCircularProgressIndicator(node.composeModifiers(), adaptationScope = {
                cupertino { this.color = color; this.size = size; this.count = count; innerRadius = inner; strokeWidth = stroke; minAlpha = alpha; this.progress = progress; animationSpec = animation }
                material { if (node.props.containsKey("color")) this.color = color; if (stroke != Dp.Unspecified) strokeWidth = stroke }
            })
        }
        "CupertinoDivider", "CupertinoHorizontalDivider", "CupertinoVerticalDivider", "AdaptiveDivider", "AdaptiveHorizontalDivider", "AdaptiveVerticalDivider" -> {
            val color = cupertinoColor(node, "color") ?: CupertinoDividerDefaults.color
            val thickness = props.float("thickness")?.dp ?: CupertinoDividerDefaults.Thickness
            when (name) {
                "CupertinoVerticalDivider" -> CupertinoVerticalDivider(node.composeModifiers(), thickness, color)
                "CupertinoDivider", "CupertinoHorizontalDivider" -> CupertinoHorizontalDivider(node.composeModifiers(), thickness, color)
                "AdaptiveVerticalDivider" -> AdaptiveVerticalDivider(node.composeModifiers()) { cupertino { this.color = color; this.thickness = thickness }; material { if (node.props.containsKey("color")) this.color = color; if (node.props.containsKey("thickness")) this.thickness = thickness } }
                else -> AdaptiveHorizontalDivider(node.composeModifiers()) { cupertino { this.color = color; this.thickness = thickness }; material { if (node.props.containsKey("color")) this.color = color; if (node.props.containsKey("thickness")) this.thickness = thickness } }
            }
        }
        else -> return false
    }
    return true
}

@Composable
private fun RenderCupertinoSlider(node: ViewNode) {
    val props = Props(node.props)
    val range = cupertinoRange(node, "valueRange")
    val external = (props.float("value") ?: range.start).coerceIn(range)
    var value by remember(node.id, external) { mutableFloatStateOf(external) }
    val externalRange = cupertinoRange(node, "value", range)
    var rangeValue by remember(node.id, externalRange) { mutableStateOf(externalRange) }
    val enabled = cupertinoEnabled(node)
    val steps = (props.int("steps") ?: 0).coerceAtLeast(0)
    val colors = sliderColors(node, steps)
    val startSource = remember(node.id) { MutableInteractionSource() }
    val endSource = remember(node.id) { MutableInteractionSource() }
    val callback = props.doubleAction("onValueChange")
    val rangeCallback = props.stringAction("onValueChange")
    val changed: (Float) -> Unit = { if (enabled) { value = it; callback?.invoke(it.toDouble()) } }
    val rangeChanged: (ClosedFloatingPointRange<Float>) -> Unit = { if (enabled) { rangeValue = it; rangeCallback?.invoke(buildJsonObject { put("start", it.start); put("end", it.endInclusive) }.toString()) } }
    val finish = props.voidAction("onValueChangeFinished")?.let { action -> { if (enabled) action() } }
    val thumb: @Composable (String, MutableInteractionSource, SliderPositions) -> Unit = { slot, source, positions ->
        if (cupertinoHasSlot(node, slot)) CompositionLocalProvider(LocalCupertinoSlider provides SliderSlotContext(positions, source, colors, enabled)) { cupertinoSlot(node, slot) }
        else CupertinoSliderDefaults.Thumb(source, colors = colors, enabled = enabled)
    }
    val track: @Composable (SliderPositions) -> Unit = { positions ->
        if (cupertinoHasSlot(node, "track")) CompositionLocalProvider(LocalCupertinoSlider provides SliderSlotContext(positions, startSource, colors, enabled)) { cupertinoSlot(node, "track") }
        else CupertinoSliderDefaults.Track(positions, colors = colors, enabled = enabled)
    }
    when (node.string("name")) {
        "AdaptiveSlider" -> AdaptiveSlider(value, changed, node.composeModifiers(), enabled, range, steps, finish)
        "AdaptiveRangeSlider" -> AdaptiveRangeSlider(rangeValue, rangeChanged, node.composeModifiers(), enabled, range, steps, finish)
        "CupertinoRangeSlider" -> CupertinoRangeSlider(rangeValue, rangeChanged, node.composeModifiers(), enabled, range, finish, steps, colors,
            startSource, endSource, startThumb = { thumb("startThumb", startSource, it) }, endThumb = { thumb("endThumb", endSource, it) }, track = track)
        else -> CupertinoSlider(value, changed, node.composeModifiers(), enabled, range, finish, steps, colors, startSource,
            thumb = { thumb("thumb", startSource, it) }, track = track)
    }
}
