package com.pureswift.swiftui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/** Hosts can bundle family_name_{weight}.ttf resources without a JNI API. */
@Composable
internal expect fun rememberNamedFontFamily(name: String): FontFamily?

internal fun fontResourceName(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
