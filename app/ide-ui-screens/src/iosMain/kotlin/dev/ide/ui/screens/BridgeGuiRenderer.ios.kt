package dev.ide.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
actual fun BridgeGuiWindow(
    rawJson: String,
    moduleName: String,
    mainClass: String,
    onSendCallback: (String) -> Unit,
    modifier: Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text("GUI Bridge preview ($moduleName)")
    }
}
