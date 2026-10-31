package com.pureswift.swiftui

import androidx.compose.runtime.*
import kotlinx.serialization.json.*

internal val LocalPreviewCupertino = staticCompositionLocalOf { false }

@Composable
fun RenderCupertinoPreview(node: ViewNode) {
    CompositionLocalProvider(LocalPreviewCupertino provides true) { Render(node) }
}

internal fun previewStyle(node: ViewNode, enabled: Boolean) =
    if (enabled) cupertinoPrimitive(node) else node

/** Apply Cupertino styling to ordinary SwiftUI controls, retaining callback ids,
 * structural identity, labels, modifiers, and Swift-owned values.
 */
private fun cupertinoPrimitive(node: ViewNode): ViewNode {
    val children = node.children
    fun component(name: String, props: Map<String, JsonElement>, content: List<ViewNode> = children) =
        node.copy(type = "Composable", props = JsonObject(props + ("name" to JsonPrimitive(name))), children = content)
    fun slot(name: String, content: List<ViewNode>) = ViewNode("CupertinoSlot", "${node.id}/$name",
        props = buildJsonObject { put("name", name) }, children = content)
    return when (node.type) {
        "Text" -> component("CupertinoText", node.props)
        // These two common symbols are sufficient for the pinned upstream
        // counter. The square minus has no shared Cupertino vector, so use a
        // visible minus glyph. Keep other unsupported symbols diagnostic.
        "Image" -> when (node.props["systemName"]?.jsonPrimitive?.content) {
            "plus.square.fill" -> component("CupertinoIcon", buildJsonObject {
                put("imageVector", "CupertinoIcons.Filled.PlusSquare")
                put("contentDescription", "Increment")
            })
            "minus.square.fill" -> component("CupertinoText", buildJsonObject { put("text", "−") })
            else -> node
        }
        "Button" -> component("CupertinoButton", mapOf("onClick" to node.props.getValue("onTap")),
            listOf(slot("content", children)))
        "Toggle" -> node.copy(type = "HStack", props = buildJsonObject { put("spacing", 12) },
            children = children + ViewNode("Spacer", "${node.id}/space") +
                component("CupertinoSwitch", mapOf("checked" to node.props.getValue("isOn"),
                    "onCheckedChange" to node.props.getValue("onChange")), emptyList()).copy(
                    id = "${node.id}/switch", modifiers = emptyList()))
        "TextField" -> if (node.props["secure"] == JsonPrimitive(true)) node else
            component("CupertinoBorderedTextField", mapOf("value" to node.props.getValue("text"),
                "onValueChange" to node.props.getValue("onChange")), listOf(slot("placeholder", listOf(
                    ViewNode("Composable", "${node.id}/placeholder/text", props = buildJsonObject {
                        put("name", "CupertinoText"); put("text", node.props["placeholder"] ?: JsonPrimitive(""))
                    })))))
        else -> node
    }
}
