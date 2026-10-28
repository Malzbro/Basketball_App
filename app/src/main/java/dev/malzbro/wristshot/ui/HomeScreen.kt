package dev.malzbro.wristshot.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.tooling.preview.devices.WearDevices
import dev.malzbro.wristshot.config.AppConfig
import dev.malzbro.wristshot.recording.SessionUiState
import kotlinx.coroutines.delay
import java.util.Locale

private val StartGreen = Color(0xFF2E7D32)
private val StopRed = Color(0xFFC62828)

@Composable
fun HomeScreen(
    state: SessionUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenLabeling: () -> Unit,
    onBackKeyMarksShotChange: (Boolean) -> Unit,
) {
    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            item {
                Text(
                    text = if (state.isRecording) "● REC" else "WristShot",
                    color = if (state.isRecording) StopRed else MaterialTheme.colors.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                Text(
                    text = formatElapsed(rememberSessionElapsedMs(state)),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            item {
                val c = state.counts
                Text(
                    text = "Shots ${c.shots}   ✓${c.makes}  ✗${c.misses}",
                    style = MaterialTheme.typography.body1,
                )
            }
            item {
                if (state.isRecording) {
                    Chip(
                        onClick = onStop,
                        label = { Text("Stop session") },
                        colors = ChipDefaults.primaryChipColors(backgroundColor = StopRed),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Chip(
                        onClick = onStart,
                        label = { Text("Start session") },
                        colors = ChipDefaults.primaryChipColors(backgroundColor = StartGreen),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (state.isRecording) {
                item {
                    Chip(
                        onClick = onOpenLabeling,
                        label = { Text("Make / Miss") },
                        secondaryLabel = { Text("Label shots") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                ToggleChip(
                    checked = state.backKeyMarksShot,
                    onCheckedChange = onBackKeyMarksShotChange,
                    label = { Text("Back key = shot") },
                    secondaryLabel = { Text("On label screen") },
                    toggleControl = { Switch(checked = state.backKeyMarksShot) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.error?.let { error ->
                item { Text(text = error, color = StopRed, textAlign = TextAlign.Center) }
            }
            state.lastSession?.let { last ->
                item {
                    Text(
                        text = "Last session\n${last.sessionId.removePrefix("session_")}\n" +
                            "${formatElapsed((last.durationS * 1000).toLong())} · ${last.counts.shots} shots\n" +
                            String.format(Locale.US, "%.0f Hz · %d gaps", last.meanRateHz, last.dropouts),
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** Milliseconds since the session started, refreshed a couple of times per second. */
@Composable
private fun rememberSessionElapsedMs(state: SessionUiState): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.isRecording) {
        while (state.isRecording) {
            now = SystemClock.elapsedRealtime()
            delay(AppConfig.UI_TIMER_TICK_MS)
        }
    }
    return if (state.isRecording) now - state.startElapsedMs else 0L
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun HomeScreenPreview() {
    HomeScreen(SessionUiState(), {}, {}, {}, {})
}
