package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
        "VStack", "NavigationStack", "NavigationView", "Section", "Group" -> {
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
            var textVal by remember(node.id) { mutableStateOf(initial) }
            OutlinedTextField(
                value = textVal,
                onValueChange = { textVal = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = mod.fillMaxWidth(),
            )
        }
        "Slider", "CupertinoSlider" -> {
            val initial = node.props["value"]?.jsonPrimitive?.floatOrNull ?: 0.5f
            var sliderVal by remember(node.id) { mutableFloatStateOf(initial) }
            Slider(
                value = sliderVal,
                onValueChange = { sliderVal = it },
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
