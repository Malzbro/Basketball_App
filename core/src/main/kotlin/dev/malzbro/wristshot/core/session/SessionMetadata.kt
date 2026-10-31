package dev.malzbro.wristshot.core.session

/** Description of one sensor, copied from Android's Sensor object. */
data class SensorDescription(
    val role: String,
    val name: String,
    val vendor: String,
    val type: Int,
    val maxRange: Double,
    val resolution: Double,
    val minDelayUs: Int,
)

/**
 * Everything worth knowing about a session besides the samples themselves.
 * Written as session_<id>_meta.json when the session stops.
 */
data class SessionMetadata(
    val sessionId: String,
    val appVersion: String,
    val deviceModel: String,
    val androidSdk: Int,
    val startWallTime: String,
    val startElapsedNs: Long,
    val stopElapsedNs: Long,
    val requestedSamplingPeriodUs: Int,
    val maxReportLatencyUs: Int,
    val sensors: List<SensorDescription>,
    val sampleCount: Long,
    val meanSampleRateHz: Double,
    val maxGapMs: Double,
    val dropouts: Int,
    val counts: SessionCounts,
    /** Shots found by the live detector (labels are in [counts]). */
    val detectedShots: Int = 0,
    /** Detector thresholds used, so a session can be re-analyzed with the same values. */
    val detector: Map<String, Double> = emptyMap(),
) {
    val durationS: Double get() = (stopElapsedNs - startElapsedNs) / 1e9

    /** Hand-written JSON so the core module needs no libraries. */
    fun toJson(): String = buildString {
        append("{\n")
        field("session_id", str(sessionId))
        field("app_version", str(appVersion))
        field("device_model", str(deviceModel))
        field("android_sdk", androidSdk.toString())
        field("start_wall_time", str(startWallTime))
        field("start_elapsed_ns", startElapsedNs.toString())
        field("stop_elapsed_ns", stopElapsedNs.toString())
        field("duration_s", num(durationS))
        field("requested_sampling_period_us", requestedSamplingPeriodUs.toString())
        field("max_report_latency_us", maxReportLatencyUs.toString())
        field("sample_count", sampleCount.toString())
        field("mean_sample_rate_hz", num(meanSampleRateHz))
        field("max_gap_ms", num(maxGapMs))
        field("dropouts", dropouts.toString())
        field(
            "counts",
            "{\"makes\": ${counts.makes}, \"misses\": ${counts.misses}, " +
                "\"unlabeled\": ${counts.unlabeled}, \"shots\": ${counts.shots}}",
        )
        field("detected_shots", detectedShots.toString())
        field("detector", detector.entries.joinToString(", ", "{", "}") { (k, v) -> "${str(k)}: ${num(v)}" })
        append("  \"sensors\": [")
        sensors.forEachIndexed { i, s ->
            if (i > 0) append(",")
            append("\n    {\"role\": ${str(s.role)}, \"name\": ${str(s.name)}, ")
            append("\"vendor\": ${str(s.vendor)}, \"type\": ${s.type}, ")
            append("\"max_range\": ${num(s.maxRange)}, \"resolution\": ${num(s.resolution)}, ")
            append("\"min_delay_us\": ${s.minDelayUs}}")
        }
        append("\n  ]\n}\n")
    }

    private fun StringBuilder.field(key: String, jsonValue: String) {
        append("  ").append(str(key)).append(": ").append(jsonValue).append(",\n")
    }

    private fun num(v: Double): String = if (v.isNaN() || v.isInfinite()) "null" else v.toString()

    private fun str(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c < ' ' -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
