package dev.ide.ui.ext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.ide.ui.backend.IdeBackend

/** Host-specific executable tools belong in SDK Manager without introducing platform dependencies. */
val LocalSdkManagerTools = staticCompositionLocalOf<(@Composable (IdeBackend) -> Unit)?> { null }
