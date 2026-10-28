package dev.malzbro.wristshot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.malzbro.wristshot.core.model.EventLabel
import dev.malzbro.wristshot.core.model.EventSource
import dev.malzbro.wristshot.recording.RecordingService
import dev.malzbro.wristshot.recording.SessionRepository
import dev.malzbro.wristshot.ui.WristShotApp

class MainActivity : ComponentActivity() {

    // The recording works without this permission; it only controls whether the
    // "recording" notification is visible. So we ask once and accept either answer.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askForNotificationPermissionIfNeeded()
        setContent {
            WristShotApp(
                onStartSession = { RecordingService.start(this) },
                onStopSession = { RecordingService.stop(this) },
            )
        }
    }

    /**
     * Watches with programmable "stem" buttons send KEYCODE_STEM_1..3. Pressing one
     * during a session marks an (unlabeled) shot. The Galaxy Watch 7's buttons are
     * Home and Back instead; for Back, see the toggle on the home screen.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val isStemKey = keyCode == KeyEvent.KEYCODE_STEM_1 ||
            keyCode == KeyEvent.KEYCODE_STEM_2 ||
            keyCode == KeyEvent.KEYCODE_STEM_3
        if (isStemKey && event.repeatCount == 0) {
            val recorded = SessionRepository.label(
                EventLabel.SHOT, EventSource.HARDWARE_KEY, SystemClock.elapsedRealtime(),
            )
            if (recorded) {
                window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun askForNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
