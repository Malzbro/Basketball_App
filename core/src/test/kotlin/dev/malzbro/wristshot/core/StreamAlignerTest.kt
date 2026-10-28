package dev.malzbro.wristshot.core

import dev.malzbro.wristshot.core.model.RawSample
import dev.malzbro.wristshot.core.sensor.StreamAligner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamAlignerTest {
    private val ms = 1_000_000L

    @Test
    fun `waits for gyro and interpolates to accel timestamp`() {
        val out = mutableListOf<RawSample>()
        val aligner = StreamAligner(hasGyro = true, hasGravity = false, hasRotation = false) { out += it }

        // A batch of accel samples arrives before any gyro data.
        aligner.onAccel(10 * ms, 0f, 0f, 9.8f)
        aligner.onAccel(20 * ms, 0f, 0f, 9.8f)
        assertTrue(out.isEmpty(), "must wait for gyro data")

        aligner.onGyro(5 * ms, 0f, 0f, 0f)
        aligner.onGyro(15 * ms, 1f, 0f, 0f)
        assertEquals(1, out.size)
        assertEquals(0.5, out[0].gx, 1e-9) // halfway between 5 ms and 15 ms

        aligner.onGyro(25 * ms, 2f, 0f, 0f)
        assertEquals(2, out.size)
        assertEquals(1.5, out[1].gx, 1e-9)
        assertTrue(out[1].gravX.isNaN(), "missing sensor is written as NaN")
    }

    @Test
    fun `does not wait forever for a stalled stream`() {
        val out = mutableListOf<RawSample>()
        val aligner = StreamAligner(true, false, false, maxWaitNs = 50 * ms) { out += it }
        aligner.onGyro(0, 1f, 1f, 1f)
        for (i in 1..10) aligner.onAccel(i * 10 * ms, 0f, 0f, 9.8f)
        // Samples more than 50 ms behind the newest accel are released.
        assertTrue(out.size >= 4)
        assertEquals(1.0, out[0].gx, 1e-9)
        aligner.flush()
        assertEquals(10, out.size)
    }

    @Test
    fun `quaternion interpolation takes the short way and stays normalized`() {
        val out = mutableListOf<RawSample>()
        val aligner = StreamAligner(false, false, true) { out += it }
        aligner.onRotation(0, 0f, 0f, 0f, 1f)
        aligner.onRotation(20 * ms, 0f, 0f, 0f, -1f) // same rotation, opposite sign
        aligner.onAccel(10 * ms, 0f, 0f, 9.8f)
        assertEquals(1, out.size)
        val q = out[0]
        assertEquals(1.0, q.qw, 1e-9)
        assertEquals(1.0, q.qx * q.qx + q.qy * q.qy + q.qz * q.qz + q.qw * q.qw, 1e-9)
    }

    @Test
    fun `output stays in timestamp order`() {
        val out = mutableListOf<RawSample>()
        val aligner = StreamAligner(true, true, false) { out += it }
        for (i in 0 until 100) {
            aligner.onAccel(i * 10 * ms, 0f, 0f, 9.8f)
            if (i % 20 == 19) {
                for (j in i - 19..i) {
                    aligner.onGyro(j * 10 * ms + 3 * ms, j.toFloat(), 0f, 0f)
                    aligner.onGravity(j * 10 * ms + 1 * ms, 0f, 0f, 9.8f)
                }
            }
        }
        aligner.flush()
        assertEquals(100, out.size)
        assertTrue(out.zipWithNext().all { (a, b) -> a.tNs < b.tNs })
    }
}
