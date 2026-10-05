package com.okanetsolutions.briareus.quest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.okanetsolutions.briareus.core.ApiError
import com.okanetsolutions.briareus.core.Board
import com.okanetsolutions.briareus.core.Issue
import com.okanetsolutions.briareus.core.Voice
import com.okanetsolutions.briareus.quest.Store
import com.okanetsolutions.briareus.quest.Windows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Read errors remain visible while safe reads retry with backoff; a refresh starts from the first page. */
@Composable
internal fun <T> ReadPanel(store: Store, identity: Any, read: suspend () -> T, content: @Composable (T) -> Unit) {
    var value by remember(identity) { mutableStateOf<T?>(null) }
    var error by remember(identity) { mutableStateOf<String?>(null) }
    var reload by remember(identity) { mutableIntStateOf(0) }
    LaunchedEffect(identity, reload) {
        var wait = 1_000L
        while (true) {
            try {
                value = read(); error = null
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "This view could not be read."
                if ((e as? ApiError)?.unauthorized == true) { store.failed(e); break }
                delay(maxOf(wait, ((e as? ApiError)?.retryAfter?.times(1000))?.toLong() ?: 0L)); wait = (wait * 2).coerceAtMost(30_000)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { value = null; reload++ }) { Text("Refresh") }
            error?.let { Text(it, Modifier.weight(1f).padding(12.dp), color = LocalPalette.current.danger) }
        }
        val loaded = value
        if (loaded == null && error == null) CircularProgressIndicator(Modifier.padding(24.dp))
        loaded?.let { content(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IssuesScreen(store: Store, repo: String, onPane: (Pane?) -> Unit, back: (() -> Unit)?) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var label by remember(repo) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(p.canvas).padding(12.dp)) {
        Row {
            if (back != null) TextButton(onClick = back) { Text("← Conversations") }
            TextButton(onClick = { Windows.openPanel(context, Pane.Issues(repo)) }) { Text("⧉ New panel") }
        }
        Text("${store.projectTitle(repo)} · Issues", style = MaterialTheme.typography.titleLarge, color = p.ink)
        ReadPanel(store, repo, { store.review.board(repo) }) { board: Board ->
            Filter("Label", label, board.issues.flatMap { it.labels }.map { it.name }.distinct().sorted()) { label = it }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val issues = board.issues.filter { issue -> label == null || issue.labels.any { it.name == label } }
                board.issuesError?.let { item { Text("Issues could not be read: $it", color = p.danger) } }
                if (issues.isEmpty() && board.issuesError == null) item { Text("No open issues match.", color = p.muted) }
                items(issues, key = { it.number }) { issue ->
                    Column(Modifier.fillMaxWidth().background(p.raise).clickable(enabled = store.can("issue")) {
                        onPane(Pane.Issue(repo, issue.number))
                    }.padding(16.dp)) {
                        Text("#${issue.number} ${issue.title}", style = MaterialTheme.typography.titleMedium, color = p.ink)
                        issue.parent?.let { Text("Part of $it", color = p.muted) }
                        issue.author?.let { Text("@$it", color = p.muted) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { issue.labels.forEach { LabelChip(it) } }
                        issue.pulls.forEach { pull ->
                            if (store.can("pull")) TextButton(onClick = { onPane(Pane.Pull(repo, pull.number)) }) { Text("PR #${pull.number} ${pull.title}") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IssueScreen(store: Store, repo: String, number: Int, onPane: (Pane?) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(p.canvas).padding(12.dp)) {
        Row {
            TextButton(onClick = { onPane(Pane.Issues(repo)) }) { Text("← Issues") }
            TextButton(onClick = { Windows.openPanel(context, Pane.Issue(repo, number)) }) { Text("⧉ New panel") }
        }
        ReadPanel(store, repo to number, { store.review.issue(repo, number) }) { issue: Issue ->
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { Text("#${issue.number} ${issue.title}", style = MaterialTheme.typography.titleLarge, color = p.ink) }
                item { Text(issue.state, color = p.muted) }
                item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { issue.labels.forEach { LabelChip(it) } } }
                issue.parent?.let { item { Text("Part of $it", color = p.muted) } }
                item { MarkdownText(issue.body.ifBlank { "No description." }) }
                items(issue.pulls, key = { it.number }) { pull ->
                    if (store.can("pull")) TextButton(onClick = { onPane(Pane.Pull(repo, pull.number)) }) { Text("PR #${pull.number} ${pull.title}") }
                }
                if (store.can("start_session")) item {
                    TextButton(onClick = { onPane(Pane.New(repo, Voice.issuePrompt(issue.raw, repo))) }) { Text("Work on issue") }
                }
            }
        }
    }
}
