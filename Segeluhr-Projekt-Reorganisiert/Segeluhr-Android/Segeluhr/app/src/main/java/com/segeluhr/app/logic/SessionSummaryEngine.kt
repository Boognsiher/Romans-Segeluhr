package com.segeluhr.app.logic

import com.segeluhr.app.core.Fix
import com.segeluhr.app.core.GeoPoint
import com.segeluhr.app.core.GeoUtils
import com.segeluhr.app.data.model.OperationMode
import com.segeluhr.app.data.model.SessionKind
import com.segeluhr.app.data.model.SessionReport

/**
 * Erweiterung (17.08.2026, siehe docs/Erweiterung_Tages_Auswertung.md):
 * zeichnet während der laufenden 1-Hz-Tick-Schleife einen leichtgewichtigen
 * GPS-Track auf (Zeit/Position/Speed/Uhr-Verbindung) und baut daraus
 * zusammen mit [WindEngine] (Wenden/Halsen, Wind-Shifts, Windkalibrierung —
 * beide mit Zeitstempel, siehe dort) auf Abruf einen fertigen
 * [SessionReport] für ein beliebiges Zeitfenster — Pendant zum bisher
 * manuellen CSV-Auswerten der Diagnose-Logs (siehe PROJEKT_STATUS.md,
 * 15./16.08.2026-Einträge), jetzt direkt in der App.
 *
 * Ein Objekt pro ViewModel-Lebensdauer (wie
 * [com.segeluhr.app.data.diagnostics.DiagnosticsLogger]) — deckt damit
 * denselben Zeitraum ab wie die zugehörige Diagnose-CSV-Datei. Wird bei
 * [SegeluhrViewModel.stopApp]/[SegeluhrViewModel.startApp] bewusst NICHT
 * zurückgesetzt, bleibt also über mehrere Stopp/Start-Zyklen (z.B.
 * Mittagspause) hinweg ein Track für den ganzen Tag — sowohl der
 * Tages-Bericht als auch ein einzelner Wettfahrt-Bericht (schmaleres
 * Zeitfenster, siehe [buildReport]) werden aus demselben Track geschnitten,
 * kein zweiter, parallel laufender Aufzeichnungsweg nötig.
 */
class SessionSummaryEngine {

    private data class TrackSample(
        val timestampMs: Long,
        val lat: Double,
        val lon: Double,
        val sogKn: Double?,
        val watchConnected: Boolean,
        val operationMode: OperationMode,
    )

    private val track = mutableListOf<TrackSample>()

    /**
     * Bei jedem 1-Hz-Tick aufrufen, unabhängig vom Diagnose-Log-Schalter
     * (leichtgewichtig, keine Datei-I/O). Zeitstempel kommt bewusst aus
     * [Fix.timestampMs], nicht aus `System.currentTimeMillis()` — für den
     * Live-Betrieb praktisch identisch (Fix wird direkt bei Ankunft
     * gestempelt, Tick läuft sofort danach), aber beim CSV-Import (siehe
     * DiagnosticsLogImporter) macht das den entscheidenden Unterschied: die
     * Session trägt dann die ECHTEN Log-Zeiten statt der Import-
     * Ausführungszeit (sonst würde jeder Import "jetzt, 0 Minuten Dauer"
     * anzeigen statt der tatsächlichen Törn-Zeit/-Dauer).
     */
    fun onTick(fix: Fix, watchConnected: Boolean, operationMode: OperationMode) {
        val lat = fix.lat
        val lon = fix.lon
        if (!fix.valid || lat == null || lon == null) return
        track.add(TrackSample(fix.timestampMs, lat, lon, fix.sogKn, watchConnected, operationMode))
    }

    /**
     * Baut einen [SessionReport] aus dem seit [fromMs] aufgezeichneten Track
     * (Default: seit dem allerersten Sample, also der ganze bisherige Tag).
     * [toMs] Default: jetzt. Null, solange in diesem Fenster kein einziger
     * valider Fix aufgezeichnet wurde.
     */
    fun buildReport(
        windEngine: WindEngine,
        kind: SessionKind = SessionKind.DAY,
        fromMs: Long? = null,
        toMs: Long = System.currentTimeMillis(),
    ): SessionReport? {
        val from = fromMs ?: track.firstOrNull()?.timestampMs ?: return null
        val slice = track.filter { it.timestampMs in from..toMs }
        if (slice.isEmpty()) return null

        var distanceM = 0.0
        var maxSpeedKn = 0.0
        var speedSumKn = 0.0
        var speedSampleCount = 0
        var watchTicks = 0
        var watchConnectedTicks = 0
        var prev: TrackSample? = null
        for (s in slice) {
            val sog = s.sogKn
            if (sog != null && sog >= 0.0) {
                if (sog > maxSpeedKn) maxSpeedKn = sog
                speedSumKn += sog
                speedSampleCount++
            }
            if (s.operationMode == OperationMode.WITH_WATCH) {
                watchTicks++
                if (s.watchConnected) watchConnectedTicks++
            }
            prev?.let { p -> distanceM += GeoUtils.distanceMeters(p.lat, p.lon, s.lat, s.lon) }
            prev = s
        }

        val maneuvers = windEngine.sessionManeuvers.filter { it.timestampMs in from..toMs }
        val tackAngles = maneuvers.filter { it.isTack }.map { it.angleDeg }
        val gybeAngles = maneuvers.filter { !it.isTack }.map { it.angleDeg }
        val shifts = windEngine.sessionWindShifts.filter { it.timestampMs in from..toMs }
        val calibrations = windEngine.sessionCalibrations.count { it.timestampMs in from..toMs }

        return SessionReport(
            kind = kind,
            startedAtMs = slice.first().timestampMs,
            durationS = ((slice.last().timestampMs - slice.first().timestampMs) / 1000).coerceAtLeast(0),
            distanceM = distanceM,
            maxSpeedKn = maxSpeedKn,
            avgSpeedKn = if (speedSampleCount > 0) speedSumKn / speedSampleCount else null,
            tackCount = tackAngles.size,
            avgTackAngleDeg = tackAngles.averageOrNull(),
            gybeCount = gybeAngles.size,
            avgGybeAngleDeg = gybeAngles.averageOrNull(),
            windShiftCount = shifts.size,
            windShiftHeaderCount = shifts.count { it.isHeader == true },
            windShiftLiftCount = shifts.count { it.isHeader == false },
            windCalibrationCount = calibrations,
            finalClosehauledAngleDeg = windEngine.closehauledAngleDeg,
            finalDownwindAngleDeg = windEngine.downwindAngleDeg,
            watchConnectedPct = if (watchTicks > 0) 100.0 * watchConnectedTicks / watchTicks else null,
            route = downsample(slice.map { GeoPoint(it.lat, it.lon) }),
        )
    }

    /** Für die Karte/PDF reicht eine grobe Auflösung — hält die Route-JSON in der DB und im PDF-Renderer klein, auch bei mehrstündigen Sessions. */
    private fun downsample(points: List<GeoPoint>): List<GeoPoint> {
        if (points.size <= ROUTE_MAX_POINTS) return points
        val step = points.size.toDouble() / ROUTE_MAX_POINTS
        return (0 until ROUTE_MAX_POINTS).map { points[(it * step).toInt()] } + points.last()
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    companion object {
        const val ROUTE_MAX_POINTS = 2000
    }
}
