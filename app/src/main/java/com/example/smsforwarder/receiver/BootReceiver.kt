package com.example.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.smsforwarder.service.TelegramBotService

/**
 * BroadcastReceiver that listens for device boot completion
 * and automatically starts the TelegramBotService.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            TelegramBotService.start(context)
        }
    }
}