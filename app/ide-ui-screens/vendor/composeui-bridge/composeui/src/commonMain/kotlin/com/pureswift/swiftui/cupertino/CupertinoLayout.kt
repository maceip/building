@file:OptIn(io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi::class,
    io.github.alexzhirkevich.cupertino.adaptive.ExperimentalAdaptiveApi::class)
@file:Suppress("DEPRECATION")
package com.pureswift.swiftui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.LocalContentColor
import io.github.alexzhirkevich.cupertino.*
import io.github.alexzhirkevich.cupertino.adaptive.*
import io.github.alexzhirkevich.cupertino.section.CupertinoSectionDefaults
import io.github.alexzhirkevich.cupertino.theme.CupertinoTheme
import kotlinx.serialization.json.*

internal val LocalCupertinoScaffoldPadding = staticCompositionLocalOf { PaddingValues(0.dp) }
internal val LocalCupertinoScaffold = staticCompositionLocalOf { false }
private data class SegmentSlot(val selected: Int, val positions: List<TabPosition>)
private val LocalCupertinoSegment = staticCompositionLocalOf<SegmentSlot?> { null }

internal fun cupertinoInsets(node: ViewNode, key: String, default: WindowInsets): WindowInsets {
    if (node.string(key) == "none") return WindowInsets(0, 0, 0, 0)
    val obj = cupertinoObject(node, key) ?: return default
    return WindowInsets(left = (obj.double("left") ?: obj.double("start") ?: 0.0).toFloat().dp,
        top = (obj.double("top") ?: 0.0).toFloat().dp,
        right = (obj.double("right") ?: obj.double("end") ?: 0.0).toFloat().dp,
        bottom = (obj.double("bottom") ?: 0.0).toFloat().dp)
}

@Composable
internal fun RenderCupertinoLayout(node: ViewNode): Boolean {
    val name = node.string("name") ?: return false
    val props = Props(node.props)
    when (name) {
        "CupertinoSurface", "Surface", "AdaptiveSurface" -> {
            val shape = cupertinoShape(node)
            val contentColor = cupertinoColor(node, "contentColor") ?: LocalContentColor.current
            val color = cupertinoColor(node, "color") ?: CupertinoTheme.colorScheme.systemBackground
            val click = props.voidAction("onClick")
            val enabled = cupertinoEnabled(node)
            val content: @Composable () -> Unit = { cupertinoSlot(node, "content") }
            if (name == "AdaptiveSurface") AdaptiveSurface(modifier = node.composeModifiers(), shape = shape,
                color = cupertinoColor(node, "color") ?: Color.Unspecified,
                contentColor = cupertinoColor(node, "contentColor") ?: Color.Unspecified,
                shadowElevation = (node.double("shadowElevation") ?: 0.0).toFloat().dp, content = content)
            else if (click != null) CupertinoSurface(onClick = { if (enabled) click() }, modifier = node.composeModifiers(),
                enabled = enabled, shape = shape, color = color, contentColor = contentColor, border = cupertinoBorder(node), content = content)
            else CupertinoSurface(modifier = node.composeModifiers(), shape = shape, color = color,
                shadowElevation = (node.double("shadowElevation") ?: 0.0).toFloat().dp, contentColor = contentColor, content = content)
        }
        "CupertinoScaffold", "AdaptiveScaffold" -> {
            val content: @Composable (PaddingValues) -> Unit = { padding ->
                CompositionLocalProvider(LocalCupertinoScaffoldPadding provides padding) {
                    if (node.bool("applyContentPadding") ?: false) Box(Modifier.padding(padding)) { cupertinoSlot(node, "content") }
                    else cupertinoSlot(node, "content")
                }
            }
            val top: @Composable () -> Unit = { cupertinoSlot(node, "topBar") }
            val bottom: @Composable () -> Unit = { cupertinoSlot(node, "bottomBar") }
            val snackbar: @Composable () -> Unit = { cupertinoSlot(node, "snackbarHost") }
            val fab: @Composable () -> Unit = { cupertinoSlot(node, "floatingActionButton") }
            val fabPosition = if (node.string("floatingActionButtonPosition")?.lowercase() == "center") FabPosition.Center else FabPosition.End
            CompositionLocalProvider(LocalCupertinoScaffold provides true) {
                if (name == "CupertinoScaffold") CupertinoScaffold(modifier = node.composeModifiers(),
                    topBar = top, bottomBar = bottom, snackbarHost = snackbar, floatingActionButton = fab,
                    floatingActionButtonPosition = fabPosition,
                    containerColor = cupertinoColor(node, "containerColor") ?: CupertinoScaffoldDefaults.containerColor,
                    contentColor = cupertinoColor(node, "contentColor") ?: CupertinoScaffoldDefaults.contentColor,
                    contentWindowInsets = cupertinoInsets(node, "contentWindowInsets", CupertinoScaffoldDefaults.contentWindowInsets),
                    appBarsBlurAlpha = props.float("appBarsBlurAlpha") ?: CupertinoScaffoldDefaults.AppBarsBlurAlpha,
                    appBarsBlurRadius = props.float("appBarsBlurRadius")?.dp ?: CupertinoScaffoldDefaults.AppBarsBlurRadius,
                    hasNavigationTitle = node.bool("hasNavigationTitle") ?: false, content = content)
                else AdaptiveScaffold(modifier = node.composeModifiers(), topBar = top, bottomBar = bottom,
                    snackbarHost = snackbar, floatingActionButton = fab, floatingActionButtonPosition = fabPosition,
                    containerColor = cupertinoColor(node, "containerColor") ?: Color.Unspecified,
                    contentColor = cupertinoColor(node, "contentColor") ?: Color.Unspecified,
                    contentWindowInsets = cupertinoInsets(node, "contentWindowInsets", CupertinoScaffoldDefaults.contentWindowInsets), content = content)
            }
        }
        "CupertinoScaffoldPadding" -> Box(node.composeModifiers().padding(LocalCupertinoScaffoldPadding.current)) { cupertinoSlot(node, "content") }
        "CupertinoTopAppBar", "AdaptiveTopAppBar" -> {
            val title: @Composable () -> Unit = { cupertinoSlot(node, "title") }
            val navigation: @Composable () -> Unit = { cupertinoSlot(node, "navigationIcon") }
            val actions: @Composable RowScope.() -> Unit = { CompositionLocalProvider(LocalCupertinoRowScope provides this) { cupertinoSlot(node, "actions") } }
            val transparent = node.bool("isTransparent") ?: false
            val translucent = node.bool("isTranslucent") ?: LocalCupertinoScaffold.current
            val colors = CupertinoTopAppBarDefaults.topAppBarColors(
                containerColor = cupertinoColor(node, "containerColor") ?: CupertinoTheme.colorScheme.tertiarySystemBackground,
                scrolledContainerColor = cupertinoColor(node, "scrolledContainerColor") ?: Color.Transparent,
                navigationIconContentColor = cupertinoColor(node, "navigationIconContentColor") ?: CupertinoTheme.colorScheme.accent,
                titleContentColor = cupertinoColor(node, "titleContentColor") ?: CupertinoTheme.colorScheme.label,
                actionIconContentColor = cupertinoColor(node, "actionIconContentColor") ?: CupertinoTheme.colorScheme.accent)
            val divider: @Composable () -> Unit = {
                if (cupertinoHasSlot(node, "divider")) cupertinoSlot(node, "divider")
                else if (!transparent) CupertinoTopAppBarDefaults.divider()
            }
            if (name == "CupertinoTopAppBar") CupertinoTopAppBar(title, node.composeModifiers(), navigation, actions,
                windowInsets = cupertinoInsets(node, "windowInsets", CupertinoTopAppBarDefaults.windowInsets),
                isTransparent = transparent, isTranslucent = translucent, divider = divider, colors = colors)
            else AdaptiveTopAppBar(title, node.composeModifiers(), navigation, actions,
                windowInsets = cupertinoInsets(node, "windowInsets", CupertinoTopAppBarDefaults.windowInsets), adaptation = {
                    cupertino { this.colors = colors; isTransparent = transparent; isTranslucent = translucent; this.divider = { divider() } }
                    material { isCenterAligned = node.bool("isCenterAligned") ?: isCenterAligned }
                })
        }
        "CupertinoBottomAppBar" -> CupertinoBottomAppBar(modifier = node.composeModifiers(),
            isTranslucent = node.bool("isTranslucent") ?: true, isTransparent = node.bool("isTransparent") ?: false,
            containerColor = cupertinoColor(node, "containerColor") ?: CupertinoNavigationBarDefaults.containerColor,
            contentColor = cupertinoColor(node, "contentColor") ?: CupertinoTheme.colorScheme.accent,
            contentPadding = cupertinoPadding(node, "contentPadding", CupertinoSectionDefaults.PaddingValues),
            windowInsets = cupertinoInsets(node, "windowInsets", WindowInsets.navigationBars)) {
                CompositionLocalProvider(LocalCupertinoRowScope provides this) { cupertinoSlot(node, "content") }
            }
        "CupertinoNavigationBar", "AdaptiveNavigationBar" -> {
            val content: @Composable RowScope.() -> Unit = { CompositionLocalProvider(LocalCupertinoRowScope provides this) { cupertinoSlot(node, "content") } }
            if (name == "CupertinoNavigationBar") CupertinoNavigationBar(modifier = node.composeModifiers(),
                containerColor = cupertinoColor(node, "containerColor") ?: CupertinoNavigationBarDefaults.containerColor,
                windowInsets = cupertinoInsets(node, "windowInsets", WindowInsets.navigationBars),
                isTransparent = node.bool("isTransparent") ?: false, isTranslucent = node.bool("isTranslucent") ?: LocalCupertinoScaffold.current,
                divider = { if (cupertinoHasSlot(node, "divider")) cupertinoSlot(node, "divider") else CupertinoNavigationBarDefaults.divider() }, content = content)
            else AdaptiveNavigationBar(modifier = node.composeModifiers(),
                windowInsets = cupertinoInsets(node, "windowInsets", androidx.compose.material3.NavigationBarDefaults.windowInsets), content = content)
        }
        "RowScope.CupertinoNavigationBarItem", "CupertinoNavigationBarItem", "RowScope.AdaptiveNavigationBarItem", "AdaptiveNavigationBarItem" -> {
            val scope = LocalCupertinoRowScope.current
            if (scope == null) CupertinoText("This navigation control is unavailable.", color = Color.Red)
            else with(scope) {
                val enabled = cupertinoEnabled(node)
                val click = props.voidAction("onClick")
                val action: () -> Unit = { if (enabled) click?.invoke() }
                val icon: @Composable () -> Unit = { cupertinoSlot(node, "icon") }
                val label: (@Composable () -> Unit)? = if (cupertinoHasSlot(node, "label")) ({ cupertinoSlot(node, "label") }) else null
                if (name.contains("Adaptive")) AdaptiveNavigationBarItem(node.bool("selected") ?: false, action, icon,
                    node.composeModifiers(), enabled, label, node.bool("alwaysShowLabel") ?: true)
                else CupertinoNavigationBarItem(node.bool("selected") ?: false, action, icon, node.composeModifiers(), enabled,
                    label, node.bool("alwaysShowLabel") ?: true, node.bool("pressIndicationEnabled") ?: false,
                    colors = CupertinoNavigationBarDefaults.itemColors(
                        selectedIconColor = cupertinoColor(node, "selectedIconColor") ?: CupertinoTheme.colorScheme.accent,
                        selectedTextColor = cupertinoColor(node, "selectedTextColor") ?: CupertinoTheme.colorScheme.accent,
                        unselectedIconColor = cupertinoColor(node, "unselectedIconColor") ?: CupertinoTheme.colorScheme.secondaryLabel,
                        unselectedTextColor = cupertinoColor(node, "unselectedTextColor") ?: CupertinoTheme.colorScheme.secondaryLabel,
                        disabledIconColor = cupertinoColor(node, "disabledIconColor") ?: CupertinoTheme.colorScheme.tertiaryLabel,
                        disabledTextColor = cupertinoColor(node, "disabledTextColor") ?: CupertinoTheme.colorScheme.tertiaryLabel))
            }
        }
        "CupertinoNavigationTitle" -> CupertinoNavigationTitle(modifier = node.composeModifiers(),
            maxFontScale = props.float("maxFontScale") ?: 1.1f,
            maxFontScaleDistance = props.float("maxFontScaleDistance")?.dp ?: 150.dp,
            paddingValues = cupertinoPadding(node, "paddingValues", CupertinoSectionDefaults.PaddingValues)) { cupertinoSlot(node, "content") }
        "CupertinoTopAppBarDefaults.divider" -> CupertinoTopAppBarDefaults.divider()
        "CupertinoNavigationBarDefaults.divider" -> CupertinoNavigationBarDefaults.divider()
        "CupertinoSegmentedControl" -> {
            val selected = (props.int("selectedTabIndex") ?: 0).coerceAtLeast(0)
            val defaults = CupertinoSegmentedControlDefaults.colors()
            val colors = CupertinoSegmentedControlDefaults.colors(
                containerColor = cupertinoColor(node, "containerColor") ?: defaults.containerColor,
                indicatorColor = cupertinoColor(node, "indicatorColor") ?: defaults.indicatorColor,
                contentColor = cupertinoColor(node, "contentColor") ?: defaults.contentColor,
                separatorColor = cupertinoColor(node, "separatorColor") ?: defaults.separatorColor)
            val shape = cupertinoShape(node, default = CupertinoSegmentedControlDefaults.shape)
            CupertinoSegmentedControl(selected, node.composeModifiers(), colors, shape,
                cupertinoPadding(node, "paddingValues", CupertinoSegmentedControlDefaults.PaddingValues), indicator = { positions ->
                    if (cupertinoHasSlot(node, "indicator")) CompositionLocalProvider(LocalCupertinoSegment provides SegmentSlot(selected, positions)) { cupertinoSlot(node, "indicator") }
                    else if (positions.isNotEmpty()) CupertinoSegmentedControlIndicator(selected.coerceIn(positions.indices), positions,
                        shape = shape, color = colors.indicatorColor, separatorColor = colors.separatorColor)
                }, tabs = { cupertinoSlot(node, "tabs") })
        }
        "CupertinoSegmentedControlTab" -> {
            val enabled = cupertinoEnabled(node)
            val action = props.voidAction("onClick")
            CupertinoSegmentedControlTab(onClick = { if (enabled) action?.invoke() }, isSelected = node.bool("isSelected") ?: false,
                modifier = node.composeModifiers()) { cupertinoSlot(node, "content") }
        }
        "CupertinoSegmentedControlIndicator" -> {
            val context = LocalCupertinoSegment.current
            if (context == null) CupertinoText("This selection indicator is unavailable.", color = Color.Red)
            else if (context.positions.isNotEmpty()) CupertinoSegmentedControlIndicator(
                (props.int("selectedTabIndex") ?: context.selected).coerceIn(context.positions.indices), context.positions,
                node.composeModifiers(), cupertinoShape(node, default = CupertinoTheme.shapes.small),
                cupertinoColor(node, "color") ?: CupertinoSegmentedControlDefaults.colors().indicatorColor,
                cupertinoColor(node, "separatorColor") ?: CupertinoTheme.colorScheme.separator)
        }
        "Modifier.haze" -> {
            val raw = node.props["area"] ?: node.props["areaJson"]
            val array = (raw as? JsonArray) ?: (raw as? JsonPrimitive)?.content?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
            val areas = array.orEmpty().mapNotNull { value ->
                val area = value as? JsonObject ?: return@mapNotNull null
                val left = area.double("left")?.toFloat() ?: return@mapNotNull null
                val top = area.double("top")?.toFloat() ?: return@mapNotNull null
                val right = area.double("right")?.toFloat() ?: return@mapNotNull null
                val bottom = area.double("bottom")?.toFloat() ?: return@mapNotNull null
                Rect(left, top, right, bottom)
            }.toTypedArray()
            val background = cupertinoColor(node, "backgroundColor") ?: CupertinoTheme.colorScheme.systemBackground
            Box(node.composeModifiers().haze(*areas, backgroundColor = background,
                tint = cupertinoColor(node, "tint") ?: HazeDefaults.tint(background),
                blurRadius = props.float("blurRadius")?.dp ?: HazeDefaults.blurRadius)) { cupertinoSlot(node, "content") }
        }
        "TabRowDefaults.Modifier.tabIndicatorOffset" -> {
            val context = LocalCupertinoSegment.current
            val position = context?.positions?.getOrNull(props.int("selectedTabIndex") ?: context.selected)
            if (position == null) CupertinoText("This selection indicator is unavailable.", color = Color.Red)
            else with(TabRowDefaults) { Box(node.composeModifiers().tabIndicatorOffset(position)) { cupertinoSlot(node, "content") } }
        }
        "Modifier.cupertinoPickerIndicator" -> {
            val state = LocalCupertinoPickerState.current
            if (state == null) CupertinoText("This selection indicator is unavailable.", color = Color.Red)
            else {
                val indicatorNode = node.copy(props = JsonObject(node.props + buildMap {
                    node.props["shape"]?.let { put("indicatorShape", it) }
                    node.props["paddingValues"]?.let { put("indicatorPadding", it) }
                }))
                val indicator = cupertinoWheelIndicator(indicatorNode)
                Box(node.composeModifiers().cupertinoPickerIndicator(state, indicator)) { cupertinoSlot(node, "content") }
            }
        }
        else -> return false
    }
    return true
}
