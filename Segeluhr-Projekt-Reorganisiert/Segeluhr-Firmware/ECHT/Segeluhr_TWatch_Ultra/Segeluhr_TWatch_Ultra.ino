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
#include <Preferences.h> // NVS-Persistenz fuer trainierte Klio-Gesten-Muster
#include <LilyGoLib.h>
#include <LV_Helper.h>
#include <NimBLEDevice.h>
#include "../../shared/LoRaPacket.h"
#include "../../shared/QuickMessages.h"
#include "../../shared/Crypto.h"

// BHI260AP (6-Achsen-IMU) ist nur auf der Ultra verbaut (S3 nutzt fuer
// Standby/Schrittzaehler den separaten BMA423) - schaltet den Gesten-/
// Klio-Code weiter unten frei. WICHTIG (Bug gefunden 06.08.2026): dieses
// Define war in der bisherigen Datei NIE gesetzt, wodurch ausschliesslich
// die Stub-Varianten von setupGestureSensor()/gestureTick() (siehe #else
// weiter unten) kompiliert wurden - der komplette Schwellenwert-Gestencode
// (inkl. der vermeintlichen Pitch-Messung "-30°" vom 05.08., siehe
// GESTURE_TILT_TARGET_ANGLE_DEG-Kommentar) lief also nie wirklich auf der
// Hardware. Muss beim naechsten Hardware-Test neu verifiziert werden.
#define USING_BHI260_SENSOR

#ifdef USING_BHI260_SENSOR
#include <bosch/BoschSensorDataHelper.hpp>
#include <SensorBHI260AP_Klio.hpp>

// Klio-faehige BHI260-Firmware separat laden (siehe ausfuehrlicher
// Kommentar bei setupGestureSensor()): LilyGoLib laedt in instance.begin()
// standardmaessig NUR die "GPIO"-Firmware (BOSCH_BHI260_GPIO), die laut
// SensorLib-Klio-Beispielen KEIN Klio unterstuetzt - dafuer muesste
// USING_XL9555_EXPANDS gesetzt sein, was fuer die T-Watch Ultra laut
// boards.txt nirgends passiert.
//
// In einen anonymen Namespace gepackt: BoschFirmware.h legt u.a.
// "bosch_firmware_image" als (nicht-const) Zeiger-VARIABLE auf ein
// const-Array an - anders als das Array selbst hat so ein Zeiger OHNE
// "const" auf oberster Ebene in C++ externe Verlinkung, kollidiert also
// beim Linken mit dem gleichnamigen Symbol, das LilyGoWatchUltra.cpp fuer
// die GPIO-Firmware anlegt ("multiple definition"-Fehler, an genau dieser
// Stelle einmal falsch angenommen und durch echten Compile-Lauf gefunden).
// Der anonyme Namespace erzwingt interne Verlinkung unabhaengig von der
// Const-Frage, macht die Namen aber innerhalb dieser Datei weiterhin ohne
// Praefix nutzbar.
namespace {
#define BOSCH_BHI260_KLIO
#include <BoschFirmware.h>
}
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

// ---- Thread-Safety: NimBLE ruft seine Callbacks (onConnect/onDisconnect/
// Notify-Handler) in einem EIGENEN FreeRTOS-Task auf, nicht im loop()-Task.
// LVGL ist nicht thread-safe (lv_timer_handler() läuft in loop()) — deshalb
// dürfen die Callbacks NIE direkt LVGL- oder I2C-Funktionen (Haptik/DRV2605)
// aufrufen. Stattdessen nur Daten/Flags setzen, die eigentliche Arbeit
// erledigt bleTick() im loop()-Task. (Ursache eines Absturzes beim ersten
// echten Handy-Verbindungstest 06.08.2026 — vorher war BLE zum Handy nie
// unter Last getestet.)
static volatile bool pendingConnectSwitchToSegeln = false;
static volatile bool screenNeedsRefresh = false;
static volatile int pendingHapticCode = -1;

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
    screenNeedsRefresh = true; // NICHT refreshActiveScreen() direkt: läuft im NimBLE-Task, nicht im loop()-Task (siehe Kommentar bei der Deklaration)
}

static void onBatteryNotify(NimBLERemoteCharacteristic *c, uint8_t *data, size_t len, bool isNotify) {
    if (len < 1) return;
    phoneBatteryPct = data[0];
    screenNeedsRefresh = true;
}

static void onHapticNotify(NimBLERemoteCharacteristic *c, uint8_t *data, size_t len, bool isNotify) {
    if (len < 1) return;
    pendingHapticCode = data[0]; // triggerHaptic() fasst I2C an -> erst im loop()-Task ausführen
}

static void onHomeStatusNotify(NimBLERemoteCharacteristic *c, uint8_t *data, size_t len, bool isNotify) {
    if (len < 3) return;
    uint8_t flags = data[0];
    uint16_t eta = rdU16(data + 1);
    homeData.active = (flags & HOME_FLAG_ACTIVE) != 0;
    homeData.maneuverNeeded = (flags & HOME_FLAG_MANEUVER) != 0;
    homeData.etaMinutes = (eta == 0xFFFF) ? -1 : (int)eta;
    homeData.haveData = true;
    screenNeedsRefresh = true;
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
    screenNeedsRefresh = true;
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
    screenNeedsRefresh = true;
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
// Merkt sich den zuletzt erzwungenen "zeitkritischen" Tab (-1 = keiner) --
// für die Flankenerkennung in autoFocusTick() unten.
static int lastAutoFocusPriorityTab = -1;

static void autoFocusTick() {
    if (appMode != MODE_SEGELN || tabview == nullptr) return;

    int priorityTab = -1; // -1 = gerade kein zeitkritischer Zustand aktiv
    if (raceData.haveData && raceData.maneuverNeeded) {
        priorityTab = 4; // tabManeuver
    } else if (raceData.haveData && raceData.raceState == 1 /* COUNTDOWN */) {
        priorityTab = 3; // tabCountdown
    } else if (homeData.haveData && homeData.active) {
        priorityTab = 2; // tabHome
    }

    int activeTab = (int)lv_tabview_get_tab_act(tabview);

    if (priorityTab != -1) {
        // Zeitkritisch -> IMMER durchsetzen, überschreibt auch manuelle
        // Navigation (Sicherheit/Rechtzeitigkeit geht vor Bedienkomfort).
        if (activeTab != priorityTab) {
            lv_tabview_set_active(tabview, priorityTab, LV_ANIM_ON);
        }
    } else if (lastAutoFocusPriorityTab != -1) {
        // Bugfix 06.08.2026: gerade eben noch zeitkritisch, jetzt nicht mehr
        // -> EINMALIG auf Nav als "ruhigen Standard" zurückfallen (Flanke).
        // Vorher wurde das bei JEDEM Tick erzwungen (~1x/s durch echte
        // BLE-Notifies vom Handy), auch wenn der Nutzer längst manuell zu
        // Wind/Heim/CD/Man/Menu gewechselt hatte -> alle Tabs ausser Nav
        // waren praktisch unbedienbar, sprangen ständig zurück.
        if (activeTab != 0) {
            lv_tabview_set_active(tabview, 0, LV_ANIM_ON);
        }
    }
    // Wenn priorityTab == -1 UND vorher auch schon -1 war: nichts tun, der
    // Nutzer darf frei zwischen allen sechs Tabs navigieren.
    lastAutoFocusPriorityTab = priorityTab;
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
        // NICHT switchToMode() direkt: lv_screen_load() aus dem NimBLE-Task
        // heraus race't gegen lv_timer_handler() im loop()-Task -> Absturz.
        // Nur Flag setzen, bleTick() im loop() erledigt den echten Wechsel.
        pendingConnectSwitchToSegeln = true;
    }
    void onDisconnect(NimBLEClient *pClient, int reason) override {
        Serial.printf("[BLE] Verbindung getrennt (Reason %d)\n", reason);
        bleConnected = false;
        chGps = chBattery = chControl = chHaptic = chHomeStatus = chWind = chRaceStatus = nullptr;
        // Kein sofortiger Rückfall nach Alltag — kurze Dropouts (siehe
        // SEGELN_FALLBACK_GRACE_MS) werden toleriert, appModeTick() im
        // loop() übernimmt den eigentlichen Rückfall nach Ablauf der Frist.
        disconnectAtMs = millis();
        screenNeedsRefresh = true; // s.o.: kein direkter LVGL-Aufruf aus dem NimBLE-Task
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
    if (bleClient != nullptr) {
        // Leck-Fix: bisher wurde bei jedem Reconnect ein neuer Client angelegt,
        // ohne den alten freizugeben (NimBLEDevice::deleteClient() fehlte) —
        // bei wiederholten Verbindungsabbrüchen wächst der Heap unbegrenzt.
        NimBLEDevice::deleteClient(bleClient);
        bleClient = nullptr;
    }
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
        screenNeedsRefresh = true;
    }
    if (!bleConnected && !NimBLEDevice::getScan()->isScanning()) {
        uint32_t now = millis();
        if (now - lastScanAttemptMs > 3000) {
            lastScanAttemptMs = now;
            NimBLEDevice::getScan()->start(2000, false);
        }
    }

    // Alles, was NimBLE-Callbacks (eigener Task!) nur als Flag/Daten
    // hinterlassen haben, wird hier im loop()-Task nachgeholt — sicher für
    // LVGL und I2C (Haptik), siehe Kommentar bei den Flag-Deklarationen.
    if (pendingConnectSwitchToSegeln) {
        pendingConnectSwitchToSegeln = false;
        switchToMode(MODE_SEGELN);
    }
    if (pendingHapticCode >= 0) {
        int code = pendingHapticCode;
        pendingHapticCode = -1;
        triggerHaptic(code);
    }
    if (screenNeedsRefresh) {
        screenNeedsRefresh = false;
        refreshActiveScreen();
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
    // übereinstimmen, sonst hören sich Sender/Empfänger nicht.
    // 869.525 MHz statt des alten Standard-Kanals 868.0 MHz: liegt im
    // Schweizer/EU-Band 869.4-869.65 MHz, das 10% Duty-Cycle erlaubt
    // (statt nur 1% im 868.0-868.6-Band) — bei unserem Sendemuster (30s-
    // Status-Broadcast + sporadische Quick-Messages) unkritisch für 1%,
    // aber die 10%-Erlaubnis gibt deutlich mehr Spielraum, falls das
    // Sendeintervall später mal verkürzt wird. Moderate Bandbreite/SF:
    // Reichweite hat laut Doku Priorität vor Datenrate, unsere Pakete
    // sind winzig (20-28 Byte) und selten.
    // Duty-Cycle/Kanalwahl gegen BAKOM-Vorgaben geprüft (06.08.2026,
    // siehe PROJEKT_STATUS.md) — 869.525 MHz ist die gewählte Frequenz.
    radio.setFrequency(869.525);
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

    // Zeit-Sync fuer die Land-Uhr (siehe LoRaPacket.h-Kommentar). Nur senden,
    // wenn die eigene RTC schon sinnvoll gestellt ist (per bestehendem
    // BLE-Zeit-Sync vom Handy) - PCF85063 startet sonst bei einem
    // Reset-Datum weit vor 2020, das wollen wir der Land-Uhr nicht
    // aufdruecken.
    struct tm rtcNow;
    instance.rtc.getDateTime(&rtcNow);
    if (rtcNow.tm_year + 1900 >= 2020) {
        pkt.timeHour = (uint8_t)rtcNow.tm_hour;
        pkt.timeMinute = (uint8_t)rtcNow.tm_min;
        pkt.timeSecond = (uint8_t)rtcNow.tm_sec;
        pkt.timeDay = (uint8_t)rtcNow.tm_mday;
        pkt.timeMonth = (uint8_t)(rtcNow.tm_mon + 1);
        pkt.timeYearOffset = (uint8_t)(rtcNow.tm_year + 1900 - 2000);
    } else {
        pkt.timeHour = 0xFF; // signalisiert der Land-Uhr "kein Sync verfuegbar"
    }

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
    // Standby-Aufwecken (siehe docs/Erweiterung_Standby_Wecken.md Abschnitt 2):
    // eine eingehende Frage darf nicht unbemerkt bleiben, weil der Screen aus
    // ist - unabhaengig von Geste/Touch aufwecken.
    lv_display_trigger_activity(NULL);
    wakeDisplay();
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
        Serial.printf("[Quick-Msg RX] Frage von Land: seq=%d %s\n", req.sequence, quickMessageRequestText(req));
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
        lv_display_trigger_activity(NULL); // siehe Standby-Abschnitt weiter unten: Knopf zaehlt als Interaktion
    } else if (!down && buttonWasDown) {
        unsigned long heldMs = millis() - buttonDownAtMs;
        if (heldMs >= BUTTON_LONG_PRESS_MS) onButtonLongPress();
        else onButtonShortPress();
    }
    buttonWasDown = down;
}

// ---- Standby: Display-Aus nach 30s Inaktivitaet (siehe docs/Erweiterung_Standby_Wecken.md) ----
// WICHTIG: das ist NICHT dasselbe wie cbShutdown()/instance.sleep() weiter
// unten (echter ESP32-Deep-Sleep, haelt u.a. LoRa/BLE komplett an, nur per
// Knopf/Krone aufweckbar). Hier geht es nur um das Display - LoRa-Empfang,
// Statuslogik, BLE etc. laufen im Hintergrund unveraendert weiter (siehe
// Doku Abschnitt 2: "Restliche Logik laeuft im Hintergrund normal weiter").
// instance.sleepDisplay()/wakeupDisplay() (LilyGoLib) schalten nur die
// AMOLED-Anzeige ab (~10mA laut Doku-Kommentar in LilyGoWatchUltra.h),
// CPU/Sensoren/Funk bleiben aktiv.
//
// "Aktivitaet" = irgendeine LVGL-Eingabe (Touch, hier kaum genutzt) ODER
// lv_display_trigger_activity(NULL), das wir manuell bei Knopfdruck (siehe
// buttonTick() oben) und bei erkannter Handgelenk-Heben-Geste (siehe
// gestureTick() unten) aufrufen - dieselbe LVGL-Inaktivitaetsuhr deckt so
// alle Interaktionsarten einheitlich ab, ohne separate Timer je Quelle.
static bool displayAsleep = false;
static const unsigned long STANDBY_TIMEOUT_MS = 30000;

static void wakeDisplay() {
    if (!displayAsleep) return;
    instance.wakeupDisplay();
    displayAsleep = false;
    Serial.println("[Standby] Display aufgeweckt");
}

static void standbyTick() {
    uint32_t inactiveMs = lv_display_get_inactive_time(NULL);
    if (!displayAsleep && inactiveMs >= STANDBY_TIMEOUT_MS) {
        instance.sleepDisplay();
        displayAsleep = true;
        Serial.println("[Standby] Display nach 30s Inaktivitaet ausgeschaltet (Hintergrundlogik laeuft weiter)");
    } else if (displayAsleep && inactiveMs < STANDBY_TIMEOUT_MS) {
        // Inaktivitaetsuhr wurde zurueckgesetzt (Knopf oder Geste, siehe oben) -> aufwecken
        wakeDisplay();
    }
}

// ---- Gesten-Erkennung: Klio (trainiert) mit Schwellenwert-Fallback ----
// (siehe docs/Erweiterung_Gesten_Training_Klio.md)
//
// Zwei unabhängige Erkennungswege je Geste (JA/NEIN):
//   1. Klio (SensorBHI260AP_Klio) - bevorzugt, SOBALD ein Muster trainiert
//      + in der NVS gespeichert ist (Pattern-ID 1=JA, 2=NEIN, siehe
//      GestureTarget). Trainiert wird NUR per Serial-Kommando (TRAIN JA /
//      TRAIN NEIN), siehe gestureTrainingSerialTick() unten - kein UI auf
//      der Uhr nötig (Doku Abschnitt 2).
//   2. Schwellenwert-Fallback (Pitch/Shake, wie bisher) - bleibt für eine
//      Geste aktiv, SOLANGE für genau diese noch kein Klio-Muster trainiert
//      ist. Sobald trainiert, übernimmt Klio komplett (kein Doppel-Trigger).
// API-Namen (SensorXYZ/SensorQuaternion/.enable()/.hasUpdated() sowie die
// komplette SensorBHI260AP_Klio-Klasse: begin(), setState(), learning(),
// recognition(), getLearnPattern(), writePattern(), setLearningCallback(),
// setRecognitionCallback()) sind gegen den SensorLib-Quelltext und die
// Klio-Beispiel-Sketches (BHI260AP_Klio_{Recognition,Selflearning}) im
// SensorLib-Repo geprüft, keine Vermutung.
#ifdef USING_BHI260_SENSOR
static SensorXYZ gestureAccel(SensorBHI260AP::ACCEL_PASSTHROUGH, instance.sensor);
static SensorQuaternion gestureQuat(instance.sensor);
static SensorBHI260AP_Klio klio(instance.sensor);
static bool bhi260Online = false;
static bool klioOnline = false;

// Klio-Pattern-IDs fuer JA/NEIN. Bewusst PLAIN uint8_t-Konstanten statt
// enum class: Arduino generiert Funktionsprototypen automatisch VOR den
// eigenen Typdefinitionen im .ino (Einfuegepunkt liegt vor jeglichem Code
// des Sketches) - ein eigener enum-class-Typ als Funktionsparameter fuehrt
// dort zu "was not declared in this scope", weil der generierte Prototyp
// den Typ noch nicht kennt. Mit einfachen Konstanten (wie auch von der
// Klio-API selbst verwendet, die durchgehend mit uint8_t pattern_id
// arbeitet) tritt das Problem nicht auf.
static const uint8_t GESTURE_ID_JA = 1;
static const uint8_t GESTURE_ID_NEIN = 2;
static const uint16_t KLIO_PATTERN_BUF_SIZE = 252; // wie im SensorLib-Klio-Beispiel

// Ob für JA/NEIN bereits ein Klio-Muster trainiert+geladen ist. Index 0=JA,
// 1=NEIN. Solange false: Schwellenwert-Fallback bleibt für genau diese
// Geste maßgeblich (siehe gestureTick()).
static bool klioPatternTrained[2] = {false, false};

// Laufender Trainingszustand - ausschließlich per Serial-Kommando gesteuert.
static bool trainingActive = false;
static uint8_t trainingTarget = GESTURE_ID_JA; // GESTURE_ID_JA oder GESTURE_ID_NEIN
static unsigned long trainingStartedMs = 0;
static const unsigned long TRAINING_TIMEOUT_MS = 60000; // Abbruch, falls nie "fertig" gemeldet

static Preferences gesturePrefs; // NVS-Namespace "klio" (Muster überleben Neustart)

// Lokale Platzhalter-Schwellenwerte für den Fallback (sensor-/einheiten-
// abhängig, siehe QuickMessages.h-Kommentar - deshalb hier und nicht dort
// definiert). ACHTUNG: der bisher hier notierte Messwert "-30°" vom
// 05.08.2026 stammt vermutlich NICHT aus dieser Firmware - USING_BHI260_SENSOR
// war zu dem Zeitpunkt nachweislich nie gesetzt (siehe Fix-Kommentar beim
// #define oben), der Gestencode lief also nur als Stub. Vor dem nächsten
// Wassertest neu verifizieren, nicht blind übernehmen. Ohnehin nur noch
// Fallback, sobald Klio für die jeweilige Geste trainiert ist.
static const float GESTURE_TILT_TARGET_ANGLE_DEG = -30.0f; // TODO: weiter kalibrieren
static const float GESTURE_TILT_TOLERANCE_DEG = 20.0f;     // TODO: weiter kalibrieren
static const float GESTURE_SHAKE_MIN_AMPLITUDE = 8.0f;    // m/s^2, TODO: auf dem Wasser kalibrieren

struct ShakeDetectorState {
    int8_t lastSign = 0;
    uint8_t reversals = 0;
    unsigned long windowStartMs = 0;
} shakeDetector;

// ---- Klio: Persistenz (Muster überleben Neustart, siehe Doku Abschnitt 4) ----
// Klio selbst vergisst gelernte Muster bei Stromverlust (läuft im RAM des
// Sensor-Chips) - deshalb Rohdaten zusätzlich in der ESP32-NVS ablegen und
// bei jedem Boot per writePattern() zurück in den Sensor schreiben.
static void restoreRecognitionAfterTraining() {
    uint8_t patternIds[2];
    uint8_t count = 0;
    if (klioPatternTrained[0]) patternIds[count++] = GESTURE_ID_JA;
    if (klioPatternTrained[1]) patternIds[count++] = GESTURE_ID_NEIN;
    if (count > 0) {
        klio.recognition(patternIds, count);
    } else {
        klio.setState(false, false, false, false); // weder Lernen noch Erkennen
    }
}

static void restoreKlioPatterns() {
    gesturePrefs.begin("klio", true); // read-only
    uint8_t buf[KLIO_PATTERN_BUF_SIZE];
    const char *keys[2] = {"ja", "nein"};
    for (uint8_t i = 0; i < 2; i++) {
        size_t len = gesturePrefs.getBytesLength(keys[i]);
        if (len == 0 || len > sizeof(buf)) continue;
        gesturePrefs.getBytes(keys[i], buf, len);
        uint8_t patternId = i + 1; // 1=JA, 2=NEIN
        if (klio.writePattern(patternId, buf, (uint16_t)len)) {
            klioPatternTrained[i] = true;
            Serial.printf("[Klio] Muster '%s' aus NVS geladen (%u Bytes)\n", keys[i], (unsigned)len);
        } else {
            Serial.printf("[Klio] Muster '%s' aus NVS konnte nicht geschrieben werden: %s\n", keys[i], klio.errorToString());
        }
    }
    gesturePrefs.end();
    if (klioPatternTrained[0] || klioPatternTrained[1]) {
        restoreRecognitionAfterTraining();
        Serial.println("[Klio] Erkennung aktiv fuer gespeicherte Muster.");
    } else {
        Serial.println("[Klio] Keine gespeicherten Muster - Schwellenwert-Fallback bleibt fuer JA/NEIN aktiv, bis per 'TRAIN JA'/'TRAIN NEIN' trainiert wurde.");
    }
}

static void finalizeGestureTraining(int learnIndex) {
    uint8_t patternBuf[KLIO_PATTERN_BUF_SIZE];
    uint16_t patternSize = sizeof(patternBuf);
    if (!klio.getLearnPattern(patternBuf, &patternSize)) {
        Serial.printf("[Klio] Gelerntes Muster konnte nicht gelesen werden: %s\n", klio.errorToString());
        trainingActive = false;
        restoreRecognitionAfterTraining();
        return;
    }

    uint8_t targetId = trainingTarget; // 1=JA, 2=NEIN
    const char *targetName = (trainingTarget == GESTURE_ID_JA) ? "JA" : "NEIN";
    const char *prefKey = (trainingTarget == GESTURE_ID_JA) ? "ja" : "nein";

    if (!klio.writePattern(targetId, patternBuf, patternSize)) {
        Serial.println("[Klio] Muster schreiben fehlgeschlagen!");
        trainingActive = false;
        restoreRecognitionAfterTraining();
        return;
    }

    gesturePrefs.begin("klio", false); // read-write
    gesturePrefs.putBytes(prefKey, patternBuf, patternSize);
    gesturePrefs.end();

    klioPatternTrained[targetId - 1] = true;
    trainingActive = false;

    Serial.printf("[Klio] Muster '%s' fertig trainiert und gespeichert (%u Bytes, ueberlebt Neustart).\n",
                  targetName, (unsigned)patternSize);
    Serial.println("[Klio] Kurzer Erkennungstest: Geste jetzt ein paar Mal wiederholen - Ergebnis erscheint hier als '[Klio] Erkannt: ...'.");
    restoreRecognitionAfterTraining();
}

static void onKlioLearningEvent(SensorBHI260AP_Klio::LeaningChangeReason reason, uint32_t progress, int learn_index, void *user_data) {
    if (!trainingActive) return; // Events ausserhalb eines aktiven Trainings ignorieren
    switch (reason) {
        case SensorBHI260AP_Klio::LEARNING_PROGRESSING:
            Serial.printf("[Klio] Trainingsfortschritt: %lu%%\n", (unsigned long)progress);
            break;
        case SensorBHI260AP_Klio::LEARNING_NO_REPETITIVE_ACTIVITY:
            Serial.println("[Klio] Bewegung war nicht wiederholend genug - Geste gleichmaessiger wiederholen und weitermachen.");
            break;
        case SensorBHI260AP_Klio::LEARNING_NO_SIGNIFICANT:
            Serial.println("[Klio] Zu wenig Bewegung erkannt - Geste deutlicher ausfuehren und weitermachen.");
            break;
    }
    if (learn_index != SensorBHI260AP_Klio::INVALID_LEARNING_INDEX) {
        finalizeGestureTraining(learn_index);
    }
}

// Wird bei JEDEM erkannten Klio-Muster aufgerufen, auch ausserhalb einer
// offenen Frage - Logging bewusst ungegatet, damit der Fehlalarm-Test aus
// docs/Erweiterung_Gesten_Training_Klio.md Abschnitt 3 (Wende/Halse/
// Trapez-Ein-Aushaken sollen NICHTS auslösen, aber im Serial-Log sichtbar
// sein, falls doch) auswertbar ist. Die eigentliche Aktion (Quick-Message-
// Antwort senden) bleibt wie beim Schwellenwert-Fallback hinter
// haveIncomingQuestion gated.
static void onKlioRecognitionEvent(uint8_t pattern_id, float count, void *user_data) {
    Serial.printf("[Klio] Erkannt: Pattern=%u Count=%.1f (Frage offen: %s)\n",
                  pattern_id, count, haveIncomingQuestion ? "ja" : "nein");
    if (!haveIncomingQuestion) return;
    if (pattern_id == GESTURE_ID_JA) onGestureTiltUp();
    else if (pattern_id == GESTURE_ID_NEIN) onGestureShake();
}

static void startGestureTraining(uint8_t target) {
    if (!bhi260Online) {
        Serial.println("[Klio] BHI260AP nicht online - Training nicht moeglich.");
        return;
    }
    if (!klioOnline) {
        Serial.println("[Klio] Klio-Sensor nicht initialisiert - Training nicht moeglich (siehe Boot-Log).");
        return;
    }
    trainingActive = true;
    trainingTarget = target;
    trainingStartedMs = millis();
    Serial.printf("\n[Klio] === Training '%s' gestartet ===\n", target == GESTURE_ID_JA ? "JA" : "NEIN");
    Serial.println("[Klio] Geste jetzt mehrfach gleichmaessig wiederholen (Kalibrierungs-Protokoll siehe");
    Serial.println("[Klio] docs/Erweiterung_Gesten_Training_Klio.md Abschnitt 3). Fortschritt erscheint hier.");
    Serial.println("[Klio] 'TRAIN CANCEL' bricht ab.");
    // learning_reset=true: fruehere, unvollstaendige Lernversuche verwerfen.
    // recognition_enable=false: waehrend des Trainings keine alten Muster
    // erkennen (vermeidet Ja/Nein-Antworten mitten im Training).
    klio.setState(/*learning_enable=*/true, /*learning_reset=*/true,
                  /*recognition_enable=*/false, /*recognition_reset=*/false);
}

static void cancelGestureTraining() {
    if (!trainingActive) {
        Serial.println("[Klio] Kein Training aktiv.");
        return;
    }
    Serial.println("[Klio] Training abgebrochen.");
    trainingActive = false;
    restoreRecognitionAfterTraining();
}

static void printGestureTrainingStatus() {
    Serial.printf("[Klio] JA trainiert: %s | NEIN trainiert: %s | Training aktiv: %s\n",
                  klioPatternTrained[0] ? "ja" : "nein",
                  klioPatternTrained[1] ? "ja" : "nein",
                  trainingActive ? (trainingTarget == GESTURE_ID_JA ? "JA" : "NEIN") : "nein");
}

static void resetGesturePattern(uint8_t target) {
    const char *prefKey = (target == GESTURE_ID_JA) ? "ja" : "nein";
    gesturePrefs.begin("klio", false);
    gesturePrefs.remove(prefKey);
    gesturePrefs.end();
    klioPatternTrained[target - 1] = false;
    Serial.printf("[Klio] Gespeichertes Muster '%s' geloescht - Schwellenwert-Fallback greift wieder, bis neu trainiert wird.\n",
                  target == GESTURE_ID_JA ? "JA" : "NEIN");
    restoreRecognitionAfterTraining();
}

// ---- Serial-Kommandos fuer den Kalibrierungslauf (siehe Doku Abschnitt 2) ----
// Bewusst simpler Zeilen-Parser, kein UI auf der Uhr noetig. Kommandos:
//   TRAIN JA / TRAIN NEIN   - Trainingslauf fuer diese Geste starten
//   TRAIN CANCEL            - laufendes Training abbrechen
//   TRAIN STATUS            - aktuellen Trainingsstand ausgeben
//   TRAIN RESET JA / NEIN   - gespeichertes Muster loeschen (neu trainieren)
static void gestureTrainingSerialTick() {
    if (trainingActive && (millis() - trainingStartedMs) > TRAINING_TIMEOUT_MS) {
        Serial.println("[Klio] Training-Timeout (60s ohne Ergebnis) - abgebrochen. 'TRAIN JA'/'TRAIN NEIN' erneut eintippen.");
        cancelGestureTraining();
    }

    if (!Serial.available()) return;
    String line = Serial.readStringUntil('\n');
    line.trim();
    line.toUpperCase();
    if (line.length() == 0) return;

    if (line == "TRAIN JA") {
        startGestureTraining(GESTURE_ID_JA);
    } else if (line == "TRAIN NEIN") {
        startGestureTraining(GESTURE_ID_NEIN);
    } else if (line == "TRAIN CANCEL") {
        cancelGestureTraining();
    } else if (line == "TRAIN STATUS") {
        printGestureTrainingStatus();
    } else if (line == "TRAIN RESET JA") {
        resetGesturePattern(GESTURE_ID_JA);
    } else if (line == "TRAIN RESET NEIN") {
        resetGesturePattern(GESTURE_ID_NEIN);
    } else if (line.startsWith("TRAIN")) {
        Serial.println("[Klio] Unbekanntes Kommando. Verfuegbar: TRAIN JA, TRAIN NEIN, TRAIN CANCEL, TRAIN STATUS, TRAIN RESET JA, TRAIN RESET NEIN");
    }
}

static void setupGestureSensor() {
    if (!(instance.getDeviceProbe() & HW_BHI260AP_ONLINE)) {
        Serial.println("[Gesten] BHI260AP nicht online - Gestenerkennung deaktiviert, Taster bleibt Fallback");
        bhi260Online = false;
        return;
    }
    bhi260Online = true;

    // Klio-Firmware ZUERST laden (ersetzt die von LilyGoLib in instance.begin()
    // bereits hochgeladene GPIO-Firmware, siehe Kommentar beim
    // BOSCH_BHI260_KLIO-Define oben) - erst DANACH virtuelle Sensoren
    // aktivieren, sonst würde enable() noch gegen die alte Firmware laufen.
    bool klioFirmwareOk = instance.sensor.uploadFirmware(bosch_firmware_image, bosch_firmware_size, false);
    if (!klioFirmwareOk) {
        Serial.printf("[Klio] Firmware-Upload fehlgeschlagen (%s) - Klio bleibt deaktiviert, Schwellenwert-Fallback laeuft weiter.\n",
                      instance.sensor.getError());
    }

    Serial.println("[Gesten] BHI260AP online, Sensoren aktiviert"); // TODO(Test-Debug)
    float sampleRate = 50.0;       // reicht für Handgelenk-Gesten, spart Strom ggü. 100Hz
    uint32_t reportLatencyMs = 0;
    gestureAccel.enable(sampleRate, reportLatencyMs);
    gestureQuat.enable(sampleRate, reportLatencyMs);

    if (!klioFirmwareOk) {
        klioOnline = false;
        return;
    }
    if (!klio.begin()) {
        Serial.println("[Klio] Initialisierung fehlgeschlagen - Klio bleibt deaktiviert, Schwellenwert-Fallback laeuft weiter.");
        klioOnline = false;
        return;
    }
    klioOnline = true;
    Serial.printf("[Klio] Online, max. %u Muster gleichzeitig moeglich.\n", klio.getMaxPatterns());
    klio.setLearningCallback(onKlioLearningEvent, nullptr);
    klio.setRecognitionCallback(onKlioRecognitionEvent, nullptr);
    klio.enable(sampleRate, reportLatencyMs);
    restoreKlioPatterns();
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
            Serial.printf("[Gesten] Pitch=%.1f Roll=%.1f Heading=%.1f (Fallback-Ziel JA: %.0f +-%.0f, Fallback aktiv: %s)\n",
                          pitch, gestureQuat.getRoll(), gestureQuat.getHeading(),
                          GESTURE_TILT_TARGET_ANGLE_DEG, GESTURE_TILT_TOLERANCE_DEG,
                          klioPatternTrained[0] ? "nein, Klio uebernimmt JA" : "ja");
        }
        bool tiltDetected = fabsf(pitch - GESTURE_TILT_TARGET_ANGLE_DEG) <= GESTURE_TILT_TOLERANCE_DEG;

        // Standby-Aufwecken (siehe docs/Erweiterung_Standby_Wecken.md): dieselbe
        // Handgelenk-Heben-Erkennung wie fuer die JA-Antwort, aber UNGEGATET
        // (auch ohne offene Frage) - LilyGoLib/BHI260AP bringt laut Doku keine
        // fertige "Wrist Tilt to Wake"-Funktion mit (anders als S3/BMA423, siehe
        // dortiges enableTiltIRQ()), deshalb Wiederverwendung dieser einfacheren,
        // nicht trainierten Pitch-Schwelle statt eines eigenen Klio-Musters.
        if (tiltDetected) {
            lv_display_trigger_activity(NULL);
            if (displayAsleep) wakeDisplay();
        }

        // Nur auswerten, solange fuer JA noch KEIN Klio-Muster trainiert ist -
        // sonst uebernimmt onKlioRecognitionEvent() komplett (kein Doppel-Trigger).
        if (!klioPatternTrained[0] && haveIncomingQuestion && tiltDetected) {
            onGestureTiltUp();
            lastGestureLogMs = millis();
            return;
        }
    }

    if (gestureAccel.hasUpdated()) {
        float ax = gestureAccel.getX();
        if (doLog) {
            Serial.printf("[Gesten] Accel X=%.2f Y=%.2f Z=%.2f (Fallback-Schwelle: %.1f, Fallback aktiv: %s)\n",
                          ax, gestureAccel.getY(), gestureAccel.getZ(), GESTURE_SHAKE_MIN_AMPLITUDE,
                          klioPatternTrained[1] ? "nein, Klio uebernimmt NEIN" : "ja");
            lastGestureLogMs = millis();
        }

        if (klioPatternTrained[1]) return; // NEIN laeuft jetzt komplett ueber Klio
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
static void gestureTrainingSerialTick() {}
#endif

// ============================================================================
// LVGL-UI: Tabview mit 6 Screens, ausschliesslich Touch-Bedienung
// ============================================================================

lv_obj_t *tabview; // nicht static: siehe extern-Vorwärtsdeklaration weiter oben
static lv_obj_t *tabNav, *tabWind, *tabHome, *tabCountdown, *tabManeuver, *tabMenu;

// Statusleiste (oben, immer sichtbar)
static lv_obj_t *lblBleStatus;
static lv_obj_t *lblPhoneBattery;
static lv_obj_t *lblStatusClock; // Bugfix 06.08.2026: RTC wird per BLE synchronisiert,
                                  // war aber im Segeln-Modus nirgends sichtbar (nur der
                                  // grosse Alltags-Screen-Clock, der beim Verbinden
                                  // verschwindet) -- deshalb hier zusätzlich in der
                                  // Statusleiste, die auf JEDEM Screen sichtbar ist.

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
    struct tm timeinfo;
    instance.rtc.getDateTime(&timeinfo);
    lv_label_set_text_fmt(lblStatusClock, "%02d:%02d", timeinfo.tm_hour, timeinfo.tm_min);
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
                                   quickMessageRequestText(incomingRequest));
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
    lblStatusClock = lv_label_create(lv_layer_top());
    lv_obj_set_style_text_font(lblStatusClock, &lv_font_montserrat_20, 0);
    lv_obj_align(lblStatusClock, LV_ALIGN_TOP_MID, 0, 10);

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
    gestureTrainingSerialTick();
    standbyTick();

    lv_timer_handler();
    delay(5);
}
