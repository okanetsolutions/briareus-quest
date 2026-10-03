package com.okanetsolutions.briareus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TriageTest {
    private val session = Session(
        json(
            """{"id":"s","status":"idle","reviewLoop":{"triage":{"round":2,"prNumber":14,"findings":[
            {"key":"a","title":"Null deref","file":"x.kt","line":3,"url":"https://gh/x"},
            {"key":"b","title":"Style","url":"javascript:alert(1)"},
            {"title":"No key"}],
            "drafts":{"verdicts":{"b":{"decision":"dismissed","reason":"noise"}}}}}}""",
        ),
    )

    @Test fun readsTheLoopsRound() {
        val t = Triage.of(session)!!
        assertEquals("Round 2 findings · PR #14", t.title)
        assertEquals("x.kt:3", t.findings[0].location)
        assertEquals("https://gh/x", t.findings[0].url)
        assertNull(t.findings[1].url)
    }

    @Test fun verdictsFallBackToDraftsThenOptional() {
        val t = Triage.of(session)!!
        assertEquals(
            listOf(mapOf("key" to "a", "decision" to "optional"), mapOf("key" to "b", "decision" to "dismissed", "reason" to "noise")),
            t.verdicts(emptyMap()),
        )
        assertEquals("Complete · nothing to fix, approve and close", t.completeLabel(emptyMap()))
        assertEquals("Complete · send 1 to be fixed", t.completeLabel(mapOf("a" to "fix")))
    }

    @Test fun somebodyElsesReviewTakesNoVerdicts() {
        val t = Triage.of(Session(json("""{"id":"s","reviewTriage":{"mine":false,"findings":[{"key":"a","title":"t"}]}}""")))!!
        assertFalse(t.mine)
        assertEquals(emptyList<Map<String, String>>(), t.verdicts(mapOf("a" to "fix")))
        assertEquals("Complete", t.completeLabel(emptyMap()))
        assertNull(Triage.of(Session(json("""{"id":"s","reviewTriage":{"findings":[]}}"""))))
    }
}
