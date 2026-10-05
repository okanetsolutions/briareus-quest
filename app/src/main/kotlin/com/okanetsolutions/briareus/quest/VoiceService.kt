package com.okanetsolutions.briareus.quest

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps the microphone open while a voice conversation goes on and you look at another app, as Android allows only a
 * foreground service to. Its notification ends the conversation.
 */
class VoiceService : Service() {
    private var job: Job? = null
    private var release: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val voice = (application as BriareusApp).voice
        if (intent?.action == ACTION_END) {
            voice.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, 3, VoiceNotification.create(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) {
            // Android refuses a microphone service started from the background; the panel keeps the call going while shown.
            stopSelf()
            return START_NOT_STICKY
        }
        if (release == null) release = store.watch()
        if (job == null) job = store.scope.launch {
            voice.phase.collect { if (it == VoiceSession.Phase.OFF) stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        release?.invoke()
        release = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_END = "end"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, VoiceService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }
}
