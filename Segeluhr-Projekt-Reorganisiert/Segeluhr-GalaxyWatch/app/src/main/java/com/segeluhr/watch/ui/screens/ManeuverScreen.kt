package com.segeluhr.watch.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.segeluhr.watch.data.WatchUiState
import com.segeluhr.watch.ui.theme.Amber
import com.segeluhr.watch.ui.theme.TextDim

/**
 * Man-Tab, analog zu maneuverScreenUpdate() auf der Ultra. Die Bojen-
 * Rundungs-Rückfrage ("Boje hier?") wird NICHT hier, sondern als
 * bildschirmfüllendes Overlay über allen Tabs gezeigt (siehe
 * ui/SegelnApp.kt/RoundingConfirmBanner) — auf der Ultra ersetzt sie
 * stattdessen nur diesen einen Tab-Inhalt und die Uhr wechselt per
 * Auto-Focus automatisch dorthin; hier bewusst einfacher gelöst
 * (kein Auto-Focus-Mechanismus nachgebaut), siehe
 * docs/Erweiterung_GalaxyWatch_App.md.
 */
@Composable
fun ManeuverScreen(state: WatchUiState) {
    val race = state.race
    val needed = race != null && race.maneuverNeeded
    val bigText = if (needed) (if (race!!.isTack) "WENDE!" else "HALSE!") else "kein Manöver\nempfohlen"
    val legNames = listOf("Luv-Kurs", "Halbwind zur Boje", "Vorwind-Kurs")
    val legText = race?.competitionLeg?.let { legNames.getOrNull(it) } ?: ""

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            bigText, color = if (needed) Amber else TextDim, fontSize = 18.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        if (legText.isNotEmpty()) {
            Text(legText, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
