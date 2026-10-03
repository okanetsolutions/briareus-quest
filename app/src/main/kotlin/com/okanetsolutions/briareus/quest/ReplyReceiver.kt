package com.okanetsolutions.briareus.quest

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.core.app.RemoteInput
import kotlinx.coroutines.launch

/** Sends a reply typed or tapped in a notification as the conversation's next message. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return
        val text = (intent.getStringExtra(EXTRA_TEXT) ?: RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString())
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val pending = goAsync()
        val store = context.store
        store.scope.launch {
            try {
                var error: String? = null
                val sub = launch { store.messages.collect { error = it } }
                val sent = store.send(sessionId, text)
                sub.cancel()
                Notifier.replied(context, sessionId, text, if (sent) null else error ?: "The message could not be sent.")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_TEXT = "text"

        /** [text] null takes the notification's reply box; otherwise a one-tap answer. [slot] keeps each button's intent distinct. */
        fun pendingIntent(context: Context, sessionId: String, text: String?, slot: Int = 0): PendingIntent {
            val intent = Intent(context, ReplyReceiver::class.java)
                .setData("briareus://reply/$sessionId/$slot".toUri())
                .putExtra(EXTRA_SESSION, sessionId)
            if (text != null) intent.putExtra(EXTRA_TEXT, text)
            // A reply box needs a mutable intent to carry what was typed.
            val flags = if (text == null) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
