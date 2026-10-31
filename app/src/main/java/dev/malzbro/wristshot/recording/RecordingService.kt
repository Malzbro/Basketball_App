package dev.malzbro.wristshot.recording

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.malzbro.wristshot.config.AppConfig
import dev.malzbro.wristshot.core.config.RecorderConfig
import dev.malzbro.wristshot.core.detector.Candidate
import dev.malzbro.wristshot.core.detector.DetectorState

/**
 * Foreground service that owns the [SessionRecorder].
 *
 * Why a foreground service: once the screen turns off, Android may pause or
 * kill a normal app. A foreground service with an ongoing notification is the
 * official way to say "the user is in a workout, keep running". On Wear OS the
 * notification also becomes an "ongoing activity" icon on the watch face, so
 * you can jump back into the app with one tap.
 *
 * Why a wake lock: with the screen off the watch CPU goes to sleep and sensor
 * samples would be dropped. A PARTIAL wake lock keeps only the CPU on (not the
 * screen) and is released as soon as the session stops.
 */
class RecordingService : Service() {
    private var recorder: SessionRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        // If Android kills us, don't restart with an empty session.
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (recorder != null) return
        // Must be called promptly after startForegroundService().
        startInForeground(SystemClock.elapsedRealtime())

        val r = SessionRecorder(this, recorderListener(ShotHaptics(this)))
        try {
            r.start()
        } catch (e: Exception) {
            Log.e(TAG, "Could not start recording", e)
            SessionRepository.onError("Could not start: ${e.message}")
            shutDown()
            return
        }
        recorder = r
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, AppConfig.WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire(RecorderConfig.WAKE_LOCK_TIMEOUT_MS)
            }
        SessionRepository.onStarted(r)
    }

    /** Forwards recorder events to the UI state, and buzzes on each detected shot. */
    private fun recorderListener(haptics: ShotHaptics) = object : RecorderListener {
        override fun onProgress(samplesWritten: Long) = SessionRepository.onProgress(samplesWritten)

        override fun onError(message: String) = SessionRepository.onError(message)

        override fun onCandidate(candidate: Candidate) {
            if (candidate.isShot) haptics.shotDetected()
            SessionRepository.onCandidate(candidate)
        }

        override fun onDetectorUpdate(state: DetectorState, pitchDeg: Double) =
            SessionRepository.onDetectorUpdate(state, pitchDeg)
    }

    private fun stopRecording() {
        val r = recorder
        recorder = null
        SessionRepository.onStopped(r?.stop(SessionRepository.state.value.counts))
        shutDown()
    }

    private fun shutDown() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Graceful stop even if the system tears us down: close files, write meta.json.
        if (recorder != null) stopRecording()
        super.onDestroy()
    }

    private fun startInForeground(startElapsedMs: Long) {
        val notification = RecordingNotification.build(
            context = this,
            startElapsedMs = startElapsedMs,
            stopIntent = PendingIntent.getService(
                this, 0, serviceIntent(this, ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        }
        ServiceCompat.startForeground(this, AppConfig.NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val ACTION_START = "dev.malzbro.wristshot.action.START"
        private const val ACTION_STOP = "dev.malzbro.wristshot.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, serviceIntent(context, ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(serviceIntent(context, ACTION_STOP))
        }

        private fun serviceIntent(context: Context, action: String): Intent =
            Intent(context, RecordingService::class.java).setAction(action)
    }
}
