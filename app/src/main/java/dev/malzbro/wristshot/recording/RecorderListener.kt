package dev.malzbro.wristshot.recording

import dev.malzbro.wristshot.core.detector.Candidate
import dev.malzbro.wristshot.core.detector.DetectorState

/** What a [SessionRecorder] reports while it runs. Called on the recorder thread. */
interface RecorderListener {
    /** Every few seconds, after buffered rows were written. */
    fun onProgress(samplesWritten: Long)

    /** The first time writing to storage fails. */
    fun onError(message: String)

    /** The detector finished evaluating an arm raise (a shot or a rejected candidate). */
    fun onCandidate(candidate: Candidate)

    /** Detector state changed, or AppConfig.DETECTOR_UI_INTERVAL_MS passed (for the live arm angle). */
    fun onDetectorUpdate(state: DetectorState, pitchDeg: Double)
}
