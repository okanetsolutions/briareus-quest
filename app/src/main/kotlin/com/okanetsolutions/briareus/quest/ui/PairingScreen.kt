package com.okanetsolutions.briareus.quest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.okanetsolutions.briareus.quest.Store
import kotlinx.coroutines.launch

/** Pairs this headset with a server using a per-device token from Settings → Devices and clients. */
@Composable
fun PairingScreen(store: Store) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(store.signedOutReason) }
    val tokenFocus = remember { FocusRequester() }

    fun connect() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            error = store.pair(address, token)
            busy = false
        }
    }

    Box(Modifier.fillMaxSize().background(p.canvas).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(32.dp).background(p.raise, RoundedCornerShape(16.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Connect to Briareus", style = MaterialTheme.typography.titleLarge, color = p.ink)
            Text(
                "On a computer, open your dashboard's Settings → Devices and clients, create a token for this headset " +
                    "(Manage lets you send messages and start sessions), and enter it here with the server's address.",
                style = MaterialTheme.typography.bodyMedium, color = p.muted,
            )
            OutlinedTextField(
                address, { address = it }, Modifier.fillMaxWidth(),
                label = { Text("Server address") }, placeholder = { Text("https://briareus.example.com") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onNext = { tokenFocus.requestFocus() }),
            )
            OutlinedTextField(
                token, { token = it }, Modifier.fillMaxWidth().focusRequester(tokenFocus),
                label = { Text("Device token") }, placeholder = { Text("brm_…") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { connect() }),
            )
            error?.let { Text(it, color = p.danger, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(4.dp))
            Button(onClick = ::connect, enabled = !busy && address.isNotBlank() && token.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = p.onAccent) else Text("Connect")
            }
            Text(
                "The token is sealed with a key kept in this headset's Keystore and is sent only to this server, over HTTPS.",
                style = MaterialTheme.typography.bodySmall, color = p.muted,
            )
        }
    }
}
