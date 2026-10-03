package com.okanetsolutions.briareus.quest

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Horizon OS shows each task as a panel of its own. A conversation opened this way gets its own panel beside the main
 * window; opening the same conversation again brings its panel back rather than adding another.
 */
object Windows {
    fun conversationIntent(context: Context, sessionId: String): Intent =
        Intent(context, ConversationActivity::class.java)
            .setData("briareus://session/$sessionId".toUri())
            .putExtra(ConversationActivity.EXTRA_SESSION, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)

    fun openConversation(context: Context, sessionId: String) = context.startActivity(conversationIntent(context, sessionId))

    fun openStatus(context: Context) = context.startActivity(
        Intent(context, StatusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT),
    )
}
