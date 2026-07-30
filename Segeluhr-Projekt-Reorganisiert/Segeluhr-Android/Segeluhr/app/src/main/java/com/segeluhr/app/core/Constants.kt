package com.segeluhr.app.core

/**
 * Konstanten-Übersicht, Abschnitt 10 der Segeluhr_Spezifikation.md.
 * Alle Werte sind Startwerte aus der Konzeptphase — plausibel, aber noch
 * nicht auf dem Wasser validiert. Bei Bedarf hier zentral anpassen.
 */
object Constants {
    const val MIN_SPEED_KN = 1.5
    const val WINDOW_SIZE = 8
    const val STEADY_COURSE_MAX_DEV = 8.0
    const val TRAIN_STEADY_MAX_DEV_MANEUVER = 14.0
    const val RACE_COURSE_MAX_DEV = 10.0
    const val POSITION_CHECK_MIN_DIST_M = 15.0
    const val POSITION_CHECK_MAX_DEV_DEG = 20.0
    const val IMU_STILL_MAX_DEV = 5.0
    const val METHOD_TIMEOUT_MS = 90_000L
    const val TACK_CHANGE_MIN_DEG = 50.0
    const val MIN_TACK_ANGLE_DEG = 60.0
    const val MAX_TACK_ANGLE_DEG = 110.0
    const val WIND_SHIFT_THRESHOLD_DEG = 6.0
    const val TACK_SIGN_DEADZONE_DEG = 5.0
    const val WIND_LOG_INTERVAL_MS = 60_000L
    const val WIND_LOG_SIZE = 30
    const val TRAIN_MIN_INTERVAL_MS = 20_000L
    const val TRAIN_MAX_INTERVAL_MS = 60_000L
    const val TRAIN_MANEUVER_TIMEOUT_MS = 60_000L
    const val TRAIN_TURN_START_DEG = 15.0
    const val MANEUVER_LOG_SIZE = 20
    const val ROUNDING_RADIUS_M = 20.0
    const val RACE_TURN_MIN_DEG = 25.0
    const val RACE_AIM_TOLERANCE_DEG = 30.0
    const val LAKE_WARN_FACTOR = 0.80
    const val LAKE_CLEAR_FACTOR = 0.65
    const val LAKE_WARN_REPEAT_MS = 15_000L

    // 5-4-1 Startsequenz
    const val COUNTDOWN_DURATION_MS = 5 * 60 * 1000L
    val COUNTDOWN_MARKS_S = listOf(240, 60) // Signal bei 4:00 und 1:00, starkes Signal bei 0:00 separat
}
