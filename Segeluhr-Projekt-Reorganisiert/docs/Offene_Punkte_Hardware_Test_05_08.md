# Offene Punkte nach erstem Hardware-Test (05.08.2026)

> Status-Log der heutigen Session: beide Firmwares (Ultra + S3) zum ersten
> Mal auf echter Hardware kompiliert, geflasht und getestet. LoRa-Link und
> Quick-Messages funktionieren Ende-zu-Ende. Diese Datei sammelt, was noch
> nicht fertig/getestet ist, damit die nächste Session direkt weitermachen
> kann, statt von vorne zu suchen.

## ✅ 09.08.2026 (Folgesession): beide "Als Nächstes"-Punkte von unten erledigt

- **S3-Zeitanzeige erneut getestet: lief korrekt.** Vermutlich lag der
  06.08.-Fehler tatsächlich an einem der vielen Test-Reflashes an diesem Tag
  (siehe Theorie unten) - ein sauberer Boot-Zyklus hat gereicht. **Root
  Cause im Code bleibt unverändert** (`loraReceiveTick()`/
  `timeSyncedFromBoat` synct weiterhin nur einmal pro S3-Boot) - Roman-
  Entscheidung 09.08.: aktuell kein robusterer Fix nötig, im Zweifel S3
  einfach per RST neu starten.
- **94px-Tab-Leisten-Fix auf der Ultra jetzt auf Hardware verifiziert:**
  gedrehte Eck-Tabs (Nav/Menu bzw. Uhr/Setup) sitzen sauber sichtbar in der
  Gehäuse-Ecke, nichts mehr abgeschnitten.
- **Neuer Bug gefunden + gefixt: "Segelmodus erzwingen" hatte keinen Weg
  zurück.** Der Schalter dafür sitzt im Setup-Tab des Alltags-Screens, der
  aber genau dann verlassen wird (Wechsel zu `screenSegeln`) - und
  `appModeTick()` überspringt den automatischen Rückfall komplett, solange
  `forceSegelnMode` gesetzt ist (auch ohne BLE-Verbindung). Sackgasse. Fix:
  neuer Button "Segelmodus beenden (zurück zu Alltag)" ganz oben im
  Segeln-Menü (`cbExitForcedSegeln()` in `Segeluhr_TWatch_Ultra.ino`),
  kompiliert + auf COM17 geflasht + auf Hardware verifiziert.
- Nebenbei geklärt: die übrigen Menü-Buttons im Segeln-Menü (Countdown
  Start, Wind-Kalibrierung, Training, Wegpunkte, ...) senden nur BLE-Befehle
  ans Handy - ohne verbundenes Handy tun sie beim Testen mit erzwungenem
  Segelmodus erwartungsgemäß nichts, das ist kein Bug.

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

## ✅ Beide untenstehenden Punkte erledigt (siehe 09.08.-Eintrag ganz oben)

- ~~S3 (Land-Uhr) zeigt eine falsche/alte Zeit an~~ — 09.08. erneut
  getestet, lief korrekt. Root Cause im Code unverändert (Einmal-Sync pro
  Boot), siehe oben — bewusst nicht weiter verändert (Roman-Entscheidung).
- ~~Tab-Leisten-Fix auf der Ultra (94px Höhe) ist UNGETESTET~~ — 09.08. auf
  Hardware verifiziert, Eck-Tabs sitzen sauber sichtbar.

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
