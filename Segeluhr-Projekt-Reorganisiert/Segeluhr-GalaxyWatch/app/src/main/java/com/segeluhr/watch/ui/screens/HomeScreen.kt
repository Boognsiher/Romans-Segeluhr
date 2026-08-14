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
import com.segeluhr.watch.ui.theme.Green
import com.segeluhr.watch.ui.theme.TextDim
import com.segeluhr.watch.ui.theme.TextLight

/** Heim-Tab, analog zu homeScreenUpdate() auf der Ultra: Heimweg-Status, Wende-Empfehlung, ETA. */
@Composable
fun HomeScreen(state: WatchUiState) {
    val home = state.home
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (home == null) {
            Text("warte auf\nHeimweg-Status...", color = TextDim, fontSize = 13.sp, textAlign = TextAlign.Center)
            return@Column
        }
        Text(
            if (home.active) "Heimweg: AKTIV" else "Heimweg: inaktiv",
            color = if (home.active) Green else TextDim, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            if (home.maneuverNeeded) "Wende empfohlen: JA" else "Wende empfohlen: nein",
            color = TextLight, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            home.etaMinutes?.let { "ETA: $it min" } ?: "ETA: unbekannt",
            color = TextLight, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
        )
        home.vmcKn?.let {
            Text("VMC: %.1f kn".format(it), color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Text(
            "bisher %.1f km".format(home.distanceTraveledM / 1000.0),
            color = TextDim, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp),
        )
    }
}
