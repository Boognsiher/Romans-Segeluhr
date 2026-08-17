package com.segeluhr.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.segeluhr.app.core.GeoUtils
import com.segeluhr.app.data.model.SessionKind
import com.segeluhr.app.data.model.SessionReport
import com.segeluhr.app.ui.components.RouteMapView
import com.segeluhr.app.ui.components.SessionStatsColumn
import com.segeluhr.app.ui.components.sessionReportTitle
import com.segeluhr.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Erweiterung (17.08.2026, siehe docs/Erweiterung_Tages_Auswertung.md):
 * "Verlauf"-Tab — Liste aller gespeicherten Sessions (Tag + einzelne
 * Wettfahrten), Antippen öffnet die Detailansicht mit Route auf der Karte
 * und "Als PDF teilen". Verwaltet die Liste/Detail-Umschaltung selbst
 * (lokaler remember-State) statt eines eigenen Ersetzungs-Screens in
 * MainActivity, da beides klar innerhalb des Tabs bleibt.
 */
@Composable
fun SessionHistoryScreen(
    sessions: List<SessionReport>,
    onDeleteSession: (Long) -> Unit,
    onExportPdf: (SessionReport) -> Unit,
    // Diagnose-Log-Import (17.08.2026, Roman-Wunsch, siehe
    // docs/Erweiterung_Tages_Auswertung.md/DiagnosticsLogImporter.kt) — holt
    // eine zuvor geteilte CSV rückwirkend als DAY-Session in die Historie.
    onImportLog: (Uri) -> Unit,
) {
    var selected by remember { mutableStateOf<SessionReport?>(null) }

    val current = selected
    if (current != null) {
        SessionDetailScreen(
            report = current,
            onBack = { selected = null },
            onDelete = {
                current.id?.let(onDeleteSession)
                selected = null
            },
            onExportPdf = { onExportPdf(current) },
        )
        return
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportLog)
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth().padding(14.dp, 14.dp, 14.dp, 0.dp),
        ) {
            Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Diagnose-Log importieren")
        }

        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Noch keine gespeicherten Sessions.\nErscheint automatisch nach dem ersten App-Stopp bzw. nach einer Wettfahrt — oder importiere ein früheres Diagnose-Log oben.",
                    color = TextDim, fontSize = 13.sp,
                )
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(sessions, key = { it.id ?: it.startedAtMs }) { session ->
                SessionListItem(session, onClick = { selected = session })
            }
        }
    }
}

@Composable
private fun SessionListItem(report: SessionReport, onClick: () -> Unit) {
    val icon: ImageVector = if (report.kind == SessionKind.RACE) Icons.Filled.EmojiEvents else Icons.Filled.WbSunny
    Row(
        Modifier
            .fillMaxWidth()
            .background(PanelDark, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (report.kind == SessionKind.RACE) Amber else Teal)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(sessionReportTitle(report), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextLight)
            Text(
                SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY).format(Date(report.startedAtMs)),
                fontSize = 11.sp, color = TextDim,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(GeoUtils.fmtDist(report.distanceM), fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = TextLight)
            Text(GeoUtils.fmtDuration(report.durationS.toDouble()), fontSize = 11.sp, color = TextDim)
        }
    }
}

@Composable
private fun SessionDetailScreen(
    report: SessionReport,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onExportPdf: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        RouteMapView(route = report.route, modifier = Modifier.fillMaxWidth().height(240.dp))

        // Bugfix (17.08.2026, Roman-Feedback "Karte teils überschnitten der
        // Auswertung"): weight(1f) statt fillMaxSize() - in einer Column
        // bezieht sich fillMaxSize() auf die GESAMTE verfügbare Höhe des
        // Elternteils, nicht auf die nach der Karte oben noch übrige Höhe.
        // Der Stats-Block bekam dadurch dieselbe volle Bildschirmhöhe wie
        // die Karte zugewiesen und überlappte sie. weight(1f) lässt Compose
        // korrekt zuerst die feste Kartenhöhe abziehen und den Rest an
        // dieses Element vergeben.
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            Text(sessionReportTitle(report), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextLight)
            Text(
                SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY).format(Date(report.startedAtMs)),
                fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
            )

            SessionStatsColumn(report)

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Zurück") }
                Button(onClick = onExportPdf, modifier = Modifier.weight(1f)) { Text("Als PDF teilen") }
            }
            Spacer(Modifier.height(8.dp))
            var confirmingDelete by remember { mutableStateOf(false) }
            if (!confirmingDelete) {
                OutlinedButton(
                    onClick = { confirmingDelete = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Löschen") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Wirklich löschen?", fontSize = 13.sp, color = TextLight, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
                    Button(onClick = onDelete, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("Ja") }
                    OutlinedButton(onClick = { confirmingDelete = false }) { Text("Nein") }
                }
            }
        }
    }
}
