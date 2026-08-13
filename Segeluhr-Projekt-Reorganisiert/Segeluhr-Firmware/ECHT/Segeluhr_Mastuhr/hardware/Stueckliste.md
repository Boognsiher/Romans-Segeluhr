# Stückliste & Kostenschätzung — Segeluhr Mastuhr

Stand: 13.08.2026 Abend — **Standard-Empfehlung: Waveshare
ESP32-S3-Touch-LCD-3.49** (von Roman vorgeschlagen, als Basis
übernommen). Weiterhin die radikal vereinfachte BLE-only-Variante: kein
GPS, kein LoRa, alle Nav-Daten kommen per BLE vom Handy (dieselbe Rolle
wie die T-Watch S3 heute). Details/Historie der Entscheidung:
`../../../../docs/Erweiterung_Mastuhr.md`, Abschnitte 2a/2b.

## Board: Waveshare ESP32-S3-Touch-LCD-3.49

[Produktseite](https://www.waveshare.com/esp32-s3-touch-lcd-3.49.htm) ·
[Doku](https://docs.waveshare.com/ESP32-S3-Touch-LCD-3.49)

Ersetzt gegenüber der vorherigen LilyGO-T-Display-S3-Empfehlung mehrere
Einzelteile, weil es sie schon eingebaut mitbringt:

| Eingebaut | Ersetzt/spart | 
|---|---|
| PCF85063-RTC-Chip | separates RTC-Breakout (Pos. 6/7 der alten Liste) |
| 18650-Akkuhalter | JST-GH/JST-PH-Adapterkabel-Problem der T-Display-S3-Wahl (Standardzelle, überall nachkaufbar, kein Stecker-Mismatch) |
| ES8311/ES7210-Audio-Codec + Lautsprecher-Header | löst nebenbei die offene Hardware-Frage aus `Erweiterung_S3_Ton_QuickMessages.md` — Lautsprecher könnte hier ohne Rätselraten angeschlossen werden |
| QMI8658 6-Achsen-IMU | Option für spätere Klio-artige Gestensteuerung (nicht Teil dieser Phase) |
| Kein LilyGO-Bezug | Waveshare statt Hersteller der gekauften T-Watches — beantwortet die zuvor offene Frage |

**Display-Formfaktor beachten**: 172×640, ein schmaler, hoher Streifen
statt eines normalen Breitformats — die bestehenden LVGL-Tab-Layouts
(Nav/Wind/Heimweg/CD/Manöver) lassen sich nicht 1:1 übernehmen, eher als
vertikaler Stapel (Uhrzeit gross oben, SOG/Kurs darunter) neu entwerfen.
Für "auf einen Blick vom Cockpit ablesen" evtl. sogar passender als ein
quadratisches Display — aber definitiv ein UI-Neuentwurf, kein Copy-Paste.

## Minimal-Stückliste

| # | Komponente | Vorschlag | Menge | Preis (CHF, ca.) |
|---|---|---|---|---|
| 1 | Board | Waveshare ESP32-S3-Touch-LCD-3.49, **Case A mit 18650-Akku** (Variante ohne Akku + Case B/LiPo ebenfalls erhältlich, siehe Doku) | 1 | ~21 |
| 2 | Taster | wasserdicht IP67, 12mm, Momentary (onboard PWR/BOOT-Taster + Touchscreen im geschlossenen Gehäuse für Stand-alone-Bedienung bei Nässe unzuverlässig — siehe Warnhinweis unten) | 3 | ~15 |
| 3 | Kleinteile | Kabel/Stecker für Taster-Verdrahtung an die 22-Pin-Durchsteck-Pads | pauschal | ~6 |
| **Zwischensumme Elektronik** | | | | **~42** |

## Gehäuse & Montage (unverändert, lokal/Baumarkt)

| Komponente | Menge | Preis (CHF, ca.) |
|---|---|---|
| Filament (ASA/PETG) | pauschal | 6 |
| Silikon-Flachdichtung/O-Ring | pauschal | 7 |
| Schrauben Edelstahl | pauschal | 5 |
| Mast-Befestigung (Edelstahl-Schellen) | pauschal | 15 |
| **Zwischensumme Gehäuse** | | **~33** |

## Optionale Erweiterung: Solar (nachrüstbar)

Wie bei der vorherigen Empfehlung — Stromverbrauch der BLE-only-Firmware
noch nicht gemessen, Solar ggf. unnötig (siehe offene Punkte).

| Komponente | Produkt | Preis (CHF, ca.) |
|---|---|---|
| Solar-Laderegler | [Adafruit bq24074](https://www.digikey.com/en/products/detail/adafruit-industries-llc/4755/13231325) | 14 |
| Solarpanel | [Adafruit 6V/2W ETFE, wasserdicht](https://www.digikey.com/en/products/detail/adafruit-industries-llc/5366/15998627) | 24 |
| **Zwischensumme Solar (optional)** | | **~38** |

⚠️ Das Board hat bereits einen eigenen "hochintegrierten Power-
Management-IC" fürs Laden über USB-C — ein externer Solar-Laderegler
müsste den Akku parallel/anstatt davon laden, nicht gleichzeitig beide
aktiv. Detailplanung erst bei tatsächlichem Solar-Entscheid.

## Gesamt

| | CHF |
|---|---|
| Elektronik | ~42 |
| Gehäuse/Montage | ~33 |
| **Ohne Solar** | **~75** |
| + optional Solar | +38 |
| **Mit Solar** | **~113** |
| + ca. 15–20% Puffer (Versand/Zoll/Fehlkäufe) | +11–23 |
| **Geschätzte Gesamtkosten** | **~85–135** |

Günstiger und mit weniger Einzelteilen als die T-Display-S3-Variante,
bei gleichzeitig mehr eingebauter Funktionalität (RTC, Audio, IMU).

## ⚠️ Wichtig vor der Bestellung

- **Case-Variante wählen**: "Case A" = 18650-Rundzellenhalter (empfohlen,
  Standardzelle), "Case B" = LiPo über MX1.25-Stecker (dann derselbe
  Stecker-Mismatch-Vorbehalt wie beim T-Display-S3 möglich — vor
  Bestellung im Waveshare-Shop die Variante mit 18650-Halter gezielt
  auswählen, sonst versehentlich Case B erwischen).
- **V1 wurde eingestellt, Auslieferung läuft seit 8.6.2026 auf V2 um**
  (laut Produktsuche) — beim Bestellen prüfen, welche Revision aktuell
  versendet wird, und ob Pinout/Bibliotheken-Beispiele dazu passen.
- **Onboard-Taster (PWR/BOOT) + Touchscreen im geschlossenen Gehäuse**:
  für zuverlässige Stand-alone-Bedienung bei Nässe/Spritzwasser eher auf
  externe wasserdichte Taster setzen statt auf den Touchscreen durchs
  3D-gedruckte Sichtfenster — im Projekt gibt es dazu bereits eine
  einschlägige Erfahrung: die T-Watch S3 bekam ihre automatische
  Tab-Umschaltung ("Auto-Focus") genau deshalb, weil Touch durch den
  wasserdichten Sack am Handgelenk unzuverlässig ist.
- **RTC-Backup-Stromquelle**: nicht dokumentiert gefunden, ob der
  PCF85063 einen eigenen Superkondensator/Backup-Akku für die Uhrzeit
  bei Stromausfall hat, oder ob er ohne Hauptstrom die Zeit verliert —
  vor der Bestellung im Schaltplan/Datenblatt prüfen, falls das relevant
  ist (bei BLE-only mit Zeitsync vom Handy ohnehin unkritisch, siehe
  Konzept-Doku).
- **Firmware-Basis**: BLE-Central-Code + GATT-Protokoll weiterhin von
  `Segeluhr_TWatch_S3.ino` übernehmbar — Display-Ansteuerung muss auf
  den AXS15231B-Controller (QSPI+I2C) umgestellt werden, dafür passende
  Waveshare-Beispiele/Bibliothek verwenden statt LVGL/LilyGoLib.

---

## Referenz: ältere, verworfene Varianten

Zur Nachvollziehbarkeit in der Git-Historie dieser Datei erhalten:
1. **GPS+LoRa-Vollausstattung** (Digikey-Stückliste, ~CHF 240–260) —
   verworfen zugunsten BLE-only.
2. **LilyGO T-Display-S3** (~CHF 90–140) — verworfen zugunsten dieser
   Waveshare-Empfehlung (mehr eingebaute Funktionalität, kein
   LilyGO-Bezug, kein Akku-Stecker-Mismatch bei der 18650-Variante).
