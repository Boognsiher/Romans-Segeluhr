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
    /**
     * Lebenszeichen eines Trackers OHNE GPS-Fix (17.11.2026): Die Tracker-Firmware sendet ohne Fix alle ~10 s ein Paket mit
     * lat = lon = Int.MIN_VALUE (-2147483648) und GPS-Zeit 0 (siehe protocol.h im Roemis-Regatta-Tracker-Repo, PKT_NOFIX_COORD).
     * Akku, RSSI und SNR sind gueltig, lat/lon/timestampMs/speedMs NICHT (hier 0.0/0/null). Vorher verwarf der Codec solche Pakete
     * wegen "Lat ausserhalb -90..90" als ungueltig - Tracker ohne Fix blieben deshalb in der Uebersicht und auf dem Server unsichtbar.
     */
    val isHeartbeat: Boolean = false,
    /** Nur beim Lebenszeichen: 0 = GPS-Modul stumm (keine Daten), 1 = Daten aber keine Satelliten, 2 = Satelliten sichtbar (Suche). */
    val gpsState: Int? = null,
    /** Nur beim Lebenszeichen: Anzahl sichtbarer Satelliten. */
    val sats: Int? = null,
) {
    /**
     * Hat der Tracker beim Senden einen gueltigen GPS-Fix? (12.11.2026) Das
     * 24-Byte-Paket hat KEIN explizites Fix-Flag - ein Tracker ohne Fix sendet
     * trotzdem weiter Pakete (dann zuvor "aktiv"=gruen angezeigt, obwohl keine
     * brauchbare Position drin war). Abgeleitet wird der Fix deshalb aus:
     * - Position nicht (0,0) ("Null Island", typischer Leerwert ohne Fix)
     * - HDOP bekannt (Wire-Wert 255 = unbekannt, siehe [GatewayPacketCodec.decode])
     * ANNAHME, gegen protocol.h im Tracker-Repo zu verifizieren: ein Tracker
     * ohne Fix setzt (0,0) und/oder HDOP 255. Falls er stattdessen die letzte
     * bekannte Position weitersendet, bleibt das hier unerkannt.
     * 17.11.2026 geprueft: Die Firmware sendet ohne Fix KEIN (0,0), sondern das explizite Lebenszeichen ([isHeartbeat]);
     * (0,0)/HDOP bleiben nur als zusaetzliche Absicherung drin.
     */
    val hasGpsFix: Boolean
        get() = !isHeartbeat && !(kotlin.math.abs(lat) < 1e-6 && kotlin.math.abs(lon) < 1e-6) && hdop != null
}

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
        val latRaw = buf.int
        val lonRaw = buf.int
        val speedCms = buf.short.toInt() and 0xFFFF
        val headingDeg10 = buf.short.toInt() and 0xFFFF
        val battPct = buf.get().toInt() and 0xFF
        val hdop10 = buf.get().toInt() and 0xFF
        val rssi = buf.short.toInt()
        val snr4 = buf.get().toInt()

        // Lebenszeichen ohne Fix (siehe GatewayPacket.isHeartbeat): lat = lon = Int.MIN_VALUE. In "Geschwindigkeit" steht dann der
        // GPS-Zustand: Hochbyte = Zustand (0 stumm, 1 keine Satelliten, 2 Suche), Tiefbyte = Satelliten.
        if (latRaw == Int.MIN_VALUE && lonRaw == Int.MIN_VALUE) {
            return GatewayPacket(
                loraId = id,
                timestampMs = 0L,
                lat = 0.0,
                lon = 0.0,
                speedMs = null,
                headingDeg = null,
                batteryPct = if (battPct == 255) null else battPct,
                hdop = null,
                rssi = rssi,
                snrDb = snr4 / 4.0,
                isHeartbeat = true,
                gpsState = (speedCms ushr 8) and 0xFF,
                sats = speedCms and 0xFF,
            )
        }
        val lat = latRaw / 1e6
        val lon = lonRaw / 1e6

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
