package com.okanetsolutions.briareus.quest

import android.content.Context
import android.content.Intent
import com.okanetsolutions.briareus.core.ScreenAction
import com.okanetsolutions.briareus.core.Session
import com.okanetsolutions.briareus.core.Voice
import com.okanetsolutions.briareus.core.args
import com.okanetsolutions.briareus.quest.ui.Pane
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * What the main window shows, held outside it so the voice can change it from its own panel. Also opens native panels beside it, as the screens' own buttons do.
 */
class Navigator(context: Context, private val store: Store) {
    private val context = context.applicationContext

    val pane = MutableStateFlow<Pane?>(null)
    val panels = linkedMapOf<String, Pane>()
    init {
        // A forgotten connection takes what was on screen with it.
        store.scope.launch { store.connection.collect { if (it == null) { pane.value = null; panels.clear() } } }
    }

    /** The conversation the user is looking at: the main window's, else the only one open in a panel of its own. */
    fun onScreen(): Session? {
        val sessions = store.sessions.value
        (pane.value as? Pane.Open)?.let { open -> sessions[open.sessionId]?.let { return it } }
        return store.visibleSessions.value.singleOrNull()?.let { sessions[it] }
    }

    /** The pull requests in the main window: their repository, and the number when it shows one. */
    fun pullOnScreen(): Pair<String, Int?>? = when (val p = panels.values.filter { it is Pane.Pull || it is Pane.Pulls }.singleOrNull() ?: pane.value) {
        is Pane.Pull -> p.repo to p.number
        is Pane.Pulls -> p.repo to null
        else -> null
    }

    /** Carries out [action] and says what is on screen now. Throws when a window cannot be opened. */
    @Suppress("CyclomaticComplexMethod") // One branch per action, each saying what it left on screen.
    fun show(action: ScreenAction): String {
        val sessions = store.sessions.value
        fun title(id: String) = sessions[id]?.title ?: "the conversation"
        return when (action) {
            is ScreenAction.Conversation -> if (action.ownPanel) {
                Windows.openConversation(context, action.sessionId)
                "\"${title(action.sessionId)}\" is open in a panel of its own."
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
            is ScreenAction.PullRequest -> {
                check(store.can("pull")) { "This server or token cannot read pull requests." }
                if (action.view == "files") check(store.can("pull_files")) { "This server or token cannot read pull request files." }
                display(Pane.Pull(action.repo, action.number, action.view), action.ownPanel)
                "Pull request #${action.number}${action.view?.let { ", $it" }.orEmpty()} is open" + location(action.ownPanel)
            }
            is ScreenAction.PullRequests -> {
                check(store.can("pulls")) { "This server or token cannot read pull requests." }
                display(Pane.Pulls(action.repo), action.ownPanel)
                "${store.projectTitle(action.repo)}'s open pull requests are open" + location(action.ownPanel)
            }
            is ScreenAction.Issue -> {
                check(store.can("issue")) { "This server or token cannot read issues." }
                display(Pane.Issue(action.repo, action.number), action.ownPanel)
                "Issue #${action.number} is open" + location(action.ownPanel)
            }
            is ScreenAction.Issues -> {
                check(store.can("pulls")) { "This server or token cannot read issues." }
                display(Pane.Issues(action.repo), action.ownPanel)
                "${store.projectTitle(action.repo)}'s issues are open" + location(action.ownPanel)
            }
            is ScreenAction.Preview -> {
                check(sessions[action.sessionId]?.serveUrl != null) { "That conversation serves nothing right now." }
                Windows.openRun(context, action.sessionId)
                "What \"${title(action.sessionId)}\" serves is open in the preview panel."
            }
            is ScreenAction.Home -> {
                pane.value = Pane.Pulls(action.repo)
                bringMain()
                "The main window shows ${store.projectTitle(action.repo)}'s pull requests."
            }
        }
    }

    /** What the windows show, for the model to resolve "this" and "that". */
    fun screen(repo: String): JsonObject {
        val sessions = store.sessions.value.filterValues { it.repo == repo }
        val main = pane.value?.takeIf { projectOf(it) == repo }
        val shown = (main as? Pane.Open)?.let { sessions[it.sessionId] }
        val panels = store.visibleSessions.value.filter { it != shown?.id }.mapNotNull { sessions[it] }
        return args(
            "project" to repo,
            "main_window" to when {
                shown != null -> mapOf("showing" to "a conversation", "conversation" to Voice.conversation(shown, store.projectTitle(shown.repo)))
                main is Pane.New -> mapOf("showing" to "the form to start a conversation", "project" to main.repo?.let(store::projectTitle))
                main is Pane.Pull -> mapOf(
                    "showing" to "a pull request", "project" to store.projectTitle(main.repo), "number" to main.number,
                    "conversations" to sessions.values.filter { it.repo == main.repo && it.pullNumber == main.number }.map { mapOf("session_id" to it.id, "title" to it.title) },
                )
                main is Pane.Issue -> mapOf("showing" to "an issue", "project" to store.projectTitle(main.repo), "number" to main.number)
                main is Pane.Issues -> mapOf("showing" to "open issues", "project" to store.projectTitle(main.repo))
                main is Pane.Pulls -> mapOf("showing" to "a project's open pull requests", "project" to store.projectTitle(main.repo))
                else -> mapOf("showing" to "no selected content from this project")
            },
            "document_panels" to this.panels.values.filter { projectOf(it) == repo }.map { shownPane ->
                when (shownPane) {
                    is Pane.Pull -> mapOf("showing" to "a pull request", "project" to shownPane.repo, "number" to shownPane.number, "view" to shownPane.view)
                    is Pane.Pulls -> mapOf("showing" to "open pull requests", "project" to shownPane.repo)
                    is Pane.Issue -> mapOf("showing" to "an issue", "project" to shownPane.repo, "number" to shownPane.number)
                    is Pane.Issues -> mapOf("showing" to "open issues", "project" to shownPane.repo)
                    else -> emptyMap()
                }
            },
            "own_panels" to panels.map { Voice.conversation(it, store.projectTitle(it.repo)) },
        )
    }

    private fun display(target: Pane, ownPanel: Boolean) {
        if (ownPanel) Windows.openPanel(context, target)
        else { pane.value = target; bringMain() }
    }

    private fun location(ownPanel: Boolean) = if (ownPanel) " in its own native panel." else " in the main window."

    private fun bringMain() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))
    }

    fun projectOnScreen(): String? = pane.value?.let(::projectOf) ?: panels.values.lastOrNull()?.let(::projectOf) ?: onScreen()?.repo

    private fun projectOf(target: Pane): String? = when (target) {
        is Pane.Pull -> target.repo
        is Pane.Pulls -> target.repo
        is Pane.Issue -> target.repo
        is Pane.Issues -> target.repo
        is Pane.New -> target.repo
        is Pane.Open -> store.sessions.value[target.sessionId]?.repo
    }
}
