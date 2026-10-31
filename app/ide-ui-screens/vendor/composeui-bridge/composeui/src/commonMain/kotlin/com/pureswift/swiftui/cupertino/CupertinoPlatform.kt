package com.pureswift.swiftui

import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import io.github.alexzhirkevich.cupertino.CupertinoText
import io.github.alexzhirkevich.cupertino.rememberCupertinoHapticFeedback

@Composable internal expect fun PlatformCupertinoSystemBarAppearance(dark: Boolean)

@Composable
internal fun RenderCupertinoPlatform(node: ViewNode): Boolean {
    when (node.string("name")) {
        "SystemBarAppearance" -> {
            PlatformCupertinoSystemBarAppearance(node.bool("dark") ?: LocalAppearanceIsDark.current)
            cupertinoSlot(node)
        }
        "rememberCupertinoHapticFeedback" -> {
            val feedback = rememberCupertinoHapticFeedback()
            val enabled = cupertinoEnabled(node)
            val type = when (node.string("type") ?: "LongPress") {
                "LongPress" -> HapticFeedbackType.LongPress
                "TextHandleMove" -> HapticFeedbackType.TextHandleMove
                else -> null
            }
            if (type == null) CupertinoText("Haptic feedback is unavailable on this device.")
            LaunchedEffect(node.id, node.string("commandID")) {
                if (enabled && type != null && node.string("commandID") != null) {
                    feedback.performHapticFeedback(type)
                    Props(node.props).voidAction("onPerformed")?.invoke()
                }
            }
            cupertinoSlot(node)
        }
        else -> return false
    }
    return true
}
