package com.segeluhr.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.segeluhr.app.core.Constants
import com.segeluhr.app.core.GeoPoint
import com.segeluhr.app.core.LakeCircle
import com.segeluhr.app.data.model.AppRole
import com.segeluhr.app.data.model.BoatProfile
import com.segeluhr.app.data.model.OperationMode
import com.segeluhr.app.tracker.TrackerSettings
import java.util.UUID
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

        // Boots-Kalibrierung (siehe docs/Erweiterung_Boots_Kalibrierung.md) -
        // MEHRERE benannte Profile (z.B. pro Boot), je mit eigenem gelernten
        // Am-Wind-Wendewinkel, ersetzt den vorher fest verdrahteten 45°-Wert
        // in HomeEngine/CompetitionEngine. Gleiches JSON-Array-Muster wie
        // LAKE_CIRCLES_JSON. Bewusst getrennt von WIND_DIR/WIND_CALIBRATED:
        // das ist eine Boots-Eigenschaft, keine Windmessung, und soll auch
        // über eine neue Windkalibrierung hinweg erhalten bleiben.
        val BOAT_PROFILES_JSON = stringPreferencesKey("boat_profiles_json")
        val ACTIVE_BOAT_PROFILE_ID = stringPreferencesKey("active_boat_profile_id")

        val WAKE_LOCK_ENABLED = booleanPreferencesKey("wake_lock_enabled")
        val OPERATION_MODE = stringPreferencesKey("operation_mode")
        val APP_ROLE = stringPreferencesKey("app_role")

        // Regatta-Tracker-Anbindung, siehe docs/Erweiterung_Regatta_Tracker.md
        val TRACKER_URL = stringPreferencesKey("tracker_url")
        val TRACKER_ID = stringPreferencesKey("tracker_id")
        val TRACKER_TOKEN = stringPreferencesKey("tracker_token")
        val TRACKER_ENABLED = booleanPreferencesKey("tracker_enabled")
    }

    /** Wegpunkt-Schlüssel (wie in setWaypoint/clearWaypoint) -> DataStore-Keys, für Konfig-Export/-Import. */
    private val waypointKeys: Map<String, Pair<Preferences.Key<Double>, Preferences.Key<Double>>> = mapOf(
        "pin" to (Keys.PIN_LAT to Keys.PIN_LON),
        "boat" to (Keys.BOAT_LAT to Keys.BOAT_LON),
        "target" to (Keys.TARGET_LAT to Keys.TARGET_LON),
        "buoy1" to (Keys.BUOY1_LAT to Keys.BUOY1_LON),
        "buoy2" to (Keys.BUOY2_LAT to Keys.BUOY2_LON),
        "home" to (Keys.HOME_LAT to Keys.HOME_LON),
        "competitionMark1" to (Keys.COMPETITION_MARK1_LAT to Keys.COMPETITION_MARK1_LON),
        "competitionMark2" to (Keys.COMPETITION_MARK2_LAT to Keys.COMPETITION_MARK2_LON),
    )

    data class Waypoints(
        val pin: GeoPoint?, val boat: GeoPoint?, val target: GeoPoint?,
        val buoy1: GeoPoint?, val buoy2: GeoPoint?,
        val lakeCircles: List<LakeCircle> = emptyList(),
        val home: GeoPoint? = null,
        val competitionMark1: GeoPoint? = null,
        val competitionMark2: GeoPoint? = null,
    )

    data class WindCalib(val windDir: Double?, val calibrated: Boolean)

    /** [activeProfileId] zeigt immer auf ein Element aus [profiles] — nie leer, siehe [boatProfilesFlow]. */
    data class BoatProfiles(val profiles: List<BoatProfile>, val activeProfileId: String)

    companion object {
        /**
         * Grundprofil, mit dem jede Installation startet (siehe
         * docs/Erweiterung_Boots_Kalibrierung.md). Wendewinkel = Mittelwert
         * der drei geschätzten Am-Wind-TWA-Bänder des Musto Skiff
         * (Leichtwind 42-48°, Mittelwind/optimales Pointing 38-42°,
         * Starkwind 40-50° — Mittelpunkte 45/40/45 gemittelt = 43.3° ≈ 43°).
         * Vorwind-Winkel (10.08.2026 ergänzt) analog aus den drei
         * geschätzten Vorwind-TWA-Bändern derselben Referenzdaten
         * (Leichtwind 130-145°, Haupt-Racebereich 140-155°, Starkwind
         * 155-170° — Mittelpunkte 137.5/147.5/162.5 gemittelt = 149.2° ≈ 149°).
         * Community-Schätzwerte (Musto Skiff Class Association +
         * Vergleichsklassen 49er/RS800), KEINE echten Messdaten -
         * `sampleCount = 0` spiegelt das bewusst wider ("noch nicht
         * kalibriert"), auch wenn die Winkel nicht mehr die generischen
         * Fallback-Werte (45°/180°) sind. Für den Vorwind-Winkel gibt es
         * ohnehin kein sampleCount-Äquivalent (siehe Klassendoku
         * BoatProfile.downwindAngleDeg).
         */
        val DEFAULT_BOAT_PROFILE = BoatProfile(
            id = "musto-skiff-default",
            name = "Musto Skiff",
            closehauledAngleDeg = 43.0,
            sampleCount = 0,
            downwindAngleDeg = 149.0,
        )
    }

    private fun serializeBoatProfiles(profiles: List<BoatProfile>): String {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("closehauledAngleDeg", p.closehauledAngleDeg)
                put("sampleCount", p.sampleCount)
                put("downwindAngleDeg", p.downwindAngleDeg)
            })
        }
        return arr.toString()
    }

    private fun parseBoatProfiles(json: String?): List<BoatProfile> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BoatProfile(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    closehauledAngleDeg = o.getDouble("closehauledAngleDeg"),
                    sampleCount = o.getInt("sampleCount"),
                    // optDouble mit Fallback: Profile, die vor dem 10.08.2026
                    // gespeichert wurden, haben dieses Feld noch nicht -
                    // sonst würde jedes bestehende Profil beim ersten Laden
                    // nach dem Update auf parseBoatProfiles()-Exception und
                    // damit auf DEFAULT_BOAT_PROFILE zurückfallen.
                    downwindAngleDeg = o.optDouble("downwindAngleDeg", Constants.DEFAULT_DOWNWIND_ANGLE_DEG),
                )
            }
        } catch (e: Exception) {
            emptyList() // beschädigtes/altes Format - lieber leer als abstürzen (fällt dann auf DEFAULT_BOAT_PROFILE zurück)
        }
    }

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

    val boatProfilesFlow: Flow<BoatProfiles> = context.dataStore.data.map { p ->
        val stored = parseBoatProfiles(p[Keys.BOAT_PROFILES_JSON])
        val profiles = stored.ifEmpty { listOf(DEFAULT_BOAT_PROFILE) }
        // Zeigt die gespeicherte ID auf kein (mehr) vorhandenes Profil (z.B.
        // gelöscht) oder ist noch nie gesetzt -> erstes Profil übernehmen.
        val activeId = p[Keys.ACTIVE_BOAT_PROFILE_ID]?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.first().id
        BoatProfiles(profiles, activeId)
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

    // ---- Boots-Kalibrierung: mehrere Profile ----
    // (siehe Klassenkommentar bei BOAT_PROFILES_JSON und
    // docs/Erweiterung_Boots_Kalibrierung.md)

    /** Aktualisiert Wende-/Vorwind-Winkel + Kalibrierlauf-Zähler EINES Profils (Kalibrierungs-/Smart-Modus in WindEngine). */
    suspend fun updateBoatProfileCalibration(id: String, closehauledAngleDeg: Double, sampleCount: Int, downwindAngleDeg: Double) {
        context.dataStore.edit { p ->
            val current = parseBoatProfiles(p[Keys.BOAT_PROFILES_JSON]).ifEmpty { listOf(DEFAULT_BOAT_PROFILE) }.toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            if (idx >= 0) {
                current[idx] = current[idx].copy(
                    closehauledAngleDeg = closehauledAngleDeg,
                    sampleCount = sampleCount,
                    downwindAngleDeg = downwindAngleDeg,
                )
                p[Keys.BOAT_PROFILES_JSON] = serializeBoatProfiles(current)
            }
        }
    }

    /** Neues Profil anlegen (Setup-Tab "Neues Profil") — startet bei den generischen Standardwinkeln (45°/180°) und wird gleich aktiviert. */
    suspend fun addBoatProfile(name: String): BoatProfile {
        val newProfile = BoatProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            closehauledAngleDeg = Constants.DEFAULT_CLOSEHAULED_ANGLE_DEG,
            sampleCount = 0,
            downwindAngleDeg = Constants.DEFAULT_DOWNWIND_ANGLE_DEG,
        )
        context.dataStore.edit { p ->
            val current = parseBoatProfiles(p[Keys.BOAT_PROFILES_JSON]).ifEmpty { listOf(DEFAULT_BOAT_PROFILE) }.toMutableList()
            current.add(newProfile)
            p[Keys.BOAT_PROFILES_JSON] = serializeBoatProfiles(current)
            p[Keys.ACTIVE_BOAT_PROFILE_ID] = newProfile.id
        }
        return newProfile
    }

    suspend fun setActiveBoatProfile(id: String) {
        context.dataStore.edit { p -> p[Keys.ACTIVE_BOAT_PROFILE_ID] = id }
    }

    /** Löscht ein Profil — mindestens eines bleibt immer bestehen. War es das aktive, übernimmt ein verbleibendes. */
    suspend fun deleteBoatProfile(id: String) {
        context.dataStore.edit { p ->
            val current = parseBoatProfiles(p[Keys.BOAT_PROFILES_JSON]).ifEmpty { listOf(DEFAULT_BOAT_PROFILE) }.toMutableList()
            if (current.size <= 1) return@edit
            current.removeAll { it.id == id }
            p[Keys.BOAT_PROFILES_JSON] = serializeBoatProfiles(current)
            if (p[Keys.ACTIVE_BOAT_PROFILE_ID] == id) {
                p[Keys.ACTIVE_BOAT_PROFILE_ID] = current.first().id
            }
        }
    }

    /** Setzt Wende- UND Vorwind-Winkel EINES Profils zurück (Wind-Tab-Button) — Name/ID bleiben erhalten. */
    suspend fun resetBoatProfileCalibration(id: String) {
        updateBoatProfileCalibration(id, Constants.DEFAULT_CLOSEHAULED_ANGLE_DEG, 0, Constants.DEFAULT_DOWNWIND_ANGLE_DEG)
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

    // ---- Regatta-Tracker (siehe docs/Erweiterung_Regatta_Tracker.md) ----

    val trackerSettingsFlow: Flow<TrackerSettings> = context.dataStore.data.map { p ->
        TrackerSettings(
            serverUrl = p[Keys.TRACKER_URL].orEmpty(),
            trackerId = p[Keys.TRACKER_ID].orEmpty(),
            token = p[Keys.TRACKER_TOKEN].orEmpty(),
            enabled = p[Keys.TRACKER_ENABLED] ?: false,
        )
    }

    /** Speichert Server/ID/Token aus dem Tracker-Link; [TrackerSettings.enabled] bleibt unverändert. */
    suspend fun setTrackerCredentials(serverUrl: String, trackerId: String, token: String) {
        context.dataStore.edit { p ->
            p[Keys.TRACKER_URL] = serverUrl; p[Keys.TRACKER_ID] = trackerId; p[Keys.TRACKER_TOKEN] = token
        }
    }

    suspend fun setTrackerEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TRACKER_ENABLED] = enabled }
    }

    suspend fun clearTracker() {
        context.dataStore.edit { p ->
            p.remove(Keys.TRACKER_URL); p.remove(Keys.TRACKER_ID); p.remove(Keys.TRACKER_TOKEN)
            p.remove(Keys.TRACKER_ENABLED)
        }
    }

    // ---- Konfiguration auf dem Tracker-Server ablegen / von dort laden ----
    // Enthält bewusst NUR geräteunabhängige Einrichtung (Wegpunkte, See-Grenze,
    // Boots-Profile, Betriebsmodus, Display-wach). NICHT enthalten: Windrichtung
    // (tagesaktuell), Rolle (pro Handy), Tracker-Zugangsdaten.

    /** Aktueller Konfig-Stand als JSON (Format siehe docs/Erweiterung_Regatta_Tracker.md). */
    suspend fun exportConfigJson(): JSONObject {
        val p = context.dataStore.data.first()
        val wp = JSONObject()
        waypointKeys.forEach { (name, keys) ->
            val lat = p[keys.first]; val lon = p[keys.second]
            if (lat != null && lon != null) wp.put(name, JSONObject().put("lat", lat).put("lon", lon))
        }
        wp.put("lakeCircles", JSONArray(serializeLakeCircles(parseLakeCircles(p[Keys.LAKE_CIRCLES_JSON]))))
        val profiles = boatProfilesFlow.first()
        return JSONObject().apply {
            put("app", "segeluhr")
            put("version", 1)
            put("waypoints", wp)
            put("boatProfiles", JSONArray(serializeBoatProfiles(profiles.profiles)))
            put("activeBoatProfileId", profiles.activeProfileId)
            put("wakeLockEnabled", p[Keys.WAKE_LOCK_ENABLED] ?: false)
            put("operationMode", p[Keys.OPERATION_MODE] ?: OperationMode.STANDALONE.name)
        }
    }

    /**
     * Ersetzt die lokale Konfiguration durch [config] (Format wie [exportConfigJson]).
     * Wegpunkte, die in [config] fehlen, werden lokal gelöscht (Server-Stand ist maßgeblich).
     * Wirft [IllegalArgumentException], wenn es keine Segeluhr-Konfiguration ist — dann bleibt alles unverändert.
     * Der Betriebsmodus wird NICHT hier gesetzt (Foreground-Service-Steuerung liegt im ViewModel).
     */
    suspend fun importConfigJson(config: JSONObject) {
        require(config.optString("app") == "segeluhr") { "Keine Segeluhr-Konfiguration" }
        val wp = config.optJSONObject("waypoints")
        val profiles = parseBoatProfiles(config.optJSONArray("boatProfiles")?.toString())
        context.dataStore.edit { p ->
            waypointKeys.forEach { (name, keys) ->
                val o = wp?.optJSONObject(name)
                if (o != null && o.has("lat") && o.has("lon")) {
                    p[keys.first] = o.getDouble("lat"); p[keys.second] = o.getDouble("lon")
                } else {
                    p.remove(keys.first); p.remove(keys.second)
                }
            }
            val circles = wp?.optJSONArray("lakeCircles")?.toString()
            if (circles != null) p[Keys.LAKE_CIRCLES_JSON] = serializeLakeCircles(parseLakeCircles(circles))
            else p.remove(Keys.LAKE_CIRCLES_JSON)
            if (profiles.isNotEmpty()) {
                p[Keys.BOAT_PROFILES_JSON] = serializeBoatProfiles(profiles)
                val active = config.optString("activeBoatProfileId")
                if (profiles.any { it.id == active }) p[Keys.ACTIVE_BOAT_PROFILE_ID] = active
                else p[Keys.ACTIVE_BOAT_PROFILE_ID] = profiles.first().id
            }
            if (config.has("wakeLockEnabled")) p[Keys.WAKE_LOCK_ENABLED] = config.optBoolean("wakeLockEnabled")
        }
    }

    suspend fun currentWaypoints(): Waypoints = waypointsFlow.first()

    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
    }
}
