package com.segeluhr.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.segeluhr.app.ble.BleBridge
import com.segeluhr.app.ble.BleHapticSender
import com.segeluhr.app.ble.SegeluhrForegroundService
import com.segeluhr.app.core.*
import com.segeluhr.app.data.db.AppDatabase
import com.segeluhr.app.data.db.toEntity
import com.segeluhr.app.data.db.toRecord
import com.segeluhr.app.data.model.OperationMode
import com.segeluhr.app.data.model.RaceState
import com.segeluhr.app.data.model.TrainMode
import com.segeluhr.app.data.settings.SettingsRepository
import com.segeluhr.app.location.LocationProvider
import com.segeluhr.app.logic.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt

class SegeluhrViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    private val db = AppDatabase.getInstance(application)
    private val locationProvider = LocationProvider(application)

    // Zwei mögliche Haptik-Ziele; SwitchableHaptics entscheidet zur Laufzeit,
    // welches davon tatsächlich vibriert (siehe setOperationMode).
    private val phoneHaptics = VibrationPatterns(application)
    private val bleManager = BleBridge.getInstance(application)
    private val watchHaptics = BleHapticSender(bleManager)
    private val haptics = SwitchableHaptics(phoneHaptics, watchHaptics) { bleManager.isConnected }

    private val _uiState = MutableStateFlow(SegeluhrUiState())
    val uiState: StateFlow<SegeluhrUiState> = _uiState.asStateFlow()

    // "Letzter bekannter Fix" — wird per GPS-Callback jederzeit aktualisiert,
    // von der 1Hz-Tickschleife aber nur einmal pro Sekunde gelesen (analog
    // zu S.fix / mainTick() im Browser-Prototyp).
    private var currentFix: Fix = Fix()
    private var currentWaypoints = SettingsRepository.Waypoints(null, null, null, null, null, null, null)

    private val statusSink = StatusSink { text, level ->
        _uiState.update { it.copy(statusText = text, statusLevel = level) }
    }

    private val windEngine = WindEngine(haptics, statusSink) { dir, calibrated ->
        settingsRepo.setWindCalibration(dir, calibrated)
    }
    private val trainingEngine = TrainingEngine(haptics, statusSink) { record ->
        db.maneuverDao().insert(record.toEntity())
        db.maneuverDao().trimTo()
    }
    private val countdownEngine = StartCountdownEngine(haptics) {
        // Automatischer Competition-Start bei 0:00 (Erweiterung, siehe
        // docs/Erweiterung_Competition_Modus.md). Läuft unabhängig vom
        // Trainings-Tab (TrainMode) — mit Luv-Bojen, falls gesetzt, sonst
        // mit der virtuellen Windschätzung.
        competitionEngine.activate()
        _uiState.update { it.copy(competitionActive = true) }
    }
    private val lakeEngine = LakeGeofenceEngine(haptics, statusSink)
    private val homeEngine = HomeEngine(haptics, statusSink)
    private val competitionEngine = CompetitionEngine(haptics, statusSink)

    init {
        viewModelScope.launch {
            val calib = settingsRepo.windCalibFlow.first()
            windEngine.restore(calib.windDir, calib.calibrated)
        }
        viewModelScope.launch {
            settingsRepo.waypointsFlow.collect { wp ->
                currentWaypoints = wp
                _uiState.update {
                    it.copy(
                        pin = wp.pin, boat = wp.boat, target = wp.target,
                        buoy1 = wp.buoy1, buoy2 = wp.buoy2,
                        lakeCenter = wp.lakeCenter, lakeRadius = wp.lakeRadius,
                        home = wp.home,
                        competitionMark1 = wp.competitionMark1,
                        competitionMark2 = wp.competitionMark2,
                    )
                }
            }
        }
        viewModelScope.launch {
            db.maneuverDao().observeRecent().collect { entities ->
                val records = entities.map { it.toRecord() }
                val tacks = records.filter { it.isTack }
                val jibes = records.filter { !it.isTack }
                _uiState.update {
                    it.copy(
                        maneuverLog = records,
                        avgTackScore = if (tacks.isNotEmpty()) tacks.map { r -> r.score }.average() else null,
                        avgJibeScore = if (jibes.isNotEmpty()) jibes.map { r -> r.score }.average() else null,
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepo.wakeLockEnabledFlow.collect { enabled -> _uiState.update { it.copy(wakeLockEnabled = enabled) } }
        }
        viewModelScope.launch {
            // Erster Wert = der beim App-Start aus DataStore wiederhergestellte Modus.
            // War der Foreground-Service (GATT-Server) beim letzten Mal aktiv, muss er
            // hier explizit neu gestartet werden — sonst zeigt die UI "Mit Uhr" an,
            // ohne dass tatsächlich ein Dienst im Hintergrund läuft (genau der Bug,
            // der zu "verbunden, aber es kommt nichts an" führt).
            val initialMode = settingsRepo.operationModeFlow.first()
            haptics.preferWatch = (initialMode == OperationMode.WITH_WATCH)
            _uiState.update { it.copy(operationMode = initialMode) }
            if (initialMode == OperationMode.WITH_WATCH) {
                SegeluhrForegroundService.start(getApplication())
            }
        }
        viewModelScope.launch {
            // Ab dem zweiten emittierten Wert: nur noch UI-State/Haptik-Ziel
            // aktualisieren — Start/Stop des Dienstes läuft dann exklusiv über
            // setOperationMode() (durch aktives Umschalten im Setup-Tab), damit
            // der Dienst nicht bei jedem Recompose doppelt (neu)gestartet wird.
            settingsRepo.operationModeFlow.drop(1).collect { mode ->
                haptics.preferWatch = (mode == OperationMode.WITH_WATCH)
                _uiState.update { it.copy(operationMode = mode) }
            }
        }
        startTicker()
    }

    fun onLocationPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(locationPermissionGranted = granted) }
        if (granted) startGps()
    }

    private fun startGps() {
        viewModelScope.launch {
            locationProvider.fixFlow().collect { fix -> currentFix = fix }
        }
    }

    private fun startTicker() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                tick()
            }
        }
    }

    private fun currentlyValid(): Boolean =
        currentFix.valid && (System.currentTimeMillis() - currentFix.timestampMs < 5000)

    private suspend fun tick() {
        val fix = currentFix
        val valid = currentlyValid()
        val competitionActive = _uiState.value.competitionActive

        // Zielwinkel-Bewertung für die Wind-Shift-Erkennung (Abschnitt 4.2):
        // Competition (falls aktiv) hat Vorrang, danach der Trainings-
        // Racemode mit seiner aktiven Boje, sonst der fest gesetzte "Ziel"-
        // Wegpunkt aus dem Setup-Tab.
        val windShiftReference = when {
            competitionActive -> competitionEngine.windShiftReferencePoint(
                fix, windEngine.windDir, currentWaypoints.competitionMark1, currentWaypoints.competitionMark2,
            ) ?: currentWaypoints.target
            trainingEngine.trainMode == TrainMode.RACE ->
                trainingEngine.activeBuoy(currentWaypoints.buoy1, currentWaypoints.buoy2) ?: currentWaypoints.target
            else -> currentWaypoints.target
        }

        // Kontinuierliches Wind-Tracking, pausiert während TURNING (Abschnitt 4.2)
        if (windEngine.windCalibrated && trainingEngine.trainState != com.segeluhr.app.data.model.TrainState.TURNING) {
            windEngine.tickContinuous(fix, windShiftReference)
        }
        windEngine.tickLog()

        if (windEngine.calibState != com.segeluhr.app.data.model.WindCalibState.IDLE) {
            windEngine.tickCalibration(fix)
        }

        if (trainingEngine.trainMode != TrainMode.OFF) {
            trainingEngine.tick(fix, windEngine.windDir, windEngine.windCalibrated)
        }
        if (trainingEngine.trainMode == TrainMode.RACE) {
            trainingEngine.tickRaceNav(fix, currentWaypoints.buoy1, currentWaypoints.buoy2)
        }
        lakeEngine.tick(fix, trainingEngine.trainMode, currentWaypoints.lakeCenter, currentWaypoints.lakeRadius)

        val competitionGuidance = if (competitionActive) {
            competitionEngine.tick(fix, windEngine.windDir, currentWaypoints.competitionMark1, currentWaypoints.competitionMark2)
        } else null

        val countdownS = countdownEngine.tick()

        renderTelemetry(fix, valid, countdownS, competitionGuidance)
    }

    private fun renderTelemetry(fix: Fix, valid: Boolean, countdownS: Int?, competitionGuidance: com.segeluhr.app.data.model.CompetitionGuidance?) {
        val windDir = windEngine.windDir
        val calibrated = windEngine.windCalibrated

        val vmg: Double? = if (calibrated && fix.sogKn != null && fix.cogDeg != null && windDir != null) {
            fix.sogKn * cos(GeoUtils.toRad(fix.cogDeg - windDir))
        } else null

        var lineBiasDeg: Double? = null
        var lineBiasFavors: String? = null
        val pin = currentWaypoints.pin
        val boat = currentWaypoints.boat
        if (pin != null && boat != null && calibrated && windDir != null) {
            val lineBearing = GeoUtils.bearingDeg(pin.lat, pin.lon, boat.lat, boat.lon)
            val squareBearing = GeoUtils.normalize360(windDir + 90)
            val bias = GeoUtils.angleDiff(lineBearing, squareBearing)
            lineBiasDeg = bias
            lineBiasFavors = if (bias > 0) "Boot" else if (bias < 0) "Pin" else "neutral"
        }

        var targetBearing: Double? = null
        var targetDistance: Double? = null
        val target = currentWaypoints.target
        if (target != null && fix.lat != null && fix.lon != null) {
            targetBearing = GeoUtils.bearingDeg(fix.lat, fix.lon, target.lat, target.lon)
            targetDistance = GeoUtils.distanceMeters(fix.lat, fix.lon, target.lat, target.lon)
        }

        var activeBuoyLabel: String? = null
        var buoyBearing: Double? = null
        var buoyDistance: Double? = null
        val activeBuoy = trainingEngine.activeBuoy(currentWaypoints.buoy1, currentWaypoints.buoy2)
        if (trainingEngine.trainMode == TrainMode.RACE && activeBuoy != null && fix.lat != null && fix.lon != null) {
            activeBuoyLabel = "Boje ${(trainingEngine.activeBuoyIdx ?: 0) + 1}"
            buoyBearing = GeoUtils.bearingDeg(fix.lat, fix.lon, activeBuoy.lat, activeBuoy.lon)
            buoyDistance = GeoUtils.distanceMeters(fix.lat, fix.lon, activeBuoy.lat, activeBuoy.lon)
        }

        val lakePct = lakeEngine.distancePct(fix, currentWaypoints.lakeCenter, currentWaypoints.lakeRadius)

        val trend = windEngine.trendStats()

        val homeGuidance = if (_uiState.value.homeModeActive) {
            homeEngine.tick(fix, windEngine.windDir, windEngine.windCalibrated, currentWaypoints.home)
        } else null

        // Sendet an die Ultra-Watch, egal ob gerade "Mit Uhr" aktiv ist —
        // ist keine Watch verbunden, ist notifyHomeStatus ein No-Op (siehe
        // BleGattServerManager). Die Watch entscheidet selbst, ob sie
        // deswegen tatsächlich eine LoRa-Nachricht losschickt.
        bleManager.notifyHomeStatus(
            active = _uiState.value.homeModeActive,
            maneuverNeeded = homeGuidance?.maneuverNeeded ?: false,
            etaMinutes = homeGuidance?.etaSeconds?.let { (it / 60.0).roundToInt() },
        )

        _uiState.update {
            it.copy(
                gpsFix = fix,
                gpsFresh = valid,
                gpsMoving = valid && (fix.sogKn ?: 0.0) >= Constants.MIN_SPEED_KN && fix.cogDeg != null,
                windDir = windDir,
                windCalibrated = calibrated,
                windCalibState = windEngine.calibState,
                windLog = windEngine.windLog,
                windNet = trend?.first,
                windRange = trend?.second,
                vmg = vmg,
                lineBiasDeg = lineBiasDeg,
                lineBiasFavors = lineBiasFavors,
                targetBearing = targetBearing,
                targetDistanceM = targetDistance,
                activeBuoyLabel = activeBuoyLabel,
                buoyBearing = buoyBearing,
                buoyDistanceM = buoyDistance,
                lakeDistanceM = lakePct?.first,
                lakeDistancePct = lakePct?.second,
                raceState = countdownEngine.raceState,
                countdownSeconds = countdownS,
                isRaceTimerRunning = countdownEngine.raceState == RaceState.RACE,
                trainMode = trainingEngine.trainMode,
                trainRequirementWarning = trainRequirementText(),
                watchConnected = bleManager.isConnected,
                homeGuidance = homeGuidance,
                competitionGuidance = competitionGuidance,
            )
        }
    }

    private fun trainRequirementText(): String = when {
        !windEngine.windCalibrated -> "⚠️ Wind muss kalibriert sein (siehe Tab \"Wind\")."
        trainingEngine.trainMode == TrainMode.RACE && (currentWaypoints.buoy1 == null || currentWaypoints.buoy2 == null) ->
            "⚠️ Racemode benötigt beide Bojen gesetzt."
        else -> ""
    }

    // ---- UI-Aktionen ----

    fun startAmwindCalibration() = windEngine.startCalibration(currentlyValid())
    fun abortCalibration() = windEngine.abortCalibration()

    fun startCountdown() = countdownEngine.start()
    fun resetCountdown() = countdownEngine.reset()
    fun syncCountdownToNextMinute() = countdownEngine.syncToNextMinute()

    fun setTrainMode(mode: TrainMode) {
        trainingEngine.setMode(mode, currentWaypoints.buoy1, currentWaypoints.buoy2, currentFix)
        _uiState.update { it.copy(trainMode = mode, trainRequirementWarning = trainRequirementText()) }
    }

    fun captureWaypoint(key: String) {
        if (!currentlyValid() || currentFix.lat == null || currentFix.lon == null) {
            statusSink.setStatus("Kein gültiger GPS-Fix — Wegpunkt kann nicht gesetzt werden.", StatusLevel.RED)
            return
        }
        val point = GeoPoint(currentFix.lat!!, currentFix.lon!!)
        viewModelScope.launch {
            if (key == "lakeEdge") {
                val center = currentWaypoints.lakeCenter
                if (center == null) {
                    statusSink.setStatus("Bitte zuerst See-Mitte setzen.", StatusLevel.AMBER)
                } else {
                    settingsRepo.addLakeEdgeSample(center, point)
                    statusSink.setStatus("See-Rand erfasst.", StatusLevel.GREEN)
                }
            } else {
                settingsRepo.setWaypoint(key, point)
                statusSink.setStatus("Wegpunkt gesetzt: $key", StatusLevel.GREEN)
            }
        }
    }

    fun clearWaypoint(key: String) {
        viewModelScope.launch { settingsRepo.clearWaypoint(key) }
    }

    fun clearManeuverLog() {
        viewModelScope.launch { db.maneuverDao().clearAll() }
    }

    fun resetAll() {
        viewModelScope.launch {
            settingsRepo.resetAll()
            db.maneuverDao().clearAll()
        }
        // Sicherstellen, dass ein laufender Foreground-Service/Watch-Routing
        // nicht "verwaist", nur weil die zugrunde liegende Einstellung
        // (DataStore) gerade gelöscht wurde.
        setOperationMode(OperationMode.STANDALONE)
    }

    fun setWakeLockEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setWakeLockEnabled(enabled) }
    }

    /**
     * Aktiviert/deaktiviert die Heimweg-Navigation (Peilung, Wende-Vorschlag,
     * ETA — siehe docs/Erweiterung_Heimweg.md). [shoreStatusSender] ist der
     * vorgesehene Anknüpfungspunkt für die LoRa-Statusmeldung an Land.
     */
    fun setHomeModeActive(active: Boolean) {
        homeEngine.reset()
        _uiState.update { it.copy(homeModeActive = active, homeGuidance = if (active) it.homeGuidance else null) }
        // TODO(LoRa): Sobald die Hardware/das Protokoll feststehen, hier
        // z.B. shoreStatusSender.sendModeChanged(active, currentWaypoints.home) aufrufen.
    }

    /** Beendet den Competition-Modus manuell (z.B. nach dem Zieleinlauf) */
    fun stopCompetition() {
        _uiState.update { it.copy(competitionActive = false, competitionGuidance = null) }
    }

    /**
     * "STANDALONE": Handy vibriert selbst, BLE-Bridge/Foreground-Service aus.
     * "WITH_WATCH": Foreground-Service (GATT-Server + GPS-Weiterleitung)
     * läuft, und [haptics] leitet alle Muster per BLE an die Uhr um, statt
     * das Handy selbst vibrieren zu lassen.
     */
    fun setOperationMode(mode: OperationMode) {
        viewModelScope.launch { settingsRepo.setOperationMode(mode) }
        haptics.preferWatch = (mode == OperationMode.WITH_WATCH)
        val app = getApplication<Application>()
        if (mode == OperationMode.WITH_WATCH) {
            SegeluhrForegroundService.start(app)
        } else {
            SegeluhrForegroundService.stop(app)
        }
    }
}
