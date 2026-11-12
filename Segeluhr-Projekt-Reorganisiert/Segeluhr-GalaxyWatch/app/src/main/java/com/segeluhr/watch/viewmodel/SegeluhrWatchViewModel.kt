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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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

    // ---- Physischer Taster als Touch-Ersatz (22.09.2026, Roman-Wunsch, siehe
    // docs/Erweiterung_GalaxyWatch_App.md "Physischer Taster") ----
    // currentPage ist ab jetzt die Wahrheitsquelle fürs aktuell sichtbare Tab
    // (SegelnApp.kt synct den HorizontalPager in BEIDE Richtungen dagegen),
    // damit ein Tastendruck von woher auch immer die letzte Anzeige weiss.
    // Zuordnung 22.09.2026 getauscht (Roman-Feedback): kurzer Druck = Aktion
    // (öfter gebraucht, soll schneller gehen), langer Druck = Tab-Wechsel.
    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage

    fun setCurrentPage(page: Int) {
        _currentPage.value = page
    }

    /**
     * Langer Tastendruck (siehe MainActivity.onKeyUp): normalerweise zur
     * nächsten Anzeige weiterschalten. Steht gerade eine Bojen-Rundungs-
     * Rückfrage aus (siehe [onPhysicalButtonAction]), wird der Taster
     * stattdessen komplett von der Rückfrage belegt — Tab-Wechsel ergibt
     * währenddessen ohnehin wenig Sinn (Overlay liegt sowieso über allem,
     * siehe SegelnApp.kt) — und langer Druck wird zum "Nein" (22.09.2026,
     * Roman-Feedback: sonst gäbe es auf der Uhr keine zuverlässige,
     * touch-unabhängige Möglichkeit, aktiv abzulehnen — ein verpasstes
     * "Nein" landet nach ROUNDING_CONFIRM_TIMEOUT_MS sonst automatisch beim
     * "Ja" (Auto-Timeout, siehe SegeluhrViewModel.updatePendingBuoyConfirmation()
     * am Handy).
     */
    fun onPhysicalButtonNextPage() {
        if (uiState.value.race?.roundingConfirmPending == true) {
            rejectBuoyRounding()
            hapticPlayer.play(BleProtocol.HAPTIC_STEP1)
            showOverlay("Abgelehnt")
            return
        }
        _currentPage.value = (_currentPage.value + 1) % TAB_COUNT
        hapticPlayer.play(BleProtocol.HAPTIC_STEP1)
    }

    /**
     * Kurzer Tastendruck: kontextabhängige Hauptaktion, damit Countdown-Start
     * und Bojen-Rundungs-Bestätigung (die zwei zeitkritischen Aktionen beim
     * Segeln) auch mit nassen/behandschuhten Händen zuverlässig auslösbar
     * sind — Touch bleibt für alles andere (Einstellungen, Wegpunkte,
     * Reset/Sync), das sich im Stillstand erledigen lässt.
     * Bojen-Rückfrage hat Vorrang vor dem Tab-Check, weil sie tab-unabhängig
     * als Overlay erscheint (siehe SegelnApp.kt) und die dringendere der
     * beiden Aktionen ist. Solange sie aussteht, ist der Taster komplett
     * von ihr belegt: kurz = Ja, lang = Nein (siehe [onPhysicalButtonNextPage]).
     */
    fun onPhysicalButtonAction() {
        when {
            uiState.value.race?.roundingConfirmPending == true -> {
                confirmBuoyRounding()
                hapticPlayer.play(BleProtocol.HAPTIC_DONE2)
                showOverlay("Bestätigt")
            }
            // CD-Tab: Start/Sync/Reset je nach Rennstatus (aus dem Branch
            // galaxy-watch-app-interface, 02.09.2026), nicht nur Start.
            _currentPage.value == CD_TAB_INDEX -> {
                hapticPlayer.play(BleProtocol.HAPTIC_DONE2)
                when (uiState.value.race?.raceStateOrdinal ?: 0) {
                    0 -> { startCountdown(); showOverlay("Start!") }
                    1 -> { syncCountdown(); showOverlay("Sync") }
                    else -> { resetCountdown(); showOverlay("Reset") }
                }
            }
            // Nav-Tab: Pin/Boot der Startlinie setzen (02.09.2026, Roman-Wunsch).
            _currentPage.value == NAV_TAB_INDEX -> {
                hapticPlayer.play(BleProtocol.HAPTIC_STEP1)
                setStartLineWaypointHere()
            }
        }
    }

    /**
     * Startlinie per Taste (02.09.2026, Roman-Wunsch): Pin/Boot werden laut
     * Roman erfahrungsgemäss noch kurz vor dem Start final gelegt/
     * korrigiert — anders als Marke1/Lee-Boje/Gate (die stehen laut Kurs-
     * Modell schon vorher fest) brauchen sie deshalb eine eigene Tasten-
     * Aktion, nicht nur Touch im Menu-Tab. Feste, zustandslose Regel statt
     * Umschalter: Pin fehlt → Pin, sonst Boot fehlt → Boot, sonst (beide
     * schon gesetzt) → wieder Pin (der wird laut Startlinie-Bias-Logik
     * erfahrungsgemäss öfter nachjustiert als das Committee-Boot). Der
     * CD-Tab-Taste-Hinweistext auf NavScreen.kt spiegelt dieselbe Regel.
     */
    private fun setStartLineWaypointHere() {
        val wp = uiState.value.waypoints
        val id = if (wp?.pinSet != true) BleProtocol.WaypointId.PIN else BleProtocol.WaypointId.BOAT
        bleClient.sendCommand(BleProtocol.CMD_SET_WAYPOINT, id)
        showOverlay(if (id == BleProtocol.WaypointId.PIN) "Pin gesetzt" else "Boot gesetzt")
    }

    // Auto-Fokus auf den Nav-Tab beim Wettfahrt-Start (02.09.2026,
    // Roman-Wunsch): im Startmoment (0:00-Signal, StartCountdownEngine
    // COUNTDOWN->RACE) sind beide Hände typischerweise am Boot beschäftigt
    // - die App soll dann von selbst auf den Tab mit SOG/VMC/Manöver-Ampel
    // springen, statt dass man das noch manuell nachholen muss. Reiner
    // Zustandswechsel-Trigger (raceStateOrdinal 1->2), unabhängig davon, ob
    // es sich um eine echte Competition oder einen freien Race-Timer
    // handelt - beide feuern denselben onRaceStart() in StartCountdownEngine.
    private var lastRaceStateOrdinal: Int? = null

    private fun watchForRaceStart() {
        bleClient.raceData.onEach { race ->
            val prev = lastRaceStateOrdinal
            val cur = race?.raceStateOrdinal
            if (prev == 1 && cur == 2) _currentPage.value = NAV_TAB_INDEX
            lastRaceStateOrdinal = cur
        }.launchIn(viewModelScope)
    }

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
        watchForRaceStart()
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

    companion object {
        // Muss synchron zu SegelnApp.TAB_TITLES bleiben (Nav/Wind/Heim/CD/Man/Menu).
        const val TAB_COUNT = 6
        const val NAV_TAB_INDEX = 0
        const val CD_TAB_INDEX = 3
    }
}
