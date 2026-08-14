package com.segeluhr.watch.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

// Kontrastfarbe für Text auf Teal/Amber-Flächen (analog Theme.kt am Handy: onPrimary = 0xFF04201C)
val OnAccent = Color(0xFF04201C)

private val SegeluhrWatchColors = Colors(
    primary = Teal,
    primaryVariant = Teal,
    secondary = Amber,
    secondaryVariant = Amber,
    background = BgDark,
    surface = PanelDark,
    error = Red,
    onPrimary = OnAccent,
    onSecondary = OnAccent,
    onBackground = TextLight,
    onSurface = TextLight,
    onSurfaceVariant = TextDim,
    onError = TextLight,
)

@Composable
fun SegeluhrWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = SegeluhrWatchColors, content = content)
}
