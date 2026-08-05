/**
 * Segeluhr_TWatch_Ultra.ino
 *
 * Echte "Boots-Uhr"-Firmware für die LilyGO T-Watch Ultra. Übernimmt die
 * komplette bisherige Rolle von Segeluhr_TWatch_S3.ino (BLE Central zum
 * Handy, 6 Segel-Screens + Alltagsmodus, Haptik, Auto-Focus, Zeit-Sync) und
 * erweitert sie um LoRa: sendet alle 30s einen Status an die Land-Uhr
 * (T-Watch S3, siehe Segeluhr_TWatch_S3.ino) und tauscht mit ihr
 * lockere Ja/Nein-"Quick-Messages" aus.
 *
 * Entstanden durch Zusammenführen von:
 *   - Segeluhr_TWatch_S3_ALT_zum_Portieren.ino (die einzige lauffähige Basis)
 *   - Segeluhr_TWatch_Ultra.ino-Skeleton (LoRa-/Quick-Message-Gerüst)
 * Siehe docs/Erweiterung_Land_Boot_LoRa_Kommunikation.md für die volle
 * Spezifikation dieser Erweiterung.
 *
 * WICHTIG (Ehrlichkeit, wie schon in der Vorgänger-Datei): Ich kann hier
 * keinen ESP32-Compiler laufen lassen. Der BLE-/LVGL-/Haptik-Teil ist
 * unverändert aus der bereits funktionierenden S3-Firmware übernommen. Der
 * LoRa-Teil (radio.*) und die BHI260AP-Gestenerkennung (accel.*,
 * quaternion.*) sind gegen die echten LilyGoLib-Beispiel-Sketches geprüft
 * (examples/radio/SX1262/SX126x_{Transmit,Receive} und
 * examples/sensor/BHI260AP_{6DoF,Euler} im LilyGoLib-Repo) - die API-Namen
 * sind also keine Vermutung, aber ein erster Compile-Lauf auf echter
 * Hardware steht noch aus. Gerne Fehler zurückschicken.
 *
 * Benötigte Bibliotheken: "LilyGoLib" (bringt RadioLib + lvgl als
 * Abhängigkeit mit), "NimBLE-Arduino" (h2zero) - siehe
 * docs/Hardware_Arduino_Settings_LilyGO.md für Bibliotheks-/Board-Setup.
 *
 * BOARD-SETTINGS: Board=LilyGo T-Watch-Ultra, Partition Scheme=16M Flash
 * (3MB APP/9.9MB FATFS), Board Revision=Radio-SX1262, Upload Mode=UART0/
 * Hardware CDC, USB Mode=CDC and JTAG (Details siehe Hardware-Doku oben).
 *
 * DOWNLOAD-MODE bei Upload-Problemen (ANDERS als bei der S3, kein
 * Akku-Ausbau nötig): BOOT halten -> RST drücken -> RST loslassen ->
 * BOOT loslassen -> Upload -> RST.
 *
 * LORA-PINS (SX1262, nur zur Referenz - LilyGoLib initialisiert das
 * globale `radio`-Objekt bereits selbst in instance.begin(), inkl. der
 * ALDO3-Power-Rail): RESET=47, BUSY=48, CS=36, IRQ/DIO1=14,
 * SPI SCK=35/MISO=33/MOSI=34.
 */

#include <Arduino.h>
#include <math.h>
#include <LilyGoLib.h>
#include <LV_Helper.h>
#include <NimBLEDevice.h>
#include "../../shared/LoRaPacket.h"
#include "../../shared/QuickMessages.h"
#include "../../shared/Crypto.h"

#ifdef USING_BHI260_SENSOR
#include <bosch/BoschSensorDataHelper.hpp>
#endif

// ============================================================================
// BLE-Protokoll — 1:1 zu BleProtocol.kt (App-Seite)
// ============================================================================

static const char *SERVICE_UUID          = "6f6e0001-b5a3-f393-e0a9-e50e24dcca9e";
static const char *CHAR_GPS_UUID          = "6f6e0002-b5a3-f393-e0a9-e50e24dcca9e";
static const char *CHAR_BATTERY_UUID      = "6f6e0003-b5a3-f393-e0a9-e50e24dcca9e";
static const char *CHAR_CONTROL_UUID      = "6f6e0004-b5a3-f393-e0a9-e50e24dcca9e";
static const char *CHAR_HAPTIC_UUID       = "6f6e0005-b5a3-f393-e0a9-e50e24dcca9e";
static const char *CHAR_HOME_STATUS_UUID  = "6f6e0006-b5a3-f393-e0a9-e50e24dcca9e";
static const char *CHAR_WIND_UUID         = "6f6e0007-b5a3-f393-e0a9-e50e24dcca9e";
static const char *CHAR_RACE_STATUS_UUID  = "6f6e0008-b5a3-f393-e0a9-e50e24dcca9e";
static const char *CHAR_TIME_SYNC_UUID    = "6f6e0009-b5a3-f393-e0a9-e50e24dcca9e";

// Haptik-Codes (Abschnitt 7 der Spezifikation)
enum HapticCode {
    HAPTIC_STEP1 = 1,
    HAPTIC_DONE2 = 2,
    HAPTIC_HEADER3 = 3,
    HAPTIC_ERROR4 = 4,
    HAPTIC_LAKE_WARN5 = 5,
    HAPTIC_ROUNDING6 = 6,
    HAPTIC_MANEUVER_CMD = 7,
    HAPTIC_START_SIGNAL = 8,
};

// CMD_*-Steuerbefehle Uhr -> Handy (siehe BleProtocol.kt)
enum ControlCmd {
    CMD_COUNTDOWN_START = 1,
    CMD_COUNTDOWN_RESET = 2,
    CMD_COUNTDOWN_SYNC_NEXT_MINUTE = 3,
    CMD_WIND_CALIBRATE_START = 4,
    CMD_WIND_CALIBRATE_ABORT = 5,
    CMD_TRAIN_MODE_OFF = 6,
    CMD_TRAIN_MODE_TACK_ONLY = 7,
    CMD_TRAIN_MODE_JIBE_ONLY = 8,
    CMD_TRAIN_MODE_RACE = 9,
    CMD_SET_WAYPOINT = 10,
    CMD_CLEAR_WAYPOINT = 11,
    CMD_HOME_MODE_TOGGLE = 12,
    CMD_COMPETITION_END = 13,
    CMD_CLEAR_LOG = 14,
};

enum WaypointId {
    WP_PIN = 1, WP_BOAT = 2, WP_TARGET = 3, WP_BUOY1 = 4, WP_BUOY2 = 5,
    WP_LAKE_CENTER = 6, WP_HOME = 7, WP_COMPETITION_MARK1 = 8, WP_COMPETITION_MARK2 = 9,
};

#define GPS_FLAG_VALID_FIX   (1 << 0)
#define GPS_FLAG_BATTERY_LOW (1 << 1)
#define HOME_FLAG_ACTIVE     (1 << 0)
#define HOME_FLAG_MANEUVER   (1 << 1)
#define WIND_FLAG_CALIBRATED (1 << 0)
#define MANEUVER_FLAG_NEEDED (1 << 0)
#define MANEUVER_FLAG_IS_TACK (1 << 1)

// ============================================================================
// Dekodierte Live-Daten (werden von den Notify-Callbacks gefüllt)
// ============================================================================

struct GpsData {
    double lat = 0, lon = 0;
    double cogDeg = -1;      // -1 = ungültig
    double sogKn = 0;
    uint16_t fixAgeMs = 0;
    uint8_t accuracyM = 255;
    bool validFix = false;
    bool haveData = false;
} gpsData;

int phoneBatteryPct = -1;   // -1 = noch kein Wert empfangen

struct HomeData {
    bool active = false;
    bool maneuverNeeded = false;
    int etaMinutes = -1;     // -1 = keine ETA
    bool haveData = false;
} homeData;

struct WindData {
    double dirDeg = -1;      // -1 = nicht kalibriert
    bool calibrated = false;
    double trendDeg = 0;
    bool haveData = false;
} windData;

struct RaceData {
    uint8_t raceState = 0;   // 0=MENU 1=COUNTDOWN 2=RACE
    int countdownSeconds = -1; // -1 = kein laufender Countdown
    bool maneuverNeeded = false;
    bool isTack = true;
    int competitionLeg = -1; // -1 = kein Competition aktiv, sonst 0=UPWIND 1=REACH 2=DOWNWIND
    bool haveData = false;
} raceData;

// ============================================================================
// Boot-Zustand für LoRa (vereinfachtes Abbild der Engine-Zustände, siehe
// docs/Erweiterung_Land_Boot_LoRa_Kommunikation.md Abschnitt 2/4)
// ============================================================================

static BoatState currentBoatState = BoatState::IDLE;

/**
 * Bildet den vereinfachten BoatState aus den bereits vorhandenen
 * raceData/homeData-Structs (kommen per BLE vom Handy). Wird bei jedem
 * relevanten Notify neu aufgerufen (aus refreshActiveScreen()), damit
 * currentBoatState beim 30s-LoRa-Senden UND bei der Auto-Regatta-Antwort auf
 * Quick-Messages immer aktuell ist.
 *
 * Priorität (wichtigstes zuerst): Manöver > Competition > Countdown >
 * Training > Heimweg > Idle. PAUSED wird aktuell nicht vergeben - dafür
 * gibt es kein Signal im bestehenden BLE-Protokoll (RaceStatus/HomeStatus
 * kennen keinen Pause-Flag).
 */
static void updateBoatState() {
    if (raceData.haveData && raceData.maneuverNeeded) {
        currentBoatState = BoatState::MANEUVER_ALERT;
    } else if (raceData.haveData && raceData.competitionLeg >= 0) {
        currentBoatState = BoatState::COMPETITION;
    } else if (raceData.haveData && raceData.raceState == 1) {
        currentBoatState = BoatState::COUNTDOWN;
    } else if (raceData.haveData && raceData.raceState == 2) {
        currentBoatState = BoatState::TRAINING;
    } else if (homeData.haveData && homeData.active) {
        currentBoatState = BoatState::HEIMWEG;
    } else {
        currentBoatState = BoatState::IDLE;
    }
}

// ============================================================================
// BLE-Central (NimBLE) — Verbindung zum Handy, unverändert aus der
// bisherigen S3-Firmware übernommen
// ============================================================================

static NimBLEClient       *bleClient = nullptr;
static NimBLERemoteCharacteristic *chGps = nullptr;
static NimBLERemoteCharacteristic *chBattery = nullptr;
static NimBLERemoteCharacteristic *chControl = nullptr;
static NimBLERemoteCharacteristic *chHaptic = nullptr;
static NimBLERemoteCharacteristic *chHomeStatus = nullptr;
static NimBLERemoteCharacteristic *chWind = nullptr;
static NimBLERemoteCharacteristic *chRaceStatus = nullptr;

static volatile bool bleConnected = false;
static volatile bool doConnect = false;
static NimBLEAdvertisedDevice *foundDevice = nullptr;
static uint32_t lastScanAttemptMs = 0;

// ============================================================================
// App-Modus: ALLTAG (Uhrzeit/Wecker/Stoppuhr/Batterie, kein Handy nötig)
// vs. SEGELN (die 6 Sailing-Tabs, siehe unten). Erweiterung, siehe
// docs/Erweiterung_TWatch_S3_Alltagsmodus.md.
// ============================================================================

enum AppMode { MODE_ALLTAG, MODE_SEGELN };
static AppMode appMode = MODE_ALLTAG;
static bool forceSegelnMode = false;   // Menü-Schalter "Segelmodus erzwingen"
static uint32_t disconnectAtMs = 0;    // 0 = aktuell verbunden oder noch nie verbunden
static const uint32_t SEGELN_FALLBACK_GRACE_MS = 30000; // Kurzabbruch tolerieren

void switchToMode(AppMode mode); // Vorwärtsdeklaration (Screens werden weiter unten gebaut)

static void appModeTick() {
    if (appMode == MODE_SEGELN && !bleConnected && !forceSegelnMode) {
        if (disconnectAtMs != 0 && (millis() - disconnectAtMs > SEGELN_FALLBACK_GRACE_MS)) {
            switchToMode(MODE_ALLTAG);
            disconnectAtMs = 0;
        }
    }
}

// ---- kleine Helfer für Little-Endian-Decoding ----
static int16_t rdI16(const uint8_t *p) { return (int16_t)(p[0] | (p[1] << 8)); }
static uint16_t rdU16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static int32_t rdI32(const uint8_t *p) {
    return (int32_t)((uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24));
}

void triggerHaptic(int code);        // Vorwärtsdeklaration
void refreshActiveScreen();          // Vorwärtsdeklaration
static void updateQuickOverlay();    // Vorwärtsdeklaration (LVGL-UI-Sektion)
extern lv_obj_t *tabview;            // Vorwärtsdeklaration (Definition weiter unten, LVGL-UI-Sektion)

static void onGpsNotify(NimBLERemoteCharacteristic *c, uint8_t *data, size_t len, bool isNotify) {
    if (len < 16) return;
    int32_t latE7 = rdI32(data + 0);
    int32_t lonE7 = rdI32(data + 4);
    uint16_t cogDdeg = rdU16(data + 8);
    uint16_t sogCkn  = rdU16(data + 10);
    uint16_t fixAge  = rdU16(data + 12);
    uint8_t accuracy = data[14];
    uint8_t flags    = data[15];

    gpsData.lat = latE7 / 1e7;
    gpsData.lon = lonE7 / 1e7;
    gpsData.validFix = (flags & GPS_FLAG_VALID_FIX) != 0;
    gpsData.cogDeg = gpsData.validFix ? (cogDdeg / 10.0) : -1;
    gpsData.sogKn = sogCkn / 100.0;
    gpsData.fixAgeMs = fixAge;
    gpsData.accuracyM = accuracy;
    gpsData.haveData = true;
    if (flags & GPS_FLAG_BATTERY_LOW) { /* nur informativ, Battery-Char liefert %-Wert */ }
    refreshActiveScreen();
}

static void onBatteryNotify(NimBLERemoteCharacteristic *c, uint8_t *data, size_t len, bool isNotify) {
    if (len < 1) return;
    phoneBatteryPct = data[0];
    refreshActiveScreen();
}

static void onHapticNotify(NimBLERemoteCharacteristic *c, uint8_t *data, size_t len, bool isNotify) {
    if (len < 1) return;
    triggerHaptic(data[0]);
}

static void onHomeStatusNotify(NimBLERemoteCharacteristic *c, uint8_t *data, size_t len, bool isNotify) {
    if (len < 3) return;
    uint8_t flags = data[0];
    uint16_t eta = rdU16(data + 1);
    homeData.active = (flags & HOME_FLAG_ACTIVE) != 0;
    homeData.maneuverNeeded = (flags & HOME_FLAG_MANEUVER) != 0;
    homeData.etaMinutes = (eta == 0xFFFF) ? -1 : (int)eta;
    homeData.haveData = true;
    refreshActiveScreen();
}

static void onWindNotify(NimBLERemoteCharacteristic *c, uint8_t *data, size_t len, bool isNotify) {
    if (len < 5) return;
    uint16_t dirDdeg = rdU16(data + 0);
    uint8_t flags = data[2];
    int16_t trendDdeg = rdI16(data + 3);
    windData.calibrated = (flags & WIND_FLAG_CALIBRATED) != 0;
    windData.dirDeg = (dirDdeg == 0xFFFF) ? -1 : (dirDdeg / 10.0);
    windData.trendDeg = trendDdeg / 10.0;
    windData.haveData = true;
    refreshActiveScreen();
}

static void onRaceStatusNotify(NimBLERemoteCharacteristic *c, uint8_t *data, size_t len, bool isNotify) {
    if (len < 5) return;
    raceData.raceState = data[0];
    uint16_t cd = rdU16(data + 1);
    uint8_t maneuverFlags = data[3];
    uint8_t leg = data[4];
    raceData.countdownSeconds = (cd == 0xFFFF) ? -1 : (int)cd;
    raceData.maneuverNeeded = (maneuverFlags & MANEUVER_FLAG_NEEDED) != 0;
    raceData.isTack = (maneuverFlags & MANEUVER_FLAG_IS_TACK) != 0;
    raceData.competitionLeg = (leg == 0xFF) ? -1 : (int)leg;
    raceData.haveData = true;
    refreshActiveScreen();
}

/**
 * Automatische Bildschirm-Priorisierung (Erweiterung, siehe
 * docs/Erweiterung_TWatch_S3_AutoFocus.md): die Uhr steckt beim Segeln in
 * einem wasserdichten Sack, Touch-Bedienung ist dort nicht möglich. Statt
 * manuell zu wischen, zeigt die Uhr von selbst den gerade wichtigsten
 * Screen. Reihenfolge (wichtigstes zuerst):
 *   1) Manöver empfohlen (zeitkritisch)
 *   2) Countdown läuft
 *   3) Heimweg aktiv
 *   4) Nav (ruhiger Standard)
 * Wind/Menü bleiben nur per manuellem Wisch erreichbar (z.B. an Land vor
 * dem Versiegeln der Uhr).
 */
static void autoFocusTick() {
    if (appMode != MODE_SEGELN || tabview == nullptr) return;

    int desiredTab;
    if (raceData.haveData && raceData.maneuverNeeded) {
        desiredTab = 4; // tabManeuver
    } else if (raceData.haveData && raceData.raceState == 1 /* COUNTDOWN */) {
        desiredTab = 3; // tabCountdown
    } else if (homeData.haveData && homeData.active) {
        desiredTab = 2; // tabHome
    } else {
        desiredTab = 0; // tabNav
    }

    if ((int)lv_tabview_get_tab_act(tabview) != desiredTab) {
        lv_tabview_set_active(tabview, desiredTab, LV_ANIM_ON);
    }
}

/** Sendet einen CMD_*-Steuerbefehl ans Handy (optional +1 Byte Payload, z.B. Waypoint-ID). */
static void sendControlCommand(uint8_t cmd, int payloadByte = -1) {
    if (!bleConnected || chControl == nullptr) return;
    uint8_t buf[2];
    buf[0] = cmd;
    size_t n = 1;
    if (payloadByte >= 0) { buf[1] = (uint8_t)payloadByte; n = 2; }
    chControl->writeValue(buf, n, false);
}

// ---- Verbindungs-Callback ----
class ClientCallbacks : public NimBLEClientCallbacks {
    void onConnect(NimBLEClient *pClient) override {
        Serial.println("[BLE] Verbunden mit Handy");
        disconnectAtMs = 0;
        switchToMode(MODE_SEGELN);
    }
    void onDisconnect(NimBLEClient *pClient, int reason) override {
        Serial.printf("[BLE] Verbindung getrennt (Reason %d)\n", reason);
        bleConnected = false;
        chGps = chBattery = chControl = chHaptic = chHomeStatus = chWind = chRaceStatus = nullptr;
        // Kein sofortiger Rückfall nach Alltag — kurze Dropouts (siehe
        // SEGELN_FALLBACK_GRACE_MS) werden toleriert, appModeTick() im
        // loop() übernimmt den eigentlichen Rückfall nach Ablauf der Frist.
        disconnectAtMs = millis();
        refreshActiveScreen();
    }
};
static ClientCallbacks clientCallbacks;

class ScanCallbacks : public NimBLEScanCallbacks {
    void onResult(const NimBLEAdvertisedDevice *advertisedDevice) override {
        if (advertisedDevice->isAdvertisingService(NimBLEUUID(SERVICE_UUID))) {
            NimBLEDevice::getScan()->stop();
            foundDevice = new NimBLEAdvertisedDevice(*advertisedDevice);
            doConnect = true;
        }
    }
};
static ScanCallbacks scanCallbacks;

static bool subscribe(NimBLERemoteService *svc, const char *uuid, NimBLERemoteCharacteristic **out,
                       void (*cb)(NimBLERemoteCharacteristic *, uint8_t *, size_t, bool)) {
    NimBLERemoteCharacteristic *c = svc->getCharacteristic(uuid);
    if (c == nullptr) return false;
    *out = c;
    if (c->canNotify()) c->subscribe(true, cb);
    return true;
}

static bool connectToServer() {
    bleClient = NimBLEDevice::createClient();
    bleClient->setClientCallbacks(&clientCallbacks, false);
    if (!bleClient->connect(foundDevice)) {
        Serial.println("[BLE] Verbindung fehlgeschlagen");
        NimBLEDevice::deleteClient(bleClient);
        bleClient = nullptr;
        return false;
    }
    NimBLERemoteService *svc = bleClient->getService(SERVICE_UUID);
    if (svc == nullptr) {
        Serial.println("[BLE] Service nicht gefunden");
        bleClient->disconnect();
        return false;
    }
    subscribe(svc, CHAR_GPS_UUID, &chGps, onGpsNotify);
    subscribe(svc, CHAR_BATTERY_UUID, &chBattery, onBatteryNotify);
    subscribe(svc, CHAR_HAPTIC_UUID, &chHaptic, onHapticNotify);
    subscribe(svc, CHAR_HOME_STATUS_UUID, &chHomeStatus, onHomeStatusNotify);
    subscribe(svc, CHAR_WIND_UUID, &chWind, onWindNotify);
    subscribe(svc, CHAR_RACE_STATUS_UUID, &chRaceStatus, onRaceStatusNotify);
    chControl = svc->getCharacteristic(CHAR_CONTROL_UUID);

    // Zeit-Sync: einmalig auslesen und RTC stellen (siehe
    // docs/Erweiterung_TWatch_S3_ZeitSync.md). Kein manuelles Einstellen an
    // der Uhr nötig — die steckt beim Segeln ohnehin im wasserdichten Sack.
    NimBLERemoteCharacteristic *chTime = svc->getCharacteristic(CHAR_TIME_SYNC_UUID);
    if (chTime != nullptr && chTime->canRead()) {
        std::string val = chTime->readValue();
        if (val.size() >= 7) {
            const uint8_t *d = (const uint8_t *)val.data();
            int year  = rdU16(d + 0);
            int month = d[2];
            int day   = d[3];
            int hour  = d[4];
            int min   = d[5];
            int sec   = d[6];
            instance.rtc.setDateTime(year, month, day, hour, min, sec);
            Serial.printf("[Zeit-Sync] RTC gestellt: %04d-%02d-%02d %02d:%02d:%02d\n",
                          year, month, day, hour, min, sec);
        }
    }

    bleConnected = true;
    Serial.println("[BLE] Alle Characteristics abonniert");
    return true;
}

static void bleTick() {
    if (doConnect) {
        doConnect = false;
        connectToServer();
        refreshActiveScreen();
    }
    if (!bleConnected && !NimBLEDevice::getScan()->isScanning()) {
        uint32_t now = millis();
        if (now - lastScanAttemptMs > 3000) {
            lastScanAttemptMs = now;
            NimBLEDevice::getScan()->start(2000, false);
        }
    }
}

// ============================================================================
// Haptik (DRV2605 über LilyGoLib: instance.drv.setWaveform()/run())
// ============================================================================
//
// TI-DRV2605-Effektbibliothek (Auswahl), angenähert an die Timings aus
// VibrationPatterns.kt. Bis zu 8 Waveform-Slots können verkettet werden,
// dazwischen 0 = Ende der Sequenz.

static void playWaveformSeq(std::initializer_list<uint8_t> effects) {
    int slot = 0;
    for (uint8_t e : effects) {
        instance.drv.setWaveform(slot++, e);
        if (slot >= 8) break;
    }
    instance.drv.setWaveform(slot, 0); // Terminator
    instance.drv.run();
}

// Verstärkte Muster (Nutzer-Feedback: Vibrationen fühlten sich zu schwach
// an). Wichtige Erkenntnis beim Nachschauen im SensorLib-Quellcode:
// setGain()/RTP-Amplitudensteuerung wird vom DRV2605-ROM-Effekt-Modus NICHT
// unterstützt (die Bibliothek gibt dafür bewusst `false` zurück) — die
// einzigen Stellschrauben für "stärker" sind: die kräftigsten ROM-Effekte
// (die "100%"-Varianten) UND möglichst lange/volle Ketten über alle 8
// Waveform-Slots, statt kurzer Einzel-Klicks.
void triggerHaptic(int code) {
    switch (code) {
        case HAPTIC_STEP1:        playWaveformSeq({5, 5});                      break; // 2x Strong Buzz
        case HAPTIC_DONE2:        playWaveformSeq({1, 1});                      break; // 2x Strong Click
        case HAPTIC_HEADER3:      playWaveformSeq({1, 1, 1});                   break; // 3x Strong Click
        case HAPTIC_ERROR4:       playWaveformSeq({5, 5, 5, 5});                break; // 4x Strong Buzz
        case HAPTIC_LAKE_WARN5:   playWaveformSeq({5, 5, 5});                   break; // 3x Strong Buzz
        case HAPTIC_ROUNDING6:    playWaveformSeq({1, 1, 1, 1});                break; // 4x Strong Click
        case HAPTIC_MANEUVER_CMD: playWaveformSeq({5, 5, 5, 5, 5});             break; // 5x Strong Buzz
        case HAPTIC_START_SIGNAL: playWaveformSeq({5, 5, 5, 5, 5, 5, 5, 5});    break; // volle 8 Slots, längstmöglicher Buzz
        default: break;
    }
}

// ============================================================================
// LoRa (SX1262) — Status-Broadcast an die Land-Uhr + Quick-Messages
// (Transceiver: die Ultra sendet UND empfängt, siehe QuickMessages.h)
//
// `radio` ist ein von LilyGoLib bereits global bereitgestelltes SX1262-
// Objekt (inkl. korrekter Pin-/ALDO3-Power-Rail-Initialisierung in
// instance.begin()) — geprüft gegen die echten Beispiel-Sketches
// examples/radio/SX1262/SX126x_{Transmit,Receive} im LilyGoLib-Repo. Kein
// eigenes SX1262/Module-Objekt und kein eigener radio.begin() nötig, nur
// Parameter setzen und instance.loop() in loop() aufrufen (dispatcht u.a.
// die radio-Interrupt-Callbacks).
// ============================================================================

static uint8_t loraSequence = 0;
static unsigned long lastLoraSendMillis = 0;

// Nicht-blockierender Empfang: DIO1-Interrupt setzt nur ein Flag, die
// eigentliche Verarbeitung passiert in loraReceiveTick() im loop()
// (Standardmuster aus dem SX126x_Receive-Beispiel).
static volatile bool loraReceivedFlag = false;
ICACHE_RAM_ATTR static void onLoraPacketReceived(void) {
    loraReceivedFlag = true;
}

void setupLoRaTransceiver() {
    // LoRa-Parameter — MÜSSEN exakt mit Segeluhr_TWatch_S3.ino
    // übereinstimmen, sonst hören sich Sender/Empfänger nicht. 868 MHz
    // (EU/CH-ISM-Band), moderate Bandbreite/SF: Reichweite hat laut Doku
    // Priorität vor Datenrate, unsere Pakete sind winzig (20-28 Byte) und
    // selten (alle 30s bzw. sporadische Quick-Messages).
    // TODO(Roman/Claude Code): Duty-Cycle/Kanalwahl vor dem ersten
    // Praxiseinsatz gegen BAKOM-Vorgaben prüfen (bekannter offener Punkt,
    // PROJEKT_STATUS.md).
    radio.setFrequency(868.0);
    radio.setBandwidth(125.0);
    radio.setSpreadingFactor(10);
    radio.setCodingRate(6);
    radio.setSyncWord(0xAB);              // beliebiger privater Sync-Word, muss auf beiden Uhren gleich sein
    radio.setOutputPower(22);             // Maximum, Reichweite vor Sparsamkeit
    radio.setCurrentLimit(140);
    radio.setPreambleLength(15);
    radio.setCRC(true);                   // Integritätsprüfung - AES-CTR erkennt Bitfehler sonst nicht
    radio.setTCXO(3.0);                   // Wert aus LilyGoLib-Beispiel für dieses Board übernommen
    radio.setDio2AsRfSwitch();

    radio.setPacketReceivedAction(onLoraPacketReceived);
    radio.startReceive();
}

void buildAndSendStatusPacket() {
    LoRaStatusPacket pkt;
    pkt.sequence = loraSequence++;
    pkt.state = currentBoatState;
    pkt.countdownRemainingSec = (raceData.haveData && raceData.countdownSeconds >= 0)
                                     ? (int16_t)raceData.countdownSeconds : -1;
    pkt.boatBatteryPercent = (uint8_t)instance.pmu.getBatteryPercent(); // eigener Akku, NICHT der vom Handy
    if (homeData.haveData && homeData.active && homeData.etaMinutes >= 0 && gpsData.sogKn > 0.1) {
        // Kein echtes Distanz-Feld im BLE-Protokoll vorhanden (bekannter
        // offener Punkt, siehe PROJEKT_STATUS.md "distanceTraveledM") -
        // grobe Schätzung aus ETA * aktueller Geschwindigkeit, nur während
        // aktivem Heimweg. 1 kn = 1852 m / 3600 s.
        double distM = homeData.etaMinutes * (gpsData.sogKn * 1852.0 / 60.0);
        pkt.distanceRemainingM = (int16_t)(distM > 32000.0 ? 32000.0 : distM);
    } else {
        pkt.distanceRemainingM = -1;
    }
    pkt.sogCkn = (uint16_t)(gpsData.sogKn * 100.0);
    pkt.windDirDeg = (windData.haveData && windData.calibrated) ? (int16_t)windData.dirDeg : -1;
    pkt.latE7 = (int32_t)(gpsData.lat * 1e7);
    pkt.lonE7 = (int32_t)(gpsData.lon * 1e7);

    uint8_t encBuf[CRYPTO_MAX_BUFFER];
    size_t encLen;
    if (encryptLoRaPacket((uint8_t *)&pkt, sizeof(pkt), encBuf, encLen)) {
        int16_t txState = radio.transmit(encBuf, encLen); // blockierend (~200-500ms alle 30s) - unkritisch für die UI
        radio.startReceive();           // transmit() beendet den Empfangsmodus, wieder aktivieren
        // Direkt nach dem eigenen Senden ein evtl. durch den TX-Vorgang selbst
        // ausgelöstes Empfangs-Flag verwerfen (DIO1 wird von RadioLib sowohl für
        // "Paket empfangen" als auch für "Senden fertig" genutzt - beobachtet
        // beim Test: Gerät empfing sein eigenes gerade gesendetes Paket zurück).
        loraReceivedFlag = false;
        // TODO(Test-Debug): entfernen, sobald LoRa-Verbindung verifiziert ist
        Serial.printf("[LoRa TX] seq=%d state=%d txResult=%d\n", pkt.sequence, (int)pkt.state, txState);
    }
}

static void loraSendTick() {
    unsigned long now = millis();
    if (now - lastLoraSendMillis >= LORA_SEND_INTERVAL_MS) {
        lastLoraSendMillis = now;
        buildAndSendStatusPacket();
    }
}

static void sendEncrypted(const uint8_t *data, size_t len) {
    uint8_t encBuf[CRYPTO_MAX_BUFFER];
    size_t encLen;
    if (encryptLoRaPacket(data, len, encBuf, encLen)) {
        radio.transmit(encBuf, encLen);
        radio.startReceive();
        loraReceivedFlag = false; // siehe Kommentar in buildAndSendStatusPacket()
    }
}

// ---- Quick-Messages (lockere Ja/Nein-Fragen, siehe QuickMessages.h) ----
// Menü-Zustand: Frage durchblättern (kurzer Druck) / senden (langer Druck)
static QuickQuestion selectedQuestion = QuickQuestion::ALLES_GUT;
static uint8_t quickMsgSequence = 0;

// Zustand einer ausgehenden Frage (auf die eine Antwort erwartet wird)
static bool waitingForAnswer = false;
static uint8_t pendingRequestSequence = 0;
static unsigned long pendingRequestSentMillis = 0;

// Zustand einer eingehenden Frage (die der Nutzer gerade beantworten soll)
static bool haveIncomingQuestion = false;
static QuickMessageRequest incomingRequest;

// Kurzzeitig angezeigte Antwort (eigene Frage wurde beantwortet, oder Timeout)
static QuickAnswer lastReceivedAnswer = QuickAnswer::PENDING;
static bool showAnswerOverlay = false;
static unsigned long answerOverlayShownMillis = 0;
static const unsigned long ANSWER_OVERLAY_DISPLAY_MS = 4000;

static void sendQuickAnswer(QuickAnswer answer) {
    QuickMessageResponse resp;
    resp.inResponseToSequence = incomingRequest.sequence;
    resp.responder = DeviceId::BOOT;
    resp.answer = answer;
    sendEncrypted((uint8_t *)&resp, sizeof(resp));
    haveIncomingQuestion = false;
    updateQuickOverlay();
}

// Automatische Antwort während aktiver Wettfahrt (siehe Doku Abschnitt 5):
// Kein Overlay, keine Vibration - der Skipper soll während COMPETITION gar
// nicht erst durch Quick-Messages abgelenkt werden.
void handleIncomingQuickMessageRequest(const QuickMessageRequest &req) {
    if (currentBoatState == BoatState::COMPETITION) {
        QuickMessageResponse resp;
        resp.inResponseToSequence = req.sequence;
        resp.responder = DeviceId::BOOT;
        resp.answer = QuickAnswer::AUTO_REGATTA;
        sendEncrypted((uint8_t *)&resp, sizeof(resp));
        return;
    }
    haveIncomingQuestion = true;
    incomingRequest = req;
    triggerHaptic(HAPTIC_STEP1); // kurze Vibration - nicht beim 30s-Status, nur hier
    updateQuickOverlay();
}

void onButtonShortPress() {
    Serial.println("[Taster] kurz");
    if (haveIncomingQuestion) {
        // Fallback, falls Gestenerkennung unzuverlässig ist:
        sendQuickAnswer(QuickAnswer::JA);
        return;
    }
    // Sonst: im Auswahlmenü zur nächsten Frage blättern
    selectedQuestion = (QuickQuestion)(((uint8_t)selectedQuestion + 1) % (uint8_t)QuickQuestion::COUNT);
    Serial.printf("[Quick-Msg] Frage ausgewaehlt: %s\n", quickQuestionText(selectedQuestion));
    updateQuickOverlay();
}

void onButtonLongPress() {
    Serial.println("[Taster] lang");
    if (haveIncomingQuestion) {
        // Fallback, falls Gestenerkennung unzuverlässig ist:
        sendQuickAnswer(QuickAnswer::NEIN);
        return;
    }
    // Sonst: ausgewählte Frage als QuickMessageRequest senden
    QuickMessageRequest req;
    req.sequence = quickMsgSequence++;
    req.sender = DeviceId::BOOT;
    req.question = selectedQuestion;
    sendEncrypted((uint8_t *)&req, sizeof(req));
    waitingForAnswer = true;
    pendingRequestSequence = req.sequence;
    pendingRequestSentMillis = millis();
    Serial.printf("[Quick-Msg TX] Frage gesendet: seq=%d %s\n", req.sequence, quickQuestionText(req.question));
    updateQuickOverlay();
}

// ---- Gesten-Antwort (primärer Weg auf dem Boot, siehe Doku Abschnitt 5) ----
// WICHTIG: Gestenerkennung nur aktiv abfragen/auswerten, wenn
// haveIncomingQuestion == true - sonst wird jede normale Handbewegung beim
// Segeln als Antwort fehlinterpretiert.

void onGestureTiltUp() {
    if (!haveIncomingQuestion) return;
    sendQuickAnswer(QuickAnswer::JA);
}

void onGestureShake() {
    if (!haveIncomingQuestion) return;
    sendQuickAnswer(QuickAnswer::NEIN);
}

void checkQuickMessageTimeout() {
    if (waitingForAnswer && (millis() - pendingRequestSentMillis) > QUICK_MESSAGE_TIMEOUT_MS) {
        waitingForAnswer = false;
        lastReceivedAnswer = QuickAnswer::TIMEOUT;
        showAnswerOverlay = true;
        answerOverlayShownMillis = millis();
        updateQuickOverlay();
    }
    if (showAnswerOverlay && (millis() - answerOverlayShownMillis) > ANSWER_OVERLAY_DISPLAY_MS) {
        showAnswerOverlay = false;
        updateQuickOverlay();
    }
}

void loraReceiveTick() {
    if (!loraReceivedFlag) return;
    loraReceivedFlag = false;

    size_t rawLen = radio.getPacketLength();
    if (rawLen == 0 || rawLen > CRYPTO_MAX_BUFFER) {
        radio.startReceive();
        return;
    }
    uint8_t rawBuf[CRYPTO_MAX_BUFFER];
    int state = radio.readData(rawBuf, rawLen);
    if (state != RADIOLIB_ERR_NONE) return; // startReceive() läuft nach Interrupt automatisch weiter

    uint8_t plainBuf[CRYPTO_MAX_PAYLOAD];
    size_t plainLen;
    if (!decryptLoRaPacket(rawBuf, rawLen, plainBuf, plainLen) || plainLen < 1) return;

    uint8_t msgType = plainBuf[0];
    if (msgType == 0x10 && plainLen >= sizeof(QuickMessageRequest)) {
        QuickMessageRequest req;
        memcpy(&req, plainBuf, sizeof(req));
        // Absender-Check: nur Anfragen von der Land-Uhr akzeptieren. Schützt
        // gegen Selbstempfang der eigenen gerade gesendeten Pakete (siehe
        // Kommentar bei sendEncrypted()/buildAndSendStatusPacket()).
        if (req.sender != DeviceId::LAND) return;
        Serial.printf("[Quick-Msg RX] Frage von Land: seq=%d %s\n", req.sequence, quickQuestionText(req.question));
        handleIncomingQuickMessageRequest(req);
    } else if (msgType == 0x11 && plainLen >= sizeof(QuickMessageResponse)) {
        QuickMessageResponse resp;
        memcpy(&resp, plainBuf, sizeof(resp));
        if (resp.responder != DeviceId::LAND) return; // siehe Absender-Check oben
        Serial.printf("[Quick-Msg RX] Antwort von Land: inResponseTo=%d %s (erwartet=%d wartend=%d)\n",
                       resp.inResponseToSequence, quickAnswerText(resp.answer), pendingRequestSequence, waitingForAnswer);
        if (waitingForAnswer && resp.inResponseToSequence == pendingRequestSequence) {
            waitingForAnswer = false;
            lastReceivedAnswer = resp.answer;
            showAnswerOverlay = true;
            answerOverlayShownMillis = millis();
            triggerHaptic(HAPTIC_DONE2); // kurze Vibration, Antwort ist da
            updateQuickOverlay();
        }
    }
    // sonst: LoRaStatusPacket oder unbekannter Typ - für die Ultra selbst
    // irrelevant (die sendet den Status, empfängt ihn nicht von sich selbst)
}

// ---- Physischer Taster (GPIO0, "Custom Button" laut Hardware-Doku) ----
// TODO(Claude Code / Roman): Pegel (aktiv-low/high) und ob LilyGoLib evtl.
// einen eigenen Button-Wrapper mitbringt, an echter Hardware verifizieren -
// hier als Standard-ESP32-Boot-Taster-Muster angenommen (aktiv-low,
// INPUT_PULLUP). Falls falsch, ändert sich nur buttonTick(), der Rest
// (onButtonShortPress/onButtonLongPress) bleibt unberührt.
#define CUSTOM_BUTTON_PIN 0
static const uint32_t BUTTON_LONG_PRESS_MS = 600;
static bool buttonWasDown = false;
static unsigned long buttonDownAtMs = 0;

static void buttonTick() {
    bool down = (digitalRead(CUSTOM_BUTTON_PIN) == LOW);
    if (down && !buttonWasDown) {
        buttonDownAtMs = millis();
    } else if (!down && buttonWasDown) {
        unsigned long heldMs = millis() - buttonDownAtMs;
        if (heldMs >= BUTTON_LONG_PRESS_MS) onButtonLongPress();
        else onButtonShortPress();
    }
    buttonWasDown = down;
}

// ---- Gesten-Erkennung (Platzhalter, siehe Doku Abschnitt 5) ----
// API-Namen (SensorXYZ/SensorQuaternion, instance.sensor, .enable(),
// .hasUpdated(), .getX/Y/Z(), .getPitch()) sind gegen die echten
// LilyGoLib-Beispiele examples/sensor/BHI260AP_{6DoF,Euler} geprüft, also
// keine Vermutung. UNKALIBRIERT sind aber die konkreten Schwellenwerte
// (GESTURE_* unten) - die MÜSSEN auf dem Wasser gegen echtes Segeln
// (Krängung/Wellenschlag/Schoteinholen) getestet werden, siehe Doku. Bis
// dahin bleibt der Taster (siehe oben) der zuverlässige Weg.
#ifdef USING_BHI260_SENSOR
static SensorXYZ gestureAccel(SensorBHI260AP::ACCEL_PASSTHROUGH, instance.sensor);
static SensorQuaternion gestureQuat(instance.sensor);
static bool bhi260Online = false;

// Lokale Platzhalter-Schwellenwerte (sensor-/einheitenabhängig, siehe
// QuickMessages.h-Kommentar - deshalb hier und nicht dort definiert).
// Erster echter Messwert (05.08.2026, Roman): beim "Hochschauen" ging Pitch
// auf ca. -30° (nicht +45° wie urspruenglich vermutet - falsches Vorzeichen
// geraten). Immer noch nur EIN Datenpunkt am Schreibtisch, siehe Doku-
// Warnung: auf dem Wasser mit echter Trageposition/Krängung nachschärfen.
static const float GESTURE_TILT_TARGET_ANGLE_DEG = -30.0f; // TODO: weiter kalibrieren
static const float GESTURE_TILT_TOLERANCE_DEG = 20.0f;     // TODO: weiter kalibrieren
static const float GESTURE_SHAKE_MIN_AMPLITUDE = 8.0f;    // m/s^2, TODO: auf dem Wasser kalibrieren

struct ShakeDetectorState {
    int8_t lastSign = 0;
    uint8_t reversals = 0;
    unsigned long windowStartMs = 0;
} shakeDetector;

static void setupGestureSensor() {
    if (!(instance.getDeviceProbe() & HW_BHI260AP_ONLINE)) {
        Serial.println("[Gesten] BHI260AP nicht online - Gestenerkennung deaktiviert, Taster bleibt Fallback");
        bhi260Online = false;
        return;
    }
    bhi260Online = true;
    Serial.println("[Gesten] BHI260AP online, Sensoren aktiviert"); // TODO(Test-Debug)
    float sampleRate = 50.0;       // reicht für Handgelenk-Gesten, spart Strom ggü. 100Hz
    uint32_t reportLatencyMs = 0;
    gestureAccel.enable(sampleRate, reportLatencyMs);
    gestureQuat.enable(sampleRate, reportLatencyMs);
}

// TODO(Test-Debug): Kalibrier-Logging, alle Zeilen mit diesem Kommentar nach
// erfolgreicher Kalibrierung wieder entfernen. Läuft absichtlich AUSSERHALB
// des haveIncomingQuestion-Gates, damit man die Rohwerte auch ohne offene
// Frage live beobachten kann - ausgewertet/ausgelöst wird trotzdem nur bei
// haveIncomingQuestion==true (siehe Doku-Warnung: Fehlauslösung beim Segeln).
static unsigned long lastGestureLogMs = 0;
static const unsigned long GESTURE_LOG_INTERVAL_MS = 1000; // Serial-Flut vermeiden (200ms hat den Monitor geflutet)

static void gestureTick() {
    if (!bhi260Online) return;

    bool doLog = (millis() - lastGestureLogMs) >= GESTURE_LOG_INTERVAL_MS;

    if (gestureQuat.hasUpdated()) {
        gestureQuat.toEuler();
        float pitch = gestureQuat.getPitch();
        if (doLog) {
            Serial.printf("[Gesten] Pitch=%.1f Roll=%.1f Heading=%.1f (Ziel fuer JA: %.0f +-%.0f)\n",
                          pitch, gestureQuat.getRoll(), gestureQuat.getHeading(),
                          GESTURE_TILT_TARGET_ANGLE_DEG, GESTURE_TILT_TOLERANCE_DEG);
        }
        if (haveIncomingQuestion && fabsf(pitch - GESTURE_TILT_TARGET_ANGLE_DEG) <= GESTURE_TILT_TOLERANCE_DEG) {
            onGestureTiltUp();
            lastGestureLogMs = millis();
            return;
        }
    }

    if (gestureAccel.hasUpdated()) {
        float ax = gestureAccel.getX();
        if (doLog) {
            Serial.printf("[Gesten] Accel X=%.2f Y=%.2f Z=%.2f (Schwelle: %.1f)\n",
                          ax, gestureAccel.getY(), gestureAccel.getZ(), GESTURE_SHAKE_MIN_AMPLITUDE);
            lastGestureLogMs = millis();
        }

        if (!haveIncomingQuestion) return; // Shake-Logik nur auswerten, wenn tatsächlich eine Frage offen ist

        unsigned long now = millis();
        if (now - shakeDetector.windowStartMs > GESTURE_SHAKE_WINDOW_MS) {
            shakeDetector.reversals = 0;
            shakeDetector.windowStartMs = now;
        }
        if (fabsf(ax) >= GESTURE_SHAKE_MIN_AMPLITUDE) {
            int8_t sign = (ax > 0) ? 1 : -1;
            if (shakeDetector.lastSign != 0 && sign != shakeDetector.lastSign) {
                shakeDetector.reversals++;
            }
            shakeDetector.lastSign = sign;
        }
        if (shakeDetector.reversals >= GESTURE_SHAKE_MIN_REVERSALS) {
            shakeDetector.reversals = 0;
            onGestureShake();
        }
    }
}
#else
static void setupGestureSensor() {
    Serial.println("[Gesten] USING_BHI260_SENSOR nicht definiert - Gestencode nicht mitkompiliert"); // TODO(Test-Debug)
}
static void gestureTick() {}
#endif

// ============================================================================
// LVGL-UI: Tabview mit 6 Screens, ausschliesslich Touch-Bedienung
// ============================================================================

lv_obj_t *tabview; // nicht static: siehe extern-Vorwärtsdeklaration weiter oben
static lv_obj_t *tabNav, *tabWind, *tabHome, *tabCountdown, *tabManeuver, *tabMenu;

// Statusleiste (oben, immer sichtbar)
static lv_obj_t *lblBleStatus;
static lv_obj_t *lblPhoneBattery;

// Kompass/Nav-Tab
static lv_obj_t *lblCogSog;
static lv_obj_t *lblGpsDetail;
static lv_obj_t *arcCompass;

// Wind-Tab
static lv_obj_t *lblWindDir;
static lv_obj_t *lblWindTrend;

// Heimweg-Tab
static lv_obj_t *lblHomeActive;
static lv_obj_t *lblHomeManeuver;
static lv_obj_t *lblHomeEta;

// Countdown-Tab
static lv_obj_t *lblCountdownBig;
static lv_obj_t *arcCountdown;

// Manöver-Tab
static lv_obj_t *lblManeuverBig;
static lv_obj_t *lblManeuverSub;

// Quick-Message-Overlay (layer_top, über allen Screens) + Menü-Widget
static lv_obj_t *lblQuickOverlay = nullptr;
static lv_obj_t *lblQuickSelected = nullptr;

static void statusBarUpdate() {
    lv_label_set_text(lblBleStatus, bleConnected ? "BLE OK" : "BLE ...");
    lv_obj_set_style_text_color(lblBleStatus, bleConnected ? lv_color_hex(0x30D060) : lv_color_hex(0xD03030), 0);
    if (phoneBatteryPct >= 0) {
        lv_label_set_text_fmt(lblPhoneBattery, "Bat %d%%", phoneBatteryPct);
    } else {
        lv_label_set_text(lblPhoneBattery, "Bat --");
    }
}

static void navScreenUpdate() {
    if (!gpsData.haveData) {
        lv_label_set_text(lblCogSog, "warte auf GPS...");
        lv_label_set_text(lblGpsDetail, "");
        lv_arc_set_value(arcCompass, 0);
        return;
    }
    if (gpsData.validFix) {
        lv_label_set_text_fmt(lblCogSog, "%.0f kn\nKurs %.0f°", gpsData.sogKn, gpsData.cogDeg);
        lv_arc_set_value(arcCompass, (int)gpsData.cogDeg);
    } else {
        lv_label_set_text(lblCogSog, "kein COG");
        lv_arc_set_value(arcCompass, 0);
    }
    lv_label_set_text_fmt(lblGpsDetail, "+/-%d m, %d ms alt", gpsData.accuracyM, gpsData.fixAgeMs);
}

static void windScreenUpdate() {
    if (!windData.haveData || !windData.calibrated) {
        lv_label_set_text(lblWindDir, "nicht\nkalibriert");
        lv_label_set_text(lblWindTrend, "");
        return;
    }
    lv_label_set_text_fmt(lblWindDir, "%.0f°", windData.dirDeg);
    lv_label_set_text_fmt(lblWindTrend, "Trend %+.0f°", windData.trendDeg);
}

static void homeScreenUpdate() {
    if (!homeData.haveData) {
        lv_label_set_text(lblHomeActive, "warte auf\nHeimweg-Status...");
        lv_label_set_text(lblHomeManeuver, "");
        lv_label_set_text(lblHomeEta, "");
        return;
    }
    lv_label_set_text(lblHomeActive, homeData.active ? "Heimweg: AKTIV" : "Heimweg: inaktiv");
    lv_obj_set_style_text_color(lblHomeActive, homeData.active ? lv_color_hex(0x30D060) : lv_color_hex(0x808080), 0);
    lv_label_set_text(lblHomeManeuver, homeData.maneuverNeeded ? "Wende empfohlen: JA" : "Wende empfohlen: nein");
    if (homeData.etaMinutes >= 0) {
        lv_label_set_text_fmt(lblHomeEta, "ETA: %d min", homeData.etaMinutes);
    } else {
        lv_label_set_text(lblHomeEta, "ETA: unbekannt");
    }
}

static void countdownScreenUpdate() {
    if (!raceData.haveData || raceData.countdownSeconds < 0) {
        lv_label_set_text(lblCountdownBig, "--:--");
        lv_obj_set_style_text_color(lblCountdownBig, lv_color_hex(0x808080), 0);
        lv_arc_set_value(arcCountdown, 0);
        return;
    }
    int s = raceData.countdownSeconds;
    lv_label_set_text_fmt(lblCountdownBig, "%d:%02d", s / 60, s % 60);
    // Deutlich sichtbare Farbeskalation, je näher der Start rückt (grosse
    // Zahl reicht allein oft nicht auf einen kurzen Blick beim Segeln).
    lv_color_t color;
    if (s <= 10) color = lv_color_hex(0xE02020);       // rot: letzte 10s
    else if (s <= 60) color = lv_color_hex(0xE0A020);  // orange: letzte Minute
    else color = lv_color_hex(0x30C0D0);               // türkis: entspannt
    lv_obj_set_style_text_color(lblCountdownBig, color, 0);
    int pct = (s > 60) ? 100 : (s * 100 / 60);
    lv_arc_set_value(arcCountdown, pct);
}

static void maneuverScreenUpdate() {
    bool needed = raceData.haveData && raceData.maneuverNeeded;
    lv_label_set_text(lblManeuverBig, needed ? (raceData.isTack ? "WENDE!" : "HALSE!") : "kein Manöver\nempfohlen");
    lv_obj_set_style_text_color(lblManeuverBig, needed ? lv_color_hex(0xE0A020) : lv_color_hex(0x808080), 0);
    if (raceData.haveData && raceData.competitionLeg >= 0) {
        const char *legNames[] = {"Luv-Kurs", "Halbwind zur Boje", "Vorwind-Kurs"};
        lv_label_set_text(lblManeuverSub, legNames[raceData.competitionLeg]);
    } else {
        lv_label_set_text(lblManeuverSub, "");
    }
}

// Zeigt eine eingehende Quick-Message-Frage bzw. eine frisch eingetroffene
// Antwort als Overlay über dem aktuell aktiven Screen (layer_top). Blendet
// sich automatisch aus, wenn nichts anzuzeigen ist. Aktualisiert ausserdem
// den "aktuell ausgewählte Frage"-Hinweis im Menü-Tab.
static void updateQuickOverlay() {
    if (lblQuickOverlay != nullptr) {
        if (haveIncomingQuestion) {
            lv_label_set_text_fmt(lblQuickOverlay, "FRAGE VON LAND:\n%s\n\nkurz/Geste hoch=JA\nlang/schuetteln=NEIN",
                                   quickQuestionText(incomingRequest.question));
            lv_obj_clear_flag(lblQuickOverlay, LV_OBJ_FLAG_HIDDEN);
        } else if (showAnswerOverlay) {
            lv_label_set_text_fmt(lblQuickOverlay, "ANTWORT:\n%s", quickAnswerText(lastReceivedAnswer));
            lv_obj_clear_flag(lblQuickOverlay, LV_OBJ_FLAG_HIDDEN);
        } else {
            lv_obj_add_flag(lblQuickOverlay, LV_OBJ_FLAG_HIDDEN);
        }
    }
    if (lblQuickSelected != nullptr) {
        lv_label_set_text_fmt(lblQuickSelected, "Frage: %s", quickQuestionText(selectedQuestion));
    }
}

void refreshActiveScreen() {
    statusBarUpdate();
    navScreenUpdate();
    windScreenUpdate();
    homeScreenUpdate();
    countdownScreenUpdate();
    maneuverScreenUpdate();
    updateBoatState();
    autoFocusTick();
}

// ---- Menü-Tab: Buttons, die CMD_* auslösen ----
static void cbCountdownStart(lv_event_t *e) { sendControlCommand(CMD_COUNTDOWN_START); }
static void cbCountdownReset(lv_event_t *e) { sendControlCommand(CMD_COUNTDOWN_RESET); }
static void cbCountdownSync(lv_event_t *e) { sendControlCommand(CMD_COUNTDOWN_SYNC_NEXT_MINUTE); }
static void cbWindCalStart(lv_event_t *e) { sendControlCommand(CMD_WIND_CALIBRATE_START); }
static void cbWindCalAbort(lv_event_t *e) { sendControlCommand(CMD_WIND_CALIBRATE_ABORT); }
static void cbTrainOff(lv_event_t *e) { sendControlCommand(CMD_TRAIN_MODE_OFF); }
static void cbTrainTack(lv_event_t *e) { sendControlCommand(CMD_TRAIN_MODE_TACK_ONLY); }
static void cbTrainJibe(lv_event_t *e) { sendControlCommand(CMD_TRAIN_MODE_JIBE_ONLY); }
static void cbTrainRace(lv_event_t *e) { sendControlCommand(CMD_TRAIN_MODE_RACE); }
static void cbHomeToggle(lv_event_t *e) { sendControlCommand(CMD_HOME_MODE_TOGGLE); }
static void cbCompetitionEnd(lv_event_t *e) { sendControlCommand(CMD_COMPETITION_END); }
static void cbClearLog(lv_event_t *e) { sendControlCommand(CMD_CLEAR_LOG); }

// Wegpunkt-Buttons brauchen die ID im Klick-Callback -> user_data
static void cbSetWaypoint(lv_event_t *e) {
    int id = (int)(intptr_t)lv_event_get_user_data(e);
    sendControlCommand(CMD_SET_WAYPOINT, id);
}
static void cbClearWaypoint(lv_event_t *e) {
    int id = (int)(intptr_t)lv_event_get_user_data(e);
    sendControlCommand(CMD_CLEAR_WAYPOINT, id);
}

// Touch-Alternative zum physischen Taster (siehe Doku: "Touch optional als
// Bonus bei ruhigem Wetter, nicht primär geplant") - ruft exakt dieselben
// Handler wie der echte Taster auf, also identisches Verhalten inkl.
// Ja/Nein-Fallback bei offener Frage.
static void cbQuickNext(lv_event_t *e) { onButtonShortPress(); }
static void cbQuickSend(lv_event_t *e) { onButtonLongPress(); }

// Feedback nach erstem Hardware-Test: Standard-LVGL-Buttongroesse/-Font ist
// auf dem echten Bildschirm zu klein/schmal - deshalb hier fest auf eine
// grosszuegige Mindesthoehe + groesseren Font.
static lv_obj_t *addMenuButton(lv_obj_t *parent, const char *label, lv_event_cb_t cb, void *userData = nullptr) {
    lv_obj_t *btn = lv_button_create(parent);
    lv_obj_set_width(btn, LV_PCT(94));
    lv_obj_set_height(btn, 64);
    lv_obj_add_event_cb(btn, cb, LV_EVENT_CLICKED, userData);
    lv_obj_t *lbl = lv_label_create(btn);
    lv_obj_set_style_text_font(lbl, &lv_font_montserrat_28, 0);
    lv_label_set_text(lbl, label);
    lv_obj_center(lbl);
    return btn;
}

// text_font vererbt sich nicht zuverlässig - Section-Header bekommen ihre
// Schrift deshalb hier zentral über diesen kleinen Helfer statt einzeln.
static lv_obj_t *addSubHeader(lv_obj_t *parent, const char *text) {
    lv_obj_t *lbl = lv_label_create(parent);
    lv_obj_set_style_text_font(lbl, &lv_font_montserrat_24, 0);
    lv_label_set_text(lbl, text);
    return lbl;
}

static void buildMenuTab(lv_obj_t *parent) {
    lv_obj_set_flex_flow(parent, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_row(parent, 6, 0);

    addSubHeader(parent, "-- Countdown --");
    addMenuButton(parent, "Start", cbCountdownStart);
    addMenuButton(parent, "Reset", cbCountdownReset);
    addMenuButton(parent, "Sync naechste Minute", cbCountdownSync);

    addSubHeader(parent, "-- Wind --");
    addMenuButton(parent, "Kalibrierung starten", cbWindCalStart);
    addMenuButton(parent, "Kalibrierung abbrechen", cbWindCalAbort);

    addSubHeader(parent, "-- Training --");
    addMenuButton(parent, "Aus", cbTrainOff);
    addMenuButton(parent, "Nur Wende", cbTrainTack);
    addMenuButton(parent, "Nur Halse", cbTrainJibe);
    addMenuButton(parent, "Race (2 Bojen)", cbTrainRace);

    addSubHeader(parent, "-- Wegpunkte (an akt. Position) --");
    addMenuButton(parent, "Boje 1 setzen", cbSetWaypoint, (void *)(intptr_t)WP_BUOY1);
    addMenuButton(parent, "Boje 2 setzen", cbSetWaypoint, (void *)(intptr_t)WP_BUOY2);
    addMenuButton(parent, "Ziel setzen", cbSetWaypoint, (void *)(intptr_t)WP_TARGET);
    addMenuButton(parent, "Home setzen", cbSetWaypoint, (void *)(intptr_t)WP_HOME);
    addMenuButton(parent, "Comp.-Marke 1 setzen", cbSetWaypoint, (void *)(intptr_t)WP_COMPETITION_MARK1);
    addMenuButton(parent, "Comp.-Marke 2 setzen", cbSetWaypoint, (void *)(intptr_t)WP_COMPETITION_MARK2);
    addMenuButton(parent, "Alle Bojen loeschen", cbClearWaypoint, (void *)(intptr_t)WP_BUOY1);

    addSubHeader(parent, "-- Sonstiges --");
    addMenuButton(parent, "Heimweg an/aus", cbHomeToggle);
    addMenuButton(parent, "Wettfahrt beenden", cbCompetitionEnd);
    addMenuButton(parent, "Manoever-Log loeschen", cbClearLog);

    // -- Quick-Message an Land (siehe docs/Erweiterung_Land_Boot_LoRa_Kommunikation.md) --
    // Primärer Weg ist der physische Taster/die Geste (nasse Hände auf dem
    // Boot), diese Buttons sind nur der optionale Touch-Bonus bei ruhigem
    // Wetter, rufen aber exakt dieselben Handler auf.
    addSubHeader(parent, "-- Quick-Message an Land --");
    lblQuickSelected = lv_label_create(parent);
    lv_obj_set_style_text_font(lblQuickSelected, &lv_font_montserrat_24, 0);
    lv_label_set_text(lblQuickSelected, "Frage: ALLES GUT?");
    addMenuButton(parent, "Naechste Frage", cbQuickNext);
    addMenuButton(parent, "Frage senden", cbQuickSend);
}

static lv_obj_t *screenAlltag;
static lv_obj_t *screenSegeln;

// Alltags-Tab-Widgets
static lv_obj_t *lblClockBig, *lblClockDate;
static lv_obj_t *lblStopwatch;
static lv_obj_t *lblOwnBattery;
static lv_obj_t *swForceSegeln;

static uint32_t stopwatchStartMs = 0;
static uint32_t stopwatchElapsedMs = 0;
static bool stopwatchRunning = false;

static void buildSegelnScreen() {
    screenSegeln = lv_obj_create(NULL);
    // Feedback nach erstem Hardware-Test: Schrift generell zu klein. text_font
    // ist in LVGL eine vererbte Eigenschaft - hier auf Screen-Ebene gesetzt,
    // wirkt automatisch auf alle Labels, die keine eigene (groessere) Schrift
    // gesetzt haben (Countdown/Wind/Manöver-Zahlen bleiben bei ihrer extra
    // grossen Schrift, die haben ihre eigene lokale Font-Einstellung).
    lv_obj_set_style_text_font(screenSegeln, &lv_font_montserrat_28, 0);

    tabview = lv_tabview_create(screenSegeln);
    lv_tabview_set_tab_bar_position(tabview, LV_DIR_BOTTOM);
    // 70 statt 46: die Tab-Leiste clippt ihre Kinder an der eigenen Höhe -
    // die gedrehten+hochgeschobenen Eck-Tabs brauchen den zusätzlichen Platz,
    // sonst wird ihre Schrift oben abgeschnitten (siehe Hardware-Feedback).
    lv_tabview_set_tab_bar_size(tabview, 94); // 70 war immer noch zu knapp, nochmal um denselben Betrag erhöht

    // Feedback nach erstem Hardware-Test: Bildschirm ist in den Ecken leicht
    // abgedeckt, die aeussersten Tabs (Nav ganz links, Menu ganz rechts)
    // waren dadurch nicht lesbar. Kleiner Sicherheitsabstand an der
    // Tab-Leiste schiebt alle Tabs etwas von den Raendern weg. Tab-Bar hat
    // ihre eigene Theme-Schrift (erbt nicht automatisch von screenSegeln),
    // deshalb hier explizit gesetzt - Feedback "UI generell zu klein" galt
    // auch dafür.
    lv_obj_t *segelnTabBar = lv_tabview_get_tab_bar(tabview);
    lv_obj_set_style_pad_hor(segelnTabBar, 30, 0); // fester Gehäuse-Rand, nicht nur ein Rendering-Rand - deutlich grosszuegiger
    lv_obj_set_style_text_font(segelnTabBar, &lv_font_montserrat_18, 0);

    tabNav       = lv_tabview_add_tab(tabview, "Nav");
    tabWind      = lv_tabview_add_tab(tabview, "Wind");
    tabHome      = lv_tabview_add_tab(tabview, "Heim");
    tabCountdown = lv_tabview_add_tab(tabview, "CD");
    tabManeuver  = lv_tabview_add_tab(tabview, "Man");
    tabMenu      = lv_tabview_add_tab(tabview, "Menu");

    // Auf Roman's Vorschlag: die beiden äussersten Tab-Buttons (ganz links/
    // rechts, genau dort wo das Gehäuse den Bildschirm abdeckt) werden um
    // 45° zur Bildschirmmitte gedreht - sollten dadurch aus der abgedeckten
    // Zone "rausschwenken". Erster Test, ob das an echter Hardware wirklich
    // besser lesbar/antippbar ist, steht noch aus. lv_obj_set_style_
    // transform_rotation nimmt Zehntelgrad (450 = 45.0°), Drehpunkt auf die
    // Mitte des jeweiligen Tab-Buttons gelegt.
    // Feedback: Ausrichtung der gedrehten Tabs passt, aber sie müssen um
    // ca. eine Schriftgrösse (18px-Font) nach oben verschoben werden, sonst
    // sind sie weiterhin verdeckt. translate_y ist eine reine Render-
    // Verschiebung, beeinflusst NICHT das Flex-Layout der anderen Tabs.
    static const int16_t OUTER_TAB_LIFT_PX = -22;

    uint32_t segelnTabCount = lv_obj_get_child_count(segelnTabBar);
    if (segelnTabCount > 0) {
        lv_obj_t *firstTab = lv_obj_get_child(segelnTabBar, 0);
        lv_obj_set_style_transform_pivot_x(firstTab, lv_pct(50), 0);
        lv_obj_set_style_transform_pivot_y(firstTab, lv_pct(50), 0);
        lv_obj_set_style_transform_rotation(firstTab, 450, 0);
        lv_obj_set_style_translate_y(firstTab, OUTER_TAB_LIFT_PX, 0);
    }
    if (segelnTabCount > 1) {
        lv_obj_t *lastTab = lv_obj_get_child(segelnTabBar, segelnTabCount - 1);
        lv_obj_set_style_transform_pivot_x(lastTab, lv_pct(50), 0);
        lv_obj_set_style_transform_pivot_y(lastTab, lv_pct(50), 0);
        lv_obj_set_style_translate_y(lastTab, OUTER_TAB_LIFT_PX, 0);
        lv_obj_set_style_transform_rotation(lastTab, -450, 0);
    }

    // -- Nav-Tab --
    arcCompass = lv_arc_create(tabNav);
    lv_arc_set_rotation(arcCompass, 270);
    lv_arc_set_bg_angles(arcCompass, 0, 360);
    lv_arc_set_range(arcCompass, 0, 359);
    lv_obj_set_size(arcCompass, 150, 150);
    lv_obj_align(arcCompass, LV_ALIGN_TOP_MID, 0, 10);
    lv_obj_remove_style(arcCompass, NULL, LV_PART_KNOB); // rein informativ, nicht bedienbar
    lv_obj_clear_flag(arcCompass, LV_OBJ_FLAG_CLICKABLE);

    // Feedback nach Hardware-Test: text_font vererbt sich in dieser
    // LVGL-Version NICHT wie erwartet vom Screen-Container auf Kind-Labels -
    // deshalb jetzt an jedem einzelnen Label explizit gesetzt statt über
    // lv_obj_set_style_text_font(screenSegeln, ...) am Anfang der Funktion.
    lblCogSog = lv_label_create(tabNav);
    lv_obj_set_style_text_font(lblCogSog, &lv_font_montserrat_28, 0);
    lv_obj_set_style_text_align(lblCogSog, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_align(lblCogSog, LV_ALIGN_CENTER, 0, 10);

    lblGpsDetail = lv_label_create(tabNav);
    lv_obj_set_style_text_font(lblGpsDetail, &lv_font_montserrat_20, 0);
    lv_obj_align(lblGpsDetail, LV_ALIGN_BOTTOM_MID, 0, -6);

    // -- Wind-Tab --
    lblWindDir = lv_label_create(tabWind);
    lv_obj_set_style_text_font(lblWindDir, &lv_font_montserrat_28, 0);
    lv_obj_align(lblWindDir, LV_ALIGN_CENTER, 0, -10);
    lblWindTrend = lv_label_create(tabWind);
    lv_obj_set_style_text_font(lblWindTrend, &lv_font_montserrat_28, 0);
    lv_obj_align(lblWindTrend, LV_ALIGN_CENTER, 0, 30);

    // -- Heimweg-Tab --
    lblHomeActive = lv_label_create(tabHome);
    lv_obj_set_style_text_font(lblHomeActive, &lv_font_montserrat_28, 0);
    lv_obj_set_style_text_align(lblHomeActive, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_align(lblHomeActive, LV_ALIGN_TOP_MID, 0, 20);
    lblHomeManeuver = lv_label_create(tabHome);
    lv_obj_set_style_text_font(lblHomeManeuver, &lv_font_montserrat_28, 0);
    lv_obj_align(lblHomeManeuver, LV_ALIGN_CENTER, 0, 0);
    lblHomeEta = lv_label_create(tabHome);
    lv_obj_set_style_text_font(lblHomeEta, &lv_font_montserrat_28, 0);
    lv_obj_align(lblHomeEta, LV_ALIGN_BOTTOM_MID, 0, -20);

    // -- Countdown-Tab -- (bewusst dominant: grösster verfügbarer Font +
    // grosser Ring, siehe Nutzer-Feedback "muss gross bis zum Start")
    arcCountdown = lv_arc_create(tabCountdown);
    lv_arc_set_rotation(arcCountdown, 270);
    lv_arc_set_bg_angles(arcCountdown, 0, 360);
    lv_arc_set_range(arcCountdown, 0, 100);
    lv_obj_set_size(arcCountdown, 220, 220);
    lv_obj_align(arcCountdown, LV_ALIGN_CENTER, 0, -6);
    lv_obj_set_style_arc_width(arcCountdown, 10, LV_PART_INDICATOR);
    lv_obj_set_style_arc_width(arcCountdown, 10, LV_PART_MAIN);
    lv_obj_remove_style(arcCountdown, NULL, LV_PART_KNOB);
    lv_obj_clear_flag(arcCountdown, LV_OBJ_FLAG_CLICKABLE);
    lblCountdownBig = lv_label_create(tabCountdown);
    lv_obj_set_style_text_font(lblCountdownBig, &lv_font_montserrat_48, 0);
    lv_obj_align(lblCountdownBig, LV_ALIGN_CENTER, 0, -6);

    // -- Manöver-Tab --
    lblManeuverBig = lv_label_create(tabManeuver);
    lv_obj_set_style_text_font(lblManeuverBig, &lv_font_montserrat_28, 0);
    lv_obj_set_style_text_align(lblManeuverBig, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_align(lblManeuverBig, LV_ALIGN_CENTER, 0, -10);
    lblManeuverSub = lv_label_create(tabManeuver);
    lv_obj_set_style_text_font(lblManeuverSub, &lv_font_montserrat_28, 0);
    lv_obj_align(lblManeuverSub, LV_ALIGN_CENTER, 0, 30);

    // -- Menü-Tab --
    buildMenuTab(tabMenu);
}

// ---- Alltags-Screen: Uhrzeit / Stoppuhr / Batterie / Einstellungen ----

static void cbStopwatchToggle(lv_event_t *e) {
    if (!stopwatchRunning) {
        stopwatchStartMs = millis() - stopwatchElapsedMs;
        stopwatchRunning = true;
    } else {
        stopwatchElapsedMs = millis() - stopwatchStartMs;
        stopwatchRunning = false;
    }
}
static void cbStopwatchReset(lv_event_t *e) {
    stopwatchRunning = false;
    stopwatchElapsedMs = 0;
    lv_label_set_text(lblStopwatch, "00:00.0");
}

static void cbForceSegelnToggle(lv_event_t *e) {
    forceSegelnMode = lv_obj_has_state(swForceSegeln, LV_STATE_CHECKED);
    if (forceSegelnMode) {
        switchToMode(MODE_SEGELN);
    }
    // Ausschalten allein reisst den Segelmodus nicht sofort weg, falls
    // gerade tatsächlich verbunden ist — appModeTick()/onDisconnect regeln
    // den Rückfall dann normal weiter.
}

/**
 * Deep-Sleep — die Uhr hat keine physische Power-Taste im Alltag (bewusst
 * reine Touch-Bedienung), daher hier als Button. Aufwachen per Tippen auf
 * den Screen ODER Krone-Druck (Standardwerte von instance.sleep() für die
 * T-Watch S3: WAKEUP_SRC_POWER_KEY | WAKEUP_SRC_TOUCH_PANEL).
 */
static void cbShutdown(lv_event_t *e) {
    lv_label_set_text(lblClockBig, "");
    lv_label_set_text(lblClockDate, "Aus - zum Aufwachen\ntippen oder Krone druecken");
    lv_task_handler();
    delay(300); // kurze Zeit, damit der Hinweis noch sichtbar ist
    instance.sleep(); // Standard: WAKEUP_SRC_POWER_KEY | WAKEUP_SRC_TOUCH_PANEL
}

static void buildAlltagScreen() {
    screenAlltag = lv_obj_create(NULL);
    lv_obj_set_style_text_font(screenAlltag, &lv_font_montserrat_28, 0); // siehe Kommentar in buildSegelnScreen()

    lv_obj_t *tv = lv_tabview_create(screenAlltag);
    lv_tabview_set_tab_bar_position(tv, LV_DIR_BOTTOM);
    lv_tabview_set_tab_bar_size(tv, 94); // siehe Kommentar in buildSegelnScreen() - Clipping der gedrehten Eck-Tabs
    lv_obj_t *alltagTabBar = lv_tabview_get_tab_bar(tv);
    lv_obj_set_style_pad_hor(alltagTabBar, 30, 0); // siehe Kommentar in buildSegelnScreen() - fester Gehäuse-Rand
    lv_obj_set_style_text_font(alltagTabBar, &lv_font_montserrat_18, 0);

    lv_obj_t *tabClock = lv_tabview_add_tab(tv, "Uhr");
    lv_obj_t *tabStopwatch = lv_tabview_add_tab(tv, "Timer");
    lv_obj_t *tabBattery = lv_tabview_add_tab(tv, "Akku");
    lv_obj_t *tabSettings = lv_tabview_add_tab(tv, "Setup");

    // Dieselbe 45°-Drehung + Hochschieben der äussersten Tabs wie in
    // buildSegelnScreen() (siehe dortiger Kommentar zu OUTER_TAB_LIFT_PX)
    uint32_t alltagTabCount = lv_obj_get_child_count(alltagTabBar);
    if (alltagTabCount > 0) {
        lv_obj_t *firstTab = lv_obj_get_child(alltagTabBar, 0);
        lv_obj_set_style_transform_pivot_x(firstTab, lv_pct(50), 0);
        lv_obj_set_style_transform_pivot_y(firstTab, lv_pct(50), 0);
        lv_obj_set_style_transform_rotation(firstTab, 450, 0);
        lv_obj_set_style_translate_y(firstTab, -22, 0);
    }
    if (alltagTabCount > 1) {
        lv_obj_t *lastTab = lv_obj_get_child(alltagTabBar, alltagTabCount - 1);
        lv_obj_set_style_transform_pivot_x(lastTab, lv_pct(50), 0);
        lv_obj_set_style_transform_pivot_y(lastTab, lv_pct(50), 0);
        lv_obj_set_style_transform_rotation(lastTab, -450, 0);
        lv_obj_set_style_translate_y(lastTab, -22, 0);
    }

    // -- Uhrzeit-Tab --
    lblClockBig = lv_label_create(tabClock);
    lv_obj_set_style_text_font(lblClockBig, &lv_font_montserrat_48, 0);
    lv_obj_align(lblClockBig, LV_ALIGN_CENTER, 0, -10);
    lblClockDate = lv_label_create(tabClock);
    lv_obj_set_style_text_font(lblClockDate, &lv_font_montserrat_28, 0);
    lv_obj_align(lblClockDate, LV_ALIGN_CENTER, 0, 30);

    // -- Stoppuhr-Tab --
    lblStopwatch = lv_label_create(tabStopwatch);
    lv_obj_set_style_text_font(lblStopwatch, &lv_font_montserrat_28, 0);
    lv_label_set_text(lblStopwatch, "00:00.0");
    lv_obj_align(lblStopwatch, LV_ALIGN_TOP_MID, 0, 20);
    lv_obj_t *btnToggle = lv_button_create(tabStopwatch);
    lv_obj_set_size(btnToggle, 160, 60);
    lv_obj_align(btnToggle, LV_ALIGN_CENTER, 0, 10);
    lv_obj_add_event_cb(btnToggle, cbStopwatchToggle, LV_EVENT_CLICKED, NULL);
    lv_obj_t *lblToggle = lv_label_create(btnToggle);
    lv_obj_set_style_text_font(lblToggle, &lv_font_montserrat_24, 0);
    lv_label_set_text(lblToggle, "Start/Stop");
    lv_obj_center(lblToggle);
    lv_obj_t *btnReset = lv_button_create(tabStopwatch);
    lv_obj_set_size(btnReset, 160, 60);
    lv_obj_align(btnReset, LV_ALIGN_BOTTOM_MID, 0, -10);
    lv_obj_add_event_cb(btnReset, cbStopwatchReset, LV_EVENT_CLICKED, NULL);
    lv_obj_t *lblReset = lv_label_create(btnReset);
    lv_obj_set_style_text_font(lblReset, &lv_font_montserrat_24, 0);
    lv_label_set_text(lblReset, "Reset");
    lv_obj_center(lblReset);

    // -- Akku-Tab (eigener Uhr-Akku, siehe instance.pmu) --
    lblOwnBattery = lv_label_create(tabBattery);
    lv_obj_set_style_text_font(lblOwnBattery, &lv_font_montserrat_28, 0);
    lv_obj_center(lblOwnBattery);

    // -- Setup-Tab: manueller Segelmodus-Schalter --
    lv_obj_t *lblSw = lv_label_create(tabSettings);
    lv_obj_set_style_text_font(lblSw, &lv_font_montserrat_24, 0);
    lv_label_set_text(lblSw, "Segelmodus erzwingen\n(auch ohne Handy-Verbindung)");
    lv_obj_set_style_text_align(lblSw, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_align(lblSw, LV_ALIGN_TOP_MID, 0, 10);
    swForceSegeln = lv_switch_create(tabSettings);
    lv_obj_set_style_transform_zoom(swForceSegeln, 320, 0); // groesserer Schalter, leichter zu treffen
    lv_obj_align(swForceSegeln, LV_ALIGN_CENTER, 0, 30);
    lv_obj_add_event_cb(swForceSegeln, cbForceSegelnToggle, LV_EVENT_VALUE_CHANGED, NULL);

    lv_obj_t *btnShutdown = lv_button_create(tabSettings);
    lv_obj_set_size(btnShutdown, 180, 60);
    lv_obj_set_style_bg_color(btnShutdown, lv_color_hex(0x802020), 0);
    lv_obj_align(btnShutdown, LV_ALIGN_BOTTOM_MID, 0, -10);
    lv_obj_add_event_cb(btnShutdown, cbShutdown, LV_EVENT_CLICKED, NULL);
    lv_obj_t *lblShutdown = lv_label_create(btnShutdown);
    lv_obj_set_style_text_font(lblShutdown, &lv_font_montserrat_24, 0);
    lv_label_set_text(lblShutdown, "Ausschalten");
    lv_obj_center(lblShutdown);
}

/**
 * Wechselt zwischen Alltags- und Segel-Screen (siehe
 * docs/Erweiterung_TWatch_S3_Alltagsmodus.md). Wird automatisch beim
 * BLE-Connect (-> SEGELN) bzw. nach Ablauf der Karenzzeit ohne Verbindung
 * (-> ALLTAG) aufgerufen, sowie manuell über den "Segelmodus erzwingen"-
 * Schalter im Setup-Tab.
 */
void switchToMode(AppMode mode) {
    if (appMode == mode) return;
    appMode = mode;
    lv_screen_load(mode == MODE_SEGELN ? screenSegeln : screenAlltag);
    refreshActiveScreen();
}

static void alltagScreenTick() {
    if (appMode != MODE_ALLTAG) return;

    struct tm timeinfo;
    instance.rtc.getDateTime(&timeinfo);
    char buf[16];
    snprintf(buf, sizeof(buf), "%02d:%02d", timeinfo.tm_hour, timeinfo.tm_min);
    lv_label_set_text(lblClockBig, buf);
    char dateBuf[24];
    snprintf(dateBuf, sizeof(dateBuf), "%02d.%02d.%04d", timeinfo.tm_mday, timeinfo.tm_mon + 1, timeinfo.tm_year + 1900);
    lv_label_set_text(lblClockDate, dateBuf);

    if (stopwatchRunning) {
        uint32_t elapsed = millis() - stopwatchStartMs;
        char sw[16];
        snprintf(sw, sizeof(sw), "%02lu:%02lu.%01lu",
                 (elapsed / 60000UL), (elapsed / 1000UL) % 60UL, (elapsed / 100UL) % 10UL);
        lv_label_set_text(lblStopwatch, sw);
    }

    int ownBatt = instance.pmu.getBatteryPercent();
    lv_label_set_text_fmt(lblOwnBattery, "Uhr-Akku: %d%%", ownBatt);
}

static void buildUi() {
    // Statusleiste oben, unabhängig vom aktiven Screen (lv_layer_top liegt
    // über JEDEM per lv_screen_load() geladenen Screen). Grösserer Rand
    // (14/10 statt 6/4) aus demselben Grund wie bei der Tab-Leiste unten:
    // Bildschirm ist in den Ecken leicht abgedeckt.
    lblBleStatus = lv_label_create(lv_layer_top());
    lv_obj_set_style_text_font(lblBleStatus, &lv_font_montserrat_20, 0);
    lv_obj_align(lblBleStatus, LV_ALIGN_TOP_LEFT, 14, 10);
    lblPhoneBattery = lv_label_create(lv_layer_top());
    lv_obj_set_style_text_font(lblPhoneBattery, &lv_font_montserrat_20, 0);
    lv_obj_align(lblPhoneBattery, LV_ALIGN_TOP_RIGHT, -14, 10);

    // Quick-Message-Overlay, ebenfalls auf layer_top - standardmässig
    // versteckt, wird nur bei eingehender Frage/frischer Antwort eingeblendet
    lblQuickOverlay = lv_label_create(lv_layer_top());
    lv_obj_set_width(lblQuickOverlay, LV_PCT(85));
    lv_obj_set_style_text_font(lblQuickOverlay, &lv_font_montserrat_28, 0);
    lv_obj_align(lblQuickOverlay, LV_ALIGN_CENTER, 0, 0);
    lv_obj_set_style_text_align(lblQuickOverlay, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_style_bg_color(lblQuickOverlay, lv_color_hex(0x203050), 0);
    lv_obj_set_style_bg_opa(lblQuickOverlay, LV_OPA_90, 0);
    lv_obj_set_style_pad_all(lblQuickOverlay, 10, 0);
    lv_obj_add_flag(lblQuickOverlay, LV_OBJ_FLAG_HIDDEN);

    buildSegelnScreen();
    buildAlltagScreen();

    lv_screen_load(screenAlltag); // Boot-Zustand: Alltag, bis Handy verbindet
    refreshActiveScreen();
}

// ============================================================================
// Setup / Loop
// ============================================================================

void setup() {
    Serial.begin(115200);

    instance.begin();
    beginLvglHelper(instance);
    instance.setBrightness(DEVICE_MAX_BRIGHTNESS_LEVEL);

    pinMode(CUSTOM_BUTTON_PIN, INPUT_PULLUP);

    buildUi();

    NimBLEDevice::init("Segeluhr-Watch-Ultra");
    NimBLEScan *scan = NimBLEDevice::getScan();
    scan->setScanCallbacks(&scanCallbacks, false);
    scan->setActiveScan(true);
    scan->setInterval(100);
    scan->setWindow(100);
    scan->start(2000, false);

    setupLoRaTransceiver();
    setupGestureSensor();
}

void loop() {
    instance.loop(); // u.a. nötig, damit radio-Interrupt-Callbacks dispatcht werden

    bleTick();
    appModeTick();
    alltagScreenTick();

    loraSendTick();
    loraReceiveTick();
    checkQuickMessageTimeout();
    buttonTick();
    gestureTick();

    lv_timer_handler();
    delay(5);
}
