package com.segeluhr.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.segeluhr.app.core.GeoPoint
import com.segeluhr.app.core.LakeCircle
import com.segeluhr.app.data.model.AppRole
import com.segeluhr.app.data.model.OperationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

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
        // Kette von See-Geofence-Kreisen (Roman-Entscheidung 06.08.2026, siehe
        // docs/Erweiterung_Automatische_See_Erkennung.md) - als JSON-Array
        // statt fester Einzelfelder, da die Anzahl variabel ist. Ersetzt die
        // früheren Einzelfelder lake_center_lat/lon + lake_radius; alte
        // Installationen verlieren dadurch einmalig einen gesetzten See
        // (DataStore braucht keine Schema-Migration, die alten Keys werden
        // einfach nicht mehr gelesen) - unkritisch im aktuellen Projektstand.
        val LAKE_CIRCLES_JSON = stringPreferencesKey("lake_circles_json")
        val HOME_LAT = doublePreferencesKey("home_lat"); val HOME_LON = doublePreferencesKey("home_lon")
        val COMPETITION_MARK1_LAT = doublePreferencesKey("competition_mark1_lat"); val COMPETITION_MARK1_LON = doublePreferencesKey("competition_mark1_lon")
        val COMPETITION_MARK2_LAT = doublePreferencesKey("competition_mark2_lat"); val COMPETITION_MARK2_LON = doublePreferencesKey("competition_mark2_lon")

        val WIND_DIR = doublePreferencesKey("wind_dir")
        val WIND_CALIBRATED = booleanPreferencesKey("wind_calibrated")

        val WAKE_LOCK_ENABLED = booleanPreferencesKey("wake_lock_enabled")
        val OPERATION_MODE = stringPreferencesKey("operation_mode")
        val APP_ROLE = stringPreferencesKey("app_role")
    }

    data class Waypoints(
        val pin: GeoPoint?, val boat: GeoPoint?, val target: GeoPoint?,
        val buoy1: GeoPoint?, val buoy2: GeoPoint?,
        val lakeCircles: List<LakeCircle> = emptyList(),
        val home: GeoPoint? = null,
        val competitionMark1: GeoPoint? = null,
        val competitionMark2: GeoPoint? = null,
    )

    data class WindCalib(val windDir: Double?, val calibrated: Boolean)

    private fun serializeLakeCircles(circles: List<LakeCircle>): String {
        val arr = JSONArray()
        circles.forEach { c ->
            arr.put(JSONObject().apply {
                put("lat", c.center.lat)
                put("lon", c.center.lon)
                put("radius", c.radiusM)
            })
        }
        return arr.toString()
    }

    private fun parseLakeCircles(json: String?): List<LakeCircle> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LakeCircle(GeoPoint(o.getDouble("lat"), o.getDouble("lon")), o.getDouble("radius"))
            }
        } catch (e: Exception) {
            emptyList() // beschädigtes/altes Format - lieber leer als abstürzen
        }
    }

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
            lakeCircles = parseLakeCircles(p[Keys.LAKE_CIRCLES_JSON]),
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
                "home" -> { p[Keys.HOME_LAT] = point.lat; p[Keys.HOME_LON] = point.lon }
                "competitionMark1" -> { p[Keys.COMPETITION_MARK1_LAT] = point.lat; p[Keys.COMPETITION_MARK1_LON] = point.lon }
                "competitionMark2" -> { p[Keys.COMPETITION_MARK2_LAT] = point.lat; p[Keys.COMPETITION_MARK2_LON] = point.lon }
            }
        }
    }

    // ---- See-Geofence: Kette von Kreisen statt einzelnem Mittelpunkt+Radius ----
    // (siehe Klassenkommentar bei LAKE_CIRCLES_JSON und LakeGeofenceEngine)

    /** Neuer Kreis, Mittelpunkt = übergebene Position, Radius zunächst 0 - wird über [addLakeEdgeSampleToLastCircle] befüllt ("Kreis hinzufügen" in der UI). */
    suspend fun addLakeCircle(center: GeoPoint) {
        context.dataStore.edit { p ->
            val circles = parseLakeCircles(p[Keys.LAKE_CIRCLES_JSON]).toMutableList()
            circles.add(LakeCircle(center, 0.0))
            p[Keys.LAKE_CIRCLES_JSON] = serializeLakeCircles(circles)
        }
    }

    /** See-Rand für den ZULETZT hinzugefügten Kreis: merkt sich den KLEINSTEN gemessenen Abstand als dessen Radius (gleiches Prinzip wie zuvor, jetzt pro Kreis, Abschnitt 6.5). */
    suspend fun addLakeEdgeSampleToLastCircle(edgePoint: GeoPoint) {
        context.dataStore.edit { p ->
            val circles = parseLakeCircles(p[Keys.LAKE_CIRCLES_JSON]).toMutableList()
            val last = circles.lastOrNull() ?: return@edit
            val d = com.segeluhr.app.core.GeoUtils.distanceMeters(last.center.lat, last.center.lon, edgePoint.lat, edgePoint.lon)
            val newRadius = if (last.radiusM <= 0.0) d else minOf(last.radiusM, d)
            circles[circles.size - 1] = last.copy(radiusM = newRadius)
            p[Keys.LAKE_CIRCLES_JSON] = serializeLakeCircles(circles)
        }
    }

    suspend fun removeLakeCircle(index: Int) {
        context.dataStore.edit { p ->
            val circles = parseLakeCircles(p[Keys.LAKE_CIRCLES_JSON]).toMutableList()
            if (index in circles.indices) circles.removeAt(index)
            p[Keys.LAKE_CIRCLES_JSON] = serializeLakeCircles(circles)
        }
    }

    /** Ersetzt die komplette Kreis-Kette, z.B. mit dem Ergebnis der automatischen See-Erkennung (LakeAutoDetector). */
    suspend fun setLakeCircles(circles: List<LakeCircle>) {
        context.dataStore.edit { p -> p[Keys.LAKE_CIRCLES_JSON] = serializeLakeCircles(circles) }
    }

    suspend fun clearLakeCircles() {
        context.dataStore.edit { p -> p.remove(Keys.LAKE_CIRCLES_JSON) }
    }

    suspend fun clearWaypoint(key: String) {
        context.dataStore.edit { p ->
            when (key) {
                "pin" -> { p.remove(Keys.PIN_LAT); p.remove(Keys.PIN_LON) }
                "boat" -> { p.remove(Keys.BOAT_LAT); p.remove(Keys.BOAT_LON) }
                "target" -> { p.remove(Keys.TARGET_LAT); p.remove(Keys.TARGET_LON) }
                "buoy1" -> { p.remove(Keys.BUOY1_LAT); p.remove(Keys.BUOY1_LON) }
                "buoy2" -> { p.remove(Keys.BUOY2_LAT); p.remove(Keys.BUOY2_LON) }
                // Ganze Kreis-Kette auf einmal leeren (z.B. "×" in der UI neben den
                // Kreis-Aktionen) - einzelne Kreise siehe removeLakeCircle(index).
                "lakeCircles" -> p.remove(Keys.LAKE_CIRCLES_JSON)
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

    val appRoleFlow: Flow<AppRole> = context.dataStore.data.map { p ->
        when (p[Keys.APP_ROLE]) {
            AppRole.SHORE.name -> AppRole.SHORE
            else -> AppRole.SAILOR // Default: bisheriges Verhalten, niemand faellt versehentlich in den Land-Modus
        }
    }

    suspend fun setAppRole(role: AppRole) {
        context.dataStore.edit { it[Keys.APP_ROLE] = role.name }
    }

    suspend fun currentWaypoints(): Waypoints = waypointsFlow.first()

    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
    }
}
