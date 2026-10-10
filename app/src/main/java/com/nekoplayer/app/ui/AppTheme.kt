package com.nekoplayer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8A2FF),
    onPrimary = Color(0xFF24154B),
    secondary = Color(0xFF9BC9D9),
    background = Color(0xFF101115),
    surface = Color(0xFF15171C),
    surfaceVariant = Color(0xFF1D1F26),
    onSurface = Color(0xFFE7E5EC),
    onSurfaceVariant = Color(0xFFB4B3BE),
    outlineVariant = Color(0xFF353740),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B45A8),
    onPrimary = Color.White,
    secondary = Color(0xFF246C68),
    background = Color(0xFFF7F5FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECE9F2),
    onSurface = Color(0xFF1A1820),
    onSurfaceVariant = Color(0xFF494650),
)

@Composable
fun NekoTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            content = content,
        )
    }
}
