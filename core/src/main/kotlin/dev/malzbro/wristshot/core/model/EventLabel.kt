package dev.malzbro.wristshot.core.model

/** What a row in the events CSV means. [csvName] is the value written to the file. */
enum class EventLabel(val csvName: String) {
    MAKE("make"),
    MISS("miss"),

    /** A shot happened but the outcome was not given (e.g. "mark shot" button). */
    SHOT("shot"),
}

/** How the event was entered, so you can filter or debug later. */
enum class EventSource(val csvName: String) {
    SCREEN_BUTTON("button"),
    HARDWARE_KEY("hardware_key"),
}
