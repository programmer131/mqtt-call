package com.far.mqttcall.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RuggedColors = darkColorScheme(
    primary = Color(0xFFFFC107),
    onPrimary = Color.Black,
    secondary = Color(0xFF00E676),
    onSecondary = Color.Black,
    tertiary = Color(0xFF40C4FF),
    onTertiary = Color.Black,
    background = Color(0xFF0D0F12),
    onBackground = Color.White,
    surface = Color(0xFF1A1D24),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF252932),
    onSurfaceVariant = Color(0xFF9BA4B5),
    outline = Color(0xFF333945),
    error = Color(0xFFFF5252),
    onError = Color.Black,
)

@Composable
fun MqttCallTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RuggedColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
