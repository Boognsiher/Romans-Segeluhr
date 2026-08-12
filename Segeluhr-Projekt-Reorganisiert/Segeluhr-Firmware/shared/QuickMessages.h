#pragma once
#include <cstdint>

// ============================================================
// Segeluhr - Quick-Messages (lockere Ja/Nein-Fragen zwischen den Uhren)
// Ergänzung zu LoRaPacket.h - siehe
// docs/Erweiterung_Land_Boot_LoRa_Kommunikation.md, Abschnitt 6
//
// WICHTIG: Beide Uhren müssen ab jetzt SENDEN und EMPFANGEN können
// (die Land-Uhr war bisher reiner Empfänger für LoRaStatusPacket -
// für Quick-Messages wird sie zum Transceiver).
// ============================================================

enum class QuickQuestion : uint8_t {
    ALLES_GUT           = 0,
    HUNGER              = 1,
    KALT_DRAUSSEN       = 2,
    BALD_FERTIG         = 3,
    KAFFEE_TEE_MACHEN   = 4,
    BRAUCHST_DU_WAS     = 5,
    GUTE_LAUNE          = 6,
    NOCH_LANGE          = 7,
    BRINGST_DU_WAS_MIT  = 8,
    SEHEN_WIR_UNS_GLEICH = 9,
    // NEU (12.08.2026, Roman-Wunschliste "vor dem naechsten Test", siehe
    // docs/Erweiterung_TWatch_Ultra_NavRedesign.md): wird von der Ultra
    // automatisch gesendet, sobald der Heimweg-Modus per Ankunfts-Erkennung
    // stoppt (HOME_FLAG_ARRIVED in BleProtocol.kt) - Roman kann sie ausserdem
    // jederzeit manuell ueber die normale Fragen-Auswahl (Taster kurz/lang)
    // verschicken, keine Sonderbehandlung noetig, einfach ein weiterer
    // Eintrag in derselben Liste.
    BIN_ZURUECK         = 10,
    COUNT // NICHT als Frage nutzen - nur zur Bereichsprüfung
};

inline const char* quickQuestionText(QuickQuestion q) {
    switch (q) {
        case QuickQuestion::ALLES_GUT:            return "ALLES GUT?";
        case QuickQuestion::HUNGER:                return "HUNGER?";
        case QuickQuestion::KALT_DRAUSSEN:         return "KALT DRAUSSEN?";
        case QuickQuestion::BALD_FERTIG:           return "BALD FERTIG?";
        case QuickQuestion::KAFFEE_TEE_MACHEN:     return "KAFFEE/TEE MACHEN?";
        case QuickQuestion::BRAUCHST_DU_WAS:       return "BRAUCHST DU WAS?";
        case QuickQuestion::GUTE_LAUNE:            return "GUTE LAUNE?";
        case QuickQuestion::NOCH_LANGE:            return "NOCH LANGE?";
        case QuickQuestion::BRINGST_DU_WAS_MIT:    return "BRINGST DU WAS MIT?";
        case QuickQuestion::SEHEN_WIR_UNS_GLEICH:  return "SEHEN WIR UNS GLEICH?";
        case QuickQuestion::BIN_ZURUECK:           return "BIN ZURUECK!";
        default:                                   return "?";
    }
}

enum class QuickAnswer : uint8_t {
    PENDING      = 0, // noch keine Antwort
    JA           = 1,
    NEIN         = 2,
    TIMEOUT      = 3, // keine Antwort innerhalb der Frist
    AUTO_REGATTA = 4  // automatische Antwort waehrend aktiver Wettfahrt
};

inline const char* quickAnswerText(QuickAnswer a) {
    switch (a) {
        case QuickAnswer::JA:           return "JA";
        case QuickAnswer::NEIN:         return "NEIN";
        case QuickAnswer::TIMEOUT:      return "KEINE ANTWORT";
        case QuickAnswer::AUTO_REGATTA: return "BIN IN EINER REGATTA";
        default:                        return "?";
    }
}

enum class DeviceId : uint8_t {
    BOOT = 0, // T-Watch Ultra
    LAND = 1  // T-Watch S3
};

// Maximale Länge eigener, per BLE-Fragen-Editor erstellter Fragen (siehe
// docs/Erweiterung_S3_BLE_Fragen_Editor.md) - inkl. NUL-Terminator. Text
// wird direkt im LoRa-Paket mitgeschickt (Doku-Empfehlung: "vermutlich
// einfacher als nur eine Text-ID"), die Land-Uhr muss den Text also nicht
// vorher separat zur Boots-Uhr transportieren.
constexpr uint8_t CUSTOM_QUESTION_MAX_LEN = 32;

// Wie viele eigene Fragen die Land-Uhr gleichzeitig speichert (Speicher-
// limit auf dem ESP32 klein halten, siehe Doku Abschnitt 5) - bei Erreichen
// überschreibt eine neue Frage die älteste (Ringpuffer), kein Voll-Fehler
// nötig.
constexpr uint8_t CUSTOM_QUESTION_MAX_COUNT = 5;

#pragma pack(push, 1)
// Anfrage: "Ich stelle dir Frage X"
struct QuickMessageRequest {
    uint8_t       msgType = 0x10; // zur Unterscheidung von LoRaStatusPacket auf Empfängerseite
    uint8_t       sequence;       // eigener Zähler für Quick-Messages
    DeviceId      sender;
    QuickQuestion question;              // gültig, wenn isCustom == 0
    uint8_t       isCustom = 0;          // 0/1 statt bool - sauberer in einem #pragma-pack-Struct
    char          customText[CUSTOM_QUESTION_MAX_LEN] = {0}; // gültig, wenn isCustom == 1, NUL-terminiert
};

// Antwort: "Auf deine Frage X (sequence) antworte ich mit Ja/Nein"
struct QuickMessageResponse {
    uint8_t       msgType = 0x11;
    uint8_t       inResponseToSequence; // muss zur Request-sequence passen
    DeviceId      responder;
    QuickAnswer   answer;
};
#pragma pack(pop)

// Liefert den Anzeigetext einer Anfrage, unabhängig davon ob sie eine der
// 10 vordefinierten oder eine eigene (BLE-Editor) Frage ist - zentrale
// Stelle statt an jeder Anzeigestelle isCustom selbst abzufragen.
inline const char* quickMessageRequestText(const QuickMessageRequest &req) {
    return req.isCustom ? req.customText : quickQuestionText(req.question);
}

// Timeout, nach dem eine unbeantwortete Frage auf dem sendenden Gerät
// als "keine Antwort" markiert wird
constexpr uint32_t QUICK_MESSAGE_TIMEOUT_MS = 60000;

// ---- Geste-Erkennung: Parameter gegen Fehlauslösung durch Segel-Bewegungen ----
// WICHTIG: Diese Werte sind Platzhalter und MÜSSEN auf dem Wasser kalibriert
// werden (Rohdaten während normalem Segeln loggen: Krängung, Schoteinholen,
// Wellenschlag - und gegen eine bewusste Geste vergleichen). Schreibtisch-Tests
// reichen nicht aus, siehe Doku Abschnitt 5.

// "Nein"-Geste (Schütteln): mindestens N Richtungswechsel innerhalb des
// Zeitfensters, mit Mindestamplitude oberhalb von normalem Wellenschlag
constexpr uint8_t  GESTURE_SHAKE_MIN_REVERSALS = 2;
constexpr uint32_t GESTURE_SHAKE_WINDOW_MS = 800;
// GESTURE_SHAKE_MIN_AMPLITUDE: sensor-/einheitenabhängig, vor Ort ermitteln

// "Ja"-Geste (Handgelenk heben): NICHT nur Beschleunigungs-Spike auswerten,
// sondern die Ausrichtung zur Schwerkraft prüfen (wie "Raise to Wake") -
// deutlich robuster gegen Boots-Krängung als ein reiner Bewegungs-Trigger.
// GESTURE_TILT_TARGET_ANGLE_DEG / GESTURE_TILT_TOLERANCE_DEG: vor Ort ermitteln

// Debounce: eine erkannte Geste erst nach so vielen aufeinanderfolgenden
// Samples als "echt" werten, nicht schon beim ersten Ausschlag
constexpr uint8_t GESTURE_DEBOUNCE_SAMPLES = 2;

// Tastendruck-Muster (ein einzelner physischer Knopf pro Uhr, siehe Doku):
// - Kurzer Druck: im Auswahlmenü = nächste Frage, bei eingehender Frage = JA
// - Langer Druck: im Auswahlmenü = ausgewählte Frage senden, bei eingehender Frage = NEIN
// - Kein Druck innerhalb QUICK_MESSAGE_TIMEOUT_MS = TIMEOUT
