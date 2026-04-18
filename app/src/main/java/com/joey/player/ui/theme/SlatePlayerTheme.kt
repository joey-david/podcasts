package com.joey.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0E1116),
    onPrimary = Color(0xFFF7F9FB),
    secondary = Color(0xFF525A63),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFECEEF1),
    onBackground = Color(0xFF101317),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101317),
    surfaceVariant = Color(0xFFDCE1E6),
    onSurfaceVariant = Color(0xFF4B545D),
    outline = Color(0xFF9CA5AE),
    outlineVariant = Color(0xFFC7CED5),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFF2F6FA),
    onPrimary = Color(0xFF080B0E),
    secondary = Color(0xFF98A3AE),
    onSecondary = Color(0xFF0B0E12),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F7FA),
    surface = Color(0xFF0A0D10),
    onSurface = Color(0xFFF2F6FA),
    surfaceVariant = Color(0xFF171D24),
    onSurfaceVariant = Color(0xFFB0BAC4),
    outline = Color(0xFF47515B),
    outlineVariant = Color(0xFF232B33),
)

@Composable
fun SlatePlayerTheme(
    amoled: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (amoled || isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
