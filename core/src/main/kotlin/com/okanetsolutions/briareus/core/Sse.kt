package com.okanetsolutions.briareus.core

/** One server-sent event. [event] is null for an unnamed one; [id] is the `id:` field when it had one. */
data class SseEvent(val event: String?, val data: String, val id: String?)

/**
 * Reads a server-sent event stream line by line, as the HTML spec describes it: `event`, `data` (joined with newlines),
 * `id`, comments ignored, and an event dispatched at each blank line that follows data.
 */
class SseParser(private val emit: (SseEvent) -> Unit) {
    private var event: String? = null
    private var id: String? = null
    private val data = StringBuilder()
    private var hasData = false

    fun line(raw: String) {
        val line = raw.removeSuffix("\r")
        if (line.isEmpty()) {
            if (hasData) emit(SseEvent(event, data.toString(), id))
            event = null; id = null; data.setLength(0); hasData = false
            return
        }
        if (line.startsWith(":")) return
        val colon = line.indexOf(':')
        val field = if (colon >= 0) line.substring(0, colon) else line
        var value = if (colon >= 0) line.substring(colon + 1) else ""
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> { if (hasData) data.append('\n'); data.append(value); hasData = true }
            "id" -> if ('\u0000' !in value) id = value
        }
    }
}
