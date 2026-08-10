package com.segeluhr.app.core

/**
 * Fortlaufende Distanz-Aufsummierung über die GPS-Fixes der laufenden
 * Session (Summe der Distanzen zwischen aufeinanderfolgenden Fixes) —
 * war bisher offener Punkt, siehe PROJEKT_STATUS.md ("distanceTraveledM")
 * und docs/BLE_Protokoll_Ergaenzung_Heimweg_LoRa.md.
 *
 * NUR die App-Seite (Aufsummierung + Anzeige in der App). Die Weitergabe
 * per BLE/LoRa an die Ultra-/Land-Uhr ist bewusst NICHT Teil dieser
 * Änderung — das aktuelle `LoRaStatusPacket` (Segeluhr-Firmware/shared/
 * LoRaPacket.h) hat noch kein `distanceTraveledM`-Feld, das müsste
 * zusammen mit beiden Firmwares (Ultra + S3 Land) erweitert und neu
 * geflasht werden, siehe Kommentar dort.
 *
 * Wie [CourseTracker]: zählt nur Fixes oberhalb [Constants.MIN_SPEED_KN],
 * damit GPS-Jitter im Stand (Anlegen, Pause, Startlinie warten) nicht
 * fälschlich als zurückgelegte Strecke gezählt wird. Bei Stillstand/
 * ungültigem Fix wird nur der Bezugspunkt verworfen — die bisherige Summe
 * bleibt erhalten, sonst würde jede Pause die Session-Distanz auf 0
 * zurücksetzen.
 */
class DistanceTracker {

    private var lastFix: GeoPoint? = null

    var totalM: Double = 0.0
        private set

    fun reset() {
        lastFix = null
        totalM = 0.0
    }

    /** Alle 1s aufrufen (analog zu CourseTracker.sample). */
    fun sample(
        valid: Boolean,
        lat: Double?,
        lon: Double?,
        sogKn: Double?,
        minSpeed: Double = Constants.MIN_SPEED_KN,
    ) {
        if (!valid || lat == null || lon == null || sogKn == null || sogKn < minSpeed) {
            lastFix = null
            return
        }
        lastFix?.let { prev ->
            totalM += GeoUtils.distanceMeters(prev.lat, prev.lon, lat, lon)
        }
        lastFix = GeoPoint(lat, lon)
    }
}
