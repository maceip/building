package com.pureswift.swiftui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

private object FontResources

@Composable
internal actual fun rememberNamedFontFamily(name: String): FontFamily? = remember(name) {
    val base = fontResourceName(name)
    val fonts = listOf(400, 500, 560, 600, 700).mapNotNull { weight ->
        val path = "fonts/${base}_$weight.ttf"
        FontResources.javaClass.classLoader.getResourceAsStream(path)?.use {
            Font(path, it.readBytes(), weight = FontWeight(weight))
        }
    }
    if (fonts.isEmpty()) null else FontFamily(fonts)
}
