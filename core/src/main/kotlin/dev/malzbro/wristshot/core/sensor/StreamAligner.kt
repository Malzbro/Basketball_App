package dev.malzbro.wristshot.core.sensor

import dev.malzbro.wristshot.core.config.RecorderConfig
import dev.malzbro.wristshot.core.model.RawSample

/**
 * Merges the separate sensor streams into one row per accelerometer sample.
 *
 * Why this exists: Android delivers each sensor separately, and with batching
 * a whole burst of accelerometer samples can arrive before the matching gyro
 * burst. Simply pairing each accel sample with "the latest gyro value" would
 * then mix up samples that are up to [RecorderConfig.MAX_REPORT_LATENCY_US]
 * apart. Instead, accel samples wait in a queue until the other streams have
 * data at or after their timestamp, and the other streams are linearly
 * interpolated to the accel timestamp.
 *
 * Not thread-safe: call it from a single thread (the sensor thread).
 *
 * @param hasGyro / hasGravity / hasRotation false if the sensor does not exist
 *   on this device, so we never wait for it (its columns are written as NaN).
 * @param onSample receives finished rows in timestamp order.
 */
class StreamAligner(
    hasGyro: Boolean,
    hasGravity: Boolean,
    hasRotation: Boolean,
    private val maxWaitNs: Long = RecorderConfig.ALIGN_MAX_WAIT_NS,
    private val maxBuffered: Int = RecorderConfig.ALIGN_MAX_BUFFERED_SAMPLES,
    private val onSample: (RawSample) -> Unit,
) {
    private val gyro = if (hasGyro) Stream(3) else null
    private val gravity = if (hasGravity) Stream(3) else null
    private val rotation = if (hasRotation) Stream(4) else null
    private val secondaries = listOfNotNull(gyro, gravity, rotation)

    private val pendingAccel = ArrayDeque<Reading>()

    fun onAccel(tNs: Long, x: Float, y: Float, z: Float) {
        pendingAccel.addLast(Reading(tNs, doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble())))
        drain(force = false)
    }

    fun onGyro(tNs: Long, x: Float, y: Float, z: Float) = addSecondary(gyro, tNs, x, y, z)

    fun onGravity(tNs: Long, x: Float, y: Float, z: Float) = addSecondary(gravity, tNs, x, y, z)

    fun onRotation(tNs: Long, x: Float, y: Float, z: Float, w: Float) = addSecondary(rotation, tNs, x, y, z, w)

    private fun addSecondary(stream: Stream?, tNs: Long, vararg values: Float) {
        stream?.add(tNs, values)
        drain(force = false)
    }

    /** Emit every queued accel sample using the best data available. Call when stopping. */
    fun flush() = drain(force = true)

    private fun drain(force: Boolean) {
        while (pendingAccel.isNotEmpty()) {
            val head = pendingAccel.first()
            val newestAccel = pendingAccel.last().tNs
            val waitedTooLong = newestAccel - head.tNs > maxWaitNs
            val ready = force || waitedTooLong || secondaries.all { it.hasDataAtOrAfter(head.tNs) }
            if (!ready) return
            pendingAccel.removeFirst()
            onSample(buildSample(head))
        }
    }

    private fun buildSample(accel: Reading): RawSample {
        val t = accel.tNs
        val g = gyro?.valueAt(t)
        val gr = gravity?.valueAt(t)
        val q = rotation?.valueAt(t)?.let(::normalizeQuaternion)
        secondaries.forEach { it.dropOlderThan(t) }
        return RawSample(
            tNs = t,
            ax = accel.values[0], ay = accel.values[1], az = accel.values[2],
            gx = g.at(0), gy = g.at(1), gz = g.at(2),
            gravX = gr.at(0), gravY = gr.at(1), gravZ = gr.at(2),
            qx = q.at(0), qy = q.at(1), qz = q.at(2), qw = q.at(3),
        )
    }

    private fun DoubleArray?.at(i: Int): Double = this?.get(i) ?: Double.NaN

    private fun normalizeQuaternion(q: DoubleArray): DoubleArray {
        val n = kotlin.math.sqrt(q.sumOf { it * it })
        return if (n > 0.0) DoubleArray(q.size) { q[it] / n } else q
    }

    private class Reading(val tNs: Long, val values: DoubleArray)

    /** A secondary sensor stream, kept sorted by time (Android delivers in order). */
    private inner class Stream(private val size: Int) {
        private val readings = ArrayDeque<Reading>()

        fun add(tNs: Long, v: FloatArray) {
            readings.addLast(Reading(tNs, DoubleArray(size) { v[it].toDouble() }))
            while (readings.size > maxBuffered) readings.removeFirst()
        }

        fun hasDataAtOrAfter(tNs: Long) = readings.isNotEmpty() && readings.last().tNs >= tNs

        /** Linear interpolation at [tNs]; nearest value outside the buffered range; null if empty. */
        fun valueAt(tNs: Long): DoubleArray? {
            if (readings.isEmpty()) return null
            if (tNs <= readings.first().tNs) return readings.first().values
            if (tNs >= readings.last().tNs) return readings.last().values
            for (i in 1 until readings.size) {
                val after = readings[i]
                if (after.tNs >= tNs) {
                    val before = readings[i - 1]
                    val span = (after.tNs - before.tNs).toDouble()
                    val f = if (span > 0) (tNs - before.tNs) / span else 0.0
                    return interpolate(before.values, after.values, f)
                }
            }
            return readings.last().values
        }

        private fun interpolate(a: DoubleArray, b: DoubleArray, f: Double): DoubleArray {
            // A quaternion q and -q are the same rotation; flip b if needed so we
            // interpolate the short way round.
            val flip = size == 4 && (0 until 4).sumOf { a[it] * b[it] } < 0
            return DoubleArray(size) { i ->
                val bi = if (flip) -b[i] else b[i]
                a[i] + (bi - a[i]) * f
            }
        }

        /** Keep the last reading before [tNs] (needed to interpolate the next sample). */
        fun dropOlderThan(tNs: Long) {
            while (readings.size >= 2 && readings[1].tNs <= tNs) readings.removeFirst()
        }
    }
}
