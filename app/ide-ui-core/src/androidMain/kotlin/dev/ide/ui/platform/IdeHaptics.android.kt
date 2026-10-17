package dev.ide.ui.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

@Composable
actual fun rememberPlatformHapticDriver(): HapticDriver {
    val context = LocalContext.current
    val view = LocalView.current
    return remember(context, view) { AndroidHapticDriver(context, view) }
}

private class AndroidHapticDriver(
    context: Context,
    private val view: View,
) : HapticDriver {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    override fun perform(waveform: HapticWaveform) {
        val fallbackConstant = mapFallbackConstant(waveform.fallback)
        if (waveform.isOneShot) {
            vibrateOneShot(waveform.oneShotDurationMs, waveform.oneShotAmplitude, fallbackConstant)
        } else {
            vibrateWaveform(waveform.timings, waveform.amplitudes, fallbackConstant)
        }
    }

    private fun vibrateWaveform(timings: LongArray, amplitudes: IntArray, fallbackConstant: Int) {
        val vib = vibrator
        if (vib != null && vib.hasVibrator()) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = if (vib.hasAmplitudeControl()) {
                        VibrationEffect.createWaveform(timings, amplitudes, -1)
                    } else {
                        VibrationEffect.createWaveform(timings, -1)
                    }
                    vib.vibrate(effect)
                    return
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(timings, -1)
                    return
                }
            }
        }
        runCatching { view.performHapticFeedback(fallbackConstant) }
    }

    private fun vibrateOneShot(durationMs: Long, amplitude: Int, fallbackConstant: Int) {
        val vib = vibrator
        if (vib != null && vib.hasVibrator()) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val amp = if (vib.hasAmplitudeControl()) amplitude.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
                    vib.vibrate(VibrationEffect.createOneShot(durationMs, amp))
                    return
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(durationMs)
                    return
                }
            }
        }
        runCatching { view.performHapticFeedback(fallbackConstant) }
    }

    private fun mapFallbackConstant(kind: HapticFallbackKind): Int = when (kind) {
        HapticFallbackKind.TAP -> HapticFeedbackConstants.KEYBOARD_TAP
        HapticFallbackKind.HEAVY_TAP ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) HapticFeedbackConstants.CONTEXT_CLICK
            else HapticFeedbackConstants.LONG_PRESS
        HapticFallbackKind.TICK -> HapticFeedbackConstants.CLOCK_TICK
        HapticFallbackKind.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
        HapticFallbackKind.CONFIRM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.KEYBOARD_TAP
        HapticFallbackKind.REJECT ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
            else HapticFeedbackConstants.LONG_PRESS
        HapticFallbackKind.GESTURE_THRESHOLD ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            else HapticFeedbackConstants.CLOCK_TICK
    }
}
