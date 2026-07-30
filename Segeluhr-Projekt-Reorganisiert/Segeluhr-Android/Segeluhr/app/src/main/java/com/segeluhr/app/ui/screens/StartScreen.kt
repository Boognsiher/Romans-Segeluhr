package com.segeluhr.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.segeluhr.app.data.model.RaceState
import com.segeluhr.app.ui.components.SectionCard
import com.segeluhr.app.ui.theme.Red
import com.segeluhr.app.ui.theme.Teal
import com.segeluhr.app.ui.theme.TextDim
import com.segeluhr.app.viewmodel.SegeluhrUiState

@Composable
fun StartScreen(
    state: SegeluhrUiState,
    onStart: () -> Unit,
    onReset: () -> Unit,
    onSyncToNextMinute: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        SectionCard("Start-Countdown") {
            val seconds = state.countdownSeconds
            val display = when {
                seconds == null -> "5:00"
                state.isRaceTimerRunning -> {
                    val m = seconds / 60; val s = seconds % 60
                    "+$m:${s.toString().padStart(2, '0')}"
                }
                else -> {
                    val m = seconds / 60; val s = seconds % 60
                    "$m:${s.toString().padStart(2, '0')}"
                }
            }
            val late = seconds != null && !state.isRaceTimerRunning && seconds <= 60

            Text(
                display,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 64.sp,
                color = if (late) Red else Teal,
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onStart,
                    enabled = state.raceState == RaceState.MENU,
                    colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color(0xFF04201C)),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.raceState == RaceState.MENU) "Start (5:00)" else "Läuft…")
                }
                OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("Reset") }
            }

            OutlinedButton(
                onClick = onSyncToNextMinute,
                enabled = state.raceState == RaceState.COUNTDOWN,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("⏱ Auf nächste Minute synchronisieren") }

            Text(
                "Signal bei 4:00 und 1:00 (kurz), starkes Signal bei 0:00 (Start). Danach läuft die Zeit als Race-Timer weiter.",
                fontSize = 12.sp,
                color = TextDim,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Sync-Knopf: rundet die Restzeit beim Drücken auf die nächst tiefere volle Minute ab — zum Angleichen an ein gehörtes Startsignal des Wettfahrtausschusses.",
                fontSize = 12.sp,
                color = TextDim,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
