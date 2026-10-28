package dev.malzbro.wristshot.core.config

/**
 * Every tunable number used by the Phase 1 raw recorder lives here.
 *
 * These are engineering settings (sample rate, buffering), not shot-detection
 * thresholds. Detection thresholds will get their own config object in Phase 3,
 * once they can be tuned from your recorded data.
 */
object RecorderConfig {
    /** Requested time between sensor samples. 10 000 us = 100 Hz. */
    const val SAMPLING_PERIOD_US: Int = 10_000

    /**
     * How long the sensor hardware may hold samples in its FIFO before handing
     * them to the app. Batching lets the CPU process samples in bursts instead of
     * waking up 100 times per second. Timestamps are kept, so nothing is lost.
     */
    const val MAX_REPORT_LATENCY_US: Int = 200_000

    /**
     * When the gyro/gravity/rotation streams lag behind the accelerometer, wait at
     * most this long for them before writing a row with the latest values we have.
     * Must be larger than [MAX_REPORT_LATENCY_US] (converted to ns).
     */
    const val ALIGN_MAX_WAIT_NS: Long = 600_000_000L

    /** Keep at most this many samples per secondary stream for interpolation. */
    const val ALIGN_MAX_BUFFERED_SAMPLES: Int = 256

    /** Characters buffered in memory before a write hits the flash storage. */
    const val WRITE_BUFFER_CHARS: Int = 64 * 1024

    /** Force a flush this often so a crash loses at most this much data. */
    const val FLUSH_INTERVAL_MS: Long = 2_000L

    /**
     * Safety limit for the CPU wake lock held while recording. If you forget to
     * stop a session the lock is released after this long anyway.
     */
    const val WAKE_LOCK_TIMEOUT_MS: Long = 3L * 60L * 60L * 1000L

    /** Decimal places for sensor values in the CSV (m/s^2, rad/s, unitless). */
    const val CSV_VALUE_DECIMALS: Int = 4

    /** Decimal places for the relative time column (seconds). */
    const val CSV_TIME_DECIMALS: Int = 4

    /**
     * A gap between consecutive accelerometer samples longer than this is counted
     * as a "dropout" in the session metadata, so you can spot lost data.
     */
    const val DROPOUT_GAP_NS: Long = 100_000_000L

    /** Folder (inside the app's external files dir) where sessions are written. */
    const val SESSIONS_DIR: String = "sessions"
}
