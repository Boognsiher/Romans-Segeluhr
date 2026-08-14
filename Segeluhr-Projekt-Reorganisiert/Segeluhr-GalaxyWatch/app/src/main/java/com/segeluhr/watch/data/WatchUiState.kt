package com.segeluhr.watch.data

import com.segeluhr.watch.ble.BleProtocol
import com.segeluhr.watch.ble.ConnectionState

/**
 * Vereinfachter Boot-Zustand fürs Nav-Tab — Kotlin-Entsprechung von
 * BoatState/updateBoatState() in Segeluhr_TWatch_Ultra.ino (Priorität:
 * Manöver > Competition > Countdown > Training > Heimweg > Bereit).
 */
enum class BoatState(val label: String) {
    MANEUVER_ALERT("Manöver!"),
    COMPETITION("Wettfahrt"),
    COUNTDOWN("Countdown"),
    TRAINING("Training"),
    HEIMWEG("Heimweg"),
    IDLE("Bereit"),
}

fun deriveBoatState(race: BleProtocol.RaceData?, home: BleProtocol.HomeData?): BoatState = when {
    race != null && race.maneuverNeeded -> BoatState.MANEUVER_ALERT
    race != null && race.competitionLeg != null -> BoatState.COMPETITION
    race != null && race.raceStateOrdinal == 1 -> BoatState.COUNTDOWN
    race != null && race.raceStateOrdinal == 2 -> BoatState.TRAINING
    home != null && home.active -> BoatState.HEIMWEG
    else -> BoatState.IDLE
}

/** Bündelt alle Live-Daten, die die Compose-Screens brauchen. */
data class WatchUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val gps: BleProtocol.GpsData? = null,
    val phoneBatteryPct: Int? = null,
    val ownBatteryPct: Int? = null,
    val home: BleProtocol.HomeData? = null,
    val wind: BleProtocol.WindData? = null,
    val race: BleProtocol.RaceData? = null,
    val waypoints: BleProtocol.WaypointsStatus? = null,
    /** Kurzer Bestätigungs-Text nach einem Button-Tap (z.B. "Wegpunkt gesendet"), analog zu showCommandOverlay() auf der Ultra. */
    val commandOverlay: String? = null,
) {
    val boatState: BoatState get() = deriveBoatState(race, home)

    /** Ziel-Geschwindigkeit (VMC): Heimweg hat Vorrang vor Competition, siehe navScreenUpdate() auf der Ultra. */
    val vmcKn: Double?
        get() = when {
            home?.active == true && home.vmcKn != null -> home.vmcKn
            race?.competitionLeg != null && race.vmcKn != null -> race.vmcKn
            else -> null
        }
}
