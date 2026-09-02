package com.segeluhr.app.data.model

import com.segeluhr.app.core.Constants
import com.segeluhr.app.core.GeoPoint

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

/**
 * Art einer gespeicherten Session (Erweiterung, 17.08.2026, siehe
 * docs/Erweiterung_Tages_Auswertung.md). DAY = der ganze Tag seit
 * App-Start (ein Eintrag, wächst über Stopp/Start-Zyklen hinweg, siehe
 * SessionSummaryEngine). RACE = eine einzelne Wettfahrt, ab Countdown-Start
 * bis "Wettfahrt beenden" ODER bis zum nächsten Countdown-Start (dann
 * still im Hintergrund abgeschlossen, siehe SegeluhrViewModel.startCountdown/
 * stopCompetition) - Roman-Wunsch: Wettfahrten sollen einzeln auswertbar
 * bleiben, nicht im Tages-Eintrag untergehen.
 */
enum class SessionKind { DAY, RACE }

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
 * Ein Boots-Profil für die Boots-Kalibrierung (Erweiterung, siehe
 * docs/Erweiterung_Boots_Kalibrierung.md) — eigener gelernter Wendewinkel
 * pro Boot, damit dieselbe App-Installation für mehrere Boote genutzt
 * werden kann (z.B. eigenes Boot + Charter-/Vereinsboot). [sampleCount] = 0
 * bedeutet "noch nie kalibriert" (auch beim vorbefüllten Grundprofil, dessen
 * Winkel nur eine Schätzung aus Referenzdaten ist, keine echte Messung).
 *
 * [downwindAngleDeg] (10.08.2026 ergänzt): Pendant für die Vorwind-Seite,
 * aber OHNE eigenes [sampleCount] — es gibt keinen dedizierten
 * Halsen-Kalibrierungsmodus, der Wert kommt ausschliesslich aus dem
 * bestehenden Smart-Modus (kontinuierliches Nachlernen während des
 * normalen Segelns). Default 180° = "noch nichts gelernt", entspricht dem
 * bisherigen festen Wind+180°-Verhalten.
 */
data class BoatProfile(
    val id: String,
    val name: String,
    val closehauledAngleDeg: Double,
    val sampleCount: Int,
    val downwindAngleDeg: Double = Constants.DEFAULT_DOWNWIND_ANGLE_DEG,
)

/**
 * Ergebnis der Heimweg-Führung (Erweiterung, siehe docs/Erweiterung_Heimweg.md).
 * [recommendedHeading] ist entweder die direkte Peilung (falls anliegend
 * segelbar) oder der bessere der beiden Am-Wind-Kurse (falls Kreuzen nötig).
 * [maneuverNeeded] = true bedeutet: aktueller Bug passt nicht zum
 * empfohlenen Kurs, eine Wende Richtung Heimweg wird empfohlen.
 * [vmcKn] ist seit 10.08.2026 KEINE Momentan-Berechnung aus dem aktuellen
 * Kurs mehr, sondern die über ein Zeitfenster gemessene tatsächliche
 * Annäherung (siehe [com.segeluhr.app.core.HomeProgressTracker]) — bewusst
 * träge, damit kurze Kurs-Zacken (z.B. während einer Wende) nicht sofort
 * auf ETA/VMC durchschlagen.
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
 * Etappe im Competition-Modus. **02.09.2026 überarbeitet** (Roman-Korrektur
 * am Kurs-Modell, siehe docs/Erweiterung_Competition_Kursmodell.md): Luvboje
 * -> Lee-Boje/Gate -> nächste Runde (immer [Constants.COMPETITION_LAP_COUNT]
 * Runden) -> Ziel. Der Halbwind-Schlag nach der Luvboje ist nur Teil der
 * Rundungsbewegung (immer gegen den Uhrzeigersinn), KEINE eigene Etappe mehr
 * — ersetzt das alte, am ursprünglichen (falschen) Kurs-Modell orientierte
 * `REACH_TO_OFFSET`. **Ordinal ändert sich** (0=UPWIND, 1=DOWNWIND,
 * 2=FINISH) — betrifft `BleProtocol.encodeRaceStatus()`/T-Watch-Ultra-
 * Firmware, siehe dortige Doku.
 */
enum class CompetitionLeg { UPWIND, DOWNWIND, FINISH }

/**
 * Welche der drei Lee-Bereich-Varianten Romans Verein vor dem Start
 * festlegt (Erweiterung 02.09.2026, siehe
 * docs/Erweiterung_Competition_Kursmodell.md) — bestimmt sowohl die
 * Rundung am Ende jeder Runde als auch die Ziel-Geometrie nach der letzten
 * Runde:
 * - [LEE_IS_PIN]: die Lee-Boje ist dieselbe Boje wie der Startlinien-Pin ->
 *   Ziel danach halbwind hinter dem Startboot, mit einer separaten
 *   Zielboje in Lee.
 * - [SEPARATE_BUOY]: eigenständige Lee-Boje -> Ziel danach amwind durch die
 *   Startlinie (Pin<->Boot).
 * - [GATE]: zwei Gate-Bojen, die näher liegende wird pro Runde automatisch
 *   gewählt und von innen nach aussen gerundet -> Ziel danach amwind durch
 *   die Startlinie, wie [SEPARATE_BUOY].
 */
enum class LeewardMode { LEE_IS_PIN, SEPARATE_BUOY, GATE }

/**
 * Bündelt alle Kurs-Konfigurationspunkte für den Lee-/Ziel-Bereich
 * (Erweiterung 02.09.2026) — vor dem Start festgelegt, ändert sich während
 * der Wettfahrt nicht. Eigene Datenklasse statt einzelner Parameter, weil
 * `CompetitionEngine.tick()`/`windShiftReferencePoint()` sie beide brauchen
 * und die Parameterliste sonst zu lang würde. [pin]/[boat] sind dieselben
 * Startlinien-Wegpunkte wie anderswo — hier zusätzlich gebraucht, weil sie
 * je nach [leewardMode] auch Lee-Marke ([LeewardMode.LEE_IS_PIN]) bzw.
 * einer der beiden Ziellinien-Enden sind.
 */
data class CompetitionCourseConfig(
    val leewardMode: LeewardMode,
    val pin: GeoPoint?,
    val boat: GeoPoint?,
    val leeBuoy: GeoPoint?,
    val gateA: GeoPoint?,
    val gateB: GeoPoint?,
    val finishBuoy: GeoPoint?,
)

/**
 * Anzeige-Info fürs aktuelle Etappen-Ziel im Competition-Modus — echte
 * Bojen (mit Distanz) oder Windschätzung (ohne Distanz, da kein GPS-Punkt).
 * [vmcKn] wie bei [HomeGuidance]: über ein Zeitfenster gemessene tatsächliche
 * Annäherung an das aktuelle Etappenziel (siehe
 * [com.segeluhr.app.core.HomeProgressTracker]), null ohne auflösbares Ziel
 * (z.B. DOWNWIND ohne gesetzte Lee-Boje/Gate, siehe
 * [CompetitionCourseConfig]) oder solange noch nicht genug
 * Zeitfenster-Historie vorliegt.
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
    val vmcKn: Double? = null,
)

/**
 * Welche Engine gerade eine Bestätigung braucht (siehe
 * docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md) — Training-Race und
 * Competition können laut CompetitionEngine-Klassendoku parallel aktiv
 * sein, deshalb muss der ViewModel wissen, an welche der beiden
 * `confirmPendingRounding()`/`rejectPendingRounding()` weitergereicht wird.
 */
enum class BuoyConfirmSource { TRAINING, COMPETITION }

/**
 * Spiegel von `TrainingEngine.pendingConfirmation`/
 * `CompetitionEngine.pendingConfirmation` im UiState — siehe
 * docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md. [waypointKey] ist
 * der SettingsRepository-Wegpunkt-Schlüssel ("buoy1"/"buoy2"/
 * "competitionMark1"), der bei Bestätigung auf [candidatePosition]
 * korrigiert wird. [startedAtMs] treibt den Auto-Bestätigen-Timeout
 * (`Constants.ROUNDING_CONFIRM_TIMEOUT_MS`).
 */
data class PendingBuoyConfirmation(
    val source: BuoyConfirmSource,
    val waypointKey: String,
    val candidatePosition: GeoPoint,
    val startedAtMs: Long,
)
