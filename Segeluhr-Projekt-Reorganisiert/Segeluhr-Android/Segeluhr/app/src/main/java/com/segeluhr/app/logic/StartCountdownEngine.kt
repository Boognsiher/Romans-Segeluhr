package com.segeluhr.app.logic

import com.segeluhr.app.core.Constants
import com.segeluhr.app.core.HapticFeedback
import com.segeluhr.app.data.model.RaceState

/**
 * 5-4-1-Startsequenz, Abschnitt 5. Countdown ab 5 Minuten, Signal bei 4:00
 * und 1:00, starkes Signal bei 0:00 (Start). Danach läuft die Zeit als
 * Race-Timer weiter (Anzeige "+m:ss").
 *
 * [onRaceStart] wird genau einmal aufgerufen, wenn die Restzeit 0:00
 * erreicht — Anknüpfungspunkt für den automatischen Racemode-Start
 * (siehe docs/Erweiterung_Racemodus_Automatik.md).
 */
class StartCountdownEngine(
    private val vib: HapticFeedback,
    private val onRaceStart: () -> Unit = {},
) {

    var raceState: RaceState = RaceState.MENU
        private set
    var countdownEndsAt: Long? = null
        private set
    private val firedMarks = mutableSetOf<Int>()

    fun start() {
        countdownEndsAt = System.currentTimeMillis() + Constants.COUNTDOWN_DURATION_MS
        firedMarks.clear()
        raceState = RaceState.COUNTDOWN
    }

    fun reset() {
        countdownEndsAt = null
        raceState = RaceState.MENU
        firedMarks.clear()
    }

    /** Gibt die anzuzeigende Restzeit (negativ = seit Start vergangene Zeit) in Sekunden zurück, oder null */
    fun tick(): Int? {
        val endsAt = countdownEndsAt ?: return null
        val remainingMs = endsAt - System.currentTimeMillis()
        val remainingS = Math.ceil(remainingMs / 1000.0).toInt()

        if (raceState == RaceState.COUNTDOWN) {
            for (mark in Constants.COUNTDOWN_MARKS_S) {
                if (remainingS <= mark && !firedMarks.contains(mark)) {
                    firedMarks.add(mark)
                    vib.step1()
                }
            }
            if (remainingS <= 0 && !firedMarks.contains(0)) {
                firedMarks.add(0)
                vib.startSignal()
                raceState = RaceState.RACE
                onRaceStart()
            }
            return remainingS
        }
        // RACE: vergangene Zeit seit Start (positiv)
        return (-remainingMs / 1000).toInt()
    }

    /**
     * Rundet die aktuelle Restzeit auf die nächst TIEFERE volle Minute ab —
     * zum Synchronisieren mit einem gehörten Startsignal (Kanonenschuss/
     * Hupe des Wettfahrtausschusses), falls die eigene Uhr etwas abweicht.
     * Wirkt nur während der laufenden COUNTDOWN-Phase.
     */
    fun syncToNextMinute() {
        val endsAt = countdownEndsAt ?: return
        if (raceState != RaceState.COUNTDOWN) return
        val remainingMs = endsAt - System.currentTimeMillis()
        val remainingS = Math.ceil(remainingMs / 1000.0).toInt().coerceAtLeast(0)
        val roundedDownS = (remainingS / 60) * 60
        countdownEndsAt = System.currentTimeMillis() + roundedDownS * 1000L
    }
}
