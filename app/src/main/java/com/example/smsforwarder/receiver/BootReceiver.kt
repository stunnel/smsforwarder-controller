package com.example.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.smsforwarder.SmsForwarderApp
import com.example.smsforwarder.service.TelegramBotService
import kotlinx.coroutines.launch

/**
 * Starts the TelegramBotService after boot (before and after the first unlock) and after an
 * app update, if the user left it enabled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val bootStage = when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> TelegramBotService.BOOT_STAGE_LOCKED
            Intent.ACTION_BOOT_COMPLETED -> TelegramBotService.BOOT_STAGE_UNLOCKED
            Intent.ACTION_MY_PACKAGE_REPLACED -> null
            else -> return
        }

        val app = context.applicationContext as SmsForwarderApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                val prefs = app.preferencesManager
                if (prefs.isBotEnabled() && prefs.getBotToken().isNotBlank()) {
                    TelegramBotService.start(context, bootStage)
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
