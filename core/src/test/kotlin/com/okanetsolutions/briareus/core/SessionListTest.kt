package com.okanetsolutions.briareus.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SessionListTest {
    private val projects = listOf(Project("o/a", "Alpha", false, emptyList()), Project("o/b", "", false, emptyList()))
    private fun s(id: String, repo: String, created: String, extra: String = "") =
        Session(json("""{"id":"$id","repo":"$repo","title":"Title $id","status":"idle","createdAt":"$created","branch":"feat/$id"$extra}"""))

    @Test fun groupsNewestFirstAndHidesWorkers() {
        val sessions = listOf(
            s("1", "o/a", "2026-01-01T00:00:00Z"), s("2", "o/a", "2026-02-01T00:00:00.000Z"),
            s("3", "o/a", "2026-03-01T00:00:00Z", ""","parentId":"2""""), s("4", "o/z", "2026-01-01T00:00:00Z"),
        )
        val groups = SessionList.group(projects, sessions)
        assertEquals(listOf("Alpha", "b"), groups.map { it.project.title })
        assertEquals(listOf("2", "1"), groups[0].sessions.map { it.id })
        assertEquals(emptyList<Session>(), groups[1].sessions)
    }

    @Test fun searches() {
        val sessions = listOf(s("1", "o/a", "2026-01-01T00:00:00Z"), s("22", "o/b", "2026-01-01T00:00:00Z"))
        assertEquals(listOf("b" to listOf("22")), SessionList.group(projects, sessions, "feat/22").map { it.project.title to it.sessions.map(Session::id) })
        assertEquals(listOf("Alpha" to listOf("1")), SessionList.group(projects, sessions, "alpha").map { it.project.title to it.sessions.map(Session::id) })
    }

    @Test fun countsAndAges() {
        val sessions = listOf(
            s("1", "o/a", "2026-01-01T00:00:00Z", ""","awaitingAnswer":true"""),
            Session(json("""{"id":"2","repo":"o/a","status":"running"}""")),
            s("3", "o/a", "2026-01-01T00:00:00Z"),
        )
        assertEquals(SessionList.Counts(working = 1, needsYou = 1, ready = 1), SessionList.counts(sessions))
        assertEquals(listOf("1"), SessionList.needingYou(sessions).map { it.id })
        val now = Instant.parse("2026-01-10T00:00:00Z")
        assertEquals("now", SessionList.age(now.minusSeconds(30), now))
        assertEquals("5m", SessionList.age(now.minusSeconds(300), now))
        assertEquals("3h", SessionList.age(now.minusSeconds(3 * 3600), now))
        assertEquals("2d", SessionList.age(now.minusSeconds(2 * 86400), now))
        assertEquals("2w", SessionList.age(now.minusSeconds(15 * 86400), now))
        assertEquals("$0.42", SessionList.cost(0.4249))
        assertEquals("<$0.01", SessionList.cost(0.001))
    }
}
