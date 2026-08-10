package com.segeluhr.app.logic

import com.segeluhr.app.core.Constants
import com.segeluhr.app.core.CourseTracker
import com.segeluhr.app.core.Fix
import com.segeluhr.app.core.GeoPoint
import com.segeluhr.app.core.GeoUtils
import kotlin.math.abs

/**
 * Vereinheitlichte Bojen-/Marken-Rundungserkennung (10.08.2026, siehe
 * docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md) — ersetzt die bisher
 * je nach Modus UNTERSCHIEDLICHE Logik:
 *  - `TrainingEngine` (Race-Modus): Kurswechsel ≥25° + zielt auf die andere
 *    Boje
 *  - `CompetitionEngine` mit gesetzter Luvbake: Distanz ODER beliebiger
 *    Kurswechsel
 *  - `CompetitionEngine` ohne Luvbake ("geschätzt"): reiner
 *    Windwinkel-Übergang (>90°/<90° zum Wind)
 *
 * durch EINE gemeinsame Logik (Roman-Wunsch 10.08.2026, "ich will in allen
 * Modi die gleiche Logik"):
 *
 * 1. **Distanz zur gesetzten Marke IMMER zuerst geprüft, unabhängig vom
 *    Windwinkel** — innerhalb [Constants.ROUNDING_RADIUS_M] (20m) gilt als
 *    gerundet ("wenn eine Boje gesetzt wird, zählt diese").
 * 2. **Amwind/Vorwind-Kurswechsel** (ruhiger Kurs — [CourseTracker] —
 *    quert die 90°-Linie zum Wind):
 *    - **Keine Marke gesetzt** → die aktuelle Position wird automatisch als
 *      virtuelle Marke "gesetzt", sofort gerundet ([Result.AutoRounded]).
 *    - **Marke gesetzt, aber der Kurswechsel passiert NICHT dort** (Distanz
 *      >20m, aber ≤[Constants.ROUNDING_CONFIRM_RADIUS_M] = 150m) →
 *      mehrdeutig: könnte die gemeinte Boje sein (falsch gesetzt/Boje
 *      driftet), könnte aber auch ein normales Manöver aus anderem Grund
 *      sein → [Result.NeedsConfirmation], KEINE automatische Aktion.
 *    - **Weiter als 150m entfernt** → ignoriert, ganz normale Wende/Halse
 *      mitten auf dem Schlag, keine Rückmeldung.
 *
 * Braucht kalibrierten Wind — ohne Wind ist "Amwind/Vorwind" nicht
 * definiert, dann greift NUR die reine Distanzprüfung zur gesetzten Marke
 * (Punkt 1), kein Auto-Erkennen/keine Bestätigungs-Anfrage ohne Marke.
 *
 * Eine Instanz pro "Rundungs-Kontext" (z.B. eine für den Trainings-
 * Racemode, eine für die Competition-Luvbake) — der interne Zustand
 * (Kurs-Tracker, zuletzt bekannte Amwind/Vorwind-Seite) darf nicht
 * zwischen unabhängigen Kontexten geteilt werden.
 */
class MarkRoundingDetector {
    private val tracker = CourseTracker()

    /** -1 = Amwind-Seite (< 90° zum Wind), +1 = Vorwind-Seite (>= 90°), null = noch nicht ermittelt. */
    private var lastSide: Int? = null

    sealed class Result {
        /** Nichts Neues — weder gerundet noch ein meldenswerter Kurswechsel. */
        object None : Result()

        /** Innerhalb des Rundungsradius der gesetzten Marke — zählt sofort, keine Rückfrage nötig. */
        object Rounded : Result()

        /** Windwinkel-Übergang ohne gesetzte Marke — Position wird automatisch zur neuen (virtuellen) Marke. */
        data class AutoRounded(val newMarkPosition: GeoPoint) : Result()

        /** Windwinkel-Übergang in der Nähe (≤150m), aber NICHT an der gesetzten Marke — braucht Bestätigung. */
        data class NeedsConfirmation(val candidatePosition: GeoPoint) : Result()
    }

    fun reset() {
        tracker.reset()
        lastSide = null
    }

    /**
     * 1x/s aufrufen. [mark] = die aktuell gesetzte Zielmarke (Boje/Luvbake/
     * Heimatpunkt-artig), oder `null` für "keine Marke, rein windwinkel-
     * basiert" (siehe Klassendoku).
     */
    fun tick(fix: Fix, windDir: Double?, windCalibrated: Boolean, mark: GeoPoint?): Result {
        val lat = fix.lat
        val lon = fix.lon

        // 1) Distanz zur gesetzten Marke — IMMER zuerst, unabhängig vom Wind.
        if (mark != null && lat != null && lon != null) {
            val dist = GeoUtils.distanceMeters(lat, lon, mark.lat, mark.lon)
            if (dist <= Constants.ROUNDING_RADIUS_M) {
                tracker.reset()
                lastSide = null
                return Result.Rounded
            }
        }

        if (!windCalibrated || windDir == null) return Result.None

        tracker.sample(fix.cogDeg, lat, lon, fix.sogKn)
        val steady = tracker.steady(Constants.RACE_COURSE_MAX_DEV) ?: return Result.None

        val side = if (abs(GeoUtils.angleDiff(steady, windDir)) < 90.0) -1 else 1
        val prevSide = lastSide
        lastSide = side
        if (prevSide == null || prevSide == side) return Result.None // erster Wert oder keine Seite gewechselt

        // Amwind/Vorwind-Übergang erkannt.
        if (lat == null || lon == null) return Result.None
        val here = GeoPoint(lat, lon)
        if (mark == null) return Result.AutoRounded(here)

        val distToMark = GeoUtils.distanceMeters(lat, lon, mark.lat, mark.lon)
        return if (distToMark <= Constants.ROUNDING_CONFIRM_RADIUS_M) {
            Result.NeedsConfirmation(here)
        } else {
            Result.None // zu weit von der Marke weg - normales Manöver, nichts melden
        }
    }
}
