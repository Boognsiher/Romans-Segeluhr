package com.segeluhr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SegeluhrColorScheme = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF04201C),
    secondary = Amber,
    background = BgDark,
    onBackground = TextLight,
    surface = PanelDark,
    onSurface = TextLight,
    surfaceVariant = Panel2Dark,
    onSurfaceVariant = TextDim,
    outline = LineDark,
    error = Red,
)

@Composable
fun SegeluhrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SegeluhrColorScheme,
        typography = SegeluhrTypography,
        content = content,
    )
}
