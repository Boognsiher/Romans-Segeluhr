package com.segeluhr.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.segeluhr.app.core.GeoPoint
import com.segeluhr.app.data.model.AppRole
import com.segeluhr.app.data.model.OperationMode
import com.segeluhr.app.ui.components.SectionCard
import com.segeluhr.app.ui.components.WaypointRow
import com.segeluhr.app.ui.theme.Amber
import com.segeluhr.app.ui.theme.Red
import com.segeluhr.app.ui.theme.Teal
import com.segeluhr.app.ui.theme.TextDim
import com.segeluhr.app.ui.theme.TextLight
import com.segeluhr.app.ui.theme.Panel2Dark
import com.segeluhr.app.viewmodel.SegeluhrUiState

@Composable
fun SetupScreen(
    state: SegeluhrUiState,
    onSetWaypoint: (String) -> Unit,
    onClearWaypoint: (String) -> Unit,
    onWakeLockChanged: (Boolean) -> Unit,
    onOperationModeChanged: (OperationMode) -> Unit,
    onAppRoleChanged: (AppRole) -> Unit,
    onRequestLocationPermission: () -> Unit,
    onResetAll: () -> Unit,
    onSetActiveBoatProfile: (String) -> Unit,
    onAddBoatProfile: (String) -> Unit,
    onDeleteBoatProfile: (String) -> Unit,
    onDiagnosticsEnabledChanged: (Boolean) -> Unit,
    onMarkDiagnosticsEvent: (String) -> Unit,
    onGetDiagnosticsShareUri: () -> android.net.Uri?,
    onStopApp: () -> Unit,
    onStartApp: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Startlinie") {
            WaypointRow("Pin-Ende", fmt(state.pin), { onSetWaypoint("pin") }, { onClearWaypoint("pin") })
            WaypointRow("Boot-Ende", fmt(state.boat), { onSetWaypoint("boat") }, { onClearWaypoint("boat") })
            Text(
                "⚠️ Vorzeichen von Line-Bias hängt von Pin/Boot-Zuordnung ab — einmal mit bekanntem Wind gegenprüfen.",
                fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(top = 6.dp),
            )
        }

        SectionCard("Regatta-Ziel") {
            WaypointRow("Ziel-Wegpunkt", fmt(state.target), { onSetWaypoint("target") }, { onClearWaypoint("target") })
            Text(
                "Wird für Header/Lift-Alarm beim Wind-Tracking verwendet (3× Vibration = Header).",
                fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(top = 6.dp),
            )
        }

        SectionCard("Zuhause / Hafen") {
            WaypointRow("Heimatpunkt", fmt(state.home), { onSetWaypoint("home") }, { onClearWaypoint("home") })
            Text(
                "Wird für die Heimweg-Navigation im Normal-Tab verwendet (Peilung, Wende-Vorschlag, ETA).",
                fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(top = 6.dp),
            )
        }

        SectionCard("Wettfahrt-Bojen (Luv, optional)") {
            WaypointRow("Luvbake (Hauptbake)", fmt(state.competitionMark1), { onSetWaypoint("competitionMark1") }, { onClearWaypoint("competitionMark1") })
            WaypointRow("Entlastungsboje (Halbwind)", fmt(state.competitionMark2), { onSetWaypoint("competitionMark2") }, { onClearWaypoint("competitionMark2") })
            Text(
                "Für den Competition-Modus (startet automatisch beim Startsignal). Ohne gesetzte Luvbake wird sie genau gegen den Wind geschätzt. Die Entlastungsboje ist optional — falls gesetzt, wird nach der Luvbake ein kurzer Halbwind-Schlag dorthin eingeplant.",
                fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(top = 6.dp),
            )
        }

        SectionCard("Boots-Profil") {
            Text(
                "Eigener Wendewinkel pro Boot — z.B. eigenes Boot + Charterboot getrennt kalibrieren. Kalibriert wird im Wind-Tab, wirkt immer auf das hier aktive Profil.",
                fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(bottom = 8.dp),
            )
            state.boatProfiles.forEach { profile ->
                val isActive = profile.id == state.activeBoatProfileId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isActive) { onSetActiveBoatProfile(profile.id) }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            profile.name,
                            color = if (isActive) Teal else TextLight,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        )
                        // Vorwind-Winkel (10.08.2026 ergänzt) hat kein eigenes
                        // sampleCount (nur Smart-Modus, siehe BoatProfile-Doku) -
                        // deshalb nur der Wert selbst, kompakt an den
                        // Wendewinkel angehängt statt eine eigene Zeile.
                        val angleText = if (profile.sampleCount > 0) {
                            "${"%.0f".format(profile.closehauledAngleDeg)}° Wende (${profile.sampleCount} Kalibrierläufe) / ${"%.0f".format(profile.downwindAngleDeg)}° Vorwind"
                        } else {
                            "${"%.0f".format(profile.closehauledAngleDeg)}° Wende / ${"%.0f".format(profile.downwindAngleDeg)}° Vorwind (Standardwerte)"
                        }
                        Text(angleText, fontSize = 11.sp, color = TextDim)
                    }
                    if (state.boatProfiles.size > 1) {
                        IconButton(onClick = { onDeleteBoatProfile(profile.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Profil \"${profile.name}\" löschen", tint = TextDim)
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            var addingProfile by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            var newProfileName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
            if (!addingProfile) {
                OutlinedButton(onClick = { newProfileName = ""; addingProfile = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("+ Neues Profil")
                }
            } else {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    label = { Text("Name, z.B. Bootsname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onAddBoatProfile(newProfileName); addingProfile = false },
                        enabled = newProfileName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color(0xFF04201C)),
                        modifier = Modifier.weight(1f),
                    ) { Text("Anlegen") }
                    OutlinedButton(onClick = { addingProfile = false }, modifier = Modifier.weight(1f)) { Text("Abbrechen") }
                }
            }
        }

        SectionCard("Rolle") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                val roles = listOf(
                    AppRole.SAILOR to "Auf dem Boot",
                    AppRole.SHORE to "An Land",
                )
                roles.forEach { (role, label) ->
                    val active = state.appRole == role
                    Button(
                        onClick = { onAppRoleChanged(role) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (active) Teal else Panel2Dark,
                            contentColor = if (active) Color(0xFF04201C) else TextDim,
                        ),
                        modifier = Modifier.weight(1f),
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "\"An Land\" zeigt statt der Segel-Tabs nur eine Karte mit der zuletzt " +
                    "per LoRa empfangenen Boot-Position (verbindet sich per Bluetooth mit " +
                    "der Land-Uhr, nicht mit dem Boot direkt). Zurück geht's im Karten-Screen selbst.",
                fontSize = 12.sp, color = TextDim,
            )
        }

        SectionCard("App-Betrieb") {
            Text(
                if (state.appStopped) {
                    "App gestoppt — GPS, Tickschleife und Uhr-Verbindung sind angehalten " +
                        "(kein zusätzlicher Akkuverbrauch im Hintergrund). \"Start\" nimmt " +
                        "wieder genau dort auf."
                } else {
                    "GPS läuft aktuell durchgehend, solange die App offen ist — bei Pausen " +
                        "(z.B. Mittagspause am Steg) hier stoppen, um Akku zu sparen."
                },
                fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(bottom = 10.dp),
            )
            if (state.appStopped) {
                Button(
                    onClick = onStartApp,
                    colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color(0xFF04201C)),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start") }
            } else {
                OutlinedButton(
                    onClick = onStopApp,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stopp") }
            }
        }

        SectionCard("Betriebsmodus") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                val modes = listOf(
                    OperationMode.STANDALONE to "Ohne Uhr",
                    OperationMode.WITH_WATCH to "Mit Uhr",
                )
                modes.forEach { (mode, label) ->
                    val active = state.operationMode == mode
                    Button(
                        onClick = { onOperationModeChanged(mode) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (active) Teal else Panel2Dark,
                            contentColor = if (active) Color(0xFF04201C) else TextDim,
                        ),
                        modifier = Modifier.weight(1f),
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(10.dp))
            val explanation = when (state.operationMode) {
                OperationMode.STANDALONE ->
                    "Das Handy vibriert selbst (Standalone) und rechnet alles lokal — die Uhr wird nicht gebraucht."
                OperationMode.WITH_WATCH ->
                    if (state.watchConnected) {
                        "✅ Uhr verbunden — GPS und Vibrationsmuster-Kommandos werden per BLE an die T-Watch Ultra gesendet. Das Handy selbst vibriert nicht."
                    } else {
                        "⏳ Noch keine Uhr verbunden — bis die Verbindung steht, vibriert das Handy automatisch als Fallback, damit kein Signal verloren geht."
                    }
            }
            Text(
                explanation, fontSize = 12.sp,
                color = if (state.operationMode == OperationMode.WITH_WATCH && !state.watchConnected) Amber else TextDim,
            )
        }

        SectionCard("Bildschirm") {
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Display wach halten")
                Switch(checked = state.wakeLockEnabled, onCheckedChange = onWakeLockChanged)
            }
        }

        SectionCard("GPS") {
            if (!state.locationPermissionGranted) {
                Button(onClick = onRequestLocationPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("Standortzugriff erlauben")
                }
            } else {
                Text(
                    if (state.gpsFresh) "GPS aktiv." else "Warte auf GPS-Fix…",
                    fontSize = 13.sp,
                )
            }
            // Bugfix 09.08.2026: eigener Button statt an locationPermissionGranted
            // gekoppelt - sonst bleibt BLUETOOTH_SCAN bei Installs mit schon
            // erteiltem Standortzugriff für immer unangefragt, "An Land" findet
            // dann nie eine Land-Uhr (siehe Kommentar in MainActivity.kt).
            if (!state.bluetoothScanPermissionGranted) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRequestLocationPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("Bluetooth-Berechtigung erlauben (für \"An Land\")")
                }
            }
        }

        SectionCard("Diagnose-Log") {
            val context = androidx.compose.ui.platform.LocalContext.current
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Aufzeichnung aktiv")
                    Text(
                        "Schreibt 1x/s den kompletten Segel-Zustand (GPS, Wind, gelernte Winkel, " +
                            "Heimweg-/Wettfahrt-Guidance) als CSV mit — für die Auswertung nach dem Törn.",
                        fontSize = 11.sp, color = TextDim,
                    )
                }
                Switch(checked = state.diagnosticsEnabled, onCheckedChange = onDiagnosticsEnabledChanged)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.diagnosticsFileName != null) {
                    "${state.diagnosticsFileName} — ${state.diagnosticsRowCount} Zeilen"
                } else {
                    "Noch keine Datei angelegt (wird beim ersten Tick erstellt)."
                },
                fontSize = 11.sp, color = TextDim,
            )

            Spacer(Modifier.height(12.dp))
            var markerNote by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
            OutlinedTextField(
                value = markerNote,
                onValueChange = { markerNote = it },
                label = { Text("Notiz (optional, z.B. \"gezielte Wende jetzt\")") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onMarkDiagnosticsEvent(markerNote); markerNote = "" },
                    colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color(0xFF04201C)),
                    modifier = Modifier.weight(1f),
                ) { Text("Ereignis markieren") }
                OutlinedButton(
                    onClick = {
                        val uri = onGetDiagnosticsShareUri() ?: return@OutlinedButton
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "Diagnose-Log teilen"))
                    },
                    enabled = state.diagnosticsFileName != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Log teilen") }
            }
        }

        SectionCard("Alles zurücksetzen") {
            var confirming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            if (!confirming) {
                OutlinedButton(
                    onClick = { confirming = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Alle Daten löschen") }
            } else {
                Text("Wirklich ALLE Daten (Wegpunkte, Log, Windkalibrierung, Boots-Profile) löschen?", fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onResetAll(); confirming = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Red),
                        modifier = Modifier.weight(1f),
                    ) { Text("Ja, löschen") }
                    OutlinedButton(onClick = { confirming = false }, modifier = Modifier.weight(1f)) { Text("Abbrechen") }
                }
            }
        }
    }
}

private fun fmt(p: GeoPoint?): String = if (p == null) "nicht gesetzt" else "%.5f, %.5f".format(p.lat, p.lon)
