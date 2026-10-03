package com.okanetsolutions.briareus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionTest {
    private fun s(status: String, extra: String = "") =
        Session(json("""{"id":"s1","repo":"o/app","title":"Fix login","status":"$status","lastText":"Done, pushed."$extra}"""))

    @Test fun theFirstCopyOnlySetsTheBaseline() {
        val t = AttentionTracker()
        assertNull(t.update(s("idle", ""","awaitingAnswer":true""")))
    }

    @Test fun alertsWhenATurnSettles() {
        val t = AttentionTracker()
        t.update(s("running"))
        val alert = t.update(s("idle"), "App")!!
        assertEquals(Alert.Kind.FINISHED, alert.kind)
        assertEquals("Fix login · App", alert.title)
        assertEquals("Done, pushed.", alert.text)
        assertTrue(alert.repliable)
        // The same state again says nothing.
        assertNull(t.update(s("idle")))
    }

    @Test fun questionsFindingsAndFailures() {
        val t = AttentionTracker()
        t.update(s("running"))
        assertEquals(Alert.Kind.QUESTION, t.update(s("idle", ""","awaitingAnswer":true"""))!!.kind)
        t.update(s("running"))
        val findings = t.update(s("idle", ""","reviewTriage":{"findings":[{},{}]}"""))!!
        assertEquals(Alert.Kind.FINDINGS, findings.kind)
        assertEquals("A review found 2 findings to decide.", findings.text)
        t.update(s("running"))
        assertEquals(Alert.Kind.FAILED, t.update(s("failed", ""","error":"Out of credit""""))!!.kind)
    }

    @Test fun noAlertForAReopenOrClose() {
        val t = AttentionTracker()
        t.update(s("closed"))
        assertNull(t.update(s("idle")))
        assertNull(t.update(s("closed")))
    }

    @Test fun workersAndResetsStayQuiet() {
        val t = AttentionTracker()
        t.update(s("running", ""","parentId":"orc""""))
        assertNull(t.update(s("idle", ""","parentId":"orc"""")))
        t.update(s("running"))
        t.reset()
        assertNull(t.update(s("idle")))
    }
}
