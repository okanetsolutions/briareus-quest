package com.okanetsolutions.briareus.quest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.okanetsolutions.briareus.quest.Windows
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.okanetsolutions.briareus.core.Session
import com.okanetsolutions.briareus.core.SessionList
import com.okanetsolutions.briareus.quest.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** What the right pane shows: the new session composer, a conversation, or pull requests. Null is nothing chosen yet. */
sealed interface Pane {
    data class New(val repo: String? = null, val prompt: String? = null) : Pane
    data class Open(val sessionId: String) : Pane
    data class Pulls(val repo: String) : Pane
    data class Pull(val repo: String, val number: Int, val view: String? = null) : Pane
    data class Issues(val repo: String) : Pane
    data class Issue(val repo: String, val number: Int) : Pane
}

/**
 * The main window, laid out as the dashboard is: projects and conversations in a column on the left, the chosen
 * conversation (or the composer) on the right. A window narrower than 900dp shows one at a time with a back button.
 */
@Composable
fun HomeScreen(
    store: Store,
    pane: Pane?,
    onPane: (Pane?) -> Unit,
    onPopOut: (String) -> Unit,
    voiceOn: Boolean,
    onVoiceWindow: () -> Unit,
) {
    val header = SidebarHeader(voiceOn, onVoiceWindow)
    val p = LocalPalette.current
    BoxWithConstraints(Modifier.fillMaxSize().background(p.canvas)) {
        if (maxWidth >= 900.dp) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(store, pane, onPane, header, Modifier.width(320.dp).fillMaxHeight())
                VerticalDivider(color = p.line)
                Box(Modifier.weight(1f).fillMaxHeight()) { Detail(store, pane ?: Pane.New(), onPane, onPopOut, back = null) }
            }
        } else if (pane == null) {
            Sidebar(store, null, onPane, header, Modifier.fillMaxSize())
        } else {
            Detail(store, pane, onPane, onPopOut, back = { onPane(null) })
        }
    }
}

@Composable
fun Detail(store: Store, pane: Pane, onPane: (Pane?) -> Unit, onPopOut: (String) -> Unit, back: (() -> Unit)?) {
    when (pane) {
        is Pane.New -> Column {
            if (back != null) TextButton(onClick = back, modifier = Modifier.padding(8.dp)) { Text("← Conversations") }
            NewSessionScreen(store, pane.repo, pane.prompt) { onPane(Pane.Open(it)) }
        }
        is Pane.Open -> ConversationScreen(store, pane.sessionId, onBack = back, onPopOut = { onPopOut(pane.sessionId) }, onDeleted = { onPane(null) })
        is Pane.Pulls -> PullsScreen(store, pane.repo, onPane, back)
        is Pane.Pull -> PullScreen(store, pane.repo, pane.number, onPane, pane.view)
        is Pane.Issues -> IssuesScreen(store, pane.repo, onPane, back)
        is Pane.Issue -> IssueScreen(store, pane.repo, pane.number, onPane)
    }
}

/** The sidebar's voice control. */
private class SidebarHeader(val voiceOn: Boolean, val onVoiceWindow: () -> Unit)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Sidebar(
    store: Store, pane: Pane?, onPane: (Pane?) -> Unit, header: SidebarHeader, modifier: Modifier,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projects by store.projects.collectAsState()
    val sessions by store.sessions.collectAsState()
    val link by store.link.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val groups = remember(projects, sessions) { SessionList.group(projects, sessions.values.toList()) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }

    Column(modifier.background(p.sidebar)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Briareus", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = p.ink)
            LinkIndicator(link)
            IconButton(onClick = header.onVoiceWindow) {
                Icon(Icons.Outlined.GraphicEq, if (header.voiceOn) "Voice conversation on" else "Talk to Briareus", tint = if (header.voiceOn) p.accent else p.muted)
            }
            SettingsMenu(store)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).background(p.accent, RoundedCornerShape(10.dp))
                .clickable(enabled = store.can("start_session")) { onPane(Pane.New()) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Add, null, tint = p.onAccent)
            Text("New session", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = p.onAccent)
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; scope.launch { runCatching { store.refresh() }.onFailure { store.failed(it) }; refreshing = false } },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { group ->
                    val repo = group.project.repo
                    val isCollapsed = collapsed[repo] == true
                    item(key = "p:$repo") {
                        Row(
                            Modifier.fillMaxWidth().clickable { collapsed[repo] = !isCollapsed }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (isCollapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, null, tint = p.muted)
                            Text(
                                group.project.title, Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.titleSmall,
                                color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { Windows.openVoice(context, repo) }) { Icon(Icons.Outlined.GraphicEq, "Talk about ${group.project.title}", tint = p.muted) }
                            if (group.working) Dot(p.warn, Modifier.padding(end = 8.dp))
                            if (group.needsYou > 0) Text("${group.needsYou}", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelMedium, color = p.accent)
                            Text("${group.sessions.size}", style = MaterialTheme.typography.labelMedium, color = p.muted)
                        }
                    }
                    if (!isCollapsed && store.can("pulls")) item(key = "pulls:$repo") {
                        val selected = (pane as? Pane.Pulls)?.repo == repo || (pane as? Pane.Pull)?.repo == repo
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)
                                .background(if (selected) p.field else p.sidebar, RoundedCornerShape(8.dp))
                                .clickable { onPane(Pane.Pulls(repo)) }.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.MergeType, null, Modifier.size(18.dp), tint = p.muted)
                            Text("Pull requests", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium, color = p.ink)
                        }
                    }
                    if (!isCollapsed && store.can("pulls")) item(key = "issues:$repo") {
                        TextButton(onClick = { onPane(Pane.Issues(repo)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Issues", color = if ((pane as? Pane.Issues)?.repo == repo || (pane as? Pane.Issue)?.repo == repo) p.accent else p.ink)
                        }
                    }
                    if (!isCollapsed) items(group.sessions, key = { it.id }) { s ->
                        SessionRow(s, selected = (pane as? Pane.Open)?.sessionId == s.id, now = now) { onPane(Pane.Open(s.id)) }
                    }
                }
                if (groups.isEmpty()) item { Text("No projects yet.", Modifier.padding(24.dp), color = p.muted) }
            }
        }
    }
}

@Composable
private fun SessionRow(s: Session, selected: Boolean, now: Instant, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)
            .background(if (selected) p.field else p.sidebar, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(stateColor(s.state))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(s.title, style = MaterialTheme.typography.bodyMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(if (s.state.needsYou) s.state.label else null, s.provider.ifBlank { null }, s.branch).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = if (s.state.needsYou) p.accent else p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(SessionList.age(s.endedAt ?: s.createdAt, now), style = MaterialTheme.typography.labelSmall, color = p.muted)
    }
}

@Composable
private fun SettingsMenu(store: Store) {
    val p = LocalPalette.current
    val connection by store.connection.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.Settings, "Settings", tint = p.muted) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            connection?.let { c ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(c.device.label.ifBlank { "This headset" }, style = MaterialTheme.typography.titleSmall, color = p.ink)
                    Text("${c.address.host} · ${c.device.permission}", style = MaterialTheme.typography.bodySmall, color = p.muted)
                }
                HorizontalDivider(color = p.line)
            }
            DropdownMenuItem(text = { Text("Forget this connection") }, onClick = { open = false; store.forget() })
            DropdownMenuItem(text = { Text("Revoke the token and forget", color = p.danger) }, onClick = { open = false; store.forget(revoke = true) })
        }
    }
}
