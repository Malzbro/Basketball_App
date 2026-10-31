package dev.malzbro.wristshot.core.detector

/**
 * Scores detections against labels the same way analysis/detector_proto.py does:
 * a label matches the latest unused detection up to [BEFORE_NS] before it (or the
 * earliest up to [AFTER_NS] after it), one-to-one.
 */
object LabelMatcher {
    private const val BEFORE_NS = 6_000_000_000L
    private const val AFTER_NS = 1_000_000_000L

    data class Score(val detected: Int, val labels: Int, val truePos: Int) {
        val precision: Double get() = if (detected == 0) 0.0 else truePos.toDouble() / detected
        val recall: Double get() = if (labels == 0) 0.0 else truePos.toDouble() / labels
        val f1: Double get() = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
        override fun toString() = "detected=$detected labels=$labels truePos=$truePos " +
            "precision=%.3f recall=%.3f f1=%.3f".format(precision, recall, f1)
    }

    fun score(detectedNs: List<Long>, labelNs: List<Long>): Score {
        val used = BooleanArray(detectedNs.size)
        var truePos = 0
        for (e in labelNs) {
            val options = detectedNs.indices.filter { !used[it] && detectedNs[it] in (e - BEFORE_NS)..(e + AFTER_NS) }
            if (options.isEmpty()) continue
            val before = options.filter { detectedNs[it] <= e }
            val pick = if (before.isNotEmpty()) before.maxBy { detectedNs[it] } else options.minBy { detectedNs[it] }
            used[pick] = true
            truePos++
        }
        return Score(detectedNs.size, labelNs.size, truePos)
    }
}
