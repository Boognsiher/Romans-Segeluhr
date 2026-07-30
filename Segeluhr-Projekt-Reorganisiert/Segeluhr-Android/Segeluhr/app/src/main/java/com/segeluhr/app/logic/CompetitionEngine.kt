package com.segeluhr.app.logic

import com.segeluhr.app.core.*
import com.segeluhr.app.data.model.CompetitionGuidance
import com.segeluhr.app.data.model.CompetitionLeg
import kotlin.math.abs

/**
 * "Competition"-Modus fürs echte Rennen (Erweiterung, siehe
 * docs/Erweiterung_Competition_Modus.md). Startet automatisch bei
 * Countdown 0:00 (siehe StartCountdownEngine/ViewModel), läuft unabhängig
 * vom Trainings-Tab (TrainingEngine/TrainMode) — beide können parallel
 * aktiv sein, ohne sich zu stören.
 *
 * Kurs-Modell: Luvbake (mark1) -> optional kurzer Halbwind-Schlag zur
 * Entlastungsboje (mark2) -> Vorwind -> nächste Runde. Ist mark1 nicht
 * gesetzt, wird die Luvbake direkt gegen den Wind geschätzt; die Vorwind-
 * Etappe wird IMMER geschätzt (kein Leetonnen-Wegpunkt vorgesehen).
 *
 * Anders als beim Trainings-Racemode gibt es hier KEINE zufälligen
 * Wende/Halse-Kommandos — nur laufende Peilung/Distanz/Wende-Empfehlung
 * wie beim Heimweg-Modus (HomeEngine), weil taktische Entscheidungen im
 * echten Rennen beim Segler liegen, nicht auf einem Zufallstimer.
 */
class CompetitionEngine(private val vib: HapticFeedback, private val status: StatusSink) {

    companion object {
        /** Gleicher Am-Wind-Winkel-Ansatz wie im Heimweg-Modus (HomeEngine) */
        const val CLOSEHAULED_ANGLE_DEG = 45.0
    }

    var leg: CompetitionLeg = CompetitionLeg.UPWIND
        private set
    var lapCount: Int = 0
        private set

    private val legTracker = CourseTracker()
    private var lastSteady: Double? = null
    private var lastManeuverNeeded: Boolean? = null

    /** Beim automatischen Start des Competition-Modus (Countdown 0:00) aufrufen */
    fun activate() {
        leg = CompetitionLeg.UPWIND
        lapCount = 0
        legTracker.reset()
        lastSteady = null
        lastManeuverNeeded = null
    }

    fun tick(fix: Fix, windDir: Double?, mark1: GeoPoint?, mark2: GeoPoint?): CompetitionGuidance? {
        val lat = fix.lat ?: return null
        val lon = fix.lon ?: return null
        val wd = windDir ?: return null

        // Sicherheitsnetz: wurde die Entlastungsboje zwischenzeitlich wieder
        // gelöscht, während wir genau auf dem Weg dorthin waren -> direkt
        // weiter zu Vorwind, statt mit einer nicht mehr vorhandenen Boje zu rechnen.
        if (leg == CompetitionLeg.REACH_TO_OFFSET && mark2 == null) {
            leg = CompetitionLeg.DOWNWIND
            legTracker.reset()
            lastSteady = null
        }

        return when (leg) {
            CompetitionLeg.UPWIND -> tickUpwind(fix, lat, lon, wd, mark1, mark2)
            CompetitionLeg.REACH_TO_OFFSET -> tickReach(lat, lon, mark2!!)
            CompetitionLeg.DOWNWIND -> tickDownwind(fix, lat, lon, wd)
        }
    }

    /** Gleiche Am-Wind-Logik wie HomeEngine: direkter Kurs oder besserer Kreuz-Kurs + Wende-Bedarf */
    private fun closehauledGuidance(fix: Fix, wd: Double, bearingToTarget: Double): Pair<Double, Boolean> {
        val angleToWind = GeoUtils.angleDiff(bearingToTarget, wd)
        val canSailDirect = abs(angleToWind) >= CLOSEHAULED_ANGLE_DEG
        if (canSailDirect) return bearingToTarget to false

        val ch1 = GeoUtils.normalize360(wd - CLOSEHAULED_ANGLE_DEG)
        val ch2 = GeoUtils.normalize360(wd + CLOSEHAULED_ANGLE_DEG)
        val recommended = if (abs(GeoUtils.angleDiff(ch1, bearingToTarget)) <=
            abs(GeoUtils.angleDiff(ch2, bearingToTarget))
        ) ch1 else ch2

        val cog = fix.cogDeg ?: return recommended to false
        val currentTackSign = if (GeoUtils.angleDiff(cog, wd) > 0) 1 else -1
        val recommendedTackSign = if (GeoUtils.angleDiff(recommended, wd) > 0) 1 else -1
        return recommended to (currentTackSign != recommendedTackSign)
    }

    private fun maybeVibrateManeuver(maneuverNeeded: Boolean, label: String) {
        if (maneuverNeeded && lastManeuverNeeded != true) {
            vib.maneuverCmd()
            status.setStatus("Wende Richtung $label empfehlenswert!", StatusLevel.AMBER)
        }
        lastManeuverNeeded = maneuverNeeded
    }

    private fun tickUpwind(fix: Fix, lat: Double, lon: Double, wd: Double, mark1: GeoPoint?, mark2: GeoPoint?): CompetitionGuidance {
        val bearing: Double
        val distance: Double?
        val isEstimated: Boolean

        if (mark1 != null) {
            bearing = GeoUtils.bearingDeg(lat, lon, mark1.lat, mark1.lon)
            distance = GeoUtils.distanceMeters(lat, lon, mark1.lat, mark1.lon)
            isEstimated = false

            // Rundung: Distanz- ODER Manöver-basiert (wie beim Trainings-Racemode)
            legTracker.sample(fix.cogDeg, fix.lat, fix.lon, fix.sogKn)
            val steady = legTracker.steady(Constants.RACE_COURSE_MAX_DEV)
            var roundedByManeuver = false
            if (steady != null) {
                val last = lastSteady
                if (last == null) {
                    lastSteady = steady
                } else {
                    if (abs(GeoUtils.angleDiff(steady, last)) >= Constants.RACE_TURN_MIN_DEG) roundedByManeuver = true
                    lastSteady = steady
                }
            }
            if (distance <= Constants.ROUNDING_RADIUS_M || roundedByManeuver) {
                advanceLegAfterUpwind(mark2 != null)
            }
        } else {
            bearing = wd
            distance = null
            isEstimated = true

            legTracker.sample(fix.cogDeg, fix.lat, fix.lon, fix.sogKn)
            val steady = legTracker.steady(Constants.RACE_COURSE_MAX_DEV)
            if (steady != null && abs(GeoUtils.angleDiff(steady, wd)) >= 90.0) {
                advanceLegAfterUpwind(false) // ohne Luvbake auch keine Entlastungsboje sinnvoll ansteuerbar
            }
        }

        val (recommended, maneuverNeeded) = closehauledGuidance(fix, wd, bearing)
        maybeVibrateManeuver(maneuverNeeded, if (isEstimated) "Luvtonne (geschätzt)" else "Luvbake")

        return CompetitionGuidance(
            leg = CompetitionLeg.UPWIND,
            label = if (isEstimated) "Luvtonne (geschätzt)" else "Luvbake",
            bearing = bearing,
            distanceM = distance,
            recommendedHeading = recommended,
            maneuverNeeded = maneuverNeeded,
            isEstimated = isEstimated,
            lapCount = lapCount,
        )
    }

    private fun advanceLegAfterUpwind(hasOffsetMark: Boolean) {
        vib.rounding6()
        legTracker.reset()
        lastSteady = null
        lastManeuverNeeded = null
        if (hasOffsetMark) {
            leg = CompetitionLeg.REACH_TO_OFFSET
            status.setStatus("Luvbake gerundet — weiter zur Entlastungsboje!", StatusLevel.GREEN)
        } else {
            leg = CompetitionLeg.DOWNWIND
            status.setStatus("Luvbake gerundet — Vorwind-Schlag!", StatusLevel.GREEN)
        }
    }

    private fun tickReach(lat: Double, lon: Double, mark2: GeoPoint): CompetitionGuidance {
        val bearing = GeoUtils.bearingDeg(lat, lon, mark2.lat, mark2.lon)
        val distance = GeoUtils.distanceMeters(lat, lon, mark2.lat, mark2.lon)

        if (distance <= Constants.ROUNDING_RADIUS_M) {
            vib.rounding6()
            legTracker.reset()
            lastSteady = null
            leg = CompetitionLeg.DOWNWIND
            status.setStatus("Entlastungsboje gerundet — Vorwind-Schlag!", StatusLevel.GREEN)
        }

        return CompetitionGuidance(
            leg = CompetitionLeg.REACH_TO_OFFSET,
            label = "Entlastungsboje (Halbwind)",
            bearing = bearing,
            distanceM = distance,
            recommendedHeading = bearing,
            maneuverNeeded = false,
            isEstimated = false,
            lapCount = lapCount,
        )
    }

    private fun tickDownwind(fix: Fix, lat: Double, lon: Double, wd: Double): CompetitionGuidance {
        val bearing = GeoUtils.normalize360(wd + 180)

        legTracker.sample(fix.cogDeg, fix.lat, fix.lon, fix.sogKn)
        val steady = legTracker.steady(Constants.RACE_COURSE_MAX_DEV)
        if (steady != null && abs(GeoUtils.angleDiff(steady, wd)) < 90.0) {
            vib.rounding6()
            legTracker.reset()
            lastSteady = null
            lastManeuverNeeded = null
            lapCount++
            leg = CompetitionLeg.UPWIND
            status.setStatus("Leetonne (geschätzt) gerundet — nächste Runde!", StatusLevel.GREEN)
        }

        return CompetitionGuidance(
            leg = CompetitionLeg.DOWNWIND,
            label = "Leetonne (geschätzt)",
            bearing = bearing,
            distanceM = null,
            recommendedHeading = bearing,
            maneuverNeeded = false,
            isEstimated = true,
            lapCount = lapCount,
        )
    }

    /** Referenzpunkt für die Wind-Shift-Bewertung (Header/Lift, Abschnitt 4.2) */
    fun windShiftReferencePoint(fix: Fix, windDir: Double?, mark1: GeoPoint?, mark2: GeoPoint?): GeoPoint? {
        val lat = fix.lat ?: return null
        val lon = fix.lon ?: return null
        val wd = windDir ?: return null
        return when (leg) {
            CompetitionLeg.UPWIND -> mark1 ?: GeoUtils.projectPoint(lat, lon, wd, 2000.0)
            CompetitionLeg.REACH_TO_OFFSET -> mark2
            CompetitionLeg.DOWNWIND -> GeoUtils.projectPoint(lat, lon, GeoUtils.normalize360(wd + 180), 2000.0)
        }
    }
}
