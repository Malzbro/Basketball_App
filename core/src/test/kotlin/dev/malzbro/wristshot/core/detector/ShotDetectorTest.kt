package dev.malzbro.wristshot.core.detector

import dev.malzbro.wristshot.core.model.RawSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

/** Builds simple synthetic motion at 100 Hz and checks the state machine. */
class ShotDetectorTest {
    private val g = 9.81

    /** Watch at the given forearm pitch, with optional gyro (rad/s, on z) and extra accel (on x). */
    private fun sample(i: Int, pitchDeg: Double, gyro: Double = 0.5, extraAcc: Double = 0.0): RawSample {
        val p = Math.toRadians(pitchDeg)
        val gx = g * sin(p)
        val gz = g * cos(p)
        return RawSample(
            tNs = i * 10_000_000L,
            ax = gx + extraAcc, ay = 0.0, az = gz,
            gx = 0.0, gy = 0.0, gz = gyro,
            gravX = gx, gravY = 0.0, gravZ = gz,
            qx = 0.0, qy = 0.0, qz = 0.0, qw = 1.0,
        )
    }

    /** n samples; raised (pitch -60) in [raiseFrom, raiseTo), flick + spike just before raiseFrom. */
    private fun run(n: Int, raises: List<IntRange>, config: DetectorConfig = DetectorConfig()): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val detector = ShotDetector(config) { out += it }
        for (i in 0 until n) {
            val raised = raises.any { i in it }
            val flick = raises.any { i == it.first - 2 }
            val spike = raises.any { i == it.first }
            detector.onSample(
                sample(i, if (raised) -60.0 else 30.0, gyro = if (flick) 40.0 else 0.5, extraAcc = if (spike) 60.0 else 0.0),
            )
        }
        return out
    }

    @Test
    fun `held raise with flick and spike is a shot`() {
        val out = run(400, listOf(100 until 230))
        assertEquals(1, out.size)
        val shot = out.single()
        assertTrue(shot.isShot, "rejected: ${shot.rejectReasons}")
        assertEquals(1.3, shot.features.holdS, 0.051) // smoothing shifts the edges by a sample or two
        assertTrue(shot.features.peakGyro >= DetectorConfig().releaseGyroMin)
        assertTrue(shot.features.minPitchDeg < -55)
    }

    @Test
    fun `short raise is rejected as short hold`() {
        val out = run(400, listOf(100 until 115))
        assertEquals(listOf(RejectReason.SHORT_HOLD), out.single().rejectReasons)
    }

    @Test
    fun `raise without a flick is rejected`() {
        val out = mutableListOf<Candidate>()
        val detector = ShotDetector { out += it }
        for (i in 0 until 400) detector.onSample(sample(i, if (i in 100 until 230) -60.0 else 30.0, extraAcc = if (i == 100) 60.0 else 0.0))
        // No flick means no release point either, so the spike is not found after it.
        assertTrue(RejectReason.WEAK_FLICK in out.single().rejectReasons)
    }

    @Test
    fun `second shot too soon is rejected`() {
        val out = run(600, listOf(50 until 150, 170 until 270, 420 until 520))
        assertEquals(listOf(true, false, true), out.map { it.isShot })
        assertEquals(listOf(RejectReason.TOO_SOON), out[1].rejectReasons)
    }

    @Test
    fun `state follows the arm`() {
        val detector = ShotDetector { }
        repeat(50) { detector.onSample(sample(it, 30.0)) }
        assertEquals(DetectorState.IDLE, detector.state)
        repeat(50) { detector.onSample(sample(50 + it, -60.0)) }
        assertEquals(DetectorState.ARM_RAISED, detector.state)
        assertEquals(-60.0, detector.pitchDeg, 1e-6)
        repeat(50) { detector.onSample(sample(100 + it, 30.0)) }
        assertEquals(DetectorState.IDLE, detector.state)
    }

    @Test
    fun `very long raise is cut off and needs the arm lowered before re-arming`() {
        val out = run(1200, listOf(100 until 1000), DetectorConfig(maxRaiseS = 2.0))
        assertEquals(1, out.size)
        assertEquals(2.0, out.single().features.holdS, 0.011)
    }
}
