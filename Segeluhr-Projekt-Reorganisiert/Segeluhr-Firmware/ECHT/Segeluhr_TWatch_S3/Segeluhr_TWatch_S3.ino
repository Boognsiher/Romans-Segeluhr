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
#include "../../shared/LoRaPacket.h"
#include "../../shared/QuickMessages.h"
#include "../../shared/Crypto.h"

// ============================================================================
// Empfangener Status der Boots-Uhr
// ============================================================================

static LoRaStatusPacket lastPacket;
static unsigned long lastPacketReceivedMillis = 0;
static bool havePacketYet = false;

static bool isSignalLost() {
    if (!havePacketYet) return true;
    return (millis() - lastPacketReceivedMillis) > LORA_SIGNAL_LOST_THRESHOLD_MS;
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

static QuickQuestion selectedQuestion = QuickQuestion::ALLES_GUT;
static uint8_t quickMsgSequence = 0;

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
    Serial.printf("[Quick-Msg RX] Frage vom Boot: seq=%d %s\n", req.sequence, quickQuestionText(req.question));
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

// Fragen-Browser im Menü-Tab: nächste Frage / senden
static void cbQuickNext(lv_event_t *e) {
    selectedQuestion = (QuickQuestion)(((uint8_t)selectedQuestion + 1) % (uint8_t)QuickQuestion::COUNT);
    updateMenuScreen();
}
static void cbQuickSend(lv_event_t *e) {
    QuickMessageRequest req;
    req.sequence = quickMsgSequence++;
    req.sender = DeviceId::LAND;
    req.question = selectedQuestion;
    sendEncrypted((uint8_t *)&req, sizeof(req));
    waitingForAnswer = true;
    pendingRequestSequence = req.sequence;
    pendingRequestSentMillis = millis();
    Serial.printf("[Quick-Msg TX] Frage gesendet: seq=%d %s\n", req.sequence, quickQuestionText(req.question));
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

// Detail-Tab
static lv_obj_t *lblDetailDistance;
static lv_obj_t *lblDetailSog;
static lv_obj_t *lblDetailBattery;
static lv_obj_t *lblDetailWind;
static lv_obj_t *lblDetailPacketInfo;

// Menü-Tab
static lv_obj_t *swMute;
static lv_obj_t *lblQuickSelected;
static lv_obj_t *lblTimeSetPreview;

// Quick-Message-Overlay (layer_top, über allen Tabs)
static lv_obj_t *lblQuickOverlay = nullptr;
static lv_obj_t *btnAnswerJa = nullptr;
static lv_obj_t *btnAnswerNein = nullptr;

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
    lv_label_set_text_fmt(lblDetailPacketInfo, "Paket #%d, %lus alt", lastPacket.sequence, ageMs / 1000);
}

static void updateMenuScreen() {
    if (lblQuickSelected != nullptr) {
        lv_label_set_text_fmt(lblQuickSelected, "Frage: %s", quickQuestionText(selectedQuestion));
    }
}

static void updateQuickOverlay() {
    if (lblQuickOverlay == nullptr) return;
    if (haveIncomingQuestion) {
        lv_label_set_text_fmt(lblQuickOverlay, "FRAGE VOM BOOT:\n%s", quickQuestionText(incomingRequest.question));
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
}

// ---- Menü-Tab: Stumm-Modus + Zeit stellen + Quick-Message-Browser + Ausschalten ----

static void cbMuteToggle(lv_event_t *e) {
    muteEnabled = lv_obj_has_state(swMute, LV_STATE_CHECKED);
}

// Lokale RTC-Einstellung (siehe Header-Kommentar: bewusst ohne
// Protokolländerung gelöst, nur Stunde/Minute - das Datum ist für die
// reine HH:MM-Anzeige auf dem Hauptscreen irrelevant und bleibt, was die
// RTC ohnehin gespeichert hat).
static int8_t timeSetHour = 12;
static int8_t timeSetMinute = 0;

static void updateTimeSetPreview() {
    lv_label_set_text_fmt(lblTimeSetPreview, "%02d:%02d", timeSetHour, timeSetMinute);
}
static void cbTimeHourPlus(lv_event_t *e) { timeSetHour = (timeSetHour + 1) % 24; updateTimeSetPreview(); }
static void cbTimeHourMinus(lv_event_t *e) { timeSetHour = (timeSetHour + 23) % 24; updateTimeSetPreview(); }
static void cbTimeMinutePlus(lv_event_t *e) { timeSetMinute = (timeSetMinute + 1) % 60; updateTimeSetPreview(); }
static void cbTimeMinuteMinus(lv_event_t *e) { timeSetMinute = (timeSetMinute + 59) % 60; updateTimeSetPreview(); }
static void cbTimeSetApply(lv_event_t *e) {
    struct tm now;
    instance.rtc.getDateTime(&now);
    instance.rtc.setDateTime(now.tm_year + 1900, now.tm_mon + 1, now.tm_mday, timeSetHour, timeSetMinute, 0);
}

/**
 * Deep-Sleep, analog zum Shutdown-Mechanismus der Boots-Uhr
 * (instance.sleep(), Standard-Wakeup: Power-Key + Touch-Panel).
 */
static void cbShutdown(lv_event_t *e) {
    lv_label_set_text(lblStatusBig, "");
    lv_label_set_text(lblClockBig, "Aus - zum Aufwachen\ntippen oder Power-Taste");
    lv_task_handler();
    delay(300);
    instance.sleep();
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

// text_font vererbt sich nicht zuverlässig - Section-Header/Zeit-Buttons
// bekommen ihre Schrift deshalb hier zentral über einen kleinen Helfer.
static lv_obj_t *addSubHeader(lv_obj_t *parent, const char *text) {
    lv_obj_t *lbl = lv_label_create(parent);
    lv_obj_set_style_text_font(lbl, &lv_font_montserrat_24, 0);
    lv_label_set_text(lbl, text);
    return lbl;
}

static lv_obj_t *addSmallButton(lv_obj_t *parent, const char *label, lv_event_cb_t cb) {
    lv_obj_t *btn = lv_button_create(parent);
    lv_obj_set_size(btn, 70, 56);
    lv_obj_add_event_cb(btn, cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *lbl = lv_label_create(btn);
    lv_obj_set_style_text_font(lbl, &lv_font_montserrat_20, 0);
    lv_label_set_text(lbl, label);
    lv_obj_center(lbl);
    return btn;
}

static void buildMenuTab(lv_obj_t *parent) {
    lv_obj_set_flex_flow(parent, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_row(parent, 6, 0);

    addSubHeader(parent, "-- Stumm-Modus --");
    lv_obj_t *muteRow = lv_obj_create(parent);
    lv_obj_set_size(muteRow, LV_PCT(94), LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(muteRow, LV_FLEX_FLOW_ROW);
    lv_obj_t *lblMute = lv_label_create(muteRow);
    lv_obj_set_style_text_font(lblMute, &lv_font_montserrat_20, 0);
    lv_label_set_text(lblMute, "Vibration/Ton stumm:");
    swMute = lv_switch_create(muteRow);
    lv_obj_set_style_transform_zoom(swMute, 320, 0); // groesserer Schalter, leichter zu treffen
    lv_obj_add_event_cb(swMute, cbMuteToggle, LV_EVENT_VALUE_CHANGED, NULL);

    addSubHeader(parent, "-- Zeit stellen --");
    lblTimeSetPreview = lv_label_create(parent);
    lv_obj_set_style_text_font(lblTimeSetPreview, &lv_font_montserrat_28, 0);
    lv_label_set_text(lblTimeSetPreview, "12:00");
    lv_obj_t *timeRow = lv_obj_create(parent);
    lv_obj_set_size(timeRow, LV_PCT(94), LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(timeRow, LV_FLEX_FLOW_ROW);
    addSmallButton(timeRow, "Std-", cbTimeHourMinus);
    addSmallButton(timeRow, "Std+", cbTimeHourPlus);
    addSmallButton(timeRow, "Min-", cbTimeMinuteMinus);
    addSmallButton(timeRow, "Min+", cbTimeMinutePlus);
    addMenuButton(parent, "Uebernehmen", cbTimeSetApply);

    addSubHeader(parent, "-- Quick-Message ans Boot --");
    lblQuickSelected = lv_label_create(parent);
    lv_obj_set_style_text_font(lblQuickSelected, &lv_font_montserrat_24, 0);
    lv_label_set_text(lblQuickSelected, "Frage: ALLES GUT?");
    addMenuButton(parent, "Naechste Frage", cbQuickNext);
    addMenuButton(parent, "Frage senden", cbQuickSend);

    addSubHeader(parent, "-- Sonstiges --");
    lv_obj_t *btnShutdown = addMenuButton(parent, "Ausschalten", cbShutdown);
    lv_obj_set_style_bg_color(btnShutdown, lv_color_hex(0x802020), 0);
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

    tabMain   = lv_tabview_add_tab(tv, "Status");
    tabDetail = lv_tabview_add_tab(tv, "Detail");
    tabMenu   = lv_tabview_add_tab(tv, "Menu");

    // -- Haupt-Tab: grosser Status-Text + Uhrzeit + Verbindungsindikator --
    lblStatusBig = lv_label_create(tabMain);
    lv_obj_set_style_text_font(lblStatusBig, &lv_font_montserrat_28, 0);
    lv_obj_set_style_text_align(lblStatusBig, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_align(lblStatusBig, LV_ALIGN_TOP_MID, 0, 20);

    lblClockBig = lv_label_create(tabMain);
    lv_obj_set_style_text_font(lblClockBig, &lv_font_montserrat_48, 0);
    lv_obj_align(lblClockBig, LV_ALIGN_CENTER, 0, 10);

    // text_font vererbt sich in dieser LVGL-Version NICHT zuverlässig vom
    // Tabview auf Kind-Labels - deshalb ab hier überall explizit gesetzt.
    lblConnIndicator = lv_label_create(tabMain);
    lv_obj_set_style_text_font(lblConnIndicator, &lv_font_montserrat_24, 0);
    lv_obj_align(lblConnIndicator, LV_ALIGN_BOTTOM_MID, 0, -10);

    // -- Detail-Tab --
    lblDetailDistance = lv_label_create(tabDetail);
    lv_obj_set_style_text_font(lblDetailDistance, &lv_font_montserrat_28, 0);
    lv_obj_align(lblDetailDistance, LV_ALIGN_TOP_MID, 0, 6);
    lblDetailSog = lv_label_create(tabDetail);
    lv_obj_set_style_text_font(lblDetailSog, &lv_font_montserrat_28, 0);
    lv_obj_align(lblDetailSog, LV_ALIGN_TOP_MID, 0, 48);
    lblDetailBattery = lv_label_create(tabDetail);
    lv_obj_set_style_text_font(lblDetailBattery, &lv_font_montserrat_28, 0);
    lv_obj_align(lblDetailBattery, LV_ALIGN_TOP_MID, 0, 90);
    lblDetailWind = lv_label_create(tabDetail);
    lv_obj_set_style_text_font(lblDetailWind, &lv_font_montserrat_28, 0);
    lv_obj_align(lblDetailWind, LV_ALIGN_TOP_MID, 0, 132);
    lblDetailPacketInfo = lv_label_create(tabDetail);
    lv_obj_set_style_text_font(lblDetailPacketInfo, &lv_font_montserrat_18, 0);
    lv_obj_align(lblDetailPacketInfo, LV_ALIGN_BOTTOM_MID, 0, -10);

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
    beginLvglHelper(instance);
    instance.setBrightness(DEVICE_MAX_BRIGHTNESS_LEVEL);

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
