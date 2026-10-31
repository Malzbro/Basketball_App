package dev.malzbro.wristshot.core.session

import dev.malzbro.wristshot.core.csv.CsvFormat
import dev.malzbro.wristshot.core.detector.Candidate
import dev.malzbro.wristshot.core.model.EventLabel
import dev.malzbro.wristshot.core.model.EventSource
import dev.malzbro.wristshot.core.model.RawSample
import java.io.Writer

/**
 * Writes the raw, events and detections CSVs for one session.
 *
 * Pass buffered writers: rows collect in memory and reach storage in large
 * chunks, which is much kinder to the battery than writing every row.
 * Not thread-safe: use it from one thread (the recorder's sensor thread).
 */
class SessionCsvWriter(
    private val raw: Writer,
    private val events: Writer,
    private val detections: Writer,
    private val sessionStartNs: Long,
) {
    private val row = StringBuilder(256)
    private val writers = listOf(raw, events, detections)
    val stats = SampleStats()

    init {
        raw.write(CsvFormat.RAW_HEADER + "\n")
        events.write(CsvFormat.EVENTS_HEADER + "\n")
        detections.write(CsvFormat.DETECTIONS_HEADER + "\n")
    }

    fun writeSample(sample: RawSample) {
        row.setLength(0)
        CsvFormat.appendRawRow(row, sample, sessionStartNs)
        raw.append(row)
        stats.add(sample.tNs)
    }

    fun writeEvent(tNs: Long, wallTimeIso: String, label: EventLabel, source: EventSource) {
        events.write(CsvFormat.eventRow(tNs, sessionStartNs, wallTimeIso, label, source))
        // Events are rare and precious: push them to storage right away.
        events.flush()
    }

    fun writeDetection(candidate: Candidate) {
        detections.write(CsvFormat.detectionRow(candidate, sessionStartNs))
        detections.flush()
    }

    fun flush() = writers.forEach { it.flush() }

    fun close() = writers.forEach { it.close() }
}
