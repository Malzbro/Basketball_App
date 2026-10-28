package dev.malzbro.wristshot.core.session

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * File names for one session, e.g.
 *   sessions/session_20261028_181502_raw.csv
 *   sessions/session_20261028_181502_events.csv
 *   sessions/session_20261028_181502_meta.json
 */
data class SessionFiles(val id: String, val raw: File, val events: File, val meta: File) {
    companion object {
        private val ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        fun sessionId(start: LocalDateTime): String = "session_" + start.format(ID_FORMAT)

        fun create(dir: File, start: LocalDateTime): SessionFiles {
            val id = sessionId(start)
            return SessionFiles(
                id = id,
                raw = File(dir, "${id}_raw.csv"),
                events = File(dir, "${id}_events.csv"),
                meta = File(dir, "${id}_meta.json"),
            )
        }
    }
}
