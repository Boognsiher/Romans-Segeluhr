# Segeluhr_Mastuhr — 📋 Konzept, noch nicht gebaut

Fest am Mast/Boot montiertes Gerät, das die T-Watch (Handgelenk) beim
Segeln ersetzen soll. **13.08.2026 radikal vereinfacht** (Roman-
Entscheidung): kein eigenes GPS, kein LoRa mehr — die Mastuhr ist reine
**BLE-Anzeige fürs Handy**, architektonisch dieselbe Rolle wie die
T-Watch S3 heute (BLE Central, alle Nav-Daten kommen vom Handy).

**Board-Standard-Empfehlung: [Waveshare ESP32-S3-Touch-LCD-3.49](https://www.waveshare.com/esp32-s3-touch-lcd-3.49.htm)**
(Roman-Vorschlag, 13.08.2026 Abend übernommen) — ESP32-S3 mit
eingebautem 3.49"-Touch-IPS-Display (172×640, schmaler Streifen statt
Breitformat), RTC-Chip, 18650-Akkuhalter, Audio-Codec+Lautsprecher-
Header, 6-Achsen-IMU, alles auf einem Board. Kein LilyGO-Bezug. Gehäuse
wird von Roman selbst per 3D-Druck gefertigt.

Volle Konzept-Dokumentation (Motivation, Architektur-Entscheidungen,
Alternativen-Vergleich, offene Punkte): `../../../docs/Erweiterung_Mastuhr.md`

## Inhalt dieses Ordners

- `Segeluhr_Mastuhr.ino` — **noch nicht angelegt.** Wird erst geschrieben,
  sobald Taster-Pins gewählt und das UI-Layout fürs Streifen-Display
  entworfen sind.
- `hardware/Stueckliste.md` — Bauteile + Kostenschätzung (~CHF 85–135)
- `hardware/Verkabelung.md` — Pin-Zuordnung (Entwurf)
- `hardware/Gehaeuse/README.md` — Anforderungen an den 3D-Druck-Entwurf

## Rolle im Gesamtsystem

Dritter BLE-Anzeige-Client neben Handy und T-Watch S3 im "Mit Uhr"-Modus
(siehe `CLAUDE.md`, "KRITISCHE Begriffs-Unterscheidungen") — fest am Mast
statt am Handgelenk. Nutzt dasselbe GATT-Protokoll/dieselbe Custom
Service-UUID wie die bestehenden Uhren. Firmware-Basis kann grösstenteils
von `../Segeluhr_TWatch_S3/Segeluhr_TWatch_S3.ino` übernommen werden
(BLE-Central-Code, GATT-Characteristics) — nur die Display-Ansteuerung
muss vom LilyGoLib/LVGL-Layer auf den AXS15231B-Controller des neuen
Boards umgestellt werden.

## Warum dieses Board (Kurzfassung, Details in der Stückliste)

Ersetzt gegenüber der zuvor geprüften LilyGO-T-Display-S3-Option mehrere
Einzelteile durch Onboard-Hardware: eigenes RTC (spart ein separates
Breakout), 18650-Akkuhalter (kein JST-Stecker-Mismatch), Audio-Codec +
Lautsprecher-Header (löst nebenbei die seit 10.08. offene Frage aus
`docs/Erweiterung_S3_Ton_QuickMessages.md`). Trade-off: das Display ist
ein schmaler, hoher Streifen statt eines normalen Breitformats — die
bestehenden LVGL-Tab-Layouts brauchen einen UI-Neuentwurf statt
Copy-Paste.
