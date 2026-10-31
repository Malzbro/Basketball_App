package dev.malzbro.wristshot.core.detector

/**
 * Measurements of one arm raise (a shot candidate). Names match the Python
 * analysis columns.
 */
data class ShotFeatures(
    /** Release time, sensor clock (same as the raw CSV t_ns). */
    val tReleaseNs: Long,
    /** Peak smoothed |gyro| at the release flick, rad/s. */
    val peakGyro: Double,
    /** Peak |accel| within the accel window after the flick, m/s^2. */
    val releaseAccel: Double,
    /** How long the arm stayed raised (follow-through), s. */
    val holdS: Double,
    /** Highest arm position reached (most negative pitch), deg. */
    val minPitchDeg: Double,
    /** Peak |accel| in the drive window before release, m/s^2. */
    val peakAccelDrive: Double,
    /** Lowest |accel| in the dip window before release, m/s^2. */
    val minAccelDip: Double,
    /** Time from that lowest point to the release, s. */
    val dipToReleaseS: Double,
)

/** Why a candidate was not counted as a shot. */
enum class RejectReason(val csvName: String) {
    SHORT_HOLD("short_hold"),
    WEAK_FLICK("weak_flick"),
    NO_ACCEL_SPIKE("no_accel_spike"),
    TOO_SOON("too_soon"),
}

/** The outcome for one arm raise: a shot if [rejectReasons] is empty. */
data class Candidate(val features: ShotFeatures, val rejectReasons: List<RejectReason>) {
    val isShot: Boolean get() = rejectReasons.isEmpty()
}

/** What the detector is doing right now (shown on the debug screen). */
enum class DetectorState { IDLE, ARM_RAISED }
