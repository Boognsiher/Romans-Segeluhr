/**
 * Segeluhr_TWatch_S3.ino
 *
 * "Land-Uhr" — ERSETZT die BLE-Rolle der bisherigen Segeluhr_TWatch_S3.ino
 * auf diesem Gerät komplett. Kein BLE mehr, kein Handy nötig. Stattdessen
 * LoRa-Transceiver: empfängt den 30s-Status-Broadcast der Boots-Uhr
 * (T-Watch Ultra, siehe ../Segeluhr_TWatch_Ultra/Segeluhr_TWatch_Ultra.ino)
 * und tauscht mit ihr Quick-Messages (lockere Ja/Nein-Fragen) aus.
 *
 * (Die alte segeluhr_ble_tester_v2.ino bleibt als unabhängiges Test-Tool
 * bestehen und hat mit dieser Firmware nichts zu tun.)
 *
 * Siehe: docs/Erweiterung_Land_Boot_LoRa_Kommunikation.md
 * Board-Settings: docs/Hardware_Arduino_Settings_LilyGO.md
 * (Board=LilyGo T-Watch-S3, Board Revision=Radio-SX1262, sonst wie gehabt.
 * LoRa-Parameter MÜSSEN exakt mit Segeluhr_TWatch_Ultra.ino übereinstimmen!)
 *
 * WICHTIG (Ehrlichkeit, wie in der Boots-Uhr-Firmware): Ich kann hier
 * keinen ESP32-Compiler laufen lassen. Die LoRa-API-Aufrufe (radio.*) sind
 * gegen die echten LilyGoLib-Beispiel-Sketches geprüft (examples/radio/
 * SX1262/SX126x_{Transmit,Receive}), genau wie bei der Boots-Uhr — kein
 * eigenes SX1262/Module-Objekt nötig, `radio` ist von LilyGoLib bereits
 * global bereitgestellt (inkl. ALDO3-Power-Rail-Init). Touch-Bedienung
 * läuft komplett über normale LVGL-Widgets (Buttons/Switches) statt
 * manueller Touch-Koordinaten-Auswertung — LVGL+Touch-Treiber übernehmen
 * das bereits über beginLvglHelper(instance), exakt wie in der Boots-Uhr-
 * Firmware bei den Menü-Buttons.
 *
 * ABWEICHUNG vom ursprünglichen Skeleton: Bildschirmwechsel läuft über ein
 * lv_tabview (wie bei der Boots-Uhr) statt über eine manuelle
 * LandScreen-Zustandsmaschine mit Rohtouch-Handlern - spart Code, nutzt
 * bewährtes Muster, erfüllt aber weiterhin "Wischen oder Tippen" laut Doku
 * (Tab-Leiste ist antippbar).
 *
 * Physischer Knopf als Fallback (siehe Doku Abschnitt 5) ist auf der S3
 * bewusst NICHT verdrahtet: die S3 hat laut bisherigem Projektstand keinen
 * für App-Logik nutzbaren Taster (nur Touch, siehe Kommentar in der
 * ursprünglichen Segeluhr_TWatch_S3_ALT_zum_Portieren.ino). Für die
 * Land-Uhr ist das unkritisch, da Touch dort ohnehin der primäre und
 * einzige vorgesehene Weg ist (trockene Hände an Land).
 */

#include <Arduino.h>
#include <LilyGoLib.h>
#include <LV_Helper.h>
#include <NimBLEDevice.h>      // nur fuer den optionalen BLE-Fragen-Editor, siehe docs/Erweiterung_S3_BLE_Fragen_Editor.md
#include <Preferences.h>       // NVS-Persistenz eigener Fragen + Wecker-Einstellung
#include <esp_system.h>        // esp_reset_reason(), siehe Reset-Diagnose weiter unten
#include "../../shared/LoRaPacket.h"
#include "../../shared/QuickMessages.h"
#include "../../shared/Crypto.h"

// ============================================================================
// Empfangener Status der Boots-Uhr
// ============================================================================

static LoRaStatusPacket lastPacket;
static unsigned long lastPacketReceivedMillis = 0;
static bool havePacketYet = false;
static bool timeSyncedFromBoat = false; // siehe loraReceiveTick(): einmaliger Auto-Sync statt manuellem Stellen

static bool isSignalLost() {
    if (!havePacketYet) return true;
    return (millis() - lastPacketReceivedMillis) > LORA_SIGNAL_LOST_THRESHOLD_MS;
}

// ============================================================================
// Reset-/Crash-Diagnose (siehe docs/Erweiterung_S3_Reset_Diagnose.md): loggt
// bei JEDEM Boot den ESP32-Reset-Grund (esp_reset_reason()) + Uhrzeit in
// einen 8er-Ringpuffer im NVS. Die PCF8563-RTC laeuft unabhaengig vom
// Neustart weiter (eigene Backup-Versorgung) und ist deshalb schon VOR dem
// LoRa-Zeit-Sync gueltig (falls die Uhr vorher schonmal lief). Hintergrund:
// nach einem Feld-Vorfall (Bildschirm schwarz/unbedienbar, 11.08.2026 von
// Roman beobachtet) war bisher nur per LIVE angeschlossenem Serial Monitor
// erkennbar, WARUM neu gestartet wurde - der genaue Moment laesst sich im
// Feld aber praktisch nie live mitschneiden. Das NVS-Log ueberlebt den
// Vorfall selbst und laesst sich danach in Ruhe auslesen (Menü > Diagnose),
// auch ganz ohne PC in der Naehe.
// ============================================================================

static const int RESET_LOG_SIZE = 8;

static const char *resetReasonText(esp_reset_reason_t reason) {
    switch (reason) {
        case ESP_RST_POWERON:   return "Power-On (Strom neu)";
        case ESP_RST_EXT:       return "Extern (Reset-Pin)";
        case ESP_RST_SW:        return "Software-Neustart";
        case ESP_RST_PANIC:     return "CRASH (Panic/Exception)";
        case ESP_RST_INT_WDT:   return "Watchdog (intern)";
        case ESP_RST_TASK_WDT:  return "Watchdog (Task haengt)";
        case ESP_RST_WDT:       return "Watchdog (sonstig)";
        case ESP_RST_DEEPSLEEP: return "Aufgewacht (Deep-Sleep)";
        case ESP_RST_BROWNOUT:  return "Unterspannung (Brownout)";
        case ESP_RST_SDIO:      return "SDIO";
        case ESP_RST_USB:       return "USB-Reset (z.B. Flashen/RTS)";
        case ESP_RST_JTAG:      return "JTAG-Reset";
        case ESP_RST_EFUSE:     return "eFuse-Fehler";
        case ESP_RST_PWR_GLITCH: return "Spannungs-Glitch";
        case ESP_RST_CPU_LOCKUP: return "CPU LOCKUP (Doppel-Exception)";
        default:                return "Unbekannt";
    }
}

// Direkt in setup() aufgerufen (VOR buildUi(), aber NACH instance.begin(),
// damit die RTC schon lesbar ist) - schreibt den aktuellen Reset-Grund als
// neuen Ringpuffer-Eintrag ins NVS.
static void logResetReasonToNvs() {
    esp_reset_reason_t reason = esp_reset_reason();
    struct tm now;
    instance.rtc.getDateTime(&now);

    char entry[56]; // laengster Text ("CPU LOCKUP (Doppel-Exception)") + Datum/Zeit-Prefix, mit Marge
    if (now.tm_year + 1900 >= 2020) {
        snprintf(entry, sizeof(entry), "%02d.%02d. %02d:%02d - %s",
                 now.tm_mday, now.tm_mon + 1, now.tm_hour, now.tm_min, resetReasonText(reason));
    } else {
        // RTC noch nie gestellt (frisch ab Werk/nach Vollreset) - kein Datum verfuegbar
        snprintf(entry, sizeof(entry), "(Uhr ungestellt) %s", resetReasonText(reason));
    }

    Preferences prefs;
    prefs.begin("diag", false);
    int idx = prefs.getInt("idx", 0) % RESET_LOG_SIZE;
    int count = prefs.getInt("count", 0);
    char key[8];
    snprintf(key, sizeof(key), "e%d", idx);
    prefs.putString(key, entry);
    prefs.putInt("idx", (idx + 1) % RESET_LOG_SIZE);
    if (count < RESET_LOG_SIZE) prefs.putInt("count", count + 1);
    prefs.end();

    Serial.printf("[Diagnose] Reset-Grund geloggt: %s\n", entry);
}

// Liest den Ringpuffer aus dem NVS, neuester Eintrag zuerst - fuer die
// Diagnose-Unteransicht im Menue (siehe buildMenuTab()).
static String getResetLogText() {
    Preferences prefs;
    prefs.begin("diag", true);
    int count = prefs.getInt("count", 0);
    int idx = prefs.getInt("idx", 0);
    if (count == 0) {
        prefs.end();
        return "Noch keine Eintraege.";
    }
    String out;
    for (int i = 0; i < count; i++) {
        int pos = ((idx - 1 - i) % RESET_LOG_SIZE + RESET_LOG_SIZE) % RESET_LOG_SIZE;
        char key[8];
        snprintf(key, sizeof(key), "e%d", pos);
        out += prefs.getString(key, "");
        out += "\n";
    }
    prefs.end();
    return out;
}

static void clearResetLog() {
    Preferences prefs;
    prefs.begin("diag", false);
    prefs.clear();
    prefs.end();
    Serial.println("[Diagnose] Reset-Log geloescht");
}

// ============================================================================
// Stumm-Modus (nur Quick-Message-Benachrichtigungen betroffen, siehe Doku
// Abschnitt 6 - der 30s-Status-Broadcast benachrichtigt ohnehin nie)
// ============================================================================

static bool muteEnabled = false;

// TODO: Ton (zusätzlich zur Vibration bei eingehender Quick-Message) ist
// laut Doku ein offener Punkt ("Ton auf der Land-Uhr: an/aus-Umschaltbar
// machen") - hier bewusst noch nicht implementiert, nur Vibration.

// ============================================================================
// Haptik (DRV2605 über LilyGoLib, wie in der Boots-Uhr-Firmware) — reduziert
// auf das, was die Land-Uhr tatsächlich braucht: Benachrichtigung bei
// eingehender Frage bzw. eingetroffener Antwort. Die vollen 8 Segel-
// Haptik-Codes aus der Boots-Uhr sind hier nicht nötig (kein BLE/Handy).
// ============================================================================

static void playWaveformSeq(std::initializer_list<uint8_t> effects) {
    int slot = 0;
    for (uint8_t e : effects) {
        instance.drv.setWaveform(slot++, e);
        if (slot >= 8) break;
    }
    instance.drv.setWaveform(slot, 0); // Terminator
    instance.drv.run();
}

static void vibrateIncomingQuestion() {
    if (muteEnabled) return;
    playWaveformSeq({5, 5, 5}); // 3x Strong Buzz - gut spürbar, falls Uhr auf dem Tisch liegt
}

static void vibrateAnswerReceived() {
    if (muteEnabled) return;
    playWaveformSeq({1, 1}); // 2x Strong Click - kürzer, weniger dringend als eine neue Frage
}

// ============================================================================
// LoRa (SX1262) — Transceiver: empfängt den 30s-Status der Boots-Uhr UND
// tauscht Quick-Messages in beide Richtungen aus (siehe Kommentar oben zur
// API-Herkunft).
// ============================================================================

static volatile bool loraReceivedFlag = false;
ICACHE_RAM_ATTR static void onLoraPacketReceived(void) {
    loraReceivedFlag = true;
}

void setupLoRaTransceiver() {
    // MUSS exakt mit Segeluhr_TWatch_Ultra.ino übereinstimmen, sonst
    // hören sich Sender/Empfänger nicht (siehe dortige setupLoRaTransceiver()
    // für dieselben Werte/Begründungen). 869.525 MHz (CH/EU-Band
    // 869.4-869.65 MHz, 10% statt 1% Duty-Cycle erlaubt) statt des alten
    // Standard-Kanals 868.0 MHz.
    radio.setFrequency(869.525);
    radio.setBandwidth(125.0);
    radio.setSpreadingFactor(10);
    radio.setCodingRate(6);
    radio.setSyncWord(0xAB);
    radio.setOutputPower(22);
    radio.setCurrentLimit(140);
    radio.setPreambleLength(15);
    radio.setCRC(true);
    radio.setTCXO(3.0);
    radio.setDio2AsRfSwitch();

    radio.setPacketReceivedAction(onLoraPacketReceived);
    radio.startReceive();
}

static void sendEncrypted(const uint8_t *data, size_t len) {
    uint8_t encBuf[CRYPTO_MAX_BUFFER];
    size_t encLen;
    if (encryptLoRaPacket(data, len, encBuf, encLen)) {
        radio.transmit(encBuf, encLen); // blockierend, aber selten (nur bei Quick-Messages)
        radio.startReceive();           // transmit() beendet den Empfangsmodus, wieder aktivieren
        // Direkt nach dem eigenen Senden ein evtl. durch den TX-Vorgang selbst
        // ausgelöstes Empfangs-Flag verwerfen (DIO1 wird von RadioLib sowohl für
        // "Paket empfangen" als auch für "Senden fertig" genutzt - beobachtet
        // beim Test: Gerät empfing sein eigenes gerade gesendetes Paket zurück).
        loraReceivedFlag = false;
    }
}

// ============================================================================
// Quick-Messages (lockere Ja/Nein-Fragen, siehe QuickMessages.h)
// ============================================================================

// Fragen-Auswahl deckt jetzt zwei Quellen ab: die 10 festen QuickQuestion-
// Werte (Index 0..COUNT-1) und eigene, per BLE-Fragen-Editor erstellte
// Fragen (Index COUNT..COUNT+CUSTOM_QUESTION_MAX_COUNT-1), siehe
// docs/Erweiterung_S3_BLE_Fragen_Editor.md.
struct CustomQuestion {
    bool used = false;
    char text[CUSTOM_QUESTION_MAX_LEN] = {0};
};
static CustomQuestion customQuestions[CUSTOM_QUESTION_MAX_COUNT];
static uint8_t customQuestionWriteIdx = 0; // Ringpuffer: naechster Slot, der bei Bedarf ueberschrieben wird
static const uint8_t TOTAL_QUESTION_SLOTS = (uint8_t)QuickQuestion::COUNT + CUSTOM_QUESTION_MAX_COUNT;
static uint8_t selectedQuestionIndex = 0;
static uint8_t quickMsgSequence = 0;

static bool questionIndexValid(uint8_t idx) {
    if (idx < (uint8_t)QuickQuestion::COUNT) return true; // die 10 festen sind immer waehlbar
    uint8_t slot = idx - (uint8_t)QuickQuestion::COUNT;
    return slot < CUSTOM_QUESTION_MAX_COUNT && customQuestions[slot].used;
}
static const char *selectedQuestionText() {
    if (selectedQuestionIndex < (uint8_t)QuickQuestion::COUNT) {
        return quickQuestionText((QuickQuestion)selectedQuestionIndex);
    }
    return customQuestions[selectedQuestionIndex - (uint8_t)QuickQuestion::COUNT].text;
}

// ---- Persistenz eigener Fragen (NVS, ueberlebt Neustart) ----
static void saveCustomQuestionToPrefs(uint8_t slot) {
    Preferences prefs;
    prefs.begin("customq", false);
    char key[4];
    snprintf(key, sizeof(key), "%u", slot);
    if (customQuestions[slot].used) {
        prefs.putBytes(key, customQuestions[slot].text, strlen(customQuestions[slot].text) + 1);
    } else {
        prefs.remove(key);
    }
    prefs.end();
}
static void loadCustomQuestionsFromPrefs() {
    Preferences prefs;
    prefs.begin("customq", true);
    for (uint8_t i = 0; i < CUSTOM_QUESTION_MAX_COUNT; i++) {
        char key[4];
        snprintf(key, sizeof(key), "%u", i);
        size_t len = prefs.getBytesLength(key);
        if (len > 1) { // mehr als nur ein NUL-Byte
            prefs.getBytes(key, customQuestions[i].text, sizeof(customQuestions[i].text));
            customQuestions[i].used = true;
            Serial.printf("[Fragen-Editor] Eigene Frage aus NVS geladen (Slot %u): %s\n", i, customQuestions[i].text);
        }
    }
    prefs.end();
}
// Wird vom BLE-Fragen-Editor bei eingehendem Schreibzugriff aufgerufen
// (siehe FragenEditorWriteCallbacks weiter unten). Ringpuffer: bei vollem
// Speicher wird die aelteste eigene Frage ueberschrieben, kein Fehler noetig
// (siehe Doku Abschnitt 5, "Speicherlimit klein halten").
static void addCustomQuestion(const char *text) {
    CustomQuestion &slot = customQuestions[customQuestionWriteIdx];
    slot.used = true;
    strncpy(slot.text, text, CUSTOM_QUESTION_MAX_LEN - 1);
    slot.text[CUSTOM_QUESTION_MAX_LEN - 1] = '\0';
    saveCustomQuestionToPrefs(customQuestionWriteIdx);
    Serial.printf("[Fragen-Editor] Neue eigene Frage gespeichert (Slot %u): %s\n", customQuestionWriteIdx, slot.text);
    customQuestionWriteIdx = (customQuestionWriteIdx + 1) % CUSTOM_QUESTION_MAX_COUNT;
}

static bool waitingForAnswer = false;
static uint8_t pendingRequestSequence = 0;
static unsigned long pendingRequestSentMillis = 0;

static bool haveIncomingQuestion = false;
static QuickMessageRequest incomingRequest;

static QuickAnswer lastReceivedAnswer = QuickAnswer::PENDING;
static bool showAnswerOverlay = false;
static unsigned long answerOverlayShownMillis = 0;
static const unsigned long ANSWER_OVERLAY_DISPLAY_MS = 4000;

static void updateQuickOverlay(); // Vorwärtsdeklaration (LVGL-UI-Sektion)
static void updateMenuScreen();   // Vorwärtsdeklaration (LVGL-UI-Sektion, zeigt selectedQuestion)

static void sendQuickAnswer(QuickAnswer answer) {
    QuickMessageResponse resp;
    resp.inResponseToSequence = incomingRequest.sequence;
    resp.responder = DeviceId::LAND;
    resp.answer = answer;
    sendEncrypted((uint8_t *)&resp, sizeof(resp));
    haveIncomingQuestion = false;
    Serial.printf("[Quick-Msg TX] Antwort gesendet: inResponseTo=%d %s\n", resp.inResponseToSequence, quickAnswerText(answer));
    updateQuickOverlay();
}

// ---- Standby: Display-Aus nach 30s Inaktivitaet (siehe docs/Erweiterung_Standby_Wecken.md) ----
// WICHTIG: nur das Display geht aus - LoRa-Empfang/Statuslogik laufen im
// Hintergrund unveraendert weiter (Doku Abschnitt 2). Aufwecken hier per
// Touch (LVGL-Eingabeaktivitaet laeuft automatisch mit, S3 ist touch-
// primaer, siehe Doku "Land-Uhr: nur Touch, keine Geste") ODER per
// Handgelenk-Heben-Geste - dafuer bringt der BMA423 (anders als der
// BHI260AP der Ultra) eine fertige Hardware-Tilt-Erkennung mit
// (SensorBMA423::enableTiltIRQ()/isTilt()), die LilyGoLib fuer die S3
// bereits vollstaendig konfiguriert (instance.begin() -> initSensor(),
// siehe LilyGoWatchS3.cpp) und als SENSOR_TILT_DETECTED-Event ueber das
// eingebaute Event-System (LilyGoEventManage) meldet - kein eigener
// Schwellenwert-Code noetig (anders als bei der Ultra, die kein
// vergleichbares BHI260AP-Standardfeature hat).
static bool displayAsleep = false;
static const unsigned long STANDBY_TIMEOUT_MS = 30000;

// Bugfix 11.08.2026: instance.sleepDisplay()/wakeupDisplay() sind fuer die S3
// in der gepinnten LilyGoLib (LilyGoWatchS3.cpp, Klasse LilyGoWatch2022)
// AUSKOMMENTIERTE LEER-STUBS ("// LilyGoDispSPI::sleep();" - kein Aufruf,
// tut nichts):
//   void LilyGoWatch2022::sleepDisplay()   { /* LilyGoDispSPI::sleep(); */ }
//   void LilyGoWatch2022::wakeupDisplay()  { /* LilyGoDispSPI::wakeup(); */ }
// Unser Standby-Code rief bisher NUR diese beiden Funktionen auf - loggte
// "Display aufgeweckt"/"ausgeschaltet", ohne dass am Backlight/Panel real
// etwas passierte. Deshalb hier zusaetzlich direkt per instance.setBrightness()
// steuern (LilyGoWatch2022::setBrightness() ruft echt LilyGoDispSPI::setBrightness()
// auf, siehe LilyGoWatchS3.cpp) - die Stub-Aufrufe bleiben trotzdem drin,
// falls eine kuenftige LilyGoLib-Version sie doch befuellt.
static void wakeDisplay() {
    if (!displayAsleep) return;
    instance.wakeupDisplay();
    instance.setBrightness(DEVICE_MAX_BRIGHTNESS_LEVEL);
    displayAsleep = false;
    Serial.println("[Standby] Display aufgeweckt");
}

static void standbyTick() {
    uint32_t inactiveMs = lv_display_get_inactive_time(NULL);
    if (!displayAsleep && inactiveMs >= STANDBY_TIMEOUT_MS) {
        instance.sleepDisplay();
        instance.setBrightness(0);
        displayAsleep = true;
        Serial.println("[Standby] Display nach 30s Inaktivitaet ausgeschaltet (LoRa-Empfang laeuft weiter)");
    } else if (displayAsleep && inactiveMs < STANDBY_TIMEOUT_MS) {
        wakeDisplay();
    }
}

// Reagiert auf Sensor-Events von LilyGoLib (u.a. Tilt vom BMA423, siehe
// instance.onEvent()-Registrierung in setup()). Nur Tilt ist hier relevant -
// andere BMA423-Features (Schrittzaehler etc., LilyGoLib aktiviert sie
// bereits pauschal mit) sind Teil von docs/Erweiterung_S3_Alltagsfunktionen.md,
// dort noch zu verkabeln.
static void onDeviceSensorEvent(DeviceEvent_t event, void *params, void *user_data) {
    if (event != SENSOR_EVENT) return;
    if (instance.getSensorEventType(params) != SENSOR_TILT_DETECTED) return;
    lv_display_trigger_activity(NULL);
    if (displayAsleep) wakeDisplay();
}

// Anders als bei der Boots-Uhr gibt es hier KEINE Auto-Regatta-Antwort -
// die Land-Uhr beantwortet nie automatisch, der/die Crew soll die Frage
// immer sehen (siehe Doku Abschnitt 5, Auto-Antwort ist boots-spezifisch).
static void handleIncomingQuickMessageRequest(const QuickMessageRequest &req) {
    haveIncomingQuestion = true;
    incomingRequest = req;
    Serial.printf("[Quick-Msg RX] Frage vom Boot: seq=%d %s\n", req.sequence, quickMessageRequestText(req));
    vibrateIncomingQuestion();
    updateQuickOverlay();
    // Standby-Aufwecken (siehe docs/Erweiterung_Standby_Wecken.md Abschnitt 2):
    // eine eingehende Frage darf nicht unbemerkt bleiben, weil der Screen aus ist.
    lv_display_trigger_activity(NULL);
    wakeDisplay();
}

// Touch-Buttons "JA"/"NEIN" im Overlay - primärer und einziger Weg auf der
// Land-Uhr (siehe Doku: "Land-Uhr (S3): nur Touch, keine Geste")
static void cbAnswerJa(lv_event_t *e) { sendQuickAnswer(QuickAnswer::JA); }
static void cbAnswerNein(lv_event_t *e) { sendQuickAnswer(QuickAnswer::NEIN); }

// Fragen-Browser im Menü-Tab: nächste Frage / senden. Blättert über die 10
// festen UND alle aktuell gespeicherten eigenen Fragen, überspringt leere
// (noch nicht belegte) Custom-Slots.
static void cbQuickNext(lv_event_t *e) {
    uint8_t next = selectedQuestionIndex;
    for (uint8_t tries = 0; tries < TOTAL_QUESTION_SLOTS; tries++) {
        next = (next + 1) % TOTAL_QUESTION_SLOTS;
        if (questionIndexValid(next)) break;
    }
    selectedQuestionIndex = next;
    updateMenuScreen();
}
static void cbQuickSend(lv_event_t *e) {
    QuickMessageRequest req;
    req.sequence = quickMsgSequence++;
    req.sender = DeviceId::LAND;
    if (selectedQuestionIndex < (uint8_t)QuickQuestion::COUNT) {
        req.question = (QuickQuestion)selectedQuestionIndex;
        req.isCustom = 0;
    } else {
        req.isCustom = 1;
        const char *text = customQuestions[selectedQuestionIndex - (uint8_t)QuickQuestion::COUNT].text;
        strncpy(req.customText, text, CUSTOM_QUESTION_MAX_LEN - 1);
        req.customText[CUSTOM_QUESTION_MAX_LEN - 1] = '\0';
    }
    sendEncrypted((uint8_t *)&req, sizeof(req));
    waitingForAnswer = true;
    pendingRequestSequence = req.sequence;
    pendingRequestSentMillis = millis();
    Serial.printf("[Quick-Msg TX] Frage gesendet: seq=%d %s\n", req.sequence, quickMessageRequestText(req));
}

// ============================================================================
// BLE-Fragen-Editor (siehe docs/Erweiterung_S3_BLE_Fragen_Editor.md) -
// bewusste Abweichung von der Entscheidung "S3 hat kein BLE mehr": hier
// gezielt wieder eingeführt, aber standardmäßig AUS und nur per Menü-
// Toggle einschaltbar (Akku sparen, "S3 ist einfach/lorabasiert"-Idee
// weitgehend erhalten). Eigener, komplett unabhängiger GATT-Service -
// NICHT derselbe wie der alte Handy-zu-Uhr-Service aus der ursprünglichen
// Architektur (die gibt es auf der S3 nicht mehr, nur noch auf der Ultra).
// Kein PIN/Bestätigung nötig (siehe Doku Abschnitt 5: "BLE ist ja
// standardmäßig aus" reicht als Zugriffsschutz für diesen Anwendungsfall).
// ============================================================================

#define FRAGEN_EDITOR_SERVICE_UUID "7a6e0001-b5a3-f393-e0a9-e50e24dcca9e"
#define FRAGEN_EDITOR_CHAR_UUID    "7a6e0002-b5a3-f393-e0a9-e50e24dcca9e"

// Boot-Position fuers Handy an Land (siehe docs/Erweiterung_Landuhr_Kartenansicht.md).
// Bewusst dieselbe Service/Ein-Aus-Schalter wie der Fragen-Editor statt
// eines zweiten GATT-Servers - beides haengt ohnehin am selben "BLE an"-
// Zustand, ein zweiter Server waere nur unnoetig doppelte Verwaltung.
#define POSITION_CHAR_UUID         "7a6e0003-b5a3-f393-e0a9-e50e24dcca9e"

static bool bleEditorEnabled = false;
static lv_obj_t *swBleEditor; // BLE-Fragen-Editor Ein/Aus, siehe cbBleEditorToggle()
static NimBLECharacteristic *pPositionChar = nullptr; // nullptr wenn BLE aus, siehe stopFragenEditorBle()

// Payload von POSITION_CHAR_UUID (NOTIFY), 10 Byte, little-endian (ESP32-
// nativ) - Android-Seite siehe ble/LandUhrClient.kt in der App:
//   int32 latE7, int32 lonE7, uint8 gpsValidFix, uint8 sequence
#pragma pack(push, 1)
struct BlePositionPayload {
    int32_t latE7;
    int32_t lonE7;
    uint8_t gpsValidFix;
    uint8_t sequence; // = lastPacket.sequence, damit die App neue Updates erkennt
};
#pragma pack(pop)

// Empfängt den Frage-Text von der Web-Bluetooth-Seite (einfaches Textfeld +
// "Senden", siehe Doku Abschnitt 2) und legt ihn als neue eigene Frage ab.
class FragenEditorWriteCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *pCharacteristic, NimBLEConnInfo &connInfo) override {
        std::string value = pCharacteristic->getValue();
        if (value.empty()) return;
        addCustomQuestion(value.c_str()); // schneidet bei CUSTOM_QUESTION_MAX_LEN-1 ab, falls zu lang
        updateMenuScreen(); // Fragen-Vorschau aktualisieren, falls Screen gerade offen ist
    }
};
static FragenEditorWriteCallbacks fragenEditorWriteCallbacks;

static void startFragenEditorBle() {
    NimBLEDevice::init("Segeluhr-Fragen-Editor");
    NimBLEServer *pServer = NimBLEDevice::createServer();
    NimBLEService *pService = pServer->createService(FRAGEN_EDITOR_SERVICE_UUID);
    NimBLECharacteristic *pChar = pService->createCharacteristic(FRAGEN_EDITOR_CHAR_UUID, NIMBLE_PROPERTY::WRITE);
    pChar->setCallbacks(&fragenEditorWriteCallbacks);
    pPositionChar = pService->createCharacteristic(POSITION_CHAR_UUID, NIMBLE_PROPERTY::NOTIFY | NIMBLE_PROPERTY::READ);
    // Direkt beim Einschalten schon den letzten bekannten Stand eintragen
    // (falls schon vor dem BLE-Einschalten ein LoRa-Paket da war) - sonst
    // liest die App beim Verbinden erstmal 0/kein Fix, bis das naechste
    // LoRa-Paket (bis zu 30s) reinkommt.
    if (havePacketYet) {
        BlePositionPayload initial{lastPacket.latE7, lastPacket.lonE7, lastPacket.gpsValidFix, lastPacket.sequence};
        pPositionChar->setValue((uint8_t *)&initial, sizeof(initial));
    }
    pServer->start();
    pServer->getAdvertising()->addServiceUUID(FRAGEN_EDITOR_SERVICE_UUID);
    pServer->getAdvertising()->start();
    Serial.println("[Fragen-Editor] BLE gestartet, sichtbar als 'Segeluhr-Fragen-Editor'");
}

static void stopFragenEditorBle() {
    NimBLEDevice::deinit(true); // clearAll=true: Server/Service/Characteristic komplett abbauen
    pPositionChar = nullptr; // Objekt wurde durch deinit() zerstoert, Pointer nicht mehr gueltig
    Serial.println("[Fragen-Editor] BLE gestoppt");
}

static void cbBleEditorToggle(lv_event_t *e) {
    bleEditorEnabled = lv_obj_has_state(swBleEditor, LV_STATE_CHECKED);
    if (bleEditorEnabled) startFragenEditorBle();
    else stopFragenEditorBle();
}

static void checkQuickMessageTimeout() {
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

// ============================================================================
// LVGL-UI: 3 Tabs (Haupt/Detail/Menü), reine Touch-Bedienung
// ============================================================================

static lv_obj_t *tabMain, *tabDetail, *tabMenu;

// Haupt-Tab
static lv_obj_t *lblStatusBig;
static lv_obj_t *lblClockBig;
static lv_obj_t *lblConnIndicator;
// NEU (12.08.2026, Roman-Wunschliste "vor dem naechsten Test"): S3 zeigte
// bisher nirgends die EIGENE Batterie (lblDetailBattery auf tabDetail ist
// die des BOOTS, per LoRa empfangen) - gleiche PMU-API wie auf der Ultra
// bereits genutzt (instance.pmu.getBatteryPercent(), siehe dort).
// Hardware-Test-Feedback 12.08. Abend: auf tabMain sass sie genau unter
// lblConnIndicator und ueberlappte dessen (teils zweizeiligen) Text -
// deshalb jetzt auf tabDetail verschoben (unten deklariert, letztes Label
// in dessen Flex-Column, siehe buildUi()).
static lv_obj_t *lblOwnBattery;

// Detail-Tab
static lv_obj_t *lblDetailDistance;
static lv_obj_t *lblDetailSog;
static lv_obj_t *lblDetailBattery;
static lv_obj_t *lblDetailWind;
static lv_obj_t *lblDetailPacketInfo;

// Menü-Tab: Icon-Grid als Standardansicht + Unteransichten (Fragen, Alltag-
// Grid, sowie Wecker/Stoppuhr/Schritte darunter), siehe buildMenuTab(). Alle
// sind Vollbild-Geschwister innerhalb des Menü-Tabs, per Hidden-Flag
// umgeschaltet (showMenuScreen()). Taschenlampe ist bewusst KEINE dieser
// Screens, sondern ein Overlay auf layer_top (siehe Alltagsfunktionen unten)
// - "Verlassen durch Antippen" ueberall auf dem Bildschirm laesst sich so
// einfacher umsetzen als innerhalb der Tab-Struktur.
static lv_obj_t *menuGridScreen, *menuFragenScreen, *menuAlltagScreen;
static lv_obj_t *menuWeckerScreen, *menuStoppuhrScreen, *menuSchritteScreen;
static lv_obj_t *menuDiagnoseScreen; // Reset-Log, siehe logResetReasonToNvs()/getResetLogText()
static lv_obj_t *lblDiagnoseLog;
static lv_obj_t *btnMuteTile, *lblMuteTileText;
static lv_obj_t *lblQuickSelected;

// Quick-Message-Overlay (layer_top, über allen Tabs)
static lv_obj_t *lblQuickOverlay = nullptr;
static lv_obj_t *btnAnswerJa = nullptr;
static lv_obj_t *btnAnswerNein = nullptr;

// ============================================================================
// Alltagsfunktionen (siehe docs/Erweiterung_S3_Alltagsfunktionen.md):
// Wecker, Stoppuhr, Schrittzähler, Taschenlampe. Macht die Land-Uhr auch
// ausserhalb des Segel-Kontexts als normale Armbanduhr sinnvoll tragbar.
// ============================================================================

// ---- Wecker (Software-Vergleich statt Hardware-Alarm-Register) ----
// ABWEICHUNG von der urspruenglichen Doku-Annahme: die reale S3 nutzt laut
// LilyGoLib (LilyGoWatchS3.h) einen PCF8563, nicht wie dokumentiert einen
// PCF85063A - der PCF8563 hat zwar ebenfalls ein Alarm-Register
// (SensorPCF8563::setAlarm/enableAlarm), aber LilyGoLib verdrahtet dessen
// Interrupt-Pin fuer die S3 nur als Deep-Sleep-Wakeup-Quelle, nicht als
// laufende Interrupt-Quelle im Normalbetrieb (RTC_EVENT_INTERRUPT wuerde nie
// feuern). Einfacher und genauso zuverlaessig: die RTC wird ohnehin jede
// Sekunde fuer die Uhrzeit-Anzeige gelesen (siehe mainScreenUpdate()) - der
// Wecker vergleicht bei dieser Gelegenheit einfach mit, kein Zugriff auf
// Alarm-Register noetig.
static bool alarmEnabled = false;
static uint8_t alarmHour = 7;
static uint8_t alarmMinute = 0;
static bool alarmArmedThisMinute = true; // verhindert Re-Trigger innerhalb derselben Minute
static bool alarmRinging = false;
static unsigned long lastAlarmVibrateMs = 0;
static const unsigned long ALARM_VIBRATE_INTERVAL_MS = 2000; // wiederholt vibrieren, bis "Stopp" gedrueckt wird
static lv_obj_t *lblAlarmTimePreview;
static lv_obj_t *btnAlarmToggleTile, *lblAlarmToggleText;
static lv_obj_t *alarmRingingOverlay; // layer_top, siehe buildUi()

// ---- Stoppuhr (identisches Muster wie auf der Ultra, siehe dortiges
// alltagScreenTick()/cbStopwatch{Toggle,Reset}) ----
static lv_obj_t *lblStopwatch;
static uint32_t stopwatchStartMs = 0;
static uint32_t stopwatchElapsedMs = 0;
static bool stopwatchRunning = false;

// ---- Schrittzähler (BMA423-Pedometer, von LilyGoLib bereits aktiviert -
// siehe initSensor() in LilyGoWatchS3.cpp: enableFeature(FEATURE_STEP_CNTR),
// setStepCounterWatermark(1)) ----
static lv_obj_t *lblSteps;
// Bekannte Einschränkung (siehe Doku): Mitternachts-Reset nur waehrend die
// Uhr durchlaeuft - kein NVS-Tracking des letzten Reset-Tages ueber einen
// Neustart hinweg, "-1" heisst "seit Boot noch kein Tag gesehen".
static int8_t stepsLastSeenDay = -1;

// ---- Taschenlampe (Overlay auf layer_top, siehe buildUi()) ----
static bool flashlightActive = false;
static unsigned long flashlightActivatedMs = 0;
static const unsigned long FLASHLIGHT_TIMEOUT_MS = 90000; // 90s, siehe Doku "60-120s"
static lv_obj_t *flashlightOverlay;

static void mainScreenUpdate() {
    if (isSignalLost()) {
        if (havePacketYet) {
            unsigned long secsAgo = (millis() - lastPacketReceivedMillis) / 1000;
            lv_label_set_text_fmt(lblStatusBig, "KEIN SIGNAL\n(seit %lus)", secsAgo);
        } else {
            lv_label_set_text(lblStatusBig, "KEIN SIGNAL\n(noch nie)");
        }
        lv_obj_set_style_text_color(lblStatusBig, lv_color_hex(0xD03030), 0);
        lv_label_set_text(lblConnIndicator, "○ kein Empfang");
        lv_obj_set_style_text_color(lblConnIndicator, lv_color_hex(0xD03030), 0);
    } else {
        lv_label_set_text(lblStatusBig, boatStateToDisplayText(lastPacket.state));
        lv_obj_set_style_text_color(lblStatusBig, lv_color_hex(0x30D060), 0);
        unsigned long secsAgo = (millis() - lastPacketReceivedMillis) / 1000;
        lv_label_set_text_fmt(lblConnIndicator, "● Empfang (%lus)", secsAgo);
        lv_obj_set_style_text_color(lblConnIndicator, lv_color_hex(0x30D060), 0);
    }

    struct tm timeinfo;
    instance.rtc.getDateTime(&timeinfo);
    lv_label_set_text_fmt(lblClockBig, "%02d:%02d", timeinfo.tm_hour, timeinfo.tm_min);

    int ownBatt = instance.pmu.getBatteryPercent();
    lv_label_set_text_fmt(lblOwnBattery, "Akku Uhr: %d%%", ownBatt);

    alarmCheckTick(timeinfo);
    stepsAutoResetCheck(timeinfo);
}

// Aktualisiert die Anzeigen der Alltag-Unteransichten (Wecker-Vorschau,
// Stoppuhr, Schritte) - unabhaengig davon, ob sie gerade sichtbar sind
// (billig genug, gleiche Konvention wie updateMenuScreen()).
static void updateAlltagScreens() {
    if (lblAlarmTimePreview != nullptr) {
        lv_label_set_text_fmt(lblAlarmTimePreview, "%02d:%02d", alarmHour, alarmMinute);
    }
    if (stopwatchRunning && lblStopwatch != nullptr) {
        uint32_t elapsed = millis() - stopwatchStartMs;
        lv_label_set_text_fmt(lblStopwatch, "%02lu:%02lu.%01lu",
                              (elapsed / 60000UL), (elapsed / 1000UL) % 60UL, (elapsed / 100UL) % 10UL);
    }
    if (lblSteps != nullptr) {
        lv_label_set_text_fmt(lblSteps, "Schritte heute:\n%u", (unsigned)instance.sensor.getPedometerCounter());
    }
}

static void detailScreenUpdate() {
    if (!havePacketYet) {
        lv_label_set_text(lblDetailDistance, "Distanz: --");
        lv_label_set_text(lblDetailSog, "SOG: --");
        lv_label_set_text(lblDetailBattery, "Akku Boot: --");
        lv_label_set_text(lblDetailWind, "Wind: --");
        lv_label_set_text(lblDetailPacketInfo, "noch kein Paket empfangen");
        return;
    }
    if (lastPacket.distanceRemainingM >= 0) {
        lv_label_set_text_fmt(lblDetailDistance, "Distanz: %d m", lastPacket.distanceRemainingM);
    } else {
        lv_label_set_text(lblDetailDistance, "Distanz: unbekannt");
    }
    lv_label_set_text_fmt(lblDetailSog, "SOG: %.1f kn", lastPacket.sogCkn / 100.0);
    lv_label_set_text_fmt(lblDetailBattery, "Akku Boot: %d%%", lastPacket.boatBatteryPercent);
    if (lastPacket.windDirDeg >= 0) {
        lv_label_set_text_fmt(lblDetailWind, "Wind: %d°", lastPacket.windDirDeg);
    } else {
        lv_label_set_text(lblDetailWind, "Wind: unbekannt");
    }
    unsigned long ageMs = millis() - lastPacketReceivedMillis;
    // distanceTraveledM (10.08.2026 neu im Paket, siehe LoRaPacket.h) hier
    // statt eines eigenen Labels mit reingepackt - spart eine weitere
    // Zeile auf dem kleinen Display, gleiches Muster wie schon Paket-
    // Nummer/Alter.
    lv_label_set_text_fmt(lblDetailPacketInfo, "Paket #%d, %lus alt, bisher %.1f km",
                          lastPacket.sequence, ageMs / 1000, lastPacket.distanceTraveledM / 1000.0);
}

static void updateMenuScreen() {
    if (lblQuickSelected != nullptr) {
        lv_label_set_text_fmt(lblQuickSelected, "Frage: %s", selectedQuestionText());
    }
}

static void updateQuickOverlay() {
    if (lblQuickOverlay == nullptr) return;
    if (haveIncomingQuestion) {
        lv_label_set_text_fmt(lblQuickOverlay, "FRAGE VOM BOOT:\n%s", quickMessageRequestText(incomingRequest));
        lv_obj_clear_flag(lblQuickOverlay, LV_OBJ_FLAG_HIDDEN);
        lv_obj_clear_flag(btnAnswerJa, LV_OBJ_FLAG_HIDDEN);
        lv_obj_clear_flag(btnAnswerNein, LV_OBJ_FLAG_HIDDEN);
    } else if (showAnswerOverlay) {
        lv_label_set_text_fmt(lblQuickOverlay, "ANTWORT:\n%s", quickAnswerText(lastReceivedAnswer));
        lv_obj_clear_flag(lblQuickOverlay, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(btnAnswerJa, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(btnAnswerNein, LV_OBJ_FLAG_HIDDEN);
    } else {
        lv_obj_add_flag(lblQuickOverlay, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(btnAnswerJa, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(btnAnswerNein, LV_OBJ_FLAG_HIDDEN);
    }
}

static void refreshActiveScreen() {
    mainScreenUpdate();
    detailScreenUpdate();
    updateAlltagScreens();
}

// ---- Wecker: Software-Vergleich statt Hardware-Alarm-Register (siehe
// ausführlichen Begründungs-Kommentar bei den Wecker-Globals oben) ----
static void alarmCheckTick(const struct tm &now) {
    if (!alarmEnabled) {
        alarmArmedThisMinute = true;
        return;
    }
    bool matches = (now.tm_hour == alarmHour && now.tm_min == alarmMinute);
    if (matches && alarmArmedThisMinute) {
        alarmArmedThisMinute = false;
        alarmRinging = true;
        lastAlarmVibrateMs = 0; // sofort beim naechsten alarmRingingTick() vibrieren
        lv_obj_clear_flag(alarmRingingOverlay, LV_OBJ_FLAG_HIDDEN);
        lv_obj_move_foreground(alarmRingingOverlay);
        lv_display_trigger_activity(NULL); // Standby-Aufwecken, siehe Erweiterung_Standby_Wecken.md
        if (displayAsleep) wakeDisplay();
    } else if (!matches) {
        alarmArmedThisMinute = true; // Minute vorbei - naechstes Mal (naechster Tag) wieder scharf
    }
}

static void alarmRingingTick() {
    if (!alarmRinging) return;
    if (millis() - lastAlarmVibrateMs >= ALARM_VIBRATE_INTERVAL_MS) {
        lastAlarmVibrateMs = millis();
        playWaveformSeq({5, 5, 5}); // dieselbe "Strong Buzz"-Sequenz wie vibrateIncomingQuestion()
    }
}

static void cbAlarmStop(lv_event_t *e) {
    alarmRinging = false;
    lv_obj_add_flag(alarmRingingOverlay, LV_OBJ_FLAG_HIDDEN);
}

// Persistenz (NVS): Weckzeit + Ein/Aus-Zustand ueberleben jetzt einen
// Neustart - war beim ersten Alltagsfunktionen-Commit noch offener Punkt.
static void saveAlarmToPrefs() {
    Preferences prefs;
    prefs.begin("alarm", false);
    prefs.putUChar("hour", alarmHour);
    prefs.putUChar("minute", alarmMinute);
    prefs.putBool("enabled", alarmEnabled);
    prefs.end();
}
static void loadAlarmFromPrefs() {
    Preferences prefs;
    prefs.begin("alarm", true);
    alarmHour = prefs.getUChar("hour", alarmHour);
    alarmMinute = prefs.getUChar("minute", alarmMinute);
    alarmEnabled = prefs.getBool("enabled", alarmEnabled);
    prefs.end();
}

static void cbAlarmHourPlus(lv_event_t *e) { alarmHour = (alarmHour + 1) % 24; saveAlarmToPrefs(); }
static void cbAlarmHourMinus(lv_event_t *e) { alarmHour = (alarmHour + 23) % 24; saveAlarmToPrefs(); }
static void cbAlarmMinutePlus(lv_event_t *e) { alarmMinute = (alarmMinute + 1) % 60; saveAlarmToPrefs(); }
static void cbAlarmMinuteMinus(lv_event_t *e) { alarmMinute = (alarmMinute + 59) % 60; saveAlarmToPrefs(); }

static void updateAlarmToggleVisual() {
    lv_label_set_text_fmt(lblAlarmToggleText, "%s\n%s", alarmEnabled ? LV_SYMBOL_BELL : LV_SYMBOL_CLOSE,
                          alarmEnabled ? "An" : "Aus");
    lv_obj_set_style_bg_color(btnAlarmToggleTile, alarmEnabled ? lv_color_hex(0x208030) : lv_color_hex(0x2A2A2A), 0);
}
static void cbAlarmToggle(lv_event_t *e) {
    alarmEnabled = !alarmEnabled;
    alarmArmedThisMinute = true; // frisch scharf, verhindert Sofort-Trigger falls gerade Alarmzeit erreicht ist
    saveAlarmToPrefs();
    updateAlarmToggleVisual();
}

// ---- Stoppuhr (identisches Muster wie auf der Ultra) ----
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

// ---- Schrittzähler ----
static void stepsAutoResetCheck(const struct tm &now) {
    if (stepsLastSeenDay == -1) {
        stepsLastSeenDay = now.tm_mday; // erster Tick nach Boot: nur Basislinie merken, nicht resetten
        return;
    }
    if (now.tm_mday != stepsLastSeenDay) {
        instance.sensor.resetPedometer();
        stepsLastSeenDay = now.tm_mday;
        Serial.println("[Alltag] Schrittzaehler: neuer Tag, automatisch zurueckgesetzt");
    }
}
static void cbStepsReset(lv_event_t *e) {
    instance.sensor.resetPedometer();
    lv_label_set_text(lblSteps, "Schritte heute:\n0");
}

// ---- Taschenlampe (Overlay auf layer_top, siehe buildUi()) ----
static void cbOpenFlashlight(lv_event_t *e) {
    flashlightActive = true;
    flashlightActivatedMs = millis();
    lv_obj_clear_flag(flashlightOverlay, LV_OBJ_FLAG_HIDDEN);
    lv_obj_move_foreground(flashlightOverlay);
}
static void cbCloseFlashlight(lv_event_t *e) {
    flashlightActive = false;
    lv_obj_add_flag(flashlightOverlay, LV_OBJ_FLAG_HIDDEN);
}
static void flashlightTick() {
    if (!flashlightActive) return;
    lv_display_trigger_activity(NULL); // verhindert, dass der normale 30s-Standby waehrend Nutzung dazwischenfunkt
    if (millis() - flashlightActivatedMs > FLASHLIGHT_TIMEOUT_MS) {
        cbCloseFlashlight(nullptr);
    }
}

// ---- Menü-Tab: Icon-Grid (siehe docs/Erweiterung_Land_Boot_LoRa_Kommunikation.md
// Abschnitt 3, Mockup [Stumm][Fragen] / [Alltag] / [Ausschalten]) ----
// "Zeit stellen" gibt es nicht mehr im Menü - die Land-Uhr übernimmt ihre
// Zeit jetzt automatisch aus dem ersten LoRa-Statuspaket (siehe
// loraReceiveTick()/timeSyncedFromBoat), Roman-Entscheidung 06.08.2026.

static void updateMuteTileVisual() {
    lv_label_set_text_fmt(lblMuteTileText, "%s\nStumm", muteEnabled ? LV_SYMBOL_MUTE : LV_SYMBOL_VOLUME_MAX);
    lv_obj_set_style_bg_color(btnMuteTile, muteEnabled ? lv_color_hex(0x802020) : lv_color_hex(0x2A2A2A), 0);
}
static void cbMuteTileToggle(lv_event_t *e) {
    muteEnabled = !muteEnabled;
    updateMuteTileVisual();
}

static void showMenuScreen(lv_obj_t *screen); // Vorwärtsdeklaration, siehe unten

static void cbOpenFragen(lv_event_t *e) { showMenuScreen(menuFragenScreen); }
static void cbOpenAlltag(lv_event_t *e) { showMenuScreen(menuAlltagScreen); }
static void cbMenuBack(lv_event_t *e) { showMenuScreen(menuGridScreen); }
static void cbOpenWecker(lv_event_t *e) { showMenuScreen(menuWeckerScreen); }
static void cbOpenStoppuhr(lv_event_t *e) { showMenuScreen(menuStoppuhrScreen); }
static void cbOpenSchritte(lv_event_t *e) { showMenuScreen(menuSchritteScreen); }
static void cbAlltagBack(lv_event_t *e) { showMenuScreen(menuAlltagScreen); } // von Wecker/Stoppuhr/Schritte zurueck ins Alltag-Grid

// Reset-Diagnose (siehe logResetReasonToNvs()/getResetLogText() weiter oben)
static void cbOpenDiagnose(lv_event_t *e) {
    lv_label_set_text(lblDiagnoseLog, getResetLogText().c_str());
    showMenuScreen(menuDiagnoseScreen);
}
static void cbDiagnoseClear(lv_event_t *e) {
    clearResetLog();
    lv_label_set_text(lblDiagnoseLog, getResetLogText().c_str());
}

/**
 * Deep-Sleep. Aufwachen NUR per Touch, siehe Bugfix-Kommentar unten.
 */
static void cbShutdown(lv_event_t *e) {
    lv_label_set_text(lblStatusBig, "");
    lv_label_set_text(lblClockBig, "Aus - zum Aufwachen\ntippen");
    lv_task_handler();
    delay(300);
    // Feinschliff, damit ein gerade erst quittiertes Touch-Loslass-Event
    // (vom Antippen des "Ausschalten"-Buttons selbst) sicher ausgelesen ist,
    // bevor TP_INT als Aufwach-Quelle scharf geschaltet wird.
    lv_task_handler();
    // 10.08.2026 Bugfix "Ausschalten startet sofort neu": mit den
    // instance.sleep()-Standardquellen (WAKEUP_SRC_POWER_KEY |
    // WAKEUP_SRC_TOUCH_PANEL) wachte die Uhr JEDES Mal sofort wieder auf,
    // auch ohne USB-Kabel und mit unbeschaedigtem Taster (beides gezielt
    // per Diagnose-Build ausgeschlossen: esp_sleep_get_ext1_wakeup_status()
    // zeigte auf Hardware zuverlaessig PMU_INT=1/TP_INT=0). Weiter
    // eingegrenzt: instance.pmu.getIrqStatus() direkt bei Tastendruck (vor
    // instance.sleep()) zeigt 0x0/PKEY_SHORT=0 - der falsche PKEY_SHORT_IRQ
    // entsteht also WAEHREND der sleep()-Sequenz selbst (irgendwo zwischen
    // deren pmu.clearIrqStatus() und esp_deep_sleep_start(), ~4s Fenster mit
    // mehreren Power-Rail-Abschaltungen), nicht durch alten Zustand von
    // vorher. Genauere Stelle wuerde Debug-Ausgaben INNERHALB der gepinnten
    // LilyGoLib (LilyGoWatchS3.cpp) brauchen - bewusst nicht gemacht, siehe
    // CLAUDE.md zu Bibliotheksversionen. Deshalb hier bewusst NUR Touch als
    // Aufwach-Quelle (nachweislich sauber, TP_INT=0), Power-Taste raus.
    // Aufwecken geht also nur noch per Antippen, nicht mehr per Taster -
    // Trade-off in Kauf genommen, da Tippen ohnehin der primaere Weg ist
    // (Touch-only-Bedienung dieser Uhr, siehe CLAUDE.md).
    instance.sleep(WAKEUP_SRC_TOUCH_PANEL);
}

// Feedback nach erstem Hardware-Test: Standard-LVGL-Buttongroesse ist auf
// dem echten Touchscreen zu klein/schmal zum zuverlaessigen Antippen -
// deshalb hier fest auf eine grosszuegige Mindesthoehe + groesseren Font.
static lv_obj_t *addMenuButton(lv_obj_t *parent, const char *label, lv_event_cb_t cb) {
    lv_obj_t *btn = lv_button_create(parent);
    lv_obj_set_width(btn, LV_PCT(94));
    lv_obj_set_height(btn, 64);
    lv_obj_add_event_cb(btn, cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *lbl = lv_label_create(btn);
    lv_obj_set_style_text_font(lbl, &lv_font_montserrat_28, 0);
    lv_label_set_text(lbl, label);
    lv_obj_center(lbl);
    return btn;
}

// Quadratische Icon-Kachel (Symbol + kurzer Text übereinander) fürs neue
// Grid-Menü. widthPct ist relativ zum jeweiligen Eltern-Container (z.B.
// 47 für zwei Kacheln nebeneinander in einer Reihe, 94 für eine volle Reihe).
static lv_obj_t *addIconTile(lv_obj_t *parent, const char *symbol, const char *label, lv_event_cb_t cb, lv_coord_t widthPct) {
    lv_obj_t *btn = lv_button_create(parent);
    lv_obj_set_width(btn, LV_PCT(widthPct));
    lv_obj_set_height(btn, 90);
    lv_obj_add_event_cb(btn, cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *lbl = lv_label_create(btn);
    // Kleiner als der Rest der UI (Hardware-Test-Feedback 06.08.: Schrift in
    // den Kacheln zu gross) - die Kacheln sind mit ~106px deutlich schmaler
    // als die vollbreiten Menü-Buttons, Symbol + Text auf zwei Zeilen
    // braucht hier mehr Zurückhaltung.
    lv_obj_set_style_text_font(lbl, &lv_font_montserrat_20, 0);
    lv_obj_set_style_text_align(lbl, LV_TEXT_ALIGN_CENTER, 0);
    lv_label_set_text_fmt(lbl, "%s\n%s", symbol, label);
    lv_obj_center(lbl);
    return btn;
}

// Kleiner +/- -Button, z.B. zum Einstellen der Weckzeit.
static lv_obj_t *addAdjustButton(lv_obj_t *parent, const char *label, lv_event_cb_t cb) {
    lv_obj_t *btn = lv_button_create(parent);
    lv_obj_set_size(btn, 100, 56); // Breiter als frueher (70px) - vier nebeneinander passten
                                    // nicht auf den 240px-Bildschirm, siehe addRow()-Kommentar
    lv_obj_add_event_cb(btn, cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *lbl = lv_label_create(btn);
    lv_obj_set_style_text_font(lbl, &lv_font_montserrat_20, 0);
    lv_label_set_text(lbl, label);
    lv_obj_center(lbl);
    return btn;
}

// text_font vererbt sich nicht zuverlässig - Section-Header bekommen ihre
// Schrift deshalb hier zentral über einen kleinen Helfer.
static lv_obj_t *addSubHeader(lv_obj_t *parent, const char *text) {
    lv_obj_t *lbl = lv_label_create(parent);
    lv_obj_set_style_text_font(lbl, &lv_font_montserrat_24, 0);
    // Ohne feste Breite waechst ein Label ueber den 240px-Bildschirm hinaus
    // statt umzubrechen (Ursache fuer das Hardware-Test-Feedback 06.08.:
    // "muss nach rechts scrollen") - deshalb Breite + Zeilenumbruch + Zentrierung.
    lv_obj_set_width(lbl, LV_PCT(94));
    lv_label_set_long_mode(lbl, LV_LABEL_LONG_WRAP);
    lv_obj_set_style_text_align(lbl, LV_TEXT_ALIGN_CENTER, 0);
    lv_label_set_text(lbl, text);
    return lbl;
}

// Vollbild-Container für eine der Menü-Unteransichten - randlos, damit
// er optisch mit dem Tab verschmilzt statt als eigene Box aufzufallen.
static lv_obj_t *addMenuScreenContainer(lv_obj_t *parent) {
    lv_obj_t *screen = lv_obj_create(parent);
    lv_obj_set_size(screen, LV_PCT(100), LV_PCT(100));
    lv_obj_set_style_pad_all(screen, 0, 0);
    lv_obj_set_style_border_width(screen, 0, 0);
    lv_obj_set_flex_flow(screen, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_row(screen, 8, 0);
    // Nur senkrecht scrollen (falls Inhalt mal die Bildschirmhoehe
    // ueberschreitet) - seitlich soll es NIE noetig sein (Hardware-Test-
    // Feedback 06.08.: "musste nach rechts scrollen").
    lv_obj_set_scroll_dir(screen, LV_DIR_VER);
    return screen;
}

// Randlose, nicht scrollbare Zeile innerhalb eines Menü-Screens (z.B. zwei
// Icon-Kacheln nebeneinander). Der T-Watch-S3-Bildschirm ist nur 240x240px
// (siehe pins_arduino.h, DISP_WIDTH/DISP_HEIGHT) - Standard-Container-
// Padding/-Rahmen von LVGL frisst davon spürbar was weg und hat vorher zu
// horizontalem Scrollen geführt, obwohl die Prozent-Rechnung an sich
// aufging. widthPct ist relativ zum jeweiligen Eltern-Screen.
static lv_obj_t *addRow(lv_obj_t *parent, lv_coord_t widthPct) {
    lv_obj_t *row = lv_obj_create(parent);
    lv_obj_set_size(row, LV_PCT(widthPct), LV_SIZE_CONTENT);
    lv_obj_set_style_pad_all(row, 0, 0);
    lv_obj_set_style_border_width(row, 0, 0);
    lv_obj_set_style_pad_column(row, 8, 0);
    lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
    lv_obj_clear_flag(row, LV_OBJ_FLAG_SCROLLABLE); // soll nie seitlich scrollen, Inhalt muss reinpassen
    return row;
}

static void showMenuScreen(lv_obj_t *screen) {
    lv_obj_add_flag(menuGridScreen, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(menuFragenScreen, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(menuAlltagScreen, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(menuWeckerScreen, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(menuStoppuhrScreen, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(menuSchritteScreen, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(menuDiagnoseScreen, LV_OBJ_FLAG_HIDDEN);
    lv_obj_clear_flag(screen, LV_OBJ_FLAG_HIDDEN);
}

static void buildMenuTab(lv_obj_t *parent) {
    // Icon-Grid, Fragen- und Alltag-Ansicht sind Vollbild-Geschwister
    // innerhalb des Tabs (kein Layout auf "parent" selbst nötig - Kinder
    // liegen per Default bei (0,0) übereinander, showMenuScreen() blendet
    // per Hidden-Flag um).

    // -- Icon-Grid (Standardansicht) --
    menuGridScreen = addMenuScreenContainer(parent);

    lv_obj_t *row1 = addRow(menuGridScreen, 94);

    btnMuteTile = lv_button_create(row1);
    lv_obj_set_width(btnMuteTile, LV_PCT(47));
    lv_obj_set_height(btnMuteTile, 90);
    lv_obj_add_event_cb(btnMuteTile, cbMuteTileToggle, LV_EVENT_CLICKED, NULL);
    lblMuteTileText = lv_label_create(btnMuteTile);
    lv_obj_set_style_text_font(lblMuteTileText, &lv_font_montserrat_20, 0); // siehe addIconTile()-Kommentar, dieselbe Kachelgroesse
    lv_obj_set_style_text_align(lblMuteTileText, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_center(lblMuteTileText);
    updateMuteTileVisual();

    addIconTile(row1, LV_SYMBOL_ENVELOPE, "Fragen", cbOpenFragen, 47);

    addIconTile(menuGridScreen, LV_SYMBOL_HOME, "Alltag", cbOpenAlltag, 94);

    // Reset-Diagnose (siehe docs/Erweiterung_S3_Reset_Diagnose.md) - eigene
    // Kachel statt im Alltag-Grid, ist kein Alltags-Feature sondern Debug/
    // Fehlersuche.
    addIconTile(menuGridScreen, LV_SYMBOL_SETTINGS, "Diagnose", cbOpenDiagnose, 94);

    lv_obj_t *btnShutdown = addMenuButton(menuGridScreen, "Ausschalten", cbShutdown);
    lv_obj_set_style_bg_color(btnShutdown, lv_color_hex(0x802020), 0);

    // -- Fragen-Unteransicht (vorher: inline im alten Listen-Menü) --
    menuFragenScreen = addMenuScreenContainer(parent);
    addSubHeader(menuFragenScreen, "-- Quick-Message ans Boot --");
    lblQuickSelected = lv_label_create(menuFragenScreen);
    lv_obj_set_style_text_font(lblQuickSelected, &lv_font_montserrat_24, 0);
    lv_obj_set_width(lblQuickSelected, LV_PCT(94)); // eigene Fragen koennen bis zu 31 Zeichen lang sein
    lv_label_set_long_mode(lblQuickSelected, LV_LABEL_LONG_WRAP);
    lv_obj_set_style_text_align(lblQuickSelected, LV_TEXT_ALIGN_CENTER, 0);
    lv_label_set_text(lblQuickSelected, "Frage: ALLES GUT?");
    addMenuButton(menuFragenScreen, "Naechste Frage", cbQuickNext);
    addMenuButton(menuFragenScreen, "Frage senden", cbQuickSend);

    // BLE-Fragen-Editor Ein/Aus (siehe docs/Erweiterung_S3_BLE_Fragen_Editor.md)
    // - Standard AUS, macht die S3 nur bei Bedarf per BLE sichtbar.
    lv_obj_t *bleRow = addRow(menuFragenScreen, 94);
    lv_obj_t *lblBle = lv_label_create(bleRow);
    lv_obj_set_style_text_font(lblBle, &lv_font_montserrat_18, 0);
    lv_label_set_text(lblBle, "BLE-Editor:"); // kurz gehalten, siehe addRow()-Kommentar (240px-Bildschirm)
    swBleEditor = lv_switch_create(bleRow);
    lv_obj_set_style_transform_zoom(swBleEditor, 320, 0);
    lv_obj_add_event_cb(swBleEditor, cbBleEditorToggle, LV_EVENT_VALUE_CHANGED, NULL);

    addMenuButton(menuFragenScreen, LV_SYMBOL_LEFT " Zurueck", cbMenuBack);

    // -- Alltag-Unteransicht: eigenes Icon-Grid [Wecker][Stoppuhr] /
    // [Schritte][Taschenlampe] / [Zurück], siehe
    // docs/Erweiterung_S3_Alltagsfunktionen.md Abschnitt 2 --
    menuAlltagScreen = addMenuScreenContainer(parent);
    addSubHeader(menuAlltagScreen, "-- Alltag --");
    lv_obj_t *alltagRow1 = addRow(menuAlltagScreen, 94);
    addIconTile(alltagRow1, LV_SYMBOL_BELL, "Wecker", cbOpenWecker, 47);
    addIconTile(alltagRow1, LV_SYMBOL_PLAY, "Stoppuhr", cbOpenStoppuhr, 47);
    lv_obj_t *alltagRow2 = addRow(menuAlltagScreen, 94);
    addIconTile(alltagRow2, LV_SYMBOL_GPS, "Schritte", cbOpenSchritte, 47);
    addIconTile(alltagRow2, LV_SYMBOL_EYE_OPEN, "Taschen-\nlampe", cbOpenFlashlight, 47);
    addMenuButton(menuAlltagScreen, LV_SYMBOL_LEFT " Zurueck", cbMenuBack);

    // -- Wecker-Unteransicht --
    menuWeckerScreen = addMenuScreenContainer(parent);
    addSubHeader(menuWeckerScreen, "-- Wecker --");
    lblAlarmTimePreview = lv_label_create(menuWeckerScreen);
    lv_obj_set_style_text_font(lblAlarmTimePreview, &lv_font_montserrat_48, 0);
    lv_label_set_text_fmt(lblAlarmTimePreview, "%02d:%02d", alarmHour, alarmMinute);
    // Zwei Zeilen (Stunde/Minute) statt vier Knöpfe nebeneinander - vier
    // Knöpfe passten nicht nebeneinander auf den 240px-Bildschirm, siehe
    // addRow()-Kommentar.
    lv_obj_t *alarmHourRow = addRow(menuWeckerScreen, 94);
    addAdjustButton(alarmHourRow, "Std-", cbAlarmHourMinus);
    addAdjustButton(alarmHourRow, "Std+", cbAlarmHourPlus);
    lv_obj_t *alarmMinuteRow = addRow(menuWeckerScreen, 94);
    addAdjustButton(alarmMinuteRow, "Min-", cbAlarmMinuteMinus);
    addAdjustButton(alarmMinuteRow, "Min+", cbAlarmMinutePlus);
    btnAlarmToggleTile = lv_button_create(menuWeckerScreen);
    lv_obj_set_width(btnAlarmToggleTile, LV_PCT(94));
    lv_obj_set_height(btnAlarmToggleTile, 90);
    lv_obj_add_event_cb(btnAlarmToggleTile, cbAlarmToggle, LV_EVENT_CLICKED, NULL);
    lblAlarmToggleText = lv_label_create(btnAlarmToggleTile);
    lv_obj_set_style_text_font(lblAlarmToggleText, &lv_font_montserrat_28, 0);
    lv_obj_set_style_text_align(lblAlarmToggleText, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_center(lblAlarmToggleText);
    updateAlarmToggleVisual();
    addMenuButton(menuWeckerScreen, LV_SYMBOL_LEFT " Zurueck", cbAlltagBack);

    // -- Stoppuhr-Unteransicht (identisches Muster wie auf der Ultra) --
    menuStoppuhrScreen = addMenuScreenContainer(parent);
    addSubHeader(menuStoppuhrScreen, "-- Stoppuhr --");
    lblStopwatch = lv_label_create(menuStoppuhrScreen);
    lv_obj_set_style_text_font(lblStopwatch, &lv_font_montserrat_48, 0);
    lv_label_set_text(lblStopwatch, "00:00.0");
    addMenuButton(menuStoppuhrScreen, "Start/Stop", cbStopwatchToggle);
    addMenuButton(menuStoppuhrScreen, "Reset", cbStopwatchReset);
    addMenuButton(menuStoppuhrScreen, LV_SYMBOL_LEFT " Zurueck", cbAlltagBack);

    // -- Schritte-Unteransicht (BMA423-Pedometer, siehe Globals-Kommentar) --
    menuSchritteScreen = addMenuScreenContainer(parent);
    addSubHeader(menuSchritteScreen, "-- Schritte --");
    lblSteps = lv_label_create(menuSchritteScreen);
    lv_obj_set_style_text_font(lblSteps, &lv_font_montserrat_28, 0);
    lv_obj_set_width(lblSteps, LV_PCT(94));
    lv_label_set_long_mode(lblSteps, LV_LABEL_LONG_WRAP);
    lv_obj_set_style_text_align(lblSteps, LV_TEXT_ALIGN_CENTER, 0);
    lv_label_set_text(lblSteps, "Schritte heute:\n0");
    addMenuButton(menuSchritteScreen, "Reset", cbStepsReset);
    addMenuButton(menuSchritteScreen, LV_SYMBOL_LEFT " Zurueck", cbAlltagBack);

    // -- Diagnose-Unteransicht: Reset-Log (siehe logResetReasonToNvs()/
    // getResetLogText() oben, docs/Erweiterung_S3_Reset_Diagnose.md) --
    menuDiagnoseScreen = addMenuScreenContainer(parent);
    addSubHeader(menuDiagnoseScreen, "-- Diagnose: Neustart-Log --");
    lblDiagnoseLog = lv_label_create(menuDiagnoseScreen);
    lv_obj_set_style_text_font(lblDiagnoseLog, &lv_font_montserrat_18, 0);
    lv_obj_set_width(lblDiagnoseLog, LV_PCT(94));
    lv_label_set_long_mode(lblDiagnoseLog, LV_LABEL_LONG_WRAP);
    lv_obj_set_style_text_align(lblDiagnoseLog, LV_TEXT_ALIGN_CENTER, 0);
    lv_label_set_text(lblDiagnoseLog, "Noch keine Eintraege.");
    addMenuButton(menuDiagnoseScreen, "Log loeschen", cbDiagnoseClear);
    addMenuButton(menuDiagnoseScreen, LV_SYMBOL_LEFT " Zurueck", cbMenuBack);

    showMenuScreen(menuGridScreen);
}

static void buildUi() {
    lv_obj_t *tv = lv_tabview_create(lv_screen_active());
    // Feedback nach erstem Hardware-Test: UI generell zu klein. text_font ist
    // in LVGL vererbt - hier auf dem Tabview gesetzt, wirkt auf alle Labels
    // in allen drei Tabs, die keine eigene (groessere) Schrift haben (Uhrzeit/
    // Status-Text behalten ihre eigene extra grosse Schrift).
    lv_obj_set_style_text_font(tv, &lv_font_montserrat_24, 0);
    lv_tabview_set_tab_bar_position(tv, LV_DIR_BOTTOM);
    lv_tabview_set_tab_bar_size(tv, 44);
    lv_obj_set_style_text_font(lv_tabview_get_tab_bar(tv), &lv_font_montserrat_18, 0); // Tab-Bar hat eigene Theme-Schrift

    // Hardware-Test-Feedback 06.08.: Wischen zwischen den Tabs schob den
    // Screen sichtbar seitlich an, obwohl gar kein seitlicher Input noetig
    // ist - Tabs werden ohnehin nur ueber die Tab-Leiste unten gewechselt.
    // lv_tabview blendet Tab-Wechsel intern als horizontales Scrollen des
    // Content-Containers um - hier deaktiviert, lv_tabview_set_active()
    // (von den Tab-Buttons ausgeloest) scrollt weiterhin programmatisch,
    // unabhaengig vom SCROLLABLE-Flag.
    lv_obj_clear_flag(lv_tabview_get_content(tv), LV_OBJ_FLAG_SCROLLABLE);

    tabMain   = lv_tabview_add_tab(tv, "Status");
    tabDetail = lv_tabview_add_tab(tv, "Detail");
    tabMenu   = lv_tabview_add_tab(tv, "Menu");

    // -- Haupt-Tab: grosser Status-Text + Uhrzeit + Verbindungsindikator --
    // Hardware-Test-Feedback 06.08.: "WARTE AUF START" lief ohne Breiten-
    // begrenzung über den 240px-Bildschirmrand hinaus und wurde beidseitig
    // abgeschnitten ("nur ARTE AUF STAR lesbar") - derselbe Fehler wie
    // vorher im Menü, hier nachgezogen. Alle Labels auf Haupt-/Detail-Tab
    // bekommen deshalb jetzt Breite + Zeilenumbruch + Zentrierung.
    lblStatusBig = lv_label_create(tabMain);
    lv_obj_set_style_text_font(lblStatusBig, &lv_font_montserrat_28, 0);
    lv_obj_set_width(lblStatusBig, LV_PCT(94));
    lv_label_set_long_mode(lblStatusBig, LV_LABEL_LONG_WRAP);
    lv_obj_set_style_text_align(lblStatusBig, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_align(lblStatusBig, LV_ALIGN_TOP_MID, 0, 20);

    lblClockBig = lv_label_create(tabMain);
    lv_obj_set_style_text_font(lblClockBig, &lv_font_montserrat_48, 0);
    lv_obj_align(lblClockBig, LV_ALIGN_CENTER, 0, 10);

    // text_font vererbt sich in dieser LVGL-Version NICHT zuverlässig vom
    // Tabview auf Kind-Labels - deshalb ab hier überall explizit gesetzt.
    lblConnIndicator = lv_label_create(tabMain);
    lv_obj_set_style_text_font(lblConnIndicator, &lv_font_montserrat_24, 0);
    lv_obj_set_width(lblConnIndicator, LV_PCT(94));
    lv_label_set_long_mode(lblConnIndicator, LV_LABEL_LONG_WRAP);
    lv_obj_set_style_text_align(lblConnIndicator, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_align(lblConnIndicator, LV_ALIGN_BOTTOM_MID, 0, -10);

    // -- Detail-Tab --
    // 10.08.2026: von fixen Pixel-Koordinaten (y=6/48/90/132, PacketInfo
    // per lv_obj_align_to relativ zu lblDetailWind) auf LVGL-Flex-Column
    // umgestellt. Grund: die Labels bekommen ihren echten Text erst
    // NACHTRAEGLICH per detailScreenUpdate() (Aufruf hier beim Bauen der
    // UI zeigt noch leere/Platzhalter-Labels) - eine einmalige Positions-
    // berechnung beim Setup (ob fix oder relativ per align_to) passt danach
    // nicht mehr zur tatsaechlichen Zeilenzahl/Hoehe, sobald echte Werte
    // (v.a. die seit heute laengere PacketInfo-Zeile mit "bisher X km",
    // haeufig 2-3 Zeilen) reinkommen - auf Hardware als wild ueberlappender
    // Text beobachtet. Flex-Column lässt LVGL bei JEDER Content-Aenderung
    // neu reflowen, dadurch strukturell ausgeschlossen statt neu geraten.
    lv_obj_set_flex_flow(tabDetail, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(tabDetail, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_row(tabDetail, 8, 0);
    lv_obj_set_style_pad_top(tabDetail, 6, 0);
    // tabDetail bleibt scrollbar (Standard, hier nicht deaktiviert wie der
    // Tabview-Content-Container oben) - falls der Text bei vielen Zeilen
    // doch mal ueber den Bildschirm hinausragt, kann man scrollen statt
    // dass es abgeschnitten/ueberlappend wird.

    lblDetailDistance = lv_label_create(tabDetail);
    lv_obj_set_style_text_font(lblDetailDistance, &lv_font_montserrat_28, 0);
    lv_obj_set_width(lblDetailDistance, LV_PCT(94));
    lv_label_set_long_mode(lblDetailDistance, LV_LABEL_LONG_WRAP);
    lv_obj_set_style_text_align(lblDetailDistance, LV_TEXT_ALIGN_CENTER, 0);
    lblDetailSog = lv_label_create(tabDetail);
    lv_obj_set_style_text_font(lblDetailSog, &lv_font_montserrat_28, 0);
    lv_obj_set_width(lblDetailSog, LV_PCT(94));
    lv_label_set_long_mode(lblDetailSog, LV_LABEL_LONG_WRAP);
    lv_obj_set_style_text_align(lblDetailSog, LV_TEXT_ALIGN_CENTER, 0);
    lblDetailBattery = lv_label_create(tabDetail);
    lv_obj_set_style_text_font(lblDetailBattery, &lv_font_montserrat_28, 0);
    lv_obj_set_width(lblDetailBattery, LV_PCT(94));
    lv_label_set_long_mode(lblDetailBattery, LV_LABEL_LONG_WRAP);
    lv_obj_set_style_text_align(lblDetailBattery, LV_TEXT_ALIGN_CENTER, 0);
    lblDetailWind = lv_label_create(tabDetail);
    lv_obj_set_style_text_font(lblDetailWind, &lv_font_montserrat_28, 0);
    lv_obj_set_width(lblDetailWind, LV_PCT(94));
    lv_label_set_long_mode(lblDetailWind, LV_LABEL_LONG_WRAP);
    lv_obj_set_style_text_align(lblDetailWind, LV_TEXT_ALIGN_CENTER, 0);
    lblDetailPacketInfo = lv_label_create(tabDetail);
    lv_obj_set_style_text_font(lblDetailPacketInfo, &lv_font_montserrat_18, 0);
    lv_obj_set_width(lblDetailPacketInfo, LV_PCT(94));
    lv_label_set_long_mode(lblDetailPacketInfo, LV_LABEL_LONG_WRAP);
    lv_obj_set_style_text_align(lblDetailPacketInfo, LV_TEXT_ALIGN_CENTER, 0);

    // Eigene Akku-Anzeige (12.08.2026, siehe lblOwnBattery-Deklaration) -
    // bewusst als letztes Label in dieser Flex-Column, damit sie unten
    // erscheint, unabhaengig von der (teils mehrzeiligen) Laenge der
    // Labels darueber. tabDetail bleibt scrollbar, falls es doch nicht
    // passt.
    lblOwnBattery = lv_label_create(tabDetail);
    lv_obj_set_style_text_font(lblOwnBattery, &lv_font_montserrat_20, 0);
    lv_obj_set_width(lblOwnBattery, LV_PCT(94));
    lv_label_set_long_mode(lblOwnBattery, LV_LABEL_LONG_WRAP);
    lv_obj_set_style_text_align(lblOwnBattery, LV_TEXT_ALIGN_CENTER, 0);

    // -- Menü-Tab --
    buildMenuTab(tabMenu);

    // -- Quick-Message-Overlay (layer_top, über allen Tabs) --
    // Feedback nach erstem Hardware-Test: JA/NEIN-Buttons waren mit
    // Standardgroesse zu schmal zum zuverlaessigen Antippen - jetzt fest
    // gross (140x90), grosser Font, mehr Abstand zueinander.
    lblQuickOverlay = lv_label_create(lv_layer_top());
    lv_obj_set_width(lblQuickOverlay, LV_PCT(90));
    lv_obj_set_style_text_font(lblQuickOverlay, &lv_font_montserrat_28, 0); // layer_top erbt NICHT von tv
    lv_obj_align(lblQuickOverlay, LV_ALIGN_CENTER, 0, -55);
    lv_obj_set_style_text_align(lblQuickOverlay, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_style_bg_color(lblQuickOverlay, lv_color_hex(0x203050), 0);
    lv_obj_set_style_bg_opa(lblQuickOverlay, LV_OPA_90, 0);
    lv_obj_set_style_pad_all(lblQuickOverlay, 10, 0);
    lv_obj_add_flag(lblQuickOverlay, LV_OBJ_FLAG_HIDDEN);

    btnAnswerJa = lv_button_create(lv_layer_top());
    lv_obj_set_size(btnAnswerJa, 140, 90);
    lv_obj_set_style_bg_color(btnAnswerJa, lv_color_hex(0x208030), 0);
    lv_obj_align(btnAnswerJa, LV_ALIGN_CENTER, -80, 55);
    lv_obj_add_event_cb(btnAnswerJa, cbAnswerJa, LV_EVENT_CLICKED, NULL);
    lv_obj_t *lblJa = lv_label_create(btnAnswerJa);
    lv_obj_set_style_text_font(lblJa, &lv_font_montserrat_28, 0);
    lv_label_set_text(lblJa, "JA");
    lv_obj_center(lblJa);
    lv_obj_add_flag(btnAnswerJa, LV_OBJ_FLAG_HIDDEN);

    btnAnswerNein = lv_button_create(lv_layer_top());
    lv_obj_set_size(btnAnswerNein, 140, 90);
    lv_obj_set_style_bg_color(btnAnswerNein, lv_color_hex(0x802020), 0);
    lv_obj_align(btnAnswerNein, LV_ALIGN_CENTER, 80, 55);
    lv_obj_add_event_cb(btnAnswerNein, cbAnswerNein, LV_EVENT_CLICKED, NULL);
    lv_label_set_text(lv_label_create(btnAnswerNein), "NEIN");
    lv_obj_add_flag(btnAnswerNein, LV_OBJ_FLAG_HIDDEN);

    // -- Wecker-Overlay (layer_top, siehe alarmCheckTick()/alarmRingingTick()) --
    alarmRingingOverlay = lv_obj_create(lv_layer_top());
    lv_obj_set_size(alarmRingingOverlay, LV_PCT(100), LV_PCT(100));
    lv_obj_set_style_bg_color(alarmRingingOverlay, lv_color_hex(0x101040), 0);
    lv_obj_set_style_bg_opa(alarmRingingOverlay, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(alarmRingingOverlay, 0, 0);
    lv_obj_t *lblAlarmRinging = lv_label_create(alarmRingingOverlay);
    lv_obj_set_style_text_font(lblAlarmRinging, &lv_font_montserrat_28, 0);
    lv_label_set_text(lblAlarmRinging, LV_SYMBOL_BELL "\nWECKER");
    lv_obj_set_style_text_align(lblAlarmRinging, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_align(lblAlarmRinging, LV_ALIGN_CENTER, 0, -40);
    lv_obj_t *btnAlarmStop = lv_button_create(alarmRingingOverlay);
    lv_obj_set_size(btnAlarmStop, 160, 70);
    lv_obj_set_style_bg_color(btnAlarmStop, lv_color_hex(0x802020), 0);
    lv_obj_align(btnAlarmStop, LV_ALIGN_CENTER, 0, 60);
    lv_obj_add_event_cb(btnAlarmStop, cbAlarmStop, LV_EVENT_CLICKED, NULL);
    lv_obj_t *lblAlarmStop = lv_label_create(btnAlarmStop);
    lv_obj_set_style_text_font(lblAlarmStop, &lv_font_montserrat_24, 0);
    lv_label_set_text(lblAlarmStop, "Stopp");
    lv_obj_center(lblAlarmStop);
    lv_obj_add_flag(alarmRingingOverlay, LV_OBJ_FLAG_HIDDEN);

    // -- Taschenlampen-Overlay (layer_top, volle Helligkeit + weisser
    // Vollbild-Hintergrund, siehe cbOpenFlashlight()/flashlightTick()).
    // Antippen IRGENDWO auf dem Overlay verlaesst sie wieder (klickbarer
    // Vollbild-Container statt einzelnem Button). --
    flashlightOverlay = lv_obj_create(lv_layer_top());
    lv_obj_set_size(flashlightOverlay, LV_PCT(100), LV_PCT(100));
    lv_obj_set_style_bg_color(flashlightOverlay, lv_color_hex(0xFFFFFF), 0);
    lv_obj_set_style_bg_opa(flashlightOverlay, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(flashlightOverlay, 0, 0);
    lv_obj_add_flag(flashlightOverlay, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(flashlightOverlay, cbCloseFlashlight, LV_EVENT_CLICKED, NULL);
    lv_obj_add_flag(flashlightOverlay, LV_OBJ_FLAG_HIDDEN);

    refreshActiveScreen();
    updateMenuScreen();
}

// ============================================================================
// LoRa-Empfang verarbeiten (Nicht-blockierend, Flag aus ISR - siehe
// SX126x_Receive-Beispiel). Unterscheidet drei Pakettypen anhand des ersten
// entschlüsselten Bytes:
//   - 1 (LoRaStatusPacket.protocolVersion) + Länge 20 -> Status der Boots-Uhr
//   - 0x10 -> QuickMessageRequest
//   - 0x11 -> QuickMessageResponse
// ============================================================================

static void loraReceiveTick() {
    if (!loraReceivedFlag) return;
    loraReceivedFlag = false;

    size_t rawLen = radio.getPacketLength();
    // TODO(Test-Debug): Zeilen bis Verbindung verifiziert ist
    Serial.printf("[LoRa RX] Paket da, rawLen=%d RSSI=%.1f SNR=%.1f\n", (int)rawLen, radio.getRSSI(), radio.getSNR());
    if (rawLen == 0 || rawLen > CRYPTO_MAX_BUFFER) return;
    uint8_t rawBuf[CRYPTO_MAX_BUFFER];
    int state = radio.readData(rawBuf, rawLen);
    if (state != RADIOLIB_ERR_NONE) {
        Serial.printf("[LoRa RX] readData fehlgeschlagen, Code %d\n", state);
        return;
    }

    uint8_t plainBuf[CRYPTO_MAX_PAYLOAD];
    size_t plainLen;
    if (!decryptLoRaPacket(rawBuf, rawLen, plainBuf, plainLen) || plainLen < 1) {
        Serial.println("[LoRa RX] Entschluesselung fehlgeschlagen oder leer");
        return;
    }

    uint8_t firstByte = plainBuf[0];
    if (firstByte == 0x10 && plainLen >= sizeof(QuickMessageRequest)) {
        QuickMessageRequest req;
        memcpy(&req, plainBuf, sizeof(req));
        // Absender-Check: nur Anfragen von der Boots-Uhr akzeptieren. Schützt
        // gegen Selbstempfang der eigenen gerade gesendeten Pakete (siehe
        // Kommentar bei sendEncrypted()).
        if (req.sender != DeviceId::BOOT) return;
        handleIncomingQuickMessageRequest(req);
    } else if (firstByte == 0x11 && plainLen >= sizeof(QuickMessageResponse)) {
        QuickMessageResponse resp;
        memcpy(&resp, plainBuf, sizeof(resp));
        if (resp.responder != DeviceId::BOOT) return; // siehe Absender-Check oben
        Serial.printf("[Quick-Msg RX] Antwort vom Boot: inResponseTo=%d %s (erwartet=%d wartend=%d)\n",
                       resp.inResponseToSequence, quickAnswerText(resp.answer), pendingRequestSequence, waitingForAnswer);
        if (waitingForAnswer && resp.inResponseToSequence == pendingRequestSequence) {
            waitingForAnswer = false;
            lastReceivedAnswer = resp.answer;
            showAnswerOverlay = true;
            answerOverlayShownMillis = millis();
            vibrateAnswerReceived();
            updateQuickOverlay();
        }
    } else if (plainLen == sizeof(LoRaStatusPacket)) {
        memcpy(&lastPacket, plainBuf, sizeof(LoRaStatusPacket));
        lastPacketReceivedMillis = millis();
        havePacketYet = true;
        Serial.printf("[LoRa RX] Status OK: seq=%d state=%d\n", lastPacket.sequence, (int)lastPacket.state);

        // Landuhr-Kartenansicht (siehe docs/Erweiterung_Landuhr_Kartenansicht.md):
        // nur relevant, wenn BLE gerade an ist (pPositionChar != nullptr,
        // siehe startFragenEditorBle()/stopFragenEditorBle()).
        if (pPositionChar != nullptr) {
            BlePositionPayload posPayload{lastPacket.latE7, lastPacket.lonE7, lastPacket.gpsValidFix, lastPacket.sequence};
            pPositionChar->setValue((uint8_t *)&posPayload, sizeof(posPayload));
            pPositionChar->notify();
        }

        // Zeit-Sync von der Boots-Uhr (siehe LoRaPacket.h-Kommentar): einmalig
        // beim ersten gueltigen Paket nach dem Booten, nicht bei jedem Paket -
        // die lokale RTC laeuft danach eigenstaendig weiter (siehe Doku
        // "Eigene RTC/Uhrzeit läuft lokal weiter, auch ohne Empfang"). Manuelles
        // Stellen im Menue gibt es deshalb nicht mehr (vorher: "Zeit stellen"-
        // Abschnitt, siehe PROJEKT_STATUS.md-Historie).
        if (!timeSyncedFromBoat && lastPacket.timeHour != 0xFF) {
            instance.rtc.setDateTime(2000 + lastPacket.timeYearOffset, lastPacket.timeMonth, lastPacket.timeDay,
                                      lastPacket.timeHour, lastPacket.timeMinute, lastPacket.timeSecond);
            timeSyncedFromBoat = true;
            Serial.printf("[Zeit-Sync] Von Boots-Uhr uebernommen: %02d.%02d.%04d %02d:%02d:%02d\n",
                          lastPacket.timeDay, lastPacket.timeMonth, 2000 + lastPacket.timeYearOffset,
                          lastPacket.timeHour, lastPacket.timeMinute, lastPacket.timeSecond);
        }

        refreshActiveScreen(); // sofort aktualisieren, nicht erst beim naechsten 1Hz-Tick
    } else {
        Serial.printf("[LoRa RX] unbekanntes Paket, firstByte=0x%02X plainLen=%d\n", firstByte, (int)plainLen);
    }
}

// ============================================================================
// Setup / Loop
// ============================================================================

void setup() {
    Serial.begin(115200);

    instance.begin();

    // So frueh wie moeglich (RTC ist ab instance.begin() lesbar) - loggt den
    // Grund fuer DIESEN Boot, bevor irgendwas anderes schiefgehen koennte.
    // Siehe Erklaerung bei logResetReasonToNvs() oben.
    logResetReasonToNvs();

    beginLvglHelper(instance);
    instance.setBrightness(DEVICE_MAX_BRIGHTNESS_LEVEL);

    // Vor buildUi(), damit die initialen Anzeigen (Weckzeit-Vorschau,
    // Alarm-Toggle-Farbe) schon die geladenen Werte zeigen.
    loadCustomQuestionsFromPrefs();
    loadAlarmFromPrefs();

    buildUi();

    setupLoRaTransceiver();

    // Standby/Wecken (siehe docs/Erweiterung_Standby_Wecken.md): auf
    // SENSOR_EVENT (u.a. BMA423-Tilt) hoeren, siehe onDeviceSensorEvent().
    instance.onEvent(onDeviceSensorEvent, SENSOR_EVENT, nullptr);
}

static unsigned long lastScreenTickMs = 0;

void loop() {
    instance.loop(); // u.a. nötig, damit radio-Interrupt-Callbacks dispatcht werden

    loraReceiveTick();
    checkQuickMessageTimeout();
    standbyTick();
    alarmRingingTick();
    flashlightTick();

    // 1Hz-Tick fürs Uhrzeit-/Verbindungsalter-Update (Status ändert sich
    // sonst nur bei Paketempfang, aber Uhrzeit und "Xs her" müssen auch
    // ohne neues Paket weiterlaufen)
    unsigned long now = millis();
    if (now - lastScreenTickMs >= 1000) {
        lastScreenTickMs = now;
        refreshActiveScreen();
    }

    lv_timer_handler();
    delay(5);
}
