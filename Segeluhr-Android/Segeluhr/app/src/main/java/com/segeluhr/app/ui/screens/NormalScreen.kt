package com.segeluhr.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.segeluhr.app.core.GeoUtils
import com.segeluhr.app.ui.components.SectionCard
import com.segeluhr.app.ui.components.StatRow
import com.segeluhr.app.ui.components.StatTile
import com.segeluhr.app.ui.theme.Amber
import com.segeluhr.app.ui.theme.Green
import com.segeluhr.app.ui.theme.Panel2Dark
import com.segeluhr.app.ui.theme.Red
import com.segeluhr.app.ui.theme.Teal
import com.segeluhr.app.ui.theme.TextDim
import com.segeluhr.app.viewmodel.SegeluhrUiState

@Composable
fun NormalScreen(state: SegeluhrUiState, onToggleHomeMode: (Boolean) -> Unit, onStopCompetition: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                "SOG", state.gpsFix.sogKn?.let { "%.1f".format(it) } ?: "--", "kn",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                "COG", state.gpsFix.cogDeg?.let { "${it.toInt()}" } ?: "--", "°",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                "Wind (scheinbar)",
                if (state.windCalibrated) "${state.windDir?.toInt() ?: "--"}" else "--", "°",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                "VMG", state.vmg?.let { "%.1f".format(it) } ?: "--", "kn",
                modifier = Modifier.weight(1f),
                valueColor = if ((state.vmg ?: 0.0) >= 0) Green else Amber,
            )
        }

        SectionCard("Heimweg") {
            if (state.home == null) {
                Text(
                    "Kein Heimatpunkt gesetzt — im Setup-Tab unter \"Zuhause/Hafen\" festlegen.",
                    fontSize = 12.sp, color = TextDim,
                )
            } else {
                Button(
                    onClick = { onToggleHomeMode(!state.homeModeActive) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.homeModeActive) Teal else Panel2Dark,
                        contentColor = if (state.homeModeActive) Color(0xFF04201C) else TextDim,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.homeModeActive) "Heimweg-Navigation beenden" else "Nach Hause navigieren") }

                if (state.homeModeActive) {
                    val g = state.homeGuidance
                    Spacer(Modifier.height(4.dp))
                    StatRow("Peilung", GeoUtils.fmtDeg(g?.bearingToHome))
                    StatRow("Distanz", GeoUtils.fmtDist(g?.distanceM))
                    StatRow("Empfohlener Kurs", GeoUtils.fmtDeg(g?.recommendedHeading))
                    StatRow(
                        "Manöver",
                        if (g?.maneuverNeeded == true) "Wende empfohlen" else "Kurs halten",
                        valueColor = if (g?.maneuverNeeded == true) Amber else Green,
                    )
                    StatRow("ETA", GeoUtils.fmtDuration(g?.etaSeconds), valueColor = if (g?.etaSeconds == null) TextDim else Green)
                }
            }
        }

        SectionCard("Wettfahrt (Competition)") {
            if (!state.competitionActive) {
                Text(
                    "Startet automatisch, sobald der Countdown im Start-Tab 0:00 erreicht.",
                    fontSize = 12.sp, color = TextDim,
                )
            } else {
                val g = state.competitionGuidance
                StatRow("Etappe", g?.label ?: "--", valueColor = TextDim)
                StatRow("Peilung", GeoUtils.fmtDeg(g?.bearing))
                StatRow("Distanz", GeoUtils.fmtDist(g?.distanceM))
                StatRow("Empfohlener Kurs", GeoUtils.fmtDeg(g?.recommendedHeading))
                if (g?.maneuverNeeded == true) {
                    StatRow("Manöver", "Wende empfohlen", valueColor = Amber)
                }
                StatRow("Runde", "${g?.lapCount ?: 0}", valueColor = TextDim)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onStopCompetition, modifier = Modifier.fillMaxWidth()) {
                    Text("Wettfahrt beenden")
                }
            }
        }

        SectionCard("Startlinie") {
            val biasText = state.lineBiasDeg?.let { bias ->
                val sign = if (bias > 0) "+" else ""
                "$sign${"%.0f".format(bias)}° (${state.lineBiasFavors} bevorzugt)"
            } ?: "--"
            StatRow("Line-Bias", biasText, valueColor = TextDim)
        }

        SectionCard("Ziel") {
            StatRow("Peilung", GeoUtils.fmtDeg(state.targetBearing))
            StatRow("Distanz", GeoUtils.fmtDist(state.targetDistanceM))
        }

        SectionCard("Racemode-Boje") {
            StatRow("Aktives Ziel", state.activeBuoyLabel ?: "--", valueColor = TextDim)
            StatRow("Peilung", GeoUtils.fmtDeg(state.buoyBearing))
            StatRow("Distanz", GeoUtils.fmtDist(state.buoyDistanceM))
        }

        SectionCard("See-Rand") {
            val pct = state.lakeDistancePct
            val color = when {
                pct == null -> TextDim
                pct >= 80 -> Red
                pct >= 65 -> Amber
                else -> TextDim
            }
            val text = if (state.lakeDistanceM != null && pct != null) {
                "${GeoUtils.fmtDist(state.lakeDistanceM)} (${"%.0f".format(pct)}%)"
            } else "--"
            StatRow("Abstand", text, valueColor = color)
        }

        Spacer(Modifier.height(8.dp))
    }
}
