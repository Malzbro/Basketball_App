package dev.malzbro.wristshot.core.util

import java.util.Locale

/** 75 000 ms -> "01:15", 3 725 000 ms -> "1:02:05". Negative values show as "00:00". */
fun formatElapsed(ms: Long): String {
    val totalS = (ms / 1000).coerceAtLeast(0)
    val h = totalS / 3600
    val m = (totalS % 3600) / 60
    val s = totalS % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}
