# Erweiterung: Uhrzeit-Sync über BLE (T-Watch S3)

**Nicht Teil der ursprünglichen Spezifikation.** Da die Uhr im
wasserdichten Sack steckt und ohnehin komplett vom Handy gesteuert wird,
soll auch die Uhrzeit nicht manuell an der Uhr eingestellt werden müssen.

## Neue Characteristic

| Name | UUID | Eigenschaft | Richtung |
|---|---|---|---|
| CHAR_TIME_SYNC | `6f6e0009-b5a3-f393-e0a9-e50e24dcca9e` | READ | Handy -> Uhr |

Bewusst als **READ** (nicht NOTIFY) umgesetzt: die Uhr braucht die Zeit
nur einmal beim Verbindungsaufbau, kein fortlaufendes Update nötig — die
RTC (PCF8563) läuft danach mit eigener Batteriepufferung weiter.

## TimeSyncPacket (7 Byte, Little-Endian)

| Byte | Feld | Bedeutung |
|---|---|---|
| 0-1 | `uint16 year` | z.B. 2026 |
| 2 | `uint8 month` | 1-12 |
| 3 | `uint8 day` | 1-31 |
| 4 | `uint8 hour` | 0-23 |
| 5 | `uint8 minute` | 0-59 |
| 6 | `uint8 second` | 0-59 |

Bewusst **lokale Wanduhrzeit** des Handys, kein Unix-Timestamp — dadurch
ist keine Zeitzonen-Umrechnung auf der Uhr nötig, die Anzeige stimmt
direkt mit dem Handy überein.

## Ablauf

1. Android (`BleGattServerManager`): `onCharacteristicReadRequest` erkennt
   die UUID, ruft `BleProtocol.encodeTimeSync()` frisch auf (liest
   `Calendar.getInstance()` im Moment des Reads) und antwortet direkt —
   kein gespeicherter Zustand nötig.
2. Firmware (`connectToServer()`): direkt nach erfolgreichem Verbinden und
   Registrieren aller Notify-Characteristics wird `CHAR_TIME_SYNC_UUID`
   einmalig gelesen und `instance.rtc.setDateTime(year, month, day, hour,
   minute, second)` aufgerufen.
3. Bei jedem Neuverbinden wird die Zeit erneut synchronisiert — die Uhr
   geht so nie spürbar nach, ganz ohne manuelle Einstellung.
