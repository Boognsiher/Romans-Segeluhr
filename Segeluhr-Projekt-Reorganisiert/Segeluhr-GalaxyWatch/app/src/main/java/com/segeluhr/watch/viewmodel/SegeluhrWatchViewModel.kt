package com.segeluhr.watch.viewmodel

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.segeluhr.watch.ble.BleProtocol
import com.segeluhr.watch.ble.WatchBleBridge
import com.segeluhr.watch.ble.WatchBleForegroundService
import com.segeluhr.watch.core.HapticPlayer
import com.segeluhr.watch.data.WatchUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Verbindet [com.segeluhr.watch.ble.WatchBleClient] mit den Compose-Screens
 * — Kotlin-Entsprechung der refreshActiveScreen()/handleControlCommand()-
 * Logik in Segeluhr_TWatch_Ultra.ino, nur reaktiv über StateFlow statt
 * 1-Hz-Tick-Polling (Notifies kommen ohnehin schon vom Handy im 1Hz-Takt).
 */
class SegeluhrWatchViewModel(application: Application) : AndroidViewModel(application) {

    private val bleClient = WatchBleBridge.getInstance(application)
    private val hapticPlayer = HapticPlayer(application)

    private val _commandOverlay = MutableStateFlow<String?>(null)
    private val _ownBatteryPct = MutableStateFlow<Int?>(null)

    // Kotlins combine() gibt es nur bis 5 Argumente auf einmal - deshalb in
    // zwei Zwischen-Datenklassen gestückelt statt einem einzigen Riesen-Aufruf.
    private data class WatchUiStatePartial1(
        val conn: com.segeluhr.watch.ble.ConnectionState, val gps: BleProtocol.GpsData?,
        val phoneBatt: Int?, val home: BleProtocol.HomeData?, val wind: BleProtocol.WindData?,
    )
    private data class WatchUiStatePartial2(
        val p1: WatchUiStatePartial1, val race: BleProtocol.RaceData?,
        val wp: BleProtocol.WaypointsStatus?, val ownBatt: Int?,
    )

    val uiState: StateFlow<WatchUiState> = combine(
        bleClient.connectionState, bleClient.gpsData, bleClient.phoneBatteryPct, bleClient.homeData, bleClient.windData,
    ) { conn, gps, phoneBatt, home, wind -> WatchUiStatePartial1(conn, gps, phoneBatt, home, wind) }
        .combine(bleClient.raceData) { p1, race -> p1 to race }
        .combine(bleClient.waypointsStatus) { (p1, race), wp -> Triple(p1, race, wp) }
        .combine(_ownBatteryPct) { (p1, race, wp), ownBatt -> WatchUiStatePartial2(p1, race, wp, ownBatt) }
        .combine(_commandOverlay) { p2, overlay ->
            WatchUiState(
                connectionState = p2.p1.conn, gps = p2.p1.gps, phoneBatteryPct = p2.p1.phoneBatt,
                ownBatteryPct = p2.ownBatt, home = p2.p1.home, wind = p2.p1.wind, race = p2.race,
                waypoints = p2.wp, commandOverlay = overlay,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WatchUiState())

    init {
        bleClient.hapticEvents.onEach { hapticPlayer.play(it) }.launchIn(viewModelScope)
        WatchBleForegroundService.start(application)
        pollOwnBattery()
    }

    private fun pollOwnBattery() {
        viewModelScope.launch {
            while (true) {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val status: Intent? = getApplication<Application>().registerReceiver(null, filter)
                val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                _ownBatteryPct.value = if (level >= 0 && scale > 0) level * 100 / scale else null
                delay(60_000L)
            }
        }
    }

    private fun showOverlay(text: String) {
        viewModelScope.launch {
            _commandOverlay.value = text
            delay(2500L)
            if (_commandOverlay.value == text) _commandOverlay.value = null
        }
    }

    // ---- Menü-Tab-Aktionen (CMD_*), analog zu den cb*()-Callbacks in Segeluhr_TWatch_Ultra.ino ----

    fun startCountdown() = bleClient.sendCommand(BleProtocol.CMD_COUNTDOWN_START)
    fun resetCountdown() = bleClient.sendCommand(BleProtocol.CMD_COUNTDOWN_RESET)
    fun syncCountdown() = bleClient.sendCommand(BleProtocol.CMD_COUNTDOWN_SYNC_NEXT_MINUTE)

    fun startWindCalibration() {
        bleClient.sendCommand(BleProtocol.CMD_WIND_CALIBRATE_START)
        showOverlay("Kalibrierung gestartet")
    }

    fun abortWindCalibration() {
        bleClient.sendCommand(BleProtocol.CMD_WIND_CALIBRATE_ABORT)
        showOverlay("Kalibrierung abgebrochen")
    }

    fun setTrainModeOff() = bleClient.sendCommand(BleProtocol.CMD_TRAIN_MODE_OFF)
    fun setTrainModeTackOnly() = bleClient.sendCommand(BleProtocol.CMD_TRAIN_MODE_TACK_ONLY)
    fun setTrainModeJibeOnly() = bleClient.sendCommand(BleProtocol.CMD_TRAIN_MODE_JIBE_ONLY)
    fun setTrainModeRace() = bleClient.sendCommand(BleProtocol.CMD_TRAIN_MODE_RACE)

    fun toggleHomeMode() = bleClient.sendCommand(BleProtocol.CMD_HOME_MODE_TOGGLE)
    fun endCompetition() = bleClient.sendCommand(BleProtocol.CMD_COMPETITION_END)
    fun clearLog() = bleClient.sendCommand(BleProtocol.CMD_CLEAR_LOG)

    fun confirmBuoyRounding() = bleClient.sendCommand(BleProtocol.CMD_CONFIRM_BUOY_ROUNDING)
    fun rejectBuoyRounding() = bleClient.sendCommand(BleProtocol.CMD_REJECT_BUOY_ROUNDING)

    /** Setzt einen Wegpunkt an der AKTUELLEN Boots-Position (Handy-GPS), wie bei der Ultra. */
    fun setWaypointHere(id: Int) {
        bleClient.sendCommand(BleProtocol.CMD_SET_WAYPOINT, id)
        showOverlay("Wegpunkt gesendet")
    }

    fun clearWaypoint(id: Int) {
        bleClient.sendCommand(BleProtocol.CMD_CLEAR_WAYPOINT, id)
        showOverlay("Wegpunkt gelöscht")
    }

    /** Setzt einen Wegpunkt per Karten-Tap auf der Uhr (neu, siehe docs/Erweiterung_GalaxyWatch_App.md). */
    fun setWaypointAtCoords(id: Int, lat: Double, lon: Double) {
        bleClient.sendWaypointAtCoords(id, lat, lon)
        showOverlay("Wegpunkt auf Karte gesetzt")
    }
}
