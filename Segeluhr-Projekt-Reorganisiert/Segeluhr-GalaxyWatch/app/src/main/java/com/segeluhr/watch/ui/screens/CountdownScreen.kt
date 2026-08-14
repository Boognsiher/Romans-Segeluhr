package com.segeluhr.watch.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import com.segeluhr.watch.data.WatchUiState
import com.segeluhr.watch.ui.theme.Amber
import com.segeluhr.watch.ui.theme.Red
import com.segeluhr.watch.ui.theme.TextDim
import com.segeluhr.watch.ui.theme.Teal

/**
 * CD-Tab, analog zu countdownScreenUpdate() auf der Ultra: grosse Zahl +
 * Fortschrittsring statt lv_arc, gleiche Farbeskalation (türkis -> orange
 * -> rot in den letzten 60/10 Sekunden).
 */
@Composable
fun CountdownScreen(state: WatchUiState) {
    val cd = state.race?.countdownSeconds
    val color = when {
        cd == null -> TextDim
        cd <= 10 -> Red
        cd <= 60 -> Amber
        else -> Teal
    }
    val progress = when {
        cd == null -> 0f
        cd > 60 -> 1f
        else -> cd / 60f
    }
    Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = progress, modifier = Modifier.size(110.dp), indicatorColor = color, strokeWidth = 6.dp,
        )
        val text = cd?.let { "%d:%02d".format(it / 60, it % 60) } ?: "--:--"
        Text(text, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}
