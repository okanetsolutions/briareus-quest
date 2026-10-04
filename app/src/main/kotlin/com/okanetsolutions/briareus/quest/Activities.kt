package com.okanetsolutions.briareus.quest

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.okanetsolutions.briareus.quest.ui.BriareusTheme
import com.okanetsolutions.briareus.quest.ui.ConversationScreen
import com.okanetsolutions.briareus.quest.ui.HomeScreen
import com.okanetsolutions.briareus.quest.ui.PairingScreen
import com.okanetsolutions.briareus.quest.ui.PreviewScreen
import com.okanetsolutions.briareus.quest.ui.StatusScreen
import com.okanetsolutions.briareus.quest.ui.VoiceScreen

/** Every window holds the events stream open while it is shown, and shows the store's messages as toasts. */
abstract class BriareusActivity : ComponentActivity() {
    private var release: (() -> Unit)? = null

    override fun onStart() {
        super.onStart()
        release = store.watch()
    }

    override fun onStop() {
        release?.invoke()
        release = null
        super.onStop()
    }

    protected fun content(body: @Composable () -> Unit) = setContent {
        BriareusTheme {
            val context = LocalContext.current
            LaunchedEffect(Unit) { store.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
            body()
        }
    }
}

/** The main window: pairing until connected, then the projects and conversations beside the chosen one. */
class MainActivity : BriareusActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content {
            val connection by store.connection.collectAsState()
            if (connection == null) {
                PairingScreen(store)
            } else {
                LaunchedEffect(Unit) {
                    // Connected: ask once to notify, and keep alerts going while you are in other apps.
                    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    EventsService.start(this@MainActivity)
                }
                val navigator = app.navigator
                val pane by navigator.pane.collectAsState()
                val voicePhase by app.voice.phase.collectAsState()
                HomeScreen(
                    store, pane, onPane = { navigator.pane.value = it },
                    onPopOut = { id -> Windows.openConversation(this, id); navigator.pane.value = null },
                    onStatusWindow = { Windows.openStatus(this) },
                    voiceOn = voicePhase != VoiceSession.Phase.OFF,
                    onVoiceWindow = { Windows.openVoice(this) },
                    onBackgroundAlerts = { on ->
                        store.backgroundAlerts = on
                        if (on) EventsService.start(this) else EventsService.stop(this)
                    },
                )
            }
        }
    }
}

/** One conversation in its own panel. */
class ConversationActivity : BriareusActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_SESSION) ?: intent.data?.lastPathSegment
        if (id == null || store.connection.value == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        content {
            val connection by store.connection.collectAsState()
            LaunchedEffect(connection) { if (connection == null) finishAndRemoveTask() }
            ConversationScreen(store, id, onBack = null, onPopOut = null, onDeleted = { finishAndRemoveTask() })
        }
    }

    companion object {
        const val EXTRA_SESSION = "session"

        fun pendingIntent(context: Context, sessionId: String): PendingIntent = PendingIntent.getActivity(
            context, sessionId.hashCode(), Windows.conversationIntent(context, sessionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** The narrow status panel. */
class StatusActivity : BriareusActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (store.connection.value == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        content {
            val connection by store.connection.collectAsState()
            LaunchedEffect(connection) { if (connection == null) finishAndRemoveTask() }
            StatusScreen(store) { id -> Windows.openConversation(this, id) }
        }
    }
}

/** A ▶ Run preview in its own panel; opening another brings this panel back with the new address. */
class PreviewActivity : BriareusActivity() {
    private var url by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL)
        if (url == null || store.connection.value == null) {
            finish()
            return
        }
        content {
            val connection by store.connection.collectAsState()
            LaunchedEffect(connection) { if (connection == null) finishAndRemoveTask() }
            url?.let { key(it) { PreviewScreen(store, it) } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_URL)?.let { url = it }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}

/** The voice panel: a spoken conversation about your projects that also drives the other windows. */
class VoiceActivity : BriareusActivity() {
    private var startAfterPermission = false
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && startAfterPermission) app.voice.start()
        else if (!granted) Toast.makeText(this, "Briareus needs the microphone to talk with you.", Toast.LENGTH_LONG).show()
        startAfterPermission = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (store.connection.value == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        content {
            val connection by store.connection.collectAsState()
            LaunchedEffect(connection) { if (connection == null) { app.voice.stop(); finishAndRemoveTask() } }
            VoiceScreen(app.voice, app.voiceSettings) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) app.voice.start()
                else { startAfterPermission = true; microphone.launch(Manifest.permission.RECORD_AUDIO) }
            }
        }
    }
}
