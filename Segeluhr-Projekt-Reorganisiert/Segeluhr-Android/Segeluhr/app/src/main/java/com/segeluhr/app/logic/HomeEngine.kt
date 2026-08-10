package com.segeluhr.app.logic

import com.segeluhr.app.core.*
import com.segeluhr.app.data.model.HomeGuidance
import kotlin.math.abs

/**
 * Erweiterung "Nach Hause gehen" (nicht Teil der ursprünglichen Spezifikation,
 * siehe docs/Erweiterung_Heimweg.md). Ähnlich der Racemode-Bojen-Peilung
 * (Ziel anpeilen, Abstand anzeigen), aber zusätzlich mit:
 *  - Wende-Vorschlag, falls der direkte Kurs zum Ziel zu dicht am Wind liegt
 *    (innerhalb des Anluv-Limits, Standard 45°) und daher gekreuzt werden muss
 *  - ETA über die tatsächlich gemessene Annäherung Richtung Ziel (VMC,
 *    "Velocity Made Good" — siehe [HomeProgressTracker])
 *
 * Bewusst NICHT über die zufällige Trainings-FSM (WAITING/COMMANDED/TURNING)
 * gelöst — hier geht es um dauerhafte Navigationsunterstützung, nicht um
 * Manöver-Drills mit zufälligem Timing.
 */
class HomeEngine(private val vib: HapticFeedback, private val status: StatusSink) {

    companion object {
        /** Typischer Am-Wind-Winkel; darunter gilt der direkte Kurs als nicht anliegend segelbar */
        const val CLOSEHAULED_ANGLE_DEG = 45.0
    }

    private var lastManeuverNeeded: Boolean? = null
    private var lastHome: GeoPoint? = null

    // Offener Punkt vom 10.08.2026 (siehe docs/Erweiterung_Heimweg.md): die
    // vorherige VMC-Berechnung aus dem Momentan-Kurs reagierte zu direkt auf
    // Kurs-Zacken während einer Wende. Ersetzt durch eine über ein
    // Zeitfenster gemessene tatsächliche Annäherung, siehe dortige
    // Klassen-Doku.
    private val progressTracker = HomeProgressTracker()

    /** Beim Aktivieren/Deaktivieren des Heimweg-Modus aufrufen, damit der erste Tick nicht sofort vibriert */
    fun reset() {
        lastManeuverNeeded = null
        progressTracker.reset()
    }

    fun tick(fix: Fix, windDir: Double?, windCalibrated: Boolean, home: GeoPoint?): HomeGuidance? {
        val lat = fix.lat ?: return null
        val lon = fix.lon ?: return null
        if (home == null) return null

        // Heimatpunkt im Setup-Tab geändert, während der Modus aktiv blieb -
        // alte Distanz-Historie bezog sich aufs vorherige Ziel und würde die
        // gemessene Annäherung sonst verfälschen.
        if (home != lastHome) {
            progressTracker.reset()
            lastManeuverNeeded = null
            lastHome = home
        }

        val bearingToHome = GeoUtils.bearingDeg(lat, lon, home.lat, home.lon)
        val distanceM = GeoUtils.distanceMeters(lat, lon, home.lat, home.lon)

        // Träge/geglättete VMC statt Momentan-Kurs (siehe HomeProgressTracker-
        // Doku). null (noch nicht genug Historie) fällt bewusst auf 0.0
        // zurück — das führt über den bestehenden etaFrom()-Pfad ganz regulär
        // zu "ETA: unbekannt", genau wie bei "kein Fortschritt Richtung Ziel".
        progressTracker.sample(fix.timestampMs, distanceM)
        val vmcKn = progressTracker.averageVmcKn() ?: 0.0
        val etaSeconds = etaFrom(distanceM, vmcKn)

        // Ohne kalibrierten Wind kein Wende-Vorschlag möglich — nur Peilung/Distanz/ETA über direkten Kurs
        if (!windCalibrated || windDir == null) {
            lastManeuverNeeded = null
            return HomeGuidance(bearingToHome, distanceM, bearingToHome, false, vmcKn, etaSeconds)
        }

        val angleToWind = GeoUtils.angleDiff(bearingToHome, windDir)
        val canSailDirect = abs(angleToWind) >= CLOSEHAULED_ANGLE_DEG

        val recommendedHeading: Double
        var maneuverNeeded = false

        if (canSailDirect) {
            recommendedHeading = bearingToHome
        } else {
            val closehauled1 = GeoUtils.normalize360(windDir - CLOSEHAULED_ANGLE_DEG)
            val closehauled2 = GeoUtils.normalize360(windDir + CLOSEHAULED_ANGLE_DEG)
            recommendedHeading = if (abs(GeoUtils.angleDiff(closehauled1, bearingToHome)) <=
                abs(GeoUtils.angleDiff(closehauled2, bearingToHome))
            ) closehauled1 else closehauled2

            val cog = fix.cogDeg
            if (cog != null) {
                val currentTackSign = if (GeoUtils.angleDiff(cog, windDir) > 0) 1 else -1
                val recommendedTackSign = if (GeoUtils.angleDiff(recommendedHeading, windDir) > 0) 1 else -1
                maneuverNeeded = currentTackSign != recommendedTackSign
            }
        }

        // Nur beim WECHSEL vibrieren (neu empfohlen), nicht dauerhaft bei jedem Tick
        if (maneuverNeeded && lastManeuverNeeded != true) {
            vib.maneuverCmd()
            status.setStatus("Wende Richtung Heimweg empfehlenswert!", StatusLevel.AMBER)
        }
        lastManeuverNeeded = maneuverNeeded

        return HomeGuidance(bearingToHome, distanceM, recommendedHeading, maneuverNeeded, vmcKn, etaSeconds)
    }

    private fun etaFrom(distanceM: Double, vmcKn: Double): Double? {
        if (vmcKn <= 0.05) return null // kein/kaum Fortschritt Richtung Ziel -> keine seriöse ETA
        val distanceNm = distanceM / 1852.0
        val hours = distanceNm / vmcKn
        return hours * 3600.0
    }
}
