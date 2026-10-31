package dev.malzbro.wristshot.recording

import android.os.SystemClock
import dev.malzbro.wristshot.config.AppConfig
import dev.malzbro.wristshot.core.detector.Candidate
import dev.malzbro.wristshot.core.detector.DetectorState
import dev.malzbro.wristshot.core.model.EventLabel
import dev.malzbro.wristshot.core.model.EventSource
import dev.malzbro.wristshot.core.session.SessionCounts
import dev.malzbro.wristshot.core.session.SessionMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Everything the UI shows. */
data class SessionUiState(
    val isRecording: Boolean = false,
    val sessionId: String? = null,
    /** Session start in SystemClock.elapsedRealtime() milliseconds (for the timer). */
    val startElapsedMs: Long = 0L,
    val counts: SessionCounts = SessionCounts(),
    val samplesWritten: Long = 0L,
    val lastLabel: EventLabel? = null,
    /** elapsedRealtime() ms when [lastLabel] was entered, to flash it briefly. */
    val lastLabelAtMs: Long = 0L,
    val lastSession: SessionMetadata? = null,
    val backKeyMarksShot: Boolean = AppConfig.BACK_KEY_MARKS_SHOT_DEFAULT,
    /** Shots found by the live detector this session. */
    val detectedShots: Int = 0,
    /** The last arm raise the detector evaluated (shot or rejected). */
    val lastCandidate: Candidate? = null,
    val detectorState: DetectorState = DetectorState.IDLE,
    /** Live smoothed forearm pitch, deg. */
    val pitchDeg: Double = Double.NaN,
    val error: String? = null,
)

/**
 * Single place where the UI and the recording service meet.
 * The service reports progress here; the UI reads [state] and sends labels.
 */
object SessionRepository {
    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    @Volatile
    private var recorder: SessionRecorder? = null

    internal fun onStarted(recorder: SessionRecorder) {
        this.recorder = recorder
        _state.update {
            it.copy(
                isRecording = true,
                sessionId = recorder.sessionFiles.id,
                startElapsedMs = recorder.startElapsedNs / 1_000_000L,
                counts = SessionCounts(),
                samplesWritten = 0L,
                lastLabel = null,
                detectedShots = 0,
                lastCandidate = null,
                detectorState = DetectorState.IDLE,
                error = null,
            )
        }
    }

    internal fun onProgress(samplesWritten: Long) {
        _state.update { it.copy(samplesWritten = samplesWritten) }
    }

    internal fun onCandidate(candidate: Candidate) {
        _state.update {
            it.copy(
                lastCandidate = candidate,
                detectedShots = it.detectedShots + if (candidate.isShot) 1 else 0,
            )
        }
    }

    internal fun onDetectorUpdate(state: DetectorState, pitchDeg: Double) {
        _state.update { it.copy(detectorState = state, pitchDeg = pitchDeg) }
    }

    internal fun onStopped(metadata: SessionMetadata?) {
        recorder = null
        _state.update {
            it.copy(
                isRecording = false,
                lastSession = metadata ?: it.lastSession,
                detectorState = DetectorState.IDLE,
                pitchDeg = Double.NaN,
            )
        }
    }

    internal fun onError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun setBackKeyMarksShot(enabled: Boolean) {
        _state.update { it.copy(backKeyMarksShot = enabled) }
    }

    /**
     * Writes a labeled event if a session is recording.
     * @return true if the label was recorded (so the caller can give haptic feedback).
     */
    fun label(label: EventLabel, source: EventSource): Boolean {
        val r = recorder ?: return false
        r.logEvent(label, source)
        val now = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(counts = it.counts.plus(label), lastLabel = label, lastLabelAtMs = now)
        }
        return true
    }
}
