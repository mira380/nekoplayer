package com.nekoplayer.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val AppDarkColors = darkColorScheme(
    primary = Color(0xFFA0CDE0),
    onPrimary = Color(0xFF153E4B),
    primaryContainer = Color(0xFF293E47),
    onPrimaryContainer = Color(0xFFDEE7EC),
    secondary = Color(0xFFA0CDE0),
    onSecondary = Color(0xFF153E4B),
    secondaryContainer = Color(0xFF293E47),
    onSecondaryContainer = Color(0xFFA0CDE0),
    background = Color(0xFF0C0F10),
    surface = Color(0xFF111415),
    surfaceVariant = Color(0xFF171A1B),
    onSurface = Color(0xFFDEE7EC),
    onSurfaceVariant = Color(0xFFA3ACB1),
    outline = Color(0xFF6E777B),
    outlineVariant = Color(0xFF232628),
    error = Color(0xFFE6A5A5),
    surfaceTint = Color(0xFFA0CDE0),
)

private val AppLightColors = lightColorScheme(
    primary = Color(0xFF386A78),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E8EE),
    onPrimaryContainer = Color(0xFF153E4B),
    secondary = Color(0xFF386A78),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E8EE),
    onSecondaryContainer = Color(0xFF153E4B),
    background = Color(0xFFF4F7F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8EEF0),
    onSurface = Color(0xFF172126),
    onSurfaceVariant = Color(0xFF4D5B61),
    outline = Color(0xFF6E777B),
    outlineVariant = Color(0xFFD4DDE0),
    error = Color(0xFF9E4148),
    surfaceTint = Color(0xFF386A78),
)

private val AppTypography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun NekoTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) AppDarkColors else AppLightColors,
        typography = AppTypography,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            content = content,
        )
    }
}

@Composable
fun NekoPlayerTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) AppDarkColors else AppLightColors
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background,
            contentColor = colors.onSurface,
            content = content,
        )
    }
}
