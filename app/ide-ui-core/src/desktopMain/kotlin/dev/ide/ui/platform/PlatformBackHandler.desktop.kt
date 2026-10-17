package dev.ide.ui.platform

import androidx.compose.runtime.Composable

/** Desktop has no system back gesture; window chrome handles close, so this is a no-op. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
}

@Composable
actual fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
}
