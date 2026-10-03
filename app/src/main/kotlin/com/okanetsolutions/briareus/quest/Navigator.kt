package com.okanetsolutions.briareus.quest

import com.okanetsolutions.briareus.core.ScreenAction
import com.okanetsolutions.briareus.core.Session
import com.okanetsolutions.briareus.core.SpaceLayout
import com.okanetsolutions.briareus.core.SpaceMove
import com.okanetsolutions.briareus.core.SpacePanel
import com.okanetsolutions.briareus.core.Surroundings
import com.okanetsolutions.briareus.core.Voice
import com.okanetsolutions.briareus.core.args
import com.okanetsolutions.briareus.quest.ui.Pane
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * What the immersive space holds: the panels around the user and where each floats, what the main window shows, and
 * whether the room or the virtual surroundings are around them. Held outside the activity so the voice and the screens'
 * own buttons change it alike; the activity only draws it.
 */
class Navigator(private val store: Store) {

    val pane = MutableStateFlow<Pane?>(null)
    val space = MutableStateFlow(SpaceLayout.START)
    val surroundings = MutableStateFlow(Surroundings.VIRTUAL)
    /** Bumped to have the activity place the panels around where the user looks now. */
    val recenter = MutableStateFlow(0)

    init {
        // A forgotten connection takes what was on screen with it.
        store.scope.launch {
            store.connection.collect { if (it == null) { pane.value = null; space.value = SpaceLayout.START } }
        }
    }

    /** Shows [panel] in front of the user, or beside the others when not [focus]. */
    fun open(panel: SpacePanel, focus: Boolean = true) { space.value = space.value.show(panel, focus) }

    fun close(key: String) { space.value = space.value.close(key) }

    /** The conversation the user is looking at: the panel's in front, else the main window's, else the only one in a panel of its own. */
    fun onScreen(): Session? {
        val sessions = store.sessions.value
        (space.value.front as? SpacePanel.Conversation)?.let { front -> sessions[front.sessionId]?.let { return it } }
        (pane.value as? Pane.Open)?.let { open -> sessions[open.sessionId]?.let { return it } }
        return ownPanels().singleOrNull()?.let { sessions[it] }
    }

    private fun ownPanels() = space.value.panels.mapNotNull { (it.first as? SpacePanel.Conversation)?.sessionId }

    /** The pull request in front (or its diff), else the pull requests in the main window: their repository, and the number when it shows one. */
    fun pullOnScreen(): Pair<String, Int?>? {
        when (val front = space.value.front) {
            is SpacePanel.Pull -> return front.repo to front.number
            is SpacePanel.Diff -> return front.repo to front.number
            is SpacePanel.Pulls -> return front.repo to null
            else -> {}
        }
        return when (val p = pane.value) {
            is Pane.Pull -> p.repo to p.number
            is Pane.Pulls -> p.repo to null
            else -> null
        }
    }

    /** Carries out [action] and says what is on screen now. Throws when a window cannot be opened. */
    @Suppress("CyclomaticComplexMethod") // One branch per action, each saying what it left on screen.
    fun show(action: ScreenAction): String {
        val sessions = store.sessions.value
        fun title(id: String) = sessions[id]?.title ?: "the conversation"
        return when (action) {
            is ScreenAction.Conversation -> if (action.ownPanel) {
                open(SpacePanel.Conversation(action.sessionId))
                "\"${title(action.sessionId)}\" is open in a panel of its own, in front."
            } else {
                pane.value = Pane.Open(action.sessionId)
                bringMain()
                "\"${title(action.sessionId)}\" is in the main window."
            }
            is ScreenAction.NewConversation -> {
                pane.value = Pane.New(action.repo, action.prompt)
                bringMain()
                "The form to start a conversation" + (action.repo?.let { " on ${store.projectTitle(it)}" } ?: "") + " is in the main window" +
                    (if (action.prompt != null) ", with the prompt filled in." else ".")
            }
            is ScreenAction.PullRequest -> if (action.browser) {
                val own = sessions.values.firstOrNull { it.repo == action.repo && it.pullNumber == action.number }?.pullUrl
                openUrl(Voice.pullUrl(action.repo, action.number, own, action.view))
                "Pull request #${action.number}'s GitHub page is in a panel of its own, in front" + (action.view?.let { ", on its $it" } ?: "") + "."
            } else {
                open(SpacePanel.Pull(action.repo, action.number))
                "Pull request #${action.number} is in a panel of its own, in front" + (action.view?.let { "; its $it are on it" } ?: "") + "."
            }
            is ScreenAction.PullRequests -> {
                open(SpacePanel.Pulls(action.repo))
                "${store.projectTitle(action.repo)}'s open pull requests are in a panel of their own, in front."
            }
            is ScreenAction.Issue -> {
                openUrl(Voice.issueUrl(action.repo, action.number))
                "Issue #${action.number}'s GitHub page is in a panel of its own, in front."
            }
            is ScreenAction.Preview -> {
                open(SpacePanel.Preview(action.sessionId, sessions[action.sessionId]?.serveUrl ?: error("That conversation serves nothing right now.")))
                "What \"${title(action.sessionId)}\" serves is in the preview panel, in front."
            }
            is ScreenAction.Diff -> {
                open(SpacePanel.Diff(action.repo, action.number, action.file))
                "The diff of pull request #${action.number} is in front" + (action.file?.let { ", at $it" } ?: "") + "."
            }
            ScreenAction.StatusPanel -> {
                open(SpacePanel.Status)
                "The status panel is in front."
            }
            is ScreenAction.Arrange -> arrange(action.panel, action.move)
            is ScreenAction.Surround -> {
                surroundings.value = action.surroundings
                if (action.surroundings == Surroundings.PASSTHROUGH) "The user sees their room around the panels." else "The virtual surroundings are around the panels."
            }
            ScreenAction.Home -> {
                pane.value = null
                bringMain()
                "The main window shows the conversation list."
            }
        }
    }

    /** Carries out a spoken move of a panel; [key] "front" is the panel ahead, and null with a reset. */
    private fun arrange(key: String?, move: SpaceMove): String {
        if (move == SpaceMove.RESET) {
            space.value = space.value.reset()
            recenter.value++
            return "Every panel is back in its place, around where the user looks."
        }
        val target = resolve(key!!)
        val label = name(target)
        if (target == SpacePanel.Main.key && move == SpaceMove.CLOSE) error("The main window stays open; move it aside or away instead.")
        space.value = space.value.move(target, move) ?: error("There is no $target panel. Call read_screen to see the panels.")
        val at = space.value[target]?.second
        return if (at == null) "The panel with $label is closed." else "The panel with $label is ${at.where}, ${"%.1f".format(at.distance)} metres away" +
            (if (at.scale == 1.0) "." else ", at ${(at.scale * 100).toInt()}% size.")
    }

    /** A panel the voice named: "front", a panel's key, a conversation's id, or a kind for the newest of it. */
    private fun resolve(name: String): String {
        val layout = space.value
        return when {
            name == "front" -> layout.front?.key ?: error("No panel is straight ahead.")
            layout[name] != null -> name
            layout[SpacePanel.Conversation(name).key] != null -> SpacePanel.Conversation(name).key
            name in SpacePanel.KINDS -> layout.latest(name)?.key ?: error("No $name panel is open. Call read_screen to see the panels.")
            else -> name
        }
    }

    private fun name(key: String) = space.value[key]?.first?.let(::shows) ?: key

    /** What a panel shows, as read_screen says it. */
    private fun shows(panel: SpacePanel): String = when (panel) {
        SpacePanel.Main -> "the main window"
        SpacePanel.Status -> "the status panel"
        SpacePanel.Voice -> "this voice conversation"
        is SpacePanel.Conversation -> "the conversation \"${store.sessions.value[panel.sessionId]?.title ?: panel.sessionId}\""
        is SpacePanel.Pulls -> "${store.projectTitle(panel.repo)}'s open pull requests"
        is SpacePanel.Pull -> "${store.projectTitle(panel.repo)} pull request #${panel.number}"
        is SpacePanel.Web -> "the page ${panel.url}"
        is SpacePanel.Diff -> "the diff of ${store.projectTitle(panel.repo)} pull request #${panel.number}" + (panel.file?.let { " at $it" } ?: "")
        is SpacePanel.Preview -> "what \"${store.sessions.value[panel.sessionId]?.title ?: "a conversation"}\" serves"
    }

    /** What the space shows, for the model to resolve "this" and "that" and to name the panels it moves. */
    fun screen(): JsonObject {
        val sessions = store.sessions.value
        val main = pane.value
        val shown = (main as? Pane.Open)?.let { sessions[it.sessionId] }
        val panels = ownPanels().filter { it != shown?.id }.mapNotNull { sessions[it] }
        return args(
            "main_window" to when {
                shown != null -> mapOf("showing" to "a conversation", "conversation" to Voice.conversation(shown, store.projectTitle(shown.repo)))
                main is Pane.New -> mapOf("showing" to "the form to start a conversation", "project" to main.repo?.let(store::projectTitle))
                main is Pane.Pull -> mapOf(
                    "showing" to "a pull request", "project" to store.projectTitle(main.repo), "number" to main.number,
                    "conversations" to sessions.values.filter { it.repo == main.repo && it.pullNumber == main.number }.map { mapOf("session_id" to it.id, "title" to it.title) },
                )
                main is Pane.Pulls -> mapOf("showing" to "a project's open pull requests", "project" to store.projectTitle(main.repo))
                else -> mapOf("showing" to "the conversation list")
            },
            "own_panels" to panels.map { Voice.conversation(it, store.projectTitle(it.repo)) },
            "panels" to space.value.describe(::shows),
            "in_front" to space.value.front?.key,
            "surroundings" to surroundings.value.wire,
        )
    }

    private fun bringMain() = open(SpacePanel.Main)

    /** A web page, in a panel of its own: the browser would end the immersive space. */
    fun openUrl(url: String) = open(SpacePanel.Web(url))
}
