package dev.malzbro.wristshot.recording

import android.content.Context
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import dev.malzbro.wristshot.BuildConfig
import dev.malzbro.wristshot.config.AppConfig
import dev.malzbro.wristshot.core.config.RecorderConfig
import dev.malzbro.wristshot.core.model.EventLabel
import dev.malzbro.wristshot.core.model.EventSource
import dev.malzbro.wristshot.core.session.SessionCounts
import dev.malzbro.wristshot.core.session.SessionCsvWriter
import dev.malzbro.wristshot.core.session.SessionFiles
import dev.malzbro.wristshot.core.session.SessionMetadata
import dev.malzbro.wristshot.sensors.MotionSensorSource
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Records one session: sensors -> CSV files.
 *
 * All sensor callbacks and all file writes happen on one background thread
 * ("recorder thread"), so nothing needs locks and the UI never waits on storage.
 * Rows are buffered in memory and written in chunks (see RecorderConfig).
 */
class SessionRecorder(
    private val context: Context,
    private val onProgress: (samplesWritten: Long) -> Unit,
    private val onError: (message: String) -> Unit,
) {
    private val thread = HandlerThread("WristShotRecorder")
    private lateinit var handler: Handler
    private lateinit var files: SessionFiles
    private lateinit var writer: SessionCsvWriter
    private lateinit var sensors: MotionSensorSource
    private lateinit var startWallTime: String
    private var writeFailed = false

    /** Session start on the sensor clock (SystemClock.elapsedRealtimeNanos). */
    var startElapsedNs: Long = 0L
        private set

    val sessionFiles: SessionFiles get() = files

    /** Opens the files and starts the sensors. Throws if the watch has no accelerometer. */
    fun start() {
        val sensorManager = context.getSystemService(SensorManager::class.java)
        sensors = MotionSensorSource(sensorManager) { sample ->
            guarded { writer.writeSample(sample) }
        }
        check(sensors.hasAccelerometer) { "This watch has no accelerometer" }

        val now = LocalDateTime.now()
        files = SessionFiles.create(sessionsDir(context), now)
        startWallTime = now.format(WALL_TIME_FORMAT)
        startElapsedNs = SystemClock.elapsedRealtimeNanos()
        writer = SessionCsvWriter(
            raw = files.raw.bufferedWriter(bufferSize = RecorderConfig.WRITE_BUFFER_CHARS),
            events = files.events.bufferedWriter(),
            sessionStartNs = startElapsedNs,
        )

        thread.start()
        handler = Handler(thread.looper)
        sensors.start(handler)
        handler.postDelayed(periodicFlush, RecorderConfig.FLUSH_INTERVAL_MS)
    }

    /**
     * Records a label. The timestamp is taken right now (on the caller's thread),
     * using the same clock as the sensor samples, then written in the background.
     */
    fun logEvent(label: EventLabel, source: EventSource) {
        val tNs = SystemClock.elapsedRealtimeNanos()
        val wall = LocalDateTime.now().format(WALL_TIME_FORMAT)
        handler.post { guarded { writer.writeEvent(tNs, wall, label, source) } }
    }

    /**
     * Stops sensors, writes everything left, closes files and writes meta.json.
     * @return the session's metadata, or null if writing did not finish in time.
     */
    fun stop(counts: SessionCounts): SessionMetadata? {
        sensors.stop()
        val stopElapsedNs = SystemClock.elapsedRealtimeNanos()
        val done = CountDownLatch(1)
        var metadata: SessionMetadata? = null
        handler.post {
            handler.removeCallbacks(periodicFlush)
            guarded {
                sensors.flushPending()
                writer.close()
                // Built on the recorder thread, after the last sample, so the stats are final.
                metadata = buildMetadata(stopElapsedNs, counts).also { files.meta.writeText(it.toJson()) }
            }
            done.countDown()
        }
        val finished = done.await(AppConfig.STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) Log.w(TAG, "Timed out waiting for recorder thread to finish writing")
        thread.quitSafely()
        // The latch makes the recorder thread's write to `metadata` visible here.
        return if (finished) metadata else null
    }

    private val periodicFlush = object : Runnable {
        override fun run() {
            guarded { writer.flush() }
            onProgress(writer.stats.count)
            handler.postDelayed(this, RecorderConfig.FLUSH_INTERVAL_MS)
        }
    }

    private fun buildMetadata(stopElapsedNs: Long, counts: SessionCounts): SessionMetadata {
        val stats = writer.stats
        return SessionMetadata(
            sessionId = files.id,
            appVersion = BuildConfig.VERSION_NAME,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidSdk = Build.VERSION.SDK_INT,
            startWallTime = startWallTime,
            startElapsedNs = startElapsedNs,
            stopElapsedNs = stopElapsedNs,
            requestedSamplingPeriodUs = RecorderConfig.SAMPLING_PERIOD_US,
            maxReportLatencyUs = RecorderConfig.MAX_REPORT_LATENCY_US,
            sensors = sensors.describe(),
            sampleCount = stats.count,
            meanSampleRateHz = stats.meanRateHz,
            maxGapMs = stats.maxGapNs / 1e6,
            dropouts = stats.dropouts,
            counts = counts,
        )
    }

    /** Runs a file operation; reports the first failure (e.g. storage full) instead of crashing. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Write failed", e)
            if (!writeFailed) {
                writeFailed = true
                onError("Write failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "SessionRecorder"
        private val WALL_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

        /**
         * /sdcard/Android/data/dev.malzbro.wristshot/files/sessions on the watch.
         * Falls back to internal storage if external storage is unavailable.
         */
        fun sessionsDir(context: Context): File {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, RecorderConfig.SESSIONS_DIR).apply { mkdirs() }
        }
    }
}
