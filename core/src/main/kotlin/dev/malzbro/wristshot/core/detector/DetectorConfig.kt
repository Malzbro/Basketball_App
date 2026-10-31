package dev.malzbro.wristshot.core.detector

/**
 * Every shot-detection threshold, in one place.
 *
 * Tuned on recordings/session_20261030_163316 (43 min, 144 labeled shots, watch
 * on the right/shooting wrist): F1 0.90 there, 0.91 on the half of the session
 * the values were not tuned on. One session from one shooter is not proof:
 * re-check on new sessions with analysis/analyze_session.py, which uses the
 * same parameter names (lower_snake_case there).
 *
 * The pitch sign depends on which wrist the watch is on and which way round it
 * is worn; these values are for a right wrist in the default orientation.
 */
data class DetectorConfig(
    /** Arm counts as raised while forearm pitch (deg) is below this. Shots reach about -60 deg. */
    val raisePitchMaxDeg: Double = -45.0,
    /** Follow-through: the arm must stay raised at least this long (shots: ~1.3 s). */
    val holdMinS: Double = 0.3,
    /** Release flick: peak smoothed |gyro| (rad/s) near the moment the arm goes up. */
    val releaseGyroMin: Double = 8.0,
    /** Release spike: peak |accel| (m/s^2) just after the flick. */
    val releaseAccelMin: Double = 20.0,
    /** The flick is searched from this long before the arm crosses the threshold... */
    val releaseLookbackS: Double = 0.5,
    /** ...until this long after. */
    val releaseLookaheadS: Double = 0.2,
    /** Window after the flick in which the accel spike must appear. */
    val releaseAccelWindowS: Double = 0.3,
    /** Minimum time between two shots. */
    val minQuietS: Double = 2.0,
    /** Trailing moving-average lengths. */
    val gyroSmoothS: Double = 0.03,
    val pitchSmoothS: Double = 0.05,
    /** Feature-only windows before the release (for form scoring later, not detection). */
    val driveWindowS: Double = 0.6,
    val dipWindowS: Double = 1.2,
    /**
     * A raise longer than this is evaluated without waiting for the arm to come
     * down (its hold_s is reported as this value). Real shots held 0.4-2 s.
     */
    val maxRaiseS: Double = 5.0,
) {
    /** For meta.json, with the same names the Python analysis uses. */
    fun toMap(): Map<String, Double> = linkedMapOf(
        "raise_pitch_max_deg" to raisePitchMaxDeg,
        "hold_min_s" to holdMinS,
        "release_gyro_min" to releaseGyroMin,
        "release_accel_min" to releaseAccelMin,
        "release_lookback_s" to releaseLookbackS,
        "release_lookahead_s" to releaseLookaheadS,
        "release_accel_window_s" to releaseAccelWindowS,
        "min_quiet_s" to minQuietS,
        "gyro_smooth_s" to gyroSmoothS,
        "pitch_smooth_s" to pitchSmoothS,
        "drive_window_s" to driveWindowS,
        "dip_window_s" to dipWindowS,
        "max_raise_s" to maxRaiseS,
    )
}
