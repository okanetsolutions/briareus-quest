package com.okanetsolutions.briareus.quest

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.okanetsolutions.briareus.quest.ui.Detail
import com.okanetsolutions.briareus.quest.ui.Pane

/** A native document view with its own navigation, independent of the main and voice windows. */
class PanelActivity : BriareusActivity() {
    private var pane by mutableStateOf<Pane?>(null)
    private val panelKey get() = taskId.toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        if (pane == null || store.connection.value == null) { finish(); return }
        content {
            val connection by store.connection.collectAsState()
            LaunchedEffect(connection) { if (connection == null) finishAndRemoveTask() }
            pane?.let { current ->
                Detail(store, current, onPane = { next ->
                    when (next) {
                        is Pane.Open -> Windows.openConversation(this, next.sessionId)
                        is Pane.New -> { app.navigator.pane.value = next; startActivity(Intent(this, MainActivity::class.java)) }
                        null -> finishAndRemoveTask()
                        else -> { pane = next; app.navigator.panels[panelKey] = next }
                    }
                }, onPopOut = { Windows.openConversation(this, it) }, back = { finishAndRemoveTask() })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        pane?.let { app.navigator.panels[panelKey] = it }
    }

    override fun onStop() {
        app.navigator.panels.remove(panelKey)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        pane?.let { app.navigator.panels[panelKey] = it }
    }

    private fun readIntent(intent: Intent) {
        val uri = intent.data ?: return
        val parts = uri.pathSegments
        val repo = parts.getOrNull(1) ?: return
        val number = parts.getOrNull(2)?.toIntOrNull()
        pane = when (parts.firstOrNull()) {
            "pull" -> number?.let { Pane.Pull(repo, it, uri.getQueryParameter("view")) }
            "pulls" -> Pane.Pulls(repo)
            "issue" -> number?.let { Pane.Issue(repo, it) }
            "issues" -> Pane.Issues(repo)
            else -> null
        }
    }
}
