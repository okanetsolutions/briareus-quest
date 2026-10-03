package com.okanetsolutions.briareus.quest

import android.app.Application
import android.content.Context

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
        Notifier.createChannels(this)
    }
}

val Context.store: Store get() = (applicationContext as BriareusApp).store
val Context.app: BriareusApp get() = applicationContext as BriareusApp
