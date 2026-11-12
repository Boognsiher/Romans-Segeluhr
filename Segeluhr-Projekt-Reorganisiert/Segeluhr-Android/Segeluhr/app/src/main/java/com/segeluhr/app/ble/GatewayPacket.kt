package com.segeluhr.app.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Ein per LoRa empfangenes Positionspaket eines ANDEREN Boots, weitergereicht
 * vom "RegattaMaster"-Board über dessen BLE-Notify-Characteristic, siehe
 * docs/Erweiterung_Regatta_Tracker_Gateway.md. Getrennt vom eigenen
 * [com.segeluhr.app.tracker.TrackerPosition] (eigenes Boot, per REST direkt
 * vom Handy-GPS).
 */
data class GatewayPacket(
    val loraId: Int,
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMs: Double?,
    val headingDeg: Double?,
    /** 0..100, nicht 0..1 (anders als [com.segeluhr.app.tracker.TrackerPosition.battery]!) — siehe [GatewayPacketCodec.decode]. */
    val batteryPct: Int?,
    val hdop: Double?,
    val rssi: Int,
    val snrDb: Double,
)

/**
 * Decodiert das 24-Byte-Binärpaket vom "RegattaMaster" (Little-Endian).
 * Format siehe docs/Erweiterung_Regatta_Tracker_Gateway.md Abschnitt 2.
 */
object GatewayPacketCodec {
    const val PACKET_SIZE = 24
    private const val MAGIC: Int = 0xA7

    fun decode(bytes: ByteArray): GatewayPacket? {
        if (bytes.size < PACKET_SIZE) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.get().toInt() and 0xFF
        if (magic != MAGIC) return null
        val id = buf.get().toInt() and 0xFF
        val epochSec = buf.int.toLong() and 0xFFFFFFFFL
        val centisec = buf.get().toInt() and 0xFF
        val lat = buf.int / 1e6
        val lon = buf.int / 1e6
        val speedCms = buf.short.toInt() and 0xFFFF
        val headingDeg10 = buf.short.toInt() and 0xFFFF
        val battPct = buf.get().toInt() and 0xFF
        val hdop10 = buf.get().toInt() and 0xFF
        val rssi = buf.short.toInt()
        val snr4 = buf.get().toInt()

        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null

        return GatewayPacket(
            loraId = id,
            timestampMs = epochSec * 1000L + centisec * 10L,
            lat = lat,
            lon = lon,
            speedMs = speedCms / 100.0,
            headingDeg = if (headingDeg10 == 0xFFFF) null else headingDeg10 / 10.0,
            batteryPct = if (battPct == 255) null else battPct,
            hdop = if (hdop10 == 255) null else hdop10 / 10.0,
            rssi = rssi,
            snrDb = snr4 / 4.0,
        )
    }
}
