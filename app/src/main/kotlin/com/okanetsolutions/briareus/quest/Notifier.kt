package com.okanetsolutions.briareus.quest

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.okanetsolutions.briareus.core.Alert

/**
 * The headset's notifications: one per conversation that needs you, with a reply box for a question or a finished turn
 * and the agent's suggested answers as buttons, so you can answer without leaving the video you are watching.
 */
object Notifier {
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_VOICE = "voice"
    const val KEY_REPLY = "reply"
    const val SERVICE_ID = 1
    const val VOICE_ID = 3

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Sessions that need you", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A question from an agent, a finished turn, findings to decide or a failure."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Staying connected", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Briareus follows your sessions in the background."
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_VOICE, "Voice conversation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you talk to Briareus, with a button that ends the conversation."
                setShowBadge(false)
            },
        )
    }

    /** While a voice conversation goes on: what it is, a tap back to its panel, and End. */
    fun voiceNotification(context: Context): Notification {
        val end = PendingIntent.getService(
            context, 0, Intent(context, VoiceService::class.java).setAction(VoiceService.ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_VOICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Talking with Briareus")
            .setContentText("The microphone is on until you end the conversation.")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_notification, "End", end)
            .build()
    }

    fun serviceNotification(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Following your sessions")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openStatus(context))
            .build()

    fun post(context: Context, alert: Alert, options: List<String>) {
        if (!allowed(context)) return
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(ConversationActivity.pendingIntent(context, alert.sessionId))
        if (alert.repliable && context.store.can("message")) {
            val input = RemoteInput.Builder(KEY_REPLY)
                .setLabel(if (alert.kind == Alert.Kind.QUESTION) "Answer" else "Reply")
                .setChoices(options.take(3).toTypedArray())
                .build()
            val reply = NotificationCompat.Action.Builder(R.drawable.ic_notification, "Reply", ReplyReceiver.pendingIntent(context, alert.sessionId, null))
                .addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .build()
            builder.addAction(reply)
            // The agent's own options as one-tap answers, for a headset where typing is slow.
            for ((index, option) in options.take(2).withIndex()) {
                builder.addAction(R.drawable.ic_notification, option, ReplyReceiver.pendingIntent(context, alert.sessionId, option, index + 1))
            }
            if (alert.kind == Alert.Kind.FINISHED && options.isEmpty()) {
                builder.addAction(R.drawable.ic_notification, "Continue", ReplyReceiver.pendingIntent(context, alert.sessionId, "Continue", 1))
            }
        }
        notify(context, alert.sessionId, builder.build())
    }

    /** After a reply from the notification: a quiet confirmation in its place, or the error. */
    fun replied(context: Context, sessionId: String, text: String, error: String?) {
        if (!allowed(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (error == null) "Sent" else "Not sent")
            .setContentText(error ?: text)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(if (error == null) 4_000 else 0)
            .setContentIntent(ConversationActivity.pendingIntent(context, sessionId))
            .build()
        notify(context, sessionId, n)
    }

    fun cancel(context: Context, sessionId: String) = NotificationManagerCompat.from(context).cancel(sessionId, ID_ALERT)

    private fun allowed(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notify(context: Context, tag: String, notification: Notification) {
        if (!allowed(context)) return
        // The user may withdraw the permission between the check and the call.
        try {
            NotificationManagerCompat.from(context).notify(tag, ID_ALERT, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun openStatus(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, StatusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private const val ID_ALERT = 2
}
