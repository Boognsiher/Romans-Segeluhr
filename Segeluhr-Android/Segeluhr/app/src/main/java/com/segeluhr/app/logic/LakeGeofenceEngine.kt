package com.segeluhr.app.logic

import com.segeluhr.app.core.Constants
import com.segeluhr.app.core.Fix
import com.segeluhr.app.core.GeoPoint
import com.segeluhr.app.core.GeoUtils
import com.segeluhr.app.core.HapticFeedback
import com.segeluhr.app.data.model.TrainMode

/**
 * See-Geofence, Abschnitt 6.5. Nur aktiv, während Training läuft. Warnung
 * ab 80% des Radius, wiederholt sich alle 15s, Reset erst unter 65%
 * (Hysterese gegen Dauer-Alarm am Rand).
 */
class LakeGeofenceEngine(private val vib: HapticFeedback, private val status: StatusSink) {

    private var warnActive = false
    private var lastWarnAt = 0L

    fun tick(fix: Fix, trainMode: TrainMode, lakeCenter: GeoPoint?, lakeRadius: Double?) {
        if (trainMode == TrainMode.OFF || lakeCenter == null || lakeRadius == null) return
        val lat = fix.lat ?: return
        val lon = fix.lon ?: return

        val dist = GeoUtils.distanceMeters(lat, lon, lakeCenter.lat, lakeCenter.lon)
        val warnThresh = Constants.LAKE_WARN_FACTOR * lakeRadius
        val clearThresh = Constants.LAKE_CLEAR_FACTOR * lakeRadius

        if (dist >= warnThresh) {
            val now = System.currentTimeMillis()
            if (!warnActive || (now - lastWarnAt) >= Constants.LAKE_WARN_REPEAT_MS) {
                vib.lakeWarn5()
                lastWarnAt = now
                warnActive = true
                status.setStatus("See-Rand-Warnung!", StatusLevel.RED)
            }
        } else if (dist < clearThresh) {
            warnActive = false
        }
    }

    /** Aktueller Abstand + Prozentwert für die Telemetrie-Anzeige */
    fun distancePct(fix: Fix, lakeCenter: GeoPoint?, lakeRadius: Double?): Pair<Double, Double>? {
        val lat = fix.lat ?: return null
        val lon = fix.lon ?: return null
        if (lakeCenter == null || lakeRadius == null || lakeRadius <= 0) return null
        val dist = GeoUtils.distanceMeters(lat, lon, lakeCenter.lat, lakeCenter.lon)
        return dist to (dist / lakeRadius * 100.0)
    }
}
