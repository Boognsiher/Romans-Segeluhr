// ============================================================
// Segeluhr - T-Watch S3 "Land-Uhr" - SKELETON
// ============================================================
// ACHTUNG: Diese Firmware ERSETZT die BLE-Rolle der bisherigen
// Segeluhr_TWatch_S3.ino auf diesem Gerät. Die S3 ist ab jetzt
// kein BLE mehr, sondern LoRa-Transceiver: empfängt den 30s-Status
// der Boots-Uhr UND kann/empfängt zusätzlich Quick-Messages
// (Ja/Nein-Fragen, siehe QuickMessages.h) in beide Richtungen.
//
// (Die alte segeluhr_ble_tester_v2.ino bleibt als unabhängiges
// Test-Tool bestehen und hat mit dieser Firmware nichts zu tun.)
//
// Siehe: docs/Erweiterung_Land_Boot_LoRa_Kommunikation.md
// Board-Settings: docs/Hardware_Arduino_Settings_LilyGO.md
// (Board=LilyGo T-Watch-S3, Board Revision=Radio-SX1262, sonst wie gehabt.
// LoRa-Parameter MÜSSEN exakt mit denen im Ultra-Sender übereinstimmen!)
// ============================================================

#include <LilyGoLib.h>
#include <RadioLib.h>
#include "../../shared/LoRaPacket.h"
#include "../../shared/QuickMessages.h"
#include "../../shared/Crypto.h"

// ---- Empfangener Zustand ----
static LoRaStatusPacket lastPacket;
static unsigned long lastPacketReceivedMillis = 0;
static bool havePacketYet = false;

// ---- Lokale Uhrzeit ----
// TODO(Claude Code): Lokale RTC nutzen (LilyGoLib stellt i.d.R. einen
// RTC-Wrapper bereit). Entscheidung noch offen: Zeit manuell per
// Knopf-Menü stellen, oder optional per erstem LoRa-Paket synchronisieren
// (dafür müsste LoRaStatusPacket um einen Zeitstempel erweitert werden -
// aktuell NICHT im Paket enthalten, siehe Doku).

// ---- Screens ----
enum class LandScreen : uint8_t { MAIN = 0, DETAIL = 1, MENU = 2 };
static LandScreen currentScreen = LandScreen::MAIN;

// ---- Stumm-Modus (nur Quick-Message-Benachrichtigungen betroffen, siehe
// Doku Abschnitt 6 - der 30s-Status-Broadcast benachrichtigt ohnehin nie) ----
static bool muteEnabled = false;

// TODO(Claude Code): SX1262-Instanz für S3 gemäß LilyGoLib initialisieren
// SX1262 radio = ...;

void setupLoRaTransceiver() {
    // TODO(Claude Code): radio.begin(...) - MUSS exakt dieselben
    // Frequenz-/Bandbreiten-/SF-Parameter wie die Ultra nutzen!
    // radio.startReceive();
}

// TODO(Claude Code): Empfangs-Handler, der bei radio.receive() ZUERST
// decryptLoRaPacket() auf die rohen Bytes anwendet (siehe Crypto.h), dann erst
// anhand des entschlüsselten ersten Bytes (msgType) unterscheidet:
// - kein msgType-Byte / passt zu sizeof(LoRaStatusPacket) -> Statuspaket,
//   siehe onLoRaPacketReceived() unten
// - msgType 0x10 -> QuickMessageRequest -> haveIncomingQuestion=true,
//   Overlay+Vibration auslösen
// - msgType 0x11 -> QuickMessageResponse -> falls inResponseToSequence
//   == pendingRequestSequence: Antwort anzeigen (quickAnswerText(answer) -
//   deckt JA/NEIN/TIMEOUT/AUTO_REGATTA einheitlich ab) + kurze Vibration
//   (+ optional Ton, siehe Doku Abschnitt 6), waitingForAnswer=false

void onLoRaPacketReceived(const uint8_t* data, size_t len) {
    if (len != sizeof(LoRaStatusPacket)) {
        // TODO(Claude Code): Fehlerfall - falsches Paketformat, ignorieren
        return;
    }
    memcpy(&lastPacket, data, sizeof(LoRaStatusPacket));
    lastPacketReceivedMillis = millis();
    havePacketYet = true;
}

// ---- Quick-Messages (lockere Ja/Nein-Fragen, siehe QuickMessages.h) ----
static QuickQuestion selectedQuestion = QuickQuestion::ALLES_GUT;
static uint8_t quickMsgSequence = 0;
static bool waitingForAnswer = false;
static uint8_t pendingRequestSequence = 0;
static unsigned long pendingRequestSentMillis = 0;
static bool haveIncomingQuestion = false;
static QuickMessageRequest incomingRequest;

void onButtonShortPress() {
    if (haveIncomingQuestion) {
        // Fallback, falls Touch mal nicht reagiert:
        // TODO(Claude Code): QuickMessageResponse mit QuickAnswer::JA senden,
        // haveIncomingQuestion = false, Overlay ausblenden
        return;
    }
    // Sonst (auf Hauptscreen): zur nächsten Frage im Auswahlmenü blättern
    // TODO(Claude Code): selectedQuestion weiterschalten (Wrap-Around bei COUNT)
}

void onButtonLongPress() {
    if (haveIncomingQuestion) {
        // Fallback, falls Touch mal nicht reagiert:
        // TODO(Claude Code): QuickMessageResponse mit QuickAnswer::NEIN senden,
        // haveIncomingQuestion = false, Overlay ausblenden
        return;
    }
    // Sonst: ausgewählte Frage senden
    // TODO(Claude Code):
    // QuickMessageRequest req;
    // req.sequence = quickMsgSequence++;
    // req.sender = DeviceId::LAND;
    // req.question = selectedQuestion;
    // uint8_t encBuf[CRYPTO_MAX_BUFFER]; size_t encLen;
    // encryptLoRaPacket((uint8_t*)&req, sizeof(req), encBuf, encLen);
    // radio.transmit(encBuf, encLen);
    // waitingForAnswer = true; pendingRequestSequence = req.sequence;
    // pendingRequestSentMillis = millis();
}

// ---- Touch-Antwort (primärer Weg auf der Land-Uhr, siehe Doku Abschnitt 5) ----
// WICHTIG: Touch-Events nur auswerten, wenn haveIncomingQuestion == true bzw.
// wenn sich der Touch auf den entsprechend sichtbaren Button/Bereich bezieht.

void onTouchButtonJa() {
    if (!haveIncomingQuestion) return;
    // TODO(Claude Code): QuickMessageResponse mit QuickAnswer::JA senden,
    // haveIncomingQuestion = false, Overlay ausblenden
}

void onTouchButtonNein() {
    if (!haveIncomingQuestion) return;
    // TODO(Claude Code): QuickMessageResponse mit QuickAnswer::NEIN senden,
    // haveIncomingQuestion = false, Overlay ausblenden
}

void onTouchSwipeScreen() {
    // Wischen zwischen Haupt-/Detailscreen (nur wenn KEINE Frage offen ist)
    if (haveIncomingQuestion) return;
    onScreenSwitchButtonPressed();
}

void onTouchMenuNext() {
    // Antippen im Fragen-Menü = nächste Frage (analog zu kurzem Knopfdruck)
    onButtonShortPress();
}

void onTouchMenuSend() {
    // Antippen "senden" im Fragen-Menü (analog zu langem Knopfdruck)
    onButtonLongPress();
}

// TODO(Claude Code): Touch-Init (Focaltech FT5336) und Touch-Koordinaten auf
// die entsprechenden Bildschirmbereiche mappen -> onTouchButtonJa()/
// onTouchButtonNein()/onTouchSwipeScreen()/onTouchMenuNext()/onTouchMenuSend()
// je nach angezeigtem Screen (siehe Mockups). Physischer Knopf bleibt als
// Fallback (onButtonShortPress/onButtonLongPress unten), falls Touch mal
// nicht reagiert.

void checkQuickMessageTimeout() {
    if (waitingForAnswer &&
        (millis() - pendingRequestSentMillis) > QUICK_MESSAGE_TIMEOUT_MS) {
        waitingForAnswer = false;
        // TODO(Claude Code): Anzeige "keine Antwort" kurz zeigen
    }
}

bool isSignalLost() {
    if (!havePacketYet) return true;
    return (millis() - lastPacketReceivedMillis) > LORA_SIGNAL_LOST_THRESHOLD_MS;
}

void drawMainScreen() {
    // TODO(Claude Code): Großer Status-Text (boatStateToDisplayText)
    // + aktuelle Uhrzeit + kleiner Verbindungsindikator
    // Bei isSignalLost(): Status-Text durch "KEIN SIGNAL (seit Xs)" ersetzen,
    // Uhrzeit bleibt trotzdem sichtbar (läuft lokal weiter)
}

void drawDetailScreen() {
    // TODO(Claude Code): Distanz, SOG, Batterie Boot, Windschätzung,
    // Paket-Sequenznummer + Alter des letzten Pakets
}

void drawMenuScreen() {
    // TODO(Claude Code): Stumm-Modus-Toggle (zeigt muteEnabled-Zustand an,
    // antippbar über onTouchMuteToggle) + "Ausschalten"-Button (über
    // onTouchShutdown)
}

void onTouchMuteToggle() {
    muteEnabled = !muteEnabled;
    // TODO(Claude Code): Menü-Anzeige aktualisieren
}

void onTouchShutdown() {
    // TODO(Claude Code): Herunterfahren analog zum bestehenden
    // Shutdown-Mechanismus der Boots-Uhr (z.B. instance.sleep())
}

// Von den Vibrations-/Ton-Aufrufen VOR dem eigentlichen Auslösen prüfen:
// if (!muteEnabled) { /* vibrieren + ggf. Ton */ }
// Betrifft NUR Quick-Message-Benachrichtigungen (eingehende Frage, Antwort
// erhalten) - der 30s-Status-Broadcast benachrichtigt ohnehin nie, siehe
// Doku Abschnitt 6.

void onScreenSwitchButtonPressed() {
    // Zyklisch: MAIN -> DETAIL -> MENU -> MAIN
    switch (currentScreen) {
        case LandScreen::MAIN:   currentScreen = LandScreen::DETAIL; break;
        case LandScreen::DETAIL: currentScreen = LandScreen::MENU;   break;
        case LandScreen::MENU:   currentScreen = LandScreen::MAIN;   break;
    }
}

void setup() {
    // TODO(Claude Code): Display-Init (round ST77916 o.ä. je nach
    // Ultra/S3-Displaytreiber), Button-Init

    setupLoRaTransceiver();
}

void loop() {
    // TODO(Claude Code): 1Hz-Tick: lokale Uhrzeit updaten, aktuellen
    // Screen rendern (drawMainScreen / drawDetailScreen / drawMenuScreen je
    // nach currentScreen,
    // ODER falls haveIncomingQuestion: Frage-Overlay statt normalem Screen)

    checkQuickMessageTimeout();

    // TODO(Claude Code): Touch-Handling entscheiden - je nach angezeigtem
    // Screen auf onTouchButtonJa/onTouchButtonNein (bei offener Frage) bzw.
    // onTouchSwipeScreen/onTouchMenuNext/onTouchMenuSend (normal) routen.
    // Physischer Knopf bleibt als Fallback (onButtonShortPress/onButtonLongPress).

    // TODO(Claude Code): RadioLib Receive-Handling -> onLoRaPacketReceived(...)
    //   bzw. Quick-Message-Handler je nach msgType (siehe Kommentar oben)
}
