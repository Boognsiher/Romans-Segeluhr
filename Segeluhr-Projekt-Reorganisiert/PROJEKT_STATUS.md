# Segeluhr — Projekt-Status

Diese Datei ist die zentrale Übersicht über alle Komponenten. **Bitte bei
jeder Session, in der sich etwas Wesentliches ändert, aktualisieren** —
das ist der eigentliche Zweck: ein Blick hier reicht, um zu wissen, was
gerade läuft, was Baustelle und was nur Test ist.

## Status-Legende

✅ läuft/getestet · 🔧 in Arbeit · 📋 geplant, noch nicht gebaut · 🗄️ archiviert/nicht mehr aktiv gepflegt

## Übersicht

| Komponente | Hardware | Status | Zuletzt getestet | Bemerkung |
|---|---|---|---|---|
| Android-App | Handy (Samsung) | ✅ | 28.07.2026 | Master: GATT-Server, alle Engines, BLE-Protokoll |
| `Segeluhr_TWatch_S3` (ALT) | T-Watch S3 | 🗄️ | 28.07.2026 | Ursprüngliche Boots-Uhr-Rolle (BLE Central, Alltags-/Segelmodus, Auto-Focus, Zeit-Sync) — Code jetzt nach `Segeluhr_TWatch_Ultra.ino` portiert, physische S3-Hardware wird zur Land-Uhr (siehe Zeile unten) |
| `Segeluhr_TWatch_Ultra` | T-Watch Ultra | 🔧 | 05.08.2026 (Boot + LoRa + Quick-Msg, beide Richtungen) | Boots-Uhr, aus S3-Firmware + LoRa-Sender/Quick-Messages zusammengeführt, kompiliert + geflasht (COM17), bootet sauber. **LoRa-Status-Broadcast + Quick-Messages (beide Richtungen, JA per kurzem/NEIN per langem Tasterdruck) Ende-zu-Ende verifiziert.** Selbstempfangs-Bug gefixt. UI-Nacharbeit (Schriftgrösse, gedrehte Eck-Tabs wg. Gehäuse-Abdeckung) mehrfach nachgebessert, **letzter Stand (94px Tab-Leiste) noch ungetestet**. LoRa-Frequenz 06.08. auf 869.525 MHz umgestellt (siehe unten). **Klio-Gestentraining (docs/Erweiterung_Gesten_Training_Klio.md) 06.08. implementiert + kompiliert** (Serial-Kommandos `TRAIN JA`/`TRAIN NEIN`/etc., NVS-Persistenz) — dabei zwei Bugs im bestehenden Gesten-Code gefunden: `USING_BHI260_SENSOR` war nie gesetzt (Gestencode lief nur als Stub, der 05.08.-Pitch-Messwert stammt vermutlich nicht aus dieser Firmware) und LilyGoLib lädt standardmässig eine BHI260-Firmware ohne Klio-Support (jetzt per `uploadFirmware()` nachgeladen) — Details siehe Doku Abschnitt 4. **Noch nicht auf Hardware getestet**, insbesondere ob der Firmware-Swap den Schwellenwert-Fallback (Pitch/Shake) weiter funktionsfähig lässt. **Standby/Wecken (docs/Erweiterung_Standby_Wecken.md) 06.08. implementiert + kompiliert**: Display-Aus nach 30s (`instance.sleepDisplay()`, kein Deep-Sleep), Aufwecken per Knopf/Geste/eingehender Quick-Message — noch nicht auf Hardware getestet. BLE zum Handy noch offen — siehe `docs/Offene_Punkte_Hardware_Test_05_08.md` |
| `Segeluhr_TWatch_S3` (Land) | T-Watch S3 | 🔧 | 05.08.2026 (Boot + LoRa + Quick-Msg, beide Richtungen) | Land-Uhr (neue Rolle, ersetzt bisherige BLE-Central-Rolle), kompiliert + geflasht (COM16), bootet sauber. **LoRa-Empfang + Quick-Messages (beide Richtungen) Ende-zu-Ende verifiziert**, Buttons/Schrift nach Hardware-Test vergrössert. **Standby/Wecken 06.08. implementiert + kompiliert**: Display-Aus nach 30s, Aufwecken per Touch oder BMA423-Hardware-Tilt (LilyGoLib-Event, kein eigener Schwellenwert-Code nötig) — noch nicht auf Hardware getestet. Ton bei Quick-Messages noch offen — siehe `docs/Offene_Punkte_Hardware_Test_05_08.md` |
| `segeluhr_ble_tester` | ESP32-C3/XIAO | 🗄️ | — | nur lokal vorhanden, nicht in diesem Repo, siehe `Segeluhr-Firmware/TESTING/README.md` |
| `Segeluhr_Basis_Solo` | (für Ultra gedacht) | 🗄️ | — | nur lokal vorhanden, nie auf echter Hardware getestet |

## Bekannte offene Punkte

- **Detaillierter Fahrplan für die nächste Firmware-Session:**
  `docs/Offene_Punkte_Hardware_Test_05_08.md` (Gesten-Kalibrierung,
  ungetesteter UI-Fix, BLE-Test, was heute schon gefixt wurde).
- **Fehlende Root-Dokumente:** `Segeluhr_Spezifikation.md` und
  `BLE_Protokoll.md` (die ursprüngliche Basis-Spezifikation) liegen nicht
  in diesem Repo — nur die `Erweiterung_*.md`-Ergänzungsdokumente unter
  `Segeluhr-Android/Segeluhr/docs/`. Falls die Basisdokumente noch woanders
  existieren, gehören sie hier in den Root-Ordner.
- **`distanceTraveledM`** (LoRa-Paket) braucht noch eine neue
  Distanz-Aufsummierung in der App, existiert aktuell nicht.
- ~~**Duty-Cycle-/Kanalwahl für LoRa in der Schweiz**~~ ✅ erledigt
  06.08.2026: Frequenz beider Firmwares von 868.0 MHz auf 869.525 MHz
  umgestellt (Band 869.4-869.65 MHz, 10% statt 1% Duty-Cycle in der
  Schweiz erlaubt). Beide Sketches nach der Änderung fehlerfrei neu
  kompiliert (`esp32:esp32:twatch_ultra` / `esp32:esp32:twatchs3`), noch
  nicht auf Hardware geflasht/getestet — nächster Schritt vor dem ersten
  Praxiseinsatz.
- **DRV2605-Haptik-Stärke**: aktuelle stärkste verfügbare ROM-Effekte
  genutzt, aber keine echte Software-Gain-Kontrolle möglich (siehe
  Firmware-Kommentar bei `triggerHaptic()`) — bei Bedarf nochmal
  RTP-Modus auf Register-Ebene prüfen.

## Ordnerstruktur

```
Segeluhr-Android/Segeluhr/     Android-App (Kotlin/Compose)
  docs/                        Erweiterungs-Dokumentation (Erweiterung_*.md)
Segeluhr-Firmware/
  ECHT/                        Firmware für echten Segelbetrieb
    Segeluhr_TWatch_S3/        ✅ aktueller Stand
    Segeluhr_TWatch_Ultra/     📋 geplant
    Segeluhr_WatchS_LoRaEmpfaenger/  📋 geplant
  TESTING/                     Tester/Prototypen, nicht für echten Betrieb
PROJEKT_STATUS.md              diese Datei
```
