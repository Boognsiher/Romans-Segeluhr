package com.segeluhr.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.segeluhr.app.core.GeoPoint
import com.segeluhr.app.data.model.OperationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "segeluhr_settings")

/**
 * Persistenz für Wegpunkte, Windkalibrierung und einfache Einstellungen.
 * Bewusst als Key-Value über DataStore gelöst (kein Schema nötig, siehe
 * Abschnitt 11 der Spezifikation: "auf dem Watch-Prototyp nur im RAM" —
 * hier von Anfang an persistent).
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val PIN_LAT = doublePreferencesKey("pin_lat"); val PIN_LON = doublePreferencesKey("pin_lon")
        val BOAT_LAT = doublePreferencesKey("boat_lat"); val BOAT_LON = doublePreferencesKey("boat_lon")
        val TARGET_LAT = doublePreferencesKey("target_lat"); val TARGET_LON = doublePreferencesKey("target_lon")
        val BUOY1_LAT = doublePreferencesKey("buoy1_lat"); val BUOY1_LON = doublePreferencesKey("buoy1_lon")
        val BUOY2_LAT = doublePreferencesKey("buoy2_lat"); val BUOY2_LON = doublePreferencesKey("buoy2_lon")
        val LAKE_CENTER_LAT = doublePreferencesKey("lake_center_lat"); val LAKE_CENTER_LON = doublePreferencesKey("lake_center_lon")
        val LAKE_RADIUS = doublePreferencesKey("lake_radius")
        val HOME_LAT = doublePreferencesKey("home_lat"); val HOME_LON = doublePreferencesKey("home_lon")
        val COMPETITION_MARK1_LAT = doublePreferencesKey("competition_mark1_lat"); val COMPETITION_MARK1_LON = doublePreferencesKey("competition_mark1_lon")
        val COMPETITION_MARK2_LAT = doublePreferencesKey("competition_mark2_lat"); val COMPETITION_MARK2_LON = doublePreferencesKey("competition_mark2_lon")

        val WIND_DIR = doublePreferencesKey("wind_dir")
        val WIND_CALIBRATED = booleanPreferencesKey("wind_calibrated")

        val WAKE_LOCK_ENABLED = booleanPreferencesKey("wake_lock_enabled")
        val OPERATION_MODE = stringPreferencesKey("operation_mode")
    }

    data class Waypoints(
        val pin: GeoPoint?, val boat: GeoPoint?, val target: GeoPoint?,
        val buoy1: GeoPoint?, val buoy2: GeoPoint?,
        val lakeCenter: GeoPoint?, val lakeRadius: Double?,
        val home: GeoPoint? = null,
        val competitionMark1: GeoPoint? = null,
        val competitionMark2: GeoPoint? = null,
    )

    data class WindCalib(val windDir: Double?, val calibrated: Boolean)

    val waypointsFlow: Flow<Waypoints> = context.dataStore.data.map { p ->
        fun pt(latKey: Preferences.Key<Double>, lonKey: Preferences.Key<Double>): GeoPoint? {
            val lat = p[latKey] ?: return null
            val lon = p[lonKey] ?: return null
            return GeoPoint(lat, lon)
        }
        Waypoints(
            pin = pt(Keys.PIN_LAT, Keys.PIN_LON),
            boat = pt(Keys.BOAT_LAT, Keys.BOAT_LON),
            target = pt(Keys.TARGET_LAT, Keys.TARGET_LON),
            buoy1 = pt(Keys.BUOY1_LAT, Keys.BUOY1_LON),
            buoy2 = pt(Keys.BUOY2_LAT, Keys.BUOY2_LON),
            lakeCenter = pt(Keys.LAKE_CENTER_LAT, Keys.LAKE_CENTER_LON),
            lakeRadius = p[Keys.LAKE_RADIUS],
            home = pt(Keys.HOME_LAT, Keys.HOME_LON),
            competitionMark1 = pt(Keys.COMPETITION_MARK1_LAT, Keys.COMPETITION_MARK1_LON),
            competitionMark2 = pt(Keys.COMPETITION_MARK2_LAT, Keys.COMPETITION_MARK2_LON),
        )
    }

    val windCalibFlow: Flow<WindCalib> = context.dataStore.data.map { p ->
        WindCalib(p[Keys.WIND_DIR], p[Keys.WIND_CALIBRATED] ?: false)
    }

    val wakeLockEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[Keys.WAKE_LOCK_ENABLED] ?: false }

    val operationModeFlow: Flow<OperationMode> = context.dataStore.data.map { p ->
        when (p[Keys.OPERATION_MODE]) {
            OperationMode.WITH_WATCH.name -> OperationMode.WITH_WATCH
            else -> OperationMode.STANDALONE
        }
    }

    suspend fun setWaypoint(key: String, point: GeoPoint) {
        context.dataStore.edit { p ->
            when (key) {
                "pin" -> { p[Keys.PIN_LAT] = point.lat; p[Keys.PIN_LON] = point.lon }
                "boat" -> { p[Keys.BOAT_LAT] = point.lat; p[Keys.BOAT_LON] = point.lon }
                "target" -> { p[Keys.TARGET_LAT] = point.lat; p[Keys.TARGET_LON] = point.lon }
                "buoy1" -> { p[Keys.BUOY1_LAT] = point.lat; p[Keys.BUOY1_LON] = point.lon }
                "buoy2" -> { p[Keys.BUOY2_LAT] = point.lat; p[Keys.BUOY2_LON] = point.lon }
                "lakeCenter" -> { p[Keys.LAKE_CENTER_LAT] = point.lat; p[Keys.LAKE_CENTER_LON] = point.lon }
                "home" -> { p[Keys.HOME_LAT] = point.lat; p[Keys.HOME_LON] = point.lon }
                "competitionMark1" -> { p[Keys.COMPETITION_MARK1_LAT] = point.lat; p[Keys.COMPETITION_MARK1_LON] = point.lon }
                "competitionMark2" -> { p[Keys.COMPETITION_MARK2_LAT] = point.lat; p[Keys.COMPETITION_MARK2_LON] = point.lon }
            }
        }
    }

    /** See-Rand: merkt sich den KLEINSTEN gemessenen Abstand als Radius (Abschnitt 6.5) */
    suspend fun addLakeEdgeSample(center: GeoPoint, edgePoint: GeoPoint) {
        val d = com.segeluhr.app.core.GeoUtils.distanceMeters(center.lat, center.lon, edgePoint.lat, edgePoint.lon)
        context.dataStore.edit { p ->
            val current = p[Keys.LAKE_RADIUS]
            p[Keys.LAKE_RADIUS] = if (current == null) d else minOf(current, d)
        }
    }

    suspend fun clearWaypoint(key: String) {
        context.dataStore.edit { p ->
            when (key) {
                "pin" -> { p.remove(Keys.PIN_LAT); p.remove(Keys.PIN_LON) }
                "boat" -> { p.remove(Keys.BOAT_LAT); p.remove(Keys.BOAT_LON) }
                "target" -> { p.remove(Keys.TARGET_LAT); p.remove(Keys.TARGET_LON) }
                "buoy1" -> { p.remove(Keys.BUOY1_LAT); p.remove(Keys.BUOY1_LON) }
                "buoy2" -> { p.remove(Keys.BUOY2_LAT); p.remove(Keys.BUOY2_LON) }
                "lakeCenter" -> { p.remove(Keys.LAKE_CENTER_LAT); p.remove(Keys.LAKE_CENTER_LON) }
                "lakeRadius" -> p.remove(Keys.LAKE_RADIUS)
                "home" -> { p.remove(Keys.HOME_LAT); p.remove(Keys.HOME_LON) }
                "competitionMark1" -> { p.remove(Keys.COMPETITION_MARK1_LAT); p.remove(Keys.COMPETITION_MARK1_LON) }
                "competitionMark2" -> { p.remove(Keys.COMPETITION_MARK2_LAT); p.remove(Keys.COMPETITION_MARK2_LON) }
            }
        }
    }

    suspend fun setWindCalibration(windDir: Double, calibrated: Boolean) {
        context.dataStore.edit { p ->
            p[Keys.WIND_DIR] = windDir
            p[Keys.WIND_CALIBRATED] = calibrated
        }
    }

    suspend fun setWakeLockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WAKE_LOCK_ENABLED] = enabled }
    }

    suspend fun setOperationMode(mode: OperationMode) {
        context.dataStore.edit { it[Keys.OPERATION_MODE] = mode.name }
    }

    suspend fun currentWaypoints(): Waypoints = waypointsFlow.first()

    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
    }
}
