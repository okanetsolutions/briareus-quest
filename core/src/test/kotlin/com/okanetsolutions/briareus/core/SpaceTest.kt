package com.okanetsolutions.briareus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceTest {
    private fun SpaceLayout.at(panel: SpacePanel) = this[panel.key]!!.second
    private val diff = SpacePanel.Diff("acme/api", 7)
    private val pull = SpacePanel.Pull("acme/api", 7)
    /** The space with the status panel opened beside the main window. */
    private val withStatus = SpaceLayout.START.show(SpacePanel.Status, focus = false)

    @Test fun opensWithTheMainWindowAheadAndTheVoiceBesideIt() {
        val l = SpaceLayout.START
        assertEquals(SpacePanel.Main, l.front)
        assertEquals("to the right", l.at(SpacePanel.Voice).where)
        assertNull(l[SpacePanel.Status.key])
        // The status panel opens where it belongs, on the left.
        assertEquals("to the left", withStatus.at(SpacePanel.Status).where)
    }

    @Test fun somethingShownComesToTheFrontAndTheMainWindowStepsAside() {
        val l = withStatus.show(diff)
        assertEquals(diff, l.front)
        assertEquals(-100.0, l.at(SpacePanel.Main).yaw, 0.0)
        // Status and voice keep their places.
        assertEquals(-55.0, l.at(SpacePanel.Status).yaw, 0.0)
        assertEquals(55.0, l.at(SpacePanel.Voice).yaw, 0.0)
    }

    @Test fun eachPullRequestIsAScreenOfItsOwn() {
        val l = withStatus.show(pull).show(SpacePanel.Pull("acme/api", 9)).show(diff)
        assertEquals(6, l.panels.size)
        assertEquals(diff, l.front)
        // Every screen has a place of its own.
        assertEquals(l.panels.size, l.panels.map { it.second.yaw to it.second.height }.toSet().size)
        assertEquals(SpacePanel.Pull("acme/api", 9), l.latest("pull"))
    }

    @Test fun bringingAPanelForwardSwapsItWithTheOneInFront() {
        val l = withStatus.move("status", SpaceMove.FRONT)!!
        assertEquals(SpacePanel.Status, l.front)
        assertEquals(-55.0, l.at(SpacePanel.Main).yaw, 0.0)
    }

    @Test fun showingTheSameDiffAgainMovesItToTheFileAsked() {
        val l = SpaceLayout.START.show(diff).show(SpacePanel.Diff("acme/api", 7, "src/a.kt"))
        assertEquals(1, l.panels.count { it.first.kind == "diff" })
        assertEquals(SpacePanel.Diff("acme/api", 7, "src/a.kt"), l.front)
    }

    @Test fun aConversationShownBesideTakesAFreePlace() {
        val one = SpacePanel.Conversation("s1")
        val l = withStatus.show(one, focus = false)
        assertEquals(SpacePanel.Main, l.front)
        assertEquals(-100.0, l.at(one).yaw, 0.0)
        val two = SpacePanel.Conversation("s2")
        assertEquals(-145.0, l.show(two, focus = false).at(two).yaw, 0.0)
    }

    @Test fun aFullRingFillsTheRowAboveThenClosesTheOldest() {
        var l = withStatus
        (1..SpaceLayout.MAX_EXTRA).forEach { l = l.show(SpacePanel.Pull("acme/api", it)) }
        assertEquals(3 + SpaceLayout.MAX_EXTRA, l.panels.size)
        assertTrue(l.panels.any { it.second.height == SpaceLayout.ROW_HEIGHT })
        assertEquals(l.panels.size, l.panels.map { it.second.yaw to it.second.height }.toSet().size)
        l = l.show(SpacePanel.Pull("acme/api", 99))
        assertEquals(3 + SpaceLayout.MAX_EXTRA, l.panels.size)
        assertNull(l[SpacePanel.Pull("acme/api", 1).key])
        assertNotNull(l[SpacePanel.Main.key])
    }

    @Test fun movesStayWithinReach() {
        var l = SpaceLayout.START
        repeat(20) { l = l.move("main", SpaceMove.CLOSER)!! }
        assertEquals(SpaceLayout.MIN_DISTANCE, l.at(SpacePanel.Main).distance, 0.0)
        repeat(20) { l = l.move("main", SpaceMove.BIGGER)!! }
        assertEquals(SpaceLayout.MAX_SCALE, l.at(SpacePanel.Main).scale, 0.0)
        repeat(7) { l = l.move("voice", SpaceMove.RIGHT)!! }
        assertEquals(SpaceLayout.wrap(55.0 + 7 * 30), l.at(SpacePanel.Voice).yaw, 0.0)
        assertEquals("behind you", Placement(-170.0, 1.0).where)
    }

    @Test fun theMainWindowCannotBeClosedButOthersCan() {
        assertNull(SpaceLayout.START.move("main", SpaceMove.CLOSE))
        assertNull(SpaceLayout.START.move("nothing", SpaceMove.LEFT))
        assertNull(withStatus.move("status", SpaceMove.CLOSE)!!["status"])
    }

    @Test fun resetPutsEverythingBack() {
        val preview = SpacePanel.Preview("s1", "https://x")
        val l = withStatus.show(preview).move("status", SpaceMove.FARTHER)!!.move("x", SpaceMove.RESET)!!
        assertEquals(SpacePanel.Main, l.front)
        assertEquals(1.4, l.at(SpacePanel.Status).distance, 0.0)
        assertEquals(100.0, l.at(preview).yaw, 0.0)
    }

    @Test fun readsMovesAndSurroundingsLoosely() {
        assertEquals(SpaceMove.CLOSER, SpaceMove.of(" Closer"))
        assertNull(SpaceMove.of("sideways"))
        assertEquals(Surroundings.VIRTUAL, Surroundings.of("virtual"))
        assertEquals("pull", SpacePanel.Pull("a/b", 1).kind)
    }
}
