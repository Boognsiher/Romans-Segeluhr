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
| `Segeluhr_TWatch_S3` | T-Watch S3 | ✅ | 28.07.2026 | BLE Central, Alltags-/Segelmodus, Auto-Focus, Zeit-Sync |
| `Segeluhr_TWatch_Ultra` | T-Watch Ultra | 📋 | — | Solo-GPS + LoRa-Sender, siehe `Segeluhr-Firmware/ECHT/Segeluhr_TWatch_Ultra/README.md` |
| `Segeluhr_WatchS_LoRaEmpfaenger` | Watch S | 📋 | — | reiner LoRa-Empfänger an Land |
| `segeluhr_ble_tester` | ESP32-C3/XIAO | 🗄️ | — | nur lokal vorhanden, nicht in diesem Repo, siehe `Segeluhr-Firmware/TESTING/README.md` |
| `Segeluhr_Basis_Solo` | (für Ultra gedacht) | 🗄️ | — | nur lokal vorhanden, nie auf echter Hardware getestet |

## Bekannte offene Punkte

- **Fehlende Root-Dokumente:** `Segeluhr_Spezifikation.md` und
  `BLE_Protokoll.md` (die ursprüngliche Basis-Spezifikation) liegen nicht
  in diesem Repo — nur die `Erweiterung_*.md`-Ergänzungsdokumente unter
  `Segeluhr-Android/Segeluhr/docs/`. Falls die Basisdokumente noch woanders
  existieren, gehören sie hier in den Root-Ordner.
- **`distanceTraveledM`** (LoRa-Paket) braucht noch eine neue
  Distanz-Aufsummierung in der App, existiert aktuell nicht.
- **Duty-Cycle-/Kanalwahl für LoRa in der Schweiz** vor dem ersten
  Praxiseinsatz gegen BAKOM-Vorgaben prüfen (siehe
  `BLE_Protokoll_Ergaenzung_Heimweg_LoRa.md`).
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
