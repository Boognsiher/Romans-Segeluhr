package com.segeluhr.app.ble

import com.segeluhr.app.core.Fix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * GATT-Service-Definition und Paket-Encoding gemäß BLE_Protokoll.md.
 * Rollenverteilung: Handy = Peripheral/GATT-Server (dieser Code),
 * T-Watch Ultra = Central/Client (scannt, verbindet, abonniert Notify).
 */
object BleProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("6f6e0001-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_GPS_UUID: UUID = UUID.fromString("6f6e0002-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_BATTERY_UUID: UUID = UUID.fromString("6f6e0003-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_CONTROL_UUID: UUID = UUID.fromString("6f6e0004-b5a3-f393-e0a9-e50e24dcca9e")

    /**
     * NEU (Erweiterung ggü. der ursprünglichen BLE_Protokoll.md): 1-Byte
     * NOTIFY-Characteristic, Handy -> Uhr. Wird nur im Modus "Mit Uhr"
     * genutzt: statt selbst zu vibrieren, schickt das Handy hier den
     * Vibrationsmuster-Code, die Uhr triggert dann ihren eigenen Motor.
     * Siehe BLE_Protokoll_Ergaenzung_Haptik.md für die Firmware-Seite.
     */
    val CHAR_HAPTIC_UUID: UUID = UUID.fromString("6f6e0005-b5a3-f393-e0a9-e50e24dcca9e")

    /**
     * NEU (Erweiterung für die Heimweg-LoRa-Anbindung, siehe
     * docs/BLE_Protokoll_Ergaenzung_Heimweg_LoRa.md): 3-Byte NOTIFY-
     * Characteristic, Handy -> Ultra-Watch. Die aktuelle Position kommt
     * bereits über CHAR_GPS_UUID; hier steht nur, OB der Heimweg-Modus
     * aktiv ist, ob eine Wende empfohlen wird, und die ETA in Minuten.
     * Die Ultra-Watch entscheidet anhand von flags.bit0, ob sie überhaupt
     * eine LoRa-Nachricht an die Watch S sendet.
     */
    val CHAR_HOME_STATUS_UUID: UUID = UUID.fromString("6f6e0006-b5a3-f393-e0a9-e50e24dcca9e")

    private const val HOME_FLAG_ACTIVE: Int = 1 shl 0
    private const val HOME_FLAG_MANEUVER_NEEDED: Int = 1 shl 1

    // Muster-Codes für CHAR_HAPTIC_UUID, 1:1 zu Abschnitt 7 der Spezifikation
    const val HAPTIC_STEP1: Int = 1
    const val HAPTIC_DONE2: Int = 2
    const val HAPTIC_HEADER3: Int = 3
    const val HAPTIC_ERROR4: Int = 4
    const val HAPTIC_LAKE_WARN5: Int = 5
    const val HAPTIC_ROUNDING6: Int = 6
    const val HAPTIC_MANEUVER_CMD: Int = 7
    const val HAPTIC_START_SIGNAL: Int = 8

    fun encodeHapticCommand(code: Int): ByteArray = byteArrayOf(code.toByte())

    // Client Characteristic Configuration Descriptor (Standard-UUID für Notify-Anmeldung)
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val FLAG_VALID_FIX = 1 shl 0
    private const val FLAG_BATTERY_LOW = 1 shl 1

    /**
     * 16-Byte GpsPacket, siehe Abschnitt 3 von BLE_Protokoll.md:
     * int32 lat_e7, int32 lon_e7, uint16 cog_ddeg, uint16 sog_ckn,
     * uint16 fixAgeMs, uint8 accuracyM, uint8 flags. Little-Endian.
     */
    fun encodeGpsPacket(fix: Fix, phoneBatteryLow: Boolean): ByteArray {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)

        val latE7 = ((fix.lat ?: 0.0) * 1e7).toInt()
        val lonE7 = ((fix.lon ?: 0.0) * 1e7).toInt()
        // 0xFFFF = ungültig, wenn kein brauchbarer COG vorhanden
        val cogDdeg: Int = fix.cogDeg?.let { (it * 10.0).toInt().coerceIn(0, 3599) } ?: 0xFFFF
        val sogCkn: Int = ((fix.sogKn ?: 0.0) * 100.0).toInt().coerceIn(0, 65535)
        val fixAgeMs: Int = (System.currentTimeMillis() - fix.timestampMs).toInt().coerceIn(0, 65535)
        val accuracyM: Int = (fix.accuracyM?.toInt() ?: 255).coerceIn(0, 255)

        var flags = 0
        if (fix.valid && fix.cogDeg != null) flags = flags or FLAG_VALID_FIX
        if (phoneBatteryLow) flags = flags or FLAG_BATTERY_LOW

        buf.putInt(latE7)
        buf.putInt(lonE7)
        buf.putShort(cogDdeg.toShort())
        buf.putShort(sogCkn.toShort())
        buf.putShort(fixAgeMs.toShort())
        buf.put(accuracyM.toByte())
        buf.put(flags.toByte())
        return buf.array()
    }

    fun encodeBatteryLevel(percent: Int): ByteArray = byteArrayOf(percent.coerceIn(0, 100).toByte())

    /**
     * 3-Byte HomeStatusPacket: uint8 flags, uint16 etaMinutes (little-endian).
     * etaMinutes = 0xFFFF bedeutet "keine ETA verfügbar" (kein Fortschritt
     * Richtung Heimatpunkt, oder Heimweg-Modus nicht aktiv).
     */
    fun encodeHomeStatus(active: Boolean, maneuverNeeded: Boolean, etaMinutes: Int?): ByteArray {
        var flags = 0
        if (active) flags = flags or HOME_FLAG_ACTIVE
        if (maneuverNeeded) flags = flags or HOME_FLAG_MANEUVER_NEEDED
        val eta = (etaMinutes ?: 0xFFFF).coerceIn(0, 0xFFFF)
        val buf = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(flags.toByte())
        buf.putShort(eta.toShort())
        return buf.array()
    }
}
