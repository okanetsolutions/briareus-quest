package com.okanetsolutions.briareus.quest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.okanetsolutions.briareus.core.RuntimeCatalog
import com.okanetsolutions.briareus.core.RuntimeChoice
import com.okanetsolutions.briareus.core.Session
import com.okanetsolutions.briareus.core.args
import com.okanetsolutions.briareus.quest.Store
import kotlinx.coroutines.launch

/** The dashboard's "Welcome back" composer: a project, a branch, a runtime, the review loop, and the first message. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewSessionScreen(store: Store, initialRepo: String?, onStarted: (String) -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val projects by store.projects.collectAsState()
    var picked by rememberSaveable { mutableStateOf(initialRepo) }
    val repo = picked?.takeIf { r -> projects.any { it.repo == r } } ?: projects.firstOrNull()?.repo
    var prompt by rememberSaveable { mutableStateOf("") }
    var catalog by remember { mutableStateOf<RuntimeCatalog?>(null) }
    var choice by remember { mutableStateOf<RuntimeChoice?>(null) }
    var branches by remember { mutableStateOf<Pair<String?, List<String>>>(null to emptyList()) }
    var branch by remember { mutableStateOf<String?>(null) }
    var reviewLoop by rememberSaveable { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }

    LaunchedEffect(repo) {
        val r = repo ?: return@LaunchedEffect
        catalog = null; choice = null; branch = null
        catalog = store.runtimes(r)
        choice = catalog?.default
        branches = store.branches(r)
    }

    Box(Modifier.fillMaxSize().background(p.canvas).verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Welcome back", style = MaterialTheme.typography.titleLarge, color = p.ink)
            if (!store.can("start_session")) {
                Text("This token cannot start sessions. Ask for a Manage token to start them from the headset.", color = p.muted)
                return@Column
            }
            if (projects.isEmpty()) {
                Text("This token has no projects yet.", color = p.muted)
                return@Column
            }
            VoiceField(
                store, prompt, { prompt = it }, Modifier.fillMaxWidth(), placeholder = "Record what the agent should do",
                minHeight = 160.dp, maxHeight = 320.dp, enabled = !starting,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker("Project", projects.firstOrNull { it.repo == repo }?.title ?: "—", projects.map { it.title to it.repo }) { picked = it }
                val (default, names) = branches
                Picker("Branch", branch ?: "New branch off ${default ?: "default"}", listOf("New branch off ${default ?: "default"}" to null) + names.map { it to it }) { branch = it }
                catalog?.let { c ->
                    val current = choice
                    Picker("Provider", current?.let { c.provider(it.providerId)?.label } ?: "Project default",
                        listOf("Project default" to null) + c.providers.filter { it.available }.map { it.label to it.id }) { id -> choice = id?.let { c.choice(it) } }
                    if (current != null) {
                        val provider = c.provider(current.providerId)
                        Picker("Model", c.model(current)?.title ?: current.model ?: "Default", provider?.models.orEmpty().map { it.title to it.id }) { m -> choice = c.choice(current.providerId, m) }
                        val efforts = c.model(current)?.efforts.orEmpty()
                        if (efforts.isNotEmpty()) Picker("Effort", current.effort ?: "Default", efforts.map { it to it }) { choice = current.copy(effort = it) }
                    }
                }
                FilterChip(selected = reviewLoop, onClick = { reviewLoop = !reviewLoop }, label = { Text("Review loop") })
            }
            Button(
                enabled = !starting && prompt.isNotBlank() && repo != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                onClick = {
                    starting = true
                    scope.launch {
                        val result = store.mutate(
                            "start_session",
                            args(
                                "repo" to repo, "prompt" to prompt.trim(), "branch" to branch, "reviewLoop" to reviewLoop.takeIf { it },
                                *choice?.arguments()?.toList()?.toTypedArray().orEmpty(),
                            ),
                        )
                        starting = false
                        Session.parse(result?.get("session"))?.let { prompt = ""; onStarted(it.id) }
                    }
                },
            ) {
                if (starting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = p.onAccent) else Text("Start session")
            }
            Text("A session runs a paid agent on the server.", style = MaterialTheme.typography.bodySmall, color = p.muted)
        }
    }
}

@Composable
private fun <T> Picker(label: String, current: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text("$label: $current") }, shape = RoundedCornerShape(50))
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (text, value) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) }) }
        }
    }
}
