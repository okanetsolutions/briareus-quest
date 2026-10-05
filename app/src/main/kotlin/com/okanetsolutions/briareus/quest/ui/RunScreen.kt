package com.okanetsolutions.briareus.quest.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.okanetsolutions.briareus.quest.Store
import com.okanetsolutions.briareus.quest.Windows

/** Setup remains visible until the session publishes its running app; no second Run write is needed. */
@Composable
fun RunScreen(store: Store, sessionId: String) {
    val sessions by store.sessions.collectAsState()
    val session = sessions[sessionId]
    val conversation = remember(store, sessionId) { store.conversation(sessionId) }
    val events by conversation.events.collectAsState()
    DisposableEffect(conversation) { val release = conversation.watch(); onDispose { release() } }
    val url = session?.serveUrl
    if (url != null) PreviewScreen(store, url)
    else Column(Modifier.fillMaxSize()) {
        val p = LocalPalette.current
        val context = LocalContext.current
        Text("▶ Run · ${session?.status ?: "Connecting"}", Modifier.padding(16.dp), color = p.ink)
        Text(session?.error ?: "Waiting for the preview address. Setup output appears below.", Modifier.padding(16.dp), color = if (session?.error != null) p.danger else p.muted)
        TextButton(onClick = { Windows.openConversation(context, sessionId) }) { Text("Open conversation") }
        LazyColumn(Modifier.weight(1f).padding(16.dp)) {
            items(events.filter { !it.detail.isNullOrBlank() }, key = { it.seq }) { event ->
                Text(event.detail.orEmpty(), color = if (event.isError || event.kind == "stderr") p.danger else p.ink, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono))
            }
        }
    }
}

/** Opens immediately when Run is requested, including while the server has not answered its long setup request. */
@Composable
fun PullRunScreen(store: Store, repo: String, number: Int) {
    val requests by store.runs.states.collectAsState()
    val sessions by store.sessions.collectAsState()
    val request = requests[repo to number]
    val session = sessions.values.filter { it.repo == repo && it.pullNumber == number && it.title.startsWith("Run: #") }.maxByOrNull { it.createdAt ?: java.time.Instant.MIN }
    val id = request?.sessionId ?: session?.id
    val url = request?.url ?: session?.serveUrl
    Column(Modifier.fillMaxSize()) {
        request?.error?.let { Text(it, Modifier.padding(16.dp), color = LocalPalette.current.danger) }
        Box(Modifier.weight(1f)) {
            when {
                url != null -> PreviewScreen(store, url)
                id != null -> RunScreen(store, id)
                else -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("▶ Run · #$number", color = LocalPalette.current.ink)
                    Text(
                        if (request?.busy == true) "Preparing the workspace…" else "No running preview. Start Run from the pull request.",
                        color = LocalPalette.current.muted,
                    )
                }
            }
        }
    }
}
