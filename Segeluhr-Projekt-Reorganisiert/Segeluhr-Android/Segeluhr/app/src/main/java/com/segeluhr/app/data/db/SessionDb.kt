package com.segeluhr.app.data.db

import androidx.room.*
import com.segeluhr.app.core.GeoPoint
import com.segeluhr.app.data.model.SessionKind
import com.segeluhr.app.data.model.SessionReport
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

/**
 * Persistenz für die Tages-/Wettfahrt-Auswertung (Erweiterung, 17.08.2026,
 * siehe docs/Erweiterung_Tages_Auswertung.md und logic/SessionSummaryEngine.kt).
 * Ein Eintrag pro Tag (DAY, wächst über Stopp/Start-Zyklen hinweg — siehe
 * [SessionDao.upsertDay]) plus ein Eintrag pro einzelner Wettfahrt (RACE).
 *
 * Route bewusst als JSON-Text-Spalte (org.json, gleiches Muster wie
 * SettingsRepository.BOAT_PROFILES_JSON) statt einer eigenen Tabelle —
 * downgesampelt auf max. SessionSummaryEngine.ROUTE_MAX_POINTS Punkte,
 * bleibt damit klein genug für eine simple TEXT-Spalte.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String, // SessionKind.name - Room mag Enums nur mit eigenem TypeConverter, String reicht hier
    val startedAtMs: Long,
    val durationS: Long,
    val distanceM: Double,
    val maxSpeedKn: Double,
    val avgSpeedKn: Double?,
    val tackCount: Int,
    val avgTackAngleDeg: Double?,
    val gybeCount: Int,
    val avgGybeAngleDeg: Double?,
    val windShiftCount: Int,
    val windShiftHeaderCount: Int,
    val windShiftLiftCount: Int,
    val windCalibrationCount: Int,
    val finalClosehauledAngleDeg: Double,
    val finalDownwindAngleDeg: Double,
    val watchConnectedPct: Double?,
    val routeJson: String,
)

fun SessionEntity.toReport() = SessionReport(
    id = id,
    kind = SessionKind.valueOf(kind),
    startedAtMs = startedAtMs,
    durationS = durationS,
    distanceM = distanceM,
    maxSpeedKn = maxSpeedKn,
    avgSpeedKn = avgSpeedKn,
    tackCount = tackCount,
    avgTackAngleDeg = avgTackAngleDeg,
    gybeCount = gybeCount,
    avgGybeAngleDeg = avgGybeAngleDeg,
    windShiftCount = windShiftCount,
    windShiftHeaderCount = windShiftHeaderCount,
    windShiftLiftCount = windShiftLiftCount,
    windCalibrationCount = windCalibrationCount,
    finalClosehauledAngleDeg = finalClosehauledAngleDeg,
    finalDownwindAngleDeg = finalDownwindAngleDeg,
    watchConnectedPct = watchConnectedPct,
    route = decodeRoute(routeJson),
)

fun SessionReport.toEntity() = SessionEntity(
    id = id ?: 0,
    kind = kind.name,
    startedAtMs = startedAtMs,
    durationS = durationS,
    distanceM = distanceM,
    maxSpeedKn = maxSpeedKn,
    avgSpeedKn = avgSpeedKn,
    tackCount = tackCount,
    avgTackAngleDeg = avgTackAngleDeg,
    gybeCount = gybeCount,
    avgGybeAngleDeg = avgGybeAngleDeg,
    windShiftCount = windShiftCount,
    windShiftHeaderCount = windShiftHeaderCount,
    windShiftLiftCount = windShiftLiftCount,
    windCalibrationCount = windCalibrationCount,
    finalClosehauledAngleDeg = finalClosehauledAngleDeg,
    finalDownwindAngleDeg = finalDownwindAngleDeg,
    watchConnectedPct = watchConnectedPct,
    routeJson = encodeRoute(route),
)

private fun encodeRoute(route: List<GeoPoint>): String {
    val arr = JSONArray()
    route.forEach { p -> arr.put(JSONArray().apply { put(p.lat); put(p.lon) }) }
    return arr.toString()
}

private fun decodeRoute(json: String): List<GeoPoint> {
    if (json.isBlank()) return emptyList()
    val arr = JSONArray(json)
    return (0 until arr.length()).map { i ->
        val pair = arr.getJSONArray(i)
        GeoPoint(pair.getDouble(0), pair.getDouble(1))
    }
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    @Insert
    suspend fun insert(entity: SessionEntity): Long

    @Update
    suspend fun update(entity: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Tages-Eintrag (siehe Klassendoku): existiert noch keiner (id == 0),
     * wird eingefügt und die neue id zurückgegeben; sonst wird der
     * bestehende Eintrag überschrieben (id bleibt gleich, Route/Zahlen
     * wachsen über Stopp/Start-Zyklen weiter).
     */
    @Transaction
    suspend fun upsertDay(entity: SessionEntity): Long =
        if (entity.id == 0L) insert(entity) else { update(entity); entity.id }
}
