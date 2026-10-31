package com.pureswift.swiftui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun Context.cupertinoActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.cupertinoActivity()
    else -> null
}

/** Android equivalent for upstream's iOS-only system-bar appearance side effect. */
@Composable
internal actual fun PlatformCupertinoSystemBarAppearance(dark: Boolean) {
    val view = LocalView.current
    val activity = view.context.cupertinoActivity()
    DisposableEffect(view, activity, dark) {
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        val status = controller?.isAppearanceLightStatusBars
        val navigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = !dark
        controller?.isAppearanceLightNavigationBars = !dark
        onDispose {
            if (status != null) controller.isAppearanceLightStatusBars = status
            if (navigation != null) controller.isAppearanceLightNavigationBars = navigation
        }
    }
}
