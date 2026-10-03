package com.okanetsolutions.briareus.quest.ui

import android.content.Intent
import androidx.core.net.toUri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.okanetsolutions.briareus.core.ApiClient
import com.okanetsolutions.briareus.core.Session
import com.okanetsolutions.briareus.core.SessionList
import com.okanetsolutions.briareus.core.TranscriptEvent
import com.okanetsolutions.briareus.core.Triage
import com.okanetsolutions.briareus.core.args
import com.okanetsolutions.briareus.quest.Notifier
import com.okanetsolutions.briareus.quest.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** An item of the transcript as drawn: one visible line, or a run of tool calls collapsed into one row. */
private sealed interface Row {
    val key: Long
    data class Line(val event: TranscriptEvent) : Row { override val key get() = event.seq }
    data class Tools(val events: List<TranscriptEvent>) : Row { override val key get() = events.first().seq }
}

private fun rows(events: List<TranscriptEvent>): List<Row> {
    val out = ArrayList<Row>()
    val tools = ArrayList<TranscriptEvent>()
    fun flush() { if (tools.isNotEmpty()) out += Row.Tools(tools.toList()); tools.clear() }
    for (e in events) {
        if (!e.visible) continue
        if (e.kind == "tool" || e.kind == "tool_error") { tools += e; continue }
        flush()
        out += Row.Line(e)
    }
    flush()
    return out
}

/**
 * One conversation: its header and actions, the transcript as it streams in, the question the agent waits on with its
 * options as buttons, held findings, the queue, and the composer with voice notes and attachments.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConversationScreen(store: Store, sessionId: String, onBack: (() -> Unit)?, onPopOut: (() -> Unit)?, onDeleted: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conversation = remember(sessionId) { store.conversation(sessionId) }
    val sessions by store.sessions.collectAsState()
    val session = sessions[sessionId]
    val events by conversation.events.collectAsState()
    val loading by conversation.loading.collectAsState()
    // What the token may do, and whether the server transcribes, is read from the connection: recompose when it changes.
    store.connection.collectAsState()

    DisposableEffect(sessionId) {
        val release = conversation.watch()
        store.visibleSessions.value += sessionId
        Notifier.cancel(context, sessionId)
        onDispose {
            release()
            store.visibleSessions.value -= sessionId
        }
    }

    if (session == null) {
        Box(Modifier.fillMaxSize().background(p.canvas), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator() else Text("This conversation is gone.", color = p.muted)
        }
        return
    }

    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(p.canvas)) {
        Header(store, session, onBack, onPopOut, onDelete = { confirmDelete = true }, onReload = conversation::reload)
        HorizontalDivider(color = p.line)

        val rows = remember(events) { rows(events) }
        val list = rememberLazyListState()
        val expanded = remember { mutableStateMapOf<Long, Boolean>() }
        // Follow the bottom while it is in view, as a chat does.
        val atBottom = !list.canScrollForward
        LaunchedEffect(rows.size) {
            val count = list.layoutInfo.totalItemsCount
            if (rows.isNotEmpty() && count > 0 && (atBottom || list.firstVisibleItemIndex == 0)) list.scrollToItem(count - 1)
        }

        val triage = remember(session.raw) { Triage.of(session) }
        val pending = remember(events, session.raw) { if (session.awaitingAnswer) conversation.pendingQuestion() else null }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading && rows.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is Row.Line -> Line(row.event, canAnswer = store.can("message") && row.event.seq == pending?.seq) { answer ->
                        scope.launch { store.send(sessionId, answer) }
                    }
                    is Row.Tools -> ToolCluster(row.events, expanded[row.key] == true) { expanded[row.key] = expanded[row.key] != true }
                }
            }
            if (session.isActive) item(key = "working") { Working(session) }
            session.error?.takeIf { session.status == "failed" }?.let { error -> item(key = "error") { Notice(error, p.danger) } }
            if (triage != null) item(key = "triage") { TriageCard(store, session, triage) }
        }

        Queue(store, session)
        Composer(store, session)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this conversation?") },
            text = { Text("Its workspace is released and its transcript deleted on the server. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { if (store.mutate("delete", args("sessionId" to sessionId)) != null) onDeleted() }
                }) { Text("Delete", color = p.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(
    store: Store, session: Session, onBack: (() -> Unit)?, onPopOut: (() -> Unit)?,
    onDelete: () -> Unit, onReload: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    fun act(name: String, vararg extra: Pair<String, Any?>) = scope.launch { store.mutate(name, args("sessionId" to session.id, *extra)) }
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)) }

    Row(Modifier.fillMaxWidth().background(p.sidebar).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = p.ink) }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(session.title, style = MaterialTheme.typography.titleMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StateBadge(session.state)
                Tag(store.projectTitle(session.repo))
                Tag(listOf(session.provider, session.model, session.effort).filter { it.isNotBlank() }.joinToString(" · "))
                session.branch?.let { Tag(it) }
                session.pullNumber?.let { n -> Tag("PR #$n", Modifier.clickable(enabled = session.pullUrl != null) { session.pullUrl?.let(::open) }) }
                SessionList.cost(session.costUsd)?.let { Tag(it) }
            }
        }
        if (session.isActive && store.can("cancel")) {
            OutlinedButton(onClick = { act("cancel") }) {
                Icon(Icons.Outlined.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Stop")
            }
        }
        if (onPopOut != null) IconButton(onClick = onPopOut) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open in its own window", tint = p.muted) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = p.muted) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                fun item(text: String, enabled: Boolean = true, action: () -> Unit) =
                    @Composable { DropdownMenuItem(text = { Text(text) }, enabled = enabled, onClick = { menu = false; action() }) }
                session.serveUrl?.let { url -> item("Open ▶ Run in the browser") { open(url) }() }
                session.pullUrl?.let { url -> item("Open the pull request") { open(url) }() }
                if (store.can("review_loop")) item(if (session.reviewLoopOn) "Turn the review loop off" else "Turn the review loop on") {
                    act("review_loop", "on" to !session.reviewLoopOn)
                }()
                if (session.isClosed) item("Reopen", store.can("reopen")) { act("reopen") }()
                else item("Close", store.can("close") && !session.isActive) { act("close") }()
                item("Read the transcript again", action = onReload)()
                item("Delete…", store.can("delete"), onDelete)()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Line(e: TranscriptEvent, canAnswer: Boolean, onAnswer: (String) -> Unit) {
    val p = LocalPalette.current
    when (e.kind) {
        "user" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Column(Modifier.widthIn(max = 640.dp).background(p.field, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(e.text.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = p.ink)
                if (e.attachments.isNotEmpty()) Text("📎 " + e.attachments.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = p.muted)
                Text(time(e), Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall, color = p.muted)
            }
        }
        "text" -> Column {
            MarkdownText(e.text.orEmpty())
            Text(time(e), style = MaterialTheme.typography.labelSmall, color = p.muted)
        }
        "ask" -> Column(
            Modifier.fillMaxWidth().background(p.raise, RoundedCornerShape(14.dp)).border(1.dp, p.accent, RoundedCornerShape(14.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Your input is needed", style = MaterialTheme.typography.labelMedium, color = p.accent)
            MarkdownText(e.question ?: e.text.orEmpty())
            if (canAnswer && e.options.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    e.options.forEach { option -> OutlinedButton(onClick = { onAnswer(option) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(option) } }
                }
            }
        }
        "result" -> {
            val parts = listOfNotNull(
                if (e.isError) "Turn failed" else "Turn finished",
                e.durationMs?.let { "%.0fs".format(it / 1000.0) },
                SessionList.cost(e.costUsd),
            )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = if (e.isError) p.danger else p.muted)
        }
        else -> Text(e.text.orEmpty(), style = MaterialTheme.typography.bodySmall, color = p.muted)
    }
}

@Composable
private fun ToolCluster(events: List<TranscriptEvent>, expanded: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val errors = events.count { it.kind == "tool_error" }
    Column(Modifier.fillMaxWidth().background(p.sunken, RoundedCornerShape(10.dp)).clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 10.dp)) {
        val names = events.mapNotNull { it.name }.distinct().take(4).joinToString(", ")
        Text(
            (if (expanded) "▾ " else "▸ ") + "${events.size} tool call${if (events.size == 1) "" else "s"}" + (if (names.isNotEmpty()) " · $names" else "") +
                (if (errors > 0) " · $errors failed" else ""),
            style = MaterialTheme.typography.labelMedium, color = if (errors > 0) p.danger else p.muted,
        )
        if (expanded) events.forEach { e ->
            Row(Modifier.padding(top = 6.dp)) {
                Text(e.name ?: "tool", Modifier.widthIn(min = 90.dp), style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = if (e.kind == "tool_error") p.danger else p.accent)
                Text(e.detail.orEmpty(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = p.ink, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Working(session: Session) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = p.warn)
        val what = when (session.status) { "queued" -> "Queued"; "preparing" -> "Preparing the workspace"; else -> session.lastTool?.let { "Working · $it" } ?: "Working" }
        Text(what, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelMedium, color = p.muted)
    }
}

@Composable
private fun Notice(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, Modifier.fillMaxWidth().border(1.dp, color, RoundedCornerShape(10.dp)).padding(14.dp), color = color, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun TriageCard(store: Store, session: Session, triage: Triage) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val chosen = remember(session.id) { mutableStateMapOf<String, String>() }
    var note by rememberSaveable(session.id) { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val canDecide = store.can("complete_findings")
    Column(Modifier.fillMaxWidth().background(p.raise, RoundedCornerShape(14.dp)).border(1.dp, p.line, RoundedCornerShape(14.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(triage.title, style = MaterialTheme.typography.titleSmall, color = p.ink)
        triage.findings.forEach { f ->
            HorizontalDivider(color = p.line)
            Text(listOfNotNull(f.severity?.uppercase(), f.title).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = p.ink)
            f.location?.let { Text(it, style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = p.muted) }
            f.parkedWhy?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.muted) }
            val key = f.key
            if (triage.mine && key != null) {
                val selected = chosen[key] ?: f.draft
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Triage.DECISIONS.forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = selected == value, enabled = canDecide && !busy, onClick = { chosen[key] = value },
                            shape = SegmentedButtonDefaults.itemShape(i, Triage.DECISIONS.size),
                        ) { Text(label) }
                    }
                }
            }
        }
        if (canDecide) {
            if (triage.mine) VoiceField(store, note, { note = it }, Modifier.fillMaxWidth(), placeholder = "Record a note for the pull request and the fix session (optional)", enabled = !busy)
            Button(onClick = { confirm = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(triage.completeLabel(chosen)) }
        }
    }
    if (confirm) {
        val fixes = triage.fixes(chosen)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = {
                val plural = if (fixes == 1) "" else "s"
                Text(if (!triage.mine) "Take this review off the queue?" else if (fixes == 0) "Complete with nothing to fix?" else "Start a paid fix session for $fixes finding$plural?")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    busy = true
                    scope.launch {
                        store.mutate(
                            "complete_findings",
                            args("sessionId" to session.id, "verdicts" to triage.verdicts(chosen).ifEmpty { null }, "note" to note.trim().ifEmpty { null }),
                        )
                        busy = false
                    }
                }) { Text("Complete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Queue(store: Store, session: Session) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val queued = session.queued
    if (queued.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(p.sidebar).padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text("Queued until the turn ends", style = MaterialTheme.typography.labelSmall, color = p.muted)
        queued.forEachIndexed { index, text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (store.can("drop_message")) IconButton(onClick = { scope.launch { store.mutate("drop_message", args("sessionId" to session.id, "index" to index)) } }) {
                    Icon(Icons.Outlined.Close, "Take back", tint = p.muted)
                }
            }
        }
    }
}

private data class Attachment(val id: String, val name: String, val size: Long)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(store: Store, session: Session) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(session.id) { mutableStateOf("") }
    val attachments = remember(session.id) { mutableStateListOf<Attachment>() }
    var sending by remember { mutableStateOf(false) }
    var uploading by remember { mutableIntStateOf(0) }

    if (!store.can("message")) {
        Text(
            "This token is read-only: it can follow conversations but not write in them.",
            Modifier.fillMaxWidth().background(p.sidebar).padding(16.dp), style = MaterialTheme.typography.bodySmall, color = p.muted,
        )
        return
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (uri in uris) {
            uploading++
            scope.launch {
                try {
                    val (name, bytes) = withContext(Dispatchers.IO) {
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        } ?: "attachment"
                        name to (context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0))
                    }
                    if (bytes.size > ApiClient.UPLOAD_LIMIT) { store.failed(Exception("$name is larger than 25 MiB.")); return@launch }
                    val type = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val id = store.client?.upload(name, bytes, type) ?: return@launch
                    attachments += Attachment(id, name, bytes.size.toLong())
                } catch (e: Exception) {
                    store.failed(e)
                } finally {
                    uploading--
                }
            }
        }
    }
    fun send() {
        val body = text.trim()
        if ((body.isEmpty() && attachments.isEmpty()) || sending) return
        sending = true
        scope.launch {
            if (store.send(session.id, body, attachments.map { it.id })) { text = ""; attachments.clear() }
            sending = false
        }
    }

    Column(Modifier.fillMaxWidth().background(p.sidebar).padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (attachments.isNotEmpty() || uploading > 0) {
            FlowRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                attachments.forEach { a ->
                    Row(Modifier.background(p.field, RoundedCornerShape(8.dp)).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📎 ${a.name} · ${a.size / 1024} KB", style = MaterialTheme.typography.labelMedium, color = p.ink)
                        IconButton(onClick = { attachments.remove(a) }) { Icon(Icons.Outlined.Close, "Remove", tint = p.muted) }
                    }
                }
                if (uploading > 0) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = store.can("upload")) { Icon(Icons.Outlined.AttachFile, "Attach files", tint = p.muted) }
            VoiceField(
                store, text, { text = it }, Modifier.weight(1f), enabled = !sending,
                placeholder = when {
                    session.isClosed -> "Record a message: it reopens this conversation"
                    session.isActive && !session.liveInput -> "Record a message: it is queued until the turn ends"
                    session.awaitingAnswer -> "Record your answer to the agent"
                    else -> "Record a message for the agent"
                },
            )
            Spacer(Modifier.size(8.dp))
            FilledIconButton(
                onClick = ::send, enabled = !sending && uploading == 0 && (text.isNotBlank() || attachments.isNotEmpty()),
                modifier = Modifier.size(52.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = p.accent, contentColor = p.onAccent),
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = p.onAccent) else Icon(Icons.AutoMirrored.Outlined.Send, "Send")
            }
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private fun time(e: TranscriptEvent): String = e.time?.atZone(ZoneId.systemDefault())?.format(TIME).orEmpty()
