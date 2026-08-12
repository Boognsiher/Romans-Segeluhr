package com.segeluhr.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.segeluhr.app.core.GeoPoint
import com.segeluhr.app.data.model.TrainMode
import com.segeluhr.app.ui.components.SectionCard
import com.segeluhr.app.ui.components.WaypointRow
import com.segeluhr.app.ui.theme.Panel2Dark
import com.segeluhr.app.ui.theme.Teal
import com.segeluhr.app.ui.theme.TextDim
import com.segeluhr.app.ui.theme.TextLight
import com.segeluhr.app.viewmodel.SegeluhrUiState

@Composable
fun TrainingScreen(
    state: SegeluhrUiState,
    onSetMode: (TrainMode) -> Unit,
    onSetWaypoint: (String) -> Unit,
    onClearWaypoint: (String) -> Unit,
    onAutoDetectLake: () -> Unit,
    onRemoveLakeCircle: (Int) -> Unit,
    onStartLakeDrawing: () -> Unit,
    // NEU (12.08.2026, siehe docs/Erweiterung_Boje_Kartenauswahl.md)
    onSetWaypointFromMap: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
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
            WaypointRow("Boje 1", fmt(state.buoy1), { onSetWaypoint("buoy1") }, { onClearWaypoint("buoy1") }, { onSetWaypointFromMap("buoy1") })
            WaypointRow("Boje 2", fmt(state.buoy2), { onSetWaypoint("buoy2") }, { onClearWaypoint("buoy2") }, { onSetWaypointFromMap("buoy2") })
        }

        SectionCard("See-Geofence") {
            Text(
                "Kette von Sicherheits-Kreisen statt einer einzelnen Kreisfläche — " +
                    "deckt auch lange/unregelmässige Seen ab. Sicher ist, wer sich " +
                    "innerhalb IRGENDEINES Kreises befindet.",
                fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(bottom = 8.dp),
            )
            Button(
                onClick = onAutoDetectLake,
                enabled = !state.lakeDetectionInProgress,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color(0xFF04201C)),
            ) {
                Text(if (state.lakeDetectionInProgress) "Erkenne See…" else "See automatisch erkennen")
            }
            Spacer(Modifier.height(8.dp))
            // Fallback für Seen, die als OSM-Relation statt einfachem "way"
            // erfasst sind (automatische Erkennung findet die nicht, siehe
            // docs/Erweiterung_Seegrenze_Zeichnen.md, z.B. Zürichsee mit
            // Ufenau/Lützelau als Inseln).
            androidx.compose.material3.OutlinedButton(
                onClick = onStartLakeDrawing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("See-Grenze auf Karte einzeichnen")
            }
            Text(
                "Falls die automatische Erkennung fehlschlägt (z.B. bei grossen Seen mit Inseln) — " +
                    "auf einer Karte die Uferpunkte antippen, statt physisch am See entlangzufahren.",
                fontSize = 11.sp, color = TextDim, modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            if (state.lakeCircles.isEmpty()) {
                Text("Noch kein See-Geofence gesetzt.", fontSize = 12.sp, color = TextDim)
            } else {
                state.lakeCircles.forEachIndexed { index, circle ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("Kreis ${index + 1}: ${circle.radiusM.toInt()} m", fontSize = 13.sp, color = TextLight)
                            Text(
                                "%.5f, %.5f".format(circle.center.lat, circle.center.lon),
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextDim,
                            )
                        }
                        IconButton(onClick = { onRemoveLakeCircle(index) }) { Text("×", color = TextDim, fontSize = 18.sp) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            WaypointRow(
                "Kreis hinzufügen",
                "aktuelle Position wird Mittelpunkt",
                { onSetWaypoint("lakeCircle") },
                { onClearWaypoint("lakeCircles") }, // "×" hier: ganze Kette zurücksetzen
            )
            WaypointRow(
                "Rand erfassen (mehrfach möglich)",
                "misst gegen den ZULETZT hinzugefügten Kreis",
                { onSetWaypoint("lakeEdge") },
                { onClearWaypoint("lakeCircles") },
            )
        }
    }
}

private fun fmt(p: GeoPoint?): String = if (p == null) "nicht gesetzt" else "%.5f, %.5f".format(p.lat, p.lon)
