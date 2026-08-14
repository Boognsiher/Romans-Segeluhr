package com.segeluhr.watch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import com.segeluhr.watch.ble.BleProtocol
import com.segeluhr.watch.ui.theme.Amber
import com.segeluhr.watch.ui.theme.BgDark
import com.segeluhr.watch.ui.theme.Green
import com.segeluhr.watch.ui.theme.Red
import com.segeluhr.watch.ui.theme.TextDim
import com.segeluhr.watch.ui.theme.TextLight
import com.segeluhr.watch.viewmodel.SegeluhrWatchViewModel

private data class WaypointEntry(val label: String, val id: Int, val isSet: (BleProtocol.WaypointsStatus) -> Boolean)

private val WAYPOINT_ENTRIES = listOf(
    WaypointEntry("Boje 1", BleProtocol.WaypointId.BUOY1) { it.buoy1Set },
    WaypointEntry("Boje 2", BleProtocol.WaypointId.BUOY2) { it.buoy2Set },
    WaypointEntry("Ziel", BleProtocol.WaypointId.TARGET) { it.targetSet },
    WaypointEntry("Home", BleProtocol.WaypointId.HOME) { it.homeSet },
    WaypointEntry("Marke 1", BleProtocol.WaypointId.COMPETITION_MARK1) { it.mark1Set },
    WaypointEntry("Marke 2", BleProtocol.WaypointId.COMPETITION_MARK2) { it.mark2Set },
    WaypointEntry("Pin", BleProtocol.WaypointId.PIN) { it.pinSet },
    WaypointEntry("Boot-Ende", BleProtocol.WaypointId.BOAT) { it.boatSet },
)

/**
 * Menu-Tab, analog zu buildMenuTab() auf der Ultra: alle CMD_*-Aktionen.
 * Wegpunkte nur "an der aktuellen Boots-Position" setzbar, wie auf der
 * Ultra — der ursprünglich geplante Kartenpicker direkt auf der Uhr wurde
 * 14.08. Abend gestrichen (Roman-Feedback: Display zu klein dafür).
 *
 * Nimmt bewusst NICHT das komplette [WatchUiState] entgegen, sondern nur
 * die zwei Felder, die diese Liste tatsächlich braucht (waypoints,
 * homeActive) — 14.08. Abend, Roman-Feedback "Scrollen ruckelt": bei
 * Übergabe des gesamten Zustands baute Compose die komplette
 * ScalingLazyColumn bei JEDEM GPS/Wind-Tick vom Handy (1 Hz) neu auf, auch
 * mitten im Scroll-Gesture. Mit den zwei einzelnen, strukturell
 * vergleichbaren Werten überspringt Compose die Neuzusammenstellung, wenn
 * sich nur GPS/Wind geändert haben.
 */
@Composable
fun MenuScreen(waypoints: BleProtocol.WaypointsStatus?, homeActive: Boolean, viewModel: SegeluhrWatchViewModel) {
    var confirmingStop by remember { mutableStateOf(false) }

    if (confirmingStop) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Wettfahrt stoppen?", color = Amber, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { confirmingStop = false }, colors = ButtonDefaults.secondaryButtonColors()) {
                    Text("Nein", fontSize = 12.sp)
                }
                Button(
                    onClick = { viewModel.endCompetition(); confirmingStop = false },
                    colors = ButtonDefaults.primaryButtonColors(),
                ) { Text("Ja, stoppen", fontSize = 12.sp) }
            }
        }
        return
    }

    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Countdown") }
        item { ActionChip("Start") { viewModel.startCountdown() } }
        item { ActionChip("Reset") { viewModel.resetCountdown() } }
        item { ActionChip("Sync nächste Minute") { viewModel.syncCountdown() } }

        item { SectionHeader("Wind") }
        item { ActionChip("Kalibrieren starten") { viewModel.startWindCalibration() } }
        item { ActionChip("Kalibrieren abbrechen") { viewModel.abortWindCalibration() } }

        item { SectionHeader("Training") }
        item { ActionChip("Aus") { viewModel.setTrainModeOff() } }
        item { ActionChip("Nur Wende") { viewModel.setTrainModeTackOnly() } }
        item { ActionChip("Nur Halse") { viewModel.setTrainModeJibeOnly() } }
        item { ActionChip("Race") { viewModel.setTrainModeRace() } }

        item { SectionHeader("Heimweg") }
        item { ActionChip(if (homeActive) "Heimweg beenden" else "Heimweg starten") { viewModel.toggleHomeMode() } }

        item { SectionHeader("Wettfahrt") }
        item { ActionChip("Beenden") { confirmingStop = true } }

        item { SectionHeader("Wegpunkte") }
        items(WAYPOINT_ENTRIES) { entry ->
            WaypointRow(
                entry = entry,
                isSet = waypoints?.let(entry.isSet) ?: false,
                onSetHere = { viewModel.setWaypointHere(entry.id) },
                onClear = { viewModel.clearWaypoint(entry.id) },
            )
        }

        item { SectionHeader("Log") }
        item { ActionChip("Manöver-Log löschen") { viewModel.clearLog() } }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, color = TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        colors = ChipDefaults.chipColors(backgroundColor = BgDark, contentColor = TextLight),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Ein Wegpunkt: Name+Status-Punkt, plus zwei kompakte Aktionen (Hier/Löschen). */
@Composable
private fun WaypointRow(entry: WaypointEntry, isSet: Boolean, onSetHere: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            "${entry.label}${if (isSet) " ●" else ""}",
            color = if (isSet) Green else TextLight, fontSize = 11.sp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallChip("Hier", Modifier.weight(1f), onSetHere)
            SmallChip("Löschen", Modifier.weight(1f), onClear, danger = true)
        }
    }
}

@Composable
private fun SmallChip(label: String, modifier: Modifier, onClick: () -> Unit, danger: Boolean = false) {
    Chip(
        onClick = onClick,
        label = { Text(label, fontSize = 9.sp) },
        colors = ChipDefaults.chipColors(backgroundColor = BgDark, contentColor = if (danger) Red else TextDim),
        modifier = modifier,
    )
}
