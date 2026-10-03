package com.okanetsolutions.briareus.quest

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * The way in from outside the space, for notifications: every panel lives in the one immersive activity, so a tap opens
 * it with the panel to bring to the front named in the intent.
 */
object Windows {
    const val EXTRA_PANEL = "panel"
    const val EXTRA_SESSION = "session"
    const val PANEL_CONVERSATION = "conversation"
    const val PANEL_STATUS = "status"
    const val PANEL_VOICE = "voice"

    private fun intent(context: Context, panel: String) = Intent(context, MainActivity::class.java)
        .putExtra(EXTRA_PANEL, panel)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    // The data keeps one pending intent per conversation apart.
    fun conversationIntent(context: Context, sessionId: String): Intent =
        intent(context, PANEL_CONVERSATION).setData("briareus://session/$sessionId".toUri()).putExtra(EXTRA_SESSION, sessionId)

    fun conversation(context: Context, sessionId: String): PendingIntent = pending(context, sessionId.hashCode(), conversationIntent(context, sessionId))

    fun status(context: Context): PendingIntent = pending(context, 0, intent(context, PANEL_STATUS))

    fun voice(context: Context): PendingIntent = pending(context, 1, intent(context, PANEL_VOICE))

    private fun pending(context: Context, code: Int, intent: Intent) =
        PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
