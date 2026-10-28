package dev.malzbro.wristshot.core

import dev.malzbro.wristshot.core.csv.CsvFormat
import dev.malzbro.wristshot.core.model.EventLabel
import dev.malzbro.wristshot.core.model.EventSource
import dev.malzbro.wristshot.core.model.RawSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CsvFormatTest {
    private fun fixed(v: Double, d: Int) = StringBuilder().also { CsvFormat.appendFixed(it, v, d) }.toString()

    @Test
    fun `fixed formatting rounds and pads`() {
        assertEquals("9.8100", fixed(9.81, 4))
        assertEquals("-0.0001", fixed(-0.00005, 4))
        assertEquals("0.0000", fixed(-0.00004, 4))
        assertEquals("12", fixed(12.4, 0))
        assertEquals("-3.142", fixed(-3.14159, 3))
        assertEquals("1.0000", fixed(0.99999, 4))
        assertEquals("NaN", fixed(Double.NaN, 4))
    }

    @Test
    fun `raw row matches header column count`() {
        val sample = RawSample(
            2_500_000_000L, 1.0, 2.0, 9.81, 0.1, -0.2, 0.3,
            0.0, 0.0, 9.81, 0.0, 0.0, 0.0, 1.0,
        )
        val sb = StringBuilder()
        CsvFormat.appendRawRow(sb, sample, sessionStartNs = 500_000_000L)
        val cells = sb.toString().trimEnd().split(',')
        assertEquals(CsvFormat.RAW_COLUMNS.size, cells.size)
        assertEquals("2500000000", cells[0])
        assertEquals("2.0000", cells[1])
        assertEquals("9.8100", cells[4])
    }

    @Test
    fun `event row has label and source`() {
        val row = CsvFormat.eventRow(
            3_000_000_000L, 1_000_000_000L, "2026-10-28T18:00:00.000",
            EventLabel.MAKE, EventSource.SCREEN_BUTTON,
        )
        assertEquals("3000000000,2.0000,2026-10-28T18:00:00.000,make,button\n", row)
        assertEquals(CsvFormat.EVENT_COLUMNS.size, row.trimEnd().split(',').size)
    }
}
