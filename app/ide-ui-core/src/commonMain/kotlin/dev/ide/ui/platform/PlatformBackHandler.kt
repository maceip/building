package dev.ide.ui.platform

import androidx.compose.runtime.Composable

/**
 * Handle the platform's "back" affordance (the Android system back gesture / button) while [enabled].
 * When the user navigates back and [enabled] is true, [onBack] runs and the event is consumed instead of
 * propagating to the host — so back closes an open sheet/dialog or pops a screen rather than killing the app
 * (issue #997). Nested handlers stack: the innermost enabled one wins, so an editor overlay's handler takes
 * priority over the screen-navigation handler above it.
 *
 * On desktop there is no system back gesture, so the actual is a no-op (window chrome handles close).
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/**
 * Predictive-back gesture handler that streams continuous gesture progress (`0f..1f`) via [onProgress]
 * while the user drags the Android system back gesture.
 *
 * Used for custom IDE predictive-back behaviors:
 * - In the **Agent Chat view**, swiping back smoothly shrinks the full-screen Agent drawer down to a
 *   **Half-Screen Multitasking Split** over the code editor so the user can see their code AND watch the
 *   agent typing simultaneously.
 * - In the **Code Editor view**, swiping back progressively shrinks and rounds the **code canvas first**
 *   before committing the back navigation.
 */
@Composable
expect fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCancel: () -> Unit = {},
    onBack: () -> Unit,
)
