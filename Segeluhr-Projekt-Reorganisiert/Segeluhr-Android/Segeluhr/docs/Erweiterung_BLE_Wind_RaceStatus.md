# Erweiterung: BLE-Characteristics für Wind- und Race-Status

**Nicht Teil der ursprünglichen `BLE_Protokoll.md`.** Ergänzt im Zuge der
echten T-Watch-S3-Firmware (siehe `Erweiterung_TWatch_S3_Firmware.md`),
weil das ursprüngliche Protokoll für Wind, Countdown und Manöver nur
fertige Haptik-Codes kennt, aber keine Live-Werte — für echte Screens auf
der Uhr (statt Demo-/Haptik-only-Anzeigen) reicht das nicht.

## Neue Characteristics (Service `6f6e0001-...`)

| Name | UUID | Eigenschaft | Richtung |
|---|---|---|---|
| CHAR_WIND | `6f6e0007-b5a3-f393-e0a9-e50e24dcca9e` | NOTIFY | Handy -> Uhr |
| CHAR_RACE_STATUS | `6f6e0008-b5a3-f393-e0a9-e50e24dcca9e` | NOTIFY | Handy -> Uhr |

Beide werden 1x/s aus der Tickschleife von `SegeluhrViewModel` gesendet
(No-Op ohne verbundene Uhr, wie bei den bestehenden Notifies).

## WindStatusPacket (5 Byte, Little-Endian)

| Byte | Feld | Bedeutung |
|---|---|---|
| 0-1 | `uint16 windDirDdeg` | Wahrer Wind in 0.1°-Schritten (0-3599). `0xFFFF` = nicht kalibriert |
| 2 | `uint8 flags` | Bit0 = kalibriert |
| 3-4 | `int16 trendDdeg` | Kumulierter Wind-Shift-Trend in 0.1° (signed, Abschnitt 4.3) |

## RaceStatusPacket (5 Byte, Little-Endian)

| Byte | Feld | Bedeutung |
|---|---|---|
| 0 | `uint8 raceState` | Ordinal von `RaceState`: 0=MENU, 1=COUNTDOWN, 2=RACE |
| 1-2 | `uint16 countdownSeconds` | Verbleibende Sekunden. `0xFFFF` = kein laufender Countdown |
| 3 | `uint8 maneuverFlags` | Bit0 = Manöver empfohlen, Bit1 = Wende (1) statt Halse (0) |
| 4 | `uint8 competitionLeg` | Ordinal von `CompetitionLeg` (0=UPWIND,1=REACH,2=DOWNWIND), `0xFF` = keine Competition aktiv |

`maneuverNeeded` fasst drei Quellen zusammen: `TrainingEngine`-Zustand
`COMMANDED`, `CompetitionGuidance.maneuverNeeded` und
`HomeGuidance.maneuverNeeded` (falls Heimweg aktiv). `isTack` kommt aus
`TrainingEngine.isTackManeuver` (musste dafür von `private` auf öffentlich
lesbar mit `private set` geändert werden).

## CMD_*-Steuerbefehle (CHAR_CONTROL_UUID, Uhr -> Handy)

Da im GitHub-Stand noch keine `CMD_*`-Werte committet waren, wurden sie im
Zuge dieser Erweiterung neu definiert (siehe `BleProtocol.kt`). Payload ist
1 Byte Befehlscode, bei `CMD_SET_WAYPOINT`/`CMD_CLEAR_WAYPOINT` zusätzlich
1 Byte Wegpunkt-ID (`WaypointId`-Objekt). Wegpunkte werden immer an der
**aktuellen Handy-GPS-Position** gesetzt — die Uhr hat im "Mit Uhr"-Modus
kein eigenes GPS.

**Wichtig, falls dein lokales Android-Studio-Projekt bereits eigene
`CMD_*`-Werte aus einer früheren Session enthält:** die hier gelieferten
Dateien (`BleProtocol.kt`, `BleGattServerManager.kt`, `SegeluhrViewModel.kt`,
`TrainingEngine.kt`) ersetzen den kompletten `ble/`-Ordner-Stand — bitte
nicht mit älteren, abweichenden `CMD_*`-Definitionen mischen.
