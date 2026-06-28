package com.example.smsforwarder

import android.app.Application
import com.example.smsforwarder.data.storage.PreferencesManager
import com.example.smsforwarder.service.TelegramBotService

class SmsForwarderApp : Application() {

    lateinit var preferencesManager: PreferencesManager
        private set

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(this)
        TelegramBotService.start(this)
    }
}
