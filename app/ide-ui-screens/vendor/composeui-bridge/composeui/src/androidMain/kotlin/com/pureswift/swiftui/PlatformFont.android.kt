package com.pureswift.swiftui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

@Composable
internal actual fun rememberNamedFontFamily(name: String): FontFamily? {
    val context = LocalContext.current
    return remember(name, context) {
        val base = fontResourceName(name)
        val fonts = listOf(400, 500, 560, 600, 700).mapNotNull { weight ->
            val id = context.resources.getIdentifier("${base}_$weight", "font", context.packageName)
            if (id == 0) null else Font(id, FontWeight(weight))
        }
        if (fonts.isEmpty()) null else FontFamily(fonts)
    }
}
