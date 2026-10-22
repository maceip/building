package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pureswift.swiftui.CallbackSink
import com.pureswift.swiftui.Render
import com.pureswift.swiftui.SwiftBridge
import com.pureswift.swiftui.ViewNode
import com.pureswift.swiftui.cupertinoIconNames
import io.github.alexzhirkevich.cupertino.theme.CupertinoTheme
import io.github.alexzhirkevich.cupertino.theme.darkColorScheme
import io.github.alexzhirkevich.cupertino.theme.lightColorScheme
import kotlinx.serialization.json.*

private val bridgeJson = Json { ignoreUnknownKeys = true }

@Composable
actual fun BridgeGuiWindow(
    rawJson: String,
    moduleName: String,
    mainClass: String,
    onSendCallback: (String) -> Unit,
    modifier: Modifier,
) {
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
    val children = node.children.map(::cupertino)
    fun component(name: String, props: Map<String, JsonElement>, content: List<ViewNode> = children) =
        node.copy(
            type = "Composable",
            props = JsonObject(props + ("name" to JsonPrimitive(name))),
            children = content,
        )
    fun slot(name: String, content: List<ViewNode>) = ViewNode(
        type = "CupertinoSlot",
        id = "${node.id}/$name",
        props = buildJsonObject { put("name", name) },
        children = content,
    )
    return when (node.type) {
        "Text" -> component("CupertinoText", node.props)
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
        else -> node.copy(children = children)
    }
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
