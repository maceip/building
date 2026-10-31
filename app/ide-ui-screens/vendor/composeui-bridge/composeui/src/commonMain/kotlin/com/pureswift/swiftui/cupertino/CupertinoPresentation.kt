@file:OptIn(io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi::class,
    io.github.alexzhirkevich.cupertino.adaptive.ExperimentalAdaptiveApi::class)

package com.pureswift.swiftui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import io.github.alexzhirkevich.cupertino.*
import io.github.alexzhirkevich.cupertino.adaptive.AdaptiveAlertDialog
import io.github.alexzhirkevich.cupertino.adaptive.AdaptiveAlertDialogNative
import io.github.alexzhirkevich.cupertino.theme.CupertinoTheme
import io.github.alexzhirkevich.cupertino.theme.CupertinoColors
import io.github.alexzhirkevich.cupertino.theme.DefaultAlpha
import io.github.alexzhirkevich.cupertino.theme.systemRed
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

private val LocalCupertinoMenu = compositionLocalOf<CupertinoMenuScope?> { null }
@Composable
internal fun RenderCupertinoMenuChildren(scope: CupertinoMenuScope, node: ViewNode, slot: String = "content") {
    CompositionLocalProvider(LocalCupertinoMenu provides scope) { cupertinoSlot(node, slot) }
}

private val LocalCupertinoSwipeController = compositionLocalOf<SharedSwipeBoxController?> { null }

/** Builders remain ordered data until passed into the real upstream dialog scope. */
internal fun cupertinoDialogActions(node: ViewNode): List<ViewNode> {
    fun flatten(nodes: List<ViewNode>): List<ViewNode> = nodes.flatMap {
        if (it.type in setOf("Group", "TupleView", "CupertinoSlot")) flatten(it.children) else listOf(it)
    }
    return flatten(cupertinoSlotNodes(node, "buttons")).filter {
        it.string("name")?.let { name ->
            name.startsWith("AlertDialogActionsScope.") || name.startsWith("NativeAlertDialogActionsScope.")
        } == true
    }
}

internal fun cupertinoActionStyle(node: ViewNode): AlertActionStyle = when (node.string("name")?.substringAfterLast('.')) {
    "cancel" -> AlertActionStyle.Cancel
    "destructive" -> AlertActionStyle.Destructive
    "default" -> AlertActionStyle.Default
    else -> AlertActionStyle.entries.firstOrNull { it.name == node.string("style") } ?: AlertActionStyle.Default
}

private fun actionEnabled(node: ViewNode, parentEnabled: Boolean): Boolean = parentEnabled &&
    node.bool("enabled") != false && node.modifiers.none { it.kind == "disabled" && it.args.bool("value") == true }

private fun actionClick(node: ViewNode, enabled: Boolean): () -> Unit = {
    if (enabled) Props(node.props).voidAction("onClick")?.invoke()
}

private fun dialogProperties(node: ViewNode): DialogProperties {
    val props = cupertinoObject(node, "properties")
    fun flag(key: String, default: Boolean) = (props?.get(key) as? JsonPrimitive)?.booleanOrNull ?: node.bool(key) ?: default
    return DialogProperties(dismissOnBackPress = flag("dismissOnBackPress", true),
        dismissOnClickOutside = flag("dismissOnClickOutside", true),
        usePlatformDefaultWidth = flag("usePlatformDefaultWidth", true))
}

@Composable
private fun optionalSlot(node: ViewNode, name: String): (@Composable () -> Unit)? =
    if (cupertinoHasSlot(node, name)) ({ cupertinoSlot(node, name) }) else null

private fun AlertDialogActionsScope.renderActions(actions: List<ViewNode>, enabled: Boolean) {
    for (entry in actions) {
        val active = actionEnabled(entry, enabled)
        action(onClick = actionClick(entry, active), style = cupertinoActionStyle(entry), enabled = active) {
            if (cupertinoHasSlot(entry, "title")) cupertinoSlot(entry, "title")
            else CupertinoText(entry.string("title") ?: "")
        }
    }
}

private fun NativeAlertDialogActionsScope.renderNativeActions(actions: List<ViewNode>, enabled: Boolean) {
    for (entry in actions) {
        val active = actionEnabled(entry, enabled)
        action(onClick = actionClick(entry, active), style = cupertinoActionStyle(entry), enabled = active,
            title = entry.string("title") ?: "")
    }
}

@Composable
internal fun RenderCupertinoPresentation(node: ViewNode): Boolean {
    val name = node.string("name") ?: return false
    val enabled = cupertinoEnabled(node)
    val props = Props(node.props)
    when (name) {
        "CupertinoAlertDialog", "CupertinoActionSheet", "CupertinoAlertDialogNative", "CupertinoActionSheetNative",
        "AdaptiveAlertDialog", "AdaptiveAlertDialogNative" -> {
            val actions = cupertinoDialogActions(node)
            val dismiss = props.voidAction("onDismissRequest") ?: {}
            val properties = dialogProperties(node)
            val orientation = if (node.string("buttonsOrientation") == "Vertical") Orientation.Vertical else Orientation.Horizontal
            val container = cupertinoColor(node, "containerColor") ?: CupertinoDialogsDefaults.ContainerColor
            val shape = cupertinoShape(node, default = CupertinoDialogsDefaults.Shape)
            val adaptations = cupertinoObject(node, "adaptationJson")
            fun adapted(platform: String): ViewNode = node.copy(props = JsonObject(node.props +
                ((adaptations?.get(platform) as? JsonObject) ?: JsonObject(emptyMap()))))
            val cupertinoAdaptation = adapted("cupertino")
            val materialAdaptation = adapted("material")
            when (name) {
                "CupertinoAlertDialog" -> CupertinoAlertDialog(onDismissRequest = dismiss,
                    title = { cupertinoSlot(node, "title") }, message = optionalSlot(node, "message"),
                    containerColor = container, shape = shape,
                    shadowElevation = (node.double("shadowElevation") ?: 1.0).dp,
                    properties = properties, buttonsOrientation = orientation) { renderActions(actions, enabled) }
                "CupertinoActionSheet" -> CupertinoActionSheet(visible = node.bool("visible") ?: true,
                    onDismissRequest = dismiss, title = optionalSlot(node, "title"), message = optionalSlot(node, "message"),
                    containerColor = container,
                    secondaryContainerColor = cupertinoColor(node, "secondaryContainerColor") ?: CupertinoTheme.colorScheme.tertiarySystemBackground,
                    properties = properties, content = optionalSlot(node, "content")) { renderActions(actions, enabled) }
                "CupertinoAlertDialogNative" -> CupertinoAlertDialogNative(onDismissRequest = dismiss,
                    title = node.string("title"), message = node.string("message"), containerColor = container,
                    shape = shape, properties = properties, buttonsOrientation = orientation) { renderNativeActions(actions, enabled) }
                "CupertinoActionSheetNative" -> CupertinoActionSheetNative(visible = node.bool("visible") ?: true,
                    onDismissRequest = dismiss, title = node.string("title"), message = node.string("message"),
                    containerColor = container,
                    secondaryContainerColor = cupertinoColor(node, "secondaryContainerColor") ?: CupertinoTheme.colorScheme.tertiarySystemBackground,
                    properties = properties) { renderNativeActions(actions, enabled) }
                "AdaptiveAlertDialog" -> AdaptiveAlertDialog(onDismissRequest = dismiss,
                    title = { cupertinoSlot(node, "title") }, message = optionalSlot(node, "message"),
                    properties = properties, adaptation = {
                        cupertino {
                            cupertinoColor(cupertinoAdaptation, "containerColor")?.let { containerColor = it }
                            this.shape = cupertinoShape(cupertinoAdaptation, default = this.shape)
                            buttonsOrientation = if (cupertinoAdaptation.string("buttonsOrientation") == "Vertical") Orientation.Vertical else orientation
                        }
                        material {
                            cupertinoColor(materialAdaptation, "containerColor")?.let { containerColor = it }
                            this.shape = cupertinoShape(materialAdaptation, default = this.shape)
                        }
                    }) { renderActions(actions, enabled) }
                else -> AdaptiveAlertDialogNative(onDismissRequest = dismiss, title = node.string("title") ?: "",
                    message = node.string("message") ?: "", properties = properties, adaptation = {
                        cupertino {
                            cupertinoColor(cupertinoAdaptation, "containerColor")?.let { containerColor = it }
                            this.shape = cupertinoShape(cupertinoAdaptation, default = this.shape)
                            buttonsOrientation = if (cupertinoAdaptation.string("buttonsOrientation") == "Vertical") Orientation.Vertical else orientation
                        }
                        material {
                            cupertinoColor(materialAdaptation, "containerColor")?.let { containerColor = it }
                            this.shape = cupertinoShape(materialAdaptation, default = this.shape)
                        }
                    }) { renderNativeActions(actions, enabled) }
            }
        }
        "CupertinoDropdownMenu" -> {
            val offset = cupertinoObject(node, "offset")
            val popup = cupertinoObject(node, "properties")
            CupertinoDropdownMenu(expanded = node.bool("expanded") ?: false,
                onDismissRequest = props.voidAction("onDismissRequest") ?: {}, modifier = node.composeModifiers(),
                offset = DpOffset(((offset?.get("x") as? JsonPrimitive)?.doubleOrNull ?: 0.0).dp,
                    ((offset?.get("y") as? JsonPrimitive)?.doubleOrNull ?: 0.0).dp),
                paddingValues = cupertinoPadding(node, "paddingValues", CupertinoDropdownMenuDefaults.PaddingValues),
                containerColor = cupertinoColor(node, "containerColor") ?: CupertinoDropdownMenuDefaults.ContainerColor,
                width = (node.double("width") ?: 260.0).dp, elevation = (node.double("elevation") ?: 16.0).dp,
                properties = PopupProperties(focusable = (popup?.get("focusable") as? JsonPrimitive)?.booleanOrNull ?: true,
                    dismissOnBackPress = (popup?.get("dismissOnBackPress") as? JsonPrimitive)?.booleanOrNull ?: true,
                    dismissOnClickOutside = (popup?.get("dismissOnClickOutside") as? JsonPrimitive)?.booleanOrNull ?: true)) {
                CompositionLocalProvider(LocalCupertinoMenu provides this, LocalInheritedDisabled provides !enabled) { cupertinoSlot(node) }
            }
        }
        "CupertinoMenuScope.MenuItem", "CupertinoMenuScope.MenuSection", "CupertinoMenuScope.MenuTitle",
        "CupertinoMenuScope.MenuAction", "CupertinoMenuScope.MenuPickerAction", "CupertinoMenuScope.MenuDivider" -> {
            val scope = LocalCupertinoMenu.current ?: return false
            with(scope) {
                when (name) {
                    "CupertinoMenuScope.MenuItem" -> MenuItem(modifier = node.composeModifiers(),
                        minHeight = (node.double("minHeight") ?: 44.0).dp) { padding ->
                        Box(Modifier.padding(padding)) { cupertinoSlot(node) }
                    }
                    "CupertinoMenuScope.MenuSection" -> MenuSection(title = optionalSlot(node, "title")) { cupertinoSlot(node) }
                    "CupertinoMenuScope.MenuTitle" -> MenuTitle(modifier = node.composeModifiers()) { cupertinoSlot(node, "title") }
                    "CupertinoMenuScope.MenuAction" -> MenuAction(onClick = actionClick(node, enabled), modifier = node.composeModifiers(),
                        onClickLabel = node.string("onClickLabel"), enabled = enabled,
                        contentColor = cupertinoColor(node, "contentColor") ?: CupertinoDropdownMenuDefaults.ContentColor,
                        icon = { cupertinoSlot(node, "icon") }, caption = { cupertinoSlot(node, "caption") },
                        title = { cupertinoSlot(node, "title") })
                    "CupertinoMenuScope.MenuPickerAction" -> MenuPickerAction(isSelected = node.bool("isSelected") ?: false,
                        onClick = actionClick(node, enabled), modifier = node.composeModifiers(), onClickLabel = node.string("onClickLabel"),
                        enabled = enabled, contentColor = cupertinoColor(node, "contentColor") ?: CupertinoDropdownMenuDefaults.ContentColor,
                        selectionIcon = { if (cupertinoHasSlot(node, "selectionIcon")) cupertinoSlot(node, "selectionIcon") else CupertinoDropdownMenuDefaults.PickerLeadingIcon() },
                        icon = { cupertinoSlot(node, "icon") }, caption = { cupertinoSlot(node, "caption") }, title = { cupertinoSlot(node, "title") })
                    else -> MenuDivider(modifier = node.composeModifiers(), color = cupertinoColor(node, "color"), height = (node.double("height") ?: 8.0).dp)
                }
            }
        }
        "CupertinoDropdownMenuDefaults.PickerLeadingIcon" -> CupertinoDropdownMenuDefaults.PickerLeadingIcon()
        "CupertinoBottomSheetScaffold" -> RenderCupertinoSheet(node)
        "CupertinoBottomSheetContent" -> CupertinoBottomSheetContent(modifier = node.composeModifiers(),
            containerColor = cupertinoColor(node, "containerColor") ?: CupertinoBottomSheetDefaults.containerColor,
            contentColor = cupertinoColor(node, "contentColor") ?: CupertinoBottomSheetDefaults.contentColor,
            appBarsAlpha = (node.double("appBarsAlpha") ?: CupertinoScaffoldDefaults.AppBarsBlurAlpha.toDouble()).toFloat(),
            appBarsBlurRadius = node.double("appBarsBlurRadius")?.dp ?: CupertinoScaffoldDefaults.AppBarsBlurRadius,
            hasNavigationTitle = node.bool("hasNavigationTitle") ?: false,
            topBar = { cupertinoSlot(node, "topBar") }, bottomBar = { cupertinoSlot(node, "bottomBar") }) { padding ->
            Box(Modifier.padding(padding)) { cupertinoSlot(node) }
        }
        "CupertinoBottomSheetDefaults.DragHandle" -> CupertinoBottomSheetDefaults.DragHandle(modifier = node.composeModifiers(),
            width = (node.double("width") ?: 38.0).dp, height = (node.double("height") ?: 5.0).dp,
            shape = cupertinoShape(node, default = CircleShape), color = cupertinoColor(node, "color") ?: CupertinoTheme.colorScheme.systemFill)
        "SharedSwipeBoxController" -> {
            val controller = remember { SharedSwipeBoxController() }
            val commandID = node.string("commandID")
            var consumed by rememberSaveable { mutableStateOf<String?>(null) }
            LaunchedEffect(commandID, enabled) {
                if (enabled && commandID != null && commandID != consumed && node.string("command") == "collapse") {
                    consumed = commandID
                    controller.collapse()
                }
            }
            CompositionLocalProvider(LocalCupertinoSwipeController provides controller) { cupertinoSlot(node) }
        }
        "CupertinoSwipeBox" -> RenderCupertinoSwipe(node)
        "CupertinoSwipeBoxItem" -> {
            val color = cupertinoColor(node, "color") ?: CupertinoColors.systemRed
            if (cupertinoHasSlot(node, "content")) {
                CupertinoSwipeBoxItem(color = color, onClick = actionClick(node, enabled), modifier = node.composeModifiers(),
                    enabled = enabled, onClickLabel = node.string("onClickLabel")) { cupertinoSlot(node) }
            } else {
                CupertinoSwipeBoxItem(color = color, onClick = actionClick(node, enabled), modifier = node.composeModifiers(),
                    enabled = enabled, onClickLabel = node.string("onClickLabel"),
                    icon = { cupertinoSlot(node, "icon") }, label = { cupertinoSlot(node, "label") })
            }
        }
        else -> return false
    }
    return true
}

/** Both structured wire arrays and JSON-string compatibility props use this parser. */
internal fun cupertinoDetents(node: ViewNode): List<PresentationDetent> = cupertinoArray(node, "detents")?.mapNotNull { raw ->
    val obj = raw as? JsonObject
    val kind = (obj?.get("kind") as? JsonPrimitive)?.content ?: (raw as? JsonPrimitive)?.content
    val value = (obj?.get("value") as? JsonPrimitive)?.doubleOrNull
    when (kind?.lowercase()) {
        "large" -> PresentationDetent.Large
        "medium" -> PresentationDetent.Medium
        "fraction" -> value?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let { PresentationDetent.Fraction(it.toFloat()) }
        "height" -> value?.takeIf { it.isFinite() && it > 0 }?.let { PresentationDetent.Height(it.dp) }
        else -> null
    }
}?.distinct()?.takeIf { it.isNotEmpty() } ?: listOf(PresentationDetent.Large)

internal fun cupertinoSheetValueName(value: CupertinoSheetValue): String = when (value) {
    CupertinoSheetValue.Hidden -> "Hidden"
    CupertinoSheetValue.Expanded -> "Expanded"
    is CupertinoSheetValue.PartiallyExpanded -> "PartiallyExpanded"
}

internal fun cupertinoAllowsValue(node: ViewNode, value: String): Boolean =
    cupertinoArray(node, "allowedValues")?.any { (it as? JsonPrimitive)?.content == value } ?: true

private fun valueEvent(value: String): JsonObject = buildJsonObject { put("value", value) }

@Composable
private fun RenderCupertinoSheet(node: ViewNode) {
    val enabled = cupertinoEnabled(node)
    val latest by rememberUpdatedState(node)
    val active by rememberUpdatedState(enabled)
    val detents = remember(node.props["detents"]) { cupertinoDetents(node) }
    val fullscreen = node.string("presentationStyle") == "Fullscreen"
    val style = remember(fullscreen, detents, node.props["contentInteraction"], node.props["backgroundInteractive"], node.props["dismissOnClickOutside"]) {
        if (fullscreen) PresentationStyle.Fullscreen else PresentationStyle.Modal(detents = detents.toSet(),
            contentInteraction = if (node.string("contentInteraction") == "Scroll") PresentationContentInteraction.Scroll else PresentationContentInteraction.Resize,
            isBackgroundInteractive = { node.bool("backgroundInteractive") ?: false },
            dismissOnClickOutside = node.bool("dismissOnClickOutside") ?: true)
    }
    val partial = detents.getOrNull((node.long("partialDetentIndex") ?: 0).toInt()) ?: detents.first()
    fun stateValue(raw: String?): CupertinoSheetValue = when (raw) {
        "Expanded" -> CupertinoSheetValue.Expanded
        "PartiallyExpanded" -> if (fullscreen) CupertinoSheetValue.Expanded else CupertinoSheetValue.PartiallyExpanded(partial)
        else -> CupertinoSheetValue.Hidden
    }
    val requested = node.string("value") ?: "Hidden"
    val sheet = rememberCupertinoSheetState(initialValue = stateValue(requested), presentationStyle = style,
        confirmValueChange = { active && cupertinoAllowsValue(latest, cupertinoSheetValueName(it)) })
    val scaffold = rememberCupertinoBottomSheetScaffoldState(sheet)
    var consumed by rememberSaveable { mutableStateOf<String?>(null) }
    var priorValue by rememberSaveable { mutableStateOf(requested) }
    suspend fun move(target: String) {
        if (!active || !cupertinoAllowsValue(latest, target)) return
        when (target) {
            "Hidden" -> sheet.hide()
            "Expanded" -> sheet.expand()
            "PartiallyExpanded" -> if (!fullscreen) sheet.partialExpand(partial)
        }
    }
    LaunchedEffect(sheet, requested) {
        if (requested != priorValue) { priorValue = requested; move(requested) }
    }
    LaunchedEffect(sheet, node.string("commandID"), enabled) {
        val id = node.string("commandID")
        if (active && id != null && id != consumed) {
            consumed = id
            // Wait for the actual scaffold anchors before choosing the default show detent.
            snapshotFlow { runCatching { sheet.requireOffset() }.getOrNull()?.takeIf { it.isFinite() } }.filterNotNull().first()
            when (node.string("command")) {
                "show" -> if (cupertinoAllowsValue(latest, if (!fullscreen && detents.size > 1) "PartiallyExpanded" else "Expanded")) sheet.show()
                "hide" -> move("Hidden")
                "expand" -> move("Expanded")
                "partialExpand" -> move("PartiallyExpanded")
            }
        }
    }
    LaunchedEffect(sheet) {
        snapshotFlow { cupertinoSheetValueName(sheet.currentValue) }.distinctUntilChanged().collect {
            cupertinoStringEvent(latest, "onStateChange", valueEvent(it))
        }
    }
    val dragHandle: (@Composable () -> Unit)? = when {
        cupertinoHasSlot(node, "sheetDragHandle") -> ({ cupertinoSlot(node, "sheetDragHandle") })
        node.bool("showDragHandle") == false || detents.size > 1 -> null
        else -> ({ CupertinoBottomSheetDefaults.DragHandle() })
    }
    // The upstream modifier reaches only its background scaffold. Constrain the
    // common parent so both layers share its bounds, including the hidden sheet
    // translated below the viewport on hosts taller than the requested frame.
    Box(node.composeModifiers().clipToBounds(), propagateMinConstraints = true) {
    CupertinoBottomSheetScaffold(sheetContent = { cupertinoSlot(node, "sheetContent") }, modifier = Modifier,
        scaffoldState = scaffold,
        colors = CupertinoBottomSheetScaffoldDefaults.colors(
            sheetContainerColor = cupertinoColor(node, "sheetContainerColor") ?: CupertinoBottomSheetDefaults.containerColor,
            sheetContentColor = cupertinoColor(node, "sheetContentColor") ?: CupertinoBottomSheetDefaults.contentColor,
            containerColor = cupertinoColor(node, "containerColor") ?: CupertinoTheme.colorScheme.systemBackground,
            contentColor = cupertinoColor(node, "contentColor") ?: CupertinoTheme.colorScheme.label,
            scrimColor = cupertinoColor(node, "scrimColor") ?: CupertinoColors.DefaultAlpha,
            scaledScaffoldBackgroundColor = cupertinoColor(node, "scaledScaffoldBackgroundColor") ?: Color.Black),
        sheetShape = cupertinoShape(node, "sheetShape", CupertinoBottomSheetDefaults.shape),
        sheetShadowElevation = node.double("sheetShadowElevation")?.dp ?: CupertinoBottomSheetDefaults.ShadowElevation,
        sheetDragHandle = dragHandle, sheetSwipeEnabled = enabled && node.bool("sheetSwipeEnabled") != false,
        topBar = optionalSlot(node, "topBar"), bottomBar = optionalSlot(node, "bottomBar"),
        appBarsBlurAlpha = node.double("appBarsBlurAlpha")?.toFloat() ?: CupertinoScaffoldDefaults.AppBarsBlurAlpha,
        appBarsBlurRadius = node.double("appBarsBlurRadius")?.dp ?: CupertinoScaffoldDefaults.AppBarsBlurRadius,
        hasNavigationTitle = node.bool("hasNavigationTitle") ?: false) { padding ->
        Box(Modifier.padding(padding)) { cupertinoSlot(node) }
    }
    }
}

@Composable
private fun RenderCupertinoSwipe(node: ViewNode) {
    val enabled = cupertinoEnabled(node)
    val latest by rememberUpdatedState(node)
    val active by rememberUpdatedState(enabled)
    fun value(raw: String?) = CupertinoSwipeBoxValue.entries.firstOrNull { it.name == raw } ?: CupertinoSwipeBoxValue.Collapsed
    val requested = value(node.string("value"))
    val state = rememberCupertinoSwipeBoxState(initialValue = requested,
        sharedController = LocalCupertinoSwipeController.current,
        dismissThreshold = (node.double("dismissThreshold")?.toFloat() ?: CupertinoSwipeBoxDefaults.DismissThreshold).coerceIn(0f, 1f),
        confirmValueChange = { active && cupertinoAllowsValue(latest, it.name) })
    var priorValue by rememberSaveable { mutableStateOf(requested.name) }
    var consumed by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state, requested) {
        if (priorValue != requested.name) {
            priorValue = requested.name
            if (active && cupertinoAllowsValue(latest, requested.name)) {
                snapshotFlow { state.hasMeasuredAnchor(requested) }.first { it }
                if (active && cupertinoAllowsValue(latest, requested.name)) state.animateTo(requested)
            }
        }
    }
    LaunchedEffect(state, node.string("commandID"), enabled) {
        val id = node.string("commandID")
        if (active && id != null && id != consumed) {
            consumed = id
            val target = if (node.string("command") == "reset") CupertinoSwipeBoxValue.Collapsed else value(node.string("commandValue"))
            snapshotFlow { state.hasMeasuredAnchor(target) }.first { it }
            if (cupertinoAllowsValue(latest, target.name)) when (node.string("command")) {
                "reset" -> state.reset()
                "snapTo" -> state.snapTo(target)
                "animateTo" -> state.animateTo(target)
            }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.currentValue.name }.distinctUntilChanged().collect {
            cupertinoStringEvent(latest, "onStateChange", valueEvent(it))
        }
    }
    fun behavior(key: String) = if (!enabled) SwipeBoxBehavior.Disabled else
        SwipeBoxBehavior.entries.firstOrNull { it.name == node.string(key) } ?: SwipeBoxBehavior.Dismissible
    CupertinoSwipeBox(state = state, items = {
        val slot = when (state.dismissDirection) {
            CupertinoSwipeBoxValue.ExpandedToEnd, CupertinoSwipeBoxValue.DismissedToEnd -> "startItems"
            else -> "endItems"
        }
        if (cupertinoHasSlot(node, slot)) cupertinoSlot(node, slot) else cupertinoSlot(node, "items")
    }, modifier = node.composeModifiers(), restoreOnClick = enabled && node.bool("restoreOnClick") != false,
        handleWidth = node.double("handleWidth")?.dp ?: Dp.Unspecified,
        itemWidth = node.double("itemWidth")?.dp ?: CupertinoSwipeBoxDefaults.ItemWidth,
        startToEndBehavior = behavior("startToEndBehavior"), endToStartBehavior = behavior("endToStartBehavior")) {
        cupertinoSlot(node)
    }
}
