# Segeluhr_TWatch_Ultra — "Boots-Uhr", 🔧 kompiliert, Hardware-Verhalten noch nicht getestet

`Segeluhr_TWatch_Ultra.ino` ist die aktuelle, vollständige Firmware.
Übernimmt die komplette bisherige Funktionalität von `Segeluhr_TWatch_S3.ino`
(BLE Central zum Handy, 6 Segel-Screens + Alltagsmodus, Haptik, Auto-Focus,
Zeit-Sync) und erweitert sie um:

- **LoRa-Status-Broadcast** an die Land-Uhr (T-Watch S3, siehe
  `../Segeluhr_TWatch_S3/Segeluhr_TWatch_S3.ino`), alle 30s
- **Quick-Messages**: lockere Ja/Nein-Fragen zwischen Boot und Land, Antwort
  auf dem Boot primär per Taster/Geste, Touch nur als Bonus
- Position/Geschwindigkeit/Wind fürs LoRa-Paket kommen weiterhin vom Handy
  per BLE — **kein** eigenes Solo-GPS in dieser Ausbaustufe (siehe unten)

Volle Spezifikation dieser Erweiterung:
`../../../docs/Erweiterung_Land_Boot_LoRa_Kommunikation.md`

## Herkunft dieser Datei

Zusammengeführt aus zwei Vorgänger-Dateien (05.08.2026, Claude Code):
`Segeluhr_TWatch_S3_ALT_zum_Portieren.ino` (die einzige lauffähige Basis)
und dem `Segeluhr_TWatch_Ultra.ino`-Skeleton (LoRa-/Quick-Message-
Gerüst). Die ALT-Datei bleibt vorerst als Referenz liegen — bitte erst
löschen, nachdem die zusammengeführte Version auf echter Hardware getestet
wurde (siehe `PROJEKT_STATUS.md`-Konvention).

## Kompiliert 05.08.2026 (arduino-cli, esp32:esp32:twatch_ultra)

Fehlerfrei: 1.349.353 Bytes Flash (42%), 42.064 Bytes RAM (12%). Bibliotheken
liegen jetzt in `C:\Arduino\libraries` (aus OneDrive verschoben, siehe
`docs/Hardware_Arduino_Settings_LilyGO.md`). Reines Compile-Ergebnis — das
tatsächliche Verhalten auf der Uhr (LoRa-Reichweite, Touch, Gesten, Haptik)
ist damit noch nicht geprüft, siehe Punkte unten.

## Was noch nicht verifiziert ist (vor dem ersten Flash beachten)

- **LoRa-Parameter** (`setupLoRaTransceiver()`): API-Aufrufe (`radio.set*`,
  `radio.transmit`, `radio.startReceive`) sind gegen die echten
  LilyGoLib-Beispiele `examples/radio/SX1262/SX126x_{Transmit,Receive}`
  geprüft und kompilieren fehlerfrei. Frequenz/Bandbreite/SF/Sync-Word
  müssen exakt mit `Segeluhr_TWatch_S3.ino` übereinstimmen.
- **Gestenerkennung** (`gestureTick()`): API-Namen (`SensorXYZ`,
  `SensorQuaternion`, `instance.sensor`) sind gegen
  `examples/sensor/BHI260AP_{6DoF,Euler}` geprüft, aber die konkreten
  Schwellenwerte (`GESTURE_TILT_*`, `GESTURE_SHAKE_MIN_AMPLITUDE`) sind
  Platzhalter und MÜSSEN auf dem Wasser kalibriert werden. Bis dahin ist der
  physische Taster (GPIO0) der zuverlässige Weg.
- **Taster-Pin/Pegel** (`CUSTOM_BUTTON_PIN`, `buttonTick()`): als
  Standard-ESP32-Muster (aktiv-low, `INPUT_PULLUP`) angenommen, nicht an
  echter Hardware verifiziert.
- **AES-Schlüssel**: neu generiert in `../../shared/Crypto.h` (identisch für
  Ultra und Land-Firmware, da beide dieselbe Datei einbinden) — kein
  weiterer Schritt nötig, nur beim nächsten Schlüsselwechsel dran denken,
  beide Firmwares neu zu flashen.
- **`distanceRemainingM`** im LoRa-Paket: grobe Schätzung aus
  `ETA * aktuelle Geschwindigkeit` (nur während aktivem Heimweg, sonst -1) —
  es gibt aktuell kein echtes Distanz-Feld im BLE-Protokoll vom Handy.

## Bewusst nicht Teil dieser Ausbaustufe

- **Solo-GPS** (eigenes GPS-Modul der Ultra, unabhängig vom Handy): laut
  Erweiterungs-Doku ein späterer Ausbauschritt. Aktuell kommen alle
  Positions-/Geschwindigkeitsdaten fürs LoRa-Paket weiterhin per BLE vom
  Handy, genau wie auf den Segel-Screens.
- **Klio-Gestenerkennung** (selbstlernend statt Schwellenwerte): laut Doku
  der bevorzugte Ansatz für später, hier erstmal einfache Schwellenwert-
  Platzhalter umgesetzt.
