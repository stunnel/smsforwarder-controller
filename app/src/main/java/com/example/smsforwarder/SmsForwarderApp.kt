package com.example.smsforwarder

import android.app.Application
import com.example.smsforwarder.data.storage.PreferencesManager

class SmsForwarderApp : Application() {

    lateinit var preferencesManager: PreferencesManager
        private set

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(this)
    }
}
