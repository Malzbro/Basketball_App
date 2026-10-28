package dev.malzbro.wristshot.core.csv

import dev.malzbro.wristshot.core.config.RecorderConfig
import dev.malzbro.wristshot.core.model.EventLabel
import dev.malzbro.wristshot.core.model.EventSource
import dev.malzbro.wristshot.core.model.RawSample

/**
 * Column layout of the CSV files. The Python analysis scripts read these exact
 * column names, so change both together.
 *
 * Time columns:
 *  - t_ns: sensor clock in nanoseconds (SystemClock.elapsedRealtimeNanos).
 *    Raw samples and events share this clock, so they line up exactly.
 *  - t_rel_s: seconds since the session started (t_ns - session start), for reading.
 */
object CsvFormat {
    val RAW_COLUMNS = listOf(
        "t_ns", "t_rel_s",
        "ax", "ay", "az",
        "gx", "gy", "gz",
        "grav_x", "grav_y", "grav_z",
        "qx", "qy", "qz", "qw",
    )

    val EVENT_COLUMNS = listOf("t_ns", "t_rel_s", "wall_time", "label", "source")

    val RAW_HEADER: String = RAW_COLUMNS.joinToString(",")
    val EVENTS_HEADER: String = EVENT_COLUMNS.joinToString(",")

    fun appendRawRow(sb: StringBuilder, s: RawSample, sessionStartNs: Long) {
        sb.append(s.tNs).append(',')
        appendFixed(sb, (s.tNs - sessionStartNs) / 1e9, RecorderConfig.CSV_TIME_DECIMALS)
        val d = RecorderConfig.CSV_VALUE_DECIMALS
        for (v in doubleArrayOf(
            s.ax, s.ay, s.az, s.gx, s.gy, s.gz,
            s.gravX, s.gravY, s.gravZ, s.qx, s.qy, s.qz, s.qw,
        )) {
            sb.append(',')
            appendFixed(sb, v, d)
        }
        sb.append('\n')
    }

    fun eventRow(
        tNs: Long,
        sessionStartNs: Long,
        wallTimeIso: String,
        label: EventLabel,
        source: EventSource,
    ): String {
        val sb = StringBuilder()
        sb.append(tNs).append(',')
        appendFixed(sb, (tNs - sessionStartNs) / 1e9, RecorderConfig.CSV_TIME_DECIMALS)
        sb.append(',').append(wallTimeIso)
        sb.append(',').append(label.csvName)
        sb.append(',').append(source.csvName)
        sb.append('\n')
        return sb.toString()
    }

    /**
     * Writes [value] with a fixed number of decimals, e.g. 9.81 -> "9.8100".
     * Much faster than String.format, which matters at 100 rows per second.
     * NaN is written as "NaN" (pandas reads it as missing).
     */
    fun appendFixed(sb: StringBuilder, value: Double, decimals: Int) {
        if (value.isNaN()) {
            sb.append("NaN"); return
        }
        if (value.isInfinite()) {
            sb.append(if (value > 0) "inf" else "-inf"); return
        }
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val scaled = Math.round(Math.abs(value) * scale)
        if (value < 0 && scaled != 0L) sb.append('-')
        sb.append(scaled / scale)
        if (decimals > 0) {
            sb.append('.')
            val frac = (scaled % scale).toString()
            repeat(decimals - frac.length) { sb.append('0') }
            sb.append(frac)
        }
    }
}
