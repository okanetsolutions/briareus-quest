package com.okanetsolutions.briareus.quest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** Android requires this quiet indicator while the microphone foreground service is running. */
object VoiceNotification {
    private const val CHANNEL_VOICE = "voice"

    fun create(context: Context): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_VOICE, "Voice conversation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you talk to Briareus, with a button that ends the conversation."
                setShowBadge(false)
            },
        )
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
}
