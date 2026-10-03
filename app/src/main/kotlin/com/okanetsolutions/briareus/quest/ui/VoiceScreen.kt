package com.okanetsolutions.briareus.quest.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.okanetsolutions.briareus.core.Voice
import com.okanetsolutions.briareus.core.VoiceTool
import com.okanetsolutions.briareus.quest.VoiceSession
import com.okanetsolutions.briareus.quest.VoiceSettings
import kotlinx.coroutines.delay

/**
 * The voice panel: GPT-Realtime about your projects, and the hands on the app's windows. What both sides said scrolls as
 * captions; what it did, on the server or on screen, is listed under them.
 */
@Composable
fun VoiceScreen(voice: VoiceSession, settings: VoiceSettings, onStart: () -> Unit) {
    val p = LocalPalette.current
    val phase by voice.phase.collectAsState()
    val lines by voice.lines.collectAsState()
    val steps by voice.steps.collectAsState()
    val notice by voice.notice.collectAsState()
    val hasKey by settings.hasKey.collectAsState()
    var settingsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { settings.refresh() }

    Column(Modifier.fillMaxSize().background(p.canvas)) {
        Row(Modifier.fillMaxWidth().background(p.sidebar).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Voice", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = p.ink)
            IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Outlined.Tune, "Voice settings", tint = p.muted) }
        }
        HorizontalDivider(color = p.line)

        val list = rememberLazyListState()
        LaunchedEffect(lines.size, lines.lastOrNull()?.text?.length) { if (lines.isNotEmpty()) list.scrollToItem(lines.size) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "intro") { if (lines.isEmpty()) Intro(hasKey) { settingsOpen = true } }
            items(lines, key = { it.id }) { line ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (line.user) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(
                        line.text,
                        Modifier.widthIn(max = 520.dp).background(if (line.user) p.accent.copy(alpha = 0.16f) else p.raise, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge, color = p.ink,
                    )
                }
            }
        }

        if (steps.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(p.sidebar).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.takeLast(4).forEach { step ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (val s = step.state) {
                            VoiceSession.Step.State.Running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            VoiceSession.Step.State.Waiting -> Icon(Icons.AutoMirrored.Outlined.HelpOutline, "Waiting for your yes", Modifier.size(18.dp), tint = p.warn)
                            VoiceSession.Step.State.Done -> Icon(Icons.Outlined.CheckCircle, "Done", Modifier.size(18.dp), tint = p.ok)
                            is VoiceSession.Step.State.Failed -> Icon(Icons.Outlined.Cancel, s.why, Modifier.size(18.dp), tint = p.danger)
                        }
                        Text(step.title, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        Controls(voice, phase, hasKey, notice, onStart)
    }

    if (settingsOpen) VoiceSettingsDialog(settings) { settingsOpen = false }
}

@Composable
private fun Intro(hasKey: Boolean, onSettings: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Talk to your agents", style = MaterialTheme.typography.titleLarge, color = p.ink)
        Text(
            "Ask what a conversation is doing, answer an agent, start one or stop one, and have the windows follow: " +
                "“show me that pull request's files”, “open the login conversation beside this one”, “search for billing”. " +
                "Changes on the server are read back and wait for your yes.",
            style = MaterialTheme.typography.bodyMedium, color = p.muted,
        )
        if (!hasKey) OutlinedButton(onClick = onSettings) { Text("Add your OpenAI API key") }
    }
}

@Composable
private fun Controls(voice: VoiceSession, phase: VoiceSession.Phase, hasKey: Boolean, notice: String?, onStart: () -> Unit) {
    val p = LocalPalette.current
    val muted by voice.muted.collectAsState()
    val speaking by voice.speaking.collectAsState()
    val started by voice.started.collectAsState()
    val cost by voice.cost.collectAsState()
    val on = phase != VoiceSession.Phase.OFF
    val pulse by animateFloatAsState(if (speaking) 1.08f else 1f, label = "speaking")

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.danger) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            IconButton(onClick = voice::toggleMute, enabled = phase == VoiceSession.Phase.LIVE, modifier = Modifier.size(56.dp).background(p.raise, CircleShape)) {
                Icon(if (muted) Icons.Outlined.MicOff else Icons.Outlined.Mic, if (muted) "Unmute" else "Mute", tint = if (muted) p.danger else p.ink)
            }
            val enabled = phase != VoiceSession.Phase.CLOSING && (on || hasKey)
            Box(
                Modifier.size(84.dp).scale(pulse).background(if (on) p.danger else if (enabled) p.accent else p.field, CircleShape)
                    .clickable(enabled = enabled) { if (on) voice.stop() else onStart() },
                contentAlignment = Alignment.Center,
            ) {
                when (phase) {
                    VoiceSession.Phase.CONNECTING, VoiceSession.Phase.CLOSING -> CircularProgressIndicator(Modifier.size(32.dp), color = p.onAccent)
                    VoiceSession.Phase.LIVE -> Icon(Icons.Outlined.CallEnd, "End the conversation", Modifier.size(36.dp), tint = p.onAccent)
                    VoiceSession.Phase.OFF -> Icon(Icons.Outlined.GraphicEq, "Start a conversation", Modifier.size(36.dp), tint = p.onAccent)
                }
            }
            // Keeps the big button centred.
            Spacer(Modifier.size(56.dp))
        }
        Text(
            when (phase) {
                VoiceSession.Phase.OFF -> if (hasKey) "Tap to talk" else "Add an OpenAI API key in the voice settings"
                VoiceSession.Phase.CONNECTING -> "Connecting…"
                VoiceSession.Phase.CLOSING -> "Ending…"
                VoiceSession.Phase.LIVE -> (if (muted) "Muted" else if (speaking) "Speaking" else "Listening") + (started?.let { " · " + elapsed(it) }.orEmpty())
            },
            style = MaterialTheme.typography.labelMedium, color = p.muted,
        )
        cost?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = p.muted) }
    }
}

/** "1:05", ticking each second. */
@Composable
private fun elapsed(since: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val s = ((now - since) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Voice settings: the OpenAI API key, the voice, and how long a silence ends a conversation. */
@Composable
fun VoiceSettingsDialog(settings: VoiceSettings, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val hasKey by settings.hasKey.collectAsState()
    val voice by settings.voice.collectAsState()
    val idle by settings.idleMinutes.collectAsState()
    var key by remember { mutableStateOf("") }
    var voices by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hasKey) Text("✓ API key saved on this headset", style = MaterialTheme.typography.bodyMedium, color = p.ok)
                OutlinedTextField(
                    key, { key = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(if (hasKey) "Replace the API key" else "OpenAI API key") }, visualTransformation = PasswordVisualTransformation(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { settings.save(key); key = "" }, enabled = key.isNotBlank()) { Text("Save key") }
                    if (hasKey) TextButton(onClick = settings::removeKey) { Text("Remove key", color = p.danger) }
                }
                Text(
                    "The voice talks to ${Voice.MODEL} with this key, sealed on this headset and sent only to OpenAI. OpenAI bills the audio and " +
                        "text it hears and says, and the transcription of your speech apart. Forgetting the connection erases the key too.",
                    style = MaterialTheme.typography.bodySmall, color = p.muted,
                )
                HorizontalDivider(color = p.line)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Voice", Modifier.weight(1f), color = p.ink)
                    Box {
                        TextButton(onClick = { voices = true }) { Text(voice.replaceFirstChar { it.uppercase() }) }
                        DropdownMenu(voices, onDismissRequest = { voices = false }) {
                            Voice.VOICES.forEach { v ->
                                DropdownMenuItem(text = { Text(v.replaceFirstChar { it.uppercase() }) }, onClick = { settings.setVoice(v); voices = false })
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("End after silence", Modifier.weight(1f), color = p.ink)
                    TextButton(onClick = { settings.setIdleMinutes(idle - 1) }, enabled = idle > 0) { Text("−") }
                    Text(if (idle == 0) "Never" else "$idle min", color = p.ink)
                    TextButton(onClick = { settings.setIdleMinutes(idle + 1) }, enabled = idle < 30) { Text("+") }
                }
                Text("A change of voice applies to the next conversation.", style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** What the step does, in a few words. */
private val VoiceSession.Step.title: String
    get() {
        val waiting = state == VoiceSession.Step.State.Waiting
        fun n(key: String) = input[key]?.toString()?.trim('"').orEmpty()
        fun s(key: String) = (input[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        return when (tool) {
            VoiceTool.LIST_CONVERSATIONS -> "Read the conversations"
            VoiceTool.READ_CONVERSATION -> "Read a conversation"
            VoiceTool.LIST_PULL_REQUESTS -> "Read the pull requests"
            VoiceTool.READ_PULL_REQUEST -> "Read the changes of #${n("number")}"
            VoiceTool.MERGE_PULL_REQUEST -> (if (waiting) "Asked to merge #" else "Merge #") + n("number")
            VoiceTool.WAITING_FINDINGS -> "Read the findings waiting"
            VoiceTool.LIST_ISSUES -> "Read the issues"
            VoiceTool.READ_ISSUE -> "Read issue #${n("issue")}"
            VoiceTool.START_CONVERSATION -> (if (waiting) "Asked to start an agent: " else "Start an agent: ") + s("prompt")
            VoiceTool.WORK_ON_ISSUE -> (if (waiting) "Asked to work on issue #" else "Work on issue #") + n("issue")
            VoiceTool.SEND_MESSAGE -> (if (waiting) "Asked to send: " else "Send: ") + s("text")
            VoiceTool.STOP_CONVERSATION -> if (waiting) "Asked to stop an agent" else "Stop an agent"
            VoiceTool.READ_SCREEN -> "Look at the screen"
            VoiceTool.SHOW_CONVERSATION -> "Show a conversation"
            VoiceTool.SHOW_PULL_REQUEST -> "Show pull request" + (n("number").takeIf { it.isNotEmpty() }?.let { " #$it" } ?: "")
            VoiceTool.SHOW_ISSUE -> "Show issue #${n("issue")}"
            VoiceTool.SHOW_PREVIEW -> "Show the running app"
            VoiceTool.SHOW_NEW_CONVERSATION -> "Show the new conversation form"
            VoiceTool.SEARCH_CONVERSATIONS -> s("query").let { if (it.isBlank()) "Clear the search" else "Search for “$it”" }
            VoiceTool.SHOW_STATUS_PANEL -> "Show the status panel"
            VoiceTool.GO_HOME -> "Show the conversation list"
            null -> name
        }
    }
