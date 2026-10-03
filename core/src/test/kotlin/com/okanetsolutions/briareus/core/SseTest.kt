package com.okanetsolutions.briareus.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SseTest {
    @Test fun dispatchesAtBlankLines() {
        val events = ArrayList<SseEvent>()
        val p = SseParser { events += it }
        """
        : ping
        event: session
        data: {"id":"a"}

        id: 7
        data: line one
        data:line two

        data: no blank line yet
        """.trimIndent().lines().forEach(p::line)
        assertEquals(listOf(SseEvent("session", "{\"id\":\"a\"}", null), SseEvent(null, "line one\nline two", "7")), events)
    }

    @Test fun ignoresCarriageReturnsAndEmptyEvents() {
        val events = ArrayList<SseEvent>()
        val p = SseParser { events += it }
        listOf("event: x\r", "\r", "data: y\r", "\r").forEach(p::line)
        assertEquals(listOf(SseEvent(null, "y", null)), events)
    }
}
