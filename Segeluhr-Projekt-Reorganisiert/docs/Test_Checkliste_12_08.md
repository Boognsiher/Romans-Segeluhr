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

- [ ] Android: **Build → Make Project** — grün? App startet ohne Absturz
- [ ] Ultra: Compile — grün? Flash (COM-Port Ultra)
- [ ] S3 (Land): Compile — grün? Flash (COM-Port S3/Land)
- [ ] Beide Uhren booten sauber, BLE-Verbindung Ultra↔Handy klappt

---

## 2. AN LAND testbar (kein Segeln nötig)

### 2a. Der eigentliche Auslöser: "Home setzen" funktioniert jetzt?
- [ ] Uhr-Menü-Tab → "Home setzen" drücken → Button zeigt kurz Overlay
      "Wegpunkt gesendet" + Vibration
- [ ] **Wichtigster Check**: Handy Setup-Tab → "Heimatpunkt" zeigt jetzt
      tatsächlich Koordinaten (vorher: blieb leer trotz gültigem GPS-Fix)
- [ ] Uhr-Menü zeigt den "Home setzen"-Button jetzt **grün** eingefärbt
      (innerhalb ~1s nach dem Setzen)
- [ ] Gleicher Test für "Boje 1/2 setzen", "Ziel setzen",
      "Comp.-Marke 1/2 setzen" — jeweils grün nach dem Setzen
- [ ] Jeden der 6 Wegpunkte einzeln über den zugehörigen "X löschen"-Button
      löschen → wird auf dem Handy leer, Button wird wieder grau
      (**Bugfix-Check**: früher löschte der einzige "Alle Bojen
      löschen"-Button immer nur Boje 1, egal was eigentlich gemeint war)

### 2b. CD-/Wind-Tab-Aktionen direkt auf der Uhr
- [ ] CD-Tab: "Start" → Countdown läuft (auch am Handy sichtbar)
- [ ] CD-Tab: "Reset" → Countdown stoppt, "--:--"
- [ ] CD-Tab: "Min." (Sync nächste Minute) während laufendem Countdown →
      Restzeit springt auf die nächste volle Minute
- [ ] CD-Tab: "Wettfahrt stoppen" während laufender Wettfahrt (Countdown
      durchgelaufen) → Rückfrage-Dialog "Wettfahrt stoppen?" mit Ja/Nein
- [ ] "Ja, stoppen" → CD-Tab zeigt wieder "--:--" (**Bugfix-Check**:
      früher blieb die Restzeit als "+m:ss" munter weiterlaufen, weil
      `countdownEngine.reset()` fehlte)
- [ ] Wind-Tab: "Kalibrieren starten"/"Kalibrierung abbrechen" — Befehl
      kommt am Handy an (Wind-Tab-Status wechselt), keine Fehlermeldung

### 2c. Statusleiste + Nav-Tab
- [ ] Statusleiste (BLE/Akku/Uhrzeit) oben auf dem Nav-Tab — jetzt
      lesbar/nicht mehr vom Gehäuserand verdeckt? (y=34 ist ein Schätzwert,
      evtl. muss nachjustiert werden)
- [ ] Nav-Tab-Manöver-Zeile: ohne aktive Wettfahrt grau ("kein Manöver")
- [ ] Training-Tab → Racemode aktivieren, ein paar Schritte in Richtung
      Boje/gegen den Wind gehen → Nav-Tab-Zeile wird **rot** ("WENDE!"/
      "HALSE!"), bei direktem Kurs **grün** ("kein Manöver")

### 2d. Heimweg-Ankunft (mit Trick an Land simulierbar)
- [ ] Heimatpunkt auf die **aktuelle Position** setzen (siehe 2a), Heimweg-
      Modus aktivieren
- [ ] **Sollte sofort automatisch wieder stoppen** (< 20m Radius bereits
      erreicht) — Handy zeigt Status "Heimweg-Ziel erreicht — Modus
      gestoppt.", Heimweg-Schalter springt zurück auf "aus"
- [ ] S3 (Land) sollte kurz danach eine eingehende Quick-Message
      **"BIN ZURÜCK!"** zeigen (Ja/Nein-Frage-Overlay, auch wenn die
      Antwort inhaltlich egal ist)

### 2e. Bojen-Kartenauswahl (App)
- [ ] Training-Tab → Boje 1/2 → neuer "Karte"-Button neben "Setzen"
- [ ] Kartenansicht öffnet sich, antippen setzt/verschiebt einen Marker
- [ ] "Übernehmen" → Boje-Koordinaten übernommen (Trainings-Tab zeigt sie)
- [ ] Gleicher Test für Comp.-Marke 1/2 im Setup-Tab
- [ ] "Abbrechen" verwirft die Auswahl, alte Koordinate bleibt unverändert

### 2f. S3 (Land) — eigene Akku-Anzeige
- [ ] Status-Tab zeigt jetzt zusätzlich "Akku Uhr: X%" zwischen Uhrzeit und
      Verbindungsindikator

### 2g. Klio-Training — nur die Mechanik (nicht die Kalibrierqualität)
- [ ] Uhr-Menü → Sektion "Gesten-Training (Klio)" sichtbar, Status-Zeile
      zeigt "Ja: nicht trainiert · Nein: nicht trainiert"
- [ ] "Ja trainieren" → Dialog öffnet sich, zeigt "1/6: Trapez, steuernd —
      Backbord" gross + Klio-Fortschrittstext darunter
- [ ] "Nächste Haltung" blättert durch alle 6 Einträge, dann zurück auf 1/6
- [ ] Handgelenk ein paar Mal bewusst heben (auch ohne die volle
      Kalibrierhaltung) → Fortschrittstext ändert sich (Prozent oder
      "Bewegung nicht wiederholend genug" o.ä.)
- [ ] "Abbrechen" → Dialog schliesst sich, Status bleibt "nicht trainiert"
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
