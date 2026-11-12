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
 *
 * **22.09.2026 Bugfix + Ergänzung** (siehe
 * docs/Erweiterung_Windschaetzung_Robust.md): Spalten werden jetzt per Name
 * statt per festem Index aufgelöst — die Index-Fassung wäre durch die neue
 * `wind_sample_count`-Spalte im selben Commit kaputtgegangen (jede Spalte
 * danach hätte sich um 1 verschoben). Ausserdem wird jetzt `isRacing`
 * (Competition ODER Trainings-Racemode, aus `competition_active`/
 * `train_mode`) an `tickContinuous()` weitergereicht, damit ein Reimport
 * denselben Ausweich-/Bojenmanöver-Filter anwendet wie der Live-Betrieb.
 */
object DiagnosticsLogImporter {

    // Spaltennamen im DiagnosticsLogger-CSV-Header (siehe dort). Bewusst per
    // Name statt per festem Index aufgelöst (22.09.2026 umgestellt, siehe
    // docs/Erweiterung_Windschaetzung_Robust.md) — die alte Index-Fassung
    // brach beim Hinzufügen der neuen `wind_sample_count`-Spalte (jeder
    // Index danach wäre um 1 verschoben gewesen), und genau dieses Muster
    // ("neue Spalte in der Mitte eingefügt") ist seit Projektbeginn schon
    // mehrmals vorgekommen. Per Name funktioniert unabhängig davon, ob eine
    // CSV im alten oder neuen Format vorliegt (die beiden Logs vom 15./16.08.
    // haben `wind_sample_count` z.B. noch nicht).
    private const val COL_TS_EPOCH_MS = "ts_epoch_ms"
    private const val COL_LAT = "lat"
    private const val COL_LON = "lon"
    private const val COL_SOG_KN = "sog_kn"
    private const val COL_COG_DEG = "cog_deg"
    private const val COL_GPS_VALID = "gps_valid"
    private const val COL_WIND_DIR_DEG = "wind_dir_deg"
    private const val COL_WATCH_CONNECTED = "watch_connected"
    private const val COL_OPERATION_MODE = "operation_mode"
    private const val COL_COMPETITION_ACTIVE = "competition_active"
    private const val COL_TRAIN_MODE = "train_mode"

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

        val header = parseCsvLine(lines[0])
        val colIndex = header.withIndex().associate { (i, name) -> name to i }
        val requiredCols = listOf(
            COL_TS_EPOCH_MS, COL_LAT, COL_LON, COL_SOG_KN, COL_COG_DEG,
            COL_GPS_VALID, COL_WIND_DIR_DEG, COL_WATCH_CONNECTED, COL_OPERATION_MODE,
            COL_COMPETITION_ACTIVE, COL_TRAIN_MODE,
        )
        // Fehlt eine Pflichtspalte (z.B. eine ganz andere Datei), lieber sauber
        // abbrechen als mit falschen/verschobenen Werten weiterrechnen.
        if (requiredCols.any { it !in colIndex }) return null
        val minColumns = requiredCols.maxOf { colIndex.getValue(it) } + 1

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
            if (cols.size < minColumns) continue
            val tsMs = cols[colIndex.getValue(COL_TS_EPOCH_MS)].toLongOrNull() ?: continue
            val lat = cols[colIndex.getValue(COL_LAT)].toDoubleOrNull()
            val lon = cols[colIndex.getValue(COL_LON)].toDoubleOrNull()
            val sog = cols[colIndex.getValue(COL_SOG_KN)].toDoubleOrNull()
            val cog = cols[colIndex.getValue(COL_COG_DEG)].toDoubleOrNull()
            val valid = cols[colIndex.getValue(COL_GPS_VALID)] == "true"
            val windDir = cols[colIndex.getValue(COL_WIND_DIR_DEG)].toDoubleOrNull()
            val watchConnected = cols[colIndex.getValue(COL_WATCH_CONNECTED)] == "true"
            val opMode = if (cols[colIndex.getValue(COL_OPERATION_MODE)] == "WITH_WATCH") OperationMode.WITH_WATCH else OperationMode.STANDALONE
            // isRacing (siehe WindEngine.isPlausibleRacingLeg-Doku): fürs Nachspielen
            // exakt dasselbe Kriterium wie live in SegeluhrViewModel.tick() verwenden,
            // sonst würde ein Reimport Ausweich-/Bojenmanöver während der Regatta
            // anders (schlechter) filtern als es beim Original-Törn passiert ist.
            val isRacing = cols[colIndex.getValue(COL_COMPETITION_ACTIVE)] == "true" ||
                cols[colIndex.getValue(COL_TRAIN_MODE)] == "RACE"

            val fix = Fix(lat, lon, cog, sog, tsMs, null, valid)

            if (!windSeeded && windDir != null) {
                windEngine.restore(windDir, true)
                windSeeded = true
            }
            if (windSeeded) windEngine.tickContinuous(fix, target = null, isRacing = isRacing)
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
