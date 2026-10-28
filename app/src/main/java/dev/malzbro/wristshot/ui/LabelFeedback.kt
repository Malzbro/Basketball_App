package dev.malzbro.wristshot.ui

import android.view.HapticFeedbackConstants
import android.view.View
import dev.malzbro.wristshot.core.model.EventLabel
import dev.malzbro.wristshot.core.model.EventSource
import dev.malzbro.wristshot.recording.SessionRepository

/**
 * Records a label and buzzes to confirm it. Used by the on-screen buttons and
 * the hardware keys so both behave the same.
 * @return false (and no buzz) if no session is recording.
 */
fun View.recordLabelWithHaptic(label: EventLabel, source: EventSource): Boolean {
    val recorded = SessionRepository.label(label, source)
    if (recorded) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    return recorded
}
