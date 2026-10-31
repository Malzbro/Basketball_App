package dev.malzbro.wristshot.core

import dev.malzbro.wristshot.core.csv.CsvFormat
import dev.malzbro.wristshot.core.detector.Candidate
import dev.malzbro.wristshot.core.detector.RejectReason
import dev.malzbro.wristshot.core.detector.ShotFeatures
import dev.malzbro.wristshot.core.model.EventLabel
import dev.malzbro.wristshot.core.model.EventSource
import dev.malzbro.wristshot.core.model.RawSample
import dev.malzbro.wristshot.core.session.SessionCounts
import dev.malzbro.wristshot.core.session.SessionCsvWriter
import dev.malzbro.wristshot.core.session.SessionFiles
import dev.malzbro.wristshot.core.session.SessionMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.StringWriter
import java.time.LocalDateTime

class SessionCsvWriterTest {
    private fun sample(t: Long) = RawSample(t, 0.0, 0.0, 9.8, 0.0, 0.0, 0.0, 0.0, 0.0, 9.8, 0.0, 0.0, 0.0, 1.0)

    @Test
    fun `raw and event files share the same clock`() {
        val raw = StringWriter()
        val events = StringWriter()
        val start = 1_000_000_000L
        val detections = StringWriter()
        val writer = SessionCsvWriter(raw, events, detections, start)
        for (i in 0 until 300) writer.writeSample(sample(start + i * 10_000_000L))
        writer.writeEvent(start + 1_500_000_000L, "2026-10-28T18:00:01.500", EventLabel.MISS, EventSource.HARDWARE_KEY)
        writer.close()

        val rawLines = raw.toString().lines().filter { it.isNotEmpty() }
        assertEquals(CsvFormat.RAW_HEADER, rawLines.first())
        assertEquals(301, rawLines.size)
        val eventLine = events.toString().lines()[1]
        assertEquals("2500000000,1.5000,2026-10-28T18:00:01.500,miss,hardware_key", eventLine)
        // The raw row at t_rel_s = 1.5 has exactly the event's t_ns.
        assertTrue(rawLines.any { it.startsWith("2500000000,1.5000,") })

        assertEquals(300, writer.stats.count)
        assertEquals(100.0, writer.stats.meanRateHz, 1e-6)
        assertEquals(0, writer.stats.dropouts)
        assertEquals(CsvFormat.DETECTIONS_HEADER, detections.toString().trimEnd())
    }

    @Test
    fun `detection rows have one cell per column`() {
        val detections = StringWriter()
        val writer = SessionCsvWriter(StringWriter(), StringWriter(), detections, 1_000_000_000L)
        val features = ShotFeatures(3_000_000_000L, 11.5, 80.0, 1.3, -64.0, 60.0, 4.0, 0.4)
        writer.writeDetection(Candidate(features, emptyList()))
        writer.writeDetection(Candidate(features, listOf(RejectReason.SHORT_HOLD, RejectReason.WEAK_FLICK)))
        val rows = detections.toString().trimEnd().lines()
        assertEquals("3000000000,2.0000,true,,11.5000,80.0000,1.3000,-64.0000,60.0000,4.0000,0.4000", rows[1])
        assertTrue(rows[2].startsWith("3000000000,2.0000,false,short_hold+weak_flick,"))
        rows.forEach { assertEquals(CsvFormat.DETECTION_COLUMNS.size, it.split(',').size) }
    }

    @Test
    fun `stats count dropouts`() {
        val writer = SessionCsvWriter(StringWriter(), StringWriter(), StringWriter(), 0)
        writer.writeSample(sample(0))
        writer.writeSample(sample(10_000_000))
        writer.writeSample(sample(510_000_000)) // 500 ms gap
        assertEquals(1, writer.stats.dropouts)
        assertEquals(500_000_000, writer.stats.maxGapNs)
    }

    @Test
    fun `session file names`() {
        val files = SessionFiles.create(File("sessions"), LocalDateTime.of(2026, 10, 28, 18, 5, 9))
        assertEquals("session_20261028_180509", files.id)
        assertEquals("session_20261028_180509_raw.csv", files.raw.name)
        assertEquals("session_20261028_180509_events.csv", files.events.name)
        assertEquals("session_20261028_180509_detections.csv", files.detections.name)
    }

    @Test
    fun `counts and metadata json`() {
        val counts = SessionCounts().plus(EventLabel.MAKE).plus(EventLabel.MAKE).plus(EventLabel.MISS)
        assertEquals(3, counts.shots)
        val json = SessionMetadata(
            sessionId = "session_x", appVersion = "0.1", deviceModel = "SM-L310 \"test\"",
            androidSdk = 34, startWallTime = "2026", startElapsedNs = 0, stopElapsedNs = 2_000_000_000,
            requestedSamplingPeriodUs = 10_000, maxReportLatencyUs = 200_000, sensors = emptyList(),
            sampleCount = 200, meanSampleRateHz = 100.0, maxGapMs = 10.0, dropouts = 0, counts = counts,
            detectedShots = 4, detector = mapOf("hold_min_s" to 0.3),
        ).toJson()
        assertTrue(json.contains("\"device_model\": \"SM-L310 \\\"test\\\"\""))
        assertTrue(json.contains("\"duration_s\": 2.0"))
        assertTrue(json.contains("\"makes\": 2"))
        assertTrue(json.contains("\"detected_shots\": 4"))
        assertTrue(json.contains("\"detector\": {\"hold_min_s\": 0.3}"))
    }
}
