package dev.ide.ui.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPlatformHapticDriver(): HapticDriver = HapticDriver.NoOp
