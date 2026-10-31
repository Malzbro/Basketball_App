package dev.malzbro.wristshot.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.tooling.preview.devices.WearDevices
import dev.malzbro.wristshot.core.detector.Candidate
import dev.malzbro.wristshot.core.detector.DetectorConfig
import dev.malzbro.wristshot.core.detector.DetectorState
import dev.malzbro.wristshot.core.detector.RejectReason
import dev.malzbro.wristshot.core.detector.ShotFeatures
import dev.malzbro.wristshot.recording.SessionUiState
import java.util.Locale

/**
 * Live view of the shot detector, for testing on the court: is the arm counted
 * as raised, how many shots were found, and why the last raise did or did not
 * count. Thresholds shown are the ones in core's DetectorConfig.
 */
@Composable
fun DebugScreen(state: SessionUiState, config: DetectorConfig = DetectorConfig()) {
    val listState = rememberScalingLazyListState()
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            item { Text("Detector", fontWeight = FontWeight.Bold, color = MaterialTheme.colors.primary) }
            item {
                val raised = state.detectorState == DetectorState.ARM_RAISED
                Text(
                    text = if (!state.isRecording) "not recording" else if (raised) "ARM UP" else "idle",
                    fontSize = WristShotText.LabelHalf,
                    fontWeight = FontWeight.Bold,
                    color = if (raised) WristShotColors.Go else MaterialTheme.colors.onBackground,
                )
            }
            item { Text("arm ${fmt(state.pitchDeg, 0)}°  (up < ${fmt(config.raisePitchMaxDeg, 0)}°)") }
            item { Text("detected ${state.detectedShots} · labeled ${state.counts.shots}") }
            state.lastCandidate?.let { c ->
                item { LastCandidateTitle(c) }
                featureRows(c.features, config).forEach { (name, value) ->
                    item { Text("$name  $value", style = MaterialTheme.typography.caption1) }
                }
            }
        }
    }
}

@Composable
private fun LastCandidateTitle(c: Candidate) {
    Text(
        text = if (c.isShot) "Last: SHOT" else "Last: no (${c.rejectReasons.joinToString { it.csvName }})",
        color = if (c.isShot) WristShotColors.Go else WristShotColors.Stop,
        fontWeight = FontWeight.Bold,
    )
}

/** Feature name -> "value unit (threshold)", in the order a shot happens. */
private fun featureRows(f: ShotFeatures, c: DetectorConfig): List<Pair<String, String>> = listOf(
    "flick" to "${fmt(f.peakGyro, 1)} rad/s (≥${fmt(c.releaseGyroMin, 1)})",
    "spike" to "${fmt(f.releaseAccel, 0)} m/s² (≥${fmt(c.releaseAccelMin, 0)})",
    "hold" to "${fmt(f.holdS, 2)} s (≥${fmt(c.holdMinS, 2)})",
    "top angle" to "${fmt(f.minPitchDeg, 0)}°",
    "drive" to "${fmt(f.peakAccelDrive, 0)} m/s²",
    "dip→release" to "${fmt(f.dipToReleaseS, 2)} s",
)

private fun fmt(v: Double, decimals: Int): String =
    if (v.isNaN()) "–" else String.format(Locale.US, "%.${decimals}f", v)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun DebugScreenPreview() {
    val features = ShotFeatures(0, 11.6, 85.0, 1.33, -65.0, 67.0, 3.6, 0.36)
    DebugScreen(
        SessionUiState(
            isRecording = true,
            detectorState = DetectorState.ARM_RAISED,
            pitchDeg = -61.0,
            detectedShots = 12,
            lastCandidate = Candidate(features, listOf(RejectReason.SHORT_HOLD)),
        ),
    )
}
