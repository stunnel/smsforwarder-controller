package com.example.smsforwarder.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Telegram-inspired blue palette
private val TelegramBlue = Color(0xFF2AABEE)
private val TelegramDarkBlue = Color(0xFF1A8AC4)
private val TelegramGreen = Color(0xFF4CAF50)

private val LightColorScheme = lightColorScheme(
    primary = TelegramBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0EEFF),
    onPrimaryContainer = Color(0xFF003A5C),
    secondary = TelegramGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8E6C9),
    onSecondaryContainer = Color(0xFF1B5E20),
    tertiary = Color(0xFFF57C00),
    onTertiary = Color.White,
    error = Color(0xFFD32F2F),
    background = Color(0xFFF5F7FA),
    surface = Color.White,
    onBackground = Color(0xFF1A1A2E),
    onSurface = Color(0xFF1A1A2E),
    surfaceVariant = Color(0xFFEEF2F7),
    onSurfaceVariant = Color(0xFF5A6478),
)

private val DarkColorScheme = darkColorScheme(
    primary = TelegramBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1A5A7A),
    onPrimaryContainer = Color(0xFFD0EEFF),
    secondary = Color(0xFF81C784),
    onSecondary = Color(0xFF1B5E20),
    secondaryContainer = Color(0xFF2E7D32),
    onSecondaryContainer = Color(0xFFC8E6C9),
    tertiary = Color(0xFFFFB74D),
    onTertiary = Color(0xFFE65100),
    error = Color(0xFFEF5350),
    background = Color(0xFF0F1923),
    surface = Color(0xFF17212B),
    onBackground = Color(0xFFE8EDF2),
    onSurface = Color(0xFFE8EDF2),
    surfaceVariant = Color(0xFF1E2C3A),
    onSurfaceVariant = Color(0xFF8A9BB0),
)

@Composable
fun SmsForwarderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
