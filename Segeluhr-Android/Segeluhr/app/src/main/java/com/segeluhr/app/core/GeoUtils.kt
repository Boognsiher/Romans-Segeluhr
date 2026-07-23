package com.segeluhr.app.core

import kotlin.math.*

/**
 * Formeln aus Abschnitt 9 der Segeluhr_Spezifikation.md — plattformunabhängig,
 * 1:1 aus dem Browser-Prototyp (segeluhr.html) übernommen.
 */
object GeoUtils {

    fun normalize360(d: Double): Double {
        var r = d % 360.0
        if (r < 0) r += 360.0
        return r
    }

    /** Kleinste signierte Differenz a-b, im Bereich -180..180 */
    fun angleDiff(a: Double, b: Double): Double {
        var d = a - b
        while (d > 180.0) d -= 360.0
        while (d < -180.0) d += 360.0
        return d
    }

    fun toRad(d: Double): Double = d * PI / 180.0
    fun toDeg(r: Double): Double = r * 180.0 / PI

    /** Zirkulärer Mittelwert einer Liste von Gradwerten */
    fun circularMean(values: List<Double>): Double {
        var sx = 0.0
        var sy = 0.0
        for (v in values) {
            sx += cos(toRad(v))
            sy += sin(toRad(v))
        }
        return normalize360(toDeg(atan2(sy, sx)))
    }

    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = toRad(lat1)
        val p2 = toRad(lat2)
        val dl = toRad(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalize360(toDeg(atan2(y, x)))
    }

    /** Haversine-Distanz in Metern */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val p1 = toRad(lat1)
        val p2 = toRad(lat2)
        val dphi = toRad(lat2 - lat1)
        val dl = toRad(lon2 - lon1)
        val a = sin(dphi / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun clamp(v: Double, lo: Double, hi: Double): Double = max(lo, min(hi, v))

    /**
     * Berechnet einen Zielpunkt aus Startposition, Peilung und Distanz
     * (Kehrwert zu bearingDeg). Wird für die virtuelle Luv-/Leetonnen-
     * Schätzung im Racemode ohne gesetzte Bojen gebraucht — dort kennen wir
     * nur die Richtung (Windrichtung bzw. deren Kehrwert), keine echten
     * GPS-Koordinaten, brauchen aber trotzdem einen "Punkt" für die
     * Wind-Shift-Bewertung (Header/Lift).
     */
    fun projectPoint(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): GeoPoint {
        val distRatio = distanceM / 6371000.0
        val bearingRad = toRad(bearingDeg)
        val lat1 = toRad(lat)
        val lon1 = toRad(lon)
        val lat2 = asin(sin(lat1) * cos(distRatio) + cos(lat1) * sin(distRatio) * cos(bearingRad))
        val lon2 = lon1 + atan2(
            sin(bearingRad) * sin(distRatio) * cos(lat1),
            cos(distRatio) - sin(lat1) * sin(lat2),
        )
        return GeoPoint(toDeg(lat2), toDeg(lon2))
    }

    fun fmtDeg(v: Double?): String = if (v == null) "--" else "${normalize360(v).roundToInt()}°"

    fun fmtDist(m: Double?): String {
        if (m == null) return "--"
        return if (m >= 1000) "%.2f km".format(m / 1000) else "${m.roundToInt()} m"
    }

    /** Formatiert eine Dauer in Sekunden als "1h 23min" bzw. "23min" für die ETA-Anzeige */
    fun fmtDuration(seconds: Double?): String {
        if (seconds == null || seconds.isNaN() || seconds.isInfinite() || seconds < 0) return "--"
        val totalMin = (seconds / 60.0).roundToInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}h ${m}min" else "${m}min"
    }
}

/** Einfacher Geo-Punkt (Wegpunkt) */
data class GeoPoint(val lat: Double, val lon: Double)

/** Ein GPS-Fix, egal ob vom internen Sensor oder via BLE vom gekoppelten Handy/Uhr */
data class Fix(
    val lat: Double? = null,
    val lon: Double? = null,
    val cogDeg: Double? = null,   // Course over Ground, null = ungültig
    val sogKn: Double? = null,    // Speed over Ground in Knoten
    val timestampMs: Long = 0L,
    val accuracyM: Float? = null,
    val valid: Boolean = false,
)
