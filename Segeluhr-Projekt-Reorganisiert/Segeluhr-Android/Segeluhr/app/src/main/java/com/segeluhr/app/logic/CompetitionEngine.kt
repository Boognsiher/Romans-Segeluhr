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

    var leg: CompetitionLeg = CompetitionLeg.UPWIND
        private set
    var lapCount: Int = 0
        private set

    private var lastManeuverNeeded: Boolean? = null

    // Manöver-Timing-Entschärfung (12.08.2026, Roman-Wunschliste "vor dem
    // nächsten Test", siehe docs/Erweiterung_TWatch_Ultra_NavRedesign.md):
    // im echten Rennen kamen Vorschläge zu schnell/zu oft hintereinander,
    // v.a. wenn der Kurs genau am Anluv-Limit oszilliert. activatedAtMs wird
    // beim ERSTEN tick() nach activate() lazy gesetzt (activate() hat noch
    // keinen fix/Zeitstempel), nextAllowedSuggestionAtMs startet damit
    // gleich auf "Start-Grace" und wird bei jedem tatsächlich ausgelösten
    // Vorschlag um den Cooldown weiter nach vorne verschoben - siehe
    // maybeVibrateManeuver() unten. Drosselt NUR den Push (Vibration), nicht
    // die Anzeige - siehe dortige Klassendoku.
    private var activatedAtMs: Long? = null
    private var nextAllowedSuggestionAtMs: Long = 0L

    // Vereinheitlichte Rundungserkennung für UPWIND (Luvbake) und DOWNWIND
    // (immer "geschätzt", kein Leetonnen-Wegpunkt) — siehe
    // docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md. EIN geteilter
    // Detector reicht, da nie beide Legs gleichzeitig aktiv sind (wie
    // schon beim bisherigen legTracker/lastSteady) — wird bei jedem
    // Leg-Wechsel zurückgesetzt.
    private val roundingDetector = MarkRoundingDetector()

    // VMC zur aktuellen Etappen-Marke (Luvbake bzw. Entlastungsboje), gleiches
    // Muster wie HomeEngine.progressTracker (siehe dortige Klassendoku) - eine
    // träge, über ein Zeitfenster gemessene Annäherung statt einer
    // Momentan-Berechnung aus dem Kurs. Eigene Instanz (siehe
    // HomeProgressTracker-Klassendoku: "jede Nutzung braucht eine EIGENE,
    // unabhängige Instanz"). Wird bei jedem Etappen-/Marken-Wechsel
    // zurückgesetzt, weil die alte Distanz-Historie sich aufs vorherige Ziel
    // bezieht. Auf DOWNWIND (kein Leetonnen-Wegpunkt) bleibt sie ungenutzt.
    private val vmcTracker = HomeProgressTracker()

    data class PendingConfirmation(val waypointKey: String, val candidatePosition: GeoPoint)

    /** Amwind/Vorwind-Kurswechsel abseits der gesetzten Luvbake erkannt — wartet auf Bestätigen/Ablehnen (ViewModel), siehe Klassendoku dort. */
    var pendingConfirmation: PendingConfirmation? = null
        private set

    /** Beim automatischen Start des Competition-Modus (Countdown 0:00) aufrufen */
    fun activate() {
        leg = CompetitionLeg.UPWIND
        lapCount = 0
        roundingDetector.reset()
        vmcTracker.reset()
        pendingConfirmation = null
        lastManeuverNeeded = null
        activatedAtMs = null
        nextAllowedSuggestionAtMs = 0L
    }

    fun tick(
        fix: Fix,
        windDir: Double?,
        mark1: GeoPoint?,
        mark2: GeoPoint?,
        closehauledAngleDeg: Double = Constants.DEFAULT_CLOSEHAULED_ANGLE_DEG,
        downwindAngleDeg: Double = Constants.DEFAULT_DOWNWIND_ANGLE_DEG,
    ): CompetitionGuidance? {
        val lat = fix.lat ?: return null
        val lon = fix.lon ?: return null
        val wd = windDir ?: return null

        // Sicherheitsnetz: wurde die Entlastungsboje zwischenzeitlich wieder
        // gelöscht, während wir genau auf dem Weg dorthin waren -> direkt
        // weiter zu Vorwind, statt mit einer nicht mehr vorhandenen Boje zu rechnen.
        if (leg == CompetitionLeg.REACH_TO_OFFSET && mark2 == null) {
            leg = CompetitionLeg.DOWNWIND
            roundingDetector.reset()
            vmcTracker.reset()
        }

        return when (leg) {
            CompetitionLeg.UPWIND -> tickUpwind(fix, lat, lon, wd, mark1, mark2, closehauledAngleDeg)
            CompetitionLeg.REACH_TO_OFFSET -> tickReach(fix, lat, lon, mark2!!)
            CompetitionLeg.DOWNWIND -> tickDownwind(fix, lat, lon, wd, downwindAngleDeg)
        }
    }

    /** Gleiche Am-Wind-Logik wie HomeEngine: direkter Kurs oder besserer Kreuz-Kurs + Wende-Bedarf */
    private fun closehauledGuidance(fix: Fix, wd: Double, bearingToTarget: Double, closehauledAngleDeg: Double): Pair<Double, Boolean> {
        val angleToWind = GeoUtils.angleDiff(bearingToTarget, wd)
        val canSailDirect = abs(angleToWind) >= closehauledAngleDeg
        if (canSailDirect) return bearingToTarget to false

        val ch1 = GeoUtils.normalize360(wd - closehauledAngleDeg)
        val ch2 = GeoUtils.normalize360(wd + closehauledAngleDeg)
        val recommended = if (abs(GeoUtils.angleDiff(ch1, bearingToTarget)) <=
            abs(GeoUtils.angleDiff(ch2, bearingToTarget))
        ) ch1 else ch2

        val cog = fix.cogDeg ?: return recommended to false
        val currentTackSign = if (GeoUtils.angleDiff(cog, wd) > 0) 1 else -1
        val recommendedTackSign = if (GeoUtils.angleDiff(recommended, wd) > 0) 1 else -1
        return recommended to (currentTackSign != recommendedTackSign)
    }

    /**
     * Drosselt nur den PUSH (Vibration + Statusmeldung, künftig auch das
     * Nav-Tab-5s-Overlay auf der Uhr) — NICHT die Anzeige. Roman-Wunsch
     * 12.08.2026: das Nav-Tab soll immer live den aktuellen Kurs
     * rot/grün zeigen (siehe [CompetitionGuidance.maneuverNeeded], bleibt
     * dafür bewusst UNGEDROSSELT/roh), auch während eines gerade
     * unterdrückten Pushs ("wenn ich das ablehne will ich trotzdem einen
     * Indikator haben"). [activatedAtMs]/[nextAllowedSuggestionAtMs] werden
     * beim ERSTEN Tick nach [activate] lazy gesetzt (activate() hat noch
     * keinen Zeitstempel). Nur eine ECHTE Flanke (false→true von
     * [rawNeeded], nicht vom gedrosselten Wert) löst überhaupt einen Push
     * aus - bleibt der Kurs durchgehend "schlecht", wird nur einmal
     * gepusht, nicht wiederholt (wie schon vor dieser Erweiterung).
     */
    private fun maybeVibrateManeuver(rawNeeded: Boolean, now: Long, label: String) {
        if (activatedAtMs == null) {
            activatedAtMs = now
            nextAllowedSuggestionAtMs = now + Constants.COMPETITION_MANEUVER_START_GRACE_MS
        }
        if (rawNeeded && lastManeuverNeeded != true && now >= nextAllowedSuggestionAtMs) {
            vib.maneuverCmd()
            status.setStatus("Wende Richtung $label empfehlenswert!", StatusLevel.AMBER)
            nextAllowedSuggestionAtMs = now + Constants.COMPETITION_MANEUVER_SUGGEST_COOLDOWN_MS
        }
        lastManeuverNeeded = rawNeeded
    }

    private fun tickUpwind(
        fix: Fix, lat: Double, lon: Double, wd: Double, mark1: GeoPoint?, mark2: GeoPoint?, closehauledAngleDeg: Double,
    ): CompetitionGuidance {
        val isEstimated = mark1 == null
        val bearing = mark1?.let { GeoUtils.bearingDeg(lat, lon, it.lat, it.lon) } ?: wd
        val distance = mark1?.let { GeoUtils.distanceMeters(lat, lon, it.lat, it.lon) }

        // Rundungserkennung pausiert, während eine Bestätigung aussteht -
        // siehe MarkRoundingDetector-Klassendoku. windCalibrated hier immer
        // true: tick() ist ohne windDir != null gar nicht bis hierher
        // gekommen (siehe "val wd = windDir ?: return null" oben).
        if (pendingConfirmation == null) {
            when (val result = roundingDetector.tick(fix, wd, true, mark1)) {
                MarkRoundingDetector.Result.Rounded -> advanceLegAfterUpwind(mark2 != null)
                is MarkRoundingDetector.Result.AutoRounded -> advanceLegAfterUpwind(false) // ohne Luvbake auch keine Entlastungsboje sinnvoll ansteuerbar
                is MarkRoundingDetector.Result.NeedsConfirmation -> {
                    pendingConfirmation = PendingConfirmation("competitionMark1", result.candidatePosition)
                    vib.roundingConfirmNeeded()
                    status.setStatus("Luvbake noch nicht erreicht — trotzdem als gerundet werten?", StatusLevel.AMBER)
                }
                MarkRoundingDetector.Result.None -> Unit
            }
        }

        val (recommended, maneuverNeeded) = closehauledGuidance(fix, wd, bearing, closehauledAngleDeg)
        maybeVibrateManeuver(maneuverNeeded, fix.timestampMs, if (isEstimated) "Luvtonne (geschätzt)" else "Luvbake")

        // Träge/geglättete VMC statt Momentan-Kurs (siehe vmcTracker-Doku).
        // Ohne echte Luvbake (isEstimated) gibt es keinen festen GPS-Punkt,
        // gegen den sich eine Annäherung messen liesse - distance ist dann
        // ohnehin schon null, sample() wird also gar nicht erst aufgerufen.
        if (distance != null) vmcTracker.sample(fix.timestampMs, distance)

        return CompetitionGuidance(
            leg = CompetitionLeg.UPWIND,
            label = if (isEstimated) "Luvtonne (geschätzt)" else "Luvbake",
            bearing = bearing,
            distanceM = distance,
            recommendedHeading = recommended,
            maneuverNeeded = maneuverNeeded,
            isEstimated = isEstimated,
            lapCount = lapCount,
            vmcKn = if (distance != null) vmcTracker.averageVmcKn() else null,
        )
    }

    private fun advanceLegAfterUpwind(hasOffsetMark: Boolean) {
        vib.rounding6()
        roundingDetector.reset()
        vmcTracker.reset() // neue Etappe -> neues Ziel, alte Annäherungs-Historie ungültig
        lastManeuverNeeded = null
        if (hasOffsetMark) {
            leg = CompetitionLeg.REACH_TO_OFFSET
            status.setStatus("Luvbake gerundet — weiter zur Entlastungsboje!", StatusLevel.GREEN)
        } else {
            leg = CompetitionLeg.DOWNWIND
            status.setStatus("Luvbake gerundet — Vorwind-Schlag!", StatusLevel.GREEN)
        }
    }

    /**
     * Vom ViewModel aufgerufen, nachdem die Boje (Setup-Waypoint) auf die
     * neue Position korrigiert wurde ("Ja, Boje ist hier"). [mark2] wird
     * separat übergeben (statt aus dem letzten `tick()` gemerkt), da
     * zwischen Anfrage und Bestätigung Zeit vergeht — der ViewModel kennt
     * den aktuellen Wegpunkt-Stand zuverlässiger.
     */
    fun confirmPendingRounding(mark2: GeoPoint?) {
        if (pendingConfirmation == null) return
        pendingConfirmation = null
        advanceLegAfterUpwind(mark2 != null)
    }

    /** "Nein, anderer Grund" — Kurswechsel war keine Rundung, Boje bleibt unverändert, Leg läuft normal weiter. */
    fun rejectPendingRounding() {
        pendingConfirmation = null
        roundingDetector.reset()
        status.setStatus("Kurswechsel nicht als Luvbaken-Rundung gewertet.", StatusLevel.NORMAL)
    }

    private fun tickReach(fix: Fix, lat: Double, lon: Double, mark2: GeoPoint): CompetitionGuidance {
        val bearing = GeoUtils.bearingDeg(lat, lon, mark2.lat, mark2.lon)
        val distance = GeoUtils.distanceMeters(lat, lon, mark2.lat, mark2.lon)

        // Vor dem evtl. Reset unten sampeln, sonst geht der letzte Messpunkt
        // direkt vor der Rundung verloren (marginal, aber konsistent mit
        // tickUpwind()/tickDownwind()).
        vmcTracker.sample(fix.timestampMs, distance)
        val vmcKn = vmcTracker.averageVmcKn()

        if (distance <= Constants.ROUNDING_RADIUS_M) {
            vib.rounding6()
            roundingDetector.reset()
            vmcTracker.reset() // neue Etappe (Vorwind) -> kein Marken-Ziel mehr
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
            vmcKn = vmcKn,
        )
    }

    private fun tickDownwind(fix: Fix, lat: Double, lon: Double, wd: Double, downwindAngleDeg: Double): CompetitionGuidance {
        // Ohne eigenen Leetonnen-Wegpunkt ("kein Leetonnen-Wegpunkt vorgesehen",
        // siehe Klassendoku) gibt es kein Ziel, gegen das sich ein "besserer"
        // Bug bestimmen liesse - anders als bei UPWIND/HomeEngine bleibt die
        // aktuell gefahrene Gybe-Seite deshalb einfach erhalten (10.08.2026:
        // gleiche Vorzeichen-Konvention wie currentTackSign/recommendedTackSign
        // in closehauledGuidance() weiter oben). Bei downwindAngleDeg = 180°
        // (noch nichts gelernt) liefert das exakt wd+180 wie bisher,
        // unabhängig vom Vorzeichen.
        val cog = fix.cogDeg
        val gybeSign = if (cog != null && GeoUtils.angleDiff(cog, wd) > 0) 1 else -1
        val bearing = GeoUtils.normalize360(wd + gybeSign * downwindAngleDeg)

        // Kein Leetonnen-Wegpunkt vorgesehen (siehe Klassendoku) -> mark
        // immer null, MarkRoundingDetector liefert hier also nie
        // NeedsConfirmation, nur None oder AutoRounded (sobald der
        // Kurswechsel zurück zur Amwind-Seite erkannt wird).
        val result = roundingDetector.tick(fix, wd, true, null)
        if (result is MarkRoundingDetector.Result.AutoRounded) {
            vib.rounding6()
            roundingDetector.reset()
            vmcTracker.reset() // neue Runde -> neue Luvbake als Ziel
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
            vmcKn = null, // kein Leetonnen-Wegpunkt (siehe Klassendoku) -> keine Annäherung messbar
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
