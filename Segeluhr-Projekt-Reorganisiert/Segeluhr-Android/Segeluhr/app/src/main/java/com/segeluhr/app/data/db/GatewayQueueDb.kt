package com.segeluhr.app.data.db

import androidx.room.*
import com.segeluhr.app.ble.GatewayPacket

/**
 * Persistente Warteschlange für per LoRa/BLE vom "RegattaMaster" empfangene
 * Positionen anderer Boote, bevor sie an POST /api/gateway/positions gehen,
 * siehe docs/Erweiterung_Regatta_Tracker_Gateway.md Abschnitt 4. Persistent
 * (nicht nur RAM/ArrayDeque wie beim eigenen [com.segeluhr.app.tracker.TrackerPosition]-
 * Puffer), weil hier echte Fremddaten reinkommen, die ohne Zwischenspeicherung
 * bei App-Neustart/Verbindungsabbruch sonst verloren wären.
 */
@Entity(tableName = "gateway_positions")
data class GatewayPositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val loraId: Int,
    val lat: Double,
    val lon: Double,
    val timestampMs: Long,
    val headingDeg: Double?,
    val batteryFraction: Double?,
    val accuracyM: Double?,
)

fun GatewayPacket.toEntity() = GatewayPositionEntity(
    loraId = loraId,
    lat = lat,
    lon = lon,
    timestampMs = timestampMs,
    headingDeg = headingDeg,
    // Byte-Protokoll liefert 0..100 (Prozent), Server-Payload erwartet 0..1 -
    // siehe GatewayPacketCodec-Klassendoku, bewusst erst hier umgerechnet statt
    // im Codec, damit GatewayPacket.batteryPct 1:1 das Wire-Format widerspiegelt.
    batteryFraction = batteryPct?.let { it / 100.0 },
    // HDOP * 5 als grobe Meter-Näherung, wie im Gateway-Protokoll vorgeschlagen.
    accuracyM = hdop?.let { it * 5.0 },
)

@Dao
interface GatewayQueueDao {
    @Insert
    suspend fun insert(entity: GatewayPositionEntity)

    @Query("SELECT * FROM gateway_positions ORDER BY id ASC LIMIT :limit")
    suspend fun nextBatch(limit: Int): List<GatewayPositionEntity>

    @Query("DELETE FROM gateway_positions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM gateway_positions")
    suspend fun count(): Int

    // Bei langem Funkloch nicht unbegrenzt wachsen - älteste Einträge über das
    // Limit hinaus verwerfen (analog zum RAM-Puffer-Limit beim eigenen Tracker-
    // Senden, TRACKER_MAX_BUFFER in SegeluhrViewModel, hier aber grösser, weil
    // hier mehrere Fremdboote gleichzeitig durchlaufen).
    @Query(
        """DELETE FROM gateway_positions WHERE id NOT IN
           (SELECT id FROM gateway_positions ORDER BY id DESC LIMIT :limit)"""
    )
    suspend fun trimTo(limit: Int)

    @Query("DELETE FROM gateway_positions")
    suspend fun clearAll()
}
