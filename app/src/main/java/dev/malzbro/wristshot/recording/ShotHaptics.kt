package dev.malzbro.wristshot.recording

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.malzbro.wristshot.config.AppConfig

/** Buzzes the watch when a shot is detected. Works with the screen off. */
class ShotHaptics(context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    fun shotDetected() {
        vibrator.vibrate(VibrationEffect.createOneShot(AppConfig.SHOT_BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
