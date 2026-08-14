package com.segeluhr.watch.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.segeluhr.watch.ble.ConnectionState
import com.segeluhr.watch.ui.theme.Green
import com.segeluhr.watch.ui.theme.Red
import com.segeluhr.watch.ui.theme.TextDim
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kompakte Statuszeile (BLE-Status, Handy-/Uhr-Akku, Uhrzeit) — analog zu
 * statusBarUpdate() auf der Ultra. Anders als dort bewusst auf JEDEM Tab
 * sichtbar statt nur auf Nav/Uhr (auf der Ultra ein Roman-Wunsch wegen des
 * vom Gehäuse verdeckten Rands, siehe PROJEKT_STATUS.md 12.08.2026 —
 * betrifft die Galaxy Watch nicht, deren Display nicht verdeckt ist).
 */
@Composable
fun StatusBar(connectionState: ConnectionState, phoneBatteryPct: Int?, ownBatteryPct: Int?) {
    var now by remember { mutableStateOf(Date()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000L)
        }
    }
    val timeText = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(now) }
    val bleText = when (connectionState) {
        ConnectionState.CONNECTED -> "BLE OK"
        ConnectionState.CONNECTING -> "BLE ..."
        ConnectionState.SCANNING -> "BLE such"
        ConnectionState.DISCONNECTED -> "BLE aus"
    }
    val bleColor = if (connectionState == ConnectionState.CONNECTED) Green else Red

    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, start = 20.dp, end = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(bleText, color = bleColor, fontSize = 9.sp)
        Text(timeText, color = TextDim, fontSize = 9.sp, textAlign = TextAlign.Center)
        val battText = "H ${phoneBatteryPct?.let { "$it%" } ?: "--"} U ${ownBatteryPct?.let { "$it%" } ?: "--"}"
        Text(battText, color = TextDim, fontSize = 9.sp)
    }
}
