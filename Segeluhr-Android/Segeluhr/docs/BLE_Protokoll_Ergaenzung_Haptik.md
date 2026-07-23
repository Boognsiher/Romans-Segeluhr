# Ergänzung zu BLE_Protokoll.md — Haptik-Characteristic

Diese Datei ergänzt `BLE_Protokoll.md` um eine vierte Characteristic, die für
den App-seitigen Betriebsmodus **"Mit Uhr"** gebraucht wird: Statt dass das
Handy selbst vibriert, sendet es das Vibrationsmuster-Kommando per BLE an die
Uhr, die dort ihren eigenen Vibrationsmotor triggert.

## Warum nötig

Die App hat zwei Betriebsmodi (Setup-Tab):

- **"Ohne Uhr"** (Standalone): Das Handy berechnet alles selbst und vibriert
  auch selbst (siehe Abschnitt 7 der Spezifikation). Keine BLE-Verbindung
  nötig.
- **"Mit Uhr"**: Das Handy berechnet weiterhin alles selbst (unverändert —
  siehe Variante A in `BLE_Protokoll.md`, "Handy = GPS-Sensor"), aber die
  Haptik soll auf der Uhr passieren, nicht mehr am Handy in der Tasche/
  Schwimmweste. Dafür schickt das Handy nur noch den Muster-**Code** an die
  Uhr; die Uhr entscheidet nichts selbst, sie spielt nur exakt das
  angeforderte Muster ab.

## Neue Characteristic

```
Characteristic Haptik:  6f6e0005-b5a3-f393-e0a9-e50e24dcca9e
  Properties:             NOTIFY (Handy -> Uhr)
  Payload:                1 Byte, Muster-Code (siehe Tabelle)
```

Gehört zum selben Service wie die übrigen drei Characteristics
(`6f6e0001-...`), UUIDs sind wie im Hauptdokument Platzhalter — vor dem
produktiven Einsatz final festlegen.

## Muster-Codes (1:1 zu Abschnitt 7 der Spezifikation)

| Code | Bedeutung | Vibrationsmuster |
|---|---|---|
| 1 | `STEP1` | Zwischenschritt |
| 2 | `DONE2` | Vorgang erfolgreich abgeschlossen |
| 3 | `HEADER3` | Ungünstiger Wind-Shift |
| 4 | `ERROR4` | Fehler / Timeout |
| 5 | `LAKE_WARN5` | See-Rand-Warnung |
| 6 | `ROUNDING6` | Boje gerundet |
| 7 | `MANEUVER_CMD` | Manöver-Kommando (kräftig/lang, muss sich abheben) |
| 8 | `START_SIGNAL` | Start bei 0:00 (kräftig) |

Die Uhr-Firmware muss diese Codes 1:1 auf ihre eigenen lokalen
Vibrationsmuster abbilden — die Bedeutung ist identisch zu Abschnitt 7,
nur die Quelle des Auslösers wechselt vom Handy-Motor zum Uhr-Motor.

## Verhalten beim Umschalten

- Wechselt der Nutzer im Setup-Tab von "Ohne Uhr" zu "Mit Uhr": Der
  Foreground-Service (GATT-Server) startet. Sobald tatsächlich eine Uhr
  verbunden ist, vibriert die App ab sofort selbst nicht mehr, sondern
  sendet stattdessen Notifies auf dieser Characteristic.
- Wechselt der Nutzer zurück zu "Ohne Uhr": Foreground-Service stoppt, die
  App vibriert wieder selbst.
- **Automatischer Fallback:** Ist im Modus "Mit Uhr" gerade **keine** Uhr
  verbunden (außer Reichweite, ausgeschaltet, noch nicht gekoppelt), prüft
  die App das bei JEDEM einzelnen Vibrationsereignis neu (`isConnected` am
  GATT-Server) und vibriert in diesem Fall automatisch am Handy — es soll
  nie ein Signal verloren gehen, nur weil die Uhr gerade nicht erreichbar
  ist. Sobald die Uhr (wieder) verbindet, wechseln nachfolgende Ereignisse
  automatisch zurück auf BLE, ganz ohne dass der Nutzer etwas umschalten
  muss. Umgesetzt in `SwitchableHaptics` (Package `logic`).

## Testen ohne echte Uhr

Der `segeluhr_ble_tester.ino`-Sketch (ESP32, Central-Rolle) abonniert diese
Characteristic bereits und blinkt bei Empfang eines Kommandos die
Onboard-LED im passenden Muster — praktisch als visueller Vibrations-Ersatz
zum Testen, ganz ohne die eigentliche Uhr-Hardware.
