package com.pureswift.swiftui

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import com.pureswift.bridge.BridgeExport
import com.pureswift.swiftandroid.AndroidBridgeHost

// The Android host: one Compose island rendering the whole Swift-evaluated
// tree. Swift constructs this, hands its store to the bridge runtime, and
// installs it as the activity's content view.
class SwiftUIHostView(context: Context) : FrameLayout(context) {

    val store = TreeStore()

    init {
        SwiftBridge.sink = JextractCallbackSink()
        // Installed before Swift's `AndroidSwiftUIApp.run` builds its
        // scheduler closure, since that runs later in this same construction.
        BridgeExport.bridgeSetHost(AndroidBridgeHost())
        val composeView = ComposeView(context)
        composeView.setContent {
            val dark = isSystemInDarkTheme()
            val canvas = if (dark) Color(0xFF1E1E1E) else Color.White
            val ink = if (dark) Color(0xFFFCFCFC) else Color(0xFF1E1E1E)
            val accent = if (dark) Color(0xFFD28FE2) else Color(0xFFA02AB8)
            val onAccent = if (dark) Color(0xFF1E1E1E) else Color.White
            val card = if (dark) Color(0xFF252326) else Color.White
            val muted = if (dark) Color(0xFFABBAB9) else Color(0xFF6B6B6B)
            val border = if (dark) Color(0xFF2F2D30) else Color(0xFFE5E5E5)
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            val font = rememberNamedFontFamily("Host Grotesk")
            val base = Typography()
            MaterialTheme(colorScheme = scheme.copy(
                primary = accent, onPrimary = onAccent,
                primaryContainer = card, onPrimaryContainer = ink,
                secondary = accent, onSecondary = onAccent,
                secondaryContainer = card, onSecondaryContainer = ink,
                background = canvas, onBackground = ink,
                surface = card, onSurface = ink,
                surfaceVariant = card, onSurfaceVariant = muted,
                outline = border, surfaceTint = accent,
            ), typography = base.copy(
                bodyLarge = base.bodyLarge.copy(fontFamily = font),
                bodyMedium = base.bodyMedium.copy(fontFamily = font),
                bodySmall = base.bodySmall.copy(fontFamily = font),
                titleLarge = base.titleLarge.copy(fontFamily = font),
                titleMedium = base.titleMedium.copy(fontFamily = font),
                titleSmall = base.titleSmall.copy(fontFamily = font),
                labelLarge = base.labelLarge.copy(fontFamily = font),
                labelMedium = base.labelMedium.copy(fontFamily = font),
                labelSmall = base.labelSmall.copy(fontFamily = font),
            )) {
                Surface(modifier = Modifier.fillMaxSize().background(canvas).safeDrawingPadding(), color = canvas) {
                    store.root?.let { Render(it) }
                }
            }
        }
        addView(
            composeView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }
}
