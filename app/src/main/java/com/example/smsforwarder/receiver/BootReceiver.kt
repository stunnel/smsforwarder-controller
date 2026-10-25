package com.example.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.smsforwarder.SmsForwarderApp
import com.example.smsforwarder.service.TelegramBotService
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that listens for device boot completion
 * and starts the TelegramBotService if the user left it enabled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val app = context.applicationContext as SmsForwarderApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                val prefs = app.preferencesManager
                if (prefs.isBotEnabled() && prefs.getBotToken().isNotBlank()) {
                    TelegramBotService.start(context)
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
