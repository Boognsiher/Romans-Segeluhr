# Erweiterung: Nav-Tab-Redesign + Auto-Focus-Ersetzung (Ultra-Uhr)

> Nicht in der ursprünglichen Spezifikation. Roman-Wunschliste 11.08.2026
> (nach dem S3-Crash-Debugging desselben Tages).

## Status: 🔧 Code-komplett — noch NICHT auf Hardware geflasht/getestet.

**12.08.2026 (Roman-Wunschliste "vor dem nächsten Test"):** vier der fünf
ursprünglich offenen Punkte aus Abschnitt 5 umgesetzt (Fach-Tab-Aktionen,
Statusleiste, Hardware-Test steht weiterhin aus) + drei neue Ergänzungen
dazugekommen, siehe Abschnitt 6.

## 1. Ziel
- Nav-Tab zeigt die beim Segeln tatsächlich relevanten Werte statt eines
  reinen Kompass-Kreises: Bootsspeed, Speed-zum-Ziel (VMC), Lift/Header,
  aktiver Modus, Manöver-Vorschlag.
- Aktive Modi (Heimweg etc.) sperren die Tab-Auswahl nicht mehr — die alte
  Zwangsumschaltung (`Erweiterung_TWatch_S3_AutoFocus.md`) wird komplett
  ersetzt.

## 2. VMC-Datenlücke (grösster Umsetzungsaufwand)
"Speed zum Ziel" existierte bisher nur für den Heimweg
(`HomeEngine`/`HomeProgressTracker`). Für Training/Competition gab's das
Konzept nicht. Umgesetzt: `HomeProgressTracker` (generische, über ein
Zeitfenster gemessene Annäherungsgeschwindigkeit — Klasse war schon immer
generisch nutzbar) bekommt eine zweite, unabhängige Instanz in
`CompetitionEngine`, gefüttert mit der Distanz zur aktuellen Etappen-Marke
(Luvbake bzw. Entlastungsboje). Reset bei jedem Etappen-/Marken-Wechsel.
Auf DOWNWIND (kein Leetonnen-Wegpunkt vorgesehen) bleibt VMC `null`.

Training-Race (manueller Übungsmodus, Boje1/Boje2) hat noch KEINE eigene
VMC-Berechnung — bewusst nicht mitgemacht, um den Umfang nicht ausufern zu
lassen. Auf dem Nav-Tab zeigt "Ziel:" deshalb nur etwas an, wenn Heimweg
oder Competition aktiv ist.

## 3. BLE-Protokoll-Änderungen (App UND Uhr müssen zusammen geflasht werden)
- `HomeStatusPacket`: 7 → 9 Byte, neues Feld `int16 vmcCkn` (1/100 Knoten,
  signed, `0x7FFF` = keine VMC verfügbar).
- `RaceStatusPacket`: 5 → 7 Byte, gleiches neues Feld.
- `WindStatusPacket`: unverändert 5 Byte, neues Flag-Bit `WIND_FLAG_LIFT`
  im bestehenden `flags`-Byte.

Lift/Header-Konvention: Vorzeichen von Wind-Trend × aktuellem Tack-Vorzeichen
(gleiche Konvention wie `CompetitionEngine`/`HomeEngine`: COG rechts vom
Wind = +1). Negatives Produkt = Lift, positives = Header. **Reine
Modellannahme, noch nicht gegen echtes Segelverhalten verifiziert** — falls
sich das beim nächsten Test als falsch/vertauscht herausstellt, ist nur das
Vorzeichen in `SegeluhrViewModel.renderTelemetry()` zu drehen.

## 4. Auto-Focus-Ersetzung
Alte Logik (`autoFocusTick()`) erzwang bei jedem zeitkritischen Zustand
(Manöver/Countdown/Heimweg) einen Tab-Wechsel, auch wenn der Nutzer bewusst
woanders war. Ersetzt durch:
- **Grüne Tab-Markierung**: Tab-Button-Text wird grün eingefärbt, solange
  der zugehörige Zustand aktiv ist (Manöver/Rückfrage offen, Countdown
  läuft, Heimweg aktiv) — rein informativ, kein Wechsel.
- **5s-Kommando-Overlay**: bei einem neuen Kommando (Wende/Halse/Start/
  Bojenrundung, ausgelöst über `triggerHaptic()` — gleicher Trigger wie die
  Vibration) erscheint 5s lang ein Overlay-Text über dem gerade aktiven
  Screen, blendet sich danach von selbst wieder aus. Der Nutzer bleibt auf
  seinem Screen. Eigenes Widget (`lblCommandOverlay`), nicht das bestehende
  Quick-Message-Overlay — sonst könnten sich Ja/Nein-Fragen vom Boot und
  Manöver-Kommandos gegenseitig verdrängen.
- Rundungs-Rückfrage (`HAPTIC_ROUNDING_CONFIRM_NEEDED`) bekommt bewusst
  KEIN Overlay — steht schon fest auf `tabManeuver` ("Boje hier?") und ist
  jetzt zusätzlich grün markiert.

## 5. Offene Punkte
- [ ] Noch nicht auf Hardware getestet (Ultra hing beim Umsetzen nicht an
  USB).
- [ ] Lift/Header-Vorzeichenkonvention gegen echtes Segelverhalten prüfen.
- [ ] Training-Race hat keine eigene VMC.
- [x] Aktionen direkt auf den Fach-Tabs (Countdown Start/Reset/Sync, Wind-
  Kalibrierung) — 12.08.2026 umgesetzt, siehe Abschnitt 6.1.
- [x] Statusleiste (BT/Akku) nach unten verschoben — 12.08.2026 umgesetzt
  (y=10 → y=34, `tabNav`-`pad_top` entsprechend erhöht), siehe Abschnitt
  6.2. Wie beim seinerzeitigen Tab-Bar-Höhen-Fix (94px) ist ein zweiter
  Hardware-Abgleich nicht ausgeschlossen — 34px ist ein Schätzwert, keine
  vermessene Grösse.

## 6. Ergänzungen 12.08.2026 (Roman-Wunschliste "vor dem nächsten Test")

### 6.0 Bugfix: `CMD_*`-Befehle von der Uhr kamen nicht zuverlässig an
Roman-Feedback vom letzten Hardwaretest: "Home setzen" (Menü-Tab) tat
sichtbar nichts. Ursache gefunden: `sendControlCommand()` schrieb mit
`chControl->writeValue(buf, n, false)` — "Write ohne Antwort". Die
Handy-Seite (`BleGattServerManager.kt`) deklariert `CHAR_CONTROL_UUID` aber
nur mit `PROPERTY_WRITE`/`PERMISSION_WRITE`, **ohne**
`PROPERTY_WRITE_NO_RESPONSE`. NimBLEs `writeValue(..., response=false)`
wartet nie auf ein ATT-Acknowledgement und meldet schon dann Erfolg, wenn
das Paket nur lokal erfolgreich losgeschickt wurde (siehe
`NimBLERemoteValueAttribute::writeValue()`) — ob das Handy es wegen der
fehlenden Eigenschaft tatsächlich annimmt, ist stack-abhängig und war hier
offenbar nicht der Fall. **Fix**: `writeValue(buf, n, true)` — "Write MIT
Antwort", passend zur deklarierten Handy-Eigenschaft; blockiert kurz bis
zum Ack, für einzelne Tastendrücke ohne spürbaren Nachteil.

**Betrifft ALLE `CMD_*`-Befehle über `sendControlCommand()`** — nicht nur
die bestehenden Menü-Tab-Buttons (Wegpunkte, Trainingsmodus, Heimweg-
Toggle, ...), sondern auch die heute NEU gebauten Fach-Tab-Aktionen aus
Abschnitt 6.1 (CD Start/Reset/Sync/Stop, Wind-Kalibrierung) — die hätten
ohne diesen Fund denselben Bug gehabt. Zusätzlich bekamen
`cbSetWaypoint()`/`cbClearWaypoint()` jetzt ein kurzes Overlay + Vibration
nach dem Senden ("Wegpunkt gesendet"/"Wegpunkt geloescht") — vorher gab es
auf der Uhr GAR KEIN Feedback, ob ein Tastendruck überhaupt etwas ausgelöst
hat, wodurch ein stiller Fehlschlag (z.B. fehlender GPS-Fix aufs Handy,
siehe `SegeluhrViewModel.captureWaypoint()`) identisch aussah zu "Button
tut nichts". Das Overlay bestätigt nur den Sendevorgang, nicht ob das Handy
einen gültigen Fix hatte — das bleibt im Handy-Statusbanner.

- [ ] Noch nicht auf Hardware verifiziert, ob "Home setzen" jetzt
  tatsächlich ankommt (nur die Ursache gefunden + plausibel behoben, kein
  Handy/Uhr-Paar zum Nachtesten verfügbar).

### 6.1 Fach-Tab-Aktionen (CD + Wind)
Alle benötigten `CMD_*`-Befehle existierten bereits im BLE-Protokoll und
waren schon über den Menü-Tab erreichbar (`buildMenuTab()`) — jetzt
zusätzlich direkt auf dem jeweiligen Fach-Tab:
- **CD-Tab**: kleine Buttonreihe "Start"/"Reset"/"Min." (dieselben
  Callbacks wie im Menü, `cbCountdownStart`/`cbCountdownReset`/
  `cbCountdownSync`) + eigener Button "Wettfahrt stoppen" darunter — anders
  als "Wettfahrt beenden" im Menü (sofort, kein Rückfrage) öffnet dieser
  einen `lv_msgbox` mit Ja/Nein direkt auf der Uhr, erst bei "Ja" geht
  `CMD_COMPETITION_END` raus. Kein BLE-Roundtrip nötig (anders als die
  Bojen-Rundungs-Rückfrage) — das Handy muss vorher nichts wissen.
- **Wind-Tab**: "Kalibrieren starten"/"Kalibrierung abbrechen" (dieselben
  Callbacks wie im Menü). Kein Zustands-Feedback vom Handy vorgesehen
  (Protokoll kennt keinen Kalibrierungs-Status) — beide Buttons bleiben
  bewusst immer verfügbar. **Update 13.08.2026**: Sendevorgang bekommt jetzt
  immerhin ein kurzes Overlay+Vibration ("Kalibrierung gestartet"/
  "-abgebrochen", siehe `cbWindCalStart()`/`cbWindCalAbort()`).
  - [ ] **Offen (Roman-Wunsch 13.08., bewusst zurückgestellt)**: zusätzlich
    ein STEHENBLEIBENDES Zeichen auf dem Wind-Tab, solange die Kalibrierung
    tatsächlich läuft (nicht nur das 5s-Overlay beim Klick) — braucht ein
    neues Kalibrierungs-Status-Feld im BLE-Protokoll (`WindEngine.calibState`
    existiert app-seitig schon, wird aber aktuell nirgends an die Uhr
    übertragen, siehe `CHAR_WIND_UUID`/`WIND_FLAG_CALIBRATED`). Nicht jetzt
    umsetzen — erst wenn ohnehin wieder an dieser Firmware gearbeitet wird.
- Home-/Manöver-Tab bekommen laut Wunsch keine neuen Aktionen ("ausser
  Bojen usw.").
- `tabCountdown`/`tabWind` sind (anders als `tabNav`) nicht auf
  `SCROLLABLE=false` gestellt — passen Ring/Labels + neue Buttons nicht in
  den sichtbaren Bereich, scrollt der Tab einfach statt eines Sonderfalls.

### 6.2 Statusleiste nach unten
Siehe Abschnitt 5 oben — reiner Pixel-Fix, gleiche Ursache wie das
dokumentierte Tab-Bar-Clipping.

### 6.3 Competition: Manöver-Vorschläge entschärft
Nicht Ultra-Firmware, sondern `CompetitionEngine.kt` (Android) — betrifft
aber direkt das, was die Uhr per `HAPTIC_MANEUVER_CMD`/Nav-Tab zu sehen
bekommt. Bisher löste jeder false→true-Flankenwechsel von `maneuverNeeded`
sofort Vibration + 5s-Overlay aus, auch wenn der Kurs nur knapp am
Anluv-Limit oszillierte ("sehr schnell hintereinander Manöver gepusht").
Neu:
- Erste 10s nach Competition-Start (Countdown 0:00): kein Vorschlag, egal
  wie der Kurs steht — reines "geradeaus segeln lassen".
- Nach jedem tatsächlich ausgelösten Vorschlag: 15s Cooldown, bevor der
  nächste Übergang wieder durchgereicht wird (auch wenn `maneuverNeeded`
  zwischendurch flackert) — betrifft sowohl die Vibration als auch den
  exponierten `maneuverNeeded`-Wert (Nav-Tab "WENDE!", BLE-Flag).
- Neue Konstanten `Constants.COMPETITION_MANEUVER_START_GRACE_MS` (10s) /
  `COMPETITION_MANEUVER_SUGGEST_COOLDOWN_MS` (15s). Bewusst nur
  `CompetitionEngine` — Training-Race hat eigenes Zufalls-Timing, Heimweg
  war nicht Teil des Wunsches.

### 6.4 Heimweg: Auto-Stopp bei Ankunft + "BIN ZURÜCK!"-Meldung an die Land-Uhr
`SegeluhrViewModel.setHomeModeActive()` hatte schon einen vorbereiteten
TODO-Kommentar dafür. Erreicht das Boot den Heimatpunkt (`distanceM <=
Constants.ROUNDING_RADIUS_M`, dieselbe 20m-Bedeutung wie bei Bojen) während
der Heimweg-Modus aktiv ist, stoppt der Modus jetzt automatisch — und ein
neues Flag-Bit `HOME_FLAG_ARRIVED` (Bit 2 im bestehenden flags-Byte,
`HomeStatusPacket` bleibt 9 Byte) geht für genau EIN Notify mit auf die
Reise. Die Ultra erkennt das Bit in `onHomeStatusNotify()` und löst
automatisch eine `QuickQuestion::BIN_ZURUECK` ("BIN ZURUECK!") per LoRa an
die Land-Uhr aus — neuer Eintrag in `Segeluhr-Firmware/shared/
QuickMessages.h`, betrifft also potenziell beide Firmwares beim nächsten
Compile (S3 selbst braucht aber keine Code-Änderung, zeigt jede Frage schon
generisch an). Der Versand-Code aus `onButtonLongPress()` wurde dafür in
eine wiederverwendbare `sendQuickMessageRequest(QuickQuestion)` ausgelagert
— Roman kann "BIN ZURÜCK!" damit auch weiterhin manuell über die normale
Fragen-Auswahl verschicken.

**Nicht Teil dieser Erweiterung, aber am selben Tag/Wunsch umgesetzt:**
S3 zeigt jetzt auch die eigene Akku-Ladung auf dem Status-Tab
(`lblOwnBattery`, `instance.pmu.getBatteryPercent()` — gleiche API wie auf
der Ultra) — reiner S3-Fix, keine Ultra-Änderung, deshalb nicht weiter
ausgeführt hier.

### 6.5 Nav-Tab: Rot/Grün-Farbcodierung statt Amber/Grau
Roman-Nachtrag zu 6.3: das Nav-Tab soll nicht nur "WENDE!"/"kein Manöver"
text lich zeigen, sondern auf einen Blick Rot (schlechter Kurs, Manöver
empfohlen) / Grün (guter Kurs) — UND das auch dann, wenn gerade ein Push
per Cooldown unterdrückt wurde ("wenn ich das ablehne will ich trotzdem
einen Indikator haben"). Das erzwang eine Design-Korrektur an 6.3: die
Grace/Cooldown-Drosselung aus 6.3 wirkte ursprünglich auch auf den
*exponierten* `maneuverNeeded`-Wert (also auch aufs Nav-Tab) — jetzt
drosselt `CompetitionEngine.maybeVibrateManeuver()` NUR noch den Push
(Vibration + Statusmeldung), `CompetitionGuidance.maneuverNeeded` bleibt
roh/live. `lblNavManeuver` auf der Uhr zeigt entsprechend: Rot bei
Manöver-Bedarf, Grün bei aktiv bewertetem gutem Kurs, Grau ohne Renndaten
(nichts bewertbar).

### 6.6 Wegpunkt-Buttons: Feedback + individuelles Löschen
Zwei Roman-Nachträge in einem Aufwasch, weil beide dieselbe neue
Characteristic brauchen:
- **Feedback, ob ein Wegpunkt tatsächlich gesetzt wurde**: neue 1-Byte
  NOTIFY-Characteristic `CHAR_WAYPOINTS_STATUS_UUID` (Bitmaske, 1x/s vom
  Handy gesendet, `BleProtocol.WaypointSetFlag`) — die Uhr färbt die
  "X setzen"-Buttons im Menü-Tab grün, sobald das Handy eine hinterlegte
  Koordinate für diesen Wegpunkt bestätigt. Direkte Konsequenz aus 6.0: ein
  grüner Button beweist sowohl "Befehl kam an" als auch "Handy hatte einen
  gültigen GPS-Fix", ohne dass man extra nachschauen muss.
- **Bugfix "Boje löschen ging nicht"**: der bisherige einzelne "Alle Bojen
  loeschen"-Button war fest an `WP_BUOY1` gebunden — löschte trotz seines
  Namens IMMER nur Boje 1, nie Boje 2/Ziel/Home/Comp.-Marken. Ersetzt durch
  6 individuelle Setzen+Löschen-Zeilen (`addWaypointRow()`), eine pro
  Wegpunkt.
- Bewusst EIGENE Characteristic statt eines weiteren Felds in
  `HomeStatusPacket`/`RaceStatusPacket` — betrifft konzeptionell alle
  Wegpunkt-Typen, nicht nur Heimweg/Renn-Status.

### 6.8 Interner Testlauf 12.08.2026: Bugfix `stopCompetition()`
Auf Roman-Bitte einen internen Testlauf gemacht — erstmals volle
Compile-Verifikation für BEIDE Seiten möglich: Firmware wie gehabt über
`arduino-cli`, und für die App überraschend auch offline über Gradle (siehe
[[gradle-local-compile-setup]] — kein `gradlew` im Repo, aber eine gecachte
Gradle-8.7-Distribution + Android Studios JBR reichen). `compileDebugKotlin`
+ `assembleDebug` liefen beide sauber durch.

Beim Durchspielen des neuen "Wettfahrt stoppen"-Flows (Abschnitt 6.1)
gefunden: `stopCompetition()` (ViewModel) setzte bisher nur
`competitionActive=false`/`competitionGuidance=null`, rührte aber
`countdownEngine` nicht an. Dessen `raceState` blieb dadurch auf `RACE`
stehen. Konsequenz auf der Uhr: `updateBoatState()` fällt ohne aktives
`competitionLeg` (wird ja mit auf null gesetzt) auf den nächsten Fall
zurück — `raceState == 2` — und zeigt fälschlich `BoatState::TRAINING`
statt Idle; der CD-Tab zählt über `countdownEngine.tick()` als "+m:ss"
munter als laufende Wettfahrtzeit weiter, obwohl gestoppt. Betraf auch
den schon vorher bestehenden "Wettfahrt beenden"-Button im Menü-Tab, war
aber laut Testprotokollen nie verifiziert worden. **Fix**: `stopCompetition()`
ruft jetzt zusätzlich `countdownEngine.reset()` — CD-Tab zeigt danach
wieder "--:--", Boot-Zustand auf der Uhr korrekt IDLE.

Ausserdem kosmetisch nachgezogen: die neuen "X setzen"-Buttons (6.6) hatten
vor der ersten `WaypointsStatus`-Notify das LVGL-Standard-Theme statt des
definierten "nicht gesetzt"-Grau — jetzt schon beim Bauen explizit grau.

- [ ] `stopCompetition()`-Fix nur durch Code-Nachvollzug gefunden/behoben,
  nicht auf Hardware verifiziert (kein Handy/Uhr-Paar zum Nachtesten
  verfügbar).

### 6.9 Offene Punkte aus Abschnitt 6
- [ ] Alles oben: noch nicht auf Hardware getestet (gleicher Stand wie der
  Rest dieser Erweiterung) — insbesondere ob "Home setzen" nach dem
  Write-Bugfix (6.0) jetzt tatsächlich ankommt, kein Handy/Uhr-Paar zum
  Nachtesten verfügbar gewesen.
- [ ] Statusleiste-y-Offset (34px) ist ein Schätzwert, keine vermessene
  Grösse — ggf. nachjustieren.
- [ ] CD-/Wind-Tab-Buttons: Layout (Position unterhalb Ring/Labels) nur am
  Schreibtisch überschlagen, nicht gegen echte Pixel geprüft — ggf. scrollt
  der Tab mehr/weniger als erwartet.
- [ ] Wegpunkt-Set/Clear-Zeilen (6.6) im Menü-Tab: Layout (180px+56px
  nebeneinander) nur überschlagen, nicht gegen echte Pixel/Touch-Trefferzone
  geprüft.
- [ ] Rot/Grün-Farbcodierung (6.5) berücksichtigt nur `raceData.maneuverNeeded`
  (Competition/Training) — Heimweg-Manöver-Bedarf (`homeData.maneuverNeeded`)
  fliesst NICHT mit ein, war schon vor dieser Änderung nicht auf dem Nav-Tab
  sichtbar (separater, nicht behobener Punkt).
