@file:OptIn(com.arkivanov.decompose.ExperimentalDecomposeApi::class,
    io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi::class)
package com.pureswift.swiftui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.router.stack.*
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.lifecycle.*
import io.github.alexzhirkevich.cupertino.CupertinoText
import io.github.alexzhirkevich.cupertino.cupertinoTween
import io.github.alexzhirkevich.cupertino.decompose.*
import kotlinx.serialization.json.*

/** Swift owns the path; Decompose owns each entry's retained child lifecycle. */
internal class CupertinoNavigationState(initial: List<String>) {
    val lifecycle = LifecycleRegistry()
    val dispatcher = BackDispatcher()
    val navigation = StackNavigation<String>()
    private val context = DefaultComponentContext(lifecycle = lifecycle, backHandler = dispatcher)
    val stack = context.childStack(source = navigation, serializer = null,
        initialStack = { initial }, handleBackButton = false,
        childFactory = { id, childContext -> CupertinoNavigationChild(id, childContext) })
    fun replace(ids: List<String>) {
        if (stack.value.items.map { it.configuration } != ids) navigation.navigate { ids }
    }
}
internal data class CupertinoNavigationChild(val id: String, val context: com.arkivanov.decompose.ComponentContext)

@Composable
internal expect fun PlatformCupertinoBackHandler(enabled: Boolean, predictive: Boolean,
    dispatcher: BackDispatcher, onBack: () -> Unit)

@Composable
internal fun RenderCupertinoNavigation(node: ViewNode): Boolean {
    val name = node.string("name") ?: return false
    when (name) {
        "Modifier.cupertinoPredictiveEnter", "Modifier.cupertinoPredictiveExit" -> {
            val progress = (node.double("progress") ?: 0.0).toFloat().coerceIn(0f, 1f)
            val modifier = if (name.endsWith("Enter")) node.composeModifiers().cupertinoPredictiveEnter { progress }
                else node.composeModifiers().cupertinoPredictiveExit { progress }
            Box(modifier) { cupertinoSlot(node) }
        }
        "NativeChildren", "CupertinoNavigationStack" -> {
            val entries = cupertinoArray(node, "entries")?.mapNotNull { it as? JsonObject } ?: emptyList()
            val ids = entries.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }
            if (ids.isEmpty() || ids.size != entries.size || ids.distinct().size != ids.size || ids.any { '/' in it }) {
                CupertinoText("Navigation is unavailable.")
                return true
            }
            val holder = remember(node.id) { CupertinoNavigationState(ids) }
            DisposableEffect(holder) {
                holder.lifecycle.resume()
                onDispose { holder.lifecycle.destroy() }
            }
            LaunchedEffect(holder, ids) { holder.replace(ids) }
            val currentNode by rememberUpdatedState(node)
            val enabled = cupertinoEnabled(node) && ids.size > 1
            val onBack: () -> Unit = { if (enabled) currentNode.long("onBack")?.let { SwiftBridge.sink.invokeVoid(it) } }
            val mode = node.string("animation") ?: "predictive"
            val fallback = stackAnimation<String, CupertinoNavigationChild>(animator = cupertinoStackAnimator(
                cupertinoTween(durationMillis = (node.long("durationMillis") ?: 500L).toInt().coerceAtLeast(0))))
            val animation = when (mode) {
                "none" -> null
                "stack" -> fallback
                else -> cupertinoPredictiveBackAnimation(holder.dispatcher, onBack, fallback)
            }
            PlatformCupertinoBackHandler(enabled, mode == "predictive", holder.dispatcher, onBack)
            NativeChildren(stack = holder.stack, onBack = onBack,
                modifier = node.composeModifiers().onPreviewKeyEvent {
                    if (enabled && it.type == KeyEventType.KeyUp && it.key == Key.Escape) { onBack(); true } else false
                }, animation = animation) { child ->
                val index = ids.indexOf(child.configuration)
                val slot = if (cupertinoHasSlot(node, "entry_${child.configuration}")) "entry_${child.configuration}" else "entry_$index"
                key(child.configuration) {
                    val current = cupertinoSlotNodes(node, slot)
                    var retained by remember { mutableStateOf(current) }
                    if (index >= 0) SideEffect { retained = current }
                    // Decompose retains outgoing composition during its animation,
                    // even though Swift has already removed that entry's slot.
                    for (view in if (index >= 0) current else retained) key(view.id) { RenderChild(view) }
                }
            }
        }
        else -> return false
    }
    return true
}
