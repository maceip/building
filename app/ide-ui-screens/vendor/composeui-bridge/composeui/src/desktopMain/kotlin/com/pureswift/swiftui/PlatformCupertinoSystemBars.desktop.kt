package com.pureswift.swiftui

import androidx.compose.runtime.Composable

// Desktop windows have no mobile status/navigation bars to control.
@Composable internal actual fun PlatformCupertinoSystemBarAppearance(dark: Boolean) = Unit
