package com.segeluhr.wear.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Gegenstück zu app/.../ble/BleProtocol.kt auf der Handy-Seite — dieselben
 * UUIDs/Byte-Layouts, hier aber DEKODIEREND statt encodierend, weil die Uhr
 * hier die Central-/Client-Rolle übernimmt (bisher: T-Watch-Ultra-Firmware —
 * die einzige Boots-Uhr mit BLE-Central-Rolle zum Handy, die S3 ist reine
 * Land-Uhr ohne diese Verbindung, siehe BLE_Protokoll.md und
 * docs/Erweiterung_BLE_Wind_RaceStatus.md).
 *
 * TODO bei weiterem Ausbau: Duplikation zwischen hier und der Handy-Seite
 * (identische UUIDs/Layouts an zwei Stellen gepflegt) in ein gemeinsames
 * Kotlin-Modul auslagern, das app/ und wear/ beide einbinden — für diesen
 * ersten BLE-Test-Screen bewusst einfach gehalten (kein Multiplatform-Umbau
 * nötig). Noch nicht abgedeckt: CHAR_CONTROL (Schreiben von CMD_*),
 * CHAR_HAPTIC (Notify -> Vibrator), CHAR_HOME_STATUS, CHAR_RACE_STATUS,
 * CHAR_TIME_SYNC (Read direkt nach Connect) — folgen nach diesem
 * Verbindungstest.
 */
object WearBleProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("6f6e0001-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_GPS_UUID: UUID = UUID.fromString("6f6e0002-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_BATTERY_UUID: UUID = UUID.fromString("6f6e0003-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_CONTROL_UUID: UUID = UUID.fromString("6f6e0004-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_HAPTIC_UUID: UUID = UUID.fromString("6f6e0005-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_HOME_STATUS_UUID: UUID = UUID.fromString("6f6e0006-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_WIND_UUID: UUID = UUID.fromString("6f6e0007-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_RACE_STATUS_UUID: UUID = UUID.fromString("6f6e0008-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_TIME_SYNC_UUID: UUID = UUID.fromString("6f6e0009-b5a3-f393-e0a9-e50e24dcca9e")

    // Client Characteristic Configuration Descriptor (Standard-UUID für Notify-Anmeldung)
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val FLAG_VALID_FIX = 1 shl 0
    private const val FLAG_BATTERY_LOW = 1 shl 1
    private const val WIND_FLAG_CALIBRATED = 1 shl 0

    data class GpsData(
        val lat: Double,
        val lon: Double,
        val cogDeg: Double?,
        val sogKn: Double,
        val fixAgeMs: Int,
        val accuracyM: Int?,
        val validFix: Boolean,
        val phoneBatteryLow: Boolean,
    )

    data class WindData(
        val windDirDeg: Double?,
        val calibrated: Boolean,
        val trendDeg: Double,
    )

    /**
     * Gegenstück zu BleProtocol.encodeGpsPacket(): 16-Byte GpsPacket,
     * Little-Endian: int32 lat_e7, int32 lon_e7, uint16 cog_ddeg,
     * uint16 sog_ckn, uint16 fixAgeMs, uint8 accuracyM, uint8 flags.
     */
    fun decodeGpsPacket(bytes: ByteArray): GpsData? {
        if (bytes.size < 16) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val latE7 = buf.int
        val lonE7 = buf.int
        val cogDdeg = buf.short.toInt() and 0xFFFF
        val sogCkn = buf.short.toInt() and 0xFFFF
        val fixAgeMs = buf.short.toInt() and 0xFFFF
        val accuracyM = buf.get().toInt() and 0xFF
        val flags = buf.get().toInt() and 0xFF

        return GpsData(
            lat = latE7 / 1e7,
            lon = lonE7 / 1e7,
            cogDeg = if (cogDdeg == 0xFFFF) null else cogDdeg / 10.0,
            sogKn = sogCkn / 100.0,
            fixAgeMs = fixAgeMs,
            accuracyM = if (accuracyM == 0xFF) null else accuracyM,
            validFix = (flags and FLAG_VALID_FIX) != 0,
            phoneBatteryLow = (flags and FLAG_BATTERY_LOW) != 0,
        )
    }

    /** Gegenstück zu BleProtocol.encodeBatteryLevel(): 1 Byte, 0-100. */
    fun decodeBatteryLevel(bytes: ByteArray): Int? {
        if (bytes.isEmpty()) return null
        return (bytes[0].toInt() and 0xFF).coerceIn(0, 100)
    }

    /**
     * Gegenstück zu BleProtocol.encodeWindStatus(): 5-Byte WindStatusPacket,
     * Little-Endian: uint16 windDirDdeg (0xFFFF = nicht kalibriert),
     * uint8 flags, int16 trendDdeg (signed).
     */
    fun decodeWindStatus(bytes: ByteArray): WindData? {
        if (bytes.size < 5) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dirDdeg = buf.short.toInt() and 0xFFFF
        val flags = buf.get().toInt() and 0xFF
        val trendDdeg = buf.short.toInt() // signed, keine Maskierung nötig

        return WindData(
            windDirDeg = if (dirDdeg == 0xFFFF) null else dirDdeg / 10.0,
            calibrated = (flags and WIND_FLAG_CALIBRATED) != 0,
            trendDeg = trendDdeg / 10.0,
        )
    }
}
