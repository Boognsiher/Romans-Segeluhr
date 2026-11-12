package com.segeluhr.app.logic

import com.segeluhr.app.core.*
import com.segeluhr.app.data.model.CompetitionCourseConfig
import com.segeluhr.app.data.model.CompetitionGuidance
import com.segeluhr.app.data.model.CompetitionLeg
import com.segeluhr.app.data.model.LeewardMode
import kotlin.math.abs

/**
 * "Competition"-Modus fürs echte Rennen (Erweiterung, siehe
 * docs/Erweiterung_Competition_Modus.md, Kurs-Modell **02.09.2026
 * überarbeitet** nach Romans Korrektur — siehe
 * docs/Erweiterung_Competition_Kursmodell.md). Startet automatisch bei
 * Countdown 0:00 (siehe StartCountdownEngine/ViewModel), läuft unabhängig
 * vom Trainings-Tab (TrainingEngine/TrainMode) — beide können parallel
 * aktiv sein, ohne sich zu stören.
 *
 * **Kurs-Modell (Romans echter Verein-Kurs):** Luvboje -> Lee-Boje/Gate,
 * IMMER [Constants.COMPETITION_LAP_COUNT] Runden, danach Ziel. Die Luvboje
 * wird immer gegen den Uhrzeigersinn gerundet — der kurze Halbwind-Schlag
 * danach ist reine Rundungsbewegung, KEINE eigene Etappe (anders als das
 * frühere, am falschen Kurs-Modell orientierte `REACH_TO_OFFSET`-Bein).
 * Welche der drei Lee-Varianten gilt, legt [CompetitionCourseConfig.leewardMode]
 * fest (vor dem Start gewählt, siehe [LeewardMode]-Doku) — bestimmt sowohl
 * die Rundungs-Marke jeder Runde als auch die Ziel-Geometrie danach:
 * - [LeewardMode.LEE_IS_PIN]: Ziel halbwind, Boot<->Zielboje.
 * - [LeewardMode.SEPARATE_BUOY]/[LeewardMode.GATE]: Ziel amwind durch
 *   Pin<->Boot (wie eine zweite Startlinien-Querung).
 * Ist der jeweilige Punkt nicht gesetzt, wird geschätzt (Luvboje direkt
 * gegen den Wind, Lee-Ziel per Amwind/Vorwind-Kurswechsel, Ziel-Peilung als
 * reiner Wind-Wert) — gleiches Prinzip wie schon vor der Überarbeitung.
 *
 * **Kein automatisches Ziel-Erkennen** (Roman-Entscheidung 02.09.2026):
 * FINISH zeigt nur laufende Peilung/Distanz/VMC zur Ziellinie, das
 * tatsächliche Beenden bleibt der bestehende "Wettfahrt beenden"-Button
 * (`stopCompetition()`) — echte Linien-Kreuzungs-Erkennung wäre neue,
 * ungetestete Geometrie-Logik kurz vor einem Wassertest gewesen.
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

    // Vereinheitlichte Rundungserkennung für UPWIND (Luvboje) und DOWNWIND
    // (Lee-Boje/Gate, oder geschätzt ohne gesetzten Punkt) — siehe
    // docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md. EIN geteilter
    // Detector reicht, da nie beide Legs gleichzeitig aktiv sind — wird bei
    // jedem Leg-Wechsel zurückgesetzt. FINISH nutzt ihn bewusst NICHT (siehe
    // Klassendoku "Kein automatisches Ziel-Erkennen").
    private val roundingDetector = MarkRoundingDetector()

    // VMC zur aktuellen Etappen-Marke, gleiches Muster wie
    // HomeEngine.progressTracker (siehe dortige Klassendoku) - eine träge,
    // über ein Zeitfenster gemessene Annäherung statt einer Momentan-
    // Berechnung aus dem Kurs. Eigene Instanz (siehe
    // HomeProgressTracker-Klassendoku: "jede Nutzung braucht eine EIGENE,
    // unabhängige Instanz"). Wird bei jedem Etappen-/Marken-Wechsel
    // zurückgesetzt, weil die alte Distanz-Historie sich aufs vorherige Ziel
    // bezieht.
    private val vmcTracker = HomeProgressTracker()

    data class PendingConfirmation(val waypointKey: String, val candidatePosition: GeoPoint)

    /** Amwind/Vorwind-Kurswechsel abseits der gesetzten Marke erkannt — wartet auf Bestätigen/Ablehnen (ViewModel), siehe Klassendoku dort. */
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
        course: CompetitionCourseConfig,
        closehauledAngleDeg: Double = Constants.DEFAULT_CLOSEHAULED_ANGLE_DEG,
        downwindAngleDeg: Double = Constants.DEFAULT_DOWNWIND_ANGLE_DEG,
    ): CompetitionGuidance? {
        val lat = fix.lat ?: return null
        val lon = fix.lon ?: return null
        val wd = windDir ?: return null

        return when (leg) {
            CompetitionLeg.UPWIND -> tickUpwind(fix, lat, lon, wd, mark1, closehauledAngleDeg)
            CompetitionLeg.DOWNWIND -> tickDownwind(fix, lat, lon, wd, downwindAngleDeg, course)
            CompetitionLeg.FINISH -> tickFinish(fix, lat, lon, wd, course, closehauledAngleDeg)
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
        fix: Fix, lat: Double, lon: Double, wd: Double, mark1: GeoPoint?, closehauledAngleDeg: Double,
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
                MarkRoundingDetector.Result.Rounded -> advanceLegAfterUpwind()
                is MarkRoundingDetector.Result.AutoRounded -> advanceLegAfterUpwind()
                is MarkRoundingDetector.Result.NeedsConfirmation -> {
                    pendingConfirmation = PendingConfirmation("competitionMark1", result.candidatePosition)
                    vib.roundingConfirmNeeded()
                    status.setStatus("Luvboje noch nicht erreicht — trotzdem als gerundet werten?", StatusLevel.AMBER)
                }
                MarkRoundingDetector.Result.None -> Unit
            }
        }

        val (recommended, maneuverNeeded) = closehauledGuidance(fix, wd, bearing, closehauledAngleDeg)
        maybeVibrateManeuver(maneuverNeeded, fix.timestampMs, if (isEstimated) "Luvtonne (geschätzt)" else "Luvboje")

        // Träge/geglättete VMC statt Momentan-Kurs (siehe vmcTracker-Doku).
        // Ohne echte Luvboje (isEstimated) gibt es keinen festen GPS-Punkt,
        // gegen den sich eine Annäherung messen liesse - distance ist dann
        // ohnehin schon null, sample() wird also gar nicht erst aufgerufen.
        if (distance != null) vmcTracker.sample(fix.timestampMs, distance)

        return CompetitionGuidance(
            leg = CompetitionLeg.UPWIND,
            label = if (isEstimated) "Luvtonne (geschätzt)" else "Luvboje",
            bearing = bearing,
            distanceM = distance,
            recommendedHeading = recommended,
            maneuverNeeded = maneuverNeeded,
            isEstimated = isEstimated,
            lapCount = lapCount,
            vmcKn = if (distance != null) vmcTracker.averageVmcKn() else null,
        )
    }

    private fun advanceLegAfterUpwind() {
        vib.rounding6()
        roundingDetector.reset()
        vmcTracker.reset() // neue Etappe -> neues Ziel, alte Annäherungs-Historie ungültig
        lastManeuverNeeded = null
        leg = CompetitionLeg.DOWNWIND
        status.setStatus("Luvboje gerundet — Vorwind-Schlag!", StatusLevel.GREEN)
    }

    private fun advanceLegAfterDownwind() {
        vib.rounding6()
        roundingDetector.reset()
        vmcTracker.reset() // neue Etappe -> neues Ziel, alte Annäherungs-Historie ungültig
        lastManeuverNeeded = null
        lapCount++
        if (lapCount >= Constants.COMPETITION_LAP_COUNT) {
            leg = CompetitionLeg.FINISH
            status.setStatus("Lee-Boje/Gate gerundet — Ziel!", StatusLevel.GREEN)
        } else {
            leg = CompetitionLeg.UPWIND
            status.setStatus("Lee-Boje/Gate gerundet — nächste Runde!", StatusLevel.GREEN)
        }
    }

    /**
     * Vom ViewModel aufgerufen, nachdem der betroffene Wegpunkt auf die neue
     * Position korrigiert wurde ("Ja, Boje ist hier"). Welche Etappe gerade
     * bestätigt wird (Luvboje-Rundung vs. Lee-Boje/Gate-Rundung), ergibt
     * sich aus [leg] selbst — anders als vor der Kurs-Modell-Überarbeitung
     * braucht es dafür keinen extra Parameter mehr (die alte
     * `hasOffsetMark`-Fallunterscheidung ist mit `REACH_TO_OFFSET`
     * entfallen).
     */
    fun confirmPendingRounding() {
        if (pendingConfirmation == null) return
        pendingConfirmation = null
        when (leg) {
            CompetitionLeg.UPWIND -> advanceLegAfterUpwind()
            CompetitionLeg.DOWNWIND -> advanceLegAfterDownwind()
            CompetitionLeg.FINISH -> Unit // FINISH nutzt roundingDetector nicht, sollte hier nie ankommen
        }
    }

    /** "Nein, anderer Grund" — Kurswechsel war keine Rundung, Boje bleibt unverändert, Leg läuft normal weiter. */
    fun rejectPendingRounding() {
        pendingConfirmation = null
        roundingDetector.reset()
        status.setStatus("Kurswechsel nicht als Bojen-Rundung gewertet.", StatusLevel.NORMAL)
    }

    /**
     * Welcher Punkt gerade das Lee-Ziel ist, je nach [CompetitionCourseConfig.leewardMode]
     * — beim Gate automatisch die NÄHER liegende der beiden Bojen (Roman-
     * Entscheidung 02.09.2026: kein zusätzlicher Bedienschritt auf dem
     * Wasser). [waypointKey] wird für [PendingConfirmation] gebraucht, damit
     * eine Bestätigung den RICHTIGEN SettingsRepository-Wegpunkt korrigiert.
     */
    private data class LeewardTarget(val point: GeoPoint, val waypointKey: String)

    private fun resolveLeewardTarget(fix: Fix, course: CompetitionCourseConfig): LeewardTarget? = when (course.leewardMode) {
        LeewardMode.LEE_IS_PIN -> course.pin?.let { LeewardTarget(it, "pin") }
        LeewardMode.SEPARATE_BUOY -> course.leeBuoy?.let { LeewardTarget(it, "leeBuoy") }
        LeewardMode.GATE -> nearestGateMark(fix, course.gateA, course.gateB)
    }

    private fun nearestGateMark(fix: Fix, gateA: GeoPoint?, gateB: GeoPoint?): LeewardTarget? {
        val lat = fix.lat
        val lon = fix.lon
        return when {
            gateA == null && gateB == null -> null
            gateA == null -> LeewardTarget(gateB!!, "gateB")
            gateB == null -> LeewardTarget(gateA, "gateA")
            lat == null || lon == null -> LeewardTarget(gateA, "gateA") // kein Fix -> Default, wird beim naechsten Tick mit Fix neu bewertet
            else -> {
                val distA = GeoUtils.distanceMeters(lat, lon, gateA.lat, gateA.lon)
                val distB = GeoUtils.distanceMeters(lat, lon, gateB.lat, gateB.lon)
                if (distA <= distB) LeewardTarget(gateA, "gateA") else LeewardTarget(gateB, "gateB")
            }
        }
    }

    private fun tickDownwind(
        fix: Fix, lat: Double, lon: Double, wd: Double, downwindAngleDeg: Double, course: CompetitionCourseConfig,
    ): CompetitionGuidance {
        val target = resolveLeewardTarget(fix, course)
        val isEstimated = target == null
        val bearing = target?.let { GeoUtils.bearingDeg(lat, lon, it.point.lat, it.point.lon) } ?: run {
            // Kein Lee-Wegpunkt gesetzt -> wie vor der Kurs-Modell-
            // Überarbeitung rein geschätzt: aktuell gefahrene Gybe-Seite
            // beibehalten (kein Ziel, gegen das sich ein "besserer" Bug
            // bestimmen liesse).
            val cog = fix.cogDeg
            val gybeSign = if (cog != null && GeoUtils.angleDiff(cog, wd) > 0) 1 else -1
            GeoUtils.normalize360(wd + gybeSign * downwindAngleDeg)
        }
        val distance = target?.let { GeoUtils.distanceMeters(lat, lon, it.point.lat, it.point.lon) }

        if (distance != null) vmcTracker.sample(fix.timestampMs, distance)

        if (pendingConfirmation == null) {
            when (val result = roundingDetector.tick(fix, wd, true, target?.point)) {
                MarkRoundingDetector.Result.Rounded -> advanceLegAfterDownwind()
                is MarkRoundingDetector.Result.AutoRounded -> advanceLegAfterDownwind()
                is MarkRoundingDetector.Result.NeedsConfirmation -> {
                    // target ist hier garantiert nicht null: NeedsConfirmation
                    // liefert MarkRoundingDetector laut eigener Doku nur, wenn
                    // ein `mark` übergeben wurde.
                    pendingConfirmation = PendingConfirmation(target!!.waypointKey, result.candidatePosition)
                    vib.roundingConfirmNeeded()
                    status.setStatus("Lee-Boje/Gate noch nicht erreicht — trotzdem als gerundet werten?", StatusLevel.AMBER)
                }
                MarkRoundingDetector.Result.None -> Unit
            }
        }

        return CompetitionGuidance(
            leg = CompetitionLeg.DOWNWIND,
            label = if (isEstimated) "Lee-Tonne (geschätzt)" else "Lee-Boje/Gate",
            bearing = bearing,
            distanceM = distance,
            recommendedHeading = bearing, // Vorwind/Reach: kein Anluv-Kurs wie bei UPWIND/FINISH-Amwind nötig
            maneuverNeeded = false,
            isEstimated = isEstimated,
            lapCount = lapCount,
            vmcKn = if (distance != null) vmcTracker.averageVmcKn() else null,
        )
    }

    /**
     * Ziel-Punkt nach der letzten Runde, je nach [CompetitionCourseConfig.leewardMode]
     * (siehe [LeewardMode]-Doku): [LeewardMode.LEE_IS_PIN] -> Halbwind-Ziel
     * zwischen Boot und Zielboje, sonst -> Amwind-Ziel zwischen Pin und
     * Boot (dieselbe Startlinie, ein zweites Mal gequert). Einfacher
     * arithmetischer Mittelpunkt statt echter geodätischer Berechnung —
     * für die kurzen Distanzen einer Start-/Ziellinie ausreichend genau,
     * gleiche Vereinfachung wie sonst im Projekt (siehe z.B. GeoUtils).
     */
    private fun resolveFinishTarget(course: CompetitionCourseConfig): GeoPoint? =
        if (course.leewardMode == LeewardMode.LEE_IS_PIN) midpoint(course.boat, course.finishBuoy)
        else midpoint(course.pin, course.boat)

    private fun midpoint(a: GeoPoint?, b: GeoPoint?): GeoPoint? {
        if (a == null || b == null) return null
        return GeoPoint((a.lat + b.lat) / 2.0, (a.lon + b.lon) / 2.0)
    }

    /**
     * Letzte Etappe nach [Constants.COMPETITION_LAP_COUNT] Runden — nur
     * laufende Peilung/Distanz/VMC, KEIN automatisches Erkennen der Ziel-
     * durchfahrt (siehe Klassendoku). Bleibt aktiv, bis der Segler
     * `stopCompetition()` (bestehender "Wettfahrt beenden"-Button) drückt.
     */
    private fun tickFinish(
        fix: Fix, lat: Double, lon: Double, wd: Double, course: CompetitionCourseConfig, closehauledAngleDeg: Double,
    ): CompetitionGuidance {
        val isHalfwind = course.leewardMode == LeewardMode.LEE_IS_PIN
        val target = resolveFinishTarget(course)
        val isEstimated = target == null
        val bearing = target?.let { GeoUtils.bearingDeg(lat, lon, it.lat, it.lon) } ?: wd
        val distance = target?.let { GeoUtils.distanceMeters(lat, lon, it.lat, it.lon) }

        if (distance != null) vmcTracker.sample(fix.timestampMs, distance)

        val (recommended, maneuverNeeded) = if (isHalfwind) {
            bearing to false // Halbwind-Ziel: direkter Kurs, kein Anluven nötig
        } else {
            closehauledGuidance(fix, wd, bearing, closehauledAngleDeg)
        }
        if (!isHalfwind) maybeVibrateManeuver(maneuverNeeded, fix.timestampMs, "Ziel")

        return CompetitionGuidance(
            leg = CompetitionLeg.FINISH,
            label = when {
                isHalfwind -> "Ziel (Halbwind)"
                isEstimated -> "Ziel (Amwind, geschätzt)"
                else -> "Ziel (Amwind)"
            },
            bearing = bearing,
            distanceM = distance,
            recommendedHeading = recommended,
            maneuverNeeded = maneuverNeeded,
            isEstimated = isEstimated,
            lapCount = lapCount,
            vmcKn = if (distance != null) vmcTracker.averageVmcKn() else null,
        )
    }

    /** Referenzpunkt für die Wind-Shift-Bewertung (Header/Lift, Abschnitt 4.2) */
    fun windShiftReferencePoint(fix: Fix, windDir: Double?, mark1: GeoPoint?, course: CompetitionCourseConfig): GeoPoint? {
        val lat = fix.lat ?: return null
        val lon = fix.lon ?: return null
        val wd = windDir ?: return null
        return when (leg) {
            CompetitionLeg.UPWIND -> mark1 ?: GeoUtils.projectPoint(lat, lon, wd, 2000.0)
            CompetitionLeg.DOWNWIND -> resolveLeewardTarget(fix, course)?.point
                ?: GeoUtils.projectPoint(lat, lon, GeoUtils.normalize360(wd + 180), 2000.0)
            CompetitionLeg.FINISH -> resolveFinishTarget(course)
                ?: GeoUtils.projectPoint(lat, lon, wd, 2000.0)
        }
    }
}
