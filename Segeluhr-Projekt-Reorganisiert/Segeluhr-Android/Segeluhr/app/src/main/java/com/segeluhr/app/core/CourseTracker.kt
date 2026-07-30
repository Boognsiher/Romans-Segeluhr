package com.segeluhr.app.core

/**
 * "Ruhiger-Kurs"-Tracker, Abschnitt 3 der Spezifikation.
 *
 * WICHTIG: Jede Nutzung (Kalibrierung, kontinuierliches Wind-Tracking,
 * Training, Bojen-Navigation) braucht eine EIGENE, unabhängige Instanz —
 * sie dürfen sich nicht gegenseitig den Puffer leeren, da sie parallel laufen.
 */
class CourseTracker(private val windowSize: Int = Constants.WINDOW_SIZE) {

    private val buffer = ArrayDeque<Double>()
    private var firstFix: GeoPoint? = null
    private var lastFix: GeoPoint? = null

    fun reset() {
        buffer.clear()
        firstFix = null
        lastFix = null
    }

    /**
     * Alle 1s aufrufen. Bei gültigem Fix + Mindestgeschwindigkeit wird der
     * COG-Wert angehängt, sonst wird der Puffer verworfen.
     */
    fun sample(
        cog: Double?,
        lat: Double?,
        lon: Double?,
        sogKn: Double?,
        minSpeed: Double = Constants.MIN_SPEED_KN,
    ) {
        if (cog == null || sogKn == null || sogKn < minSpeed || lat == null || lon == null) {
            reset()
            return
        }
        if (buffer.isEmpty()) {
            firstFix = GeoPoint(lat, lon)
        }
        buffer.addLast(cog)
        if (buffer.size > windowSize) buffer.removeFirst()
        lastFix = GeoPoint(lat, lon)
    }

    /**
     * Liefert den ruhigen Durchschnittskurs, oder null wenn (noch) nicht ruhig.
     */
    fun steady(maxDevDeg: Double, posCheck: Boolean = true): Double? {
        if (buffer.size < windowSize) return null
        val avg = GeoUtils.circularMean(buffer.toList())
        var maxDev = 0.0
        for (v in buffer) {
            val d = kotlin.math.abs(GeoUtils.angleDiff(v, avg))
            if (d > maxDev) maxDev = d
        }
        if (maxDev > maxDevDeg) return null

        if (posCheck) {
            val ff = firstFix
            val lf = lastFix
            if (ff != null && lf != null) {
                val dist = GeoUtils.distanceMeters(ff.lat, ff.lon, lf.lat, lf.lon)
                if (dist >= Constants.POSITION_CHECK_MIN_DIST_M) {
                    val posBearing = GeoUtils.bearingDeg(ff.lat, ff.lon, lf.lat, lf.lon)
                    if (kotlin.math.abs(GeoUtils.angleDiff(posBearing, avg)) > Constants.POSITION_CHECK_MAX_DEV_DEG) {
                        return null
                    }
                }
            }
        }
        return avg
    }

    val size: Int get() = buffer.size
}
