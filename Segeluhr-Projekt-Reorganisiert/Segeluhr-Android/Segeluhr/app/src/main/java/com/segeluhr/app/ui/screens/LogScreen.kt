package com.segeluhr.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.segeluhr.app.ui.components.SectionCard
import com.segeluhr.app.ui.components.StatTile
import com.segeluhr.app.ui.theme.Red
import com.segeluhr.app.ui.theme.TextDim
import com.segeluhr.app.viewmodel.SegeluhrUiState

@Composable
fun LogScreen(state: SegeluhrUiState, onClearLog: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Durchschnitt") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Ø Wende-Score", state.avgTackScore?.let { "%.0f".format(it) } ?: "--", modifier = Modifier.weight(1f))
                StatTile("Ø Halse-Score", state.avgJibeScore?.let { "%.0f".format(it) } ?: "--", modifier = Modifier.weight(1f))
            }
        }

        SectionCard("Letzte Manöver", modifier = Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                HeaderCell("Typ"); HeaderCell("Dauer"); HeaderCell("V-Verlust"); HeaderCell("Score")
            }
            LazyColumn(Modifier.weight(1f)) {
                items(state.maneuverLog) { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Cell(if (m.isTack) "Wende" else "Halse")
                        Cell("%.1fs".format(m.durationS))
                        Cell("%.0f%%".format(m.speedLossPct))
                        Cell("%.0f".format(m.score))
                    }
                }
            }
            OutlinedButton(
                onClick = onClearLog,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text("Log löschen") }
        }
    }
}

@Composable
private fun RowScope.HeaderCell(text: String) {
    Text(text.uppercase(), fontSize = 10.sp, color = TextDim, modifier = Modifier.weight(1f))
}

@Composable
private fun RowScope.Cell(text: String) {
    Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
}
