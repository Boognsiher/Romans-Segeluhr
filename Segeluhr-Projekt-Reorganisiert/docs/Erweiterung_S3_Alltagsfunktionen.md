# Erweiterung: Alltagsfunktionen S3 (Wecker, Schrittzähler, Stoppuhr, Taschenlampe)

> Nicht in der ursprünglichen Spezifikation. Macht die S3 auch außerhalb des
> Segel-Kontexts als normale Alltags-Uhr sinnvoll tragbar.

## Status: 🔧 UMGESETZT, KOMPILIERT (06.08.2026) — noch nicht auf Hardware
getestet. Alle vier Funktionen (Wecker, Stoppuhr, Schrittzähler, Taschen-
lampe) implementiert. Siehe Abschnitt 3 (aktualisiert) für Details und eine
wichtige Hardware-Abweichung von der ursprünglichen Doku-Annahme (RTC-Chip).

## 1. Ziel
Die Land-Uhr soll nicht nur während des Segelns Sinn ergeben, sondern auch
sonst als normale Armbanduhr mit ein paar nützlichen Zusatzfunktionen
tragbar sein — mit möglichst wenig Zusatzaufwand, indem vorhandene Hardware
genutzt wird, die bisher brachliegt.

## 2. Menü-Struktur (Erweiterung des bestehenden Icon-Grids)

Bisheriges Menü: `[Stumm] [Fragen]` + `[Ausschalten]` (breiter Button).
Neu: dritte Kachel `[Alltag]` führt in ein Untermenü mit den Funktionen aus
Abschnitt 3 — hält das Hauptmenü aufgeräumt, statt es mit vielen Kacheln zu
überladen.

```
Menü:  [Stumm] [Fragen]
       [Alltag]
       [   Ausschalten   ]

Alltag-Untermenü:  [Wecker] [Stoppuhr]
                    [Schritte] [Taschenlampe]
                    [ zurück ]
```

## 3. Einzelne Funktionen

### Schrittzähler — ✅ implementiert
- BMA423-Pedometer, den LilyGoLib für die S3 bereits vollständig aktiviert
  (`enableFeature(FEATURE_STEP_CNTR)`, `setStepCounterWatermark(1)` in
  `initSensor()`) — `instance.sensor.getPedometerCounter()` liefert direkt
  den aktuellen Zählerstand, keine eigene Aktivierung nötig.
- Anzeige: Zahl im Schritte-Screen, "Reset"-Button für manuellen Reset.
- **Mitternachts-Reset**: automatisch umgesetzt, aber mit Einschränkung —
  vergleicht bei jedem RTC-Read (1x/Sekunde) den aktuellen Tag gegen den
  zuletzt gesehenen; funktioniert nur, solange die Uhr über Mitternacht
  hinweg durchläuft. Kein NVS-Tracking des letzten Reset-Tages über einen
  Neustart hinweg (offener Punkt, siehe Abschnitt 5).

### Wecker — ✅ implementiert, mit Architektur-Abweichung
- **Wichtige Hardware-Korrektur:** Die reale S3 nutzt laut LilyGoLib
  (`LilyGoWatchS3.h`) einen **PCF8563**, nicht wie hier ursprünglich
  angenommen einen PCF85063A (der sitzt auf der Ultra). Der PCF8563 hat
  zwar ebenfalls ein Alarm-Register, aber LilyGoLib verdrahtet dessen
  Interrupt-Pin für die S3 nur als Deep-Sleep-Wakeup-Quelle, nicht als
  laufende Interrupt-Quelle im Normalbetrieb — ein Hardware-Alarm würde im
  wachen Zustand also gar nicht bemerkt.
- **Lösung:** Software-Vergleich statt Hardware-Alarm-Register. Die RTC
  wird ohnehin jede Sekunde für die Uhrzeit-Anzeige gelesen
  (`mainScreenUpdate()`) — der Wecker vergleicht bei dieser Gelegenheit
  einfach mit (`alarmCheckTick()`). Funktional gleichwertig für den
  Anwendungsfall (Uhr ist ohnehin durchgehend an), nur eben nicht
  Deep-Sleep-kompatibel — was hier aber ohnehin nicht gebraucht wird, die
  Uhr läuft normalerweise durch (Display-Standby statt Deep-Sleep, siehe
  `Erweiterung_Standby_Wecken.md`).
- UI: Std/Min +/- Buttons, Ein/Aus-Toggle-Kachel (grün/grau).
- Weckton: **nur Vibration** (dieselbe Sequenz wie bei Quick-Messages,
  wiederholt alle 2s bis "Stopp" gedrückt wird). **Kein Ton** — die S3 hat
  zwar laut LilyGoLib (`I2SClass player`) einen Lautsprecher-Codec, aber
  einen Ton darüber auszugeben bräuchte eigene I2S-/PCM-Ansteuerung
  (Audio-Buffer, Codec-Setup) - deutlich mehr Aufwand als eine
  Vibrationssequenz und bewusst nicht Teil dieser Session, siehe
  Abschnitt 5.
- **Keine Persistenz**: Weckzeit/Ein-Aus-Zustand übersteht aktuell keinen
  Neustart (offener Punkt, siehe Abschnitt 5).

### Stoppuhr — ✅ implementiert
- Start/Stopp/Reset, große Zeitanzeige (MM:SS.z) — identisches Muster wie
  die bereits vorhandene Stoppuhr auf der Ultra (`Segeluhr_TWatch_Ultra.ino`,
  `alltagScreenTick()`/`cbStopwatch{Toggle,Reset}`), nur 1:1 auf die S3
  übertragen.

### Taschenlampen-Modus — ✅ implementiert
- Vollbild-Overlay auf `lv_layer_top()` (nicht als normaler Menü-Screen,
  damit "Antippen irgendwo" zum Verlassen funktioniert und auch die
  Tab-Leiste überdeckt wird), weißer Hintergrund, maximale Helligkeit.
- Timeout: 90s (im vorgeschlagenen 60-120s-Bereich aus der ersten
  Doku-Version) — hält während der Nutzung aktiv die LVGL-
  Inaktivitätsuhr zurück (`lv_display_trigger_activity()`), damit der
  normale 30s-Display-Standby nicht dazwischenfunkt.

### Nicht Teil dieser Umsetzung (siehe Abschnitt 5)
- **Datum/Wochentag auf dem Hauptscreen**: kleine, unabhängige Ergänzung,
  in dieser Session nicht mitgemacht (war nicht Teil der heutigen
  Prioritätenliste) — technisch trivial, RTC liefert das bereits.
- **Zeitgesteuerter Stumm-Modus**: baut auf dem Stumm-Toggle auf (jetzt
  eine Icon-Kachel statt Switch, siehe
  `Erweiterung_Land_Boot_LoRa_Kommunikation.md`), ebenfalls nicht Teil
  dieser Session.

## 4. Bewusst NICHT umgesetzt (siehe Begründung im Chat-Verlauf)
- **Kompass**: kein Magnetometer auf der S3 verbaut, bräuchte zusätzliche
  Hardware
- **Wetter/Musiksteuerung**: würde dauerhafte BLE-Verbindung zum Handy
  brauchen, widerspricht der bewussten Entscheidung "BLE nur kurz für den
  Fragen-Editor" (siehe `Erweiterung_S3_BLE_Fragen_Editor.md`)
- **GPS-abhängige Funktionen** (Sonnenauf-/untergang etc.): S3 hat kein
  eigenes GPS, nur die Ultra

## 5. Offene technische Punkte
- [x] BMA423-Schrittzähler-API in SensorLib verifiziert — Aktivierung
  passiert schon in LilyGoLib, Auslesen über `getPedometerCounter()`.
  Automatischer Mitternachts-Reset ist NICHT eingebaut, musste selbst
  gebaut werden (siehe Abschnitt 3) — funktioniert nur bei durchlaufender
  Uhr, nicht über einen Neustart hinweg
- [x] PCF85063A-Alarm-API verifiziert — mit wichtigem Befund: reale
  S3-Hardware hat einen PCF8563, dessen Alarm-Interrupt bei dieser
  LilyGoLib-Version im Normalbetrieb nicht verdrahtet ist. Deshalb
  Software-Vergleich statt Hardware-Alarm (siehe Abschnitt 3)
- [ ] Persistenz für Wecker-Einstellung (Weckzeit + Ein/Aus) — noch nicht
  umgesetzt, übersteht aktuell keinen Neustart. Naheliegend: `Preferences`
  (ESP32-NVS), analog zum Muster bei den Klio-Mustern auf der Ultra
  (`docs/Erweiterung_Gesten_Training_Klio.md`)
- [x] UI-Layout des Alltag-Untermenüs — Icon-Grid [Wecker][Stoppuhr] /
  [Schritte][Taschenlampe] / [Zurück], konsistent mit dem Hauptmenü
- [x] Standby-Timeout für Taschenlampe festgelegt: 90s
- [ ] **Ton beim Wecker** (S3 hat laut LilyGoLib einen Lautsprecher-Codec,
  `I2SClass player`) — nicht umgesetzt, bräuchte eigene I2S-/PCM-
  Ansteuerung, deutlich mehr Aufwand als die vorhandene Vibration. Aktuell
  nur Vibration, siehe Abschnitt 3
- [ ] Alles in diesem Dokument ist **kompiliert, aber nicht auf
  Hardware getestet** (06.08.2026) — insbesondere ob der PCF8563-
  Software-Vergleich-Ansatz für den Wecker im Alltag zuverlässig genug ist
