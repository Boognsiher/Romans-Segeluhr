package com.segeluhr.app.viewmodel

import com.segeluhr.app.core.Fix
import com.segeluhr.app.core.GeoPoint
import com.segeluhr.app.core.LakeCircle
import com.segeluhr.app.data.model.*
import com.segeluhr.app.logic.StatusLevel

data class SegeluhrUiState(
    // GPS / Telemetrie
    val gpsFix: Fix = Fix(),
    val gpsFresh: Boolean = false,
    val gpsMoving: Boolean = false,

    val statusText: String = "Bereit.",
    val statusLevel: StatusLevel = StatusLevel.NORMAL,

    // Wind
    val windDir: Double? = null,
    val windCalibrated: Boolean = false,
    val windCalibState: WindCalibState = WindCalibState.IDLE,
    val windLog: List<WindLogPoint> = emptyList(),
    val windNet: Double? = null,
    val windRange: Double? = null,

    // Abgeleitete Telemetrie
    val vmg: Double? = null,
    val lineBiasDeg: Double? = null,
    val lineBiasFavors: String? = null, // "Boot" | "Pin" | "neutral"
    val targetBearing: Double? = null,
    val targetDistanceM: Double? = null,
    val activeBuoyLabel: String? = null,
    val buoyBearing: Double? = null,
    val buoyDistanceM: Double? = null,
    val lakeDistanceM: Double? = null,
    val lakeDistancePct: Double? = null,

    // Start-Countdown
    val raceState: RaceState = RaceState.MENU,
    val countdownSeconds: Int? = null, // während COUNTDOWN: verbleibend; während RACE: vergangen
    val isRaceTimerRunning: Boolean = false,

    // Training
    val trainMode: TrainMode = TrainMode.OFF,
    val trainRequirementWarning: String = "",

    // Log
    val maneuverLog: List<ManeuverRecord> = emptyList(),
    val avgTackScore: Double? = null,
    val avgJibeScore: Double? = null,

    // Wegpunkte
    val pin: GeoPoint? = null,
    val boat: GeoPoint? = null,
    val target: GeoPoint? = null,
    val buoy1: GeoPoint? = null,
    val buoy2: GeoPoint? = null,
    val lakeCircles: List<LakeCircle> = emptyList(),
    val lakeDetectionInProgress: Boolean = false,
    val home: GeoPoint? = null,
    val competitionMark1: GeoPoint? = null,
    val competitionMark2: GeoPoint? = null,

    // Heimweg-Navigation (Erweiterung, siehe docs/Erweiterung_Heimweg.md)
    val homeModeActive: Boolean = false,
    val homeGuidance: HomeGuidance? = null,

    // Competition-Modus (Erweiterung, siehe docs/Erweiterung_Competition_Modus.md)
    val competitionActive: Boolean = false,
    val competitionGuidance: CompetitionGuidance? = null,

    // Setup
    val wakeLockEnabled: Boolean = false,
    val operationMode: OperationMode = OperationMode.STANDALONE,
    val watchConnected: Boolean = false,
    val locationPermissionGranted: Boolean = false,
)
