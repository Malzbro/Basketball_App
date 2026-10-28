package dev.malzbro.wristshot.core.session

import dev.malzbro.wristshot.core.config.RecorderConfig

/**
 * Running statistics about the recorded samples, written to the session's
 * meta.json so you can check that nothing was dropped (e.g. with screen off).
 */
class SampleStats(private val dropoutGapNs: Long = RecorderConfig.DROPOUT_GAP_NS) {
    var count: Long = 0; private set
    var firstNs: Long = -1; private set
    var lastNs: Long = -1; private set
    var maxGapNs: Long = 0; private set
    var dropouts: Int = 0; private set

    fun add(tNs: Long) {
        if (count > 0) {
            val gap = tNs - lastNs
            if (gap > maxGapNs) maxGapNs = gap
            if (gap > dropoutGapNs) dropouts++
        } else {
            firstNs = tNs
        }
        lastNs = tNs
        count++
    }

    /** Average sample rate in Hz over the recording, or 0 if fewer than two samples. */
    val meanRateHz: Double
        get() = if (count < 2) 0.0 else (count - 1) / ((lastNs - firstNs) / 1e9)
}
