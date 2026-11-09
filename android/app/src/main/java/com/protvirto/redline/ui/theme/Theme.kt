package com.protvirto.redline.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

object Neon {
    val Black = Color(0xFF050505)
    val Panel = Color(0xFF0C0708)
    val PanelHi = Color(0xFF180A0E)
    val Red = Color(0xFFFF1E3C)
    val RedHot = Color(0xFFFF5A6E)
    val RedDim = Color(0xFF6E0D1B)
    val Blood = Color(0xFF2A060C)
    val Text = Color(0xFFF4E8EA)
    val Muted = Color(0xFF8C787C)
}

val Mono = FontFamily.Monospace

private val base = Typography()
private val RedlineTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Mono),
    displayMedium = base.displayMedium.copy(fontFamily = Mono),
    displaySmall = base.displaySmall.copy(fontFamily = Mono),
    headlineLarge = base.headlineLarge.copy(fontFamily = Mono),
    headlineMedium = base.headlineMedium.copy(fontFamily = Mono),
    headlineSmall = base.headlineSmall.copy(fontFamily = Mono),
    titleLarge = base.titleLarge.copy(fontFamily = Mono),
    titleMedium = base.titleMedium.copy(fontFamily = Mono),
    titleSmall = base.titleSmall.copy(fontFamily = Mono),
    bodyLarge = base.bodyLarge.copy(fontFamily = Mono),
    bodyMedium = base.bodyMedium.copy(fontFamily = Mono),
    bodySmall = base.bodySmall.copy(fontFamily = Mono),
    labelLarge = base.labelLarge.copy(fontFamily = Mono),
    labelMedium = base.labelMedium.copy(fontFamily = Mono),
    labelSmall = base.labelSmall.copy(fontFamily = Mono),
)

@Composable
fun RedlineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Neon.Red,
            onPrimary = Neon.Black,
            secondary = Neon.RedHot,
            onSecondary = Neon.Black,
            background = Neon.Black,
            onBackground = Neon.Text,
            surface = Neon.Panel,
            onSurface = Neon.Text,
            surfaceVariant = Neon.PanelHi,
            onSurfaceVariant = Neon.Muted,
            outline = Neon.RedDim,
            error = Neon.RedHot,
        ),
        typography = RedlineTypography,
        content = content,
    )
}
