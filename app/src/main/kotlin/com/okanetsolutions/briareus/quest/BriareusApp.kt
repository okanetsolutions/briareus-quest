package com.okanetsolutions.briareus.quest

import android.app.Application
import android.content.Context

class BriareusApp : Application() {
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        Notifier.createChannels(this)
    }
}

val Context.store: Store get() = (applicationContext as BriareusApp).store
