package com.segeluhr.app.logic

import com.segeluhr.app.core.Constants
import com.segeluhr.app.core.Fix
import com.segeluhr.app.core.GeoUtils
import com.segeluhr.app.core.HapticFeedback
import com.segeluhr.app.core.LakeCircle
import com.segeluhr.app.data.model.TrainMode

/**
 * See-Geofence, Abschnitt 6.5. Nur aktiv, während Training läuft. Warnung
 * ab 80% des Radius, wiederholt sich alle 15s, Reset erst unter 65%
 * (Hysterese gegen Dauer-Alarm am Rand).
 *
 * ERWEITERUNG (Roman-Entscheidung 06.08.2026, siehe
 * docs/Erweiterung_Automatische_See_Erkennung.md): statt einem einzelnen
 * Mittelpunkt+Radius jetzt eine KETTE von Kreisen — ein einzelner Kreis
 * deckt lange/unregelmässige Seen nicht ab, ohne an den Enden ständig
 * fälschlich zu warnen. Die Position gilt als sicher, sobald sie innerhalb
 * IRGENDEINES der Kreise liegt; die 80%/65%-Hysterese wird dafür auf den
 * BESTEN (kleinsten) Prozentwert über alle Kreise angewendet - siehe
 * [bestDistancePct]. Bei nur einem Kreis (z.B. manuell gesetzt) verhält
 * sich das identisch zum bisherigen Einzelkreis-Fall.
 */
class LakeGeofenceEngine(private val vib: HapticFeedback, private val status: StatusSink) {

    private var warnActive = false
    private var lastWarnAt = 0L

    fun tick(fix: Fix, trainMode: TrainMode, lakeCircles: List<LakeCircle>) {
        if (trainMode == TrainMode.OFF || lakeCircles.isEmpty()) return
        val pct = bestDistancePct(fix, lakeCircles) ?: return

        if (pct >= Constants.LAKE_WARN_FACTOR) {
            val now = System.currentTimeMillis()
            if (!warnActive || (now - lastWarnAt) >= Constants.LAKE_WARN_REPEAT_MS) {
                vib.lakeWarn5()
                lastWarnAt = now
                warnActive = true
                status.setStatus("See-Rand-Warnung!", StatusLevel.RED)
            }
        } else if (pct < Constants.LAKE_CLEAR_FACTOR) {
            warnActive = false
        }
    }

    /**
     * Bester (kleinster) Anteil des jeweiligen Kreisradius, den die aktuelle
     * Position gerade "verbraucht" - 0.0 = im Mittelpunkt eines Kreises,
     * 1.0 = genau am Rand seines besten Kreises. Über alle Kreise das
     * Minimum, da die Position sicher ist, sobald sie in EINEM Kreis
     * komfortabel drin liegt.
     */
    private fun bestDistancePct(fix: Fix, lakeCircles: List<LakeCircle>): Double? {
        val lat = fix.lat ?: return null
        val lon = fix.lon ?: return null
        return lakeCircles
            .filter { it.radiusM > 0 }
            .minOfOrNull { circle ->
                GeoUtils.distanceMeters(lat, lon, circle.center.lat, circle.center.lon) / circle.radiusM
            }
    }

    /** Abstand zum nächstgelegenen (besten) Kreis + Prozentwert für die Telemetrie-Anzeige */
    fun distancePct(fix: Fix, lakeCircles: List<LakeCircle>): Pair<Double, Double>? {
        val lat = fix.lat ?: return null
        val lon = fix.lon ?: return null
        val best = lakeCircles
            .filter { it.radiusM > 0 }
            .minByOrNull { circle -> GeoUtils.distanceMeters(lat, lon, circle.center.lat, circle.center.lon) / circle.radiusM }
            ?: return null
        val dist = GeoUtils.distanceMeters(lat, lon, best.center.lat, best.center.lon)
        return dist to (dist / best.radiusM * 100.0)
    }
}
