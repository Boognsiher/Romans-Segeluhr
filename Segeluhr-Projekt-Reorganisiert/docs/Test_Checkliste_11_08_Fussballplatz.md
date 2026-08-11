# Test-Checkliste heute Abend (11.08.2026) — Fussballplatz statt Wasser

Kontext: `PROJEKT_STATUS.md` hatte mehrere GPS-abhängige Funktionstests
für "11.08. draussen" vorgemerkt (siehe "Offene GPS-abhängige Funktionstests"
dort). Ein Fussballplatz ist dafür gut geeignet: flach, offen, gute
GPS-Sicht, feste Referenzpunkte (Torlinien, Mittelkreis, Eckfahnen) zum
Abschreiten bekannter Distanzen — und man kann laufen/joggen, um Bewegung
zu simulieren, ohne aufs Wasser zu müssen.

**Wind-Kalibrierung geht doch, per Simulation:** `WindEngine.tickCalibration()`
ist komplett GPS-Kurs-basiert (COG/Speed), es gibt keinen echten Windsensor
— das Boot "kennt" Wind nur über gehaltene Kurse. Zwei Schläge zügig
ablaufen/joggen mit einer Wende dazwischen reicht also, um den Mechanismus
(inkl. Wendewinkel-Kalibrierung) sauber zu testen. Details dazu in 2f
unten. Nicht simulierbar ist nur die *Bedeutung* des Ergebnisses (der
"Wind" entspricht keinem echten Wind) — als Funktionstest zählt das nicht.

Bei jedem Fehler: Fehlermeldung/Screenshot einfach in den Claude-Code-Chat
kopieren, nicht selbst rumdebuggen.

---

## 0. Vorbereitung (bevor es losgeht)

- [ ] `git pull` im Repo-Ordner (aktuellen Stand holen)
- [ ] App frisch bauen + auf dem Handy installieren, startet ohne Absturz
- [ ] Falls Uhr(en) mit dabei: geladen, COM-Ports bekannt (falls vorher noch
      geflasht werden muss — siehe `docs/Heute_Abend_Ablauf.md` Abschnitt C0)
- [ ] Setup-Tab → Rolle "Auf dem Boot" wählen (auch ohne Boot — die
      GPS-/Segel-Logik läuft nur in diesem Modus)
- [ ] Setup-Tab → "Diagnose-Log" → Schalter "Aufzeichnung aktiv" prüfen
      (Standard AN) — schreibt 1×/s den kompletten Zustand mit, hilfreich
      zur späteren Auswertung
- [ ] Setup-Tab → richtiges Boots-Profil aktiv (spielt für die GPS-Tests
      unten keine Rolle, aber gleich mitprüfen)
- [ ] Falls Uhr dabei: Betriebsmodus "Mit Uhr", koppeln, grünes "Uhr
      verbunden" abwarten, bevor es losgeht

## 1. Ankunft auf dem Platz — Grundcheck

- [ ] GPS-Fix abwarten (Statuszeile oben: "Fix · ..." statt "Kein Fix") —
      auf offenem Platz sollte das deutlich schneller gehen als sonst
- [ ] Kurz stehen bleiben, dann ein Stück laufen — Statuszeile wechselt
      plausibel zwischen "Fix · langsam"/"Fix · Fahrt"

---

## 2. Priorisierte Tests (GPS-abhängig, ohne Wind möglich)

Vor jedem gezielten Test kurz **"Ereignis markieren"** (Setup-Tab,
Freitext optional, z.B. "Start Distanztest") antippen — schreibt sofort
eine Anker-Log-Zeile, sonst lässt sich das CSV später nur schwer einem
tatsächlichen Testschritt zuordnen.

### 2a. Session-Distanz (`core/DistanceTracker.kt`)
- [ ] Normal-Tab: "Zurückgelegte Strecke" sichtbar, Startwert 0
- [ ] Eine bekannte Strecke ablaufen/joggen (z.B. eine Torlinie zur
      anderen, oder einmal um den Mittelkreis — Länge grob bekannt)
      — Ereignis markieren bei Start und Ende
- [ ] Wert zählt beim Laufen hoch, bleibt beim Stehenbleiben stabil (kein
      GPS-Jitter-Zuwachs im Stand)
- [ ] Angezeigte Strecke grob plausibel gegen die bekannte Platzlänge
      prüfen (Fussballfeld: i.d.R. 90–120 m Torlinie zu Torlinie)
- [ ] Setup-Tab → "Alles zurücksetzen" → Wert springt auf 0 zurück

### 2b. Heimweg-ETA/VMC träger (`core/HomeProgressTracker.kt`)
- [ ] Heimatpunkt auf einen Torpfosten/eine Eckfahne setzen, Heimweg-Modus
      aktivieren
- [ ] Erste ~20 s: ETA zeigt "--"/keine Zahl (Tracker braucht
      Mindesthistorie) — kein Fehler
- [ ] Vom anderen Ende des Platzes auf den Heimatpunkt zulaufen — ETA
      erscheint, VMC baut sich auf
- [ ] Bewusst kurz die Richtung wechseln (z.B. einen Bogen laufen) — ETA
      sollte sich **nicht** sprunghaft ändern (das ist der eigentliche
      Testpunkt: das 60s-Zeitfenster soll kurze Kursausschläge glätten)
- [ ] Heimatpunkt ändern (anderer Torpfosten) → Tracker setzt sich sichtbar
      zurück (kurz keine ETA, dann neu aufgebaut)

### 2c. Vereinheitlichte Bojenerkennung (`docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md`)
Auf dem Platz lässt sich das mit einer "virtuellen Boje" simulieren, z.B.
Mittelpunkt des Mittelkreises als Wegpunkt setzen:
- [ ] Training-Tab (Racemode) oder Competition-Modus mit Luvbake: Boje auf
      einen festen Punkt setzen (z.B. Mittelkreis-Mittelpunkt)
- [ ] Direkt an der Boje vorbeilaufen (< 20 m) → Rundung zählt sofort,
      **kein** Rückfrage-Banner
- [ ] Kurswechsel (Richtung ändern) 20–150 m neben der Boje simulieren
      (bewusst daneben laufen) → gelbes Banner "Boje noch nicht erreicht —
      trotzdem als gerundet werten?" erscheint, tab-unabhängig sichtbar
- [ ] "Ja, Boje ist hier" antippen → Boje wird auf aktuelle Position
      korrigiert, Rundung zählt
- [ ] Banner erneut auslösen, diesmal "Nein, anderer Grund" → Banner
      verschwindet, Boje bleibt an alter Position, keine Rundung
- [ ] Banner ein drittes Mal auslösen, 20 s nicht antippen → verschwindet
      automatisch, zählt als "Ja"
- [ ] Kurswechsel weiter als 150 m von der Boje entfernt → keine Reaktion
      (normal, kein Banner)

### 2d. Falls Uhr(en) dabei: BLE/LoRa + Gesten-Bestätigung
- [ ] BLE-Verbindung Handy ↔ Ultra bleibt beim Laufen stabil (kurze
      Unterbrechung ok — Fallback aufs Handy-Vibrieren sollte automatisch
      greifen)
- [ ] Bei 2c: sobald das Handy das Rückfrage-Banner zeigt, wechselt die
      Ultra automatisch auf den Manöver-Tab, zeigt "Boje hier?"
- [ ] Auf der Ultra: Handgelenk hochreissen (Tilt) bestätigt "Ja",
      schütteln (Shake) = "Nein" — Banner am Handy verschwindet sofort
      **Achtung:** Gesten-Schwellenwerte sind laut `PROJEKT_STATUS.md`
      noch unverifiziert (nur eine Schreibtisch-Messung) — falls die
      Geste nicht zuverlässig anspricht, ist das ein bekannter offener
      Punkt, kein neuer Bug
- [ ] Taster-Fallback testen: kurzer Druck = Ja, langer Druck = Nein
- [ ] Falls S3 (Land-Uhr) mit dabei und jemand am Spielfeldrand bleibt:
      "bisher X km" auf der Land-Uhr aktualisiert sich alle ~30 s passend
      zur laufenden Person (End-to-End über LoRa)

### 2e. S3-Alltagsfunktionen (falls S3 dabei, unabhängig vom Segelmodus)
- [ ] Wecker stellen, kurz abwarten/simulieren
- [ ] Stoppuhr starten/stoppen während eines Laufabschnitts
- [ ] Schrittzähler (BMA423) zählt beim Laufen plausibel hoch
- [ ] Taschenlampe (90 s-Overlay) an/aus
- [ ] Standby: Display geht nach 30 s aus, wacht per Touch wieder auf
      (Power-Taste bewusst NICHT testen als Weckquelle — bekannter Bug,
      siehe unten)

### 2f. Wind-Kalibrierung + Wendewinkel simulieren (zu Fuss)

Mechanik (`WindEngine.kt`): "ruhiger Kurs" = 8 aufeinanderfolgende
Sekunden mit ≤8° Kursabweichung bei ≥1.5 kn (~2.8 km/h, zügiges Gehen
reicht, Joggen ist sicherer für sauberen GPS-Kurs). Wende erkannt ab
≥50° Kursänderung. Winkel zwischen beiden Kursen muss 60°–110° sein,
sonst "unplausibel". 90 s Zeit pro Phase.

- [ ] Wind-Tab → "Kalibrierungsmodus" AN
- [ ] "Kalibrieren starten" antippen
- [ ] **Schlag 1**: zügig ~10–15 s geradeaus (z. B. entlang einer
      Seitenlinie) — App zeigt "Jetzt wenden!", sobald der Kurs als
      ruhig erkannt wurde
- [ ] **Wenden**: im Bogen drehen (nicht stehenbleiben — GPS-Kurs
      braucht Bewegung), am einfachsten auf die Torlinie wechseln
      (~90° zur Seitenlinie, liegt sauber im 60–110°-Fenster und ergibt
      einen realistischen Wendewinkel um die 45°)
- [ ] **Schlag 2**: wieder ~10–15 s geradeaus, ruhig halten
- [ ] App bestätigt "Wind kalibriert: X° — Wendewinkel: Y° (N
      Kalibrierläufe)" — mehrfach mit anderen Ecken/Winkeln wiederholen,
      verfeinert den laufenden Mittelwert genau wie beim echten Segeln
- [ ] Einmal bewusst einen zu spitzen/zu weiten Winkel laufen (< 60°
      oder > 110°) → "Wendewinkel unplausibel" sollte erscheinen, kein
      Crash, Zustand geht zurück auf IDLE

**Vorwind-Winkel (Smart-Modus)** — schwieriger, weil kein eigener
Kalibrierlauf, sondern trägen EMA (α = 0.03) im laufenden Smart-Modus:
- [ ] Wind-Tab → "Smart-Modus" AN (zusätzlich zum Kalibrierungsmodus)
- [ ] Nach einer Kalibrierung (oben) eine Runde möglichst genau
      entgegengesetzt zur gerade kalibrierten Windrichtung laufen (±20°
      um den aktuellen Schätzwert, Standard 180°/149° je nach Profil)
- [ ] Wind-Tab beobachten: "Vorwind-Winkel" bewegt sich nur sehr langsam
      — nach einem Durchgang meist noch nicht sichtbar, erst nach
      mehreren Minuten/Wiederholungen ein klein wenig erwartbar (kein
      Bug, wie bei der echten Testfahrt-Strategie beschrieben)

**Erwartbare Nebenwirkung, kein Bug:** gelegentliche "Wind-Shift:
Lift/Header"-Meldungen beim Laufen — normale Folge davon, dass ein
gelaufener Kurs nie so sauber ist wie ein echter Segelkurs, nicht
ernst nehmen für die Auswertung.

### 2g. See-Geofence (`logic/LakeGeofenceEngine.kt`)

Rein distanzbasiert (Abstand zum nächsten Kreis der Geofence-Kette),
läuft aber **nur während der Trainingsmodus aktiv ist** (Training-Tab
≠ "Aus"). Automatische See-Erkennung (Overpass/OSM) ergibt auf einem
Fussballplatz keinen Sinn — stattdessen die manuelle Zeichnen-Funktion
nutzen, die im Repo ohnehin für Seen ohne OSM-Daten gedacht ist.

- [ ] Training-Tab → "See-Grenze auf Karte einzeichnen" → kleine Grenze
      um einen Teil des Platzes antippen (z. B. nur einen Strafraum,
      ≥ 3 Punkte) → "Fertig", neue Kreis-Kette erscheint
- [ ] Training-Tab: einen Modus ≠ "Aus" starten (Tack-only reicht,
      Geofence-Check läuft nur bei aktivem Training)
- [ ] Vom Mittelpunkt der gezeichneten Fläche langsam Richtung Rand
      rauslaufen — bei ~80 % des jeweiligen Kreisradius: Vibration +
      rote Statuszeile "See-Rand-Warnung!"
- [ ] Am Rand stehen bleiben/weiter aussen bleiben (> 80 %) — Warnung
      wiederholt sich alle ~15 s, kein Dauer-Alarm
- [ ] Zurück Richtung Mitte laufen — Warnung verschwindet erst unter
      ~65 % (Hysterese), nicht schon direkt unter 80 %
- [ ] Training-Tab auf "Aus" stellen, ausserhalb der Grenze bleiben —
      keine weiteren Warnungen mehr (Geofence-Check pausiert komplett)

### 2h. Kommandiertes Manöver + Score (`logic/TrainingEngine.kt`)

**Nicht zu verwechseln mit dem geplanten IMU-Manöver-Tracking** (siehe
`docs/Erweiterung_Manoever_Performance_Tracking.md` — Status dort
"KONZEPT", keine Codezeile existiert). Das hier ist der bestehende,
bereits fertig gebaute Original-Spezifikations-Mechanismus, rein
GPS-/Speed-basiert, kein IMU/Gesten-Sensor nötig:

- [ ] Training-Tab → Modus "Wenden" oder "Halsen" (oder "Race") starten
- [ ] Ruhigen Kurs halten, bis App zufällig kommandiert: "Jetzt
      WENDEN!"/"Jetzt HALSEN!" (Vibration)
- [ ] Manöver ausführen: Kursänderung ≥ 15° löst "Drehung erkannt" aus,
      erneuter ruhiger Kurs (≥ 15° von der Ausgangsrichtung entfernt)
      beendet die Messung
- [ ] App zeigt "Manöver abgeschlossen — Score X" (0–100, `100 −
      Geschwindigkeitsverlust×2 − Überzeit×3`, Überzeit ab 5 s Dauer)
- [ ] Manöver-Log (Training-Tab) zeigt den neuen Eintrag mit Dauer,
      Geschwindigkeitsverlust % und Score
- [ ] Einmal bewusst zu spät/gar nicht reagieren (60 s Timeout) →
      "Manöver verpasst.", kein Log-Eintrag, neuer Kommando-Timer startet
- [ ] Mehrere Manöver hintereinander → Log füllt sich (Ringpuffer, zeigt
      die letzten 20)

**Erwartbare Nebenwirkung, kein Bug:** zu Fuss simulierte Geschwindigkeitsverluste
beim Abbiegen fallen meist deutlich grösser aus als beim echten Segeln
(Gehen/Joggen bremst in eine Kurve stärker ab als ein gleitendes Boot) —
niedrige Scores heute Abend sind also kein Hinweis auf einen Bug in der
Score-Formel.

### 2i. Start-Countdown (`logic/StartCountdownEngine.kt`)

Reiner Timer, **komplett ohne GPS** — überall testbar, auch drinnen falls
zeitlich knapp. Das ist die eigentliche "Uhr" der Segeluhr und läuft am
Samstag im Ernstfall genauso.

- [ ] Normal-Tab: Countdown starten (5:00)
- [ ] Bei 4:00 und bei 1:00: Vibrationssignal, Anzeige zählt weiter
      korrekt runter
- [ ] Bei 0:00: starkes Signal (Start), Anzeige wechselt automatisch auf
      Race-Timer ("+m:ss", zählt jetzt hoch statt runter)
- [ ] "Sync auf nächste Minute" während der COUNTDOWN-Phase antippen →
      Restzeit springt auf die nächste volle Minute runter (zum
      Angleichen an einen gehörten Startschuss)
- [ ] Reset/Abbrechen während des Countdowns → zurück in den Ausgangszustand,
      kein hängender Timer

### 2j. Competition-Modus End-to-End (`logic/CompetitionEngine.kt`)

**Der eigentliche Samstag-Testfall.** Aktiviert sich automatisch, sobald
der Countdown oben 0:00 erreicht (`onRaceStart`) — läuft unabhängig vom
Training-Tab. Nutzt den in 2f simulierten Wendewinkel/Vorwind-Winkel.

- [ ] Vor dem Start: Wind bereits kalibriert (siehe 2f) — Competition
      nutzt `closehauledAngleDeg`/`downwindAngleDeg` des aktiven
      Boots-Profils für die Kurs-Empfehlungen
- [ ] Optional: Luvbake (mark1) und Entlastungsboje (mark2) als
      Wegpunkte setzen (z. B. zwei Punkte auf dem Platz) — einmal MIT
      und einmal OHNE testen, um beide Modi (echte Bake vs. "geschätzt
      gegen den Wind") abzudecken
- [ ] Countdown durchlaufen lassen (2i) — bei 0:00 aktiviert sich
      Competition automatisch: Leg "Luvbake"/"Luvtonne (geschätzt)",
      Runde 0
- [ ] Auf die Luvbake bzw. den geschätzten Luvtonnen-Kurs zusteuern,
      Wende-Empfehlung beobachten
- [ ] Luvbake runden: < 20 m automatisch, 20–150 m löst dasselbe
      Rückfrage-Banner wie in 2c/2h aus → je nach Entlastungsboje
      entweder "weiter zur Entlastungsboje" oder direkt "Vorwind-Schlag"
- [ ] Falls Entlastungsboje gesetzt: dorthin laufen — < 20 m rundet hier
      IMMER automatisch (kein Rückfrage-Banner auf diesem Leg) → weiter
      zu Vorwind
- [ ] Vorwind-Leg: kein Wegpunkt, Kurs wird geschätzt (Wind ± gelernter
      Vorwind-Winkel aus 2f), aktuell gefahrene Gybe-Seite bleibt erhalten
- [ ] Kurswechsel zurück Richtung Amwind laufen → automatische Rundung
      (kein Banner, da kein Wegpunkt), Rundenzähler +1, zurück ins
      Luvbake-Leg
- [ ] Mindestens 2 volle Runden fahren, Rundenzähler in der UI prüfen
- [ ] Einmal bewusst gleichzeitig Training-Tab UND Competition aktiv
      lassen — beide sollten unabhängig nebeneinander laufen, ohne sich
      gegenseitig zu stören (siehe Klassendoku)

---

## 3. Bekannter offener Punkt, den man am Rand mittesten könnte

- **PMU-Power-Taste als Deep-Sleep-Aufwach-Quelle** ist auf der S3
  nachweislich kaputt (weckt sofort wieder auf), Fix bisher nur auf
  Touch-only-Wakeup umgestellt. Auf der **Ultra** ist das noch nicht
  getestet (`cbShutdown()`, gleicher `instance.sleep()`-Aufruf). Falls
  Zeit bleibt: Ultra über "Ausschalten" in Deep-Sleep schicken und prüfen,
  ob sie ebenfalls sofort wieder aufwacht — Ergebnis kurz notieren, kein
  Fix heute Abend nötig.

---

## 4. Nach dem Test

- [ ] Setup-Tab → "Log teilen" → CSV per Mail/Drive sichern
- [ ] Auffälligkeiten (Absturz, falsches Verhalten, ungefähre Uhrzeit)
      kurz notieren — die Ereignis-Marker im Log helfen später beim
      Zuordnen
- [ ] `PROJEKT_STATUS.md` aktualisieren: welche der oben "geplant
      11.08." markierten Punkte heute tatsächlich verifiziert wurden
- [ ] `git add . && git commit && git push` — auch bei "nur getestet,
      nichts geändert" lohnt sich ein kurzer Status-Commit
