# Verkabelung / Pin-Zuordnung — Segeluhr Mastuhr

Stand: 13.08.2026 Abend — Board-Empfehlung jetzt **Waveshare
ESP32-S3-Touch-LCD-3.49** (siehe `Stueckliste.md`). Weiterhin
**Entwurf, nicht verifiziert** — kein Bauteil vorhanden.

## Fast alles bereits onboard

ESP32-S3, Display (AXS15231B-Controller, QSPI+I2C), RTC (PCF85063),
IMU (QMI8658), Audio-Codec (ES8311/ES7210) sind auf dem Board fertig
verdrahtet. Extern anzuschliessen bleibt nur:

- **Akku**: 18650-Zelle in den eingebauten Halter (Case-A-Variante) —
  kein eigenes Verkabeln nötig.
- **Externe Taster (3×)**: über die **22-Pin-Durchsteck-Pads (2.54mm
  Raster)** an freie GPIOs, aktiv-low mit `INPUT_PULLUP` (gleiches
  Muster wie der bestehende Taster-Code der Ultra-Firmware, siehe
  `../../Segeluhr_TWatch_Ultra/README.md`) — konkrete Pin-Nummern nach
  Blick ins Waveshare-Pinout wählen (Boot-Strapping-Pins meiden).
- **USB-C**: für Firmware-Flash und Laden bereits vorhanden — im
  Gehäuse zugänglich lassen (siehe `Gehaeuse/README.md`).
- **Lautsprecher (optional)**: MX1.25-2-Pin-Header auf der Rückseite —
  falls die Ton-Wiedergabe für Quick-Messages (siehe
  `docs/Erweiterung_S3_Ton_QuickMessages.md`) hier nachgezogen werden
  soll, einfach einen kleinen 8Ω-Lautsprecher anschliessen statt wie bei
  der S3 erst die Hardware/API verifizieren zu müssen.

## Optional, falls Solar nachgerüstet wird

Der bq24074-Laderegler würde **statt** des onboard-Lade-Pfads über
USB-C den Akku laden — Solarpanel → bq24074 → Akku. Genaue Anbindung an
den 18650-Halter (parallel einspeisen vs. Zelle direkt am Regler)
noch nicht geplant, da Solar selbst noch nicht entschieden ist.

## Noch zu klären

- Konkrete GPIO-Nummern für die 3 externen Taster (Waveshare-Pinout
  gegenprüfen, welche der 22 Pads frei nutzbar sind).
- Ob V1 oder V2 des Boards bestellt wird (Auslieferung lief laut
  Produktsuche seit 8.6.2026 auf V2 um) — Pinout/Bibliothek danach
  ausrichten.
- Ob USB-C-Zugang im Gehäuse für Wartung/Laden reicht, oder ein
  Solar-Pfad mit Kabeldurchführung gebraucht wird.
- Tatsächlicher Stromverbrauch der BLE-only-Firmware — bestimmt, ob der
  18650-Akku allein für die Saison reicht oder Solar nötig wird.
- Ob der PCF85063 eine eigene Backup-Stromquelle hat (siehe
  Warnhinweis in `Stueckliste.md`) — bei BLE-only mit Zeitsync vom Handy
  unkritisch, aber gut zu wissen.
