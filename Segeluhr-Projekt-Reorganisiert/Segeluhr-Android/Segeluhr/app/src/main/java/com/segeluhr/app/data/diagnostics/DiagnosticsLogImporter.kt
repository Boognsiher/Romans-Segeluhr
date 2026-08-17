package com.segeluhr.app.data.diagnostics

import android.content.Context
import android.net.Uri
import com.segeluhr.app.core.Fix
import com.segeluhr.app.core.HapticFeedback
import com.segeluhr.app.data.model.OperationMode
import com.segeluhr.app.data.model.SessionKind
import com.segeluhr.app.data.model.SessionReport
import com.segeluhr.app.logic.SessionSummaryEngine
import com.segeluhr.app.logic.StatusSink
import com.segeluhr.app.logic.WindEngine
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Erweiterung (17.08.2026, Roman-Wunsch, siehe docs/Erweiterung_Tages_Auswertung.md):
 * importiert ein zuvor exportiertes Diagnose-Log (siehe
 * docs/Erweiterung_Diagnose_Log.md, DiagnosticsLogger-CSV-Format) rückwirkend
 * als Session in die neue Historie — insbesondere für die beiden bereits im
 * Repo liegenden echten Törns (15./16.08., vor dieser Erweiterung entstanden,
 * daher bisher ohne Verlauf-Tab-Eintrag) und für jedes künftig manuell
 * geteilte CSV.
 *
 * Spielt die CSV-Zeilen der Reihe nach durch EINE frische [WindEngine]-
 * Instanz (`tickContinuous`), statt Wenden/Halsen/Wind-Shifts neu zu
 * erfinden — exakt derselbe, bereits gegen echte Logs verifizierte
 * Erkennungsweg wie im Live-Betrieb (siehe PROJEKT_STATUS.md,
 * 16.08.-Python-Replikat-Befund: dieselbe Methode traf 140/145 bzw. 39/52
 * geloggte Events exakt).
 *
 * **Bekannte Einschränkungen** (Trade-off ggü. einem separaten, aufwändigeren
 * Reimport-Format):
 * - Kein Ziel-Wegpunkt aus der CSV rekonstruierbar → Header/Lift-Aufteilung
 *   der Wind-Shifts entfällt beim Import (`windShiftHeaderCount`/
 *   `windShiftLiftCount` bleiben 0, `windShiftCount` selbst ist unverändert
 *   korrekt, da unabhängig vom Ziel).
 * - Windkalibrierungen werden nur gezählt, wenn dabei der Wendewinkel-
 *   Kalibrierungsmodus an war (`closehauled_samples`-Spalte inkrementiert) —
 *   Kalibrierungen OHNE das (nur Windrichtung, kein Wendewinkel-Lernen)
 *   fehlen in der Zählung.
 * - Immer als DAY-Session importiert (kein Wettfahrt-Fenster aus einer
 *   losen CSV rekonstruierbar).
 */
object DiagnosticsLogImporter {

    // Spalten-Indizes im DiagnosticsLogger-CSV-Header (siehe dort) — 0-basiert.
    private const val COL_TS_EPOCH_MS = 1
    private const val COL_LAT = 2
    private const val COL_LON = 3
    private const val COL_SOG_KN = 4
    private const val COL_COG_DEG = 5
    private const val COL_GPS_VALID = 6
    private const val COL_WIND_DIR_DEG = 8
    private const val COL_WATCH_CONNECTED = 39
    private const val COL_OPERATION_MODE = 40
    private const val MIN_COLUMNS = 41

    private object NoOpHaptics : HapticFeedback {
        override fun step1() {}
        override fun done2() {}
        override fun header3() {}
        override fun error4() {}
        override fun lakeWarn5() {}
        override fun rounding6() {}
        override fun maneuverCmd() {}
        override fun startSignal() {}
        override fun roundingConfirmNeeded() {}
    }

    /** Null bei leerer/kaputter Datei oder wenn keine einzige Zeile einen validen GPS-Fix hatte. */
    suspend fun import(context: Context, uri: Uri): SessionReport? {
        val lines = context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).readLines()
        } ?: return null
        if (lines.size < 2) return null // nur Kopfzeile oder leer

        val windEngine = WindEngine(
            vib = NoOpHaptics,
            status = StatusSink { _, _ -> },
            onWindChanged = { _, _ -> },
            onBoatProfileChanged = { _, _, _, _ -> },
        )
        val summaryEngine = SessionSummaryEngine()
        var windSeeded = false

        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = parseCsvLine(line)
            if (cols.size < MIN_COLUMNS) continue
            val tsMs = cols[COL_TS_EPOCH_MS].toLongOrNull() ?: continue
            val lat = cols[COL_LAT].toDoubleOrNull()
            val lon = cols[COL_LON].toDoubleOrNull()
            val sog = cols[COL_SOG_KN].toDoubleOrNull()
            val cog = cols[COL_COG_DEG].toDoubleOrNull()
            val valid = cols[COL_GPS_VALID] == "true"
            val windDir = cols[COL_WIND_DIR_DEG].toDoubleOrNull()
            val watchConnected = cols[COL_WATCH_CONNECTED] == "true"
            val opMode = if (cols[COL_OPERATION_MODE] == "WITH_WATCH") OperationMode.WITH_WATCH else OperationMode.STANDALONE

            val fix = Fix(lat, lon, cog, sog, tsMs, null, valid)

            if (!windSeeded && windDir != null) {
                windEngine.restore(windDir, true)
                windSeeded = true
            }
            if (windSeeded) windEngine.tickContinuous(fix, target = null)
            summaryEngine.onTick(fix, watchConnected, opMode)
        }

        return summaryEngine.buildReport(windEngine, SessionKind.DAY)
    }

    /** Macht DiagnosticsLogger.csvEscape() rückgängig (Anführungszeichen verdoppeln + Feld in Anführungszeichen einpacken bei Komma/Anführungszeichen/Zeilenumbruch). */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { result.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }
}
