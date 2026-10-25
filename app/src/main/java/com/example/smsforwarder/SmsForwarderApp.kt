package com.example.smsforwarder

import android.app.Application
import com.example.smsforwarder.data.storage.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SmsForwarderApp : Application() {

    lateinit var preferencesManager: PreferencesManager
        private set

    // Process-wide scope for work that must outlive a single Activity/Receiver (e.g. persisting
    // the bot-enabled flag). The bot is NOT started from here: Application.onCreate runs on every
    // process start, including background ones where starting a foreground service is not allowed.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(this)
    }
}
