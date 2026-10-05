package com.okanetsolutions.briareus.quest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.okanetsolutions.briareus.core.ApiError
import com.okanetsolutions.briareus.core.Board
import com.okanetsolutions.briareus.core.Errand
import com.okanetsolutions.briareus.core.PullFinding
import com.okanetsolutions.briareus.core.PullLabel
import com.okanetsolutions.briareus.core.PullOverview
import com.okanetsolutions.briareus.core.PullRow
import com.okanetsolutions.briareus.core.Session
import com.okanetsolutions.briareus.core.SessionList
import com.okanetsolutions.briareus.core.args
import com.okanetsolutions.briareus.core.nonEmpty
import com.okanetsolutions.briareus.core.obj
import com.okanetsolutions.briareus.core.str
import com.okanetsolutions.briareus.quest.Store
import com.okanetsolutions.briareus.quest.Windows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** How often a board on screen is read again; the server caches it for about as long. */
private const val BOARD_POLL_MS = 60_000L

/**
 * A project's open pull requests, as the dashboard's Pull requests tab shows them: each with its checks, author, branch,
 * labels, stack and linked issues, and a row of what can be done on it (serve it, the project's errands, merge) and the
 * conversations already run on it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PullsScreen(store: Store, repo: String, onPane: (Pane?) -> Unit, back: (() -> Unit)?) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sessions by store.sessions.collectAsState()
    var board by remember(repo) { mutableStateOf<Board?>(null) }
    var errands by remember { mutableStateOf<List<Errand>>(emptyList()) }
    var error by remember(repo) { mutableStateOf<String?>(null) }
    var loading by remember(repo) { mutableStateOf(false) }
    var author by rememberSaveable(repo) { mutableStateOf<String?>(null) }
    var label by rememberSaveable(repo) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(Instant.now()) }

    suspend fun load(fresh: Boolean) {
        val c = store.client ?: return
        loading = true
        try {
            board = Board.parse(c.call("pulls", args("repo" to repo, "fresh" to if (fresh) 1 else null)))
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? ApiError)?.unauthorized == true) store.failed(e)
            error = (e as? ApiError)?.description ?: e.message ?: "The pull requests could not be read."
        } finally {
            loading = false
            now = Instant.now()
        }
    }

    LaunchedEffect(Unit) {
        if (store.can("actions")) runCatching { errands = Errand.list(store.client!!.call("actions")) }.onFailure { store.failed(it, silent = true) }
    }
    LaunchedEffect(repo, reload) {
        load(fresh = reload > 0)
        while (true) { delay(BOARD_POLL_MS); load(fresh = false) }
    }

    Column(Modifier.fillMaxSize().background(p.canvas)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { Windows.openPanel(context, Pane.Pulls(repo)) }) { Text("⧉ New panel") }
            if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = p.muted) }
            Column(Modifier.weight(1f).padding(start = if (back == null) 8.dp else 0.dp)) {
                Text(store.projectTitle(repo), style = MaterialTheme.typography.titleLarge, color = p.ink)
                val b = board
                Text(
                    listOfNotNull(
                        repo,
                        b?.let { "${it.pulls.size} open pull request" + if (it.pulls.size == 1) "" else "s" },
                        b?.syncedAt?.let { "synced " + SessionList.age(it, now).let { a -> if (a == "now") a else "$a ago" } },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = p.muted,
                )
            }
            board?.let { b ->
                Filter("Author", author, b.authors) { author = it }
                Filter("Label", label, b.labels) { label = it }
            }
            IconButton(onClick = { reload++ }, enabled = !loading) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Refresh, "Read the pull requests again", tint = p.muted)
            }
        }
        HorizontalDivider(color = p.line)
        val b = board
        val rows = remember(b, author, label) { b?.filter(author, label).orEmpty() }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { item { Notice(it, p.danger) } }
            if (b != null) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Showing ${rows.size} of ${b.pulls.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = p.muted)
                    if (author != null || label != null) TextButton(onClick = { author = null; label = null }) { Text("Clear filters", color = p.accent) }
                }
            }
            items(rows, key = { it.number }) { pr ->
                PullCard(store, repo, pr, errands, SessionList.forPull(sessions.values, repo, pr.number), now, onPane)
            }
            if (b == null && error == null) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            if (b != null && b.pulls.isEmpty()) item { Text("No open pull requests.", Modifier.padding(24.dp), color = p.muted) }
        }
    }
}

@Composable
fun Filter(label: String, current: String?, options: List<String>, onPick: (String?) -> Unit) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    if (options.isEmpty()) return
    Box(Modifier.padding(start = 8.dp)) {
        OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text((current ?: "All ${label.lowercase()}s") + " ▾", color = if (current != null) p.accent else p.ink, maxLines = 1)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All ${label.lowercase()}s") }, onClick = { open = false; onPick(null) })
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PullCard(store: Store, repo: String, pr: PullRow, errands: List<Errand>, runs: List<Session>, now: Instant, onPane: (Pane?) -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().background(p.raise, RoundedCornerShape(12.dp)).border(1.dp, p.line, RoundedCornerShape(12.dp))
            .clickable(enabled = store.can("pull")) { onPane(Pane.Pull(repo, pr.number)) }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(pr.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val age = SessionList.age(pr.updatedAt, now).let { if (it.isEmpty() || it == "now") it else "$it ago" }
            Text(age, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelSmall, color = p.muted)
        }
        PullMeta(pr)
        if (pr.labels.isNotEmpty() || pr.draft || pr.conflicting) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pr.draft) LabelChip(PullLabel("draft", null))
                if (pr.conflicting) LabelChip(PullLabel("conflicts", null), p.danger)
                pr.labels.forEach { LabelChip(it) }
            }
        }
        pr.issues.forEach { issue ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("↳ #${issue.number}", style = MaterialTheme.typography.labelMedium, color = p.muted)
                Text(issue.title, Modifier.weight(1f, fill = false).padding(start = 6.dp), style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (issue.state.isNotEmpty()) Text(issue.state, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = if (issue.state == "open") p.ok else p.muted)
            }
        }
        PullActions(store, repo, pr.number, pr.recommended, errands, runs, onPane)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PullMeta(pr: PullRow) {
    val p = LocalPalette.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text("#${pr.number}", style = MaterialTheme.typography.labelMedium, color = p.muted)
        pr.checksLabel?.let { label ->
            val mark = when (pr.checks) { "success" -> "✓"; "failure", "error" -> "✕"; else -> "●" }
            Text("$mark $label", style = MaterialTheme.typography.labelMedium, color = checksColor(pr.checks))
        }
        Text(pr.assignees.joinToString(", ").ifEmpty { "unassigned" }, style = MaterialTheme.typography.labelMedium, color = p.muted)
        pr.author?.let { Text("@$it", style = MaterialTheme.typography.labelMedium, color = p.ink) }
        pr.branch?.let { Text(it, style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono), color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        pr.stack?.let { Text("stack $it", style = MaterialTheme.typography.labelMedium, color = p.warn) }
    }
}

@Composable
private fun checksColor(rollup: String?): Color {
    val p = LocalPalette.current
    return when (rollup) {
        "success" -> p.ok
        "failure", "error" -> p.danger
        else -> p.warn
    }
}

@Composable
fun LabelChip(label: PullLabel, tint: Color? = null) {
    val p = LocalPalette.current
    // GitHub's label colours are picked for a light page; lifted towards white they read on the dark panel.
    val color = tint ?: label.argb?.let { lerpToInk(Color(it), p.ink) } ?: p.muted
    Text(
        label.name, Modifier.border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1,
    )
}

private fun lerpToInk(c: Color, ink: Color): Color = Color(
    red = c.red + (ink.red - c.red) * 0.35f, green = c.green + (ink.green - c.green) * 0.35f, blue = c.blue + (ink.blue - c.blue) * 0.35f,
)

/** Starts Run, Code review, project errands or merging, and links to earlier runs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PullActions(store: Store, repo: String, number: Int, recommended: String?, errands: List<Errand>, runs: List<Session>, onPane: (Pane?) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }

    fun start(action: PendingAction, input: String?) {
        busy = true
        scope.launch {
            try {
                when (action) {
                    PendingAction.Serve -> {
                        store.runs.start(repo, number)
                        Windows.openPullRun(context, repo, number)
                    }
                    is PendingAction.Run -> store.mutate("action", args("repo" to repo, "action" to action.errand.id, "prNumber" to number, "input" to input))
                        ?.let { r -> Session.parse(r["session"])?.let { onPane(Pane.Open(it.id)) } }
                    PendingAction.Review -> store.review.start(repo, number)?.let { onPane(Pane.Open(it.id)) }
                    PendingAction.Merge -> merge(store, repo, number)
                }
            } finally {
                busy = false
            }
        }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        if (store.can("serve_pull")) ActionButton("▶ Run", enabled = !busy) { start(PendingAction.Serve, null) }
        if (store.can("review") && store.can("pull")) ActionButton("⌕ Code review", enabled = !busy, highlighted = recommended == "review") {
            start(PendingAction.Review, null)
        }
        if (store.can("action")) errands.filter { it.id != "review" && it.id != "run" }.forEach { e ->
            ActionButton(e.label, enabled = !busy, highlighted = e.id == recommended) {
                if (e.inputLabel != null) pending = PendingAction.Run(e) else start(PendingAction.Run(e), null)
            }
        }
        if (store.can("merge_pull") && store.can("pull")) ActionButton("↳ Merge", enabled = !busy) { start(PendingAction.Merge, null) }
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        if (runs.isNotEmpty()) TextButton(onClick = { onPane(Pane.Open(runs.first().id)) }) {
            Text("${runs.size} run${if (runs.size == 1) "" else "s"} ›", color = p.accent)
        }
    }

    pending?.let { action ->
        var input by remember(action) { mutableStateOf("") }
        val errand = (action as? PendingAction.Run)?.errand
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(errand?.label.orEmpty()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(errand?.inputLabel.orEmpty() + if (errand?.inputRequired == true) "" else " (optional)", color = p.ink)
                    VoiceField(store, input, { input = it }, Modifier.fillMaxWidth(), placeholder = "Record it", minHeight = 96.dp)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = errand?.inputRequired != true || input.isNotBlank(),
                    onClick = { pending = null; start(action, input.trim().ifEmpty { null }) },
                ) { Text("Start", color = p.accent) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

private sealed interface PendingAction {
    data object Serve : PendingAction
    data object Review : PendingAction
    data object Merge : PendingAction
    data class Run(val errand: Errand) : PendingAction
}

/** Reads the head and base the merge must be pinned to, then merges; says how it went. */
private suspend fun merge(store: Store, repo: String, number: Int) {
    val pr = try {
        PullOverview.parse(store.client?.call("pull", args("repo" to repo, "pr" to number)) ?: return)
    } catch (e: Exception) {
        store.failed(e); return
    }
    val headSha = pr?.headSha
    val baseRef = pr?.baseRef
    if (headSha == null || baseRef == null) { store.say("GitHub did not say where #$number stands; it was not merged."); return }
    val result = store.mutate("merge_pull", args("repo" to repo, "pr" to number, "headSha" to headSha, "baseRef" to baseRef)) ?: return
    store.say(
        result.nonEmpty("message") ?: when (result.str("status")) {
            "merged" -> "#$number was merged."
            "enqueued" -> "#$number joined the merge queue."
            else -> "GitHub is still merging #$number."
        },
    )
}

@Composable
private fun ActionButton(text: String, enabled: Boolean, highlighted: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 44.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        colors = if (highlighted) ButtonDefaults.outlinedButtonColors(containerColor = p.accent.copy(alpha = 0.15f), contentColor = p.accent)
        else ButtonDefaults.outlinedButtonColors(contentColor = p.ink),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (highlighted) p.accent else p.line),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

// MARK: - One pull request

/** One pull request in full: where it stands, its description, checks, reviews, linked issues, commits and findings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PullScreen(store: Store, repo: String, number: Int, onPane: (Pane?) -> Unit, initialView: String? = null) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val sessions by store.sessions.collectAsState()
    var tab by remember(repo, number, initialView) { mutableStateOf(initialView?.takeUnless { it == "checks" } ?: "overview") }
    var pr by remember(repo, number) { mutableStateOf<PullOverview?>(null) }
    var body by remember(repo, number) { mutableStateOf<String?>(null) }
    var findings by remember(repo, number) { mutableStateOf<List<PullFinding>?>(null) }
    var errands by remember { mutableStateOf<List<Errand>>(emptyList()) }
    var error by remember(repo, number) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (store.can("actions")) runCatching { errands = Errand.list(store.client!!.call("actions")) }.onFailure { store.failed(it, silent = true) }
    }
    LaunchedEffect(repo, number, reload) {
        val c = store.client ?: return@LaunchedEffect
        val a = args("repo" to repo, "pr" to number)
        try {
            pr = PullOverview.parse(c.call("pull", a))
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? ApiError)?.unauthorized == true) store.failed(e)
            error = (e as? ApiError)?.description ?: e.message ?: "The pull request could not be read."
        }
        if (store.can("pull_description")) runCatching { body = c.call("pull_description", a).obj("pr")?.str("body").orEmpty() }
        if (store.can("findings")) runCatching { findings = PullFinding.list(c.call("findings", a)) }
    }

    Column(Modifier.fillMaxSize().background(p.canvas)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onPane(Pane.Pulls(repo)) }) { Text("← Pull requests") }
            Box(Modifier.weight(1f))
            TextButton(onClick = { Windows.openPanel(context, Pane.Pull(repo, number, tab)) }) { Text("⧉ New panel") }
            IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, "Read it again", tint = p.muted) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("overview" to "Overview", "files" to "Files", "reviews" to "Reviews", "commits" to "Commits").forEach { (id, label) ->
                if (id != "files" || store.can("pull_files")) TextButton(onClick = { tab = id }) { Text(label, color = if (tab == id) p.accent else p.muted) }
            }
        }
        pr?.takeIf { it.state == "open" || it.state == "draft" }?.let { pull ->
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                PullActions(store, repo, pull.number, null, errands, SessionList.forPull(sessions.values, repo, pull.number), onPane)
            }
        }
        HorizontalDivider(color = p.line)
        if (tab == "files") {
            if (store.can("pull_files")) FilesScreen(store, repo, number)
            else Text("This server or token cannot read pull request files.", Modifier.padding(16.dp), color = p.muted)
            return@Column
        }
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 960.dp).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                error?.let { Notice(it, p.danger) }
                val o = pr
                if (o == null) {
                    if (error == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    return@Column
                }
                Text(o.title, style = MaterialTheme.typography.titleLarge, color = p.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    LabelChip(PullLabel(o.state, null), if (o.state == "open") p.ok else if (o.state == "merged") p.accent else p.muted)
                    Text("#${o.number}", style = MaterialTheme.typography.labelLarge, color = p.muted)
                    Text("${o.headRef ?: "?"} → ${o.baseRef ?: "?"}", style = MaterialTheme.typography.labelLarge.copy(fontFamily = Mono), color = p.muted)
                    Text("+${o.additions}", style = MaterialTheme.typography.labelLarge.copy(fontFamily = Mono), color = p.ok)
                    Text("−${o.deletions}", style = MaterialTheme.typography.labelLarge.copy(fontFamily = Mono), color = p.danger)
                    Text("${o.changedFiles} file${if (o.changedFiles == 1) "" else "s"}", style = MaterialTheme.typography.labelLarge, color = p.muted)
                    o.commitCount?.let { Text("$it commit${if (it == 1) "" else "s"}", style = MaterialTheme.typography.labelLarge, color = p.muted) }
                }

                if (tab == "overview") Section("Checks") {
                    Text(o.checksSummary ?: "No checks reported.", style = MaterialTheme.typography.bodyMedium, color = p.muted)
                }
                if (tab == "overview") body?.takeIf { it.isNotBlank() }?.let { Section("Description") { MarkdownText(it, style = MaterialTheme.typography.bodyMedium) } }

                if (tab == "overview" || tab == "reviews") Section("Reviews") {
                    if (o.reviews.isEmpty()) Text("No reviews yet.", color = p.muted)
                    o.reviews.forEach { r ->
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("@${r.user}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = p.ink)
                            Text(r.label, style = MaterialTheme.typography.labelMedium, color = if (r.state == "APPROVED") p.ok else if (r.state == "CHANGES_REQUESTED") p.danger else p.muted)
                        }
                    }
                }
                if (tab == "overview" || tab == "reviews") findings?.let { list ->
                    Section("Findings") {
                        if (list.isEmpty()) Text("No findings reported.", color = p.muted)
                        list.forEach { f ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(f.severity.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (f.severity == "critical" || f.severity == "high") p.danger else p.warn)
                                    Text(f.title, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, color = p.ink)
                                }
                                Text(
                                    listOfNotNull(f.where, if (f.fixed) "fixed" else f.decision).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = p.muted,
                                )
                            }
                        }
                    }
                }
                if (tab == "overview" && o.issues.isNotEmpty()) Section("Closes") {
                    o.issues.forEach { i ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = store.can("issue")) { onPane(Pane.Issue(repo, i.number)) }.padding(vertical = 4.dp)) {
                            Text("#${i.number}", style = MaterialTheme.typography.labelLarge, color = p.muted)
                            Text(i.title, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, color = p.ink)
                            Text(i.state, style = MaterialTheme.typography.labelSmall, color = if (i.state == "open") p.ok else p.muted)
                        }
                    }
                }
                if ((tab == "overview" || tab == "commits") && o.commits.isNotEmpty()) Section("Commits") {
                    o.commits.forEach { c ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(c.sha.take(7), style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono), color = p.muted)
                            Text(c.message, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.raise, RoundedCornerShape(12.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleSmall, color = p.ink)
        content()
    }
}

@Composable
private fun Notice(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(12.dp), style = MaterialTheme.typography.bodySmall, color = color)
}
