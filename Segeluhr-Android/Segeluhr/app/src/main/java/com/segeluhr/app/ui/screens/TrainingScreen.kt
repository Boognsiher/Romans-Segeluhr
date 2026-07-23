package com.segeluhr.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.segeluhr.app.core.GeoPoint
import com.segeluhr.app.data.model.TrainMode
import com.segeluhr.app.ui.components.SectionCard
import com.segeluhr.app.ui.components.WaypointRow
import com.segeluhr.app.ui.theme.Panel2Dark
import com.segeluhr.app.ui.theme.Teal
import com.segeluhr.app.ui.theme.TextDim
import com.segeluhr.app.viewmodel.SegeluhrUiState

@Composable
fun TrainingScreen(
    state: SegeluhrUiState,
    onSetMode: (TrainMode) -> Unit,
    onSetWaypoint: (String) -> Unit,
    onClearWaypoint: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Trainingsmodus") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                val modes = listOf(
                    TrainMode.OFF to "Aus",
                    TrainMode.TACK_ONLY to "Wenden",
                    TrainMode.JIBE_ONLY to "Halsen",
                    TrainMode.RACE to "Race",
                )
                modes.forEach { (mode, label) ->
                    val active = state.trainMode == mode
                    Button(
                        onClick = { onSetMode(mode) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (active) Teal else Panel2Dark,
                            contentColor = if (active) Color(0xFF04201C) else TextDim,
                        ),
                        contentPadding = PaddingValues(4.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text(label, fontSize = 11.sp) }
                }
            }
            Text(
                "\"Race\" ist der Trainingsmodus (zufällige Wende/Halse-Kommandos + Bojen-Navigation zum Üben). Für ein echtes Rennen: siehe \"Wettfahrt\" auf dem Normal-Tab, die startet automatisch beim Startsignal.",
                fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(top = 6.dp),
            )
            if (state.trainRequirementWarning.isNotEmpty()) {
                Text(state.trainRequirementWarning, fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(top = 6.dp))
            }
        }

        SectionCard("Boje setzen (Racemode)") {
            WaypointRow("Boje 1", fmt(state.buoy1), { onSetWaypoint("buoy1") }, { onClearWaypoint("buoy1") })
            WaypointRow("Boje 2", fmt(state.buoy2), { onSetWaypoint("buoy2") }, { onClearWaypoint("buoy2") })
        }

        SectionCard("See-Geofence") {
            WaypointRow("Mitte", fmt(state.lakeCenter), { onSetWaypoint("lakeCenter") }, { onClearWaypoint("lakeCenter") })
            WaypointRow(
                "Rand (mehrfach möglich)",
                "Radius: ${state.lakeRadius?.let { "${it.toInt()} m" } ?: "--"}",
                { onSetWaypoint("lakeEdge") },
                { onClearWaypoint("lakeRadius") },
            )
        }
    }
}

private fun fmt(p: GeoPoint?): String = if (p == null) "nicht gesetzt" else "%.5f, %.5f".format(p.lat, p.lon)
