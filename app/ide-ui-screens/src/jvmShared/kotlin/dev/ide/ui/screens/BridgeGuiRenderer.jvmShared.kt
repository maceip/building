package dev.ide.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pureswift.swiftui.CallbackSink
import com.pureswift.swiftui.ComposableRegistry
import com.pureswift.swiftui.Render
import com.pureswift.swiftui.SwiftBridge
import com.pureswift.swiftui.ViewNode
import com.pureswift.swiftui.cupertinoIconNames
import io.github.alexzhirkevich.cupertino.theme.CupertinoTheme
import io.github.alexzhirkevich.cupertino.theme.darkColorScheme
import io.github.alexzhirkevich.cupertino.theme.lightColorScheme
import kotlinx.serialization.json.*

private val bridgeJson = Json { ignoreUnknownKeys = true }

private data class CupertinoNavEntry(
    val title: String,
    val destination: ViewNode,
)

private class CupertinoNavigatorState {
    val backStack = mutableStateListOf<CupertinoNavEntry>()

    fun push(title: String, destination: ViewNode) {
        backStack.add(CupertinoNavEntry(title, destination))
    }

    fun pop() {
        if (backStack.isNotEmpty()) {
            backStack.removeAt(backStack.lastIndex)
        }
    }
}

private val LocalCupertinoNavigator = compositionLocalOf<CupertinoNavigatorState?> { null }
private val LocalCupertinoIsDark = compositionLocalOf { false }
private val bridgeNodeChildren = java.util.concurrent.ConcurrentHashMap<String, List<ViewNode>>()

private var customBridgeComposablesRegistered = false

internal fun ensureCustomBridgeComposablesRegistered() {
    if (customBridgeComposablesRegistered) return
    customBridgeComposablesRegistered = true

    ComposableRegistry.register("Path") { props, _ ->
        val isDark = LocalCupertinoIsDark.current
        val d = props.string("d")
            ?: props.string("svgPath")
            ?: props.string("commands")
            ?: ""
        val fillColor = props.color("fill") ?: props.string("fill")?.let(::parseNamedOrHexBridgeColor)
        val strokeColor = props.color("stroke") ?: props.string("stroke")?.let(::parseNamedOrHexBridgeColor)
        val strokeWidth = props.float("strokeWidth") ?: 2f
        val parsed = remember(d) { parseSvgPath(d) }
        val defaultW = (parsed.boundsWidth.takeIf { it > 0f } ?: 120f).coerceIn(24f, 360f)
        val defaultH = (parsed.boundsHeight.takeIf { it > 0f } ?: 120f).coerceIn(24f, 360f)
        Canvas(
            modifier = Modifier
                .defaultMinSize(minWidth = defaultW.dp, minHeight = defaultH.dp)
                .size(width = defaultW.dp, height = defaultH.dp),
        ) {
            val densityScaleX = if (parsed.boundsWidth > 0f) size.width / parsed.boundsWidth else 1f
            val densityScaleY = if (parsed.boundsHeight > 0f) size.height / parsed.boundsHeight else 1f
            val scale = minOf(densityScaleX, densityScaleY).takeIf { it.isFinite() && it > 0f } ?: 1f
            val scaledPath = buildScaledComposePath(d, scale)
            if (fillColor != null) {
                drawPath(path = scaledPath, color = fillColor, style = Fill)
            }
            val effectiveStroke = strokeColor
                ?: if (fillColor == null) (if (isDark) Color.White else Color(0xFF007AFF)) else null
            if (effectiveStroke != null) {
                drawPath(
                    path = scaledPath,
                    color = effectiveStroke,
                    style = Stroke(
                        width = strokeWidth * density,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
    }

    ComposableRegistry.register("CupertinoShape") { props, _ ->
        val isDark = LocalCupertinoIsDark.current
        val shapeKind = (
            props.string("shapeType")
                ?: props.string("shape")
                ?: props.string("kind")
                ?: "rectangle"
            ).lowercase()
        val cornerRadius = props.float("cornerRadius") ?: 12f
        val fillColor = props.color("fill") ?: props.color("color")
            ?: (props.string("fill") ?: props.string("color"))?.let(::parseNamedOrHexBridgeColor)
        val strokeColor = props.color("stroke") ?: props.string("stroke")?.let(::parseNamedOrHexBridgeColor)
        val strokeWidth = props.float("strokeWidth") ?: 2f
        val effectiveFill = fillColor ?: if (strokeColor == null) {
            if (isDark) Color(0xFF0A84FF) else Color(0xFF007AFF)
        } else null
        Canvas(
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            when (shapeKind) {
                "circle" -> {
                    val r = minOf(size.width, size.height) / 2f
                    if (effectiveFill != null) {
                        drawCircle(color = effectiveFill, radius = r)
                    }
                    if (strokeColor != null) {
                        drawCircle(color = strokeColor, radius = r, style = Stroke(width = strokeWidth * density))
                    }
                }
                "capsule" -> {
                    val cr = minOf(size.width, size.height) / 2f
                    if (effectiveFill != null) {
                        drawRoundRect(color = effectiveFill, cornerRadius = CornerRadius(cr, cr))
                    }
                    if (strokeColor != null) {
                        drawRoundRect(
                            color = strokeColor,
                            cornerRadius = CornerRadius(cr, cr),
                            style = Stroke(width = strokeWidth * density),
                        )
                    }
                }
                "roundedrectangle" -> {
                    val cr = cornerRadius * density
                    if (effectiveFill != null) {
                        drawRoundRect(color = effectiveFill, cornerRadius = CornerRadius(cr, cr))
                    }
                    if (strokeColor != null) {
                        drawRoundRect(
                            color = strokeColor,
                            cornerRadius = CornerRadius(cr, cr),
                            style = Stroke(width = strokeWidth * density),
                        )
                    }
                }
                else -> {
                    if (effectiveFill != null) {
                        drawRect(color = effectiveFill, size = Size(size.width, size.height))
                    }
                    if (strokeColor != null) {
                        drawRect(
                            color = strokeColor,
                            size = Size(size.width, size.height),
                            style = Stroke(width = strokeWidth * density),
                        )
                    }
                }
            }
        }
    }

    ComposableRegistry.register("CupertinoTabView") { props, slotChildren ->
        val isDark = LocalCupertinoIsDark.current
        val nodeChildren = props.string("_nodeId")?.let { bridgeNodeChildren[it] }.orEmpty()
        val remoteSelected = props.int("selectedIndex") ?: 0
        val onSelect = props.intAction("onSelect")
        var selectedTab by remember { mutableIntStateOf(remoteSelected) }
        LaunchedEffect(remoteSelected) {
            selectedTab = remoteSelected
        }
        val safeIdx = selectedTab.coerceIn(0, (nodeChildren.size - 1).coerceAtLeast(0))
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val activeChild = nodeChildren.getOrNull(safeIdx)
                if (activeChild != null) {
                    Render(activeChild, isDark = isDark)
                } else {
                    slotChildren()
                }
            }
            if (nodeChildren.isNotEmpty()) {
                val barBg = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF9F9F9)
                val activeColor = if (isDark) Color(0xFF0A84FF) else Color(0xFF007AFF)
                val inactiveColor = Color(0xFF8E8E93)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .background(barBg)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    nodeChildren.forEachIndexed { idx, child ->
                        val tabMod = child.modifiers.firstOrNull { it.kind == "tabItem" }
                        val label = tabMod?.args?.get("label")?.jsonPrimitive?.contentOrNull
                            ?: (child.props["tabTitle"] as? JsonPrimitive)?.contentOrNull
                            ?: extractFirstTextLabel(child)
                            ?: "Tab ${idx + 1}"
                        val isSelected = idx == safeIdx
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Tab) {
                                    selectedTab = idx
                                    onSelect?.invoke(idx)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) activeColor else inactiveColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    ComposableRegistry.register("CupertinoNavigationStack") { props, slotChildren ->
        val isDark = LocalCupertinoIsDark.current
        val nodeChildren = props.string("_nodeId")?.let { bridgeNodeChildren[it] }.orEmpty()
        val navState = remember { CupertinoNavigatorState() }
        val rootTitle = props.string("title") ?: extractNavTitle(nodeChildren)
        val sheetChild = nodeChildren.firstOrNull {
            it.type == "Sheet" ||
                (it.type == "Composable" && it.props["name"]?.jsonPrimitive?.contentOrNull == "CupertinoSheet")
        }
        val mainChildren = nodeChildren.filterNot { it === sheetChild }

        CompositionLocalProvider(LocalCupertinoNavigator provides navState) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    val currentEntry = navState.backStack.lastOrNull()
                    val activeTitle = currentEntry?.title ?: rootTitle
                    if (currentEntry != null || !activeTitle.isNullOrBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (currentEntry != null) {
                                Text(
                                    text = "‹ Back",
                                    color = if (isDark) Color(0xFF0A84FF) else Color(0xFF007AFF),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(role = Role.Button) { navState.pop() }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            if (!activeTitle.isNullOrBlank()) {
                                Text(
                                    text = activeTitle,
                                    color = if (isDark) Color.White else Color.Black,
                                    fontSize = if (currentEntry == null) 22.sp else 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        if (currentEntry != null) {
                            Render(currentEntry.destination, isDark = isDark)
                        } else if (mainChildren.isNotEmpty()) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                mainChildren.forEach { child ->
                                    Render(child, isDark = isDark)
                                }
                            }
                        } else {
                            slotChildren()
                        }
                    }
                }
                if (sheetChild != null) {
                    Render(sheetChild, isDark = isDark)
                }
            }
        }
    }

    ComposableRegistry.register("CupertinoNavigationLink") { props, slotChildren ->
        val isDark = LocalCupertinoIsDark.current
        val nodeChildren = props.string("_nodeId")?.let { bridgeNodeChildren[it] }.orEmpty()
        val navigator = LocalCupertinoNavigator.current
        val onTap = props.voidAction("onTap")
        val explicitTitle = props.string("title") ?: props.string("label")
        val destChild = nodeChildren.firstOrNull {
            it.props["navigationRole"]?.jsonPrimitive?.contentOrNull == "destination"
        } ?: when {
            explicitTitle != null && nodeChildren.isNotEmpty() -> nodeChildren.first()
            nodeChildren.size >= 2 -> nodeChildren.first()
            else -> null
        }
        val labelChildren = nodeChildren.filter { it !== destChild }
        val linkTitle = explicitTitle
            ?: labelChildren.firstNotNullOfOrNull { extractFirstTextLabel(it) }
            ?: "Details"

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button) {
                    onTap?.invoke()
                    if (destChild != null && navigator != null) {
                        val destTitle = extractNavTitle(listOf(destChild)) ?: linkTitle
                        navigator.push(destTitle, destChild)
                    }
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (labelChildren.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    labelChildren.forEach { lc ->
                        Render(lc, isDark = isDark)
                    }
                }
            } else if (nodeChildren.isEmpty()) {
                slotChildren()
            } else {
                Text(
                    text = linkTitle,
                    color = if (isDark) Color(0xFF0A84FF) else Color(0xFF007AFF),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                text = "›",
                color = Color(0xFF8E8E93),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    ComposableRegistry.register("CupertinoSheet") { props, slotChildren ->
        val isDark = LocalCupertinoIsDark.current
        val nodeChildren = props.string("_nodeId")?.let { bridgeNodeChildren[it] }.orEmpty()
        val remotePresented = props.bool("isPresented") ?: false
        val onDismissBool = props.boolAction("onDismiss")
        val onDismissVoid = props.voidAction("onDismiss")
        var presented by remember(remotePresented) { mutableStateOf(remotePresented) }
        if (presented && nodeChildren.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.82f),
                    shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                    color = if (isDark) Color(0xFF1C1C1E) else Color.White,
                    tonalElevation = 8.dp,
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .background(Color(0xFF8E8E93).copy(alpha = 0.4f), CircleShape),
                            )
                            Text(
                                text = "Done",
                                color = if (isDark) Color(0xFF0A84FF) else Color(0xFF007AFF),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(role = Role.Button) {
                                        presented = false
                                        onDismissBool?.invoke(false)
                                        onDismissVoid?.invoke()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                if (nodeChildren.isNotEmpty()) {
                                    nodeChildren.forEach { child ->
                                        Render(child, isDark = isDark)
                                    }
                                } else {
                                    slotChildren()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
actual fun BridgeGuiWindow(
    rawJson: String,
    moduleName: String,
    mainClass: String,
    onSendCallback: (String) -> Unit,
    modifier: Modifier,
) {
    ensureCustomBridgeComposablesRegistered()
    val currentOnSendCallback by rememberUpdatedState(onSendCallback)
    DisposableEffect(Unit) {
        val sink = object : CallbackSink {
            private fun sendEvent(id: Long, kind: String, value: JsonElement? = null) {
                val payload = buildJsonObject {
                    put("id", id.toString())
                    put("kind", kind)
                    if (value != null) put("value", value)
                }.toString()
                currentOnSendCallback(payload)
            }

            override fun invokeVoid(id: Long) = sendEvent(id, "void")
            override fun invokeBool(id: Long, value: Boolean) = sendEvent(id, "bool", JsonPrimitive(value))
            override fun invokeDouble(id: Long, value: Double) = sendEvent(id, "double", JsonPrimitive(value))
            override fun invokeInt(id: Long, value: Int) = sendEvent(id, "int", JsonPrimitive(value))
            override fun invokeString(id: Long, value: String) = sendEvent(id, "string", JsonPrimitive(value))
        }
        val previous = SwiftBridge.sink
        SwiftBridge.sink = sink
        onDispose {
            if (SwiftBridge.sink === sink) {
                SwiftBridge.sink = previous
            }
        }
    }

    val parsedNode = remember(rawJson) {
        runCatching {
            val decoded = bridgeJson.decodeFromString<ViewNode>(rawJson)
            cupertino(decoded)
        }.getOrNull()
    }

    val isNodeGui = parsedNode?.props?.get("bridgeKind")?.jsonPrimitive?.contentOrNull == "nodegui" ||
        mainClass.endsWith(".js", ignoreCase = true) ||
        mainClass.endsWith(".ts", ignoreCase = true) ||
        moduleName.contains("nodegui", ignoreCase = true)
    // App shell: no IDE bridge/preview chrome. The guest owns the surface like a real app.
    val windowBgProp = parsedNode?.props?.get("windowBackground")?.jsonPrimitive?.contentOrNull
    val windowTitle = parsedNode?.props?.get("windowTitle")?.jsonPrimitive?.contentOrNull
        ?: moduleName.ifBlank { mainClass }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        val rootBgColor = remember(parsedNode) { extractRootBackgroundColor(parsedNode) }
        val isDark = when {
            windowBgProp != null -> isDarkHex(windowBgProp)
            rootBgColor != null -> (0.299f * rootBgColor.red + 0.587f * rootBgColor.green + 0.113f * rootBgColor.blue) < 0.45
            else -> false
        }
        val contentBg = windowBgProp?.let(::parseHexColor)
            ?: rootBgColor
            ?: if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(contentBg)
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = rememberClockLabel(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) Color.White else Color.Black,
            )
            Text(
                text = windowTitle,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = (if (isDark) Color.White else Color.Black).copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(4) { bar ->
                    Box(
                        Modifier
                            .size(width = 3.dp, height = (6 + bar * 2).dp)
                            .background(
                                if (isDark) Color.White else Color.Black,
                                RoundedCornerShape(1.dp),
                            ),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(contentBg),
        ) {
            CupertinoTheme(
                colorScheme = if (isDark) darkColorScheme() else lightColorScheme(),
            ) {
                CompositionLocalProvider(LocalCupertinoIsDark provides isDark) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(contentBg)
                            .padding(top = 8.dp, start = 8.dp, end = 8.dp, bottom = 20.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        if (parsedNode != null) {
                            Render(parsedNode, isDark = isDark)
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = windowTitle.ifBlank { "App" },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isDark) Color.White else Color.Black,
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .width(120.dp)
                    .height(4.dp)
                    .background(
                        (if (isDark) Color.White else Color.Black).copy(alpha = 0.35f),
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

@Composable
private fun rememberClockLabel(): String {
    val now = remember { java.util.Calendar.getInstance() }
    val h = now.get(java.util.Calendar.HOUR_OF_DAY)
    val m = now.get(java.util.Calendar.MINUTE)
    return "%d:%02d".format(h, m)
}

/**
 * Apply `compose-cupertino` styling to ordinary SwiftUI controls, retaining callback IDs,
 * structural identity, labels, modifiers, and Swift-owned values.
 * Nodes that are already `Composable` (e.g., direct `Cupertino*` calls from Swift or NodeGUI)
 * pass straight through to `com.pureswift.swiftui.Render`.
 */
internal fun cupertino(node: ViewNode): ViewNode {
    ensureCustomBridgeComposablesRegistered()
    val children = node.children.map(::cupertino)
    fun component(name: String, props: Map<String, JsonElement>, content: List<ViewNode> = children): ViewNode {
        val nodeKey = "${node.id}/$name/${content.hashCode()}"
        if (content.isNotEmpty()) {
            bridgeNodeChildren[nodeKey] = content
        }
        return node.copy(
            type = "Composable",
            props = JsonObject(props + ("name" to JsonPrimitive(name)) + ("_nodeId" to JsonPrimitive(nodeKey))),
            children = content,
        )
    }
    fun slot(name: String, content: List<ViewNode>) = ViewNode(
        type = "CupertinoSlot",
        id = "${node.id}/$name",
        props = buildJsonObject { put("name", name) },
        children = content,
    )
    return when (node.type) {
        "Text" -> component("CupertinoText", node.props)
        "Path" -> component("Path", node.props, emptyList())
        "Circle", "Rectangle", "RoundedRectangle", "Capsule" ->
            component("CupertinoShape", node.props + ("shapeType" to JsonPrimitive(node.type)), emptyList())
        "Shape" -> if (node.props["d"] != null || node.props["svgPath"] != null || node.props["commands"] != null) {
            component("Path", node.props, emptyList())
        } else {
            component("CupertinoShape", node.props, emptyList())
        }
        "TabView" -> component("CupertinoTabView", node.props, children)
        "NavigationStack", "NavigationView", "NavStack" -> component("CupertinoNavigationStack", node.props, children)
        "NavigationLink" -> component("CupertinoNavigationLink", node.props, children)
        "Sheet" -> component("CupertinoSheet", node.props, children)
        "Image" -> {
            val symbol = node.props["systemName"]?.jsonPrimitive?.contentOrNull
            val mappedIcon = symbol?.let(::sfSymbolToCupertinoIconName)
            when {
                mappedIcon != null -> component(
                    "CupertinoIcon",
                    buildJsonObject {
                        put("imageVector", mappedIcon)
                        put("contentDescription", symbol)
                    },
                )
                symbol == "minus.square.fill" || symbol == "minus.square" ->
                    component("CupertinoText", buildJsonObject { put("text", "\u2212") })
                else -> node.copy(children = children)
            }
        }
        "Button" -> {
            val onTap = node.props["onTap"]
            if (onTap != null) {
                val styleMod = node.modifiers.firstOrNull { it.kind == "buttonStyle" }
                    ?.args?.get("style")?.jsonPrimitive?.contentOrNull
                val hasCustomBg = hasCustomVisualBackground(node)
                val effectiveStyle = styleMod ?: if (hasCustomBg) "plain" else null
                val buttonProps = buildMap {
                    put("onClick", onTap)
                    if (effectiveStyle != null) put("style", JsonPrimitive(effectiveStyle))
                    if (effectiveStyle == "plain" || effectiveStyle == "borderless" || hasCustomBg) {
                        put("contentPadding", JsonPrimitive(0))
                    }
                }
                component("CupertinoButton", buttonProps, listOf(slot("content", children)))
            } else {
                node.copy(children = children)
            }
        }
        "Toggle" -> {
            val isOn = node.props["isOn"]
            val onChange = node.props["onChange"]
            if (isOn != null && onChange != null) {
                node.copy(
                    type = "HStack",
                    props = buildJsonObject { put("spacing", 12) },
                    children = children +
                        ViewNode("Spacer", "${node.id}/space") +
                        component(
                            "CupertinoSwitch",
                            mapOf("checked" to isOn, "onCheckedChange" to onChange),
                            emptyList(),
                        ).copy(id = "${node.id}/switch", modifiers = emptyList()),
                )
            } else {
                node.copy(children = children)
            }
        }
        "TextField" -> {
            val text = node.props["text"]
            val onChange = node.props["onChange"]
            if (node.props["secure"] == JsonPrimitive(true) || text == null || onChange == null) {
                node.copy(children = children)
            } else {
                component(
                    "CupertinoBorderedTextField",
                    mapOf("value" to text, "onValueChange" to onChange),
                    listOf(
                        slot(
                            "placeholder",
                            listOf(
                                ViewNode(
                                    type = "Composable",
                                    id = "${node.id}/placeholder/text",
                                    props = buildJsonObject {
                                        put("name", "CupertinoText")
                                        put("text", node.props["placeholder"] ?: JsonPrimitive(""))
                                    },
                                )
                            ),
                        )
                    ),
                )
            }
        }
        "Slider" -> component(
            "CupertinoSlider",
            buildMap {
                node.props["value"]?.let { put("value", it) }
                node.props["onChange"]?.let { put("onValueChange", it) }
                val min = (node.props["min"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                val max = (node.props["max"] as? JsonPrimitive)?.doubleOrNull ?: 1.0
                put("valueRange", buildJsonArray { add(min); add(max) })
            },
            emptyList(),
        )
        "Divider" -> component("CupertinoHorizontalDivider", node.props, emptyList())
        "ProgressView" -> if (node.props["value"] == null) {
            component("CupertinoActivityIndicator", node.props, emptyList())
        } else {
            node.copy(children = children)
        }
        else -> {
            val sheetChild = children.firstOrNull {
                it.type == "Composable" && it.props["name"]?.jsonPrimitive?.contentOrNull == "CupertinoSheet"
            }
            if (sheetChild != null && node.type != "ZStack") {
                ViewNode(
                    type = "ZStack",
                    id = "${node.id}/sheetHost",
                    children = listOf(node.copy(children = children.filterNot { it === sheetChild }), sheetChild),
                )
            } else {
                node.copy(children = children)
            }
        }
    }
}

internal data class ParsedSvgBounds(
    val boundsWidth: Float,
    val boundsHeight: Float,
)

private fun parseNamedOrHexBridgeColor(raw: String): Color? {
    val s = raw.trim().removePrefix("Color.").removePrefix(".")
    parseHexColor(s)?.let { return it }
    return when (s.lowercase()) {
        "blue", "accentcolor" -> Color(0xFF007AFF)
        "red" -> Color(0xFFFF3B30)
        "green" -> Color(0xFF34C759)
        "orange" -> Color(0xFFFF9500)
        "yellow" -> Color(0xFFFFCC00)
        "purple" -> Color(0xFFAF52DE)
        "pink" -> Color(0xFFFF2D55)
        "teal", "cyan" -> Color(0xFF5AC8FA)
        "indigo" -> Color(0xFF5856D6)
        "gray", "grey", "secondary" -> Color(0xFF8E8E93)
        "black", "primary" -> Color(0xFF1C1C1E)
        "white" -> Color.White
        "clear" -> Color.Transparent
        else -> null
    }
}

internal fun parseSvgPath(d: String): ParsedSvgBounds {
    if (d.isBlank()) return ParsedSvgBounds(0f, 0f)
    val tokens = Regex("""[MLHVCSQTAZmlhvcsqtaz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")
        .findAll(d)
        .map { it.value }
        .toList()
    var maxX = 0f
    var maxY = 0f
    var i = 0
    fun nextFloat(): Float = tokens.getOrNull(i++)?.toFloatOrNull() ?: 0f
    while (i < tokens.size) {
        when (tokens[i++]) {
            "M", "L", "m", "l" -> {
                val x = nextFloat()
                val y = nextFloat()
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
            "H", "h" -> {
                val x = nextFloat()
                if (x > maxX) maxX = x
            }
            "V", "v" -> {
                val y = nextFloat()
                if (y > maxY) maxY = y
            }
            "Q", "q" -> {
                val cx = nextFloat()
                val cy = nextFloat()
                val x = nextFloat()
                val y = nextFloat()
                maxX = maxOf(maxX, cx, x)
                maxY = maxOf(maxY, cy, y)
            }
            "C", "c" -> {
                val c1x = nextFloat()
                val c1y = nextFloat()
                val c2x = nextFloat()
                val c2y = nextFloat()
                val x = nextFloat()
                val y = nextFloat()
                maxX = maxOf(maxX, c1x, c2x, x)
                maxY = maxOf(maxY, c1y, c2y, y)
            }
            "A", "a" -> {
                val rx = nextFloat()
                val ry = nextFloat()
                nextFloat()
                nextFloat()
                nextFloat()
                val x = nextFloat()
                val y = nextFloat()
                maxX = maxOf(maxX, x + rx)
                maxY = maxOf(maxY, y + ry)
            }
        }
    }
    return ParsedSvgBounds(maxX, maxY)
}

internal fun buildScaledComposePath(d: String, scale: Float = 1f): Path {
    val path = Path()
    if (d.isBlank()) return path
    val tokens = Regex("""[MLHVCSQTAZmlhvcsqtaz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")
        .findAll(d)
        .map { it.value }
        .toList()
    var curX = 0f
    var curY = 0f
    var i = 0
    fun nextFloat(): Float = (tokens.getOrNull(i++)?.toFloatOrNull() ?: 0f) * scale
    fun nextRawFloat(): Float = tokens.getOrNull(i++)?.toFloatOrNull() ?: 0f
    while (i < tokens.size) {
        when (val cmd = tokens[i++]) {
            "M" -> {
                curX = nextFloat()
                curY = nextFloat()
                path.moveTo(curX, curY)
            }
            "m" -> {
                curX += nextFloat()
                curY += nextFloat()
                path.moveTo(curX, curY)
            }
            "L" -> {
                curX = nextFloat()
                curY = nextFloat()
                path.lineTo(curX, curY)
            }
            "l" -> {
                curX += nextFloat()
                curY += nextFloat()
                path.lineTo(curX, curY)
            }
            "H" -> {
                curX = nextFloat()
                path.lineTo(curX, curY)
            }
            "h" -> {
                curX += nextFloat()
                path.lineTo(curX, curY)
            }
            "V" -> {
                curY = nextFloat()
                path.lineTo(curX, curY)
            }
            "v" -> {
                curY += nextFloat()
                path.lineTo(curX, curY)
            }
            "Q" -> {
                val cx = nextFloat()
                val cy = nextFloat()
                curX = nextFloat()
                curY = nextFloat()
                path.quadraticBezierTo(cx, cy, curX, curY)
            }
            "q" -> {
                val cx = curX + nextFloat()
                val cy = curY + nextFloat()
                curX += nextFloat()
                curY += nextFloat()
                path.quadraticBezierTo(cx, cy, curX, curY)
            }
            "C" -> {
                val c1x = nextFloat()
                val c1y = nextFloat()
                val c2x = nextFloat()
                val c2y = nextFloat()
                curX = nextFloat()
                curY = nextFloat()
                path.cubicTo(c1x, c1y, c2x, c2y, curX, curY)
            }
            "c" -> {
                val c1x = curX + nextFloat()
                val c1y = curY + nextFloat()
                val c2x = curX + nextFloat()
                val c2y = curY + nextFloat()
                curX += nextFloat()
                curY += nextFloat()
                path.cubicTo(c1x, c1y, c2x, c2y, curX, curY)
            }
            "A", "a" -> {
                nextFloat()
                nextFloat()
                nextRawFloat()
                nextRawFloat()
                nextRawFloat()
                val x = nextFloat()
                val y = nextFloat()
                curX = if (cmd == "a") curX + x else x
                curY = if (cmd == "a") curY + y else y
                path.lineTo(curX, curY)
            }
            "Z", "z" -> path.close()
        }
    }
    return path
}

private fun resolveBridgeColor(el: JsonElement?, fallback: Color?): Color? {
    val prim = el as? JsonPrimitive ?: return fallback
    prim.longOrNull?.let { return Color(it.toInt()) }
    val s = prim.contentOrNull?.trim()?.removePrefix(".") ?: return fallback
    parseHexColor(s)?.let { return it }
    return when (s.lowercase()) {
        "blue", "systemblue", "accentcolor" -> Color(0xFF007AFF)
        "red", "systemred" -> Color(0xFFFF3B30)
        "green", "systemgreen" -> Color(0xFF34C759)
        "orange", "systemorange" -> Color(0xFFFF9500)
        "yellow", "systemyellow" -> Color(0xFFFFCC00)
        "purple", "systempurple" -> Color(0xFFAF52DE)
        "pink", "systempink" -> Color(0xFFFF2D55)
        "cyan", "teal", "systemteal" -> Color(0xFF32ADE6)
        "indigo", "systemindigo" -> Color(0xFF5856D6)
        "gray", "grey", "secondary", "systemgray" -> Color(0xFF8E8E93)
        "black", "primary", "label" -> Color.Black
        "white" -> Color.White
        "clear" -> Color.Transparent
        else -> fallback
    }
}

private fun extractFirstTextLabel(node: ViewNode): String? {
    node.props["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
    node.props["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
    for (child in node.children) {
        extractFirstTextLabel(child)?.let { return it }
    }
    return null
}

private fun extractNavTitle(nodes: List<ViewNode>): String? {
    for (n in nodes) {
        val modTitle = n.modifiers.firstOrNull {
            it.kind == "navigationTitle" || it.kind == "navigationBarTitle"
        }?.args?.let { args ->
            args["title"]?.jsonPrimitive?.contentOrNull
                ?: args["value"]?.jsonPrimitive?.contentOrNull
        }
        if (!modTitle.isNullOrBlank()) return modTitle
        n.props["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        extractNavTitle(n.children)?.let { return it }
    }
    return null
}

private fun hasCustomVisualBackground(node: ViewNode): Boolean {
    for (child in node.children) {
        if (child.modifiers.any { it.kind == "background" }) return true
        if (child.type == "Overlay" || child.type == "Shape" || child.type == "LinearGradient" || child.type == "ZStack") return true
        if (hasCustomVisualBackground(child)) return true
    }
    return false
}

private fun extractRootBackgroundColor(node: ViewNode?): Color? {
    if (node == null) return null
    val bgMod = node.modifiers.firstOrNull { it.kind == "background" }?.args?.get("color")
    val modArgb = (bgMod as? JsonPrimitive)?.longOrNull
    if (modArgb != null) return Color(modArgb.toInt())
    if (node.type == "ZStack") {
        val firstChild = node.children.firstOrNull()
        if (firstChild?.type == "Color") {
            val childArgb = (firstChild.props["color"] as? JsonPrimitive)?.longOrNull
            if (childArgb != null) return Color(childArgb.toInt())
        }
    }
    return null
}

private fun sfSymbolToCupertinoIconName(symbol: String): String? {
    val isFilled = symbol.endsWith(".fill")
    val base = if (isFilled) symbol.removeSuffix(".fill") else symbol
    val pascal = base.split('.', '_', '-')
        .filter { it.isNotEmpty() }
        .joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
    val primaryGroup = if (isFilled) "Filled" else "Outlined"
    val fallbackGroup = if (isFilled) "Outlined" else "Filled"
    val primary = "CupertinoIcons.$primaryGroup.$pascal"
    if (primary in cupertinoIconNames) return primary
    val fallback = "CupertinoIcons.$fallbackGroup.$pascal"
    if (fallback in cupertinoIconNames) return fallback
    return null
}

private fun parseHexColor(hexStr: String): Color? {
    val clean = hexStr.trim().removePrefix("#")
    return runCatching {
        when (clean.length) {
            6 -> Color((0xFF000000L or clean.toLong(16)).toInt())
            8 -> Color(clean.toLong(16).toInt())
            else -> null
        }
    }.getOrNull()
}

private fun isDarkHex(hexStr: String): Boolean {
    val color = parseHexColor(hexStr) ?: return false
    val luminance = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    return luminance < 0.45f
}
