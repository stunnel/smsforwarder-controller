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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.example.smsforwarder.service.TelegramBotService
import com.example.smsforwarder.ui.screen.ConfigScreen
import com.example.smsforwarder.ui.theme.SmsForwarderTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                // Use StateFlow from the service – instant updates, no polling needed!
                val isBotRunning by TelegramBotService.isRunningState.collectAsState()

                var isTransitioning by remember { mutableStateOf(false) }

                // Watch isBotRunning: once it switches, clear the transitioning flag.
                // Also add a safety timeout (15s) for the transition.
                LaunchedEffect(isBotRunning) {
                    if (isBotRunning) {
                        isTransitioning = false
                    }
                }
                LaunchedEffect(isTransitioning) {
                    if (isTransitioning) {
                        delay(15_000)
                        isTransitioning = false
                    }
                }

                ConfigScreen(
                    preferencesManager = app.preferencesManager,
                    isBotRunning = isBotRunning,
                    isTransitioning = isTransitioning,
                    onStartBot = {
                        isTransitioning = true
                        startBotWithPermission()
                    },
                    onStopBot = {
                        isTransitioning = true
                        app.appScope.launch { app.preferencesManager.setBotEnabled(false) }
                        TelegramBotService.stop(this@MainActivity)
                    },
                    onConfigChanged = {
                        if (TelegramBotService.isRunningState.value) {
                            TelegramBotService.onConfigChanged()
                        }
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // If the bot is enabled but not running (e.g. killed by the system or the ROM),
        // opening the app brings it back. A bot the user stopped stays stopped.
        // Done in onStart (not onCreate) so the activity is visible when the FGS is started.
        if (TelegramBotService.isRunningState.value) return
        val app = application as SmsForwarderApp
        lifecycleScope.launch {
            val prefs = app.preferencesManager
            val shouldStart = prefs.isBotEnabled() && prefs.getBotToken().isNotBlank()
            // The DataStore read is async: only start if the activity is still visible,
            // otherwise startForegroundService() from the background throws on Android 12+.
            if (shouldStart && !TelegramBotService.isRunningState.value &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) {
                try {
                    TelegramBotService.start(this@MainActivity)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun startBotWithPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            when {
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                    enableAndStartBot()
                }
                else -> {
                    pendingStartBot = { enableAndStartBot() }
                    requestNotificationPermission.launch(permission)
                }
            }
        } else {
            enableAndStartBot()
        }
    }

    /** User-initiated start: remember it so the bot comes back after reboot / process death. */
    private fun enableAndStartBot() {
        val app = application as SmsForwarderApp
        app.appScope.launch { app.preferencesManager.setBotEnabled(true) }
        TelegramBotService.start(this)
    }
}