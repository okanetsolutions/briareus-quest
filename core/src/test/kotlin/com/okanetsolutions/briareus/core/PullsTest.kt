package com.okanetsolutions.briareus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PullsTest {
    private val board = Board.parse(
        json(
            """{"repo":"o/a","syncedAt":"2026-10-03T10:00:00Z","issues":[],"stacks":{},"pulls":[
            {"number":303,"title":"Align the link","url":"https://github.com/o/a/pull/303","draft":false,"author":"nadin","assignees":[],
             "branch":"fix/link","baseBranch":"main","updatedAt":"2026-10-03T09:57:00Z","labels":[{"name":"code-approved","color":"0e8a16"}],
             "mergeable":"mergeable","checks":"success","reviewDecision":null,"recommended":"qa","stack":null,"issues":[]},
            {"number":364,"title":"Fix ACL","author":"Bot","labels":[{"name":"blocked","color":"zz"}],"mergeable":"conflicting","checks":"pending",
             "stack":{"position":2,"total":3,"id":360,"partial":true},"issues":[{"number":361,"title":"Clarify ACL","state":"open","url":"https://x"}]},
            {"title":"No number"}
        ]}""",
        ),
    )

    @Test fun readsTheBoard() {
        assertEquals("o/a", board.repo)
        assertEquals(listOf(303, 364), board.pulls.map { it.number })
        val first = board.pulls[0]
        assertEquals("fix/link", first.branch)
        assertEquals("checks", first.checksLabel)
        assertEquals("qa", first.recommended)
        assertEquals(0xFF0E8A16L, first.labels.single().argb)
        assertNull(first.stack)
        assertFalse(first.conflicting)
        val second = board.pulls[1]
        assertTrue(second.conflicting)
        assertEquals("checks running", second.checksLabel)
        assertEquals("2/3+", second.stack)
        assertNull(second.labels.single().argb)
        assertEquals(listOf(361), second.issues.map { it.number })
    }

    @Test fun filtersByAuthorAndLabel() {
        assertEquals(listOf("Bot", "nadin"), board.authors)
        assertEquals(listOf("blocked", "code-approved"), board.labels)
        assertEquals(listOf(364), board.filter("bot", null).map { it.number })
        assertEquals(listOf(303), board.filter(null, "code-approved").map { it.number })
        assertEquals(emptyList<Int>(), board.filter("nadin", "blocked").map { it.number })
        assertEquals(2, board.filter(null, null).size)
    }

    @Test fun readsErrands() {
        val errands = Errand.list(json(
            """{"actions":[{"id":"review","label":"Code review","input":null},{"id":"feedback","label":"Give feedback","input":{"label":"What to say","required":true}},""" +
                """{"label":"no id"}]}""",
        ))
        assertEquals(listOf("review", "feedback"), errands.map { it.id })
        assertNull(errands[0].inputLabel)
        assertEquals("What to say", errands[1].inputLabel)
        assertTrue(errands[1].inputRequired)
    }

    @Test fun readsTheOverview() {
        val pr = PullOverview.parse(
            json(
                """{"pr":{"number":5,"title":"T","state":"open","draft":true,"headSha":"abc","headRef":"feat","baseRef":"main","additions":3,"deletions":1,
                "changedFiles":2,"commits":1,"commitList":[{"sha":"abc","message":"First","url":"https://c"}],"issues":[],
                "reviews":[{"user":"r","state":"CHANGES_REQUESTED","url":null}],
                "checks":{"total":3,"passed":1,"failed":1,"pending":1,"runs":[{"name":"ci","status":"completed","conclusion":"success","failed":false},""" +
                """{"name":"lint","status":"in_progress","conclusion":null}]}}}""",
            ),
        )!!
        assertEquals("draft", pr.state)
        assertEquals("abc", pr.headSha)
        assertEquals("1 passed · 1 failed · 1 running", pr.checksSummary)
        assertEquals(listOf("success", "in progress"), pr.checks.map { it.label })
        assertTrue(pr.checks[1].pending)
        assertEquals("changes requested", pr.reviews.single().label)
        assertEquals("First", pr.commits.single().message)
        assertNull(PullOverview.parse(json("""{"pr":{}}""")))
    }

    @Test fun readsFindings() {
        val f = PullFinding.list(json("""{"findings":[{"key":"k","severity":"high","title":"Bug","file":"a.kt","line":4,"decision":"fix","fixed":false},{"title":"no key"}]}""")).single()
        assertEquals("a.kt:4", f.where)
        assertEquals("fix", f.decision)
    }
}
