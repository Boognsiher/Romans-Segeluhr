# Offene Punkte nach erstem Hardware-Test (05.08.2026)

> Status-Log der heutigen Session: beide Firmwares (Ultra + S3) zum ersten
> Mal auf echter Hardware kompiliert, geflasht und getestet. LoRa-Link und
> Quick-Messages funktionieren Ende-zu-Ende. Diese Datei sammelt, was noch
> nicht fertig/getestet ist, damit die nächste Session direkt weitermachen
> kann, statt von vorne zu suchen.

## 🔴 Als Nächstes testen (höchste Priorität)

- **Tab-Leisten-Fix auf der Ultra (94px Höhe) ist UNGETESTET** — letzte
  Änderung der Session, danach nicht mehr geflasht-und-geprüft. Bitte als
  Erstes morgen: RST drücken, Segeln- und Alltag-Screen durchklicken, prüfen
  ob die zwei gedrehten Eck-Tabs (Nav/Menu bzw. Uhr/Setup) jetzt vollständig
  sichtbar sind (nicht mehr abgeschnitten) UND gut lesbar in der
  Gehäuse-Ecke sitzen. Falls immer noch abgeschnitten: `OUTER_TAB_LIFT_PX`
  und `lv_tabview_set_tab_bar_size(...)` in `buildSegelnScreen()` /
  `buildAlltagScreen()` weiter anpassen (beide Werte sind an zwei Stellen
  dupliziert, siehe Kommentare im Code).
- **BLE-Verbindung Ultra ↔ Handy** — heute komplett ungetestet. Nur
  Uhr-zu-Uhr-LoRa wurde geprüft. `currentBoatState` war die ganze Session
  über `IDLE`, weil kein Handy verbunden war.

## 🟡 Gestensteuerung — unkalibriert, nur ein Messpunkt

- `GESTURE_TILT_TARGET_ANGLE_DEG = -30.0f` (in `Segeluhr_TWatch_Ultra.ino`)
  basiert auf **genau einer** Messung am Schreibtisch (Uhr flach in der Hand
  gehalten, keine echte Trageposition am Handgelenk, keine Krängung/
  Wellenbewegung). Muss vor dem ersten Segeltörn nachkalibriert werden.
- `GESTURE_SHAKE_MIN_AMPLITUDE = 8.0f` — bisher **gar nicht** verifiziert
  (nur der Tilt/JA-Pfad wurde getestet, Schütteln/NEIN per Geste nicht).
- Debug-Logging dafür ist bereits eingebaut (`gestureTick()`, Zeilen mit
  `[Gesten]`-Präfix, gedrosselt auf 1x/Sekunde) — beim nächsten Test einfach
  seriellen Monitor auf COM-Port der Ultra mithören lassen, während die
  Geste probiert wird, dann Zahlen ablesen/Schwellenwerte anpassen.
- Ob `BHI260AP online` zuverlässig bei jedem Boot ist, war nicht in jedem
  Boot-Log eindeutig sichtbar (evtl. Wettlaufbedingung beim Verbindungs-
  aufbau des Monitors) — beim nächsten Test auf die Zeile
  `[Gesten] BHI260AP online, Sensoren aktiviert` direkt nach dem Boot achten.

## 🟢 Heute gefixt & verifiziert (zur Erinnerung, falls Rückfragen)

- **Selbstempfangs-Bug**: beide Uhren empfingen anfangs ihre eigenen gerade
  gesendeten LoRa-Pakete zurück (DIO1-Interrupt-Überschneidung TX/RX).
  Gefixt durch Absender-Check (`sender`/`responder`-Feld) + Verwerfen des
  Empfangs-Flags direkt nach eigenem Senden.
- **LoRa-Status-Broadcast**: Ultra → S3, Sequenznummer/RSSI/SNR/AES-
  Entschlüsselung alle verifiziert.
- **Quick-Messages**: S3 → Ultra (Frage) und Ultra → S3 (Antwort, JA per
  kurzem und NEIN per langem Tasterdruck) beide Richtungen verifiziert.
- **Taster auf der Ultra**: GPIO0-Annahme war richtig — Verwechslung war
  ein anderer physischer Knopf am Gehäuse, kein Code-Problem.

## 🔵 Bekannte, bewusst nicht behobene Lücken

- **Ton bei Quick-Messages auf der Land-Uhr**: laut Doku offener Punkt, nur
  Vibration implementiert (respektiert Stumm-Modus).
- **`distanceRemainingM`**: grobe Schätzung aus `ETA × aktuelle
  Geschwindigkeit`, kein echtes Distanz-Feld im BLE-Protokoll.
- **Physischer Taster-Fallback auf der S3**: bewusst nicht verdrahtet
  (Land-Uhr ist touch-only, siehe README im S3-Ordner).
- **Duty-Cycle/Kanalwahl LoRa Schweiz**: vor echtem Praxiseinsatz gegen
  BAKOM-Vorgaben prüfen (siehe `PROJEKT_STATUS.md`).

## 🧹 Aufräumen, sobald alles stabil läuft

Beide `.ino`-Dateien enthalten testweise Serial-Debug-Ausgaben, markiert mit
`// TODO(Test-Debug)` bzw. `TODO(Test-Debug):` im Kommentar direkt davor:
- `[LoRa TX]`/`[LoRa RX]` bei jedem gesendeten/empfangenen Paket
- `[Quick-Msg TX]`/`[Quick-Msg RX]` bei jeder Quick-Message
- `[Taster]` bei jedem Tastendruck
- `[Gesten]` Pitch/Roll/Heading/Accel-Werte (1x/Sekunde, wenn Sensor online)

Diese waren fürs heutige Debugging sehr hilfreich (v.a. um den
Selbstempfangs-Bug zu finden) — bewusst NICHT entfernt, bis die restlichen
offenen Punkte oben (v.a. Gesten-Kalibrierung) durch sind. Erst danach
rausnehmen oder hinter ein `#define DEBUG_LORA` schalten.
