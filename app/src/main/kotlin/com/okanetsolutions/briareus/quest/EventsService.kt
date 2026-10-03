package com.okanetsolutions.briareus.quest

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.okanetsolutions.briareus.core.AttentionTracker
import com.okanetsolutions.briareus.core.SessionList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the events stream open while you are in other apps (YouTube, WhatsApp, the browser) and turns the moments a
 * session starts waiting for you into notifications. Its own notification says how many sessions work and wait.
 */
class EventsService : Service() {
    private var release: (() -> Unit)? = null
    private val jobs = ArrayList<Job>()
    private val tracker = AttentionTracker()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, Notifier.SERVICE_ID, Notifier.serviceNotification(this, "Connecting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (release != null) return START_STICKY
        val store = store
        if (store.connection.value == null || !store.backgroundAlerts) { stopSelf(); return START_NOT_STICKY }
        release = store.watch()
        jobs += store.scope.launch {
            store.updates.collect { session ->
                val alert = tracker.update(session, store.projectTitle(session.repo)) ?: return@collect
                if (session.id in store.visibleSessions.value) return@collect
                val conversation = store.conversation(session.id)
                if (alert.kind == com.okanetsolutions.briareus.core.Alert.Kind.QUESTION) conversation.catchUp()
                val options = conversation.pendingQuestion()?.options.orEmpty()
                Notifier.post(this@EventsService, alert, options)
            }
        }
        jobs += store.scope.launch {
            store.sessions.collectLatest { sessions ->
                val c = SessionList.counts(sessions.values.toList())
                val text = listOfNotNull(
                    "${c.working} working".takeIf { c.working > 0 },
                    "${c.needsYou} waiting for you".takeIf { c.needsYou > 0 },
                ).joinToString(" · ").ifEmpty { "All quiet" }
                getSystemService(android.app.NotificationManager::class.java).notify(Notifier.SERVICE_ID, Notifier.serviceNotification(this@EventsService, text))
            }
        }
        jobs += store.scope.launch {
            store.connection.collect { if (it == null) stopSelf() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        jobs.forEach { it.cancel() }
        release?.invoke()
        release = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            if (!context.store.backgroundAlerts || context.store.connection.value == null) return
            runCatching { context.startForegroundService(Intent(context, EventsService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EventsService::class.java))
        }
    }
}
