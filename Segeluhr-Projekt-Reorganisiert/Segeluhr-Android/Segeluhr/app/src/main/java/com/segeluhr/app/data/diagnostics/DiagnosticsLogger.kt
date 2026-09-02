package com.segeluhr.app.data.diagnostics

import android.content.Context
import androidx.core.content.FileProvider
import android.net.Uri
import com.segeluhr.app.core.GeoPoint
import com.segeluhr.app.core.GeoUtils
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
 *
 * **02.09.2026, Roman-Wunsch ("logge alles was hilft, genug Speicherplatz")**:
 * deutlich erweitert im Hinblick auf die geplante automatische
 * Bojenerkennung (Muster-Suche nach dem nächsten Wassertest) — Distanz+
 * Peilung zu ALLEN acht Wegpunkt-Typen, der von [MarkRoundingDetector]
 * verwendete Amwind/Vorwind-"Side"-Wert (hier rein aus wind_dir_deg/cog_deg
 * nachgerechnet, OHNE die Engines anzufassen), Kursänderungsrate, plus
 * bisher schon berechnete, aber nie geloggte UiState-Felder (Linien-Bias,
 * aktive Boje, Pending-Bojen-Rückfrage, Race-State/Countdown). Bewusst
 * NICHT im laufenden Betrieb aus TrainingEngine/CompetitionEngine
 * herausgezogen (deren `MarkRoundingDetector`-Instanzen sind privat) — das
 * hätte Änderungen an den Segel-Engines kurz vor einem echten Wassertest
 * bedeutet, unnötiges Risiko für eine reine Logging-Erweiterung. Alle
 * neuen Spalten sind daher rein aus bereits vorhandenen [SegeluhrUiState]-
 * Feldern abgeleitet.
 *
 * **Wichtig für künftige Erweiterungen:** neue Spalten IMMER ans Ende
 * anhängen, nie mitten in die bestehende Reihenfolge einfügen —
 * [DiagnosticsLogImporter] liest die für den Reimport benötigten Felder
 * über feste 0-basierte Spalten-Indizes, die bei jeder Umsortierung
 * brechen würden.
 */
class DiagnosticsLogger(private val context: Context) {

    private val dir: File by lazy {
        File(context.filesDir, "diagnostics").apply { mkdirs() }
    }

    private var writer: FileWriter? = null
    private var file: File? = null

    // Für cogRateDps() — letzter bekannter Kurs+Zeitpunkt, um die
    // Kursänderungsrate zwischen zwei Ticks zu berechnen (siehe dort).
    private var lastCogDeg: Double? = null
    private var lastCogAtMs: Long? = null

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
        // ---- 02.09.2026 ergänzt, siehe Klassendoku — IMMER ans Ende anhängen ----
        "race_state", "countdown_seconds",
        "comp_vmc_kn",
        "gps_fresh", "gps_moving",
        "line_bias_deg", "line_bias_favors",
        "active_buoy_label", "active_buoy_bearing_deg", "active_buoy_dist_m",
        "avg_tack_score", "avg_jibe_score",
        "pending_confirm_source", "pending_confirm_waypoint_key",
        "pending_confirm_candidate_lat", "pending_confirm_candidate_lon", "pending_confirm_age_s",
        "wind_side", "cog_rate_dps",
        "dist_pin_m", "brg_pin_deg", "dist_boat_m", "brg_boat_deg",
        "dist_target_m", "brg_target_deg",
        "dist_buoy1_m", "brg_buoy1_deg", "dist_buoy2_m", "brg_buoy2_deg",
        "dist_home_m", "brg_home_deg",
        "dist_mark1_m", "brg_mark1_deg", "dist_mark2_m", "brg_mark2_deg",
        // ---- 02.09.2026 (spät) ergänzt, Kurs-Modell-Überarbeitung — siehe
        // docs/Erweiterung_Competition_Kursmodell.md, wieder IMMER ans Ende ----
        "leeward_mode",
        "dist_lee_buoy_m", "brg_lee_buoy_deg",
        "dist_gate_a_m", "brg_gate_a_deg", "dist_gate_b_m", "brg_gate_b_deg",
        "dist_finish_buoy_m", "brg_finish_buoy_deg",
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
        val pbc = state.pendingBuoyConfirmation
        val now = System.currentTimeMillis()

        val (distPin, brgPin) = distBrg(fix.lat, fix.lon, state.pin)
        val (distBoat, brgBoat) = distBrg(fix.lat, fix.lon, state.boat)
        val (distTarget, brgTarget) = distBrg(fix.lat, fix.lon, state.target)
        val (distBuoy1, brgBuoy1) = distBrg(fix.lat, fix.lon, state.buoy1)
        val (distBuoy2, brgBuoy2) = distBrg(fix.lat, fix.lon, state.buoy2)
        val (distHome, brgHome) = distBrg(fix.lat, fix.lon, state.home)
        val (distMark1, brgMark1) = distBrg(fix.lat, fix.lon, state.competitionMark1)
        val (distMark2, brgMark2) = distBrg(fix.lat, fix.lon, state.competitionMark2)
        val (distLeeBuoy, brgLeeBuoy) = distBrg(fix.lat, fix.lon, state.leeBuoy)
        val (distGateA, brgGateA) = distBrg(fix.lat, fix.lon, state.gateA)
        val (distGateB, brgGateB) = distBrg(fix.lat, fix.lon, state.gateB)
        val (distFinishBuoy, brgFinishBuoy) = distBrg(fix.lat, fix.lon, state.finishBuoy)

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
            // ---- 02.09.2026 ergänzt, siehe Klassendoku ----
            state.raceState, state.countdownSeconds,
            cg?.vmcKn,
            state.gpsFresh, state.gpsMoving,
            state.lineBiasDeg, state.lineBiasFavors,
            state.activeBuoyLabel, state.buoyBearing, state.buoyDistanceM,
            state.avgTackScore, state.avgJibeScore,
            pbc?.source, pbc?.waypointKey,
            pbc?.candidatePosition?.lat, pbc?.candidatePosition?.lon,
            pbc?.let { (now - it.startedAtMs) / 1000.0 },
            windSide(fix.cogDeg, state.windDir, state.windCalibrated), cogRateDps(fix.cogDeg, now),
            distPin, brgPin, distBoat, brgBoat,
            distTarget, brgTarget,
            distBuoy1, brgBuoy1, distBuoy2, brgBuoy2,
            distHome, brgHome,
            distMark1, brgMark1, distMark2, brgMark2,
            state.leewardMode,
            distLeeBuoy, brgLeeBuoy,
            distGateA, brgGateA, distGateB, brgGateB,
            distFinishBuoy, brgFinishBuoy,
        ).joinToString(",") { it?.toString() ?: "" }

        w.write(row + "\n")
        w.flush() // 1x/s, I/O-Kosten vernachlässigbar - lieber sofort auf Disk als bei einem Absturz Daten verlieren
        rowCount++
    }

    /** Distanz+Peilung von der aktuellen GPS-Position zu einem Wegpunkt, oder (null,null) ohne Fix/Wegpunkt. */
    private fun distBrg(lat: Double?, lon: Double?, point: GeoPoint?): Pair<Double?, Double?> {
        if (lat == null || lon == null || point == null) return null to null
        return GeoUtils.distanceMeters(lat, lon, point.lat, point.lon) to GeoUtils.bearingDeg(lat, lon, point.lat, point.lon)
    }

    /**
     * Amwind/Vorwind-Seite relativ zum Wind, EXAKT dieselbe Formel wie
     * [com.segeluhr.app.logic.MarkRoundingDetector] (`abs(angleDiff(cog,
     * windDir)) < 90`) — hier nur rein aus schon geloggten Werten
     * nachgerechnet, keine Kopplung an die Engine-Instanz selbst.
     */
    private fun windSide(cog: Double?, windDir: Double?, calibrated: Boolean): String? {
        if (!calibrated || cog == null || windDir == null) return null
        return if (kotlin.math.abs(GeoUtils.angleDiff(cog, windDir)) < 90.0) "upwind" else "downwind"
    }

    /** Kursänderungsrate in °/s zwischen diesem und dem letzten Tick — null bei fehlendem Kurs oder einer Lücke >5s (z.B. nach Uhr-Reconnect), damit kein Ausreisser-Sprung als Kurve durchgeht. */
    private fun cogRateDps(cog: Double?, nowMs: Long): Double? {
        val rate = if (cog != null && lastCogDeg != null && lastCogAtMs != null) {
            val dtS = (nowMs - lastCogAtMs!!) / 1000.0
            if (dtS in 0.1..5.0) GeoUtils.angleDiff(cog, lastCogDeg!!) / dtS else null
        } else null
        if (cog != null) { lastCogDeg = cog; lastCogAtMs = nowMs }
        return rate
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
