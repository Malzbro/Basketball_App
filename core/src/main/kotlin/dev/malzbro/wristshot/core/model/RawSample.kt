package dev.malzbro.wristshot.core.model

/**
 * One row of the raw CSV: an accelerometer sample plus the other sensors'
 * values interpolated to the same instant.
 *
 * Units follow Android's SensorManager conventions:
 *  - accel and gravity in m/s^2 (accel includes gravity),
 *  - gyro in rad/s,
 *  - quaternion (game rotation vector) unitless.
 * Missing values (sensor not available or not started yet) are NaN.
 *
 * @property tNs sensor timestamp in nanoseconds, same clock as
 *   SystemClock.elapsedRealtimeNanos(), which is also used for label events.
 */
data class RawSample(
    val tNs: Long,
    val ax: Double, val ay: Double, val az: Double,
    val gx: Double, val gy: Double, val gz: Double,
    val gravX: Double, val gravY: Double, val gravZ: Double,
    val qx: Double, val qy: Double, val qz: Double, val qw: Double,
)
