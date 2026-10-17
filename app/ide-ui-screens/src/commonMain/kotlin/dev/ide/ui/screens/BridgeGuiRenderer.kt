package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun BridgeGuiWindow(
    rawJson: String,
    moduleName: String,
    mainClass: String,
    onSendCallback: (String) -> Unit,
    modifier: Modifier = Modifier,
)
