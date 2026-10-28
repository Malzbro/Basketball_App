package dev.malzbro.wristshot.config

/**
 * Android/UI settings for the watch app. Recorder settings (sample rate,
 * buffering) live in core's RecorderConfig; shot detection thresholds will get
 * their own config object in Phase 3.
 */
object AppConfig {
    /** Keep the display on while the Make/Miss screen is open during a session. */
    const val KEEP_SCREEN_ON_WHILE_LABELING: Boolean = true

    /**
     * Default for the "Back button marks shot" toggle on the home screen. When on,
     * pressing the watch's back button on the label screen records an unlabeled
     * shot instead of going back (swipe right or tap Done to leave).
     */
    const val BACK_KEY_MARKS_SHOT_DEFAULT: Boolean = false

    /** How often the on-screen session timer refreshes. */
    const val UI_TIMER_TICK_MS: Long = 500L

    /** How long the last label stays highlighted on the label screen. */
    const val LABEL_FLASH_MS: Long = 1_200L

    /** Max time to wait for the recorder thread to finish writing when stopping. */
    const val STOP_TIMEOUT_MS: Long = 5_000L

    const val NOTIFICATION_ID: Int = 1
    const val NOTIFICATION_CHANNEL_ID: String = "recording"
    const val WAKE_LOCK_TAG: String = "WristShot:recording"
}
