package com.okanetsolutions.briareus.quest

import com.okanetsolutions.briareus.core.Session
import com.okanetsolutions.briareus.core.args
import com.okanetsolutions.briareus.core.nonEmpty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A requested Run belongs to the connection, so switching or closing a panel never repeats its write. */
class RunRequests(private val store: Store) {
    data class State(val busy: Boolean = true, val sessionId: String? = null, val url: String? = null, val error: String? = null)
    val states = MutableStateFlow<Map<Pair<String, Int>, State>>(emptyMap())

    fun start(repo: String, number: Int) {
        val key = repo to number
        if (states.value[key]?.busy == true) return
        val client = store.client ?: return
        if (!store.can("serve_pull")) return
        store.sessions.value.values.firstOrNull { it.repo == repo && it.pullNumber == number && !it.isClosed && it.serveUrl != null }?.let { session ->
            states.update { it + (key to State(false, session.id, session.serveUrl)) }
            return
        }
        states.update { it + (key to State()) }
        store.scope.launch {
            val state = try {
                val response = client.call("serve_pull", args("repo" to repo, "pr" to number))
                val session = Session.parse(response["session"])
                if (store.client === client) session?.let(store::upsert)
                val url = response.nonEmpty("url") ?: session?.serveUrl
                State(false, session?.id, url, if (session == null && url == null) "The server did not return a Run session or preview address." else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (store.client === client) store.failed(e)
                State(busy = false, error = e.message ?: "The preview could not be started.")
            }
            if (store.client === client) states.update { it + (key to state) }
        }
    }

    fun clear() { states.value = emptyMap() }
}
