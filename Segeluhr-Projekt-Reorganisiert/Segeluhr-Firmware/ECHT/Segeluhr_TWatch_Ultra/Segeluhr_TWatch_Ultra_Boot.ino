// ============================================================
// Segeluhr - T-Watch Ultra "Boots-Uhr" - SKELETON
// ============================================================
// Diese Datei ist ein Ausgangspunkt für die Claude-Code-Session.
// Sie übernimmt die Rolle der bisherigen Segeluhr_TWatch_S3.ino
// (BLE Central zum Handy, 6 Segel-Screens, Haptik) und erweitert
// sie um GPS (intern) und LoRa-Sender.
//
// Siehe: docs/Erweiterung_Land_Boot_LoRa_Kommunikation.md
//
// WICHTIG (aus PROJEKT_STATUS.md / bisherigen Erfahrungen):
// - LilyGoLib-Abhängigkeiten IMMER aus LilyGoLib-ThirdParty (gepinnt),
//   nie über den Arduino Library Manager aktualisieren
// - Arduino libraries-Ordner unter C:\Arduino\libraries (außerhalb OneDrive)
//
// BOARD-SETTINGS (siehe docs/Hardware_Arduino_Settings_LilyGO.md):
// Board=LilyGo T-Watch-Ultra, Partition Scheme=16M Flash(3M APP/9.9MB FATFS),
// Board Revision=Radio-SX1262, Upload Mode=UART0/Hardware CDC, USB Mode=CDC and JTAG
//
// DOWNLOAD-MODE bei Upload-Problemen (ANDERS als bei der S3, kein Akku-Ausbau):
// BOOT halten -> RST drücken -> RST loslassen -> BOOT loslassen -> Upload -> RST
//
// LORA-PINS (SX1262): RESET=47, BUSY=48, CS=36, IRQ/DIO1=14, SPI shared (SCK=35/MISO=33/MOSI=34)
// GOTCHA: LoRa haengt an Power-Rail ALDO3 (AXP2101) - muss vor radio.begin() aktiv sein,
// sonst scheinbar zufaellige RadioLib-Timeouts trotz korrekter Verkabelung.
// ============================================================

#include <LilyGoLib.h>
#include <RadioLib.h>
#include "../../shared/LoRaPacket.h"
#include "../../shared/QuickMessages.h"
#include "../../shared/Crypto.h"

// TODO(Claude Code): Bestehende Includes/Engines aus
// Segeluhr_TWatch_S3.ino hierher übernehmen bzw. referenzieren:
// - BLE-Central-Setup (NimBLE, Verbindung zum Handy-GATT-Server)
// - Screen-Rendering für die 6 Segel-Screens + Alltag-Modus
// - Haptik-Bridge (DRV2605 ROM-Effekte)
// - Auto-Mode-Switching bei BLE Connect/Disconnect (30s Gnadenfrist)
// - Zeit-Sync über READ-Characteristic

// ---- LoRa Sender-Status ----
static uint8_t loraSequence = 0;
static unsigned long lastLoraSendMillis = 0;

// Aktueller vereinfachter Boot-Zustand (wird von den bestehenden Engines
// aktualisiert, siehe buildAndSendStatusPacket()). Wird auch für die
// automatische Regatta-Antwort auf Quick-Messages genutzt (siehe unten).
static BoatState currentBoatState = BoatState::IDLE;

// TODO(Claude Code): SX1262-Instanz gemäß LilyGoLib-Pinbelegung für
// T-Watch Ultra initialisieren (ggf. unterscheidet sich von generischen
// RadioLib-Beispielen - LilyGoLib kapselt das normalerweise).
// SX1262 radio = ...;

void setupLoRaSender() {
    // TODO(Claude Code): radio.begin(...) mit Standard-Parametern
    // (Frequenz je nach Region - CH/EU meist 868 MHz, Bandbreite/SF
    // nach Reichweitenbedarf See<->Land wählen, nicht zu aggressiv
    // auf Datenrate optimieren, Reichweite hat Priorität)
}

void buildAndSendStatusPacket() {
    LoRaStatusPacket pkt;
    pkt.sequence = loraSequence++;

    // TODO(Claude Code): Werte aus den bestehenden Engines befüllen:
    // currentBoatState = ... (Mapping von TrainMode/CompetitionState/HomeEngine
    //                  auf das vereinfachte BoatState-Enum, siehe
    //                  LoRaPacket.h Kommentar zur Mapping-Logik)
    // pkt.state = currentBoatState;
    // pkt.countdownRemainingSec = ...
    // pkt.boatBatteryPercent = ...
    // pkt.distanceRemainingM = ...
    // pkt.sogCkn = ...
    // pkt.windDirDeg = ...
    // pkt.latE7 = ...
    // pkt.lonE7 = ...

    // TODO(Claude Code): radio.transmit(...) - jetzt über Verschlüsselung:
    // uint8_t encBuf[CRYPTO_MAX_BUFFER]; size_t encLen;
    // encryptLoRaPacket((uint8_t*)&pkt, sizeof(pkt), encBuf, encLen);
    // radio.transmit(encBuf, encLen);
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

void onButtonShortPress() {
    if (haveIncomingQuestion) {
        // Fallback, falls Gestenerkennung unzuverlässig ist (siehe Doku):
        // TODO(Claude Code): QuickMessageResponse mit QuickAnswer::JA senden,
        // haveIncomingQuestion = false, Overlay ausblenden
        return;
    }
    // Sonst: im Auswahlmenü zur nächsten Frage blättern
    // TODO(Claude Code): selectedQuestion = nächster Enum-Wert (mit Wrap-Around
    // bei QuickQuestion::COUNT), Menü-Anzeige aktualisieren
}

void onButtonLongPress() {
    if (haveIncomingQuestion) {
        // Fallback, falls Gestenerkennung unzuverlässig ist (siehe Doku):
        // TODO(Claude Code): QuickMessageResponse mit QuickAnswer::NEIN senden,
        // haveIncomingQuestion = false, Overlay ausblenden
        return;
    }
    // Sonst: ausgewählte Frage als QuickMessageRequest senden
    // TODO(Claude Code):
    // QuickMessageRequest req;
    // req.sequence = quickMsgSequence++;
    // req.sender = DeviceId::BOOT;
    // req.question = selectedQuestion;
    // uint8_t encBuf[CRYPTO_MAX_BUFFER]; size_t encLen;
    // encryptLoRaPacket((uint8_t*)&req, sizeof(req), encBuf, encLen);
    // radio.transmit(encBuf, encLen);
    // waitingForAnswer = true; pendingRequestSequence = req.sequence;
    // pendingRequestSentMillis = millis();
}

// ---- Gesten-Antwort (primärer Weg, siehe Doku Abschnitt 5) ----
// WICHTIG: Gestenerkennung nur aktiv abfragen/auswerten, wenn
// haveIncomingQuestion == true - sonst wird jede normale Handbewegung
// beim Segeln als Antwort fehlinterpretiert.

void onGestureTiltUp() {
    // "Aufschauen"-Geste = Ja
    if (!haveIncomingQuestion) return;
    // TODO(Claude Code): QuickMessageResponse mit QuickAnswer::JA senden,
    // identisch zu onButtonShortPress()-Fallback-Pfad
}

void onGestureShake() {
    // Kurzes Schütteln = Nein
    if (!haveIncomingQuestion) return;
    // TODO(Claude Code): QuickMessageResponse mit QuickAnswer::NEIN senden,
    // identisch zu onButtonLongPress()-Fallback-Pfad
}

// TODO(Claude Code): BHI260AP-Init und Gestenerkennung - bevorzugt via Klio
// (SensorLib-Beispiel, selbstlernende Mustererkennung direkt auf dem Sensor,
// siehe Doku Abschnitt 5) statt manueller Schwellenwerte: ein paar Beispiel-
// Aufnahmen der echten Ja/Nein-Geste trainieren lassen. Klio-Ergebnis ->
// onGestureTiltUp() bzw. onGestureShake(). Falls Klio nicht praktikabel ist,
// Fallback auf Rohdaten-Schwellenwerte (siehe QuickMessages.h, empirisch
// austesten).

void checkQuickMessageTimeout() {
    if (waitingForAnswer &&
        (millis() - pendingRequestSentMillis) > QUICK_MESSAGE_TIMEOUT_MS) {
        waitingForAnswer = false;
        // TODO(Claude Code): Anzeige "keine Antwort" kurz zeigen
    }
}

// TODO(Claude Code): Empfangs-Handler einbauen, der bei radio.receive() ZUERST
// decryptLoRaPacket() auf die rohen Bytes anwendet (siehe Crypto.h), dann erst
// anhand des entschlüsselten ersten Bytes (msgType) unterscheidet zwischen
// LoRaStatusPacket (hier nicht relevant, das sendet ja die Ultra selbst),
// QuickMessageRequest (msgType 0x10 -> handleIncomingQuickMessageRequest(),
// siehe unten) und QuickMessageResponse (msgType 0x11 -> falls
// inResponseToSequence == pendingRequestSequence: Antwort anzeigen + kurze
// Vibration (siehe Doku Abschnitt 6), waitingForAnswer=false).

// Automatische Antwort während aktiver Wettfahrt (siehe Doku Abschnitt 5):
// Kein Overlay, keine Vibration - der Skipper soll während COMPETITION
// gar nicht erst durch Quick-Messages abgelenkt werden.
void handleIncomingQuickMessageRequest(const QuickMessageRequest& req) {
    if (currentBoatState == BoatState::COMPETITION) {
        QuickMessageResponse resp;
        resp.inResponseToSequence = req.sequence;
        resp.responder = DeviceId::BOOT;
        resp.answer = QuickAnswer::AUTO_REGATTA;
        // TODO(Claude Code): verschlüsselt senden, siehe encryptLoRaPacket()
        // in buildAndSendStatusPacket() als Vorlage
        // uint8_t encBuf[CRYPTO_MAX_BUFFER]; size_t encLen;
        // encryptLoRaPacket((uint8_t*)&resp, sizeof(resp), encBuf, encLen);
        // radio.transmit(encBuf, encLen);
        return;
    }
    // Normalfall: Overlay anzeigen, auf Gesten-/Knopf-Antwort warten
    haveIncomingQuestion = true;
    incomingRequest = req;
    // TODO(Claude Code): Vibration auslösen (nicht bei Status-Broadcast, nur
    // hier bei Quick-Message-Fragen - siehe Doku Abschnitt 6), Overlay einblenden
}

void setup() {
    // TODO(Claude Code): bestehende Setup-Logik aus
    // Segeluhr_TWatch_S3.ino übernehmen (Display, BLE, Haptik, GPS)

    setupLoRaSender();
    // TODO(Claude Code): radio.startReceive() - Ultra muss ab jetzt auch
    // empfangen können (Quick-Messages), nicht mehr nur senden
}

void loop() {
    // TODO(Claude Code): bestehende 1Hz-Tick-Loop / Screen-Rendering
    // aus Segeluhr_TWatch_S3.ino übernehmen

    // LoRa: alle 30s Status senden
    unsigned long now = millis();
    if (now - lastLoraSendMillis >= LORA_SEND_INTERVAL_MS) {
        lastLoraSendMillis = now;
        buildAndSendStatusPacket();
    }

    checkQuickMessageTimeout();

    // TODO(Claude Code): Button-Polling -> onButtonShortPress() / onButtonLongPress()
    // TODO(Claude Code): eingehende LoRa-Pakete verarbeiten (siehe Kommentar oben)
}
