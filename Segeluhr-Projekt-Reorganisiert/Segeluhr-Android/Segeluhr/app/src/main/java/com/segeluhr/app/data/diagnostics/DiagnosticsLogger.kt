package com.segeluhr.app.data.diagnostics

import android.content.Context
import androidx.core.content.FileProvider
import android.net.Uri
import com.segeluhr.app.viewmodel.SegeluhrUiState
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Erweiterung (10.08.2026, siehe docs/Erweiterung_Diagnose_Log.md): schreibt
 * bei jedem 1-Hz-Tick eine Zeile mit dem kompletten internen Segel-Zustand
 * (GPS, Wind, gelernte Winkel, Heimweg-/Competition-Guidance, ...) als CSV
 * ins App-interne Verzeichnis. Zweck: erster Segeltörn mit den heute
 * gebauten Features (Boots-Kalibrierung, Vorwind-Winkel, Distanz-Tracking)
 * soll auswertbare Daten liefern, ohne dass am Boot ein Laptop mit
 * angeschlossenem seriellem Monitor nötig ist — die Uhr-Firmware hat sowas
 * (Serial-Ausgaben, siehe TODO(Test-Debug) in den .ino-Dateien), aber das
 * Handy ist das einzige Gerät, das während eines echten Segeltörns
 * sinnvoll mitschreiben kann.
 *
 * Ein File pro "Logging-Session" (= pro ViewModel-Lebensdauer, faktisch pro
 * App-Start) — wird lazy beim ersten [logTick] angelegt, nicht schon beim
 * Erzeugen dieser Klasse, damit ein sofort wieder deaktiviertes Logging
 * keine leere Datei hinterlässt.
 */
class DiagnosticsLogger(private val context: Context) {

    private val dir: File by lazy {
        File(context.filesDir, "diagnostics").apply { mkdirs() }
    }

    private var writer: FileWriter? = null
    private var file: File? = null

    /** Für die Setup-Tab-Anzeige ("N Zeilen protokolliert") — kein State-Flow nötig, wird eh nur 1x/s aus dem Tick gelesen. */
    var rowCount: Int = 0
        private set

    val currentFileName: String? get() = file?.name

    private val isoTime = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)
    private val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY)

    private val header = listOf(
        "ts_iso", "ts_epoch_ms",
        "lat", "lon", "sog_kn", "cog_deg", "gps_valid", "gps_accuracy_m",
        "wind_dir_deg", "wind_calibrated", "wind_calib_state", "wind_net_deg", "wind_range_deg",
        "closehauled_twa_deg", "closehauled_samples", "downwind_twa_deg",
        "calib_mode_on", "smart_mode_on", "active_boat_profile",
        "vmg_kn", "distance_traveled_m",
        "home_active", "home_bearing_deg", "home_dist_m", "home_rec_heading_deg",
        "home_maneuver_needed", "home_vmc_kn", "home_eta_s",
        "train_mode",
        "competition_active", "comp_leg", "comp_bearing_deg", "comp_dist_m",
        "comp_rec_heading_deg", "comp_maneuver_needed", "comp_lap_count", "comp_is_estimated",
        "lake_dist_m", "lake_dist_pct",
        "watch_connected", "operation_mode", "app_role",
        "status_text", "status_level",
        "event",
    ).joinToString(",")

    /**
     * Legt bei Bedarf eine neue Datei an (nur beim allerersten Aufruf
     * innerhalb dieser Logger-Instanz — ein File pro App-Start, siehe
     * Klassendoku) und hängt die Kopfzeile an. `event` bleibt bei normalen
     * Ticks leer -
     * für manuell gesetzte Marker (Setup-Tab "Ereignis markieren") wird
     * dieselbe Zeilenform mit gefülltem `event`-Feld genutzt, damit sich
     * beim Auswerten einfach nach der Spalte gefiltert werden kann, statt
     * ein zweites Dateiformat zu pflegen.
     */
    private fun ensureOpen() {
        if (writer != null) return
        val f = File(dir, "diagnose_${fileTimestamp.format(Date())}.csv")
        file = f
        writer = FileWriter(f, true).apply { write(header + "\n") }
        rowCount = 0
    }

    fun logTick(state: SegeluhrUiState, event: String = "") {
        ensureOpen()
        val w = writer ?: return
        val fix = state.gpsFix
        val hg = state.homeGuidance
        val cg = state.competitionGuidance
        val now = System.currentTimeMillis()

        val row = listOf(
            isoTime.format(Date(now)), now,
            fix.lat, fix.lon, fix.sogKn, fix.cogDeg, fix.valid, fix.accuracyM,
            state.windDir, state.windCalibrated, state.windCalibState, state.windNet, state.windRange,
            state.closehauledAngleDeg, state.closehauledSampleCount, state.downwindAngleDeg,
            state.calibrationModeEnabled, state.smartModeEnabled, state.activeBoatProfileId,
            state.vmg, state.distanceTraveledM,
            state.homeModeActive, hg?.bearingToHome, hg?.distanceM, hg?.recommendedHeading,
            hg?.maneuverNeeded, hg?.vmcKn, hg?.etaSeconds,
            state.trainMode,
            state.competitionActive, cg?.leg, cg?.bearing, cg?.distanceM,
            cg?.recommendedHeading, cg?.maneuverNeeded, cg?.lapCount, cg?.isEstimated,
            state.lakeDistanceM, state.lakeDistancePct,
            state.watchConnected, state.operationMode, state.appRole,
            csvEscape(state.statusText), state.statusLevel,
            csvEscape(event),
        ).joinToString(",") { it?.toString() ?: "" }

        w.write(row + "\n")
        w.flush() // 1x/s, I/O-Kosten vernachlässigbar - lieber sofort auf Disk als bei einem Absturz Daten verlieren
        rowCount++
    }

    /** Setup-Tab-Button "Ereignis markieren" — schreibt sofort eine Zeile mit dem aktuellen Zustand + Freitext-Notiz, statt auf den nächsten Tick zu warten. */
    fun markEvent(state: SegeluhrUiState, note: String) = logTick(state, note.ifBlank { "MARKER" })

    /** Simple CSV-Escaping: Anführungszeichen verdoppeln + in Anführungszeichen einpacken, falls Komma/Anführungszeichen/Zeilenumbruch enthalten sind (statusText ist Freitext, könnte theoretisch beides enthalten). */
    private fun csvEscape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"${s.replace("\"", "\"\"")}\"" else s

    fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }

    /** Content-URI übers FileProvider fürs Teilen (Setup-Tab "Log teilen") — null, falls noch nie geloggt wurde. */
    fun shareUri(): Uri? {
        val f = file ?: return null
        writer?.flush()
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }
}
