package com.segeluhr.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.segeluhr.app.ble.BleBridge
import com.segeluhr.app.ble.BleGattServerManager
import com.segeluhr.app.ble.BleHapticSender
import com.segeluhr.app.ble.BleProtocol
import com.segeluhr.app.ble.SegeluhrForegroundService
import com.segeluhr.app.core.*
import com.segeluhr.app.data.diagnostics.DiagnosticsLogger
import com.segeluhr.app.data.db.AppDatabase
import com.segeluhr.app.data.db.toEntity
import com.segeluhr.app.data.db.toRecord
import com.segeluhr.app.data.model.AppRole
import com.segeluhr.app.data.model.OperationMode
import com.segeluhr.app.data.model.RaceState
import com.segeluhr.app.data.model.TrainMode
import com.segeluhr.app.data.settings.SettingsRepository
import com.segeluhr.app.geo.LakeAutoDetector
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
    private var currentWaypoints = SettingsRepository.Waypoints(null, null, null, null, null)
    // Boots-Kalibrierung: nur bei tatsächlichem Profilwechsel windEngine
    // neu befüllen, nicht bei jeder Aenderung innerhalb desselben aktiven
    // Profils (siehe init{}-Collector unten).
    private var lastLoadedBoatProfileId: String? = null

    private val statusSink = StatusSink { text, level ->
        _uiState.update { it.copy(statusText = text, statusLevel = level) }
    }

    private val windEngine = WindEngine(
        vib = haptics,
        status = statusSink,
        onWindChanged = { dir, calibrated -> settingsRepo.setWindCalibration(dir, calibrated) },
        onBoatProfileChanged = { id, angle, count, downwindAngle -> settingsRepo.updateBoatProfileCalibration(id, angle, count, downwindAngle) },
    )
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
    // Session-Distanz-Aufsummierung, siehe core/DistanceTracker.kt
    private val distanceTracker = DistanceTracker()
    // Diagnose-Log für den ersten Segeltörn, siehe docs/Erweiterung_Diagnose_Log.md
    private val diagnosticsLogger = DiagnosticsLogger(application)

    init {
        // Steuerbefehle von der Uhr (CMD_*, siehe BleProtocol.kt) auf die
        // bestehenden UI-Aktionen abbilden. Erweiterung für die T-Watch-S3-
        // Firmware, siehe docs/Erweiterung_TWatch_S3_Firmware.md.
        bleManager.controlCommandListener = BleGattServerManager.ControlCommandListener { cmd, payload ->
            handleControlCommand(cmd, payload)
        }
        viewModelScope.launch {
            val calib = settingsRepo.windCalibFlow.first()
            windEngine.restore(calib.windDir, calib.calibrated)
        }
        viewModelScope.launch {
            // Laufend (nicht nur .first()): Profilliste + aktives Profil können
            // sich jederzeit über die Setup-Tab-Verwaltung ändern (neu anlegen/
            // wechseln/löschen). windEngine wird aber NUR bei einem tatsächlichen
            // Wechsel des aktiven Profils neu befüllt (siehe lastLoadedBoatProfileId)
            // — die eigentlichen Live-Werte während des Segelns kommen weiterhin
            // pro Tick direkt aus windEngine (siehe renderTelemetry), nicht von hier.
            settingsRepo.boatProfilesFlow.collect { profiles ->
                if (profiles.activeProfileId != lastLoadedBoatProfileId) {
                    val active = profiles.profiles.firstOrNull { it.id == profiles.activeProfileId } ?: profiles.profiles.first()
                    windEngine.restoreBoatProfile(active.id, active.closehauledAngleDeg, active.sampleCount, active.downwindAngleDeg)
                    lastLoadedBoatProfileId = profiles.activeProfileId
                }
                _uiState.update { it.copy(boatProfiles = profiles.profiles, activeBoatProfileId = profiles.activeProfileId) }
            }
        }
        viewModelScope.launch {
            settingsRepo.waypointsFlow.collect { wp ->
                currentWaypoints = wp
                _uiState.update {
                    it.copy(
                        pin = wp.pin, boat = wp.boat, target = wp.target,
                        buoy1 = wp.buoy1, buoy2 = wp.buoy2,
                        lakeCircles = wp.lakeCircles,
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
        viewModelScope.launch {
            // Nur UI-State, kein Seiteneffekt noetig - MainActivity liest
            // state.appRole und entscheidet, welcher Screen-Baum ueberhaupt
            // gezeigt wird (siehe docs/Erweiterung_Landuhr_Kartenansicht.md).
            settingsRepo.appRoleFlow.collect { role -> _uiState.update { it.copy(appRole = role) } }
        }
        startTicker()
    }

    fun onBluetoothScanPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(bluetoothScanPermissionGranted = granted) }
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
        lakeEngine.tick(fix, trainingEngine.trainMode, currentWaypoints.lakeCircles)

        val competitionGuidance = if (competitionActive) {
            competitionEngine.tick(
                fix, windEngine.windDir, currentWaypoints.competitionMark1, currentWaypoints.competitionMark2,
                windEngine.closehauledAngleDeg, windEngine.downwindAngleDeg,
            )
        } else null

        val countdownS = countdownEngine.tick()

        distanceTracker.sample(valid, fix.lat, fix.lon, fix.sogKn)

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

        val lakePct = lakeEngine.distancePct(fix, currentWaypoints.lakeCircles)

        val trend = windEngine.trendStats()

        val homeGuidance = if (_uiState.value.homeModeActive) {
            homeEngine.tick(
                fix, windEngine.windDir, windEngine.windCalibrated, currentWaypoints.home,
                windEngine.closehauledAngleDeg, windEngine.downwindAngleDeg,
            )
        } else null

        // Sendet an die Ultra-Watch, egal ob gerade "Mit Uhr" aktiv ist —
        // ist keine Watch verbunden, ist notifyHomeStatus ein No-Op (siehe
        // BleGattServerManager). Die Watch entscheidet selbst, ob sie
        // deswegen tatsächlich eine LoRa-Nachricht losschickt.
        bleManager.notifyHomeStatus(
            active = _uiState.value.homeModeActive,
            maneuverNeeded = homeGuidance?.maneuverNeeded ?: false,
            etaMinutes = homeGuidance?.etaSeconds?.let { (it / 60.0).roundToInt() },
            distanceTraveledM = distanceTracker.totalM.roundToInt(),
        )

        // Erweiterung: Wind- und Race-Status 1x/s an die Uhr (siehe
        // docs/Erweiterung_BLE_Wind_RaceStatus.md). No-Op ohne verbundene Uhr.
        bleManager.notifyWindStatus(windDir, calibrated, trend?.first)

        val maneuverNeededForWatch = when {
            trainingEngine.trainState == com.segeluhr.app.data.model.TrainState.COMMANDED -> true
            competitionGuidance?.maneuverNeeded == true -> true
            _uiState.value.homeModeActive && (homeGuidance?.maneuverNeeded == true) -> true
            else -> false
        }
        bleManager.notifyRaceStatus(
            raceStateOrdinal = countdownEngine.raceState.ordinal,
            countdownSeconds = countdownS,
            maneuverNeeded = maneuverNeededForWatch,
            isTack = trainingEngine.isTackManeuver,
            competitionLegOrdinal = competitionGuidance?.leg?.ordinal,
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
                closehauledAngleDeg = windEngine.closehauledAngleDeg,
                closehauledSampleCount = windEngine.closehauledSampleCount,
                downwindAngleDeg = windEngine.downwindAngleDeg,
                calibrationModeEnabled = windEngine.calibrationModeEnabled,
                smartModeEnabled = windEngine.smartModeEnabled,
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
                distanceTraveledM = distanceTracker.totalM,
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

        // Diagnose-Log (siehe docs/Erweiterung_Diagnose_Log.md): NACH dem
        // obigen _uiState.update() - _uiState.value spiegelt an dieser
        // Stelle garantiert schon den neuen Zustand (StateFlow.update()
        // läuft synchron), kein zweites Zusammenbauen der Felder nötig.
        if (_uiState.value.diagnosticsEnabled) {
            diagnosticsLogger.logTick(_uiState.value)
            _uiState.update {
                it.copy(
                    diagnosticsRowCount = diagnosticsLogger.rowCount,
                    diagnosticsFileName = diagnosticsLogger.currentFileName,
                )
            }
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

    /**
     * Boots-Kalibrierung (siehe docs/Erweiterung_Boots_Kalibrierung.md).
     * Direktes _uiState-Update zusätzlich zum Engine-Aufruf, damit der
     * Schalter sofort reagiert statt erst beim nächsten 1-Hz-Tick.
     */
    fun setCalibrationModeEnabled(enabled: Boolean) {
        windEngine.setCalibrationModeEnabled(enabled)
        _uiState.update { it.copy(calibrationModeEnabled = enabled) }
    }

    fun setSmartModeEnabled(enabled: Boolean) {
        windEngine.setSmartModeEnabled(enabled)
        _uiState.update { it.copy(smartModeEnabled = enabled) }
    }

    /** Wirft die gelernten Wende-/Vorwind-Winkel DES AKTIVEN Profils weg (Wind-Tab-Button), nicht die Windkalibrierung. */
    fun resetBoatCalibration() {
        viewModelScope.launch {
            windEngine.resetBoatProfile()
            _uiState.update {
                it.copy(
                    closehauledAngleDeg = windEngine.closehauledAngleDeg,
                    closehauledSampleCount = windEngine.closehauledSampleCount,
                    downwindAngleDeg = windEngine.downwindAngleDeg,
                )
            }
        }
    }

    // ---- Mehrere Boots-Profile (Setup-Tab, siehe docs/Erweiterung_Boots_Kalibrierung.md) ----
    // uiState.boatProfiles/activeBoatProfileId aktualisieren sich automatisch
    // über den boatProfilesFlow-Collector in init{} - hier nur die DataStore-
    // Aktion auslösen.

    /** Legt ein neues Profil an (Standard-Wendewinkel 45°) und aktiviert es sofort. */
    fun addBoatProfile(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { settingsRepo.addBoatProfile(trimmed) }
    }

    fun setActiveBoatProfile(id: String) {
        viewModelScope.launch { settingsRepo.setActiveBoatProfile(id) }
    }

    /** Löscht ein Profil (mindestens eines bleibt immer bestehen, siehe SettingsRepository). */
    fun deleteBoatProfile(id: String) {
        viewModelScope.launch { settingsRepo.deleteBoatProfile(id) }
    }

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
            when (key) {
                // See-Geofence: Kette von Kreisen statt einzelnem Mittelpunkt+Radius,
                // siehe docs/Erweiterung_Automatische_See_Erkennung.md.
                "lakeCircle" -> {
                    settingsRepo.addLakeCircle(point)
                    statusSink.setStatus("Neuer See-Kreis begonnen — jetzt Rand-Punkte erfassen.", StatusLevel.GREEN)
                }
                "lakeEdge" -> {
                    if (currentWaypoints.lakeCircles.isEmpty()) {
                        statusSink.setStatus("Bitte zuerst einen Kreis hinzufügen.", StatusLevel.AMBER)
                    } else {
                        settingsRepo.addLakeEdgeSampleToLastCircle(point)
                        statusSink.setStatus("See-Rand erfasst.", StatusLevel.GREEN)
                    }
                }
                else -> {
                    settingsRepo.setWaypoint(key, point)
                    statusSink.setStatus("Wegpunkt gesetzt: $key", StatusLevel.GREEN)
                }
            }
        }
    }

    /**
     * Automatische See-Erkennung (siehe docs/Erweiterung_Automatische_See_Erkennung.md):
     * lädt die Uferlinie um den aktuellen GPS-Standort per Overpass-API und
     * ersetzt die komplette bestehende Kreis-Kette durch das Ergebnis.
     */
    fun autoDetectLake() {
        val lat = currentFix.lat; val lon = currentFix.lon
        if (!currentlyValid() || lat == null || lon == null) {
            statusSink.setStatus("Kein gültiger GPS-Fix — See kann nicht automatisch erkannt werden.", StatusLevel.RED)
            return
        }
        _uiState.update { it.copy(lakeDetectionInProgress = true) }
        viewModelScope.launch {
            when (val result = LakeAutoDetector.detect(GeoPoint(lat, lon))) {
                is LakeAutoDetector.Result.Success -> {
                    settingsRepo.setLakeCircles(result.lake.circles)
                    val nameSuffix = result.lake.name?.let { " \"$it\"" } ?: ""
                    statusSink.setStatus(
                        "See$nameSuffix erkannt: ${result.lake.circles.size} Kreis(e).",
                        StatusLevel.GREEN,
                    )
                }
                is LakeAutoDetector.Result.Failure -> {
                    statusSink.setStatus(result.message, StatusLevel.RED)
                }
            }
            _uiState.update { it.copy(lakeDetectionInProgress = false) }
        }
    }

    fun removeLakeCircle(index: Int) {
        viewModelScope.launch { settingsRepo.removeLakeCircle(index) }
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
        distanceTracker.reset()
        _uiState.update { it.copy(distanceTraveledM = 0.0) }
        // Sicherstellen, dass ein laufender Foreground-Service/Watch-Routing
        // nicht "verwaist", nur weil die zugrunde liegende Einstellung
        // (DataStore) gerade gelöscht wurde.
        setOperationMode(OperationMode.STANDALONE)
    }

    fun setWakeLockEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setWakeLockEnabled(enabled) }
    }

    // ---- Diagnose-Log (siehe docs/Erweiterung_Diagnose_Log.md) ----

    fun setDiagnosticsEnabled(enabled: Boolean) {
        _uiState.update { it.copy(diagnosticsEnabled = enabled) }
    }

    /** Setup-Tab "Ereignis markieren" — schreibt SOFORT eine Zeile, unabhängig vom 1-Hz-Tick-Rhythmus. */
    fun markDiagnosticsEvent(note: String) {
        diagnosticsLogger.markEvent(_uiState.value, note)
        _uiState.update {
            it.copy(diagnosticsRowCount = diagnosticsLogger.rowCount, diagnosticsFileName = diagnosticsLogger.currentFileName)
        }
    }

    /** Content-URI der aktuellen Log-Datei fürs Teilen (Setup-Tab-Button baut daraus den Share-Intent) — null, falls noch nichts geloggt wurde. */
    fun shareDiagnosticsLogUri() = diagnosticsLogger.shareUri()

    override fun onCleared() {
        super.onCleared()
        diagnosticsLogger.close()
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
    /**
     * Dispatcht einen CMD_*-Steuerbefehl von der Uhr (CHAR_CONTROL_UUID) auf
     * die passende bestehende UI-Aktion. Wegpunkte werden immer an der
     * AKTUELLEN Handy-GPS-Position gesetzt (die Uhr hat im "Mit Uhr"-Modus
     * kein eigenes GPS) — analog zum bisherigen Tester-Workflow.
     */
    private fun handleControlCommand(cmd: Int, payload: ByteArray) {
        when (cmd) {
            BleProtocol.CMD_COUNTDOWN_START -> startCountdown()
            BleProtocol.CMD_COUNTDOWN_RESET -> resetCountdown()
            BleProtocol.CMD_COUNTDOWN_SYNC_NEXT_MINUTE -> syncCountdownToNextMinute()
            BleProtocol.CMD_WIND_CALIBRATE_START -> startAmwindCalibration()
            BleProtocol.CMD_WIND_CALIBRATE_ABORT -> abortCalibration()
            BleProtocol.CMD_TRAIN_MODE_OFF -> setTrainMode(TrainMode.OFF)
            BleProtocol.CMD_TRAIN_MODE_TACK_ONLY -> setTrainMode(TrainMode.TACK_ONLY)
            BleProtocol.CMD_TRAIN_MODE_JIBE_ONLY -> setTrainMode(TrainMode.JIBE_ONLY)
            BleProtocol.CMD_TRAIN_MODE_RACE -> setTrainMode(TrainMode.RACE)
            BleProtocol.CMD_SET_WAYPOINT -> waypointKeyFor(payload.getOrNull(0)?.toInt())?.let { captureWaypoint(it) }
            BleProtocol.CMD_CLEAR_WAYPOINT -> waypointKeyFor(payload.getOrNull(0)?.toInt())?.let { clearWaypoint(it) }
            BleProtocol.CMD_HOME_MODE_TOGGLE -> setHomeModeActive(!_uiState.value.homeModeActive)
            BleProtocol.CMD_COMPETITION_END -> stopCompetition()
            BleProtocol.CMD_CLEAR_LOG -> clearManeuverLog()
        }
    }

    private fun waypointKeyFor(id: Int?): String? = when (id) {
        BleProtocol.WaypointId.PIN -> "pin"
        BleProtocol.WaypointId.BOAT -> "boat"
        BleProtocol.WaypointId.TARGET -> "target"
        BleProtocol.WaypointId.BUOY1 -> "buoy1"
        BleProtocol.WaypointId.BUOY2 -> "buoy2"
        // Kette von Kreisen statt Einzelkreis (siehe captureWaypoint()) - ein
        // Tastendruck auf der Uhr markiert die aktuelle Position weiterhin
        // als (neuen) See-Sicherheitskreis-Mittelpunkt.
        BleProtocol.WaypointId.LAKE_CENTER -> "lakeCircle"
        BleProtocol.WaypointId.HOME -> "home"
        BleProtocol.WaypointId.COMPETITION_MARK1 -> "competitionMark1"
        BleProtocol.WaypointId.COMPETITION_MARK2 -> "competitionMark2"
        else -> null
    }

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

    /**
     * Nur Persistenz - siehe docs/Erweiterung_Landuhr_Kartenansicht.md.
     * Bewusst hier statt in einem eigenen ViewModel: diese Instanz bleibt
     * (aktuelle MainActivity-Struktur) so oder so am Leben, auch waehrend
     * die UI im Land-Modus zeigt, und ist der einzige Schreibzugriff auf
     * SettingsRepository - so bleibt "zurueck zu Boot-Modus" aus dem
     * Land-Screen ohne zweite SettingsRepository-Instanz moeglich.
     */
    fun setAppRole(role: AppRole) {
        viewModelScope.launch { settingsRepo.setAppRole(role) }
    }
}
