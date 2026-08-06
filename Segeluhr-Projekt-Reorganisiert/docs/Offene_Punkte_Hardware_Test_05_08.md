# Offene Punkte nach erstem Hardware-Test (05.08.2026)

> Status-Log der heutigen Session: beide Firmwares (Ultra + S3) zum ersten
> Mal auf echter Hardware kompiliert, geflasht und getestet. LoRa-Link und
> Quick-Messages funktionieren Ende-zu-Ende. Diese Datei sammelt, was noch
> nicht fertig/getestet ist, damit die nächste Session direkt weitermachen
> kann, statt von vorne zu suchen.

## ✅ 06.08.2026 (Folgesession): BLE Ultra↔Handy zum ersten Mal getestet — 3 Bugs gefixt

Erster echter Verbindungstest Ultra↔Handy (siehe Punkt oben, war bis dahin
komplett ungetestet). Dabei aufgetreten und gefixt, alle drei in
`Segeluhr_TWatch_Ultra.ino`, kompiliert + geflasht (COM17), noch nicht über
eine längere Session/echten Segeltörn verifiziert:

- **Absturz beim Verbinden**: BLE-Notify-/Connect-Callbacks (eigener
  NimBLE-FreeRTOS-Task) riefen LVGL-Funktionen (`lv_screen_load`,
  `lv_label_set_text`, ...) und I2C-Haptik direkt auf — Race gegen
  `lv_timer_handler()` im `loop()`-Task. Jetzt entkoppelt: Callbacks setzen
  nur noch Flags/Daten (`screenNeedsRefresh`, `pendingConnectSwitchToSegeln`,
  `pendingHapticCode`), `bleTick()` im `loop()` erledigt die eigentliche
  Arbeit. Nebenfund: `bleClient` wurde bei jedem Reconnect neu angelegt ohne
  `NimBLEDevice::deleteClient()` auf den alten — jetzt gefixt (war ein
  Speicherleck bei wiederholten Verbindungsabbrüchen).
- **Segeln-Tabs (Wind/Heim/CD/Man) sprangen ständig zurück zu Nav**:
  `autoFocusTick()` erzwang bei JEDEM Tick (~1x/s durch echte BLE-Notifies)
  den "Ruhezustand" Nav, sobald nichts akut Wichtiges lief — überschrieb
  damit jede manuelle Navigation. Umgebaut auf Flankenerkennung: Nav wird
  nur noch einmalig erzwungen, wenn ein zeitkritischer Zustand (Manöver/
  Countdown/Heimweg) gerade endet, nicht mehr bei jedem Tick.
- **Keine Uhrzeit im Segeln-Modus sichtbar**: der grosse Alltags-Clock
  existiert nur im Alltag-Screen, verschwindet beim automatischen Wechsel
  in den Segeln-Modus. BLE-Zeit-Sync selbst funktionierte bereits korrekt
  (RTC wurde richtig gestellt), war nur nirgends angezeigt. Jetzt zusätzlich
  kleine Uhrzeit in der globalen Statusleiste (oben mittig, auf jedem
  Screen sichtbar).

## 🔴 Als Nächstes testen (höchste Priorität)

- **S3 (Land-Uhr) zeigt eine falsche/alte Zeit an, die sich nicht mehr
  aktualisiert** (06.08. beim heutigen Test aufgefallen). Verdacht: der
  LoRa-Zeit-Sync in `Segeluhr_TWatch_S3.ino` (`loraReceiveTick()`, Flag
  `timeSyncedFromBoat`) synct bewusst nur EINMAL pro Boot der S3 — falls
  das erste empfangene `LoRaStatusPacket` zufällig noch vor dem BLE-Sync
  der Ultra ankam (`timeHour == 0xFF`) oder aus einem der vielen
  Test-Reflashes heute einen veralteten Wert enthielt, bleibt die S3 auf
  diesem falschen Stand hängen, weil weitere Pakete ignoriert werden. Noch
  nicht untersucht/gefixt — vermutlich reicht ein RST der S3 (frischer Boot,
  frischer Erst-Sync), langfristig evtl. besser: laufend synchronisieren
  statt nur einmal, oder zumindest bei grosser Abweichung nachsynchronisieren.
- **Tab-Leisten-Fix auf der Ultra (94px Höhe) ist UNGETESTET** — letzte
  Änderung der Session, danach nicht mehr geflasht-und-geprüft. Bitte als
  Erstes morgen: RST drücken, Segeln- und Alltag-Screen durchklicken, prüfen
  ob die zwei gedrehten Eck-Tabs (Nav/Menu bzw. Uhr/Setup) jetzt vollständig
  sichtbar sind (nicht mehr abgeschnitten) UND gut lesbar in der
  Gehäuse-Ecke sitzen. Falls immer noch abgeschnitten: `OUTER_TAB_LIFT_PX`
  und `lv_tabview_set_tab_bar_size(...)` in `buildSegelnScreen()` /
  `buildAlltagScreen()` weiter anpassen (beide Werte sind an zwei Stellen
  dupliziert, siehe Kommentare im Code).

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
