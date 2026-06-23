package com.example.smsforwarder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.example.smsforwarder.service.TelegramBotService
import com.example.smsforwarder.ui.screen.ConfigScreen
import com.example.smsforwarder.ui.theme.SmsForwarderTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as SmsForwarderApp

        setContent {
            SmsForwarderTheme {
                var isBotRunning by remember { mutableStateOf(TelegramBotService.isRunning) }

                // Poll service state every 2 seconds
                LaunchedEffect(Unit) {
                    while (true) {
                        isBotRunning = TelegramBotService.isRunning
                        delay(2_000)
                    }
                }

                ConfigScreen(
                    preferencesManager = app.preferencesManager,
                    isBotRunning = isBotRunning,
                    onStartBot = { TelegramBotService.start(this@MainActivity) },
                    onStopBot = { TelegramBotService.stop(this@MainActivity) }
                )
            }
        }
    }
}
