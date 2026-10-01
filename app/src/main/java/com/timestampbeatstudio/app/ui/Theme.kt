package com.timestampbeatstudio.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF082F49),
    secondary = Color(0xFFA5B4FC),
    tertiary = Color(0xFF6EE7B7),
    background = Color(0xFF0B0F14),
    surface = Color(0xFF11161D),
    surfaceVariant = Color(0xFF1A2230),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
    onSurfaceVariant = Color(0xFF9AA7B8),
    error = Color(0xFFFF8A80),
    outline = Color(0xFF2A3444)
)

/** App theme: Material3 dark-friendly, simple and professional. */
@Composable
fun TimestampBeatStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content
    )
}
