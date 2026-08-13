# Test-Checkliste 12.08.2026 — grosse Nachtrags-Runde

Alles, was am 12.08. implementiert, aber **noch nicht auf Hardware
getestet** ist. In zwei Teile geordnet: Abschnitt 2 kann komplett **an
Land** (Steg, zuhause, stehend/gehend mit GPS-Fix) erledigt werden, bevor
überhaupt das Boot ins Wasser kommt — Abschnitt 4 braucht echtes Segeln.

Bei jedem Fehler: Fehlermeldung/Screenshot einfach in den Claude-Code-Chat
kopieren, nicht selbst rumdebuggen.

---

## 0. Vorbereitung

- [ ] `git pull` im Repo-Ordner
- [ ] Android Studio: Projekt öffnen, Gradle-Sync abwarten, App bauen +
      aufs Handy installieren
- [ ] Arduino IDE bereit, beide COM-Ports bekannt (Ultra + S3/Land)
- [ ] **Beide Firmwares zusammen flashen** — heute gab es Protokoll-
      Änderungen (`CHAR_WAYPOINTS_STATUS_UUID`, `HOME_FLAG_ARRIVED`,
      `BIN_ZURUECK` in `QuickMessages.h`), altes/neues Gemisch führt zu
      stillschweigend verworfenen Paketen, keinem Crash

---

## 1. Kompilieren + flashen

- [x] Android: **Build → Make Project** — grün? App startet ohne Absturz
      (12.08. Abend: `assembleDebug` UP-TO-DATE + `adb install -r`, startet
      ohne `AndroidRuntime`-Fatal-Exception)
- [x] Ultra: Compile — grün? Flash (COM-Port Ultra) (COM17, 12.08. Abend,
      inkl. Nachbesserungen — siehe unten)
- [x] S3 (Land): Compile — grün? Flash (COM-Port S3/Land) (COM16, 12.08.
      Abend, inkl. Nachbesserung — siehe unten)
- [x] Beide Uhren booten sauber, BLE-Verbindung Ultra↔Handy klappt
- [x] **Ultra erneut flashen (13.08.-Stand)** — 13.08. abends mehrfach
      geflasht (Startlinie-Bias-Wegpunkte auf der Uhr, Wind-Kalibrier-
      Feedback, Uhr-Akku in der Statusleiste, drei Klio-Bugfixes — siehe
      2g unten)

---

## 2. AN LAND testbar (kein Segeln nötig)

### 2a. Der eigentliche Auslöser: "Home setzen" funktioniert jetzt?
- [x] **Wichtigster Check**: Handy Setup-Tab → "Heimatpunkt" zeigt jetzt
      tatsächlich Koordinaten (vorher: blieb leer trotz gültigem GPS-Fix)
      — 12.08. Abend bestätigt, End-to-End verifiziert
- [x] Uhr-Menü-Tab → "Home setzen" drücken → Button zeigt kurz Overlay
      "Wegpunkt gesendet" + Vibration (13.08. bestätigt: "das hat alles
      geklapt")
- [x] Uhr-Menü zeigt den "Home setzen"-Button jetzt **grün** eingefärbt
      (innerhalb ~1s nach dem Setzen) — 13.08. bestätigt
- [x] Gleicher Test für "Boje 1/2 setzen", "Ziel setzen",
      "Comp.-Marke 1/2 setzen" — jeweils grün nach dem Setzen — 13.08.
      bestätigt, dabei zusätzlich Pin-Ende/Boot-Ende NEU auf der Uhr
      setzbar gemacht (Roman-Wunsch, siehe
      docs/Erweiterung_Startlinie_Bias.md Abschnitt 5) und ebenfalls
      getestet
- [x] Jeden der 6 Wegpunkte einzeln über den zugehörigen "X löschen"-Button
      löschen → wird auf dem Handy leer, Button wird wieder grau — 13.08.
      bestätigt (**Bugfix-Check**: früher löschte der einzige "Alle Bojen
      löschen"-Button immer nur Boje 1, egal was eigentlich gemeint war)

**Dabei 12.08. Abend gefunden+gefixt (nicht Teil der ursprünglichen
Checkliste):** Absturz beim ersten Drücken von "Home setzen" (Stack-
Overflow im `loopTask` der Ultra, siehe PROJEKT_STATUS.md Ultra-Zeile) —
behoben, seither mehrfach ohne Absturz reproduziert. Ausserdem zwei
Hardware-Feedback-Nachbesserungen: Ultra-Statusleiste y=34→68 + BLE/Akku
nur noch auf Nav-/Uhr-Tab sichtbar, S3-Akku-Anzeige vom Haupt- in den
Detail-Tab verschoben (kollidierte dort mit dem Empfangs-Indikator).

### 2b. CD-/Wind-Tab-Aktionen direkt auf der Uhr
- [x] CD-Tab: "Start" → Countdown läuft (auch am Handy sichtbar) — 13.08.
      bestätigt ("das hat alles geklapt")
- [x] CD-Tab: "Reset" → Countdown stoppt, "--:--" — 13.08. bestätigt
- [x] CD-Tab: "Min." (Sync nächste Minute) während laufendem Countdown →
      Restzeit springt auf die nächste volle Minute — 13.08. bestätigt
- [x] CD-Tab: "Wettfahrt stoppen" während laufender Wettfahrt (Countdown
      durchgelaufen) → Rückfrage-Dialog "Wettfahrt stoppen?" mit Ja/Nein —
      13.08. bestätigt
- [x] "Ja, stoppen" → CD-Tab zeigt wieder "--:--" — 13.08. bestätigt
      (**Bugfix-Check**: früher blieb die Restzeit als "+m:ss" munter
      weiterlaufen, weil `countdownEngine.reset()` fehlte)
- [x] Wind-Tab: "Kalibrieren starten"/"Kalibrierung abbrechen" — Befehl
      kommt am Handy an, keine Fehlermeldung — 13.08. bestätigt. Dabei
      Roman-Feedback aufgenommen: kein Feedback AUF DER UHR, dass die
      Kalibrierung gestartet ist — behoben (Overlay+Vibration wie bei den
      Wegpunkt-Buttons). Ein STEHENBLEIBENDES Laufend-Zeichen ist bewusst
      zurückgestellt, siehe docs/Erweiterung_TWatch_Ultra_NavRedesign.md
      Abschnitt 6.1.

### 2c. Statusleiste + Nav-Tab
- [x] Statusleiste (BLE/Akku/Uhrzeit) oben auf dem Nav-Tab — jetzt lesbar,
      Position (y=68) passt. Dabei Roman-Wunsch aufgenommen: Akku-Feld
      zeigte nur Handy-Akku ("Bat --", weil der nur zusammen mit einem
      GPS-Fix gesendet wird) — zeigt jetzt zusätzlich den Uhr-Akku
      ("H X% U Y%"), unabhängig vom GPS-Fix.
- [x] Nav-Tab-Manöver-Zeile: ohne aktive Wettfahrt grau ("kein Manöver") —
      13.08. bestätigt
- [ ] Training-Tab → Racemode aktivieren, ein paar Schritte in Richtung
      Boje/gegen den Wind gehen → Nav-Tab-Zeile wird **rot** ("WENDE!"/
      "HALSE!"), bei direktem Kurs **grün** ("kein Manöver") — 13.08. nicht
      testbar ("kann ich nicht testen, mach ich sonst mal"), auf später
      verschoben

### 2d. Heimweg-Ankunft (mit Trick an Land simulierbar) — 13.08. übersprungen, noch offen
- [ ] Heimatpunkt auf die **aktuelle Position** setzen (siehe 2a), Heimweg-
      Modus aktivieren
- [ ] **Sollte sofort automatisch wieder stoppen** (< 20m Radius bereits
      erreicht) — Handy zeigt Status "Heimweg-Ziel erreicht — Modus
      gestoppt.", Heimweg-Schalter springt zurück auf "aus"
- [ ] S3 (Land) sollte kurz danach eine eingehende Quick-Message
      **"BIN ZURÜCK!"** zeigen (Ja/Nein-Frage-Overlay, auch wenn die
      Antwort inhaltlich egal ist)

### 2e. Bojen-Kartenauswahl (App)
- [x] Training-Tab → Boje 1/2 → neuer "Karte"-Button neben "Setzen" — 13.08.
      bestätigt ("klappt")
- [x] Kartenansicht öffnet sich, antippen setzt/verschiebt einen Marker
- [x] "Übernehmen" → Boje-Koordinaten übernommen (Trainings-Tab zeigt sie)
- [x] Gleicher Test für Comp.-Marke 1/2 im Setup-Tab
- [x] "Abbrechen" verwirft die Auswahl, alte Koordinate bleibt unverändert
- Roman-Wunsch aufgenommen (bewusst zurückgestellt bis zum nächsten Umbau):
  Karte soll die bereits gesetzten Wegpunkte zusätzlich anzeigen, siehe
  docs/Erweiterung_Boje_Kartenauswahl.md Abschnitt 4.

### 2f. S3 (Land) — eigene Akku-Anzeige
- [ ] Status-Tab zeigt jetzt zusätzlich "Akku Uhr: X%" zwischen Uhrzeit und
      Verbindungsindikator

### 2g. Klio-Training — nur die Mechanik (nicht die Kalibrierqualität)
- [x] Uhr-Menü → Sektion "Gesten-Training (Klio)" sichtbar, Status-Zeile
      zeigt "Ja: nicht trainiert · Nein: nicht trainiert" — 13.08. bestätigt
- [x] "Ja trainieren" → Dialog öffnet sich, zeigt "1/6: Trapez, steuernd —
      Backbord" gross + Klio-Fortschrittstext darunter — **13.08. zuerst
      "Klio nicht verfügbar"**, Root Cause gefunden+gefixt (Firmware-Upload-
      Reihenfolge, siehe docs/Erweiterung_Gesten_Training_Klio.md
      Abschnitt 5c) — Dialog öffnet sich seither korrekt
- [ ] "Nächste Haltung" blättert durch alle 6 Einträge, dann zurück auf 1/6
      — noch nicht gezielt durchgeklickt (Session ging in Fortschrittstext-
      Debugging über)
- [ ] Handgelenk ein paar Mal bewusst heben (auch ohne die volle
      Kalibrierhaltung) → Fortschrittstext ändert sich (Prozent oder
      "Bewegung nicht wiederholend genug" o.ä.) — **13.08. NICHT
      bestätigt**: Fortschrittstext bleibt trotz kräftiger Bewegung immer
      auf dem Platzhalter stehen. Zweiter Bug gefunden+gefixt (`k_state`-
      Software-Cache, per Chip-Rücklese verifiziert), Callback feuert aber
      trotzdem nicht — dritter, ungelöster Punkt, siehe
      docs/Erweiterung_Gesten_Training_Klio.md Abschnitt 5c. Bewusst auf
      den Wassertest verschoben (mehr Zeit, andere Bewegungsqualität testen)
- [ ] "Abbrechen" → Dialog schliesst sich, Status bleibt "nicht trainiert"
      — noch nicht gezielt geprüft
- [ ] **Das eigentliche, vollständige Training** (alle 6 Haltungen inkl.
      Trapez, siehe Abschnitt 4) erst auf dem Wasser — hier nur prüfen,
      dass die Uhr-Bedienung an sich funktioniert

### 2h. Gesten-Fehlalarm-Schutz — Mechanik
Braucht eine offene Frage zum Testen — am einfachsten über die S3 (Land)
eine Quick-Message an die Ultra schicken (Fragen-Auswahl → Taster lang),
dann auf der Ultra reagieren:
- [ ] Handgelenk heben (Ja-Geste) → **nicht sofort gesendet**, stattdessen
      Anzeige "Antwort: JA (antippen zum Abbrechen)" für ~3s
- [ ] Anzeige antippen während der 3s → Antwort wird verworfen, S3 bekommt
      nichts
- [ ] Nochmal Ja-Geste, diesmal 3s abwarten ohne einzugreifen → Antwort
      geht automatisch raus, S3 zeigt "JA" empfangen
- [ ] Gleicher Test mit Schütteln (Nein) und mit einem Tastendruck
      während der 3s (sollte ebenfalls abbrechen)
- [ ] Wende/Halse-Sperre: Trainings-Racemode mit Manöver-Vorschlag
      auslösen (Haptik "WENDE!") → für die nächsten ~20s wird KEINE
      Geste als Antwort ausgewertet, selbst wenn gerade eine Frage offen
      ist (im Serial-Log sichtbar: "Ja-Geste ignoriert (kurz nach
      Wende/Halse)")

### 2i. Klio: mehrere Trainings-Durchläufe kombinieren (NEU 13.08., experimentell)
Siehe `docs/Erweiterung_Gesten_Training_Klio.md` Abschnitt 5b — unverifiziert,
ob `learning_reset=false` wirklich an ein bereits gespeichertes Muster
anknüpft:
- [ ] "Ja trainieren", kurz ein paar einfache Wiederholungen (z.B. im
      Stehen), Training abschliessen lassen oder "Abbrechen" nach
      genug Fortschritt
- [ ] Erkennungstest: Handgelenk heben → wird JA erkannt?
- [ ] Nochmal "Ja trainieren" drücken, jetzt MIT anderen/neuen
      Wiederholungen (andere Haltung) — **im Serial-Log prüfen**: steht dort
      "ERWEITERT bestehendes Muster, reset=false" (statt "NEUES Muster")?
- [ ] Nach Abschluss beide Bewegungsarten testen (die ursprüngliche UND die
      neue) — werden BEIDE erkannt? Dann funktioniert das Kombinieren wie
      erhofft. Wird nur noch die neue erkannt, verhält sich `reset=false`
      effektiv wie ein Neustart — dann bleibt's beim bekannten Weg (eine
      einzige durchgehende Session über alle Situationen, siehe Abschnitt 4
      unten)

### 2j. Standby nur noch im Alltags-Modus (NEU 13.08.)
Siehe `docs/Erweiterung_Standby_Wecken.md` — Ultra-Abschnitt:
- [ ] Segeln-Modus aktiv (BLE verbunden), 30s ohne jede Interaktion warten
      → Display bleibt **an**, volle Helligkeit (bisher ging's nach 30s aus)
- [ ] Alltags-Modus (BLE trennen bzw. "Segelmodus beenden"), 30s ohne
      Interaktion warten → Display geht wie gewohnt aus (unverändertes
      Verhalten)
- [ ] Aus dem Alltags-Standby (Display aus) heraus BLE verbinden/in
      Segeln-Modus wechseln → Display wacht sofort automatisch auf, kein
      Taster-/Geste-Ereignis nötig

### 2k. Akku-Verbrauchs-Log (NEU 13.08.)
Siehe `docs/Erweiterung_Akku_Tracking.md`. Serieller Monitor bei 115200 Baud:
- [ ] Nach dem Boot im Serial-Log: "[Akku-Log] Bereit (...)" — FFat-Mount
      erfolgreich? (Falls "FFat-Mount fehlgeschlagen": Bug, zurückmelden)
- [ ] 1-2 Minuten warten, dann `BATLOG DUMP` eintippen → Header-Zeile +
      mindestens 1-2 Datenzeilen mit plausiblen Werten (Datum/Zeit/Modus/
      Display/BLE/Laden/Akku-%)
- [ ] `BATLOG STATUS` → Dateigrösse + Flash-Nutzung plausibel (nicht 0,
      nicht riesig)?
- [ ] `BATLOG CLEAR` → danach `BATLOG DUMP` zeigt nur noch den Header
- [ ] Bei Gelegenheit (auch über mehrere Tage/Sessions): Ultra länger im
      Segeln-Modus laufen lassen, danach `BATLOG DUMP` sichern — erste
      echte Drain-Rate-Daten für den Vergleich altes Standby-Verhalten vs.
      neues Dauerhaft-an

---

## 3. Kompilieren-Regression-Check (falls App/Firmware getrennt geflasht wurden)

- [ ] Bestehende Funktionen aus den letzten Tagen laufen weiter: GPS-Fix,
      BLE-Verbindungsanzeige, Quick-Messages, Bojen-Rundungserkennung
      (Banner + Geste/Taster) — keine Regression durch die heutigen
      Protokoll-Erweiterungen

---

## 4. NUR AUF DEM WASSER testbar

- [ ] **Klio-Training vollständig durchführen** — WICHTIG: laut heutigem
      Befund ist ein Trainingslauf EINE durchgehende Session (5min
      Timeout). "Ja trainieren" einmal drücken, dann bei laufendem
      Training alle 6 Haltungen aus der Anzeige durchgehen (inkl. Trapez),
      NICHT zwischendurch neu starten. Danach denselben Ablauf für "Nein".
      Beobachten: läuft die Fortschrittsanzeige beim Haltungswechsel
      weiter oder bricht sie ab?
- [ ] Im Anschluss die Fehlalarm-Tests aus dem Kalibrierungs-Protokoll:
      Wende, Halse, Trapez-Ein-/Aushaken jeweils mehrfach — sollen NICHT
      als Geste erkannt werden (Serial-Log `[Klio] Erkannt: ...`
      mitlesen, auch wenn's dank der neuen Wende/Halse-Sperre eh
      unterdrückt wird)
- [ ] Manöver-Timing (10s Start-Grace, 15s Push-Cooldown) — fühlt sich das
      beim echten Segeln richtig an, oder zu träge/zu häufig?
- [ ] Boots-Kalibrierung (Wendewinkel), Vorwind-Winkel-Lernen — weiterhin
      offen seit 10./11.08.
- [ ] Session-Distanz-Hochzählen während echter Bewegung
- [ ] Alle drei neuen Fehlalarm-Schutz-Platzhalterwerte (3s
      Bestätigungsfenster, Klio-`count`-Schwelle 2.0, 20s Wende/Halse-
      Sperre) nach dem ersten Eindruck ggf. nachjustieren

---

## 5. Danach: Stand sichern

- [ ] `PROJEKT_STATUS.md` Status-Spalten/"Zuletzt getestet"-Daten
      aktualisieren (welche Punkte oben tatsächlich grün waren)
- [ ] Bei erfolgreichem Test: `git push` (lokaler Commit vom 12.08. ist
      schon da) — auch Zwischenstände, nicht erst am Ende des Tages
