#pragma once
#include <cstdint>

// ============================================================
// Segeluhr LoRa Status-Paket (Boot-Uhr -> Land-Uhr)
// Siehe docs/Erweiterung_Land_Boot_LoRa_Kommunikation.md
//
// WICHTIG: Diese Datei wird von BEIDEN Firmwares eingebunden
// (TWatch_Ultra_Boot und TWatch_S3_Land). Änderungen hier immer
// in beiden Projekten neu kompilieren/flashen, sonst laufen
// Sender/Empfänger auseinander.
// ============================================================

enum class BoatState : uint8_t {
    IDLE            = 0,  // Kein aktiver Modus / Alltag
    COUNTDOWN       = 1,  // Start-Countdown läuft
    TRAINING        = 2,  // Trainings-Modus (Race-Tab, manuell)
    COMPETITION     = 3,  // Echte Wettfahrt (Competition-Modus)
    HEIMWEG         = 4,  // Heimweg-Navigation aktiv
    MANEUVER_ALERT  = 5,  // Kurzzeitiger Zustand: Wende/Halse-Kommando aktiv
    PAUSED          = 6   // Pausiert / gestoppt
};

#pragma pack(push, 1)
struct LoRaStatusPacket {
    uint8_t   protocolVersion = 1;       // für zukünftige Formatänderungen
    uint8_t   sequence;                  // rollierender Zähler (0-255), erkennt Paketverlust
    BoatState state;
    int16_t   countdownRemainingSec;     // Sekunden bis Start, -1 falls n/a
    uint8_t   boatBatteryPercent;        // 0-100
    int16_t   distanceRemainingM;        // Distanz zu Bake/Ziel/Heimweg-Ziel, -1 falls n/a
    uint16_t  sogCkn;                    // Speed over Ground, 1/100 Knoten
    int16_t   windDirDeg;                // 0-359, -1 falls unbekannt
    int32_t   latE7;                     // Breitengrad * 1e7 (für spätere Kartendarstellung)
    int32_t   lonE7;                     // Längengrad * 1e7
    // Gesamtgröße: 1+1+1+2+1+2+2+2+4+4 = 20 Bytes
};
#pragma pack(pop)

static_assert(sizeof(LoRaStatusPacket) == 20, "LoRaStatusPacket Größe hat sich geändert - Doku und Empfänger pruefen!");

// Sende-Intervall (ms) - beide Seiten sollten diesen Wert kennen,
// damit die Land-Uhr "Signal verloren" korrekt timen kann.
constexpr uint32_t LORA_SEND_INTERVAL_MS = 30000;
constexpr uint32_t LORA_SIGNAL_LOST_THRESHOLD_MS = LORA_SEND_INTERVAL_MS * 3; // 90s Toleranz

// Hilfsfunktion für Anzeige-Texte auf der Land-Uhr (Hauptscreen)
inline const char* boatStateToDisplayText(BoatState s) {
    switch (s) {
        case BoatState::IDLE:           return "WARTE AUF START";
        case BoatState::COUNTDOWN:      return "COUNTDOWN";
        case BoatState::TRAINING:       return "TRAINING LAEUFT";
        case BoatState::COMPETITION:    return "WETTFAHRT LAEUFT";
        case BoatState::HEIMWEG:        return "HEIMWEG AKTIV";
        case BoatState::MANEUVER_ALERT: return "MANOEVER!";
        case BoatState::PAUSED:         return "PAUSIERT";
        default:                        return "?";
    }
}
