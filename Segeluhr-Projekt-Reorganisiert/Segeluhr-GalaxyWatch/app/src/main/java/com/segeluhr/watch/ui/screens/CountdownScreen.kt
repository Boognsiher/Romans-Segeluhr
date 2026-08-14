package com.segeluhr.watch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Text
import com.segeluhr.watch.data.WatchUiState
import com.segeluhr.watch.ui.theme.Amber
import com.segeluhr.watch.ui.theme.BgDark
import com.segeluhr.watch.ui.theme.Red
import com.segeluhr.watch.ui.theme.TextDim
import com.segeluhr.watch.ui.theme.TextLight
import com.segeluhr.watch.ui.theme.Teal
import com.segeluhr.watch.viewmodel.SegeluhrWatchViewModel

/**
 * CD-Tab, analog zu countdownScreenUpdate() auf der Ultra: grosse Zahl +
 * Fortschrittsring statt lv_arc, gleiche Farbeskalation (türkis -> orange
 * -> rot in den letzten 60/10 Sekunden). Start/Reset/Sync jetzt PLUS direkt
 * hier als Buttons (14.08. Abend, Roman-Feedback) statt nur im Menu-Tab —
 * analog zur Ultra-Nachbesserung vom 12.08. ("Fach-Tab-Aktionen").
 *
 * Buttons als [CompactChip] (Wear-Compose-Komponente extra für schmale
 * Sekundär-Aktionen) statt normalem [androidx.wear.compose.material.Chip]
 * — ein erster Versuch mit drei festbreiten 64dp-Chips passte nicht in die
 * ca. 168dp verfügbare Breite (212dp Displaybreite abzüglich 22dp
 * Seiten-Padding je Seite aus SailScreenScaffold.kt) und schnitt "Sync"
 * rechts ab (Roman-Feedback 14.08. Abend).
 */
@Composable
fun CountdownScreen(state: WatchUiState, viewModel: SegeluhrWatchViewModel) {
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
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = progress, modifier = Modifier.size(92.dp), indicatorColor = color, strokeWidth = 6.dp,
            )
            val text = cd?.let { "%d:%02d".format(it / 60, it % 60) } ?: "--:--"
            Text(text, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Row(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            CdButton("Start", viewModel::startCountdown)
            CdButton("Reset", viewModel::resetCountdown)
            CdButton("Sync", viewModel::syncCountdown)
        }
    }
}

@Composable
private fun CdButton(label: String, onClick: () -> Unit) {
    CompactChip(
        onClick = onClick,
        label = { Text(label, fontSize = 9.sp) },
        colors = ChipDefaults.chipColors(backgroundColor = BgDark, contentColor = TextLight),
    )
}
