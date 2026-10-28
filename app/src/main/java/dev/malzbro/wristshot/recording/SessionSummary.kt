package dev.malzbro.wristshot.recording

import dev.malzbro.wristshot.core.session.SessionCounts

/** What the home screen shows about the last finished session. */
data class SessionSummary(
    val sessionId: String,
    val folder: String,
    val durationS: Double,
    val sampleCount: Long,
    val meanRateHz: Double,
    val dropouts: Int,
    val counts: SessionCounts,
)
