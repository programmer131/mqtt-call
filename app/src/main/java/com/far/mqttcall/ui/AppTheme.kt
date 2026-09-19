package com.far.mqttcall.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF006C4C),
    onPrimary = Color.White,
    secondary = Color(0xFF4F6358),
    background = Color(0xFFF7FBF7),
    surface = Color(0xFFF7FBF7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5DDBA5),
    secondary = Color(0xFFB4CCBE),
)

@Composable
fun MqttCallTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
