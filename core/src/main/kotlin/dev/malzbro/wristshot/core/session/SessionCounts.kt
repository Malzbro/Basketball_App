package dev.malzbro.wristshot.core.session

import dev.malzbro.wristshot.core.model.EventLabel

/** Live counters shown on the watch while a session runs. */
data class SessionCounts(val makes: Int = 0, val misses: Int = 0, val unlabeled: Int = 0) {
    val shots: Int get() = makes + misses + unlabeled

    fun plus(label: EventLabel): SessionCounts = when (label) {
        EventLabel.MAKE -> copy(makes = makes + 1)
        EventLabel.MISS -> copy(misses = misses + 1)
        EventLabel.SHOT -> copy(unlabeled = unlabeled + 1)
    }
}
