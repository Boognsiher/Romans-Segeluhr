/**
 * Segeluhr_TWatch_S3.ino
 *
 * Echte Uhr-Firmware (kein Tester!) für die LilyGO T-Watch S3, als
 * Vorbereitung/Machbarkeitsnachweis für die spätere LilyGO T-Watch Ultra.
 * Rolle gemäss BLE_Protokoll.md: die Uhr ist BLE CENTRAL, das Handy ist
 * GATT-Server (Peripheral). Bedienung ausschliesslich per Touchscreen
 * (kein Taster, siehe docs/Erweiterung_TWatch_S3_Firmware.md).
 *
 * Hardware-Abstraktion über LilyGoLib (Display/Touch/LVGL/DRV2605-Haptik/
 * AXP2101-Power), BLE-Central-Rolle über NimBLE-Arduino (wie beim
 * bisherigen ESP32-Tester).
 *
 * Benötigte Bibliotheken (Arduino-Bibliotheksverwalter):
 *   - "LilyGoLib" (Xinyuan-LilyGO)   -> https://github.com/Xinyuan-LilyGO/LilyGoLib
 *   - "lvgl" (wird von LilyGoLib als Abhängigkeit verlangt, siehe deren README)
 *   - "NimBLE-Arduino" (h2zero)
 *
 * Board-Einstellung in der Arduino-IDE: "LilyGo T-Watch-S3" (nach
 * Installation des LilyGO-Boardpakets bzw. gemäss LilyGoLib-README).
 *
 * WICHTIG (Ehrlichkeit): Ich kann hier keinen ESP32-Compiler laufen lassen.
 * Die API-Aufrufe (instance.begin(), instance.drv.*, instance.pmu.*,
 * beginLvglHelper) sind gegen die aktuellen LilyGoLib-Beispiel-Sketches
 * (helloworld.ino, Vibrate_Basic.ino, PowerManageMonitor.ino) geprüft.
 * Die exakten DRV2605-Effekt-IDs für die 8 Haptik-Muster sind sinnvolle
 * Annäherungen an die Timings aus VibrationPatterns.kt — beim ersten Test
 * evtl. Feintuning nötig (siehe HAPTIC-EFFECT-Tabelle unten). Gradle-/
 * Compile-Fehler gerne zurückschicken.
 */

#include <Arduino.h>
#include <LilyGoLib.h>
#include <LV_Helper.h>
#include <NimBLEDevice.h>

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
// BLE-Central (NimBLE)
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

void triggerHaptic(int code);   // Vorwärtsdeklaration
void refreshActiveScreen();     // Vorwärtsdeklaration
extern lv_obj_t *tabview;       // Vorwärtsdeklaration (Definition weiter unten, LVGL-UI-Sektion)

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

void refreshActiveScreen() {
    statusBarUpdate();
    navScreenUpdate();
    windScreenUpdate();
    homeScreenUpdate();
    countdownScreenUpdate();
    maneuverScreenUpdate();
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

static lv_obj_t *addMenuButton(lv_obj_t *parent, const char *label, lv_event_cb_t cb, void *userData = nullptr) {
    lv_obj_t *btn = lv_button_create(parent);
    lv_obj_set_width(btn, LV_PCT(90));
    lv_obj_add_event_cb(btn, cb, LV_EVENT_CLICKED, userData);
    lv_obj_t *lbl = lv_label_create(btn);
    lv_label_set_text(lbl, label);
    lv_obj_center(lbl);
    return btn;
}

static void buildMenuTab(lv_obj_t *parent) {
    lv_obj_set_flex_flow(parent, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_row(parent, 6, 0);

    lv_obj_t *sub1 = lv_label_create(parent);
    lv_label_set_text(sub1, "-- Countdown --");
    addMenuButton(parent, "Start", cbCountdownStart);
    addMenuButton(parent, "Reset", cbCountdownReset);
    addMenuButton(parent, "Sync naechste Minute", cbCountdownSync);

    lv_obj_t *sub2 = lv_label_create(parent);
    lv_label_set_text(sub2, "-- Wind --");
    addMenuButton(parent, "Kalibrierung starten", cbWindCalStart);
    addMenuButton(parent, "Kalibrierung abbrechen", cbWindCalAbort);

    lv_obj_t *sub3 = lv_label_create(parent);
    lv_label_set_text(sub3, "-- Training --");
    addMenuButton(parent, "Aus", cbTrainOff);
    addMenuButton(parent, "Nur Wende", cbTrainTack);
    addMenuButton(parent, "Nur Halse", cbTrainJibe);
    addMenuButton(parent, "Race (2 Bojen)", cbTrainRace);

    lv_obj_t *sub4 = lv_label_create(parent);
    lv_label_set_text(sub4, "-- Wegpunkte (an akt. Position) --");
    addMenuButton(parent, "Boje 1 setzen", cbSetWaypoint, (void *)(intptr_t)WP_BUOY1);
    addMenuButton(parent, "Boje 2 setzen", cbSetWaypoint, (void *)(intptr_t)WP_BUOY2);
    addMenuButton(parent, "Ziel setzen", cbSetWaypoint, (void *)(intptr_t)WP_TARGET);
    addMenuButton(parent, "Home setzen", cbSetWaypoint, (void *)(intptr_t)WP_HOME);
    addMenuButton(parent, "Comp.-Marke 1 setzen", cbSetWaypoint, (void *)(intptr_t)WP_COMPETITION_MARK1);
    addMenuButton(parent, "Comp.-Marke 2 setzen", cbSetWaypoint, (void *)(intptr_t)WP_COMPETITION_MARK2);
    addMenuButton(parent, "Alle Bojen loeschen", cbClearWaypoint, (void *)(intptr_t)WP_BUOY1);

    lv_obj_t *sub5 = lv_label_create(parent);
    lv_label_set_text(sub5, "-- Sonstiges --");
    addMenuButton(parent, "Heimweg an/aus", cbHomeToggle);
    addMenuButton(parent, "Wettfahrt beenden", cbCompetitionEnd);
    addMenuButton(parent, "Manoever-Log loeschen", cbClearLog);
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

    tabview = lv_tabview_create(screenSegeln);
    lv_tabview_set_tab_bar_position(tabview, LV_DIR_BOTTOM);
    lv_tabview_set_tab_bar_size(tabview, 34);

    tabNav       = lv_tabview_add_tab(tabview, "Nav");
    tabWind      = lv_tabview_add_tab(tabview, "Wind");
    tabHome      = lv_tabview_add_tab(tabview, "Heim");
    tabCountdown = lv_tabview_add_tab(tabview, "CD");
    tabManeuver  = lv_tabview_add_tab(tabview, "Man");
    tabMenu      = lv_tabview_add_tab(tabview, "Menu");

    // -- Nav-Tab --
    arcCompass = lv_arc_create(tabNav);
    lv_arc_set_rotation(arcCompass, 270);
    lv_arc_set_bg_angles(arcCompass, 0, 360);
    lv_arc_set_range(arcCompass, 0, 359);
    lv_obj_set_size(arcCompass, 150, 150);
    lv_obj_align(arcCompass, LV_ALIGN_TOP_MID, 0, 10);
    lv_obj_remove_style(arcCompass, NULL, LV_PART_KNOB); // rein informativ, nicht bedienbar
    lv_obj_clear_flag(arcCompass, LV_OBJ_FLAG_CLICKABLE);

    lblCogSog = lv_label_create(tabNav);
    lv_obj_set_style_text_align(lblCogSog, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_align(lblCogSog, LV_ALIGN_CENTER, 0, 10);

    lblGpsDetail = lv_label_create(tabNav);
    lv_obj_align(lblGpsDetail, LV_ALIGN_BOTTOM_MID, 0, -6);

    // -- Wind-Tab --
    lblWindDir = lv_label_create(tabWind);
    lv_obj_set_style_text_font(lblWindDir, &lv_font_montserrat_28, 0);
    lv_obj_align(lblWindDir, LV_ALIGN_CENTER, 0, -10);
    lblWindTrend = lv_label_create(tabWind);
    lv_obj_align(lblWindTrend, LV_ALIGN_CENTER, 0, 30);

    // -- Heimweg-Tab --
    lblHomeActive = lv_label_create(tabHome);
    lv_obj_set_style_text_align(lblHomeActive, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_align(lblHomeActive, LV_ALIGN_TOP_MID, 0, 20);
    lblHomeManeuver = lv_label_create(tabHome);
    lv_obj_align(lblHomeManeuver, LV_ALIGN_CENTER, 0, 0);
    lblHomeEta = lv_label_create(tabHome);
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

    lv_obj_t *tv = lv_tabview_create(screenAlltag);
    lv_tabview_set_tab_bar_position(tv, LV_DIR_BOTTOM);
    lv_tabview_set_tab_bar_size(tv, 34);

    lv_obj_t *tabClock = lv_tabview_add_tab(tv, "Uhr");
    lv_obj_t *tabStopwatch = lv_tabview_add_tab(tv, "Timer");
    lv_obj_t *tabBattery = lv_tabview_add_tab(tv, "Akku");
    lv_obj_t *tabSettings = lv_tabview_add_tab(tv, "Setup");

    // -- Uhrzeit-Tab --
    lblClockBig = lv_label_create(tabClock);
    lv_obj_set_style_text_font(lblClockBig, &lv_font_montserrat_28, 0);
    lv_obj_align(lblClockBig, LV_ALIGN_CENTER, 0, -10);
    lblClockDate = lv_label_create(tabClock);
    lv_obj_align(lblClockDate, LV_ALIGN_CENTER, 0, 30);

    // -- Stoppuhr-Tab --
    lblStopwatch = lv_label_create(tabStopwatch);
    lv_obj_set_style_text_font(lblStopwatch, &lv_font_montserrat_28, 0);
    lv_label_set_text(lblStopwatch, "00:00.0");
    lv_obj_align(lblStopwatch, LV_ALIGN_TOP_MID, 0, 20);
    lv_obj_t *btnToggle = lv_button_create(tabStopwatch);
    lv_obj_align(btnToggle, LV_ALIGN_CENTER, 0, 10);
    lv_obj_add_event_cb(btnToggle, cbStopwatchToggle, LV_EVENT_CLICKED, NULL);
    lv_obj_t *lblToggle = lv_label_create(btnToggle);
    lv_label_set_text(lblToggle, "Start/Stop");
    lv_obj_center(lblToggle);
    lv_obj_t *btnReset = lv_button_create(tabStopwatch);
    lv_obj_align(btnReset, LV_ALIGN_BOTTOM_MID, 0, -10);
    lv_obj_add_event_cb(btnReset, cbStopwatchReset, LV_EVENT_CLICKED, NULL);
    lv_obj_t *lblReset = lv_label_create(btnReset);
    lv_label_set_text(lblReset, "Reset");
    lv_obj_center(lblReset);

    // -- Akku-Tab (eigener Uhr-Akku, siehe instance.pmu) --
    lblOwnBattery = lv_label_create(tabBattery);
    lv_obj_center(lblOwnBattery);

    // -- Setup-Tab: manueller Segelmodus-Schalter --
    lv_obj_t *lblSw = lv_label_create(tabSettings);
    lv_label_set_text(lblSw, "Segelmodus erzwingen\n(auch ohne Handy-Verbindung)");
    lv_obj_set_style_text_align(lblSw, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_align(lblSw, LV_ALIGN_TOP_MID, 0, 10);
    swForceSegeln = lv_switch_create(tabSettings);
    lv_obj_align(swForceSegeln, LV_ALIGN_CENTER, 0, 20);
    lv_obj_add_event_cb(swForceSegeln, cbForceSegelnToggle, LV_EVENT_VALUE_CHANGED, NULL);

    lv_obj_t *btnShutdown = lv_button_create(tabSettings);
    lv_obj_set_style_bg_color(btnShutdown, lv_color_hex(0x802020), 0);
    lv_obj_align(btnShutdown, LV_ALIGN_BOTTOM_MID, 0, -10);
    lv_obj_add_event_cb(btnShutdown, cbShutdown, LV_EVENT_CLICKED, NULL);
    lv_obj_t *lblShutdown = lv_label_create(btnShutdown);
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
    // über JEDEM per lv_screen_load() geladenen Screen)
    lblBleStatus = lv_label_create(lv_layer_top());
    lv_obj_align(lblBleStatus, LV_ALIGN_TOP_LEFT, 6, 4);
    lblPhoneBattery = lv_label_create(lv_layer_top());
    lv_obj_align(lblPhoneBattery, LV_ALIGN_TOP_RIGHT, -6, 4);

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

    buildUi();

    NimBLEDevice::init("Segeluhr-Watch");
    NimBLEScan *scan = NimBLEDevice::getScan();
    scan->setScanCallbacks(&scanCallbacks, false);
    scan->setActiveScan(true);
    scan->setInterval(100);
    scan->setWindow(100);
    scan->start(2000, false);
}

void loop() {
    bleTick();
    appModeTick();
    alltagScreenTick();
    lv_timer_handler();
    delay(5);
}
