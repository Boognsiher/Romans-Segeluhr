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

    /**
     * NEU (Erweiterung für echte Live-Screens auf der T-Watch S3/Ultra,
     * siehe docs/Erweiterung_BLE_Wind_RaceStatus.md): 5-Byte NOTIFY-
     * Characteristic, Handy -> Uhr. Überträgt den geschätzten/kalibrierten
     * wahren Wind sowie den laufenden Trend (Abschnitt 4.3), damit die Uhr
     * einen eigenen Wind-Screen zeigen kann statt nur Haptik-Codes.
     */
    val CHAR_WIND_UUID: UUID = UUID.fromString("6f6e0007-b5a3-f393-e0a9-e50e24dcca9e")

    /**
     * NEU (gleiche Erweiterung): 5-Byte NOTIFY-Characteristic, Handy -> Uhr.
     * Überträgt Renn-/Countdown-/Manöverzustand, damit Countdown- und
     * Manöver-Screen auf der Uhr echte Werte statt Demo-Werte zeigen.
     */
    val CHAR_RACE_STATUS_UUID: UUID = UUID.fromString("6f6e0008-b5a3-f393-e0a9-e50e24dcca9e")

    /**
     * NEU (siehe docs/Erweiterung_TWatch_S3_ZeitSync.md): READ-Characteristic,
     * Handy -> Uhr. Die Uhr liest sie einmalig direkt nach dem Verbinden aus
     * und stellt damit ihre eigene RTC — kein manuelles Einstellen an der
     * Uhr selbst nötig (die steckt beim Segeln ohnehin im wasserdichten Sack).
     * 7-Byte-Payload, aktuelle lokale Handy-Zeit: uint16 year, uint8 month
     * (1-12), uint8 day, uint8 hour, uint8 minute, uint8 second. Bewusst
     * lokale Wanduhrzeit statt Unix-Timestamp, damit keine Zeitzonen-
     * Umrechnung auf der Uhr nötig ist.
     */
    val CHAR_TIME_SYNC_UUID: UUID = UUID.fromString("6f6e0009-b5a3-f393-e0a9-e50e24dcca9e")

    private const val HOME_FLAG_ACTIVE: Int = 1 shl 0
    private const val HOME_FLAG_MANEUVER_NEEDED: Int = 1 shl 1

    private const val WIND_FLAG_CALIBRATED: Int = 1 shl 0

    private const val MANEUVER_FLAG_NEEDED: Int = 1 shl 0
    private const val MANEUVER_FLAG_IS_TACK: Int = 1 shl 1

    // ---- CMD_*: Steuerbefehle Uhr -> Handy über CHAR_CONTROL_UUID (1 Byte, WRITE) ----
    // Neu definiert im Zuge der T-Watch-S3-Firmware (siehe
    // docs/Erweiterung_TWatch_S3_Firmware.md). Payload ist bei allen
    // Befehlen genau 1 Byte, ausser CMD_SET_WAYPOINT/CMD_CLEAR_WAYPOINT,
    // die zusätzlich 1 Byte Wegpunkt-ID mitschicken (siehe [WaypointId]).
    const val CMD_COUNTDOWN_START: Int = 1
    const val CMD_COUNTDOWN_RESET: Int = 2
    const val CMD_COUNTDOWN_SYNC_NEXT_MINUTE: Int = 3
    const val CMD_WIND_CALIBRATE_START: Int = 4
    const val CMD_WIND_CALIBRATE_ABORT: Int = 5
    const val CMD_TRAIN_MODE_OFF: Int = 6
    const val CMD_TRAIN_MODE_TACK_ONLY: Int = 7
    const val CMD_TRAIN_MODE_JIBE_ONLY: Int = 8
    const val CMD_TRAIN_MODE_RACE: Int = 9
    const val CMD_SET_WAYPOINT: Int = 10 // + 1 Byte Waypoint-ID, siehe [WaypointId]
    const val CMD_CLEAR_WAYPOINT: Int = 11 // + 1 Byte Waypoint-ID
    const val CMD_HOME_MODE_TOGGLE: Int = 12
    const val CMD_COMPETITION_END: Int = 13
    const val CMD_CLEAR_LOG: Int = 14

    /** Waypoint-IDs für CMD_SET_WAYPOINT / CMD_CLEAR_WAYPOINT (2. Byte). */
    object WaypointId {
        const val PIN: Int = 1
        const val BOAT: Int = 2
        const val TARGET: Int = 3
        const val BUOY1: Int = 4
        const val BUOY2: Int = 5
        const val LAKE_CENTER: Int = 6
        const val HOME: Int = 7
        const val COMPETITION_MARK1: Int = 8
        const val COMPETITION_MARK2: Int = 9
    }

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
     * 7-Byte HomeStatusPacket: uint8 flags, uint16 etaMinutes, uint32
     * distanceTraveledM (little-endian). etaMinutes = 0xFFFF bedeutet "keine
     * ETA verfügbar" (kein Fortschritt Richtung Heimatpunkt, oder
     * Heimweg-Modus nicht aktiv). distanceTraveledM ist die zurückgelegte
     * Session-Distanz (core/DistanceTracker.kt) — läuft unabhängig vom
     * Heimweg-Modus, deshalb IMMER mitgeschickt statt nur bei active=true
     * (siehe docs/BLE_Protokoll_Ergaenzung_Heimweg_LoRa.md, Erweiterung
     * 10.08.2026 um die LoRa-Weitergabe an die Land-Uhr).
     *
     * War bis 10.08.2026 3 Byte (nur flags+etaMinutes) — die Ultra-Watch
     * (`onHomeStatusNotify` in Segeluhr_TWatch_Ultra.ino) muss beim
     * nächsten Flash entsprechend mit aktualisiert werden, sonst liest sie
     * die neuen 4 Byte nicht.
     */
    fun encodeHomeStatus(active: Boolean, maneuverNeeded: Boolean, etaMinutes: Int?, distanceTraveledM: Int): ByteArray {
        var flags = 0
        if (active) flags = flags or HOME_FLAG_ACTIVE
        if (maneuverNeeded) flags = flags or HOME_FLAG_MANEUVER_NEEDED
        val eta = (etaMinutes ?: 0xFFFF).coerceIn(0, 0xFFFF)
        val buf = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(flags.toByte())
        buf.putShort(eta.toShort())
        buf.putInt(distanceTraveledM.coerceAtLeast(0))
        return buf.array()
    }

    /**
     * 5-Byte WindStatusPacket: uint16 windDirDdeg (0-3599, 0xFFFF = nicht
     * kalibriert), uint8 flags, int16 trendDdeg (signed, kumulierter
     * Wind-Shift-Trend in 0.1°, Abschnitt 4.3). Little-Endian.
     */
    fun encodeWindStatus(windDirDeg: Double?, calibrated: Boolean, trendDeg: Double?): ByteArray {
        val dirDdeg: Int = if (calibrated && windDirDeg != null) {
            (windDirDeg * 10.0).toInt().let { ((it % 3600) + 3600) % 3600 }
        } else 0xFFFF
        var flags = 0
        if (calibrated) flags = flags or WIND_FLAG_CALIBRATED
        val trendDdeg: Int = ((trendDeg ?: 0.0) * 10.0).toInt().coerceIn(-32768, 32767)

        val buf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(dirDdeg.toShort())
        buf.put(flags.toByte())
        buf.putShort(trendDdeg.toShort())
        return buf.array()
    }

    /**
     * 5-Byte RaceStatusPacket: uint8 raceState (Ordinal von RaceState:
     * 0=MENU, 1=COUNTDOWN, 2=RACE), uint16 countdownSeconds (0xFFFF = kein
     * laufender Countdown), uint8 maneuverFlags (bit0=Manöver empfohlen,
     * bit1=Wende [1] statt Halse [0]), uint8 competitionLeg (Ordinal von
     * CompetitionLeg, 0xFF = kein Competition aktiv). Little-Endian.
     */
    fun encodeRaceStatus(
        raceStateOrdinal: Int,
        countdownSeconds: Int?,
        maneuverNeeded: Boolean,
        isTack: Boolean,
        competitionLegOrdinal: Int?,
    ): ByteArray {
        val cd = (countdownSeconds ?: 0xFFFF).coerceIn(0, 0xFFFF)
        var maneuverFlags = 0
        if (maneuverNeeded) maneuverFlags = maneuverFlags or MANEUVER_FLAG_NEEDED
        if (isTack) maneuverFlags = maneuverFlags or MANEUVER_FLAG_IS_TACK
        val leg = competitionLegOrdinal ?: 0xFF

        val buf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(raceStateOrdinal.toByte())
        buf.putShort(cd.toShort())
        buf.put(maneuverFlags.toByte())
        buf.put(leg.toByte())
        return buf.array()
    }

    /**
     * 7-Byte TimeSyncPacket mit der aktuellen lokalen Handy-Zeit, siehe
     * CHAR_TIME_SYNC_UUID. Wird bei jedem Read frisch erzeugt (kein
     * gespeicherter Zustand nötig).
     */
    fun encodeTimeSync(): ByteArray {
        val cal = java.util.Calendar.getInstance()
        val buf = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(cal.get(java.util.Calendar.YEAR).toShort())
        buf.put((cal.get(java.util.Calendar.MONTH) + 1).toByte()) // Calendar.MONTH ist 0-basiert
        buf.put(cal.get(java.util.Calendar.DAY_OF_MONTH).toByte())
        buf.put(cal.get(java.util.Calendar.HOUR_OF_DAY).toByte())
        buf.put(cal.get(java.util.Calendar.MINUTE).toByte())
        buf.put(cal.get(java.util.Calendar.SECOND).toByte())
        return buf.array()
    }
}
