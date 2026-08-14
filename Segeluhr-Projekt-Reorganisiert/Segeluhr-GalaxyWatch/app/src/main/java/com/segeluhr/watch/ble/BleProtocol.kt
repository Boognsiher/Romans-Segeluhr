package com.segeluhr.watch.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Portierung von BleProtocol.kt aus der Handy-App (dort: Peripheral/Encode-
 * Seite) für die Central/Decode-Seite auf der Uhr — bewusst 1:1 dieselben
 * UUIDs/Byte-Layouts wie die T-Watch-Ultra-Firmware
 * (Segeluhr_TWatch_Ultra.ino, Abschnitt "kleine Helfer für Little-Endian-
 * Decoding" + die einzelnen on*Notify()-Funktionen), NUR in Kotlin statt
 * C++/Arduino. Rollenverteilung unverändert: Handy = GATT-Server/Peripheral,
 * diese Uhr = GATT-Client/Central — genau wie die T-Watch Ultra, nur ohne
 * deren LoRa-Weiterleitung an eine Land-Uhr (siehe
 * docs/Erweiterung_GalaxyWatch_App.md).
 */
object BleProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("6f6e0001-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_GPS_UUID: UUID = UUID.fromString("6f6e0002-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_BATTERY_UUID: UUID = UUID.fromString("6f6e0003-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_CONTROL_UUID: UUID = UUID.fromString("6f6e0004-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_HAPTIC_UUID: UUID = UUID.fromString("6f6e0005-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_HOME_STATUS_UUID: UUID = UUID.fromString("6f6e0006-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_WIND_UUID: UUID = UUID.fromString("6f6e0007-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_RACE_STATUS_UUID: UUID = UUID.fromString("6f6e0008-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_TIME_SYNC_UUID: UUID = UUID.fromString("6f6e0009-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_WAYPOINTS_STATUS_UUID: UUID = UUID.fromString("6f6e000a-b5a3-f393-e0a9-e50e24dcca9e")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val GPS_FLAG_VALID_FIX = 1 shl 0
    private const val GPS_FLAG_BATTERY_LOW = 1 shl 1
    private const val HOME_FLAG_ACTIVE = 1 shl 0
    private const val HOME_FLAG_MANEUVER_NEEDED = 1 shl 1
    private const val HOME_FLAG_ARRIVED = 1 shl 2
    private const val WIND_FLAG_CALIBRATED = 1 shl 0
    private const val WIND_FLAG_LIFT = 1 shl 1
    private const val MANEUVER_FLAG_NEEDED = 1 shl 0
    private const val MANEUVER_FLAG_IS_TACK = 1 shl 1
    private const val MANEUVER_FLAG_ROUNDING_CONFIRM_PENDING = 1 shl 2

    // ---- CMD_*: Steuerbefehle Uhr -> Handy über CHAR_CONTROL_UUID (WRITE) ----
    const val CMD_COUNTDOWN_START: Int = 1
    const val CMD_COUNTDOWN_RESET: Int = 2
    const val CMD_COUNTDOWN_SYNC_NEXT_MINUTE: Int = 3
    const val CMD_WIND_CALIBRATE_START: Int = 4
    const val CMD_WIND_CALIBRATE_ABORT: Int = 5
    const val CMD_TRAIN_MODE_OFF: Int = 6
    const val CMD_TRAIN_MODE_TACK_ONLY: Int = 7
    const val CMD_TRAIN_MODE_JIBE_ONLY: Int = 8
    const val CMD_TRAIN_MODE_RACE: Int = 9
    const val CMD_SET_WAYPOINT: Int = 10 // + 1 Byte Waypoint-ID
    const val CMD_CLEAR_WAYPOINT: Int = 11 // + 1 Byte Waypoint-ID
    const val CMD_HOME_MODE_TOGGLE: Int = 12
    const val CMD_COMPETITION_END: Int = 13
    const val CMD_CLEAR_LOG: Int = 14
    const val CMD_CONFIRM_BUOY_ROUNDING: Int = 15
    const val CMD_REJECT_BUOY_ROUNDING: Int = 16
    // CMD 17 (CMD_SET_WAYPOINT_AT_COORDS) war für einen Wegpunkt-Kartenpicker
    // auf der Uhr reserviert, 14.08. Abend wieder gestrichen (Display zu
    // klein) — siehe docs/Erweiterung_GalaxyWatch_App.md. Die Handy-Seite
    // kennt den Befehl weiterhin (schadet nicht, wird von hier nie gesendet).

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

    /** Bit-Zuordnung für CHAR_WAYPOINTS_STATUS_UUID, siehe BleProtocol.kt am Handy. */
    object WaypointSetFlag {
        const val BUOY1: Int = 1 shl 0
        const val BUOY2: Int = 1 shl 1
        const val TARGET: Int = 1 shl 2
        const val HOME: Int = 1 shl 3
        const val COMPETITION_MARK1: Int = 1 shl 4
        const val COMPETITION_MARK2: Int = 1 shl 5
        const val PIN: Int = 1 shl 6
        const val BOAT: Int = 1 shl 7
    }

    // Haptik-Muster-Codes, 1:1 zu BleProtocol.kt/VibrationPatterns.kt am Handy.
    const val HAPTIC_STEP1: Int = 1
    const val HAPTIC_DONE2: Int = 2
    const val HAPTIC_HEADER3: Int = 3
    const val HAPTIC_ERROR4: Int = 4
    const val HAPTIC_LAKE_WARN5: Int = 5
    const val HAPTIC_ROUNDING6: Int = 6
    const val HAPTIC_MANEUVER_CMD: Int = 7
    const val HAPTIC_START_SIGNAL: Int = 8
    const val HAPTIC_ROUNDING_CONFIRM_NEEDED: Int = 9

    // ---- Encode: Steuerbefehle (Uhr -> Handy) ----
    fun encodeControlCommand(cmd: Int, payloadByte: Int? = null): ByteArray =
        if (payloadByte != null) byteArrayOf(cmd.toByte(), payloadByte.toByte()) else byteArrayOf(cmd.toByte())

    // ---- Decode: Notify-Pakete (Handy -> Uhr) ----

    data class GpsData(
        val lat: Double, val lon: Double, val validFix: Boolean,
        val cogDeg: Double?, val sogKn: Double, val fixAgeMs: Int, val accuracyM: Int,
    )

    /** 16-Byte GpsPacket, siehe encodeGpsPacket() am Handy. */
    fun decodeGpsPacket(data: ByteArray): GpsData? {
        if (data.size < 16) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val latE7 = buf.int
        val lonE7 = buf.int
        val cogDdeg = buf.short.toInt() and 0xFFFF
        val sogCkn = buf.short.toInt() and 0xFFFF
        val fixAgeMs = buf.short.toInt() and 0xFFFF
        val accuracyM = buf.get().toInt() and 0xFF
        val flags = buf.get().toInt() and 0xFF
        val validFix = (flags and GPS_FLAG_VALID_FIX) != 0
        return GpsData(
            lat = latE7 / 1e7, lon = lonE7 / 1e7, validFix = validFix,
            cogDeg = if (validFix) cogDdeg / 10.0 else null,
            sogKn = sogCkn / 100.0, fixAgeMs = fixAgeMs, accuracyM = accuracyM,
        )
    }

    fun decodeBatteryLevel(data: ByteArray): Int? = data.getOrNull(0)?.let { it.toInt() and 0xFF }

    data class HomeData(
        val active: Boolean, val maneuverNeeded: Boolean, val arrived: Boolean,
        val etaMinutes: Int?, val distanceTraveledM: Int, val vmcKn: Double?,
    )

    /** 9-Byte HomeStatusPacket, siehe encodeHomeStatus() am Handy. */
    fun decodeHomeStatus(data: ByteArray): HomeData? {
        if (data.size < 9) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val flags = buf.get().toInt() and 0xFF
        val eta = buf.short.toInt() and 0xFFFF
        val distanceTraveledM = buf.int
        val vmcCkn = buf.short.toInt()
        return HomeData(
            active = (flags and HOME_FLAG_ACTIVE) != 0,
            maneuverNeeded = (flags and HOME_FLAG_MANEUVER_NEEDED) != 0,
            arrived = (flags and HOME_FLAG_ARRIVED) != 0,
            etaMinutes = if (eta == 0xFFFF) null else eta,
            distanceTraveledM = distanceTraveledM,
            vmcKn = if (vmcCkn != 0x7FFF) vmcCkn / 100.0 else null,
        )
    }

    /** Tri-State: null solange nicht kalibriert (analog windData.liftState==-1 in der Firmware). */
    data class WindData(
        val calibrated: Boolean, val dirDeg: Double?, val trendDeg: Double, val isLift: Boolean?,
    )

    /** 5-Byte WindStatusPacket, siehe encodeWindStatus() am Handy. */
    fun decodeWindStatus(data: ByteArray): WindData? {
        if (data.size < 5) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val dirDdeg = buf.short.toInt() and 0xFFFF
        val flags = buf.get().toInt() and 0xFF
        val trendDdeg = buf.short.toInt()
        val calibrated = (flags and WIND_FLAG_CALIBRATED) != 0
        return WindData(
            calibrated = calibrated,
            dirDeg = if (calibrated && dirDdeg != 0xFFFF) dirDdeg / 10.0 else null,
            trendDeg = trendDdeg / 10.0,
            isLift = if (calibrated) (flags and WIND_FLAG_LIFT) != 0 else null,
        )
    }

    data class RaceData(
        val raceStateOrdinal: Int, val countdownSeconds: Int?, val maneuverNeeded: Boolean,
        val isTack: Boolean, val competitionLeg: Int?, val roundingConfirmPending: Boolean,
        val vmcKn: Double?, val lineBiasDeg: Double?,
    )

    /** 9-Byte RaceStatusPacket, siehe encodeRaceStatus() am Handy. */
    fun decodeRaceStatus(data: ByteArray): RaceData? {
        if (data.size < 9) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val raceState = buf.get().toInt() and 0xFF
        val cd = buf.short.toInt() and 0xFFFF
        val maneuverFlags = buf.get().toInt() and 0xFF
        val leg = buf.get().toInt() and 0xFF
        val vmcCkn = buf.short.toInt()
        val lineBiasDdeg = buf.short.toInt()
        return RaceData(
            raceStateOrdinal = raceState,
            countdownSeconds = if (cd == 0xFFFF) null else cd,
            maneuverNeeded = (maneuverFlags and MANEUVER_FLAG_NEEDED) != 0,
            isTack = (maneuverFlags and MANEUVER_FLAG_IS_TACK) != 0,
            competitionLeg = if (leg == 0xFF) null else leg,
            roundingConfirmPending = (maneuverFlags and MANEUVER_FLAG_ROUNDING_CONFIRM_PENDING) != 0,
            vmcKn = if (vmcCkn != 0x7FFF) vmcCkn / 100.0 else null,
            lineBiasDeg = if (lineBiasDdeg != 0x7FFF) lineBiasDdeg / 10.0 else null,
        )
    }

    data class WaypointsStatus(
        val buoy1Set: Boolean, val buoy2Set: Boolean, val targetSet: Boolean, val homeSet: Boolean,
        val mark1Set: Boolean, val mark2Set: Boolean, val pinSet: Boolean, val boatSet: Boolean,
    )

    fun decodeWaypointsStatus(data: ByteArray): WaypointsStatus? {
        val flags = data.getOrNull(0)?.toInt()?.and(0xFF) ?: return null
        return WaypointsStatus(
            buoy1Set = (flags and WaypointSetFlag.BUOY1) != 0,
            buoy2Set = (flags and WaypointSetFlag.BUOY2) != 0,
            targetSet = (flags and WaypointSetFlag.TARGET) != 0,
            homeSet = (flags and WaypointSetFlag.HOME) != 0,
            mark1Set = (flags and WaypointSetFlag.COMPETITION_MARK1) != 0,
            mark2Set = (flags and WaypointSetFlag.COMPETITION_MARK2) != 0,
            pinSet = (flags and WaypointSetFlag.PIN) != 0,
            boatSet = (flags and WaypointSetFlag.BOAT) != 0,
        )
    }

    fun decodeHapticCommand(data: ByteArray): Int? = data.getOrNull(0)?.let { it.toInt() and 0xFF }
}
