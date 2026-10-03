package com.okanetsolutions.briareus.quest

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import kotlinx.coroutines.launch

/** Sends an answer tapped in a notification as the conversation's next message. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() } ?: return
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

        /** A one-tap answer. [slot] keeps each button's intent distinct. */
        fun pendingIntent(context: Context, sessionId: String, text: String, slot: Int): PendingIntent {
            val intent = Intent(context, ReplyReceiver::class.java)
                .setData("briareus://reply/$sessionId/$slot".toUri())
                .putExtra(EXTRA_SESSION, sessionId)
                .putExtra(EXTRA_TEXT, text)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
