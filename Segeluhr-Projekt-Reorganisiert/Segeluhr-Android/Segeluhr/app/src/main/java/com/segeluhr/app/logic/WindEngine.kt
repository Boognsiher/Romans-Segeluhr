package com.segeluhr.app.logic

import com.segeluhr.app.core.*
import com.segeluhr.app.data.model.WindCalibState
import com.segeluhr.app.data.model.WindLogPoint
import kotlin.math.abs
import kotlin.math.pow

/**
 * Windrichtung wird nie direkt gemessen, sondern abgeleitet und ständig über
 * den gesegelten Kurs nachgeführt (Abschnitt 4 der Spezifikation).
 *
 * Implementiert ist die Methode "Amwind" (Abschnitt 4.1, rein GPS-basiert,
 * kein Rotationssensor nötig) — identisch zum Browser-Prototyp. Die Methode
 * "Zeigen" (Abschnitt 4.1, benötigt einen relativen Rotationssensor) ist in
 * der Spezifikation beschrieben, aber weder im Prototyp noch hier umgesetzt;
 * bei Bedarf als zusätzlicher Kalibrierpfad ergänzen.
 *
 * Seit der Erweiterung "Boots-Kalibrierung" (siehe
 * docs/Erweiterung_Boots_Kalibrierung.md) übernimmt diese Klasse zusätzlich
 * das Einmessen des tatsächlichen Am-Wind-Wendewinkels des Boots (bisher ein
 * fest verdrahteter 45°-Wert in HomeEngine/CompetitionEngine) — bewusst hier
 * und nicht in einer eigenen Klasse, weil beide Werte aus demselben
 * Zwei-Schläge-Manöver stammen:
 *  - **Kalibrierungsmodus** (`calibrationModeEnabled`): jeder erfolgreiche
 *    Amwind-Kalibrierlauf verfeinert zusätzlich einen laufenden Mittelwert
 *    des Wendewinkels (`closehauledAngleDeg`/`closehauledSampleCount`).
 *  - **Smart-Modus** (`smartModeEnabled`): läuft während des normalen
 *    Segelns nebenbei mit (siehe `tickContinuous`) und justiert den Wert
 *    langsam per EMA nach, sobald ein ruhiger Kurs plausibel am Wind liegt.
 *    Seit 10.08.2026 lernt derselbe Schalter zusätzlich einen
 *    `downwindAngleDeg` (Vorwind-Pendant) — dafür gibt es KEINEN eigenen
 *    Kalibrierungsmodus (kein dediziertes Halsen-Manöver), nur den
 *    fortlaufenden Smart-Nachgleich, weil sich TWS (Windstärke) ohne
 *    eigenen Sensor nicht messen lässt und ein statisches Windstärke-Band-
 *    Modell (wie z.B. bei tactics_pi/OpenCPN) deshalb hier nicht sinnvoll
 *    wäre — Entscheidung Roman, 10.08.2026.
 *
 * Es gibt mehrere benannte Boots-Profile (siehe
 * `SettingsRepository.boatProfilesFlow`) — diese Klasse kennt zu jedem
 * Zeitpunkt nur das gerade AKTIVE Profil (`activeProfileId`). Beim
 * Profilwechsel ruft der ViewModel [restoreBoatProfile] erneut auf.
 *
 * Seit der Erweiterung "Robuste, kontinuierliche Windschätzung" (22.09.2026,
 * siehe docs/Erweiterung_Windschaetzung_Robust.md) ist `windDir` kein
 * fortlaufend verschobener Einzelwert mehr, sondern ein gewichtetes Mittel
 * über [windHistory] — einen Ringpuffer der letzten Wind-Bisektoren aus drei
 * Quellen (explizite Kalibrierung, automatisch erkannte Wenden/Halsen beim
 * normalen Segeln, direkte Kurs-Shifts auf demselben Bug). Eine einzelne
 * schlechte Messung kippt dadurch nicht mehr sofort den ganzen Schätzwert,
 * und die App liefert schon nach der ERSTEN Kalibrierung + der ersten
 * natürlichen Wende laufend bessere Werte, ohne weitere Handaktion.
 * Bewusst NICHT umgesetzt (siehe Doku, Abschnitt 4): eine
 * Amwind/Downwind-Erkennung ganz ohne vorherige Kalibrierung — die dafür
 * geprüfte Speed-Dip-Hypothese hat sich an echten Diagnose-Logs nicht als
 * verlässlich erwiesen. Ein einziger expliziter Kalibrierlauf bleibt daher
 * weiterhin die einzige Voraussetzung, bevor überhaupt ein windDir existiert.
 */
/**
 * Eine erkannte Wende/Halse für die Tages-Auswertung (Erweiterung,
 * 17.08.2026, siehe docs/Erweiterung_Tages_Auswertung.md) — entsteht bei
 * jedem Bug-Wechsel, den [WindEngine.tickContinuous] ohnehin schon für die
 * Header/Lift-Erkennung verfolgt (`tackSign`-Vorzeichenwechsel), kein
 * separater Erkennungsweg.
 */
data class TackEvent(val timestampMs: Long, val angleDeg: Double, val isTack: Boolean)

/**
 * Ein erkannter Wind-Shift für die Tages-/Wettfahrt-Auswertung — [isHeader]
 * ist null, wenn kein Ziel-Referenzpunkt vorlag (siehe [WindEngine.tickContinuous],
 * dann konnte keine Header/Lift-Klassifizierung stattfinden, der Shift selbst
 * hat aber trotzdem stattgefunden).
 */
data class WindShiftEvent(val timestampMs: Long, val isHeader: Boolean?)

/** Ein erfolgreicher Windkalibrierlauf für die Tages-/Wettfahrt-Auswertung. */
data class CalibrationEvent(val timestampMs: Long)

/**
 * Ein einzelnes Wind-Sample im Ringpuffer [WindEngine.windHistory] (siehe
 * docs/Erweiterung_Windschaetzung_Robust.md) — [bisectorDeg] ist bereits eine
 * absolute Windrichtung (180°-Mehrdeutigkeit aufgelöst), [weight] das
 * Basisgewicht je nach Herkunft (explizite Kalibrierung vs. automatisch
 * erkanntes Manöver vs. Kurs-Shift), unabhängig vom Alter — der Alterseinfluss
 * kommt erst beim Auswerten über `WIND_HISTORY_DECAY_HALFLIFE_MS` dazu.
 */
data class WindSample(val timestampMs: Long, val bisectorDeg: Double, val weight: Double)

class WindEngine(
    private val vib: HapticFeedback,
    private val status: StatusSink,
    private val onWindChanged: suspend (windDir: Double, calibrated: Boolean) -> Unit,
    private val onBoatProfileChanged: suspend (profileId: String, closehauledAngleDeg: Double, sampleCount: Int, downwindAngleDeg: Double) -> Unit,
) {
    var windDir: Double? = null
        private set
    var windCalibrated: Boolean = false
        private set

    var calibState: WindCalibState = WindCalibState.IDLE
        private set

    private var calibTack1: Double? = null
    private var calibStartedAt: Long = 0L
    private val calibTracker = CourseTracker()

    private val continuousTracker = CourseTracker()
    private var lastSteadyCOG: Double? = null
    private var lastSteadyAtMs: Long? = null
    private var tackSign: Int? = null

    // ---- Robuste, kontinuierliche Windschätzung (siehe Klassendoku oben
    // und docs/Erweiterung_Windschaetzung_Robust.md) ----
    private val windHistory = ArrayDeque<WindSample>()
    /** Für die UI (Wind-Tab): wie viele Messungen stecken gerade im Schätzwert. */
    val windSampleCount: Int get() = windHistory.size

    // Windverlauf-Log (Abschnitt 4.3): kumulierte Abweichung vom ersten Wert
    private val _windLog = mutableListOf<WindLogPoint>()
    val windLog: List<WindLogPoint> get() = _windLog
    private var lastRawWind: Double? = null
    private var lastWindLogAt: Long = 0L

    // ---- Boots-Kalibrierung (siehe Klassen-Doku oben) ----
    // Immer Zustand des gerade AKTIVEN Profils (siehe Erweiterung "Mehrere
    // Boots-Profile", docs/Erweiterung_Boots_Kalibrierung.md) - beim
    // Profilwechsel ruft der ViewModel [restoreBoatProfile] erneut auf, um
    // diese Werte auf das neu aktivierte Profil umzuschalten.
    var activeProfileId: String = ""
        private set
    var closehauledAngleDeg: Double = Constants.DEFAULT_CLOSEHAULED_ANGLE_DEG
        private set
    var closehauledSampleCount: Int = 0
        private set
    var downwindAngleDeg: Double = Constants.DEFAULT_DOWNWIND_ANGLE_DEG
        private set
    var calibrationModeEnabled: Boolean = false
        private set
    var smartModeEnabled: Boolean = false
        private set
    private var lastPersistedCloseHauledAngle: Double = Constants.DEFAULT_CLOSEHAULED_ANGLE_DEG
    private var lastPersistedDownwindAngle: Double = Constants.DEFAULT_DOWNWIND_ANGLE_DEG

    // ---- Tages-/Wettfahrt-Auswertung (17.08.2026, siehe
    // docs/Erweiterung_Tages_Auswertung.md) ----
    // Läuft die ganze ViewModel-Lebensdauer mit (wie DiagnosticsLogger), wird
    // NICHT bei Stopp/Start zurückgesetzt. Bewusst zeitgestempelte Listen statt
    // reiner Zähler - SessionSummaryEngine.buildReport() filtert sie nach
    // einem beliebigen Zeitfenster (ganzer Tag ODER nur eine einzelne
    // Wettfahrt zwischen Countdown-Start und "Wettfahrt beenden"), ohne einen
    // zweiten, parallel laufenden Erkennungsweg zu brauchen.
    private val _sessionManeuvers = mutableListOf<TackEvent>()
    val sessionManeuvers: List<TackEvent> get() = _sessionManeuvers
    private val _sessionWindShifts = mutableListOf<WindShiftEvent>()
    val sessionWindShifts: List<WindShiftEvent> get() = _sessionWindShifts
    private val _sessionCalibrations = mutableListOf<CalibrationEvent>()
    val sessionCalibrations: List<CalibrationEvent> get() = _sessionCalibrations

    /**
     * Beim App-Start aus der Persistenz laden. Sät [windHistory] mit einem
     * einzelnen Sample (Zeitstempel "jetzt", da das echte Alter der
     * gespeicherten Messung nicht bekannt ist) — der Wert bleibt damit
     * nutzbar, wird aber von der ersten neuen Messung dieser Session bei
     * Bedarf normal weiter verfeinert/korrigiert statt stur festzuhängen.
     */
    fun restore(windDir: Double?, calibrated: Boolean) {
        this.windDir = windDir
        this.windCalibrated = calibrated
        windHistory.clear()
        if (windDir != null && calibrated) {
            windHistory.addLast(WindSample(System.currentTimeMillis(), windDir, Constants.WIND_SAMPLE_WEIGHT_EXPLICIT_CALIB))
        }
    }

    /**
     * Beim App-Start UND bei jedem Profilwechsel aufrufen (siehe
     * SettingsRepository.boatProfilesFlow) — schaltet Kalibrierungsmodus/
     * Smart-Modus auf das neu aktive Profil um. Modi selbst
     * (`calibrationModeEnabled`/`smartModeEnabled`) bleiben dabei
     * unverändert, nur WAS sie kalibrieren wechselt.
     */
    fun restoreBoatProfile(profileId: String, closehauledAngleDeg: Double, sampleCount: Int, downwindAngleDeg: Double) {
        this.activeProfileId = profileId
        this.closehauledAngleDeg = closehauledAngleDeg
        this.closehauledSampleCount = sampleCount
        this.lastPersistedCloseHauledAngle = closehauledAngleDeg
        this.downwindAngleDeg = downwindAngleDeg
        this.lastPersistedDownwindAngle = downwindAngleDeg
    }

    /**
     * Kalibrierungsmodus an/aus (Setup/Wind-Tab-Schalter). Bewusst NICHT
     * persistiert (wie `homeModeActive`) — nach einem Neustart ist der
     * Modus wieder aus, der gelernte Winkel selbst bleibt aber erhalten.
     */
    fun setCalibrationModeEnabled(enabled: Boolean) {
        calibrationModeEnabled = enabled
        status.setStatus(
            if (enabled) "Kalibrierungsmodus aktiv — jede erfolgreiche Windkalibrierung lernt jetzt auch den Wendewinkel."
            else "Kalibrierungsmodus beendet.",
            StatusLevel.NORMAL,
        )
    }

    /** Smart-Modus an/aus — ebenfalls nicht persistiert, siehe [setCalibrationModeEnabled]. */
    fun setSmartModeEnabled(enabled: Boolean) {
        smartModeEnabled = enabled
    }

    /** Wind-Tab-Button "Wendewinkel zurücksetzen" — wirft die gelernten Winkel (Wende UND Vorwind) DES AKTIVEN Profils weg. */
    suspend fun resetBoatProfile() {
        closehauledAngleDeg = Constants.DEFAULT_CLOSEHAULED_ANGLE_DEG
        closehauledSampleCount = 0
        lastPersistedCloseHauledAngle = Constants.DEFAULT_CLOSEHAULED_ANGLE_DEG
        downwindAngleDeg = Constants.DEFAULT_DOWNWIND_ANGLE_DEG
        lastPersistedDownwindAngle = Constants.DEFAULT_DOWNWIND_ANGLE_DEG
        onBoatProfileChanged(activeProfileId, closehauledAngleDeg, closehauledSampleCount, downwindAngleDeg)
        status.setStatus("Wende-/Vorwind-Winkel auf Standardwerte (${Constants.DEFAULT_CLOSEHAULED_ANGLE_DEG.toInt()}°/${Constants.DEFAULT_DOWNWIND_ANGLE_DEG.toInt()}°) zurückgesetzt.", StatusLevel.NORMAL)
    }

    /**
     * Laufender Mittelwert über alle bisherigen Kalibrierläufe — jeder
     * weitere Lauf verfeinert den Wert, ein einzelner Ausreisser kippt ihn
     * nicht sofort um. Nur vom Kalibrierungsmodus aufgerufen (siehe
     * [tickCalibration], WAIT_TACK2-Erfolgsfall).
     */
    private suspend fun absorbCalibrationSample(measuredAngleDeg: Double) {
        val newCount = closehauledSampleCount + 1
        closehauledAngleDeg = (closehauledAngleDeg * closehauledSampleCount + measuredAngleDeg) / newCount
        closehauledSampleCount = newCount
        persistBoatProfileIfChanged()
    }

    /**
     * Smart-Modus: leiser, unbegrenzt weiterlaufender Nachgleich während des
     * normalen Segelns (siehe [tickContinuous]). Bewusst ein EMA statt eines
     * Mittelwerts über [closehauledSampleCount] — die Kalibrierläufe sollen
     * "verlässliche" Messungen bleiben, der Smart-Modus liefert nur leise,
     * potenziell verrauschte Alltags-Korrekturen obendrauf.
     */
    private suspend fun maybeLearnCloseHauledAngle(currentAngleOffWindDeg: Double) {
        if (abs(currentAngleOffWindDeg - closehauledAngleDeg) > Constants.SMART_CLOSEHAULED_LEARN_BAND_DEG) return
        closehauledAngleDeg += Constants.SMART_CLOSEHAULED_EMA_ALPHA * (currentAngleOffWindDeg - closehauledAngleDeg)
        persistBoatProfileIfChanged()
    }

    /**
     * Vorwind-Pendant zu [maybeLearnCloseHauledAngle] — gleiches Prinzip
     * (EMA, nur bei plausibel passendem Kurs), nur um den gelernten
     * Vorwind-Winkel statt den Wendewinkel. `currentAngleOffWindDeg` ist
     * hier wie dort der BETRAG des Winkels zur Windrichtung (0..180°) —
     * nahe 180° bedeutet "läuft praktisch direkt vor dem Wind".
     */
    private suspend fun maybeLearnDownwindAngle(currentAngleOffWindDeg: Double) {
        if (abs(currentAngleOffWindDeg - downwindAngleDeg) > Constants.SMART_DOWNWIND_LEARN_BAND_DEG) return
        downwindAngleDeg += Constants.SMART_CLOSEHAULED_EMA_ALPHA * (currentAngleOffWindDeg - downwindAngleDeg)
        persistBoatProfileIfChanged()
    }

    private suspend fun persistBoatProfileIfChanged() {
        val closehauledChanged = abs(closehauledAngleDeg - lastPersistedCloseHauledAngle) >= Constants.CLOSEHAULED_PERSIST_THRESHOLD_DEG
        val downwindChanged = abs(downwindAngleDeg - lastPersistedDownwindAngle) >= Constants.CLOSEHAULED_PERSIST_THRESHOLD_DEG
        if (!closehauledChanged && !downwindChanged) return
        lastPersistedCloseHauledAngle = closehauledAngleDeg
        lastPersistedDownwindAngle = downwindAngleDeg
        onBoatProfileChanged(activeProfileId, closehauledAngleDeg, closehauledSampleCount, downwindAngleDeg)
    }

    /**
     * Zentrale Stelle, über die JEDE neue Windmessung einfliesst — egal ob
     * aus der expliziten Kalibrierung, einer automatisch erkannten Wende/
     * Halse oder einem Kurs-Shift auf demselben Bug (siehe Klassendoku und
     * docs/Erweiterung_Windschaetzung_Robust.md). Ersetzt die früheren
     * direkten `windDir = ...`-Zuweisungen.
     */
    private suspend fun addWindSample(bisectorDeg: Double, weight: Double, timestampMs: Long) {
        windHistory.addLast(WindSample(timestampMs, bisectorDeg, weight))
        pruneWindHistory(timestampMs)
        recomputeWindDir(timestampMs)
    }

    private fun pruneWindHistory(nowMs: Long) {
        while (windHistory.isNotEmpty() && nowMs - windHistory.first().timestampMs > Constants.WIND_HISTORY_MAX_AGE_MS) {
            windHistory.removeFirst()
        }
        while (windHistory.size > Constants.WIND_HISTORY_MAX_SAMPLES) {
            windHistory.removeFirst()
        }
    }

    /**
     * Gewichtetes Mittel über [windHistory] — Basisgewicht je Sample-Herkunft
     * (siehe [WindSample]) mal ein mit dem Alter exponentiell abklingender
     * Faktor (`WIND_HISTORY_DECAY_HALFLIFE_MS`), damit eine anhaltende echte
     * Winddrehung sich durchsetzt statt für immer von alten Messungen
     * ausgebremst zu werden. Ruft `onWindChanged` nur auf, wenn sich der
     * Schätzwert dadurch tatsächlich sichtbar verändert hat.
     */
    private suspend fun recomputeWindDir(nowMs: Long) {
        if (windHistory.isEmpty()) return
        val weighted = windHistory.map { sample ->
            val ageMs = (nowMs - sample.timestampMs).coerceAtLeast(0L)
            val recency = 0.5.pow(ageMs.toDouble() / Constants.WIND_HISTORY_DECAY_HALFLIFE_MS)
            sample.bisectorDeg to (sample.weight * recency)
        }
        val newDir = GeoUtils.circularMeanWeighted(weighted)
        val previousDir = windDir
        windDir = newDir
        windCalibrated = true
        if (previousDir == null || abs(GeoUtils.angleDiff(newDir, previousDir)) >= 0.1) {
            onWindChanged(newDir, true)
        }
    }

    fun startCalibration(currentlyValid: Boolean) {
        if (!currentlyValid) {
            status.setStatus("Kein GPS-Fix — Kalibrierung nicht möglich.", StatusLevel.RED)
            return
        }
        calibState = WindCalibState.WAIT_TACK1
        calibTack1 = null
        calibStartedAt = System.currentTimeMillis()
        calibTracker.reset()
        status.setStatus("Ruhigen Amwind-Kurs halten…", StatusLevel.NORMAL)
    }

    fun abortCalibration() {
        // Guard gegen den 15.08.-Diagnose-Log-Befund (erster Segeltörn):
        // ohne dieses Guard überschreibt ein "Abbrechen"-Tap/BLE-Befehl, der
        // eintrifft NACHDEM calibState schon (erfolgreich) auf IDLE gesprungen
        // ist — z.B. durch eine kurze Compose-Recomposition-Verzögerung des
        // "Abbrechen"-Buttons in WindScreen.kt —, minutenlang den korrekten
        // Erfolgs-/Wind-Shift-Status mit einem irreführenden AMBER-"abgebrochen"-
        // Banner, obwohl gar nichts lief und windCalibrated unverändert blieb.
        if (calibState == WindCalibState.IDLE) return
        calibState = WindCalibState.IDLE
        status.setStatus("Kalibrierung abgebrochen.", StatusLevel.AMBER)
    }

    suspend fun tickCalibration(fix: Fix) {
        if (calibState == WindCalibState.IDLE) return

        val timedOut = (System.currentTimeMillis() - calibStartedAt) > Constants.METHOD_TIMEOUT_MS
        if (timedOut) {
            vib.error4()
            status.setStatus("Kalibrierung: Timeout — bitte erneut versuchen.", StatusLevel.RED)
            calibState = WindCalibState.IDLE
            return
        }

        when (calibState) {
            WindCalibState.WAIT_TACK1 -> {
                calibTracker.sample(fix.cogDeg, fix.lat, fix.lon, fix.sogKn)
                val avg = calibTracker.steady(Constants.STEADY_COURSE_MAX_DEV)
                if (avg != null) {
                    calibTack1 = avg
                    calibTracker.reset()
                    calibState = WindCalibState.WAIT_TACK_CHANGE
                    calibStartedAt = System.currentTimeMillis()
                    vib.step1()
                    status.setStatus("Jetzt wenden!", StatusLevel.AMBER)
                }
            }
            WindCalibState.WAIT_TACK_CHANGE -> {
                val cog = fix.cogDeg
                val sog = fix.sogKn
                val t1 = calibTack1
                if (cog != null && sog != null && t1 != null && sog >= Constants.MIN_SPEED_KN) {
                    if (abs(GeoUtils.angleDiff(cog, t1)) >= Constants.TACK_CHANGE_MIN_DEG) {
                        calibState = WindCalibState.WAIT_TACK2
                        calibStartedAt = System.currentTimeMillis()
                        calibTracker.reset()
                        status.setStatus("Neuen ruhigen Kurs auf anderem Bug halten…", StatusLevel.NORMAL)
                    }
                }
            }
            WindCalibState.WAIT_TACK2 -> {
                calibTracker.sample(fix.cogDeg, fix.lat, fix.lon, fix.sogKn)
                val avg = calibTracker.steady(Constants.STEADY_COURSE_MAX_DEV)
                if (avg != null) {
                    val tack2 = avg
                    val t1 = calibTack1!!
                    val diff = abs(GeoUtils.angleDiff(t1, tack2))
                    if (diff in Constants.MIN_TACK_ANGLE_DEG..Constants.MAX_TACK_ANGLE_DEG) {
                        val newWindDir = GeoUtils.circularMean(listOf(t1, tack2))
                        calibState = WindCalibState.IDLE
                        continuousTracker.reset()
                        lastSteadyCOG = null
                        lastSteadyAtMs = null
                        tackSign = null
                        // Zeitstempel bewusst aus dem Fix (nicht System.currentTimeMillis()),
                        // damit ein Import (siehe DiagnosticsLogImporter) die ORIGINALEN
                        // Log-Zeiten trägt statt der Import-Ausführungszeit - sonst würde
                        // SessionSummaryEngine.buildReport()s from..toMs-Fensterung (auf
                        // Basis der GPS-Fix-Zeitstempel) diese Events beim Import
                        // fälschlich rausfiltern.
                        _sessionCalibrations.add(CalibrationEvent(fix.timestampMs))
                        vib.done2()
                        // Kalibrierungsmodus (Boots-Kalibrierung, siehe Klassen-Doku): der
                        // halbe Wendewinkel ist der tatsächlich gesegelte Am-Wind-Winkel -
                        // symmetrisch angenommen (kein Backstagsegel-Polardiagramm), passt
                        // damit zum bestehenden HomeEngine/CompetitionEngine-Modell.
                        if (calibrationModeEnabled) {
                            absorbCalibrationSample(diff / 2.0)
                            status.setStatus(
                                "Wind kalibriert: ${Math.round(newWindDir)}° — Wendewinkel: ${"%.0f".format(closehauledAngleDeg)}° ($closehauledSampleCount Kalibrierläufe)",
                                StatusLevel.GREEN,
                            )
                        } else {
                            status.setStatus("Wind kalibriert: ${Math.round(newWindDir)}°", StatusLevel.GREEN)
                        }
                        addWindSample(newWindDir, Constants.WIND_SAMPLE_WEIGHT_EXPLICIT_CALIB, fix.timestampMs)
                    } else {
                        vib.error4()
                        status.setStatus("Wendewinkel unplausibel (${"%.0f".format(diff)}°) — erneut versuchen.", StatusLevel.RED)
                        calibState = WindCalibState.IDLE
                    }
                }
            }
            WindCalibState.IDLE -> Unit
        }
    }

    /** Läuft immer, sobald kalibriert — pausiert nur während eines aktiven Trainings-Manövers (TURNING) */
    suspend fun tickContinuous(fix: Fix, target: GeoPoint?) {
        val wd = windDir ?: return
        continuousTracker.sample(fix.cogDeg, fix.lat, fix.lon, fix.sogKn)
        val avg = continuousTracker.steady(Constants.STEADY_COURSE_MAX_DEV) ?: return

        val awa = GeoUtils.angleDiff(avg, wd)

        // Smart-Modus (Boots-Kalibrierung, siehe Klassen-Doku): unabhängig von
        // der Wende-/Halsen-Erkennung unten - läuft bei jedem ruhigen Kurs
        // mit, der plausibel am Wind ODER plausibel vor dem Wind liegt,
        // nicht nur beim Bug-Wechsel. Beide Aufrufe sind einzeln durch ihr
        // eigenes Toleranzband gated (SMART_CLOSEHAULED_LEARN_BAND_DEG bzw.
        // SMART_DOWNWIND_LEARN_BAND_DEG um den jeweils aktuellen Schätzwert)
        // - ein Am-Wind-Kurs verändert also nie versehentlich den
        // Vorwind-Winkel und umgekehrt.
        if (smartModeEnabled) {
            maybeLearnCloseHauledAngle(abs(awa))
            maybeLearnDownwindAngle(abs(awa))
        }

        if (abs(awa) < Constants.TACK_SIGN_DEADZONE_DEG) return // Bug-Zuordnung unsicher
        val newTackSign = if (awa > 0) 1 else -1

        val curTackSign = tackSign
        if (curTackSign == null || newTackSign != curTackSign) {
            // Tages-Auswertung (siehe Klassendoku, sessionManeuvers): nur werten,
            // wenn vorher schon ein Bug bekannt war (curTackSign != null, sonst
            // ist es nur der allererste Kurs dieser Session) und ein
            // Referenzkurs vorliegt - derselbe Bug-Wechsel-Moment, den die
            // Header/Lift-Erkennung unten ohnehin schon per tackSign verfolgt.
            val previousSteady = lastSteadyCOG
            val previousSteadyAtMs = lastSteadyAtMs
            if (curTackSign != null && previousSteady != null) {
                val angle = abs(GeoUtils.angleDiff(avg, previousSteady))
                val isTack = abs(awa) < Constants.TACK_VS_GYBE_AWA_THRESHOLD_DEG
                // Zeitstempel aus dem Fix, siehe Kommentar bei _sessionCalibrations oben.
                _sessionManeuvers.add(TackEvent(fix.timestampMs, angle, isTack))

                // Automatischer Bootstrap/Nachschärfen der Windschätzung (siehe
                // Klassendoku, docs/Erweiterung_Windschaetzung_Robust.md Abschnitt
                // 3a): jede erkannte Wende ODER Halse liefert denselben Bisektor
                // wie die explizite Kalibrierung - ob es eine Wende oder Halse war,
                // spielt dafür KEINE Rolle (siehe Doku Abschnitt 3d, warum die
                // Speed-Dip-Unterscheidung dafür bewusst NICHT gebraucht wird): der
                // Bisektor zweier symmetrisch zum Wind gesegelter Kurse ergibt so
                // oder so die Windachse, nur mit 180°-Mehrdeutigkeit - aufgelöst,
                // indem die zur bereits bekannten Windrichtung `wd` näherliegende
                // der beiden möglichen Richtungen gewählt wird. Nur verwenden, wenn
                // beide Legs zeitlich nah beieinander liegen (MANEUVER_SAMPLE_MAX_GAP_MS)
                // - sonst könnte der Wind zwischen ihnen selbst schon gedreht haben.
                if (previousSteadyAtMs != null &&
                    fix.timestampMs - previousSteadyAtMs <= Constants.MANEUVER_SAMPLE_MAX_GAP_MS
                ) {
                    val rawBisector = GeoUtils.circularMean(listOf(previousSteady, avg))
                    val bisector = if (abs(GeoUtils.angleDiff(rawBisector, wd)) <= 90.0) {
                        rawBisector
                    } else {
                        GeoUtils.normalize360(rawBisector + 180.0)
                    }
                    addWindSample(bisector, Constants.WIND_SAMPLE_WEIGHT_MANEUVER, fix.timestampMs)
                }
            }
            tackSign = newTackSign
            lastSteadyCOG = avg
            lastSteadyAtMs = fix.timestampMs
            return
        }

        val lastSteady = lastSteadyCOG ?: run { lastSteadyCOG = avg; lastSteadyAtMs = fix.timestampMs; return }
        val shift = GeoUtils.angleDiff(avg, lastSteady)
        if (abs(shift) > Constants.WIND_SHIFT_MAX_PLAUSIBLE_DEG) {
            // Plausibilitäts-Filter, siehe Constants.WIND_SHIFT_MAX_PLAUSIBLE_DEG-Doku:
            // vermutlich eine verpasste Wende/Halse statt eines echten Shifts.
            // Referenzkurs trotzdem übernehmen (sonst meldet sich derselbe
            // Riesensprung jeden weiteren Tick erneut), aber ohne Status/Haptik
            // und ohne windDir mit dem unplausiblen Wert zu verfälschen.
            lastSteadyCOG = avg
            lastSteadyAtMs = fix.timestampMs
            return
        }
        if (abs(shift) >= Constants.WIND_SHIFT_THRESHOLD_DEG) {
            val prevSteady = lastSteady
            val newWindDirGuess = GeoUtils.normalize360(wd + shift)
            lastSteadyCOG = avg
            lastSteadyAtMs = fix.timestampMs
            // Zeitstempel aus dem Fix, siehe Kommentar bei _sessionCalibrations oben.
            val shiftAtMs = fix.timestampMs

            var isHeader: Boolean? = null
            if (target != null && fix.lat != null && fix.lon != null) {
                val targetBearing = GeoUtils.bearingDeg(fix.lat, fix.lon, target.lat, target.lon)
                val angleBefore = abs(GeoUtils.angleDiff(prevSteady, targetBearing))
                val angleAfter = abs(GeoUtils.angleDiff(avg, targetBearing))
                if (angleAfter > angleBefore) {
                    isHeader = true
                    vib.header3()
                    val sign = if (shift > 0) "+" else ""
                    status.setStatus("Wind-Shift: Header zum Ziel ($sign${"%.0f".format(shift)}°)", StatusLevel.AMBER)
                } else {
                    isHeader = false
                    val sign = if (shift > 0) "+" else ""
                    status.setStatus("Wind-Shift: Lift ($sign${"%.0f".format(shift)}°)", StatusLevel.NORMAL)
                }
            }
            _sessionWindShifts.add(WindShiftEvent(shiftAtMs, isHeader))
            addWindSample(newWindDirGuess, Constants.WIND_SAMPLE_WEIGHT_SHIFT_OBSERVATION, shiftAtMs)
        }
    }

    fun tickLog() {
        val wd = windDir
        if (!windCalibrated || wd == null) return
        val now = System.currentTimeMillis()
        if (now - lastWindLogAt < Constants.WIND_LOG_INTERVAL_MS) return
        lastWindLogAt = now

        if (_windLog.isEmpty()) {
            _windLog.add(WindLogPoint(0.0, now))
            lastRawWind = wd
        } else {
            val prevVal = _windLog.last().cumulativeDeg
            val newVal = prevVal + GeoUtils.angleDiff(wd, lastRawWind ?: wd)
            _windLog.add(WindLogPoint(newVal, now))
            if (_windLog.size > Constants.WIND_LOG_SIZE) _windLog.removeAt(0)
            lastRawWind = wd
        }
    }

    /** Netto-Drehung (letzter - erster Wert) und Schwankungsbreite (max-min) im Fenster */
    fun trendStats(): Pair<Double, Double>? {
        if (_windLog.size < 2) return null
        val values = _windLog.map { it.cumulativeDeg }
        val net = values.last() - values.first()
        val range = (values.maxOrNull()!! - values.minOrNull()!!)
        return net to range
    }
}
