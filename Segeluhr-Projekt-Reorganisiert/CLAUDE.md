# Segeluhr — Projekt-Kontext für Claude Code

Segel-Trainings- und Regatta-Uhr: Android-App (Kotlin/Compose) + Uhr-Firmware
(Arduino/ESP32), ursprünglich portiert aus einem Browser-Prototyp
(`segeluhr.html`, jetzt `index.html`). Es gibt KEINE separaten
Spezifikationsdokumente (`Segeluhr_Spezifikation.md`/`BLE_Protokoll.md`) —
weder im Repo noch lokal beim Menschen (10.08.2026 geklärt, siehe
`PROJEKT_STATUS.md`); frühere Sessions hatten das nur vorsorglich
angenommen. Die "Spezifikation" lebt faktisch im Prototyp (`index.html`)
plus den `Erweiterung_*.md`-Dokumenten unter
`Segeluhr-Android/Segeluhr/docs/`, die jede spätere Abweichung/Ergänzung
festhalten.

**Vor jeder grösseren Änderung: `PROJEKT_STATUS.md` lesen** — zentrale
Übersicht, was aktuell läuft (✅), Baustelle (🔧), geplant (📋) oder
archiviert (🗄️) ist.

## Tech-Stack

- **Android**: Kotlin, Jetpack Compose, MVVM — ein zentrales
  `SegeluhrViewModel` mit 1-Hz-Tickschleife
- **Persistenz**: Room (Manöver-Log), DataStore (Wegpunkte/Einstellungen)
- **GPS**: FusedLocationProviderClient
- **BLE**: **Handy = GATT-Server/Peripheral** (nicht Central!), Uhr = Central.
  Custom Service `6f6e0001-b5a3-f393-e0a9-e50e24dcca9e`, mehrere
  Characteristics (GPS, Battery, Control, Haptik, Home-Status, Wind,
  Race-Status, Time-Sync — siehe `docs/Erweiterung_BLE_Wind_RaceStatus.md`
  und `docs/Erweiterung_TWatch_S3_ZeitSync.md` für die neueren)
- **Firmware**: Arduino/ESP32, LilyGoLib (Display/Touch/Haptik/Power),
  NimBLE-Arduino (BLE Central), LVGL für UI
- **Build Android**: Gradle/AGP, minSdk 26
- **Build Firmware**: Arduino IDE — der Mensch kann selbst keine
  Compile-Läufe durchführen (ausser Claude Code das jetzt übernimmt);
  bei Fehlern IMMER zuerst Ursache erklären, dann fixen

## Architektur-Prinzip (Android)

```
core/       Formeln & Konstanten (spiegeln die Spezifikation 1:1)
logic/      Engines (WindEngine, TrainingEngine, CompetitionEngine, HomeEngine,
            StartCountdownEngine, LakeGeofenceEngine) — jede Engine ist eine
            eigenständige Zustandsmaschine
data/       Room + DataStore
ble/        GATT-Server, Foreground-Service, Haptik-Bridge zur Uhr
viewmodel/  SegeluhrViewModel verbindet alles, rendert UiState
ui/         Compose-Screens, dunkles nautisches Theme
```

## Firmware-Struktur

```
Segeluhr-Firmware/
  ECHT/                          Firmware für echten Segelbetrieb
    Segeluhr_TWatch_S3/          ✅ aktueller Fokus (touch-only UI, LVGL,
                                  Alltags-/Segelmodus-Umschaltung, Auto-Focus
                                  ohne Touch nötig, da Uhr im wasserdichten
                                  Sack am Handgelenk steckt)
    Segeluhr_TWatch_Ultra/       📋 geplant: Solo-GPS + LoRa-Sender
    Segeluhr_WatchS_LoRaEmpfaenger/  📋 geplant: reiner LoRa-Empfänger an Land
  TESTING/                       Tester/Prototypen, NICHT für echten Betrieb
```

## KRITISCHE Begriffs-Unterscheidungen (nicht verwechseln!)

- **"Race" (Training-Tab)** = manueller Übungsmodus, 2 Bojen Pflicht,
  zufällige Wende-/Halse-Kommandos. Lebt in `TrainingEngine`
  (`TrainMode.RACE`).
- **"Competition" (Normal-Tab)** = echtes Rennen, startet automatisch bei
  Countdown 0:00, bis zu 2 Luv-Bojen optional, sonst Windschätzung. KEINE
  Zufallskommandos, nur laufende Navigationshilfe. Lebt in
  `CompetitionEngine`, komplett unabhängig von `TrainingEngine`.
- **Zwei Betriebsmodi (Setup-Tab)**: "Ohne Uhr" (Handy vibriert selbst) und
  "Mit Uhr" (Haptik per BLE an die Uhr, automatischer Fallback aufs Handy).
- **T-Watch S3 hat KEIN eigenes GPS** (nur die "S3 Plus"-Variante) — läuft
  deshalb ausschliesslich im "Mit Uhr"-Modus, bezieht alle Navigationsdaten
  vom Handy per BLE. Solo-Modus (`Segeluhr_Basis.ino`-Ansatz) ist erst für
  die T-Watch Ultra relevant.

## Dokumentations-Konvention

Jede Erweiterung, die NICHT in der ursprünglichen Spezifikation steht, wird
in `Segeluhr-Android/Segeluhr/docs/Erweiterung_<Name>.md` dokumentiert
(z.B. `Erweiterung_Heimweg.md`, `Erweiterung_TWatch_S3_AutoFocus.md`,
`BLE_Protokoll_Ergaenzung_Haptik.md`). Bei neuen Erweiterungen genauso
verfahren — kurz: Warum nötig, was genau geändert/ergänzt wurde, offene
Punkte.

## Arbeitsweise

- Bei neuen Funktionen: kurz nachfragen bei mehrdeutigen Anforderungen
  (Hardware-Details, UI-Platzierung, Verhalten bei Kantenfällen), dann
  vollständigen Code liefern statt nur Vorschläge.
- Bei Bugfixes: erst Ursache erklären, dann Fix — nicht nur den Fix zeigen.
- **Nach jedem erfolgreichen Update (Fix, neue Funktion, getestet und
  funktioniert): aktiv daran erinnern, den Stand auf GitHub zu pushen** und
  `PROJEKT_STATUS.md` zu aktualisieren (Status-Spalte, "Zuletzt getestet"-Datum).
- Bibliotheksversionen für LilyGoLib-Abhängigkeiten (SensorLib, XPowersLib,
  lvgl, RadioLib) IMMER aus `LilyGoLib-ThirdParty` (gepinnte Versionen),
  NIEMALS über den normalen Arduino-Bibliotheksverwalter aktualisieren —
  hat schon zweimal zu Compile-Fehlern durch API-Versionskonflikte geführt.

## Hardware-Kontext

- **Aktuell im Einsatz**: LilyGO T-Watch S3 (Touch-only-Bedienung, kein
  physischer Taster für die App-Logik)
- **Ziel-Hardware "Mit Uhr"**: LilyGO T-Watch Ultra (Sender/BLE + Solo-GPS)
  + LilyGO Watch S (LoRa-Empfänger an Land), beide SX1262-Chip, offizielle
  Bibliothek "LilyGoLib" (deckt Ultra, S3, S3 Plus, T-LoRa-Pager ab)
- Getestet zuvor mit einem XIAO-ESP32-Board als reiner BLE-Protokoll-Tester
  (`TESTING/`), simulierte nur die Central-Rolle — nicht mehr aktueller Fokus
- Mensch baut in Android Studio (Windows) + Arduino IDE, Segeluhr-App per
  USB-Debugging auf Samsung-Handy
