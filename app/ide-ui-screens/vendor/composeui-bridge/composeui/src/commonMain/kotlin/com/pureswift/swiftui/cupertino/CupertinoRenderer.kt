@file:OptIn(io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi::class, io.github.alexzhirkevich.cupertino.adaptive.ExperimentalAdaptiveApi::class)
package com.pureswift.swiftui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import io.github.alexzhirkevich.cupertino.CupertinoText
import io.github.alexzhirkevich.cupertino.adaptive.AdaptiveTheme
import io.github.alexzhirkevich.cupertino.adaptive.Theme
import kotlinx.serialization.json.*

private val LocalCupertinoBridgeTheme = compositionLocalOf { false }

/** Built-in Cupertino factories retain the same modifier/environment/callback path as custom UI. */
@Composable
internal fun RenderCupertino(node: ViewNode): Boolean {
    val name = node.string("name") ?: return false
    if (!(name.startsWith("Cupertino") || name.startsWith("Adaptive") ||
            name.startsWith("Modifier.") || name.startsWith("TabRowDefaults.") || name.startsWith("rememberCupertino") || name.startsWith("CupertinoMenuScope.") || name.startsWith("SectionScope.") || name.startsWith("LazySectionScope.") ||
            name.startsWith("LazyListScope.") || name.startsWith("RowScope.") ||
            name.startsWith("AlertDialogActionsScope.") || name.startsWith("NativeAlertDialogActionsScope.") ||
            name in setOf("SystemBarAppearance", "SharedSwipeBoxController", "ProvideTextStyle", "ProvideSectionStyle", "Surface", "NativeChildren", "UIKitChildren"))) return false
    var handled = false
    val content: @Composable () -> Unit = {
        handled = RenderCupertinoPlatform(node) || RenderCupertinoBasic(node) || RenderCupertinoTextEntry(node) ||
            RenderCupertinoLayout(node) || RenderCupertinoPickers(node) ||
            RenderCupertinoPresentation(node) || RenderCupertinoSections(node) ||
            RenderCupertinoNavigation(node)
        if (!handled && name in setOf("CupertinoColorPickerNative", "CupertinoPickerNative", "UIKitChildren", "CupertinoIcons.named")) {
            CupertinoText("This control is unavailable on this device.")
            handled = true
        }
    }
    if (LocalCupertinoBridgeTheme.current) content() else {
        val scheme = if (LocalAppearanceIsDark.current) io.github.alexzhirkevich.cupertino.theme.darkColorScheme()
            else io.github.alexzhirkevich.cupertino.theme.lightColorScheme()
        AdaptiveTheme(
            target = Theme.Cupertino,
            material = io.github.alexzhirkevich.cupertino.adaptive.MaterialThemeSpec.Default(),
            cupertino = io.github.alexzhirkevich.cupertino.adaptive.CupertinoThemeSpec.Default(colorScheme = scheme),
        ) {
            CompositionLocalProvider(
                LocalCupertinoBridgeTheme provides true,
                androidx.compose.material3.LocalContentColor provides scheme.label,
                io.github.alexzhirkevich.LocalContentColor provides scheme.label,
            ) { content() }
        }
    }
    return handled
}

internal fun cupertinoSlotNodes(node: ViewNode, name: String): List<ViewNode> =
    node.children.filter { it.type == "CupertinoSlot" && it.string("name") == name }.flatMap { it.children }
        .ifEmpty { if (name == "content") node.children.filter { it.type != "CupertinoSlot" } else emptyList() }

internal fun cupertinoHasSlot(node: ViewNode, name: String): Boolean = cupertinoSlotNodes(node, name).isNotEmpty()

@Composable
internal fun cupertinoSlot(node: ViewNode, name: String = "content") {
    for (child in cupertinoSlotNodes(node, name)) key(child.id) { RenderChild(child) }
}

@Composable
internal fun cupertinoColor(node: ViewNode, key: String): Color? =
    resolveAppearanceColor(node.props[key], LocalAppearanceIsDark.current)

@Composable
internal fun cupertinoEnabled(node: ViewNode): Boolean = (node.bool("enabled") ?: true) && !LocalInheritedDisabled.current

internal fun cupertinoValue(node: ViewNode, key: String): JsonElement? {
    val raw = node.props[key] ?: return null
    return if (raw is JsonPrimitive && raw.isString && (raw.content.startsWith("{") || raw.content.startsWith("[")))
        runCatching { Json.parseToJsonElement(raw.content) }.getOrNull() ?: raw else raw
}
internal fun cupertinoObject(node: ViewNode, key: String): JsonObject? = cupertinoValue(node, key) as? JsonObject
internal fun cupertinoArray(node: ViewNode, key: String): JsonArray? = cupertinoValue(node, key) as? JsonArray
internal fun cupertinoStringEvent(node: ViewNode, name: String, payload: JsonElement) {
    node.long(name)?.let { SwiftBridge.sink.invokeString(it, payload.toString()) }
}
