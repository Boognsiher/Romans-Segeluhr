# Erweiterung: Vereinheitlichte Bojen-/Marken-Rundungserkennung

Nicht Teil der ursprünglichen Spezifikation. Roman-Wunsch 10.08.2026:

> "ok wir müssen das vereinheitlichen. Ich will in allen Modis die gleiche
> Logik. Wenn eine Boje gesetzt wird zählt diese. Wenn keine gesetzt wird
> dann läuft es ohne Boje -> sobald ein Kurswechsel Amwind/Vorwind
> stattfindet wird eine Boje 'gesetzt'. Wenn ein Boje gesetzt ist und
> diese Koordinaten nicht mit der Koordinate übereinstimmt auf welcher
> ich 'runde' dann muss diese Boje korrigiert werden. am besten mit einem
> Feedback dass die Boje noch nicht erreicht war und einem Bestätigen,
> dass die Boje jetzt anders ist oder einem Ablehnen wenn ich aus anderem
> Grund die Richtung ändere."

## Ausgangslage: drei unterschiedliche Logiken

Vor dieser Erweiterung hatte jeder Modus seine eigene Rundungs-Heuristik:

- **`TrainingEngine` (Racemode)**: Kurswechsel ≥25° + zielt danach auf die
  jeweils ANDERE Boje.
- **`CompetitionEngine` mit gesetzter Luvbake**: Distanz ODER ein
  beliebiger Kurswechsel — beides ohne Windbezug.
- **`CompetitionEngine` ohne Luvbake** ("geschätzt"): reiner
  Windwinkel-Übergang (Amwind/Vorwind, >90°/<90° zum Wind), aber ohne die
  Möglichkeit, das je zu korrigieren.

Drei verschiedene Verhaltensweisen für dasselbe Ereignis ("ich habe
gerade eine Boje gerundet") — schwer vorhersagbar, und keine der drei
Varianten bot einen Korrektur-Workflow für eine falsch/ungenau gesetzte
Boje.

## Neue, einheitliche Logik: `logic/MarkRoundingDetector.kt`

Eine Instanz pro Rundungs-Kontext (Training-Racemode: eine, Competition
UPWIND/DOWNWIND: eine gemeinsame, da nie beide Legs gleichzeitig aktiv
sind). `tick(fix, windDir, windCalibrated, mark)` liefert:

1. **Distanz zur gesetzten Marke — IMMER zuerst geprüft, unabhängig vom
   Wind.** Innerhalb `Constants.ROUNDING_RADIUS_M` (20 m) gilt sofort als
   gerundet (`Result.Rounded`) — "wenn eine Boje gesetzt wird, zählt
   diese".
2. **Amwind/Vorwind-Kurswechsel** (ruhiger Kurs über `CourseTracker`,
   quert die 90°-Linie zum wahren Wind):
   - **Keine Marke gesetzt** → die aktuelle Position wird automatisch zur
     virtuellen Marke, sofort gerundet (`Result.AutoRounded`).
   - **Marke gesetzt, Kurswechsel aber NICHT dort** (Distanz >20 m, aber
     ≤`Constants.ROUNDING_CONFIRM_RADIUS_M` = 150 m) → mehrdeutig, könnte
     die gemeinte Boje sein (falsch gesetzt/verschätzt) oder ein normales
     Manöver aus anderem Grund → `Result.NeedsConfirmation`, KEINE
     automatische Aktion.
   - **Weiter als 150 m entfernt** → ignoriert, normale Wende/Halse mitten
     auf dem Schlag, keine Rückmeldung (Roman-Entscheidung: 150 m als
     "Gate"-Radius für die Rückfrage).
3. Ohne kalibrierten Wind greift nur Punkt 1 (reine Distanzprüfung) — ohne
   Wind ist "Amwind/Vorwind" nicht definiert.

## Auswirkung pro Modus

- **`TrainingEngine.tickRaceNav()`**: braucht jetzt kalibrierten Wind
  (Roman-Entscheidung "komplett vereinheitlichen" bei der Rückfrage) —
  vorher kam die alte Kurswechsel-Heuristik ohne Wind aus. `otherBuoy()`
  entfernt (totes Code nach der Umstellung).
- **`CompetitionEngine`**: UPWIND (Luvbake) und DOWNWIND (Leetonne,
  "geschätzt", weiterhin ohne eigenen Wegpunkt) nutzen jetzt denselben
  `MarkRoundingDetector` — Leg-Wechsel resettet ihn.
- Beide Engines bekamen ein `pendingConfirmation`-Feld (`waypointKey` +
  `candidatePosition`) sowie `confirmPendingRounding()`/
  `rejectPendingRounding()`. Solange eine Rückfrage offen ist, pausiert
  die jeweilige Rundungserkennung (kein zweiter Trigger währenddessen).

## Bestätigen/Ablehnen-Workflow

- **Neue Modelle** (`data/model/Models.kt`): `BuoyConfirmSource`
  (TRAINING/COMPETITION), `PendingBuoyConfirmation` (Spiegel im UiState:
  Quelle, Wegpunkt-Schlüssel, Kandidat-Position, Startzeit).
- **`SegeluhrViewModel.updatePendingBuoyConfirmation()`** (1x/s, Teil der
  Tickschleife): spiegelt `TrainingEngine.pendingConfirmation`/
  `CompetitionEngine.pendingConfirmation` in `_uiState.pendingBuoyConfirmation`
  (Competition hat Vorrang für den seltenen Fall, dass beide gleichzeitig
  offen wären), löst den Spiegel auf, falls die Engine ihn selbst schon
  aufgegeben hat (z.B. Modus zwischenzeitlich gewechselt), und prüft den
  **Auto-Bestätigen-Timeout** (`Constants.ROUNDING_CONFIRM_TIMEOUT_MS` =
  20 s — Roman-Wunsch: Standardantwort "Ja", weil beim Segeln oft keine
  Hand für eine bewusste Bestätigung frei ist).
- **`confirmBuoyRounding()`**: schliesst die Rundung in der jeweiligen
  Engine SYNCHRON ab (räumt deren `pendingConfirmation` noch im selben
  Aufruf weg), bevor die korrigierte Position asynchron persistiert wird
  (`SettingsRepository.setWaypoint`). Bewusst in dieser Reihenfolge — sonst
  könnte ein Tick zwischen dem geleerten `_uiState`-Spiegel und dem
  (`suspend`) DataStore-Schreibvorgang das Banner kurz fälschlich neu
  aufleben lassen, siehe Code-Kommentar. Die Status-Meldungen beider
  Engines referenzieren ohnehin keine Koordinaten, die Reihenfolge der
  Persistenz ist dafür also unkritisch.
- **`rejectBuoyRounding()`**: Wegpunkt bleibt unverändert, nur der
  Rundungs-Tracker wird zurückgesetzt.
- **Drei Eingabewege, alle rufen dieselben Funktionen auf:**
  1. Banner-Buttons am Handy (`ui/components/BuoyRoundingConfirmBanner`,
     tab-unabhängig direkt unter dem Status-Banner in `MainActivity.kt`
     eingeblendet).
  2. `CMD_CONFIRM_BUOY_ROUNDING`/`CMD_REJECT_BUOY_ROUNDING` von der Uhr
     (`BleProtocol.kt`, `CHAR_CONTROL_UUID`) — siehe Firmware-Abschnitt.
  3. Der 20s-Auto-Timeout (Punkt oben).

## Wichtige Klarstellung: Geste statt Haptik als Antwortweg

Erste Fassung dieser Erweiterung ging von einer haptik-basierten
Bestätigung aus ("auch mit Haptik zu bestätigen"). Roman-Korrektur:
**"nein ich meine gestik nicht haptik"** — gemeint war eine
GESTEN-basierte Antwort auf der Uhr, weil beim Segeln oft keine Hand fürs
Display frei ist. Umgesetzt durch **Wiederverwendung** der bereits
bestehenden Klio/BHI260-Gestenerkennung der T-Watch-Ultra-Firmware
(Quick-Message-JA/NEIN-System: `onGestureTiltUp()`=JA/`onGestureShake()`=NEIN,
physischer Taster als dokumentiertes Fallback) statt eines komplett neuen
Eingabewegs. Haptik (`HAPTIC_ROUNDING_CONFIRM_NEEDED`) ist ausschliesslich
eine AUSGABE-Benachrichtigung ("Rückfrage steht an"), NICHT die Antwort
selbst.

## BLE-Protokoll-Ergänzung (`BleProtocol.kt`)

- `CMD_CONFIRM_BUOY_ROUNDING = 15`, `CMD_REJECT_BUOY_ROUNDING = 16` (neue
  1-Byte-Steuerbefehle Uhr → Handy, `CHAR_CONTROL_UUID`, wie die
  bestehenden `CMD_*`).
- `HAPTIC_ROUNDING_CONFIRM_NEEDED = 9` (neuer 1-Byte-Code,
  `CHAR_HAPTIC_UUID`).
- `RaceStatusPacket` (`CHAR_RACE_STATUS_UUID`, weiterhin 5 Byte) bekommt
  ein neues Bit im bestehenden `maneuverFlags`-Byte:
  `MANEUVER_FLAG_ROUNDING_CONFIRM_PENDING = 1 << 2`. Kein Grössenwechsel
  des Pakets nötig, nur ein zusätzliches Flag-Bit.
- `HapticFeedback.roundingConfirmNeeded()` neu in allen drei
  Implementierungen (`VibrationPatterns`, `BleHapticSender`,
  `SwitchableHaptics`).

## Firmware `Segeluhr_TWatch_Ultra.ino`

- Neue Konstanten: `CMD_CONFIRM_BUOY_ROUNDING`/`CMD_REJECT_BUOY_ROUNDING`,
  `MANEUVER_FLAG_ROUNDING_CONFIRM_PENDING`, `HAPTIC_ROUNDING_CONFIRM_NEEDED`.
- `RaceData` bekommt `roundingConfirmPending`, wird in `onRaceStatusNotify()`
  aus dem neuen Flag-Bit gelesen.
- `onButtonShortPress()`/`onButtonLongPress()`: neuer
  `raceData.roundingConfirmPending`-Zweig NACH dem bestehenden
  `haveIncomingQuestion`-Zweig (Quick-Messages haben Vorrang, falls
  unwahrscheinlicherweise beides gleichzeitig offen wäre) — sendet
  `answerRoundingConfirm(true/false)`, das per BLE den passenden `CMD_*`
  zurückschickt.
- `onGestureTiltUp()`/`onGestureShake()`: gleiche Priorität
  Quick-Message > Rundungs-Rückfrage.
- Die beiden GATE-Bedingungen, die vor der eigentlichen Gestenauswertung
  liegen (`onKlioRecognitionEvent()` und der ungetrainte
  Pitch-/Shake-Schwellenwert-Fallback in `gestureTick()`), wurden um
  `raceData.roundingConfirmPending` ergänzt — sonst hätte
  `onGestureTiltUp()`/`onGestureShake()` die richtige Antwort geschickt,
  wäre aber wegen des äusseren Gates nie aufgerufen worden.
- `maneuverScreenUpdate()`: zeigt bei offener Rückfrage "Boje hier?" +
  Bedienhinweis statt der normalen Wende/Halse-Anzeige (höhere Priorität).
- `autoFocusTick()`: `roundingConfirmPending` löst denselben
  Screen-Vorrang aus wie `maneuverNeeded` — die Rückfrage soll nicht
  unbemerkt hinter Wind/Menü verschwinden, während der 20s-Timeout läuft.

## Bewusste Vereinfachungen

- Kein eigener LVGL-Screen nur für die Rückfrage — die bestehende
  Manöver-Tab wird umfunktioniert (Text/Farbe ändern sich), kein neuer
  Tab nötig.
- Kein Bestätigungsdialog vor dem Auto-Timeout selbst — Roman-Entscheidung
  war explizit "Standardantwort Ja nach ~20s", kein weiterer Zwischenschritt.
- `T-Watch S3` (Land-Uhr) ist von dieser Erweiterung nicht betroffen — sie
  zeigt nur die Boot-Position, keine Renn-/Manöverlogik.

**Noch nicht kompiliert/getestet** (weder Android-App noch Firmware — kein
lokales Gradle/keine Arduino-IDE-Compile-Möglichkeit in dieser Session).
Vor Nutzung unbedingt in Android Studio bauen + auf der T-Watch Ultra
flashen und testen — insbesondere die neuen Gate-Bedingungen in
`gestureTick()`/`onKlioRecognitionEvent()` und den 150m/20s-Grenzfall auf
dem Wasser.
