package com.segeluhr.watch.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.segeluhr.watch.data.WatchUiState
import com.segeluhr.watch.ui.theme.Green
import com.segeluhr.watch.ui.theme.Red
import com.segeluhr.watch.ui.theme.TextDim
import com.segeluhr.watch.ui.theme.TextLight

/**
 * Übersichts-Tab, analog zu navScreenUpdate() auf der Ultra: aktueller
 * Modus (BoatState), Manöver-Ampel, Bootsgeschwindigkeit, Ziel-VMC, Lift/
 * Header. Kein Solo-GPS auf dieser Uhr — SOG/COG kommen wie bei der Ultra
 * ausschliesslich vom Handy (CHAR_GPS_UUID).
 *
 * **02.09.2026, Roman-Wunsch:** liegt hier (statt auf einem thematisch
 * passenderen Tab) auch die Tasten-Kontextaktion für Pin/Boot — Nav ist
 * der Standard-/meistgesehene Tab, und Pin/Boot werden erfahrungsgemäss
 * noch kurz vor dem Start final gelegt, wenn Touch (Wasserdicht-Modus)
 * schon nicht mehr geht. Regel siehe SegeluhrWatchViewModel.
 * setStartLineWaypointHere() — Hinweistext hier spiegelt sie 1:1.
 */
@Composable
fun NavScreen(state: WatchUiState) {
    val race = state.race
    val (maneuverText, maneuverColor) = when {
        race != null && race.maneuverNeeded -> (if (race.isTack) "WENDE!" else "HALSE!") to Red
        race != null -> "kein Manöver" to Green
        else -> "kein Manöver" to TextDim
    }
    val gps = state.gps
    val sogText = when {
        gps == null -> "warte auf GPS..."
        gps.validFix -> "Boot: %.1f kn".format(gps.sogKn)
        else -> "Boot: kein Fix"
    }
    val vmcText = state.vmcKn?.let { "Ziel: %.1f kn".format(it) } ?: "Ziel: --"
    val wind = state.wind
    val liftText = when {
        wind?.isLift == true -> "↑ Lift"
        wind?.isLift == false -> "↓ Header"
        else -> ""
    }
    val liftColor = if (wind?.isLift == true) Green else Red

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(state.boatState.label, color = TextLight, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(maneuverText, color = maneuverColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        Text(sogText, color = TextLight, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Text(vmcText, color = TextDim, fontSize = 12.sp)
        if (liftText.isNotEmpty()) {
            Text(liftText, color = liftColor, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (gps != null) {
            Text(
                "±${gps.accuracyM} m, ${gps.fixAgeMs} ms alt",
                color = TextDim, fontSize = 9.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
        val startLineKeyHint = if (state.waypoints?.pinSet != true) "Pin" else "Boot"
        Text("Taste: $startLineKeyHint setzen", color = TextDim, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
