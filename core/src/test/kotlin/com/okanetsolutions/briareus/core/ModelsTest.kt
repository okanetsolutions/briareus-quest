package com.okanetsolutions.briareus.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

fun json(text: String): JsonObject = BriareusJson.parseToJsonElement(text) as JsonObject

class ModelsTest {
    @Test fun discovery() {
        val d = Discovery.parse(json("""{"version":1,"client":{"id":"c1","label":"Quest","repos":["o/a"],"permission":"manage","expiresAt":5},"transcribe":true,"extra":1}"""))!!
        assertEquals(1, d.version)
        assertEquals(listOf("o/a"), d.device.repos)
        assertTrue(d.device.canManage)
        assertEquals(true, d.transcribe)
        assertFalse(Device.parse(json("""{"id":"x","permission":"read"}"""))!!.canManage)
        assertNull(Discovery.parse(json("""{"client":{}}""")))
        assertNull(Discovery.voiceNotesOff(true))
    }

    @Test fun connectionRoundTrips() {
        val c = Connection(
            ServerAddress.parse("https://h.example")!!,
            Device("i", "Quest", "admin", emptyList(), 9),
            RouteCatalog(listOf(CatalogRoute("GET", "sessions", "read"))),
            null,
        )
        assertEquals(c.toJson(), Connection.parse(c.toJson())!!.toJson())
        assertTrue(c.can("sessions"))
        assertFalse(c.can("message"))
    }

    @Test fun sessionState() {
        fun s(extra: String) = Session(json("""{"id":"s","repo":"o/a","title":"T",$extra}"""))
        assertEquals(Session.State.WAITING, s(""""status":"idle","awaitingAnswer":true""").state)
        assertEquals(Session.State.FINDINGS, s(""""status":"idle","reviewTriage":{"findings":[]}""").state)
        assertEquals(Session.State.IDLE, s(""""status":"idle"""").state)
        assertEquals(Session.State.WORKING, s(""""status":"preparing"""").state)
        assertEquals(Session.State.FAILED, s(""""status":"failed"""").state)
        assertEquals(Session.State.CLOSED, s(""""status":"interrupted"""").state)
        assertTrue(s(""""status":"interrupted"""").isClosed)
        assertEquals("Untitled conversation", Session(json("""{"id":"s","title":" "}""")).title)
        assertEquals(42, s(""""prStatus":{"number":42,"url":"u"}""").pullNumber)
        assertEquals("https://x", s(""""serveLinks":[{"url":"https://x"}]""").serveUrl)
        assertNull(Session.parse(json("""{"title":"no id"}""")))
    }

    @Test fun transcriptDeduplicatesAndOrders() {
        val t = Transcript()
        assertTrue(t.append(BriareusJson.parseToJsonElement("""[{"seq":3,"kind":"text","text":"c"},{"seq":1,"kind":"user","text":"a"}]""") as JsonArray))
        assertFalse(t.append(BriareusJson.parseToJsonElement("""[{"seq":1,"kind":"user","text":"a"}]""") as JsonArray))
        t.append(BriareusJson.parseToJsonElement("""[{"seq":2,"kind":"status","status":"running"},{"kind":"no seq"}]""") as JsonArray)
        assertEquals(listOf(1L, 2L, 3L), t.events.map { it.seq })
        assertEquals(3L, t.cursor)
        assertEquals(listOf(true, false, true), t.events.map { it.visible })
    }

    @Test fun pendingQuestionEndsWithAReply() {
        val t = Transcript()
        t.append(BriareusJson.parseToJsonElement("""[{"seq":1,"kind":"ask","question":"Which?","options":[{"label":"A"},{"label":"B"},{}]}]""") as JsonArray)
        assertEquals(listOf("A", "B"), t.pendingQuestion()!!.options)
        t.append(BriareusJson.parseToJsonElement("""[{"seq":2,"kind":"user","text":"A"}]""") as JsonArray)
        assertNull(t.pendingQuestion())
    }

    @Test fun toolDetail() {
        assertEquals("ls", TranscriptEvent(json("""{"seq":1,"kind":"tool","name":"Bash","summary":"ls","text":"x"}""")).detail)
        assertEquals("x", TranscriptEvent(json("""{"seq":1,"kind":"text","summary":"ls","text":"x"}""")).detail)
    }

    @Test fun runtimes() {
        val c = RuntimeCatalog.parse(json("""{"default":{"providerId":2,"model":"m2","effort":"high"},"providers":[
            {"id":1,"label":"Claude","available":false,"models":[{"id":"m1","label":"Opus","efforts":["low","high"],"defaultEffort":"high"}],"defaultModel":"m1"},
            {"id":2,"label":"Codex","models":[{"id":"m2","label":"","efforts":[]},{"id":"m3","label":"Mini","efforts":["x"]}]}]}"""))
        assertEquals(RuntimeChoice(2, "m2", "high"), c.default)
        assertFalse(c.provider(1)!!.available)
        assertTrue(c.provider(2)!!.available)
        assertEquals(RuntimeChoice(1, "m1", "high"), c.choice(1))
        assertEquals(RuntimeChoice(2, "m3", "x"), c.choice(2, "m3"))
        assertEquals("Codex · m2 · high", c.label(c.default!!))
        assertNull(c.choice(9))
    }
}
