package com.okanetsolutions.briareus.quest

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.edit

class BriareusApp : Application() {
    lateinit var store: Store
        private set
    lateinit var navigator: Navigator
        private set
    lateinit var voiceSettings: VoiceSettings
        private set
    lateinit var voice: VoiceSession
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        navigator = Navigator(this, store)
        voiceSettings = VoiceSettings(this, store)
        voice = VoiceSession(this, store, navigator, voiceSettings)
        // Retire alerts and their channels on upgrades from the notification-enabled app.
        val manager = getSystemService(NotificationManager::class.java)
        val retired = setOf("alerts", "service")
        manager.activeNotifications.filter { it.notification.channelId in retired }.forEach { manager.cancel(it.tag, it.id) }
        retired.forEach(manager::deleteNotificationChannel)
        getSharedPreferences("settings", Context.MODE_PRIVATE).edit { remove("background_alerts") }
    }
}

val Context.store: Store get() = (applicationContext as BriareusApp).store
val Context.app: BriareusApp get() = applicationContext as BriareusApp
