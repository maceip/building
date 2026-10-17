package dev.ide.ui.platform

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import kotlin.coroutines.cancellation.CancellationException

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

@Composable
actual fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    PredictiveBackHandler(enabled = enabled) { progress ->
        try {
            progress.collect { backEvent ->
                onProgress(backEvent.progress.coerceIn(0f, 1f))
            }
            onProgress(0f)
            onBack()
        } catch (e: CancellationException) {
            onProgress(0f)
            onCancel()
        }
    }
}
