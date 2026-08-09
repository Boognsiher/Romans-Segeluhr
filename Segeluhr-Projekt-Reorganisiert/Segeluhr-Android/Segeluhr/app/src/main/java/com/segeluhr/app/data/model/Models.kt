package com.segeluhr.app.data.model

/**
 * "Ohne Uhr": Standalone, das Handy vibriert selbst (VibrationPatterns).
 * "Mit Uhr": Das Handy sendet GPS + Haptik-Kommandos per BLE an die Uhr
 * (BleGattServerManager/BleHapticSender) und vibriert selbst NICHT mehr.
 */
enum class OperationMode { STANDALONE, WITH_WATCH }

/**
 * Rolle des Handys, NICHT zu verwechseln mit [OperationMode] (das regelt
 * "Ohne/Mit Uhr" beim Segeln). SAILOR = klassische App-Rolle (Handy =
 * BLE-GATT-Server für die Boots-Uhr, alle bisherigen Tabs). SHORE = neue
 * Rolle für die Person an Land (Handy = BLE-Client zur Land-Uhr, zeigt nur
 * die Boot-Position auf einer Karte), siehe
 * docs/Erweiterung_Landuhr_Kartenansicht.md. Bewusst als komplett eigener
 * Umschalter statt als dritter OperationMode-Wert, weil beide Rollen
 * technisch nichts miteinander zu tun haben (Server- vs. Client-BLE-Rolle).
 */
enum class AppRole { SAILOR, SHORE }

enum class RaceState { MENU, COUNTDOWN, RACE }

enum class TrainMode { OFF, TACK_ONLY, JIBE_ONLY, RACE }

enum class TrainState { OFF, WAITING, COMMANDED, TURNING }

enum class WindCalibState { IDLE, WAIT_TACK1, WAIT_TACK_CHANGE, WAIT_TACK2 }

/** Ein aufgezeichnetes Manöver (Wende oder Halse), Abschnitt 6.3 */
data class ManeuverRecord(
    val id: Long = 0,
    val isTack: Boolean,
    val durationS: Double,
    val speedLossPct: Double,
    val score: Double,
    val timestampMs: Long,
)

/** Ein Punkt im kumulierten Windverlauf-Log, Abschnitt 4.3 */
data class WindLogPoint(val cumulativeDeg: Double, val timestampMs: Long)

/**
 * Ergebnis der Heimweg-Führung (Erweiterung, siehe docs/Erweiterung_Heimweg.md).
 * [recommendedHeading] ist entweder die direkte Peilung (falls anliegend
 * segelbar) oder der bessere der beiden Am-Wind-Kurse (falls Kreuzen nötig).
 * [maneuverNeeded] = true bedeutet: aktueller Bug passt nicht zum
 * empfohlenen Kurs, eine Wende Richtung Heimweg wird empfohlen.
 */
data class HomeGuidance(
    val bearingToHome: Double,
    val distanceM: Double,
    val recommendedHeading: Double,
    val maneuverNeeded: Boolean,
    val vmcKn: Double,
    val etaSeconds: Double?,
)

/**
 * Etappe im Competition-Modus (Erweiterung, siehe
 * docs/Erweiterung_Competition_Modus.md): Luvbake -> ggf. kurzer Halbwind-
 * Schlag zur Entlastungsboje -> Vorwind -> nächste Runde.
 */
enum class CompetitionLeg { UPWIND, REACH_TO_OFFSET, DOWNWIND }

/**
 * Anzeige-Info fürs aktuelle Etappen-Ziel im Competition-Modus — echte
 * Bojen (mit Distanz) oder Windschätzung (ohne Distanz, da kein GPS-Punkt).
 */
data class CompetitionGuidance(
    val leg: CompetitionLeg,
    val label: String,
    val bearing: Double,
    val distanceM: Double?,
    val recommendedHeading: Double,
    val maneuverNeeded: Boolean,
    val isEstimated: Boolean,
    val lapCount: Int,
)
