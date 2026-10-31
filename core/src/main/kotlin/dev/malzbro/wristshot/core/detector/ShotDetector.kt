package dev.malzbro.wristshot.core.detector

import dev.malzbro.wristshot.core.config.RecorderConfig
import dev.malzbro.wristshot.core.model.RawSample
import kotlin.math.asin
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Live shot detector. Feed it every [RawSample] in time order; it calls
 * [onCandidate] once per arm raise, saying whether it was a shot.
 *
 * What a jump shot looks like at the wrist (from recorded sessions):
 *  1. release: a sharp wrist flick (gyro peak) and accel spike as the arm goes up,
 *  2. follow-through: the forearm stays raised (pitch about -60 deg) for ~1.3 s,
 *  3. the arm comes down.
 * Dribbling and catching also flick the wrist, but with the forearm down.
 *
 * State machine:
 *   IDLE --pitch below raisePitchMaxDeg--> ARM_RAISED
 *   ARM_RAISED --arm lowered (or maxRaiseS)--> IDLE, and the raise is evaluated
 * Evaluating looks back in a short history buffer for the release flick and
 * measures the features; a raise is a shot if the hold, flick and spike pass
 * their thresholds and the previous shot was at least minQuietS earlier.
 * A raise is evaluated once its flick/spike windows are complete, which for a
 * very short raise can be after the next raise has already started.
 *
 * The shot is reported when the arm comes down, ~1.3 s after the release.
 * This is the same algorithm as analysis/detector_proto.py, so results on a
 * recording can be compared one-to-one. Pure Kotlin; not thread-safe.
 */
class ShotDetector(
    val config: DetectorConfig = DetectorConfig(),
    samplingPeriodUs: Int = RecorderConfig.SAMPLING_PERIOD_US,
    private val onCandidate: (Candidate) -> Unit,
) {
    private class Frame(val tNs: Long, val gyro: Double, val acc: Double, val pitch: Double)

    private val gyroAverage = MovingAverage(samplesFor(config.gyroSmoothS, samplingPeriodUs))
    private val pitchAverage = MovingAverage(samplesFor(config.pitchSmoothS, samplingPeriodUs))

    /** Recent samples, long enough to look back from a raise to the dip window before it. */
    private val history = ArrayDeque<Frame>()
    private val idleHistoryNs = nanos(config.releaseLookbackS + config.dipWindowS + HISTORY_MARGIN_S)

    var state: DetectorState = DetectorState.IDLE
        private set

    /** Latest smoothed forearm pitch, deg (for the debug screen). */
    var pitchDeg: Double = Double.NaN
        private set

    /** One arm raise: from the first raised sample to the first lowered one. */
    private class Raise(val startNs: Long, var minPitch: Double) {
        var endNs: Long = 0L
    }

    /** The raise in progress, if the arm is up now. */
    private var current: Raise? = null
    /** Raises that ended but whose flick/spike windows are not complete yet. */
    private val pending = ArrayDeque<Raise>()
    private val windowsAfterRaiseNs = nanos(config.releaseLookaheadS + config.releaseAccelWindowS)
    private var lastShotNs: Long? = null
    /** After a raise was cut off at maxRaiseS, wait for the arm to come down before re-arming. */
    private var waitForLower = false

    fun onSample(s: RawSample) {
        val frame = Frame(
            tNs = s.tNs,
            gyro = gyroAverage.add(magnitude(s.gx, s.gy, s.gz)),
            acc = magnitude(s.ax, s.ay, s.az),
            pitch = pitchAverage.add(forearmPitchDeg(s)),
        )
        history.addLast(frame)
        pitchDeg = frame.pitch
        val raised = frame.pitch < config.raisePitchMaxDeg

        val raise = current
        if (raise == null) {
            if (waitForLower) {
                waitForLower = raised
            } else if (raised) {
                current = Raise(frame.tNs, frame.pitch)
            }
        } else if (!raised) {
            finish(raise, frame.tNs)
        } else if (frame.tNs - raise.startNs >= nanos(config.maxRaiseS)) {
            finish(raise, frame.tNs)
            waitForLower = true
        } else {
            raise.minPitch = minOf(raise.minPitch, frame.pitch)
        }
        state = if (current != null) DetectorState.ARM_RAISED else DetectorState.IDLE

        while (pending.isNotEmpty() && frame.tNs >= pending.first().startNs + windowsAfterRaiseNs) {
            evaluate(pending.removeFirst())
        }
        pruneHistory(frame.tNs)
    }

    private fun finish(raise: Raise, endNs: Long) {
        raise.endNs = endNs
        pending.addLast(raise)
        current = null
    }

    private fun evaluate(raise: Raise) {
        val release = maxBy(raise.startNs - nanos(config.releaseLookbackS), raise.startNs + nanos(config.releaseLookaheadS)) { it.gyro }
        val t0 = release.tNs
        val spike = maxBy(t0, t0 + nanos(config.releaseAccelWindowS)) { it.acc }
        val drive = maxBy(t0 - nanos(config.driveWindowS), t0) { it.acc }
        val dip = maxBy(t0 - nanos(config.dipWindowS), t0) { -it.acc }

        val features = ShotFeatures(
            tReleaseNs = t0,
            peakGyro = release.gyro,
            releaseAccel = spike.acc,
            holdS = (raise.endNs - raise.startNs) / NANOS_PER_S,
            minPitchDeg = raise.minPitch,
            peakAccelDrive = drive.acc,
            minAccelDip = dip.acc,
            dipToReleaseS = (t0 - dip.tNs) / NANOS_PER_S,
        )
        val reasons = buildList {
            if (features.holdS < config.holdMinS) add(RejectReason.SHORT_HOLD)
            if (features.peakGyro < config.releaseGyroMin) add(RejectReason.WEAK_FLICK)
            if (features.releaseAccel < config.releaseAccelMin) add(RejectReason.NO_ACCEL_SPIKE)
            val last = lastShotNs
            if (isEmpty() && last != null && t0 - last < nanos(config.minQuietS)) add(RejectReason.TOO_SOON)
        }
        if (reasons.isEmpty()) lastShotNs = t0
        onCandidate(Candidate(features, reasons))
    }

    /** The first frame in [fromNs, toNs] with the largest [key] (the history is never empty here). */
    private inline fun maxBy(fromNs: Long, toNs: Long, key: (Frame) -> Double): Frame {
        var best: Frame? = null
        for (f in history) {
            if (f.tNs < fromNs) continue
            if (f.tNs > toNs) break
            if (best == null || key(f) > key(best)) best = f
        }
        return best ?: history.first()
    }

    private fun pruneHistory(nowNs: Long) {
        val oldestNeededNs = pending.firstOrNull()?.startNs ?: current?.startNs ?: nowNs
        val keepFromNs = oldestNeededNs - idleHistoryNs
        while (history.size > 1 && history.first().tNs < keepFromNs) history.removeFirst()
    }

    /** Trailing average over the last n values (fewer at the very start). */
    private class MovingAverage(private val n: Int) {
        private val values = ArrayDeque<Double>()
        private var sum = 0.0

        fun add(v: Double): Double {
            values.addLast(v)
            sum += v
            if (values.size > n) sum -= values.removeFirst()
            return sum / values.size
        }
    }

    companion object {
        private const val NANOS_PER_S = 1e9
        /** Extra history kept beyond the longest look-back, so window edges are never cut. */
        private const val HISTORY_MARGIN_S = 0.1

        private fun nanos(seconds: Double): Long = (seconds * NANOS_PER_S).toLong()

        private fun samplesFor(seconds: Double, periodUs: Int): Int =
            maxOf(1, (seconds * 1e6 / periodUs).roundToInt())

        private fun magnitude(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)

        /**
         * Angle of the watch x axis (along the forearm) above/below the horizon, from
         * gravity. Same formula as pitch_deg in analysis/wristshot_io.py.
         */
        fun forearmPitchDeg(s: RawSample): Double {
            val g = magnitude(s.gravX, s.gravY, s.gravZ)
            if (g == 0.0 || g.isNaN()) return Double.NaN
            return Math.toDegrees(asin((s.gravX / g).coerceIn(-1.0, 1.0)))
        }
    }
}
