package com.segeluhr.app.logic

import com.segeluhr.app.core.*
import com.segeluhr.app.data.model.ManeuverRecord
import com.segeluhr.app.data.model.TrainMode
import com.segeluhr.app.data.model.TrainState
import kotlin.math.abs
import kotlin.random.Random

/**
 * Trainingsmodus, Abschnitt 6 der Spezifikation: Manöver-Ansage & Ablauf
 * (6.2), Bewertung (6.3) und Racemode-Bojen-Navigation (6.4).
 *
 * "Race" ist hier bewusst der einfache TRAININGS-Modus (manuell im
 * Training-Tab wählbar, braucht 2 Bojen, zufällige Wende/Halse-Kommandos
 * wie die anderen Trainingsmodi). Der Modus fürs echte Rennen mit
 * automatischem Start bei 0:00, Luv-Doppelboje (Entlastungsboje) und
 * Windschätzung ohne Bojen heisst "Competition" und lebt in
 * CompetitionEngine — siehe docs/Erweiterung_Competition_Modus.md.
 */
class TrainingEngine(
    private val vib: HapticFeedback,
    private val status: StatusSink,
    private val onManeuverLogged: suspend (ManeuverRecord) -> Unit,
) {
    var trainMode: TrainMode = TrainMode.OFF
        private set
    var trainState: TrainState = TrainState.OFF
        private set

    private val waitTracker = CourseTracker()
    private val turnTracker = CourseTracker()
    private var waitUntil: Long = 0L
    private var preCOG: Double? = null
    private var preSOG: Double? = null
    private var minSOG: Double? = null
    private var commandedAt: Long = 0L
    private var turnStartTime: Long = 0L
    // Öffentlich lesbar (Erweiterung, siehe docs/Erweiterung_BLE_Wind_RaceStatus.md):
    // die T-Watch-Firmware muss wissen, ob das kommandierte Manöver eine
    // Wende oder eine Halse ist, um den passenden Screen/Pfeil zu zeigen.
    var isTackManeuver: Boolean = true
        private set

    // Racemode Bojen-Navigation (6.4) — Rundungserkennung seit 10.08.2026
    // vereinheitlicht mit CompetitionEngine, siehe
    // docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md und
    // logic/MarkRoundingDetector.kt.
    private val roundingDetector = MarkRoundingDetector()
    var activeBuoyIdx: Int? = null
        private set

    data class PendingConfirmation(val waypointKey: String, val candidatePosition: GeoPoint)

    /** Amwind/Vorwind-Kurswechsel abseits der aktiven Boje erkannt — wartet auf Bestätigen/Ablehnen (ViewModel). */
    var pendingConfirmation: PendingConfirmation? = null
        private set

    fun setMode(mode: TrainMode, buoy1: GeoPoint?, buoy2: GeoPoint?, fix: Fix) {
        trainMode = mode
        if (mode == TrainMode.OFF) {
            trainState = TrainState.OFF
        } else {
            newWaitTimer()
            if (mode == TrainMode.RACE && buoy1 != null && buoy2 != null && activeBuoyIdx == null) {
                chooseNearerBuoy(buoy1, buoy2, fix)
            }
        }
    }

    private fun newWaitTimer() {
        waitUntil = System.currentTimeMillis() + Random.nextLong(
            Constants.TRAIN_MIN_INTERVAL_MS, Constants.TRAIN_MAX_INTERVAL_MS
        )
        trainState = TrainState.WAITING
        waitTracker.reset()
    }

    private fun chooseNearerBuoy(buoy1: GeoPoint, buoy2: GeoPoint, fix: Fix) {
        val lat = fix.lat ?: return
        val lon = fix.lon ?: return
        val d1 = GeoUtils.distanceMeters(lat, lon, buoy1.lat, buoy1.lon)
        val d2 = GeoUtils.distanceMeters(lat, lon, buoy2.lat, buoy2.lon)
        activeBuoyIdx = if (d1 <= d2) 0 else 1
    }

    fun activeBuoy(buoy1: GeoPoint?, buoy2: GeoPoint?): GeoPoint? =
        when (activeBuoyIdx) { 0 -> buoy1; 1 -> buoy2; else -> null }

    private fun roundBuoy() {
        activeBuoyIdx = if (activeBuoyIdx == 0) 1 else 0
        roundingDetector.reset()
        vib.rounding6()
        status.setStatus("Boje gerundet!", StatusLevel.GREEN)
    }

    /** Abschnitt 6.2: Manöver-Ansage & Ablauf (WAITING -> COMMANDED -> TURNING -> WAITING) */
    suspend fun tick(fix: Fix, windDir: Double?, windCalibrated: Boolean) {
        if (trainMode == TrainMode.OFF || !windCalibrated || windDir == null) return

        when (trainState) {
            TrainState.WAITING -> {
                waitTracker.sample(fix.cogDeg, fix.lat, fix.lon, fix.sogKn)
                val avg = waitTracker.steady(Constants.STEADY_COURSE_MAX_DEV)
                if (avg != null && System.currentTimeMillis() >= waitUntil) {
                    val awa = GeoUtils.angleDiff(avg, windDir)
                    var modeOk = false
                    var isTack = true
                    when (trainMode) {
                        TrainMode.TACK_ONLY -> { isTack = true; modeOk = abs(awa) < 90 }
                        TrainMode.JIBE_ONLY -> { isTack = false; modeOk = abs(awa) >= 90 }
                        TrainMode.RACE -> { isTack = abs(awa) < 90; modeOk = true }
                        TrainMode.OFF -> Unit
                    }
                    if (modeOk) {
                        preCOG = avg
                        preSOG = fix.sogKn
                        minSOG = fix.sogKn
                        commandedAt = System.currentTimeMillis()
                        isTackManeuver = isTack
                        trainState = TrainState.COMMANDED
                        vib.maneuverCmd()
                        status.setStatus(if (isTack) "Jetzt WENDEN!" else "Jetzt HALSEN!", StatusLevel.AMBER)
                        waitTracker.reset()
                    }
                }
            }
            TrainState.COMMANDED -> {
                if (System.currentTimeMillis() - commandedAt > Constants.TRAIN_MANEUVER_TIMEOUT_MS) {
                    vib.error4()
                    status.setStatus("Manöver verpasst.", StatusLevel.RED)
                    newWaitTimer()
                    return
                }
                val cog = fix.cogDeg
                val pre = preCOG
                if (cog != null && pre != null && abs(GeoUtils.angleDiff(cog, pre)) >= Constants.TRAIN_TURN_START_DEG) {
                    turnStartTime = System.currentTimeMillis()
                    trainState = TrainState.TURNING
                    turnTracker.reset()
                }
            }
            TrainState.TURNING -> {
                fix.sogKn?.let { s -> minSOG = minOf(minSOG ?: s, s) }
                if (System.currentTimeMillis() - commandedAt > Constants.TRAIN_MANEUVER_TIMEOUT_MS) {
                    vib.error4()
                    status.setStatus("Manöver verpasst (Timeout während Drehung).", StatusLevel.RED)
                    newWaitTimer()
                    return
                }
                turnTracker.sample(fix.cogDeg, fix.lat, fix.lon, fix.sogKn)
                val avg = turnTracker.steady(Constants.TRAIN_STEADY_MAX_DEV_MANEUVER)
                val pre = preCOG
                if (avg != null && pre != null && abs(GeoUtils.angleDiff(avg, pre)) >= Constants.TRAIN_TURN_START_DEG) {
                    val turnEndTime = System.currentTimeMillis()
                    val durationS = (turnEndTime - turnStartTime) / 1000.0
                    val preSogVal = preSOG ?: 0.0
                    val speedLossPct = if (preSogVal > 0) (preSogVal - (minSOG ?: preSogVal)) / preSogVal * 100.0 else 0.0
                    val overTime = maxOf(0.0, durationS - 5.0)
                    val score = GeoUtils.clamp(100.0 - speedLossPct * 2 - overTime * 3, 0.0, 100.0)
                    val record = ManeuverRecord(
                        isTack = isTackManeuver,
                        durationS = durationS,
                        speedLossPct = speedLossPct,
                        score = score,
                        timestampMs = System.currentTimeMillis(),
                    )
                    onManeuverLogged(record)
                    vib.done2()
                    status.setStatus("Manöver abgeschlossen — Score ${"%.0f".format(score)}", StatusLevel.GREEN)
                    newWaitTimer()
                }
            }
            TrainState.OFF -> Unit
        }
    }

    /**
     * Abschnitt 6.4: Racemode-Bojen-Navigation, pausiert während COMMANDED/
     * TURNING. Rundungserkennung seit 10.08.2026 über [MarkRoundingDetector]
     * (siehe docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md) — braucht
     * deshalb jetzt kalibrierten Wind (vorher nicht nötig, die alte
     * "Kurswechsel + zielt auf andere Boje"-Prüfung kam ohne Wind aus).
     */
    fun tickRaceNav(fix: Fix, windDir: Double?, windCalibrated: Boolean, buoy1: GeoPoint?, buoy2: GeoPoint?) {
        if (trainMode != TrainMode.RACE || buoy1 == null || buoy2 == null) return
        if (trainState == TrainState.COMMANDED || trainState == TrainState.TURNING) return
        if (pendingConfirmation != null) return // wartet auf Bestätigen/Ablehnen, siehe [confirmPendingRounding]
        if (activeBuoyIdx == null) chooseNearerBuoy(buoy1, buoy2, fix)
        val target = activeBuoy(buoy1, buoy2) ?: return

        when (val result = roundingDetector.tick(fix, windDir, windCalibrated, target)) {
            MarkRoundingDetector.Result.Rounded -> roundBuoy()
            is MarkRoundingDetector.Result.NeedsConfirmation -> {
                val key = if (activeBuoyIdx == 0) "buoy1" else "buoy2"
                pendingConfirmation = PendingConfirmation(key, result.candidatePosition)
                vib.roundingConfirmNeeded()
                status.setStatus("Boje noch nicht erreicht — trotzdem als gerundet werten?", StatusLevel.AMBER)
            }
            // AutoRounded kann hier praktisch nicht vorkommen - target ist
            // durch die obige Vorbedingung (buoy1/buoy2 beide gesetzt) nie
            // null, siehe MarkRoundingDetector-Klassendoku.
            is MarkRoundingDetector.Result.AutoRounded, MarkRoundingDetector.Result.None -> Unit
        }
    }

    /** "Ja, Boje ist hier" — Wegpunkt-Korrektur ist zu diesem Zeitpunkt bereits vom ViewModel persistiert. */
    fun confirmPendingRounding() {
        if (pendingConfirmation == null) return
        pendingConfirmation = null
        roundBuoy()
    }

    /** "Nein, anderer Grund" — Kurswechsel war keine Rundung, Boje bleibt unverändert. */
    fun rejectPendingRounding() {
        pendingConfirmation = null
        roundingDetector.reset()
        status.setStatus("Kurswechsel nicht als Bojen-Rundung gewertet.", StatusLevel.NORMAL)
    }
}
