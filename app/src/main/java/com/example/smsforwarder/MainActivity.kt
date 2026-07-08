package com.example.smsforwarder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.example.smsforwarder.service.TelegramBotService
import com.example.smsforwarder.ui.screen.ConfigScreen
import com.example.smsforwarder.ui.theme.SmsForwarderTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    // Callback invoked after the user responds to the notification permission dialog
    private var pendingStartBot: (() -> Unit)? = null

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingStartBot?.invoke()
            }
            pendingStartBot = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as SmsForwarderApp

        setContent {
            SmsForwarderTheme {
                var isBotRunning by remember { mutableStateOf(TelegramBotService.isRunning) }

                // Poll service state every 30 seconds
                LaunchedEffect(Unit) {
                    while (true) {
                        isBotRunning = TelegramBotService.isRunning
                        delay(30_000)
                    }
                }

                ConfigScreen(
                    preferencesManager = app.preferencesManager,
                    isBotRunning = isBotRunning,
                    onStartBot = { startBotWithPermission() },
                    onStopBot = { TelegramBotService.stop(this@MainActivity) },
                    onConfigChanged = {
                        if (TelegramBotService.isRunning) {
                            TelegramBotService.onConfigChanged()
                        }
                    }
                )
            }
        }
    }

    private fun startBotWithPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            when {
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                    TelegramBotService.start(this)
                }
                else -> {
                    pendingStartBot = { TelegramBotService.start(this) }
                    requestNotificationPermission.launch(permission)
                }
            }
        } else {
            TelegramBotService.start(this)
        }
    }
}
