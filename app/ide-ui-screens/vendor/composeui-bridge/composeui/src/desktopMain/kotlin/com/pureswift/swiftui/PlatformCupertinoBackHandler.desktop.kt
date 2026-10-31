package com.pureswift.swiftui

import androidx.compose.runtime.Composable
import com.arkivanov.essenty.backhandler.BackDispatcher

// Desktop uses the stack's Escape key handler. There is no Android system gesture.
@Composable
internal actual fun PlatformCupertinoBackHandler(enabled: Boolean, predictive: Boolean,
    dispatcher: BackDispatcher, onBack: () -> Unit) = Unit
