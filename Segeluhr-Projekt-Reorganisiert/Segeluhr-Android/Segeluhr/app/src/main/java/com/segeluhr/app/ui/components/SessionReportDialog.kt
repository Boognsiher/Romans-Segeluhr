package com.segeluhr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.segeluhr.app.core.GeoUtils
import com.segeluhr.app.data.model.SessionReport
import com.segeluhr.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Erweiterung (17.08.2026, siehe docs/Erweiterung_Tages_Auswertung.md):
 * automatische Zusammenfassung der aktuellen Session — erscheint bei
 * App-Stopp (SegeluhrViewModel.stopApp()), Pendant zum bisher manuellen
 * CSV-Auswerten der Diagnose-Logs, jetzt direkt in der App.
 */
@Composable
fun SessionReportDialog(report: SessionReport, onDismiss: () -> Unit, onShare: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(PanelDark, RoundedCornerShape(16.dp))
                .border(1.dp, LineDark, RoundedCornerShape(16.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(sessionReportTitle(report), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextLight)
            Text(
                "Gestartet ${SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY).format(Date(report.startedAtMs))}",
                fontSize = 12.sp, color = TextDim,
                modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
            )

            SessionStatsColumn(report)

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("Als PDF teilen") }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Schliessen") }
            }
        }
    }
}

/** "Tages-Auswertung" oder "Wettfahrt-Auswertung" — gemeinsam für Dialog, Verlaufsliste und PDF-Titel genutzt. */
fun sessionReportTitle(report: SessionReport): String = when (report.kind) {
    com.segeluhr.app.data.model.SessionKind.DAY -> "Tages-Auswertung"
    com.segeluhr.app.data.model.SessionKind.RACE -> "Wettfahrt-Auswertung"
}

/**
 * Statistik-Block, gemeinsam genutzt von [SessionReportDialog] (Dialog bei
 * App-Stopp/Wettfahrt-Ende) und dem Verlauf-Detail-Screen (siehe
 * docs/Erweiterung_Tages_Auswertung.md) — dieselben Zahlen, zwei
 * Anzeigeorte.
 */
@Composable
fun SessionStatsColumn(report: SessionReport) {
    StatRow("Dauer", GeoUtils.fmtDuration(report.durationS.toDouble()))
    StatRow("Distanz", GeoUtils.fmtDist(report.distanceM))
    StatRow("Max. Speed", "%.1f kn".format(report.maxSpeedKn))
    StatRow("Ø Speed", report.avgSpeedKn?.let { "%.1f kn".format(it) } ?: "--")

    SectionLabel("Manöver")
    StatRow("Wenden", report.tackCount.toString() + (report.avgTackAngleDeg?.let { " · Ø %.0f°".format(it) } ?: ""))
    StatRow("Halsen", report.gybeCount.toString() + (report.avgGybeAngleDeg?.let { " · Ø %.0f°".format(it) } ?: ""))

    SectionLabel("Wind")
    StatRow("Shifts erkannt", "${report.windShiftCount} (${report.windShiftHeaderCount} Header / ${report.windShiftLiftCount} Lift)")
    StatRow("Windkalibrierungen", report.windCalibrationCount.toString())
    StatRow("Wendewinkel (gelernt)", "%.0f°".format(report.finalClosehauledAngleDeg))
    StatRow("Vorwind-Winkel (gelernt)", "%.0f°".format(report.finalDownwindAngleDeg))

    if (report.watchConnectedPct != null) {
        SectionLabel("Uhr")
        StatRow("Verbindung", "%.0f%% der Zeit".format(report.watchConnectedPct))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextDim,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

/** Klartext-Fassung für den "Teilen"-Button (ACTION_SEND text/plain) — keine Datei, kein FileProvider nötig. */
fun sessionReportShareText(report: SessionReport): String {
    val date = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY).format(Date(report.startedAtMs))
    return buildString {
        appendLine("⛵ Segeluhr ${sessionReportTitle(report)} — $date")
        appendLine()
        appendLine("Dauer: ${GeoUtils.fmtDuration(report.durationS.toDouble())}")
        appendLine("Distanz: ${GeoUtils.fmtDist(report.distanceM)}")
        appendLine("Max. Speed: %.1f kn".format(report.maxSpeedKn))
        appendLine("Ø Speed: " + (report.avgSpeedKn?.let { "%.1f kn".format(it) } ?: "--"))
        appendLine()
        appendLine("Wenden: ${report.tackCount}" + (report.avgTackAngleDeg?.let { " (Ø %.0f°)".format(it) } ?: ""))
        appendLine("Halsen: ${report.gybeCount}" + (report.avgGybeAngleDeg?.let { " (Ø %.0f°)".format(it) } ?: ""))
        appendLine()
        appendLine("Wind-Shifts: ${report.windShiftCount} (${report.windShiftHeaderCount} Header / ${report.windShiftLiftCount} Lift)")
        appendLine("Windkalibrierungen: ${report.windCalibrationCount}")
        appendLine("Wendewinkel (gelernt): %.0f°".format(report.finalClosehauledAngleDeg))
        appendLine("Vorwind-Winkel (gelernt): %.0f°".format(report.finalDownwindAngleDeg))
        if (report.watchConnectedPct != null) {
            appendLine()
            appendLine("Uhr verbunden: %.0f%% der Zeit".format(report.watchConnectedPct))
        }
    }
}
