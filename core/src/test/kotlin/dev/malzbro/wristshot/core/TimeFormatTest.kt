package dev.malzbro.wristshot.core

import dev.malzbro.wristshot.core.util.formatElapsed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TimeFormatTest {
    @Test
    fun `formats minutes and hours`() {
        assertEquals("00:00", formatElapsed(0))
        assertEquals("00:00", formatElapsed(-5_000))
        assertEquals("01:15", formatElapsed(75_000))
        assertEquals("59:59", formatElapsed(3_599_999))
        assertEquals("1:02:05", formatElapsed(3_725_000))
    }
}
