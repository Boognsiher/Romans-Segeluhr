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

    /**
     * NEU (12.08.2026, Roman-Wunschliste "vor dem nächsten Test", siehe
     * docs/Erweiterung_TWatch_Ultra_NavRedesign.md): 1-Byte NOTIFY-
     * Characteristic, Handy -> Ultra-Watch. Bitmaske, welche der über den
     * Menü-Tab setzbaren Wegpunkte GERADE eine Koordinate hinterlegt haben
     * (siehe [WaypointSetFlag]) — lässt die Uhr die zugehörigen "X setzen"-
     * Buttons grün einfärben, sobald der Punkt tatsächlich persistiert ist
     * (Roman-Wunsch: "Feedback ob es passt", nachdem sich beim letzten Test
     * ein stiller Fehlschlag bei "Home setzen" als kaputtes Write-ohne-
     * Antwort herausstellte, siehe Firmware-Kommentar bei
     * sendControlCommand()). Bewusst EIGENE Characteristic statt eines
     * weiteren Felds in HomeStatusPacket/RaceStatusPacket — betrifft
     * konzeptionell alle Wegpunkt-Typen, nicht nur Heimweg/Renn-Status.
     */
    val CHAR_WAYPOINTS_STATUS_UUID: UUID = UUID.fromString("6f6e000a-b5a3-f393-e0a9-e50e24dcca9e")

    /**
     * Bit-Zuordnung für [encodeWaypointsStatus] — bewusst EIGENE, kompakte
     * Nummerierung (nicht 1:1 [WaypointId]: die geht bis 9 und passt mit
     * Lücken (LAKE_CENTER wird hier nicht gebraucht, ist keine
     * Setzen/Löschen-Aktion auf der Uhr) nicht in 1 Byte). Die 6
     * ursprünglichen Wegpunkte plus PIN/BOAT (13.08.2026, siehe
     * docs/Erweiterung_Startlinie_Bias.md — jetzt auch von der Uhr aus
     * setzbar, gleiches Grün-Feedback-Muster wie die anderen).
     */
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

    /** 1-Byte WaypointsStatusPacket, siehe [CHAR_WAYPOINTS_STATUS_UUID]/[WaypointSetFlag]. */
    fun encodeWaypointsStatus(
        buoy1Set: Boolean, buoy2Set: Boolean, targetSet: Boolean,
        homeSet: Boolean, mark1Set: Boolean, mark2Set: Boolean,
        pinSet: Boolean, boatSet: Boolean,
    ): ByteArray {
        var flags = 0
        if (buoy1Set) flags = flags or WaypointSetFlag.BUOY1
        if (buoy2Set) flags = flags or WaypointSetFlag.BUOY2
        if (targetSet) flags = flags or WaypointSetFlag.TARGET
        if (homeSet) flags = flags or WaypointSetFlag.HOME
        if (mark1Set) flags = flags or WaypointSetFlag.COMPETITION_MARK1
        if (mark2Set) flags = flags or WaypointSetFlag.COMPETITION_MARK2
        if (pinSet) flags = flags or WaypointSetFlag.PIN
        if (boatSet) flags = flags or WaypointSetFlag.BOAT
        return byteArrayOf(flags.toByte())
    }

    private const val HOME_FLAG_ACTIVE: Int = 1 shl 0
    private const val HOME_FLAG_MANEUVER_NEEDED: Int = 1 shl 1
    // NEU (12.08.2026, Roman-Wunschliste "vor dem naechsten Test"): einmaliger
    // Puls (true fuer genau EIN notifyHomeStatus()-Notify), wenn der Heimweg-
    // Modus gerade automatisch bei Ankunft gestoppt wurde (siehe
    // SegeluhrViewModel-Ankunftserkennung). Die Ultra-Watch wertet dies als
    // steigende Flanke aus und schickt automatisch eine "BIN ZURUECK!"
    // Quick-Message per LoRa an die Land-Uhr, siehe QuickMessages.h.
    private const val HOME_FLAG_ARRIVED: Int = 1 shl 2

    private const val WIND_FLAG_CALIBRATED: Int = 1 shl 0
    // NEU (11.08.2026, Erweiterung Nav-Tab-Redesign Ultra-Uhr): true = der
    // aktuelle Wind-Trend begünstigt den aktuell gefahrenen Bug ("Lift",
    // Boot kann höher anluven), false = "Header" (Boot muss abfallen).
    // Konvention (siehe ViewModel-Berechnung): Trend und aktuelles
    // Tack-Vorzeichen (wie in CompetitionEngine/HomeEngine, "cog rechts vom
    // Wind" = +1) haben entgegengesetztes Vorzeichen -> Lift. Reine
    // Modellannahme, noch nicht gegen echtes Segelverhalten verifiziert.
    private const val WIND_FLAG_LIFT: Int = 1 shl 1

    private const val MANEUVER_FLAG_NEEDED: Int = 1 shl 0
    private const val MANEUVER_FLAG_IS_TACK: Int = 1 shl 1
    // Vereinheitlichte Bojen-Rundungserkennung (siehe
    // docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md): "Boje noch
    // nicht erreicht — trotzdem als gerundet werten?" steht offen, die Uhr
    // soll die Rückfrage anzeigen und Geste/Taster als Antwort annehmen.
    private const val MANEUVER_FLAG_ROUNDING_CONFIRM_PENDING: Int = 1 shl 2

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

    // Vereinheitlichte Bojen-Rundungserkennung (10.08.2026, siehe
    // docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md): Antwort der Uhr
    // auf eine per MANEUVER_FLAG_ROUNDING_CONFIRM_PENDING angekündigte
    // Rückfrage ("Boje noch nicht erreicht — trotzdem als gerundet
    // werten?"). Ausgelöst entweder per Geste (Klio/BHI260-Sensor, siehe
    // Firmware onGestureTiltUp()=JA/onGestureShake()=NEIN — bewusst
    // wiederverwendet statt eines neuen Eingabewegs, siehe Klassendoku
    // dort) oder per Taster als dokumentiertes Fallback.
    const val CMD_CONFIRM_BUOY_ROUNDING: Int = 15
    const val CMD_REJECT_BUOY_ROUNDING: Int = 16

    /**
     * NEU (14.08.2026, Galaxy-Watch-Begleit-App, siehe
     * docs/Erweiterung_GalaxyWatch_App.md): Gegenstück zu [CMD_SET_WAYPOINT]
     * für Wegpunkte, die NICHT an der aktuellen Boots-Position liegen,
     * sondern auf einer Karte auf der Uhr angetippt wurden (analog zu
     * WaypointMapPickScreen.kt am Handy). 9-Byte-Payload: uint8 Waypoint-ID
     * (siehe [WaypointId], nur Ziele die auch [WaypointSetFlag] kennt — kein
     * LAKE_CENTER, das läuft weiterhin nur über die aktuelle Position/
     * See-Zeichnen), int32 lat_e7, int32 lon_e7 (Little-Endian, gleiche
     * 1e7-Fixpunkt-Konvention wie [encodeGpsPacket]).
     */
    const val CMD_SET_WAYPOINT_AT_COORDS: Int = 17

    /** Dekodiert den Payload von [CMD_SET_WAYPOINT_AT_COORDS], oder null bei falscher Länge. */
    fun decodeWaypointCoordsPayload(payload: ByteArray): Triple<Int, Double, Double>? {
        if (payload.size < 9) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val id = buf.get().toInt() and 0xFF
        val lat = buf.int / 1e7
        val lon = buf.int / 1e7
        return Triple(id, lat, lon)
    }

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
    // Vereinheitlichte Bojen-Rundungserkennung (siehe
    // docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md) — reine
    // Ausgabe-Benachrichtigung ("Rückfrage steht an"), NICHT die
    // Bestätigung selbst (die läuft per Geste/Taster, siehe
    // CMD_CONFIRM_BUOY_ROUNDING/CMD_REJECT_BUOY_ROUNDING).
    const val HAPTIC_ROUNDING_CONFIRM_NEEDED: Int = 9

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
     *
     * Seit 11.08.2026 9 Byte: zusätzlich int16 vmcCkn (1/100 Knoten,
     * VORZEICHENBEHAFTET, 0x7FFF = keine VMC verfügbar — siehe
     * [com.segeluhr.app.core.HomeProgressTracker]) fürs Nav-Tab-Redesign
     * (docs/Erweiterung_TWatch_Ultra_NavRedesign.md). Watch-Firmware muss
     * wieder mit aktualisiert werden.
     *
     * Seit 12.08.2026 zusätzlich Flag-Bit [HOME_FLAG_ARRIVED] im bestehenden
     * flags-Byte (Grösse unverändert 9 Byte) — siehe dortige Doku.
     */
    fun encodeHomeStatus(active: Boolean, maneuverNeeded: Boolean, etaMinutes: Int?, distanceTraveledM: Int, vmcKn: Double?, arrived: Boolean = false): ByteArray {
        var flags = 0
        if (active) flags = flags or HOME_FLAG_ACTIVE
        if (maneuverNeeded) flags = flags or HOME_FLAG_MANEUVER_NEEDED
        if (arrived) flags = flags or HOME_FLAG_ARRIVED
        val eta = (etaMinutes ?: 0xFFFF).coerceIn(0, 0xFFFF)
        val vmcCkn = vmcKn?.let { (it * 100.0).toInt().coerceIn(-32767, 32767) } ?: 0x7FFF
        val buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(flags.toByte())
        buf.putShort(eta.toShort())
        buf.putInt(distanceTraveledM.coerceAtLeast(0))
        buf.putShort(vmcCkn.toShort())
        return buf.array()
    }

    /**
     * 5-Byte WindStatusPacket: uint16 windDirDdeg (0-3599, 0xFFFF = nicht
     * kalibriert), uint8 flags (bit0=WIND_FLAG_CALIBRATED, seit 11.08.2026
     * bit1=WIND_FLAG_LIFT, siehe dortige Doku), int16 trendDdeg (signed,
     * kumulierter Wind-Shift-Trend in 0.1°, Abschnitt 4.3). Little-Endian.
     * Grösse unverändert (Lift/Header passt als zusätzliches Flag-Bit rein,
     * kein neues Feld nötig).
     */
    fun encodeWindStatus(windDirDeg: Double?, calibrated: Boolean, trendDeg: Double?, isLift: Boolean? = null): ByteArray {
        val dirDdeg: Int = if (calibrated && windDirDeg != null) {
            (windDirDeg * 10.0).toInt().let { ((it % 3600) + 3600) % 3600 }
        } else 0xFFFF
        var flags = 0
        if (calibrated) flags = flags or WIND_FLAG_CALIBRATED
        if (isLift == true) flags = flags or WIND_FLAG_LIFT
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
     * bit1=Wende [1] statt Halse [0], bit2=Bojen-Rundungs-Rückfrage offen —
     * siehe docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md), uint8
     * competitionLeg (Ordinal von CompetitionLeg, 0xFF = kein Competition
     * aktiv). Little-Endian.
     *
     * Seit 11.08.2026 7 Byte: zusätzlich int16 vmcCkn (1/100 Knoten,
     * VORZEICHENBEHAFTET, 0x7FFF = keine VMC verfügbar — siehe
     * [com.segeluhr.app.data.model.CompetitionGuidance.vmcKn]) fürs
     * Nav-Tab-Redesign. Watch-Firmware muss wieder mit aktualisiert werden.
     *
     * Seit 13.08.2026 9 Byte: zusätzlich int16 lineBiasDdeg (1/10 Grad,
     * VORZEICHENBEHAFTET, 0x7FFF = keine Startlinie gesetzt — siehe
     * SegeluhrViewModel.renderTelemetry() für die Berechnung/Vorzeichen-
     * Herleitung). "Boot"/"Pin" wird bewusst NICHT mitgeschickt — die Uhr
     * kann das Vorzeichen selbst auswerten (>0 = Pin bevorzugt, <0 = Boot
     * bevorzugt, 0 = neutral), analog zur App-Logik. Watch-Firmware muss
     * wieder mit aktualisiert werden.
     */
    fun encodeRaceStatus(
        raceStateOrdinal: Int,
        countdownSeconds: Int?,
        maneuverNeeded: Boolean,
        isTack: Boolean,
        competitionLegOrdinal: Int?,
        roundingConfirmPending: Boolean = false,
        vmcKn: Double? = null,
        lineBiasDeg: Double? = null,
    ): ByteArray {
        val cd = (countdownSeconds ?: 0xFFFF).coerceIn(0, 0xFFFF)
        var maneuverFlags = 0
        if (maneuverNeeded) maneuverFlags = maneuverFlags or MANEUVER_FLAG_NEEDED
        if (isTack) maneuverFlags = maneuverFlags or MANEUVER_FLAG_IS_TACK
        if (roundingConfirmPending) maneuverFlags = maneuverFlags or MANEUVER_FLAG_ROUNDING_CONFIRM_PENDING
        val leg = competitionLegOrdinal ?: 0xFF
        val vmcCkn = vmcKn?.let { (it * 100.0).toInt().coerceIn(-32767, 32767) } ?: 0x7FFF
        val lineBiasDdeg = lineBiasDeg?.let { (it * 10.0).toInt().coerceIn(-32767, 32767) } ?: 0x7FFF

        val buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(raceStateOrdinal.toByte())
        buf.putShort(cd.toShort())
        buf.put(maneuverFlags.toByte())
        buf.put(leg.toByte())
        buf.putShort(vmcCkn.toShort())
        buf.putShort(lineBiasDdeg.toShort())
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
