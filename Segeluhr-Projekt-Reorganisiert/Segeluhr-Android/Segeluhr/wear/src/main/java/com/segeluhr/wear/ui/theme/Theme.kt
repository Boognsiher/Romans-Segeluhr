package com.segeluhr.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

// Gleiche nautische Palette wie die Handy-App (Theme.Segeluhr), siehe
// app/src/main/res/values/themes.xml - dunkler Hintergrund, türkiser Akzent.
private val SegeluhrWearColors = Colors(
    primary = Color(0xFF2DD4BF),
    primaryVariant = Color(0xFF2DD4BF),
    background = Color(0xFF0A1420),
    surface = Color(0xFF0A1420),
    onPrimary = Color(0xFF0A1420),
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun SegeluhrWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = SegeluhrWearColors, content = content)
}
