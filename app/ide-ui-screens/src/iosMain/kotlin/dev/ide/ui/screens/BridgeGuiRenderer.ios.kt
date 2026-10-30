package dev.ide.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private val iosBridgeJson = Json { ignoreUnknownKeys = true }

private data class IosNavEntry(
    val title: String,
    val destination: IosViewNode,
)

private class IosNavigatorState {
    val backStack = mutableStateListOf<IosNavEntry>()

    fun push(title: String, destination: IosViewNode) {
        backStack.add(IosNavEntry(title, destination))
    }

    fun pop() {
        if (backStack.isNotEmpty()) {
            backStack.removeAt(backStack.lastIndex)
        }
    }
}

private val LocalIosNavigator = compositionLocalOf<IosNavigatorState?> { null }

@Serializable
internal data class IosViewModifier(
    val kind: String = "",
    val args: JsonObject = JsonObject(emptyMap()),
)

@Serializable
internal data class IosViewNode(
    val type: String = "VStack",
    val id: String = "",
    val props: JsonObject = JsonObject(emptyMap()),
    val modifiers: List<IosViewModifier> = emptyList(),
    val children: List<IosViewNode> = emptyList(),
)

@Composable
actual fun BridgeGuiWindow(
    rawJson: String,
    moduleName: String,
    mainClass: String,
    onSendCallback: (String) -> Unit,
    modifier: Modifier,
) {
    val parsedNode = remember(rawJson) {
        runCatching { iosBridgeJson.decodeFromString<IosViewNode>(rawJson) }.getOrNull()
    }

    val windowBgProp = parsedNode?.props?.get("windowBackground")?.jsonPrimitive?.contentOrNull
    val windowTitle = parsedNode?.props?.get("windowTitle")?.jsonPrimitive?.contentOrNull
        ?: moduleName.ifBlank { mainClass }
    val contentBg = windowBgProp?.let(::parseIosHexColor) ?: Color(0xFFF2F2F7)
    val isDark = (0.299f * contentBg.red + 0.587f * contentBg.green + 0.114f * contentBg.blue) < 0.45f
    val primaryText = if (isDark) Color.White else Color(0xFF1C1C1E)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(contentBg),
    ) {
        // Simulated guest app status bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(contentBg)
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "9:41",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = primaryText,
            )
            Text(
                text = windowTitle,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = primaryText.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(4) { bar ->
                    Box(
                        Modifier
                            .size(width = 3.dp, height = (6 + bar * 2).dp)
                            .background(primaryText, RoundedCornerShape(1.dp)),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (parsedNode != null) {
                RenderIosViewNode(
                    node = parsedNode,
                    isDark = isDark,
                    onSendCallback = onSendCallback,
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF007AFF),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = windowTitle.ifBlank { "App" },
                        style = MaterialTheme.typography.titleMedium,
                        color = primaryText,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 8.dp)
                .width(120.dp)
                .height(4.dp)
                .background(primaryText.copy(alpha = 0.30f), RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun RenderIosViewNode(
    node: IosViewNode,
    isDark: Boolean,
    onSendCallback: (String) -> Unit,
) {
    val mod = applyIosModifiers(Modifier, node.modifiers)
    val compName = if (node.type == "Composable") {
        node.props["name"]?.jsonPrimitive?.contentOrNull ?: ""
    } else {
        node.type
    }

    when (compName) {
        "ScrollView", "List", "Form" -> {
            val scrollState = rememberScrollState()
            Column(
                modifier = mod
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                node.children.forEach { child ->
                    RenderIosViewNode(child, isDark, onSendCallback)
                }
            }
        }
        "NavigationStack", "NavigationView", "NavStack", "CupertinoNavigationStack" -> {
            val navState = remember { IosNavigatorState() }
            val rootTitle = node.props["title"]?.jsonPrimitive?.contentOrNull
                ?: extractIosNavTitle(node.children)
            val sheetChild = node.children.firstOrNull { it.type == "Sheet" }
            val mainChildren = node.children.filterNot { it === sheetChild }
            CompositionLocalProvider(LocalIosNavigator provides navState) {
                Box(modifier = mod.fillMaxSize()) {
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
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (currentEntry != null) {
                                RenderIosViewNode(currentEntry.destination, isDark, onSendCallback)
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    mainChildren.forEach { child ->
                                        RenderIosViewNode(child, isDark, onSendCallback)
                                    }
                                }
                            }
                        }
                    }
                    if (sheetChild != null) {
                        RenderIosViewNode(sheetChild, isDark, onSendCallback)
                    }
                }
            }
        }
        "NavigationLink", "CupertinoNavigationLink" -> {
            val navigator = LocalIosNavigator.current
            val onTapId = node.props["onTap"]?.jsonPrimitive?.longOrNull
            val explicitTitle = node.props["title"]?.jsonPrimitive?.contentOrNull
            val destChild = node.children.firstOrNull {
                it.props["navigationRole"]?.jsonPrimitive?.contentOrNull == "destination"
            } ?: when {
                explicitTitle != null && node.children.isNotEmpty() -> node.children.first()
                node.children.size >= 2 -> node.children.first()
                else -> null
            }
            val labelChildren = node.children.filter { it !== destChild }
            val linkTitle = explicitTitle
                ?: labelChildren.firstNotNullOfOrNull { extractIosFirstText(it) }
                ?: "Details"
            Row(
                modifier = mod
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button) {
                        if (onTapId != null) {
                            onSendCallback(
                                buildJsonObject {
                                    put("id", onTapId.toString())
                                    put("kind", "void")
                                }.toString(),
                            )
                        }
                        if (destChild != null && navigator != null) {
                            val destTitle = extractIosNavTitle(listOf(destChild)) ?: linkTitle
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
                            RenderIosViewNode(lc, isDark, onSendCallback)
                        }
                    }
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
        "TabView", "CupertinoTabView" -> {
            val remoteSelected = node.props["selectedIndex"]?.jsonPrimitive?.intOrNull ?: 0
            val onSelectId = node.props["onSelect"]?.jsonPrimitive?.longOrNull
            var selectedTab by remember { mutableIntStateOf(remoteSelected) }
            LaunchedEffect(remoteSelected) {
                selectedTab = remoteSelected
            }
            val safeIdx = selectedTab.coerceIn(0, (node.children.size - 1).coerceAtLeast(0))
            Column(modifier = mod.fillMaxSize()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    node.children.getOrNull(safeIdx)?.let { activeChild ->
                        RenderIosViewNode(activeChild, isDark, onSendCallback)
                    }
                }
                if (node.children.isNotEmpty()) {
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
                        node.children.forEachIndexed { idx, child ->
                            val tabMod = child.modifiers.firstOrNull { it.kind == "tabItem" }
                            val label = tabMod?.args?.get("label")?.jsonPrimitive?.contentOrNull
                                ?: extractIosFirstText(child)
                                ?: "Tab ${idx + 1}"
                            val isSelected = idx == safeIdx
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(role = Role.Tab) {
                                        selectedTab = idx
                                        if (onSelectId != null) {
                                            onSendCallback(
                                                buildJsonObject {
                                                    put("id", onSelectId.toString())
                                                    put("kind", "int")
                                                    put("value", JsonPrimitive(idx))
                                                }.toString(),
                                            )
                                        }
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
        "Sheet", "CupertinoSheet" -> {
            val remotePresented = node.props["isPresented"]?.jsonPrimitive?.booleanOrNull ?: false
            val onDismissId = node.props["onDismiss"]?.jsonPrimitive?.longOrNull
            var presented by remember(remotePresented) { mutableStateOf(remotePresented) }
            if (presented && node.children.isNotEmpty()) {
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
                                            if (onDismissId != null) {
                                                onSendCallback(
                                                    buildJsonObject {
                                                        put("id", onDismissId.toString())
                                                        put("kind", "void")
                                                    }.toString(),
                                                )
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                node.children.forEach { child ->
                                    RenderIosViewNode(child, isDark, onSendCallback)
                                }
                            }
                        }
                    }
                }
            }
        }
        "Path" -> {
            val d = node.props["d"]?.jsonPrimitive?.contentOrNull
                ?: node.props["svgPath"]?.jsonPrimitive?.contentOrNull
                ?: node.props["commands"]?.jsonPrimitive?.contentOrNull
                ?: ""
            val fillColor = resolveIosBridgeColor(node.props["fill"], null)
            val strokeColor = resolveIosBridgeColor(node.props["stroke"], null)
            val strokeWidth = node.props["strokeWidth"]?.jsonPrimitive?.floatOrNull ?: 2f
            val (bw, bh) = remember(d) { parseIosSvgBounds(d) }
            val defaultW = (bw.takeIf { it > 0f } ?: 120f).coerceIn(24f, 360f)
            val defaultH = (bh.takeIf { it > 0f } ?: 120f).coerceIn(24f, 360f)
            Canvas(
                modifier = mod
                    .defaultMinSize(minWidth = defaultW.dp, minHeight = defaultH.dp)
                    .size(width = defaultW.dp, height = defaultH.dp),
            ) {
                val scaleX = if (bw > 0f) size.width / bw else 1f
                val scaleY = if (bh > 0f) size.height / bh else 1f
                val scale = minOf(scaleX, scaleY).takeIf { it.isFinite() && it > 0f } ?: 1f
                val path = buildIosComposePath(d, scale)
                if (fillColor != null) {
                    drawPath(path = path, color = fillColor, style = Fill)
                }
                val effectiveStroke = strokeColor
                    ?: if (fillColor == null) (if (isDark) Color.White else Color(0xFF007AFF)) else null
                if (effectiveStroke != null) {
                    drawPath(
                        path = path,
                        color = effectiveStroke,
                        style = Stroke(width = strokeWidth * density, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
        "Circle", "Rectangle", "RoundedRectangle", "Capsule", "Shape", "CupertinoShape" -> {
            val d = node.props["d"]?.jsonPrimitive?.contentOrNull
                ?: node.props["svgPath"]?.jsonPrimitive?.contentOrNull
            if (!d.isNullOrBlank()) {
                val fillColor = resolveIosBridgeColor(node.props["fill"], null)
                val strokeColor = resolveIosBridgeColor(node.props["stroke"], null)
                val strokeWidth = node.props["strokeWidth"]?.jsonPrimitive?.floatOrNull ?: 2f
                Canvas(modifier = mod.defaultMinSize(minWidth = 80.dp, minHeight = 80.dp)) {
                    val path = buildIosComposePath(d, 1f)
                    if (fillColor != null) drawPath(path = path, color = fillColor, style = Fill)
                    val effectiveStroke = strokeColor ?: if (fillColor == null) Color(0xFF007AFF) else null
                    if (effectiveStroke != null) {
                        drawPath(path = path, color = effectiveStroke, style = Stroke(width = strokeWidth * density))
                    }
                }
            } else {
                val shapeKind = (node.props["shapeType"]?.jsonPrimitive?.contentOrNull ?: compName).lowercase()
                val cornerRadius = node.props["cornerRadius"]?.jsonPrimitive?.floatOrNull ?: 12f
                val fillColor = resolveIosBridgeColor(node.props["fill"] ?: node.props["color"], null)
                val strokeColor = resolveIosBridgeColor(node.props["stroke"], null)
                val strokeWidth = node.props["strokeWidth"]?.jsonPrimitive?.floatOrNull ?: 2f
                val effectiveFill = fillColor ?: if (strokeColor == null) Color(0xFF007AFF) else null
                Canvas(modifier = mod.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) {
                    when (shapeKind) {
                        "circle" -> {
                            val r = minOf(size.width, size.height) / 2f
                            if (effectiveFill != null) drawCircle(color = effectiveFill, radius = r)
                            if (strokeColor != null) drawCircle(color = strokeColor, radius = r, style = Stroke(width = strokeWidth * density))
                        }
                        "capsule" -> {
                            val cr = minOf(size.width, size.height) / 2f
                            if (effectiveFill != null) drawRoundRect(color = effectiveFill, cornerRadius = CornerRadius(cr, cr))
                            if (strokeColor != null) drawRoundRect(color = strokeColor, cornerRadius = CornerRadius(cr, cr), style = Stroke(width = strokeWidth * density))
                        }
                        "roundedrectangle" -> {
                            val cr = cornerRadius * density
                            if (effectiveFill != null) drawRoundRect(color = effectiveFill, cornerRadius = CornerRadius(cr, cr))
                            if (strokeColor != null) drawRoundRect(color = strokeColor, cornerRadius = CornerRadius(cr, cr), style = Stroke(width = strokeWidth * density))
                        }
                        else -> {
                            if (effectiveFill != null) drawRect(color = effectiveFill, size = Size(size.width, size.height))
                            if (strokeColor != null) drawRect(color = strokeColor, size = Size(size.width, size.height), style = Stroke(width = strokeWidth * density))
                        }
                    }
                }
            }
        }
        "VStack", "Section", "Group" -> {
            val spacing = node.props["spacing"]?.jsonPrimitive?.intOrNull ?: 8
            val alignStr = node.props["alignment"]?.jsonPrimitive?.contentOrNull ?: "leading"
            val alignment = when (alignStr) {
                "center" -> Alignment.CenterHorizontally
                "trailing" -> Alignment.End
                else -> Alignment.Start
            }
            val isCardSection = node.type == "Section" || node.props["card"]?.jsonPrimitive?.booleanOrNull == true
            val containerMod = if (isCardSection) {
                mod
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isDark) Color(0xFF2C2C2E) else Color.White)
                    .border(1.dp, if (isDark) Color(0xFF3A3A3C) else Color(0xFFE5E5EA), RoundedCornerShape(14.dp))
                    .padding(14.dp)
            } else {
                mod
            }
            Column(
                modifier = containerMod,
                verticalArrangement = Arrangement.spacedBy(spacing.dp),
                horizontalAlignment = alignment,
            ) {
                node.children.forEach { child ->
                    RenderIosViewNode(child, isDark, onSendCallback)
                }
            }
        }
        "HStack" -> {
            val spacing = node.props["spacing"]?.jsonPrimitive?.intOrNull ?: 8
            Row(
                modifier = mod,
                horizontalArrangement = Arrangement.spacedBy(spacing.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                node.children.forEach { child ->
                    if (child.type == "Spacer") {
                        Spacer(Modifier.weight(1f))
                    } else {
                        RenderIosViewNode(child, isDark, onSendCallback)
                    }
                }
            }
        }
        "ZStack", "Overlay", "CupertinoSlot" -> {
            Box(
                modifier = mod,
                contentAlignment = Alignment.Center,
            ) {
                node.children.forEach { child ->
                    RenderIosViewNode(child, isDark, onSendCallback)
                }
            }
        }
        "Text", "CupertinoText", "Label" -> {
            val text = node.props["text"]?.jsonPrimitive?.contentOrNull
                ?: node.props["title"]?.jsonPrimitive?.contentOrNull
                ?: ""
            val styleObj = node.props["style"] as? JsonObject
            val fontSize = styleObj?.get("fontSize")?.jsonPrimitive?.intOrNull
                ?: node.props["fontSize"]?.jsonPrimitive?.intOrNull
                ?: 14
            val weightStr = styleObj?.get("fontWeight")?.jsonPrimitive?.contentOrNull
                ?: node.props["fontWeight"]?.jsonPrimitive?.contentOrNull
                ?: "normal"
            val fontWeight = when (weightStr.lowercase()) {
                "bold", "heavy", "black" -> FontWeight.Bold
                "semibold", "medium" -> FontWeight.SemiBold
                else -> FontWeight.Normal
            }
            val colorLong = styleObj?.get("color")?.jsonPrimitive?.longOrNull
                ?: node.props["color"]?.jsonPrimitive?.longOrNull
                ?: node.modifiers.firstOrNull { it.kind == "foregroundColor" }?.args?.get("color")?.jsonPrimitive?.longOrNull
            val color = colorLong?.let { Color(it.toInt()) }
                ?: if (isDark) Color.White else Color(0xFF1C1C1E)
            val isMono = styleObj?.get("fontFamily")?.jsonPrimitive?.contentOrNull == "monospace"
            Text(
                text = text,
                fontSize = fontSize.sp,
                fontWeight = fontWeight,
                fontFamily = if (isMono) FontFamily.Monospace else FontFamily.Default,
                color = color,
                modifier = mod,
            )
        }
        "Button", "CupertinoButton" -> {
            val callbackId = node.props["onClick"]?.jsonPrimitive?.longOrNull
                ?: node.props["onTap"]?.jsonPrimitive?.longOrNull
                ?: 1L
            val containerArgb = node.props["containerColor"]?.jsonPrimitive?.longOrNull
            val contentArgb = node.props["contentColor"]?.jsonPrimitive?.longOrNull
            val containerColor = containerArgb?.let { Color(it.toInt()) } ?: Color(0xFF007AFF)
            val contentColor = contentArgb?.let { Color(it.toInt()) } ?: Color.White
            val labelText = node.props["label"]?.jsonPrimitive?.contentOrNull
            Button(
                onClick = {
                    val payload = buildJsonObject {
                        put("id", callbackId.toString())
                        put("kind", "void")
                    }.toString()
                    onSendCallback(payload)
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = containerColor,
                    contentColor = contentColor,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = mod,
            ) {
                if (!labelText.isNullOrBlank() && node.children.isEmpty()) {
                    Text(
                        text = labelText,
                        color = contentColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    node.children.forEach { child ->
                        RenderIosViewNode(child, isDark = true, onSendCallback = onSendCallback)
                    }
                }
            }
        }
        "Toggle", "CupertinoSwitch" -> {
            val initial = node.props["checked"]?.jsonPrimitive?.booleanOrNull
                ?: node.props["isOn"]?.jsonPrimitive?.booleanOrNull
                ?: true
            var checked by remember(node.id, initial) { mutableStateOf(initial) }
            val callbackId = node.props["onCheckedChange"]?.jsonPrimitive?.longOrNull
                ?: node.props["onChange"]?.jsonPrimitive?.longOrNull
                ?: 2L
            val label = node.props["label"]?.jsonPrimitive?.contentOrNull
            Row(
                modifier = mod.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (!label.isNullOrBlank()) {
                    Text(
                        text = label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isDark) Color.White else Color(0xFF1C1C1E),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    node.children.forEach { child ->
                        RenderIosViewNode(child, isDark, onSendCallback)
                    }
                }
                Switch(
                    checked = checked,
                    onCheckedChange = { next ->
                        checked = next
                        val payload = buildJsonObject {
                            put("id", callbackId.toString())
                            put("kind", "bool")
                            put("value", JsonPrimitive(next))
                        }.toString()
                        onSendCallback(payload)
                    },
                )
            }
        }
        "TextField", "CupertinoBorderedTextField" -> {
            val initial = node.props["value"]?.jsonPrimitive?.contentOrNull
                ?: node.props["text"]?.jsonPrimitive?.contentOrNull
                ?: ""
            val placeholder = node.props["placeholder"]?.jsonPrimitive?.contentOrNull ?: "Enter value..."
            val callbackId = node.props["onValueChange"]?.jsonPrimitive?.longOrNull
                ?: node.props["onChange"]?.jsonPrimitive?.longOrNull
            var textVal by remember(node.id, initial) { mutableStateOf(initial) }
            OutlinedTextField(
                value = textVal,
                onValueChange = { next ->
                    textVal = next
                    if (callbackId != null) {
                        onSendCallback(
                            buildJsonObject {
                                put("id", callbackId.toString())
                                put("kind", "string")
                                put("value", JsonPrimitive(next))
                            }.toString(),
                        )
                    }
                },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = mod.fillMaxWidth(),
            )
        }
        "Slider", "CupertinoSlider" -> {
            val initial = node.props["value"]?.jsonPrimitive?.floatOrNull ?: 0.5f
            val callbackId = node.props["onValueChange"]?.jsonPrimitive?.longOrNull
                ?: node.props["onChange"]?.jsonPrimitive?.longOrNull
            var sliderVal by remember(node.id, initial) { mutableFloatStateOf(initial) }
            Slider(
                value = sliderVal,
                onValueChange = { next ->
                    sliderVal = next
                    if (callbackId != null) {
                        onSendCallback(
                            buildJsonObject {
                                put("id", callbackId.toString())
                                put("kind", "double")
                                put("value", JsonPrimitive(next.toDouble()))
                            }.toString(),
                        )
                    }
                },
                modifier = mod.fillMaxWidth(),
            )
        }
        "Divider", "CupertinoHorizontalDivider" -> {
            HorizontalDivider(
                modifier = mod.fillMaxWidth(),
                color = if (isDark) Color(0xFF38383A) else Color(0xFFD1D1D6),
            )
        }
        "ProgressView", "CupertinoActivityIndicator" -> {
            CircularProgressIndicator(
                modifier = mod.size(22.dp),
                strokeWidth = 2.dp,
                color = Color(0xFF007AFF),
            )
        }
        "Spacer" -> {
            val h = node.props["height"]?.jsonPrimitive?.intOrNull ?: 8
            Spacer(mod.height(h.dp))
        }
        else -> {
            if (node.children.isNotEmpty()) {
                Column(
                    modifier = mod,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    node.children.forEach { child ->
                        RenderIosViewNode(child, isDark, onSendCallback)
                    }
                }
            }
        }
    }
}

private fun parseIosSvgBounds(d: String): Pair<Float, Float> {
    if (d.isBlank()) return 0f to 0f
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
                nextFloat(); nextFloat(); nextFloat()
                val x = nextFloat()
                val y = nextFloat()
                maxX = maxOf(maxX, x + rx)
                maxY = maxOf(maxY, y + ry)
            }
        }
    }
    return maxX to maxY
}

private fun buildIosComposePath(d: String, scale: Float = 1f): Path {
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
            "M" -> { curX = nextFloat(); curY = nextFloat(); path.moveTo(curX, curY) }
            "m" -> { curX += nextFloat(); curY += nextFloat(); path.moveTo(curX, curY) }
            "L" -> { curX = nextFloat(); curY = nextFloat(); path.lineTo(curX, curY) }
            "l" -> { curX += nextFloat(); curY += nextFloat(); path.lineTo(curX, curY) }
            "H" -> { curX = nextFloat(); path.lineTo(curX, curY) }
            "h" -> { curX += nextFloat(); path.lineTo(curX, curY) }
            "V" -> { curY = nextFloat(); path.lineTo(curX, curY) }
            "v" -> { curY += nextFloat(); path.lineTo(curX, curY) }
            "Q" -> { val cx = nextFloat(); val cy = nextFloat(); curX = nextFloat(); curY = nextFloat(); path.quadraticBezierTo(cx, cy, curX, curY) }
            "q" -> { val cx = curX + nextFloat(); val cy = curY + nextFloat(); curX += nextFloat(); curY += nextFloat(); path.quadraticBezierTo(cx, cy, curX, curY) }
            "C" -> { val c1x = nextFloat(); val c1y = nextFloat(); val c2x = nextFloat(); val c2y = nextFloat(); curX = nextFloat(); curY = nextFloat(); path.cubicTo(c1x, c1y, c2x, c2y, curX, curY) }
            "c" -> { val c1x = curX + nextFloat(); val c1y = curY + nextFloat(); val c2x = curX + nextFloat(); val c2y = curY + nextFloat(); curX += nextFloat(); curY += nextFloat(); path.cubicTo(c1x, c1y, c2x, c2y, curX, curY) }
            "A", "a" -> {
                nextFloat(); nextFloat(); nextRawFloat(); nextRawFloat(); nextRawFloat()
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

private fun resolveIosBridgeColor(el: JsonElement?, fallback: Color?): Color? {
    val prim = el as? JsonPrimitive ?: return fallback
    prim.longOrNull?.let { return Color(it.toInt()) }
    val s = prim.contentOrNull?.trim()?.removePrefix(".") ?: return fallback
    parseIosHexColor(s)?.let { return it }
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

private fun extractIosFirstText(node: IosViewNode): String? {
    node.props["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
    node.props["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
    for (child in node.children) {
        extractIosFirstText(child)?.let { return it }
    }
    return null
}

private fun extractIosNavTitle(nodes: List<IosViewNode>): String? {
    for (n in nodes) {
        val modTitle = n.modifiers.firstOrNull {
            it.kind == "navigationTitle" || it.kind == "navigationBarTitle"
        }?.args?.let { args ->
            args["title"]?.jsonPrimitive?.contentOrNull
                ?: args["value"]?.jsonPrimitive?.contentOrNull
        }
        if (!modTitle.isNullOrBlank()) return modTitle
        n.props["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        extractIosNavTitle(n.children)?.let { return it }
    }
    return null
}

private fun applyIosModifiers(base: Modifier, modifiers: List<IosViewModifier>): Modifier {
    var current = base
    for (m in modifiers) {
        when (m.kind) {
            "frame" -> {
                if (m.args["fillWidth"]?.jsonPrimitive?.booleanOrNull == true ||
                    m.args["maxWidth"]?.jsonPrimitive?.contentOrNull == "infinity"
                ) {
                    current = current.fillMaxWidth()
                }
                m.args["width"]?.jsonPrimitive?.intOrNull?.let { w ->
                    if (w > 0) current = current.width(w.dp)
                }
                m.args["height"]?.jsonPrimitive?.intOrNull?.let { h ->
                    if (h > 0) current = current.height(h.dp)
                }
            }
            "padding" -> {
                val all = m.args["all"]?.jsonPrimitive?.intOrNull
                val horiz = m.args["horizontal"]?.jsonPrimitive?.intOrNull
                val vert = m.args["vertical"]?.jsonPrimitive?.intOrNull
                val top = m.args["top"]?.jsonPrimitive?.intOrNull ?: vert ?: all ?: 0
                val bottom = m.args["bottom"]?.jsonPrimitive?.intOrNull ?: vert ?: all ?: 0
                val start = m.args["leading"]?.jsonPrimitive?.intOrNull ?: horiz ?: all ?: 0
                val end = m.args["trailing"]?.jsonPrimitive?.intOrNull ?: horiz ?: all ?: 0
                current = current.padding(start = start.dp, top = top.dp, end = end.dp, bottom = bottom.dp)
            }
            "background" -> {
                val colorLong = m.args["color"]?.jsonPrimitive?.longOrNull
                val radius = m.args["cornerRadius"]?.jsonPrimitive?.intOrNull ?: 0
                if (colorLong != null) {
                    val c = Color(colorLong.toInt())
                    current = if (radius > 0) {
                        current.clip(RoundedCornerShape(radius.dp)).background(c)
                    } else {
                        current.background(c)
                    }
                }
            }
            "cornerRadius" -> {
                val r = m.args["radius"]?.jsonPrimitive?.intOrNull ?: 8
                current = current.clip(RoundedCornerShape(r.dp))
            }
        }
    }
    return current
}

private fun parseIosHexColor(hexStr: String): Color? {
    val clean = hexStr.trim().removePrefix("#")
    return runCatching {
        when (clean.length) {
            6 -> Color((0xFF000000L or clean.toLong(16)).toInt())
            8 -> Color(clean.toLong(16).toInt())
            else -> null
        }
    }.getOrNull()
}
