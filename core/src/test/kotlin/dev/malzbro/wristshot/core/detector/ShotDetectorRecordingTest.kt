package dev.malzbro.wristshot.core.detector

import dev.malzbro.wristshot.core.model.RawSample
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.File
import java.util.zip.ZipFile

/**
 * Runs the live detector over a real recorded session (raw CSV + your Make/Miss
 * labels) and checks it finds the labeled shots. Skipped if recordings/ is missing.
 */
class ShotDetectorRecordingTest {
    private val recordings = File(System.getProperty("wristshot.recordings") ?: "../recordings")

    @Test
    fun `detects the labeled shots in session_20261030_163316`() {
        val prefix = "session_20261030_163316"
        val raw = File(recordings, "${prefix}_raw.zip")
        assumeTrue(raw.exists(), "recording not found: $raw")

        val shots = mutableListOf<Long>()
        val detector = ShotDetector { if (it.isShot) shots += it.features.tReleaseNs }
        ZipFile(raw).use { zip ->
            val entry = zip.entries().asSequence().first { it.name.endsWith(".csv") }
            zip.getInputStream(entry).bufferedReader().use { readSamples(it, detector::onSample) }
        }
        val labels = File(recordings, "${prefix}_events.csv").readLines().drop(1)
            .filter { it.isNotBlank() }
            .map { it.substringBefore(',').toLong() }

        val result = LabelMatcher.score(shots, labels)
        println("Kotlin detector on $prefix: $result")
        assertTrue(result.f1 >= MIN_F1, "F1 ${result.f1} below $MIN_F1: $result")
    }

    private fun readSamples(reader: BufferedReader, onSample: (RawSample) -> Unit) {
        val header = reader.readLine().split(',')
        val col = header.withIndex().associate { (i, name) -> name to i }
        reader.lineSequence().forEach { line ->
            val c = line.split(',')
            fun d(name: String) = c[col.getValue(name)].toDouble()
            onSample(
                RawSample(
                    c[col.getValue("t_ns")].toLong(),
                    d("ax"), d("ay"), d("az"), d("gx"), d("gy"), d("gz"),
                    d("grav_x"), d("grav_y"), d("grav_z"), d("qx"), d("qy"), d("qz"), d("qw"),
                ),
            )
        }
    }

    private companion object {
        /** The Python prototype scores 0.90 on this session; fail if the port falls well below. */
        const val MIN_F1 = 0.85
    }
}
