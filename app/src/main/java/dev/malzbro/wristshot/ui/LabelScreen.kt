package dev.malzbro.wristshot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Text
import androidx.wear.tooling.preview.devices.WearDevices
import dev.malzbro.wristshot.config.AppConfig
import dev.malzbro.wristshot.core.model.EventLabel
import dev.malzbro.wristshot.core.model.EventSource
import dev.malzbro.wristshot.recording.SessionUiState
import kotlinx.coroutines.delay

/**
 * Manual labeling for Phase 1: the top half of the screen is MAKE, the bottom
 * half is MISS, so you can hit them without looking carefully. The middle strip
 * has "Shot?" (shot with unknown outcome) and "Done".
 */
@Composable
fun LabelScreen(
    state: SessionUiState,
    onDone: () -> Unit,
    onLabel: (EventLabel, EventSource) -> Unit,
) {
    val view = LocalView.current
    if (AppConfig.KEEP_SCREEN_ON_WHILE_LABELING && state.isRecording) {
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
    }

    // Optional: the watch's Back button marks a shot instead of leaving this screen.
    BackHandler(enabled = state.isRecording && state.backKeyMarksShot) {
        onLabel(EventLabel.SHOT, EventSource.HARDWARE_KEY)
    }

    // Briefly highlight the half that was just pressed.
    var flash by remember { mutableStateOf<EventLabel?>(null) }
    LaunchedEffect(state.lastLabelAtMs) {
        if (state.lastLabel != null && state.lastLabelAtMs > 0) {
            flash = state.lastLabel
            delay(AppConfig.LABEL_FLASH_MS)
            flash = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(WristShotColors.Background)) {
        LabelHalf(
            text = "MAKE",
            color = if (flash == EventLabel.MAKE) WristShotColors.MakeFlash else WristShotColors.Make,
            enabled = state.isRecording,
            modifier = Modifier.weight(1f),
            onClick = { onLabel(EventLabel.MAKE, EventSource.SCREEN_BUTTON) },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactChip(
                onClick = { onLabel(EventLabel.SHOT, EventSource.SCREEN_BUTTON) },
                label = { Text("Shot?") },
                enabled = state.isRecording,
                colors = ChipDefaults.secondaryChipColors(),
            )
            Text(
                text = if (state.isRecording) "${state.counts.makes}/${state.counts.shots}" else "Not rec",
                fontWeight = FontWeight.Bold,
            )
            CompactChip(
                onClick = onDone,
                label = { Text("Done") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        LabelHalf(
            text = "MISS",
            color = if (flash == EventLabel.MISS) WristShotColors.MissFlash else WristShotColors.Miss,
            enabled = state.isRecording,
            modifier = Modifier.weight(1f),
            onClick = { onLabel(EventLabel.MISS, EventSource.SCREEN_BUTTON) },
        )
    }
}

@Composable
private fun LabelHalf(
    text: String,
    color: Color,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(if (enabled) color else WristShotColors.Disabled)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = WristShotText.LabelHalf,
            fontWeight = FontWeight.Bold,
            color = WristShotColors.OnColor,
        )
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun LabelScreenPreview() {
    LabelScreen(state = SessionUiState(isRecording = true), onDone = {}, onLabel = { _, _ -> })
}
