package com.pureswift.swiftui

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.backhandler.BackEvent
import kotlinx.coroutines.CancellationException

@Composable
internal actual fun PlatformCupertinoBackHandler(enabled: Boolean, predictive: Boolean,
    dispatcher: BackDispatcher, onBack: () -> Unit) {
    if (predictive) PredictiveBackHandler(enabled) { events ->
        var started = false
        var accepted = false
        try {
            events.collect { event ->
                val back = BackEvent(progress = event.progress, swipeEdge = when (event.swipeEdge) {
                    BackEventCompat.EDGE_LEFT -> BackEvent.SwipeEdge.LEFT
                    BackEventCompat.EDGE_RIGHT -> BackEvent.SwipeEdge.RIGHT
                    else -> BackEvent.SwipeEdge.UNKNOWN
                }, touchX = event.touchX, touchY = event.touchY)
                if (!started) { started = true; accepted = dispatcher.startPredictiveBack(back) }
                else if (accepted) dispatcher.progressPredictiveBack(back)
            }
            if (!accepted || !dispatcher.back()) onBack()
        } catch (cancelled: CancellationException) {
            if (accepted) dispatcher.cancelPredictiveBack()
            throw cancelled
        }
    } else BackHandler(enabled, onBack)
}
