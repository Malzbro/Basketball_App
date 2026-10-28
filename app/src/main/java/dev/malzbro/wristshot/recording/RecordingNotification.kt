package dev.malzbro.wristshot.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.malzbro.wristshot.MainActivity
import dev.malzbro.wristshot.R
import dev.malzbro.wristshot.config.AppConfig

/**
 * The "Recording session" notification. On Wear OS it is also registered as an
 * "ongoing activity", which shows a live stopwatch icon on the watch face that
 * opens the app with one tap.
 */
object RecordingNotification {

    fun build(context: Context, startElapsedMs: Long, stopIntent: PendingIntent): Notification {
        createChannel(context)
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, AppConfig.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ball)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(context.getString(R.string.notif_text))
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_ball, context.getString(R.string.notif_stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val status = Status.Builder()
            .addTemplate("#time#")
            .addPart("time", Status.StopwatchPart(startElapsedMs))
            .build()
        OngoingActivity.Builder(context.applicationContext, AppConfig.NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_ball)
            .setTouchIntent(openApp)
            .setStatus(status)
            .build()
            .apply(context.applicationContext)

        return builder.build()
    }

    /** Safe to call repeatedly; Android ignores re-creating an existing channel. */
    private fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                AppConfig.NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.channel_recording),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
}
