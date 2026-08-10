package com.segeluhr.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.segeluhr.app.data.model.WindCalibState
import com.segeluhr.app.ui.components.SectionCard
import com.segeluhr.app.ui.components.StatRow
import com.segeluhr.app.ui.components.StatTile
import com.segeluhr.app.ui.theme.Teal
import com.segeluhr.app.ui.theme.TextDim
import com.segeluhr.app.viewmodel.SegeluhrUiState

@Composable
fun WindScreen(
    state: SegeluhrUiState,
    onStartCalib: () -> Unit,
    onAbortCalib: () -> Unit,
    onCalibrationModeChanged: (Boolean) -> Unit,
    onSmartModeChanged: (Boolean) -> Unit,
    onResetBoatCalibration: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Windkalibrierung (Amwind-Methode)") {
            val statusText = when (state.windCalibState) {
                WindCalibState.IDLE -> if (state.windCalibrated) "Kalibriert" else "Nicht kalibriert"
                else -> "Kalibriere: ${state.windCalibState}"
            }
            StatRow("Status", statusText, valueColor = TextDim)
            StatRow("Windrichtung", if (state.windCalibrated) "${state.windDir?.toInt()}°" else "--", valueColor = Teal)

            Spacer(Modifier.height(10.dp))
            if (state.windCalibState == WindCalibState.IDLE) {
                Button(
                    onClick = onStartCalib,
                    colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color(0xFF04201C)),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Amwind-Kalibrierung starten") }
            } else {
                OutlinedButton(onClick = onAbortCalib, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") }
            }

            Text(
                "Ablauf: 1) Ruhigen Amwind-Kurs halten. 2) Bei Signal wenden. 3) Neuen ruhigen Kurs auf dem anderen Bug halten. Wendewinkel muss zwischen 60° und 110° liegen.",
                fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(top = 8.dp),
            )
        }

        SectionCard("Boots-Kalibrierung (Am-Wind-Wendewinkel)") {
            val angleText = if (state.closehauledSampleCount > 0) {
                "${"%.0f".format(state.closehauledAngleDeg)}° (${state.closehauledSampleCount} Kalibrierläufe)"
            } else {
                "${"%.0f".format(state.closehauledAngleDeg)}° (Standardwert, noch nicht kalibriert)"
            }
            StatRow("Wendewinkel", angleText, valueColor = Teal)

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Kalibrierungsmodus")
                    Text(
                        "Jede erfolgreiche Windkalibrierung oben lernt zusätzlich den Wendewinkel.",
                        fontSize = 11.sp, color = TextDim,
                    )
                }
                Switch(checked = state.calibrationModeEnabled, onCheckedChange = onCalibrationModeChanged)
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Smart-Modus")
                    Text(
                        "Passt den Wendewinkel während des normalen Segelns leise weiter an.",
                        fontSize = 11.sp, color = TextDim,
                    )
                }
                Switch(checked = state.smartModeEnabled, onCheckedChange = onSmartModeChanged)
            }

            if (state.closehauledSampleCount > 0) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onResetBoatCalibration, modifier = Modifier.fillMaxWidth()) {
                    Text("Wendewinkel zurücksetzen")
                }
            }
        }

        SectionCard("Windverlauf (Trend)") {
            WindChart(cumulativeValues = state.windLog.map { it.cumulativeDeg })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Netto-Drehung", state.windNet?.let { "%+.0f".format(it) } ?: "--", "°", modifier = Modifier.weight(1f))
                StatTile("Schwankung", state.windRange?.let { "%.0f".format(it) } ?: "--", "°", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WindChart(cumulativeValues: List<Double>) {
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        if (cumulativeValues.size < 2) return@Canvas
        val pad = 20f
        val w = size.width
        val h = size.height
        val minV = cumulativeValues.min()
        val maxV = cumulativeValues.max()
        val range = (maxV - minV).let { if (it < 1.0) 1.0 else it }
        val stepX = (w - 2 * pad) / (cumulativeValues.size - 1)

        drawLine(
            color = Color(0xFF1E3A4F),
            start = Offset(pad, h - pad),
            end = Offset(w - pad, h - pad),
            strokeWidth = 1f,
        )

        val path = androidx.compose.ui.graphics.Path()
        cumulativeValues.forEachIndexed { i, v ->
            val x = pad + i * stepX
            val y = (h - pad - ((v - minV) / range) * (h - 2 * pad)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = Teal,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
            ),
        )
    }
}
