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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private data class AccentPair(val light: Color, val dark: Color)

private val AccentOptions = mapOf(
    "cyan" to AccentPair(Color(0xFF386A78), Color(0xFFA0CDE0)),
    "violet" to AccentPair(Color(0xFF6750A4), Color(0xFFB9A3FF)),
    "blue" to AccentPair(Color(0xFF2765A8), Color(0xFF92BDF5)),
    "teal" to AccentPair(Color(0xFF08766F), Color(0xFF72D2C6)),
    "green" to AccentPair(Color(0xFF367748), Color(0xFF91D29C)),
    "amber" to AccentPair(Color(0xFF8A5A00), Color(0xFFFFC66D)),
    "coral" to AccentPair(Color(0xFFB4473E), Color(0xFFFFA99F)),
    "pink" to AccentPair(Color(0xFF9A3B70), Color(0xFFF0A3CB)),
)

private fun appColorScheme(dark: Boolean, palette: String, accent: String) = run {
    val (lightAccent, darkAccent) = AccentOptions[accent] ?: AccentOptions.getValue("cyan")
    val primary = if (dark) darkAccent else lightAccent
    val onPrimary = if (primary.luminance() > 0.55f) Color(0xFF172126) else Color.White
    val background: Color
    val surface: Color
    val surfaceVariant: Color
    val onSurface: Color
    val onSurfaceVariant: Color
    val outlineVariant: Color

    when {
        dark && (palette == "sepia") -> {
            background = Color(0xFF13100E)
            surface = Color(0xFF191512)
            surfaceVariant = Color(0xFF241E19)
            onSurface = Color(0xFFEEE5DC)
            onSurfaceVariant = Color(0xFFB6AAA0)
            outlineVariant = Color(0xFF342C25)
        }
        dark && (palette == "ink") -> {
            background = Color(0xFF050607)
            surface = Color(0xFF0C0E10)
            surfaceVariant = Color(0xFF15191B)
            onSurface = Color(0xFFE2E8EB)
            onSurfaceVariant = Color(0xFFA4ADB2)
            outlineVariant = Color(0xFF252A2D)
        }
        dark -> {
            background = Color(0xFF0C0F10)
            surface = Color(0xFF111415)
            surfaceVariant = Color(0xFF171A1B)
            onSurface = Color(0xFFDEE7EC)
            onSurfaceVariant = Color(0xFFA3ACB1)
            outlineVariant = Color(0xFF232628)
        }
        palette == "sepia" -> {
            background = Color(0xFFF5F0E7)
            surface = Color(0xFFFFFCF5)
            surfaceVariant = Color(0xFFECE4D7)
            onSurface = Color(0xFF302921)
            onSurfaceVariant = Color(0xFF665B50)
            outlineVariant = Color(0xFFD9CFC1)
        }
        palette == "mist" -> {
            background = Color(0xFFF1F5F4)
            surface = Color(0xFFFFFFFF)
            surfaceVariant = Color(0xFFE6EEEC)
            onSurface = Color(0xFF172321)
            onSurfaceVariant = Color(0xFF52615E)
            outlineVariant = Color(0xFFD2DEDB)
        }
        else -> {
            background = Color(0xFFF4F7F8)
            surface = Color(0xFFFFFFFF)
            surfaceVariant = Color(0xFFE8EEF0)
            onSurface = Color(0xFF172126)
            onSurfaceVariant = Color(0xFF4D5B61)
            outlineVariant = Color(0xFFD4DDE0)
        }
    }

    val primaryContainer = primary.copy(alpha = if (dark) 0.24f else 0.14f)
    val onPrimaryContainer = if (dark) onSurface else Color(0xFF153E4B)
    if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = primary,
            onSecondary = onPrimary,
            secondaryContainer = primaryContainer,
            onSecondaryContainer = primary,
            background = background,
            surface = surface,
            surfaceVariant = surfaceVariant,
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
            outline = Color(0xFF87949A),
            outlineVariant = outlineVariant,
            error = Color(0xFFE6A5A5),
            surfaceTint = primary,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = primary,
            onSecondary = onPrimary,
            secondaryContainer = primaryContainer,
            onSecondaryContainer = primary,
            background = background,
            surface = surface,
            surfaceVariant = surfaceVariant,
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
            outline = Color(0xFF66757B),
            outlineVariant = outlineVariant,
            error = Color(0xFF9E4148),
            surfaceTint = primary,
        )
    }
}

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
fun NekoTheme(
    dark: Boolean,
    palette: String = "classic",
    accent: String = "cyan",
    content: @Composable () -> Unit,
) {
    val colors = appColorScheme(dark, palette, accent)
    MaterialTheme(colorScheme = colors, typography = AppTypography) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background,
            contentColor = colors.onSurface,
            content = content,
        )
    }
}

@Composable
fun NekoPlayerTheme(
    dark: Boolean,
    palette: String = "classic",
    accent: String = "cyan",
    content: @Composable () -> Unit,
) {
    val colors = appColorScheme(dark, palette, accent)
    MaterialTheme(colorScheme = colors, typography = AppTypography) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background,
            contentColor = colors.onSurface,
            content = content,
        )
    }
}
