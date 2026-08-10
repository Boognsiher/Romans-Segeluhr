package com.segeluhr.app.logic

import com.segeluhr.app.core.*
import com.segeluhr.app.data.model.WindCalibState
import com.segeluhr.app.data.model.WindLogPoint
import kotlin.math.abs

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
 *
 * Es gibt mehrere benannte Boots-Profile (siehe
 * `SettingsRepository.boatProfilesFlow`) — diese Klasse kennt zu jedem
 * Zeitpunkt nur das gerade AKTIVE Profil (`activeProfileId`). Beim
 * Profilwechsel ruft der ViewModel [restoreBoatProfile] erneut auf.
 */
class WindEngine(
    private val vib: HapticFeedback,
    private val status: StatusSink,
    private val onWindChanged: suspend (windDir: Double, calibrated: Boolean) -> Unit,
    private val onBoatProfileChanged: suspend (profileId: String, closehauledAngleDeg: Double, sampleCount: Int) -> Unit,
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
    private var tackSign: Int? = null

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
    var calibrationModeEnabled: Boolean = false
        private set
    var smartModeEnabled: Boolean = false
        private set
    private var lastPersistedCloseHauledAngle: Double = Constants.DEFAULT_CLOSEHAULED_ANGLE_DEG

    /** Beim App-Start aus der Persistenz laden */
    fun restore(windDir: Double?, calibrated: Boolean) {
        this.windDir = windDir
        this.windCalibrated = calibrated
    }

    /**
     * Beim App-Start UND bei jedem Profilwechsel aufrufen (siehe
     * SettingsRepository.boatProfilesFlow) — schaltet Kalibrierungsmodus/
     * Smart-Modus auf das neu aktive Profil um. Modi selbst
     * (`calibrationModeEnabled`/`smartModeEnabled`) bleiben dabei
     * unverändert, nur WAS sie kalibrieren wechselt.
     */
    fun restoreBoatProfile(profileId: String, closehauledAngleDeg: Double, sampleCount: Int) {
        this.activeProfileId = profileId
        this.closehauledAngleDeg = closehauledAngleDeg
        this.closehauledSampleCount = sampleCount
        this.lastPersistedCloseHauledAngle = closehauledAngleDeg
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

    /** Wind-Tab-Button "Wendewinkel zurücksetzen" — wirft nur den gelernten Winkel DES AKTIVEN Profils weg. */
    suspend fun resetBoatProfile() {
        closehauledAngleDeg = Constants.DEFAULT_CLOSEHAULED_ANGLE_DEG
        closehauledSampleCount = 0
        lastPersistedCloseHauledAngle = Constants.DEFAULT_CLOSEHAULED_ANGLE_DEG
        onBoatProfileChanged(activeProfileId, closehauledAngleDeg, closehauledSampleCount)
        status.setStatus("Wendewinkel auf Standardwert (${Constants.DEFAULT_CLOSEHAULED_ANGLE_DEG.toInt()}°) zurückgesetzt.", StatusLevel.NORMAL)
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

    private suspend fun persistBoatProfileIfChanged() {
        if (abs(closehauledAngleDeg - lastPersistedCloseHauledAngle) < Constants.CLOSEHAULED_PERSIST_THRESHOLD_DEG) return
        lastPersistedCloseHauledAngle = closehauledAngleDeg
        onBoatProfileChanged(activeProfileId, closehauledAngleDeg, closehauledSampleCount)
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
                        windDir = newWindDir
                        windCalibrated = true
                        calibState = WindCalibState.IDLE
                        continuousTracker.reset()
                        lastSteadyCOG = null
                        tackSign = null
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
                        onWindChanged(newWindDir, true)
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
        // der Wende-Erkennung unten - läuft bei jedem ruhigen Kurs mit, der
        // plausibel am Wind liegt, nicht nur beim Bug-Wechsel.
        if (smartModeEnabled) {
            maybeLearnCloseHauledAngle(abs(awa))
        }

        if (abs(awa) < Constants.TACK_SIGN_DEADZONE_DEG) return // Bug-Zuordnung unsicher
        val newTackSign = if (awa > 0) 1 else -1

        val curTackSign = tackSign
        if (curTackSign == null || newTackSign != curTackSign) {
            tackSign = newTackSign
            lastSteadyCOG = avg
            return
        }

        val lastSteady = lastSteadyCOG ?: run { lastSteadyCOG = avg; return }
        val shift = GeoUtils.angleDiff(avg, lastSteady)
        if (abs(shift) >= Constants.WIND_SHIFT_THRESHOLD_DEG) {
            val prevSteady = lastSteady
            val newWindDir = GeoUtils.normalize360(wd + shift)
            windDir = newWindDir
            lastSteadyCOG = avg

            if (target != null && fix.lat != null && fix.lon != null) {
                val targetBearing = GeoUtils.bearingDeg(fix.lat, fix.lon, target.lat, target.lon)
                val angleBefore = abs(GeoUtils.angleDiff(prevSteady, targetBearing))
                val angleAfter = abs(GeoUtils.angleDiff(avg, targetBearing))
                if (angleAfter > angleBefore) {
                    vib.header3()
                    val sign = if (shift > 0) "+" else ""
                    status.setStatus("Wind-Shift: Header zum Ziel ($sign${"%.0f".format(shift)}°)", StatusLevel.AMBER)
                } else {
                    val sign = if (shift > 0) "+" else ""
                    status.setStatus("Wind-Shift: Lift ($sign${"%.0f".format(shift)}°)", StatusLevel.NORMAL)
                }
            }
            onWindChanged(newWindDir, true)
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
