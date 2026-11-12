package com.segeluhr.app.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.segeluhr.app.ui.theme.*

/**
 * Dritte Start-Rolle (AppRole.SPECTATOR, siehe
 * docs/Erweiterung_Regatta_Tracker_Gateway.md) — reine Zuschauer-Ansicht,
 * bettet die bestehende Live-Karten-Webseite des Roemis-Regatta-Tracker-
 * Servers per WebView ein (kein eigener GPS/BLE-Code nötig, die Karte lebt
 * komplett auf dem Server). [url] kommt aus SettingsRepository.spectatorUrlFlow,
 * leer bis einmalig eingerichtet.
 */
@Composable
fun SpectatorScreen(
    url: String,
    onSetUrl: (String) -> Unit,
    onSwitchToSailorMode: () -> Unit,
) {
    var editing by remember(url) { mutableStateOf(url.isBlank()) }

    Box(Modifier.fillMaxSize().background(BgDark)) {
        if (!editing && url.isNotBlank()) {
            SpectatorWebView(url)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(BgDark.copy(alpha = 0.85f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onSwitchToSailorMode) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Zurück zu Boot-Modus", tint = TextLight)
                }
                Spacer(Modifier.width(4.dp))
                Text("Zuschauer — Live-Karte", color = TextLight, fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (!editing && url.isNotBlank()) {
                    IconButton(onClick = { editing = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Link ändern", tint = TextLight)
                    }
                }
            }

            if (editing) {
                var input by remember { mutableStateOf(url) }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Link zur Live-Karte des Regatta-Trackers einfügen (vom Veranstalter/Admin-Bereich).",
                    fontSize = 12.sp, color = TextDim,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Zuschauer-Link") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onSetUrl(input.trim()); editing = false },
                        enabled = input.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color(0xFF04201C)),
                        modifier = Modifier.weight(1f),
                    ) { Text("Anzeigen") }
                    if (url.isNotBlank()) {
                        OutlinedButton(onClick = { editing = false }, modifier = Modifier.weight(1f)) { Text("Abbrechen") }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SpectatorWebView(url: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                // JS/DOM-Storage nötig, da die Live-Karte ein normales JS-Kartenframework
                // (Leaflet o.ä.) sein dürfte, kein statisches HTML.
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Navigation bewusst INNERHALB der WebView halten (kein Abspringen in
                // den System-Browser) - die Zuschauer-Ansicht soll ein einfacher
                // Vollbild-Kartenscreen bleiben.
                webViewClient = WebViewClient()
            }
        },
        update = { it.loadUrl(url) },
    )
}
