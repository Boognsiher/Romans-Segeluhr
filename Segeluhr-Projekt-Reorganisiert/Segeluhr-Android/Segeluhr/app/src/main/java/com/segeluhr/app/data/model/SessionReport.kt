package com.segeluhr.app.data.model

import com.segeluhr.app.core.GeoPoint

/**
 * Ergebnis der automatischen Tages-Auswertung (Erweiterung, 17.08.2026,
 * siehe docs/Erweiterung_Tages_Auswertung.md) — wird bei App-Stopp aus
 * [com.segeluhr.app.logic.SessionSummaryEngine] gebaut und im Setup-Tab
 * als Dialog angezeigt. Pendant zum bisher manuellen CSV-Auswerten der
 * Diagnose-Logs (siehe PROJEKT_STATUS.md, 15./16.08.2026-Einträge), jetzt
 * direkt in der App statt in einer separaten Session danach.
 *
 * Deckt den gesamten Zeitraum seit dem letzten App-(Neu-)Start ab, auch
 * über mehrere Stopp/Start-Zyklen hinweg (z.B. Mittagspause) — siehe
 * SessionSummaryEngine-Klassendoku.
 */
data class SessionReport(
    // Persistenz-Id (siehe data/db/SessionDb.kt) - null, solange der Bericht
    // noch nicht gespeichert wurde (z.B. frisch aus SessionSummaryEngine.buildReport()).
    val id: Long? = null,
    val kind: SessionKind = SessionKind.DAY,
    val startedAtMs: Long,
    val durationS: Long,
    val distanceM: Double,
    val maxSpeedKn: Double,
    val avgSpeedKn: Double?,
    val tackCount: Int,
    val avgTackAngleDeg: Double?,
    val gybeCount: Int,
    val avgGybeAngleDeg: Double?,
    val windShiftCount: Int,
    val windShiftHeaderCount: Int,
    val windShiftLiftCount: Int,
    val windCalibrationCount: Int,
    val finalClosehauledAngleDeg: Double,
    val finalDownwindAngleDeg: Double,
    /** Anteil der Ticks mit verbundener Uhr, null falls nie im "Mit Uhr"-Betrieb. */
    val watchConnectedPct: Double?,
    /** Gefahrene Route, downgesampelt auf max. SessionSummaryEngine.ROUTE_MAX_POINTS Punkte (für Karte/PDF, siehe dort). */
    val route: List<GeoPoint>,
)
