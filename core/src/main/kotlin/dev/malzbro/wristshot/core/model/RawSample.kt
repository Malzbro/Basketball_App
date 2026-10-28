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
) {
    /** Calls [action] for every sensor value, in [VALUE_COLUMNS] order, without allocating. */
    inline fun forEachValue(action: (Double) -> Unit) {
        action(ax); action(ay); action(az)
        action(gx); action(gy); action(gz)
        action(gravX); action(gravY); action(gravZ)
        action(qx); action(qy); action(qz); action(qw)
    }

    companion object {
        /** CSV column names of the sensor values; keep in the same order as [forEachValue]. */
        val VALUE_COLUMNS = listOf(
            "ax", "ay", "az",
            "gx", "gy", "gz",
            "grav_x", "grav_y", "grav_z",
            "qx", "qy", "qz", "qw",
        )
    }
}
