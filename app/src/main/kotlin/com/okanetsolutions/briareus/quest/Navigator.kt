package com.okanetsolutions.briareus.quest

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.okanetsolutions.briareus.core.ScreenAction
import com.okanetsolutions.briareus.core.Session
import com.okanetsolutions.briareus.core.Voice
import com.okanetsolutions.briareus.core.args
import com.okanetsolutions.briareus.quest.ui.Pane
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * What the main window shows, held outside it so the voice can change it from its own panel. Also opens the other windows and the browser beside them, as the screens' own buttons do.
 */
class Navigator(context: Context, private val store: Store) {
    private val context = context.applicationContext

    val pane = MutableStateFlow<Pane?>(null)
    init {
        // A forgotten connection takes what was on screen with it.
        store.scope.launch { store.connection.collect { if (it == null) pane.value = null } }
    }

    /** The conversation the user is looking at: the main window's, else the only one open in a panel of its own. */
    fun onScreen(): Session? {
        val sessions = store.sessions.value
        (pane.value as? Pane.Open)?.let { open -> sessions[open.sessionId]?.let { return it } }
        return store.visibleSessions.value.singleOrNull()?.let { sessions[it] }
    }

    /** The pull requests in the main window: their repository, and the number when it shows one. */
    fun pullOnScreen(): Pair<String, Int?>? = when (val p = pane.value) {
        is Pane.Pull -> p.repo to p.number
        is Pane.Pulls -> p.repo to null
        else -> null
    }

    /** Carries out [action] and says what is on screen now. Throws when a window cannot be opened. */
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
            is ScreenAction.PullRequest -> if (action.browser) {
                val own = sessions.values.firstOrNull { it.repo == action.repo && it.pullNumber == action.number }?.pullUrl
                browse(Voice.pullUrl(action.repo, action.number, own, action.view))
                "Pull request #${action.number} is open in the browser" + (action.view?.let { ", on its $it" } ?: "") + "."
            } else {
                pane.value = Pane.Pull(action.repo, action.number)
                bringMain()
                "Pull request #${action.number} is in the main window" + (action.view?.let { "; its $it are on it" } ?: "") + "."
            }
            is ScreenAction.PullRequests -> {
                pane.value = Pane.Pulls(action.repo)
                bringMain()
                "${store.projectTitle(action.repo)}'s open pull requests are in the main window."
            }
            is ScreenAction.Issue -> {
                browse(Voice.issueUrl(action.repo, action.number))
                "Issue #${action.number} is open in the browser."
            }
            is ScreenAction.Preview -> {
                browse(sessions[action.sessionId]?.serveUrl ?: error("That conversation serves nothing right now."))
                "What \"${title(action.sessionId)}\" serves is open in the browser."
            }
            ScreenAction.StatusPanel -> {
                Windows.openStatus(context)
                "The status panel is open."
            }
            ScreenAction.Home -> {
                pane.value = null
                bringMain()
                "The main window shows the conversation list."
            }
        }
    }

    /** What the windows show, for the model to resolve "this" and "that". */
    fun screen(): JsonObject {
        val sessions = store.sessions.value
        val main = pane.value
        val shown = (main as? Pane.Open)?.let { sessions[it.sessionId] }
        val panels = store.visibleSessions.value.filter { it != shown?.id }.mapNotNull { sessions[it] }
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
        )
    }

    private fun bringMain() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun browse(url: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT),
        )
    }
}
