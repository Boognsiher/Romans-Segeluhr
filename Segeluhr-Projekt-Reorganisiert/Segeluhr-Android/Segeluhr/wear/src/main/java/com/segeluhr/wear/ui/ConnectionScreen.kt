package com.segeluhr.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.segeluhr.wear.ble.ConnectionState
import com.segeluhr.wear.ble.WearBleProtocol
import kotlin.math.roundToInt

/**
 * Erster Test-Screen, siehe docs/Erweiterung_GalaxyWatch_WearOS.md - zeigt
 * nur Verbindungsstatus + GPS/Wind/Batterie-Rohwerte, noch keine echten
 * Segeluhr-Screens (Countdown, Manöver, Haptik-Trigger folgen erst danach).
 */
@Composable
fun ConnectionScreen(
    state: ConnectionState,
    gps: WearBleProtocol.GpsData?,
    batteryPercent: Int?,
    wind: WearBleProtocol.WindData?,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Text(text = "Segeluhr", style = MaterialTheme.typography.title3)
            Text(
                text = statusText(state),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.caption1,
            )

            if (state is ConnectionState.Connected) {
                gps?.let {
                    Text(
                        text = "SOG ${"%.1f".format(it.sogKn)} kn · COG ${it.cogDeg?.roundToInt() ?: "--"}°",
                        style = MaterialTheme.typography.body2,
                    )
                }
                wind?.let {
                    val windText = if (it.calibrated) "${it.windDirDeg?.roundToInt()}°" else "unkalibriert"
                    Text(text = "Wind $windText", style = MaterialTheme.typography.body2)
                }
                batteryPercent?.let {
                    Text(text = "Handy-Akku $it%", style = MaterialTheme.typography.body2)
                }
            }

            if (state is ConnectionState.Error || state is ConnectionState.Disconnected) {
                Button(onClick = onRetry) {
                    Text("Erneut")
                }
            }
        }
    }
}

private fun statusText(state: ConnectionState): String = when (state) {
    ConnectionState.Scanning -> "Suche Handy…"
    ConnectionState.Connecting -> "Verbinde…"
    ConnectionState.Connected -> "Verbunden"
    ConnectionState.Disconnected -> "Getrennt"
    is ConnectionState.Error -> "Fehler: ${state.message}"
}
