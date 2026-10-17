package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UISelectionFeedbackGenerator

@Composable
actual fun rememberPlatformHapticDriver(): HapticDriver = remember { IosHapticDriver() }

private class IosHapticDriver : HapticDriver {
    private val lightImpact = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    private val mediumImpact = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
    private val heavyImpact = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy)
    private val selection = UISelectionFeedbackGenerator()
    private val notification = UINotificationFeedbackGenerator()

    override fun perform(waveform: HapticWaveform) {
        when (waveform.fallback) {
            HapticFallbackKind.TAP -> lightImpact.impactOccurred()
            HapticFallbackKind.HEAVY_TAP -> heavyImpact.impactOccurred()
            HapticFallbackKind.TICK, HapticFallbackKind.GESTURE_THRESHOLD -> selection.selectionChanged()
            HapticFallbackKind.LONG_PRESS -> mediumImpact.impactOccurred()
            HapticFallbackKind.CONFIRM ->
                notification.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
            HapticFallbackKind.REJECT ->
                notification.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
        }
    }
}
