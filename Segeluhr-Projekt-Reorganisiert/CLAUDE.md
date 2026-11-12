# Segeluhr — Projektkontext für Claude Code

## Projektkontext

Segel-Trainings-/Regatta-Uhr-System: Android-App (Kotlin/Compose) als
Zentrale, Wear-OS-Begleit-App (Galaxy Watch 5 Pro) und zwei ESP32-
Firmwares (T-Watch Ultra = Boots-Uhr, T-Watch S3 = Land-Uhr, per LoRa
verbunden) — ursprünglich portiert aus einem Browser-Prototyp
(`index.html`). Zentrale Referenz für den aktuellen Stand: `PROJEKT_STATUS.md`.
- **Android**: Kotlin, Jetpack Compose, MVVM (`SegeluhrViewModel`, 1-Hz-
  Tick), Room + DataStore, FusedLocationProviderClient, BLE (Handy = GATT-
  Server/Peripheral, Uhren = Central).
- **Firmware**: Arduino/ESP32, LilyGoLib (Display/Touch/Haptik/Power),
  NimBLE-Arduino, LVGL, RadioLib (LoRa, AES-128-CTR-verschlüsselt).

## Code-Stil

- Kommentare durchgehend Deutsch, oft ausführlich mit Datum + Begründung
  ("warum so entschieden"), nicht nur "was" — inkl. Trade-offs/offener Punkte.
- Kotlin: camelCase (Funktionen/Properties), PascalCase (Klassen),
  UPPER_SNAKE_CASE für `object Constants`-Werte, KDoc (`/** */`) an
  Klassen und nicht-trivialen Konstanten.
- Arduino/C++: UPPER_SNAKE_CASE für `#define`/Konstanten, camelCase für
  Funktionen, langer Header-Kommentar pro `.ino` mit Board-Settings-Verweis
  und "WICHTIG"-Hinweisen zur Synchronität zwischen den Firmwares.

## Testen/Verifizieren

Keine automatisierten Tests im Repo. Drei Stufen sauber auseinanderhalten
(auch so in `PROJEKT_STATUS.md` eintragen): **1. Kompiliert** — Android
`./gradlew compileDebugKotlin`/`assembleDebug` (braucht Android-SDK, hier in
der Cloud-Umgebung meist fehlend — dann ehrlich sagen statt zu behaupten);
Firmware `arduino-cli compile` mit `docs/Hardware_Arduino_Settings_LilyGO.md`.
**2. Auf Hardware getestet** — geflasht/installiert. **3. Auf dem Wasser
verifiziert** — echte Segelbedingungen. Minimum pro Änderung: Stufe 1.

## Nicht anfassen

- `Segeluhr-Firmware/shared/Crypto.h`: fester `AES_KEY` — muss auf Ultra-
  und S3-Firmware exakt identisch bleiben, sonst nur Datenmüll statt Fehler.
- `shared/LoRaPacket.h`-Paketformate und LoRa-Frequenz (869.525 MHz):
  müssen auf beiden Firmwares synchron sein — Änderung erfordert Neu-
  Kompilieren + Flashen BEIDER Uhren.
- LilyGoLib-Abhängigkeiten (SensorLib, XPowersLib, lvgl, RadioLib) nur aus
  `LilyGoLib-ThirdParty` (gepinnte Versionen), nie über den normalen
  Arduino-Bibliotheksverwalter aktualisieren — führte schon zweimal zu
  Compile-Fehlern durch API-Versionskonflikte.

## Notizen
