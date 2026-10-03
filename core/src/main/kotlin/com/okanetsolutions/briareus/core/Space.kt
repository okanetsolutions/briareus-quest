package com.okanetsolutions.briareus.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A panel of the immersive space, by what it shows. [key] is how the voice names it: each pull request, diff, preview
 * and page has a panel of its own, so showing another adds a screen; showing the same one again brings it forward.
 */
sealed interface SpacePanel {
    val key: String
    /** What kind of panel it is: the voice says "the diff" for the latest of a kind. */
    val kind: String get() = key.substringBefore(':')

    /** The projects and conversations beside the chosen one: pairing until connected. Never closed. */
    data object Main : SpacePanel { override val key = "main" }
    data object Status : SpacePanel { override val key = "status" }
    data object Voice : SpacePanel { override val key = "voice" }
    data class Conversation(val sessionId: String) : SpacePanel { override val key = "conversation:$sessionId" }
    /** A project's open pull requests. */
    data class Pulls(val repo: String) : SpacePanel { override val key = "pulls:$repo" }
    data class Pull(val repo: String, val number: Int) : SpacePanel { override val key = "pull:$repo#$number" }
    /** A pull request's changed files, at [file] when one was asked for; asking for another file moves the same panel. */
    data class Diff(val repo: String, val number: Int, val file: String? = null) : SpacePanel { override val key = "diff:$repo#$number" }
    /** What a conversation's ▶ Run serves. */
    data class Preview(val sessionId: String, val url: String) : SpacePanel { override val key = "preview:$sessionId" }
    /** Any other page (GitHub, an issue, a check's log), kept inside the space rather than in the browser, which would end it. */
    data class Web(val url: String) : SpacePanel { override val key = "web:$url" }

    companion object {
        /** The kinds the voice can name instead of a key. */
        val KINDS = listOf("main", "status", "voice", "conversation", "pulls", "pull", "diff", "preview", "web")
    }
}

/**
 * Where a panel floats: [yaw] degrees around the user (0 straight ahead, positive to the right), [distance] metres
 * away, [height] metres above or below eye level, and [scale] times its usual size.
 */
data class Placement(val yaw: Double, val distance: Double, val height: Double = 0.0, val scale: Double = 1.0) {
    /** Where it is, as the user would say it. */
    val where: String
        get() {
            val a = abs(yaw)
            val side = if (yaw < 0) "left" else "right"
            return when {
                a < CENTERED -> "in front"
                a < 75 -> "to the $side"
                a < 125 -> "far to the $side"
                else -> "behind you"
            }
        }

    internal companion object {
        /** Within this many degrees of straight ahead, a panel is the one in front. */
        const val CENTERED = 15.0
    }
}

/** What the voice can ask of a panel. */
enum class SpaceMove(val wire: String) {
    FRONT("front"), LEFT("left"), RIGHT("right"), CLOSER("closer"), FARTHER("farther"),
    BIGGER("bigger"), SMALLER("smaller"), UP("up"), DOWN("down"), CLOSE("close"), RESET("reset");

    companion object {
        fun of(wire: String?): SpaceMove? = entries.firstOrNull { it.wire == wire?.trim()?.lowercase() }
    }
}

/** Where the panels float: the real room through the cameras, or a virtual one. */
enum class Surroundings(val wire: String) {
    PASSTHROUGH("passthrough"), VIRTUAL("virtual");

    companion object {
        fun of(wire: String?): Surroundings? = entries.firstOrNull { it.wire == wire?.trim()?.lowercase() }
    }
}

/**
 * The panels around the user and where each floats. Something newly shown comes to the front and what was there steps
 * aside to the nearest free place, so a spoken "show me" always lands in view. Immutable: every change is a new layout.
 */
data class SpaceLayout(val panels: List<Pair<SpacePanel, Placement>>) {
    operator fun get(key: String): Pair<SpacePanel, Placement>? = panels.firstOrNull { it.first.key == key }

    /** The panel straight ahead, if one is. */
    val front: SpacePanel? get() = panels.filter { abs(it.second.yaw) < Placement.CENTERED }.minByOrNull { abs(it.second.yaw) }?.first

    /**
     * Shows [panel]: replaces the one with its key, and brings it to the front when [focus], else to a free place. Past
     * [MAX_EXTRA] panels besides the main, status and voice ones, the one shown longest ago closes.
     */
    fun show(panel: SpacePanel, focus: Boolean = true): SpaceLayout {
        val existing = this[panel.key]
        val replaced = SpaceLayout(panels.map { if (it.first.key == panel.key) panel to it.second else it })
        if (existing != null) return if (focus) replaced.toFront(panel.key) else replaced
        val extra = panels.filter { it.first !in FIXED }
        val room = if (extra.size >= MAX_EXTRA) close(extra.first().first.key) else this
        val place = if (focus) home(panel).copy(yaw = 0.0, height = 0.0) else room.freePlace(home(panel))
        return SpaceLayout(room.panels + (panel to place)).let { if (focus) it.stepAside(panel.key, null) else it }
    }

    /** The newest panel of [kind] ("diff", "pull", ...), for when the voice names a kind rather than a panel. */
    fun latest(kind: String): SpacePanel? = panels.lastOrNull { it.first.kind == kind }?.first

    /** Applies [move] to the panel named [key]; null when there is no such panel or it cannot be closed. */
    @Suppress("CyclomaticComplexMethod") // One branch per move.
    fun move(key: String, move: SpaceMove): SpaceLayout? {
        if (move == SpaceMove.RESET) return reset()
        val (panel, at) = this[key] ?: return null
        fun with(p: Placement) = SpaceLayout(panels.map { if (it.first.key == key) panel to p else it })
        return when (move) {
            SpaceMove.FRONT -> toFront(key)
            SpaceMove.LEFT -> with(at.copy(yaw = wrap(at.yaw - STEP_DEGREES)))
            SpaceMove.RIGHT -> with(at.copy(yaw = wrap(at.yaw + STEP_DEGREES)))
            SpaceMove.CLOSER -> with(at.copy(distance = (at.distance - STEP_METRES).coerceAtLeast(MIN_DISTANCE)))
            SpaceMove.FARTHER -> with(at.copy(distance = (at.distance + STEP_METRES).coerceAtMost(MAX_DISTANCE)))
            SpaceMove.BIGGER -> with(at.copy(scale = (at.scale * SCALE_STEP).coerceAtMost(MAX_SCALE)))
            SpaceMove.SMALLER -> with(at.copy(scale = (at.scale / SCALE_STEP).coerceAtLeast(MIN_SCALE)))
            SpaceMove.UP -> with(at.copy(height = (at.height + STEP_HEIGHT).coerceAtMost(MAX_HEIGHT)))
            SpaceMove.DOWN -> with(at.copy(height = (at.height - STEP_HEIGHT).coerceAtLeast(-MAX_HEIGHT)))
            SpaceMove.CLOSE -> if (panel == SpacePanel.Main) null else close(key)
            SpaceMove.RESET -> reset()
        }
    }

    fun close(key: String): SpaceLayout = SpaceLayout(panels.filter { it.first.key != key || it.first == SpacePanel.Main })

    /** Where the user moved a panel by hand, kept so the next spoken move starts from there. */
    fun moved(key: String, place: Placement): SpaceLayout = SpaceLayout(panels.map { if (it.first.key == key) it.first to place else it })

    /** Every open panel back where it first appears, the main window in front. */
    fun reset(): SpaceLayout {
        var out = SpaceLayout(panels.filter { it.first in FIXED })
            .let { l -> SpaceLayout(l.panels.map { it.first to home(it.first) }) }
        panels.filter { it.first !in FIXED }.forEach { out = out.show(it.first, focus = false) }
        return out
    }

    /** What the voice is told: each panel, what it shows and where. */
    fun describe(title: (SpacePanel) -> String): List<Map<String, Any?>> = panels.sortedBy { it.second.yaw }.map { (panel, at) ->
        mapOf(
            "panel" to panel.key, "shows" to title(panel), "where" to at.where,
            "distance_m" to (at.distance * 10).roundToInt() / 10.0, "size" to if (at.scale == 1.0) null else "${(at.scale * 100).roundToInt()}%",
        )
    }

    private fun toFront(key: String): SpaceLayout {
        val (panel, at) = this[key] ?: return this
        if (abs(at.yaw) < Placement.CENTERED && abs(at.height) < ROW_HEIGHT / 2) return this
        return SpaceLayout(panels.map { if (it.first.key == key) panel to at.copy(yaw = 0.0, height = 0.0) else it }).stepAside(key, at)
    }

    /** Moves whatever else is in front out of [key]'s way: to where [key] was when it left a place, else to the nearest free one. */
    private fun stepAside(key: String, vacated: Placement?): SpaceLayout {
        var out = this
        panels.filter { (p, at) -> p.key != key && abs(at.yaw) < Placement.CENTERED && abs(at.height) < ROW_HEIGHT / 2 }.forEach { (p, at) ->
            val others = SpaceLayout(out.panels.filter { it.first.key != p.key })
            val preferred = home(p).yaw.takeIf { abs(it) >= Placement.CENTERED } ?: SLOTS[1]
            val to = vacated?.let { at.copy(yaw = it.yaw, height = it.height) } ?: others.freePlace(at.copy(yaw = preferred, height = 0.0))
            out = out.moved(p.key, to)
        }
        return out
    }

    /** [at] moved to the free slot nearest it, on the eye-level row first and then the row above. */
    private fun freePlace(at: Placement): Placement {
        fun taken(yaw: Double, height: Double) = panels.any { abs(wrap(it.second.yaw - yaw)) < SLOT_GAP && abs(it.second.height - height) < ROW_HEIGHT / 2 }
        for (height in listOf(0.0, ROW_HEIGHT)) {
            SLOTS.filter { !taken(it, height) }.minByOrNull { abs(wrap(it - at.yaw)) }?.let { return at.copy(yaw = it, height = height) }
        }
        return at
    }

    companion object {
        /** Where panels go around the user, nearest the front first, alternating sides; a second row floats above. */
        val SLOTS = listOf(0.0, -55.0, 55.0, -100.0, 100.0, -145.0, 145.0, 180.0)
        const val ROW_HEIGHT = 1.15
        /** Panels besides the main, status and voice ones; more than this and the oldest closes. */
        const val MAX_EXTRA = 10
        private val FIXED = setOf(SpacePanel.Main, SpacePanel.Status, SpacePanel.Voice)
        const val STEP_DEGREES = 30.0
        const val STEP_METRES = 0.3
        const val STEP_HEIGHT = 0.25
        const val SCALE_STEP = 1.25
        const val MIN_DISTANCE = 0.6
        const val MAX_DISTANCE = 4.0
        const val MIN_SCALE = 0.5
        const val MAX_SCALE = 2.5
        const val MAX_HEIGHT = 1.0

        /** The space as it opens: the main window ahead and the voice on the right. The status panel opens when asked for, on the left. */
        val START = SpaceLayout(listOf(SpacePanel.Main to home(SpacePanel.Main), SpacePanel.Voice to home(SpacePanel.Voice)))

        /** Where each kind of panel first appears. */
        fun home(panel: SpacePanel): Placement = when (panel) {
            SpacePanel.Main -> Placement(0.0, 1.8)
            SpacePanel.Status -> Placement(-55.0, 1.4)
            SpacePanel.Voice -> Placement(55.0, 1.4)
            is SpacePanel.Conversation, is SpacePanel.Pulls -> Placement(-100.0, 1.6)
            is SpacePanel.Pull, is SpacePanel.Diff, is SpacePanel.Preview, is SpacePanel.Web -> Placement(100.0, 1.8)
        }

        private const val SLOT_GAP = 25.0

        /** An angle in (-180, 180]. */
        fun wrap(degrees: Double): Double {
            var d = degrees % 360.0
            if (d > 180.0) d -= 360.0
            if (d <= -180.0) d += 360.0
            return d
        }
    }
}
