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

    // ---- Hardware-Tasten-Bedienung (02.09.2026, Roman-Wunsch) ----
    // Im Wasserdicht-Modus ist der Touchscreen deaktiviert - einzige
    // verbleibende Bedienung ist die untere ("Zurück"-)Taste der Watch 5
    // Pro, siehe MainActivity.dispatchKeyEvent(). Die obere Taste ist auf
    // Wear OS i.d.R. system-reserviert (Power/Bixby/Home) und wird
    // Vordergrund-Apps normalerweise nicht zugestellt - MainActivity fängt
    // sie defensiv trotzdem ab, unverifiziert bis zum nächsten Hardwaretest.
    //
    // Zwei Gesten, je Vibration zur blinden Bestätigung (nasse Hände, Blick
    // aufs Display möglich, Antippen nicht):
    // - Kurz = Kontextaktion, pro Tab unterschiedlich belegt (siehe
    //   onHardwareButtonShortPress()): CD-Tab = Countdown Start/Sync/Reset,
    //   Nav-Tab = Pin/Boot setzen (02.09.2026 ergänzt, siehe
    //   setStartLineWaypointHere()), sonst No-Op -> 1 Puls
    // - Lang = nächster Tab (Ring, kein Ende) -> 2 Pulse
    // Welcher Tab gerade aktiv ist, meldet SegelnApp per onTabChanged() aus
    // dem HorizontalPager - das ViewModel kennt sonst keine Pager-Details.
    // _navigateToTab trägt in beiden Fällen (Taste ODER Auto-Fokus unten)
    // einen absoluten Ziel-Index, SegelnApp ruft damit pagerState.animateScrollToPage() auf.
    private var currentTabIndex = 0
    private val _navigateToTab = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val navigateToTab: SharedFlow<Int> = _navigateToTab

    fun onTabChanged(index: Int) {
        currentTabIndex = index
    }

    fun onHardwareButtonShortPress() {
        hapticPlayer.play(BleProtocol.HAPTIC_STEP1)
        when (currentTabIndex) {
            TAB_INDEX_NAV -> setStartLineWaypointHere()
            TAB_INDEX_COUNTDOWN -> when (uiState.value.race?.raceStateOrdinal ?: 0) {
                0 -> { bleClient.sendCommand(BleProtocol.CMD_COUNTDOWN_START); showOverlay("Start") }
                1 -> { bleClient.sendCommand(BleProtocol.CMD_COUNTDOWN_SYNC_NEXT_MINUTE); showOverlay("Sync") }
                else -> { bleClient.sendCommand(BleProtocol.CMD_COUNTDOWN_RESET); showOverlay("Reset") }
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

    fun onHardwareButtonLongPress() {
        hapticPlayer.play(BleProtocol.HAPTIC_DONE2)
        _navigateToTab.tryEmit((currentTabIndex + 1) % TAB_COUNT)
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
            if (prev == 1 && cur == 2) _navigateToTab.tryEmit(TAB_INDEX_NAV)
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

    private companion object {
        // Indizes/Anzahl von SegelnApp.TAB_TITLES (Nav/Wind/Heim/CD/Man/Menu)
        // - bei Umsortierung dort mitpflegen.
        const val TAB_INDEX_NAV = 0
        const val TAB_INDEX_COUNTDOWN = 3
        const val TAB_COUNT = 6
    }
}
