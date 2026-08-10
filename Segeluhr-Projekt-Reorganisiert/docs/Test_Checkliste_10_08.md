# Test-Checkliste heute Abend (10.08.2026)

Alles, was heute implementiert, aber **noch nicht kompiliert/getestet** ist,
in der Reihenfolge, die am wenigsten Zeit verschwendet (Compile-Fehler
zuerst abfangen, bevor Hardware involviert ist; unabhängige Sachen vor
den abhängigen).

Bei jedem Fehler: Fehlermeldung/Screenshot einfach in den Claude-Code-Chat
kopieren, nicht selbst rumdebuggen.

**Diese Checkliste prüft nur "kompiliert/läuft ohne Absturz".** Für den
eigentlichen Wasser-Törn (Reihenfolge, worauf achten, wie am meisten
Erfahrung sammeln) siehe `docs/Testfahrt_Strategie_10_08.md`.

---

## 0. Vorbereitung

- [ ] `git pull` im Repo-Ordner (alle heutigen Änderungen holen)
- [ ] Android Studio: Projekt öffnen, Gradle-Sync abwarten
- [ ] Arduino IDE bereit, beide COM-Ports bekannt (Ultra + S3/Land — siehe
      letzte Session, falls neu: `docs/Heute_Abend_Ablauf.md` Abschnitt C0)

---

## 1. Android-App — kompilieren (fängt die meisten Fehler auf einmal ab)

Fünf unabhängige Änderungen von heute stecken alle in diesem einen Build:
Session-Distanz, Heimweg-Trägheit, Boots-Kalibrierung, Mehrere Boots-Profile,
`distanceTraveledM`-BLE-Erweiterung.

- [ ] **Build → Make Project** (oder Run) — grün?
  - Bei Fehler: Datei/Zeile aus der Fehlermeldung nennen, nicht raten lassen
- [ ] App auf dem Handy installiert, startet ohne Absturz
- [ ] Setup-Tab öffnen — lädt ohne Crash (dort leben die neuen
      Profil-Verwaltung + Reset-Button)

**Falls hier was rot ist: erst hier weiterkommen, bevor überhaupt eine Uhr
angefasst wird** — alles Weitere baut auf einer laufenden App auf.

---

## 2. Android-App — Funktionstest ohne Uhr (Handy allein reicht)

### 2a. Session-Distanz (`core/DistanceTracker.kt`)
- [ ] Normal-Tab öffnen, "Zurückgelegte Strecke" sichtbar (Startwert 0)
- [ ] Kurz mit GPS-Fix bewegen (draussen laufen reicht, > minimale
      Geschwindigkeit) — Wert zählt hoch
- [ ] Stehen bleiben — Wert bleibt stabil (kein GPS-Jitter-Zuwachs im Stand)
- [ ] Setup-Tab → "Alles zurücksetzen" → Wert springt auf 0 zurück

### 2b. Heimweg-ETA/VMC träger (`core/HomeProgressTracker.kt`)
- [ ] Heimatpunkt setzen, Heimweg-Modus aktivieren
- [ ] Erste ~20s: ETA zeigt "--"/keine Zahl (Tracker braucht Mindesthistorie)
- [ ] Danach: ETA erscheint, ändert sich nicht mehr sprunghaft bei
      kurzzeitigem Kurswechsel (harte Verifikation erst auf dem Wasser
      möglich — heute reicht "kein Crash, Wert wirkt plausibel")
- [ ] Heimatpunkt ändern → Tracker setzt sich sichtbar zurück (kurz keine
      ETA, dann neu aufgebaut)

### 2c. Boots-Kalibrierung (`docs/Erweiterung_Boots_Kalibrierung.md`)
- [ ] Wind-Tab: Kalibrierungsmodus einschalten, ein paar Am-Wind-Werte
      erfassen, Wendewinkel ändert sich sichtbar
- [ ] Smart-Modus umschalten — Verhalten wechselt (Details siehe Doku)

### 2c-2. Vorwind-Winkel (Halse-Erkennung, siehe Erweiterung_Boots_Kalibrierung.md)
- [ ] Wind-Tab zeigt "Vorwind-Winkel" unter dem Wendewinkel (Musto-Profil:
      Startwert 149°, neues Profil: 180°)
- [ ] Smart-Modus aktiv + eine Weile Vorwind/Halbwind gesegelt → Wert ändert
      sich langsam (nicht sprunghaft)
- [ ] Heimweg-Modus mit Zielpunkt fast direkt vor dem Wind aktivieren →
      Status zeigt "Halse Richtung Heimweg empfehlenswert!" statt "Wende"
- [ ] "Wendewinkel zurücksetzen"-Button setzt auch den Vorwind-Winkel zurück

### 2c-3. Diagnose-Log (`docs/Erweiterung_Diagnose_Log.md`)
- [ ] Setup-Tab zeigt Sektion "Diagnose-Log", Schalter standardmässig AN
- [ ] Nach ein paar Sekunden: Dateiname + Zeilenzähler erscheinen (> 0)
- [ ] "Ereignis markieren" mit Notiztext → Zeilenzähler springt sofort hoch
- [ ] "Log teilen" → Android-Share-Sheet öffnet sich (Mail/Drive/...
      auswählbar), keine Absturz/Berechtigungs-Fehler
- [ ] Geteilte CSV-Datei öffnen (z.B. in Excel/Texteditor) — Kopfzeile +
      Datenzeilen mit plausiblen Werten (keine leeren Spalten überall)

### 2c-4. See-Grenze auf Karte einzeichnen (`docs/Erweiterung_Seegrenze_Zeichnen.md`)
- [ ] Training-Tab → "See-Grenze auf Karte einzeichnen" → Vollbild-Karte
      öffnet sich, echte Kartenkacheln laden
- [ ] Antippen setzt Punkte (Marker + wachsendes Polygon sichtbar), Karte
      lässt sich dazwischen normal verschieben/zoomen
- [ ] "Letzten Punkt entfernen" funktioniert
- [ ] "Fertig" erst ab 3 Punkten aktiv; nach "Fertig" zurück im Training-Tab,
      neue Kreis-Kette sichtbar unter "See-Geofence"
- [ ] "Abbrechen" verwirft alle gesetzten Punkte, keine Kreise gespeichert

### 2c-5. Start-Rollenwahl + App-Stopp (`docs/Erweiterung_App_Stopp_Rollenwahl.md`)
- [ ] Compile-Check speziell auf `Icons.Filled.Sailing` (Material Icons
      Extended) — falls das in der aufgelösten Icon-Paket-Version nicht
      existiert, meldet der Compiler das sofort in `RolePickerScreen.kt`,
      einfach durch `Icons.Filled.DirectionsBoat` ersetzen lassen
- [ ] App frisch starten (nicht nur fortsetzen) → Wahlbildschirm "Auf dem
      Boot"/"An Land" erscheint VOR allen anderen Screens, zuletzt genutzte
      Rolle hat "zuletzt"-Badge
- [ ] Eine Rolle antippen → landet direkt im jeweils richtigen Screen
      (Tab-Leiste bzw. Land-Kartenansicht)
- [ ] App schliessen (nicht nur minimieren, "Zuletzt verwendet" wegwischen)
      und neu öffnen → Wahlbildschirm erscheint erneut
- [ ] Setup-Tab → Sektion "App-Betrieb" → "Stopp" → TopBar-Statuspunkt
      wechselt auf "Gestoppt" (grau), GPS-Werte frieren ein
- [ ] "Start" → GPS/Statuspunkt laufen wieder normal
- [ ] Rolle im Setup-Tab auf "An Land" wechseln (ohne vorher "Stopp" zu
      drücken) → GPS/Tick sollten automatisch pausieren; zurück auf "Auf
      dem Boot" → automatisch wieder aktiv

### 2d. Mehrere Boots-Profile
- [ ] Setup-Tab → Sektion "Boots-Profil" sichtbar
- [ ] Grundprofil heisst "Musto Skiff", Wendewinkel-Startwert 43°
- [ ] "+ Neues Profil" → Name eingeben → neues Profil startet bei 45°
- [ ] Zwischen Profilen wechseln (antippen) → aktiviert korrekt, Wind-Tab
      übernimmt den jeweiligen Wendewinkel
- [ ] Ein Profil löschen (×-Icon) — bei nur noch einem verbleibenden Profil
      ist ×-Icon ausgeblendet (Löschen des letzten muss verweigert werden)

---

## 3. Firmware Ultra — kompilieren + flashen

`Segeluhr-Firmware/ECHT/Segeluhr_TWatch_Ultra/Segeluhr_TWatch_Ultra.ino`

- [ ] Arduino IDE: Board = LilyGo T-Watch-Ultra, richtiger COM-Port
- [ ] Compile — grün?
- [ ] Flash (COM-Port Ultra)
- [ ] Bootet sauber, Alltags-Screen zeigt Uhrzeit
- [ ] BLE-Verbindung zum Handy klappt (Segeln-Modus wird automatisch
      aktiviert beim Verbinden)
- [ ] GPS/Wind/Heimweg-Daten kommen weiterhin an (keine Regression durch
      die `onHomeStatusNotify()`-Änderung — **wichtig:** diese Funktion
      verlangt jetzt mindestens 7 Byte statt 3, siehe Punkt 5 unten, falls
      Handy und Ultra aus Versehen unterschiedliche Stände haben)

---

## 4. Firmware S3 (Land) — kompilieren + flashen

`Segeluhr-Firmware/ECHT/Segeluhr_TWatch_S3/Segeluhr_TWatch_S3.ino`

- [ ] Arduino IDE: Board = LilyGo T-Watch-S3, richtiger COM-Port (Land-Uhr!)
- [ ] Compile — grün? (`LoRaPacket.h` hat sich geändert, 27→31 Byte —
      betrifft auch diese Firmware, static_assert sollte bei falscher Größe
      selbst einen Compile-Fehler werfen)
- [ ] Flash (COM-Port S3/Land)
- [ ] Bootet sauber, Hauptscreen zeigt "WARTE AUF START" o.ä.

**Wichtig:** Ultra (Punkt 3) und S3 (Punkt 4) teilen sich `LoRaPacket.h` —
wenn nur eine Seite neu geflasht ist, laufen sie auseinander (Land-Uhr
verwirft dann einfach stillschweigend jedes Paket, kein Crash, aber auch
keine Anzeige). Für einen sinnvollen Test **immer beide zusammen flashen**.

---

## 5. End-to-End: `distanceTraveledM` per LoRa an die Land-Uhr

Der eigentliche neue Punkt von heute Abend (siehe
`docs/BLE_Protokoll_Ergaenzung_Heimweg_LoRa.md`).

- [ ] Handy per BLE mit Ultra verbunden (siehe Punkt 3)
- [ ] Am Handy laufen/bewegen, bis "Zurückgelegte Strecke" > 0 zeigt
      (Normal-Tab)
- [ ] Ultra sendet spätestens alle 30s ein LoRa-Statuspaket (unabhängig vom
      Heimweg-Modus — läuft immer mit)
- [ ] S3 (Land) Detail-Tab: Paket-Info-Zeile zeigt jetzt
      **"Paket #N, Xs alt, bisher Y km"** — Y sollte in etwa der am Handy
      angezeigten Session-Distanz entsprechen (Rundungsdifferenz durch
      m→km-Anzeige ist normal)
- [ ] Setup-Tab am Handy → "Alles zurücksetzen" → nach dem nächsten
      30s-Sendezyklus sollte "bisher" auf der Land-Uhr auch auf 0.0 km
      zurückspringen

Falls "bisher" auf der Land-Uhr dauerhaft 0.0 bleibt, obwohl das Handy eine
Distanz > 0 anzeigt: wahrscheinlichste Ursache ist ein Versions-Mismatch
zwischen App und Ultra-Firmware (altes 3-Byte- statt neues 7-Byte-BLE-Paket)
— Punkt 1 und 3 nochmal gegen den aktuellen `git log` prüfen.

---

## 6. Danach: Stand sichern

- [ ] `PROJEKT_STATUS.md` Status-Spalten/Zuletzt-getestet-Daten aktualisieren
      (welche Punkte oben tatsächlich grün waren)
- [ ] Bei erfolgreichem Test: `git add . && git commit && git push` — auch
      Zwischenstände committen, nicht erst am Ende des Abends
