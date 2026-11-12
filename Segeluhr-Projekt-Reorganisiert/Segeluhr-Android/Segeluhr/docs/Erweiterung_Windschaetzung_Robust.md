# Erweiterung: Robuste, kontinuierliche Windschätzung

> Diese Erweiterung steht NICHT in der ursprünglichen Spezifikation und wird
> hier gemäss Doku-Konvention separat dokumentiert.

## Status: IMPLEMENTIERT, NICHT KOMPILIERT (22.09.2026, Roman-Wunsch: vor dem
Segelwochenende einbauen) — Abschnitte 3b/3c sowie der Teil von 3a, der
KEINE Punkt-vor-Wind-Klassifikation ohne bekannten Wind braucht, sind jetzt
in `WindEngine.kt` umgesetzt. Bewusst NICHT umgesetzt: die in Abschnitt 3d
skizzierte Amwind/Downwind-Erkennung ganz ohne vorherige Kalibrierung — siehe
Abschnitt 4, die Speed-Dip-Hypothese dafür hat sich nicht validiert. Diese
Umgebung hat kein Android-SDK und keinen Netzwerkzugriff auf die Google/Maven-
Repos (siehe Abschnitt 6) — **vor dem Wochenende in Android Studio
kompilieren und mindestens kurz antesten.**

## 1. Motivation

Ausgangsfrage: Wie kann die Windschätzung stabiler werden, grundsätzlicher
immer laufen (statt eine manuelle Extra-Aktion vorauszusetzen) und sich aus
alten Daten zusammensetzen (statt nur eine einzelne Zahl fortlaufend zu
verschieben) — auch damit echte Windänderungen sauberer von Rauschen
unterschieden werden können. Zusätzliches Ziel (Roman): möglichst einfache
Bedienung — im Idealfall vor dem Ablegen einmal "Start" drücken, danach
erkennt das System selbständig, ob gerade Amwind oder Vorwind gesegelt wird.

## 2. Ist-Zustand (zur Einordnung)

`WindEngine` (siehe Klassendoku dort) leitet Wind nie direkt ab, sondern
ausschliesslich aus dem gesegelten Kurs — das bleibt auch im Zielbild so
(kein neuer Sensor vorausgesetzt). Zwei getrennte Mechanismen:

- **Start-Kalibrierung** (`tickCalibration()`, Zustandsmaschine
  `WAIT_TACK1`→`WAIT_TACK_CHANGE`→`WAIT_TACK2`): verlangt eine explizite,
  angekündigte Halte-Kurs→Wende→Halte-Kurs-Aktion im Wind-Tab. Erst danach
  existiert überhaupt ein `windDir` — vorher ist er `null`, und alles, was
  Wind braucht, tut nichts.
- **Laufende Nachführung** (`tickContinuous()`, läuft erst NACH
  erfolgreicher Kalibrierung): erkennt Bug-Wechsel über das Vorzeichen des
  AWA (`angleDiff(avg, windDir)` — hängt also selbst schon von `windDir` ab)
  und verschiebt bei einem erkannten Shift `windDir` direkt um den
  gemessenen Betrag (`windDir = normalize360(wd + shift)`). Eine einzelne
  Zahl, kein Gedächtnis an ältere Messungen — ein einzelner verrauschter
  Wert lässt sich nicht mehr "vergessen".

Separat davon lernt der bestehende Smart-Modus (siehe
`Erweiterung_Boots_Kalibrierung.md`) schon heute nebenbei die Boots-Winkel
(`closehauledAngleDeg`/`downwindAngleDeg`) ohne explizite Aktion — das ist
das Vorbild für "läuft von selbst mit", betrifft aber nur die Boots-Winkel,
nicht die Windrichtung selbst, und setzt ebenfalls ein bereits bekanntes
`windDir` voraus.

## 3. Zielbild

### 3a. Automatischer Bootstrap aus natürlichen Manövern

Jede Wende oder Halse, die beim normalen Segeln passiert, liefert
grundsätzlich dieselbe Information wie die heutige explizite
Zwei-Schläge-Kalibrierung (ruhiger Kurs → Manöver → ruhiger Kurs). Statt
eine isolierte, angekündigte Sonderaktion zu verlangen, soll jedes
erkannte Manöver (Bug-Wechsel wird von `tickContinuous()` ohnehin schon
verfolgt) automatisch zu einem Kalibrier-Sample werden, sofern beide Seiten
"steady" waren (`CourseTracker.steady()`).

**Umsetzungs-Trick (22.09.2026), der 3d dafür überflüssig macht:** für den
Bisektor zweier Kurse ist es egal, ob das dazwischenliegende Manöver eine
Wende oder eine Halse war — beide sind symmetrisch zur Windachse, der
Bisektor ergibt so oder so dieselbe Achse, nur mit einer 180°-Mehrdeutigkeit
(Wind-VON vs. Wind-NACH). Diese Mehrdeutigkeit lässt sich auflösen, indem
man die zum bereits bekannten `windDir` näherliegende der beiden möglichen
Richtungen nimmt — **das funktioniert aber nur, wenn schon ein `windDir`
existiert.** Für den ALLERERSTEN Wert der Session bleibt deshalb weiterhin
ein einziger expliziter Kalibrierlauf nötig (siehe Abschnitt 6) — danach
läuft alles automatisch, ganz ohne die ungeprüfte Speed-Dip-Klassifikation
aus 3d.

### 3b. Historien-Puffer statt Einzelwert

Statt `windDir` bei jedem erkannten Shift direkt zu überschreiben: einen
Ringpuffer der letzten N Manöver-Paare (Bisektor-Winkel, Zeitstempel,
Steadiness-Güte beider Seiten) führen. Der aktuelle Schätzwert ist ein
robuster Zentralwert darüber (zirkulärer Median/gewichtetes Mittel nach
Alter), nicht die letzte Einzelmessung — eine schlechte Wende (Welle,
kurzer Schlag) kippt dann nicht sofort den ganzen Zustand.

Wichtiger Fallstrick: ein Manöver-Paar taugt nur als Sample, wenn der
Zeitabstand zwischen beiden Seiten klein ist (wenige Minuten) — sonst
mittelt man über einen Zeitraum, in dem sich der Wind selbst schon gedreht
haben könnte, und bekommt einen verfälschten Bisektor.

### 3c. Zwei-Zeitskalen-Modell für echte Shift-Erkennung

Kurzfristiger Schätzwert (letzte paar Manöver-Paare, reagiert schnell) und
langfristige Baseline (ganze Session/gleitendes 30–60-Min-Fenster, träge)
parallel führen. Weichen beide **anhaltend** (nicht nur ein einzelner Tick)
über eine Schwelle auseinander, ist das ein echter Wind-Shift statt
Rauschen um den Tagesmittelwert — Erweiterung von `trendStats()`
(`_windLog`, aktuell nur `net`/`range` über ein Fenster) zu einer
richtigen Fast/Slow-EMA-Struktur, die `WindShiftEvent` sauberer speist als
die heutige reine Schwellenwertprüfung (`WIND_SHIFT_THRESHOLD_DEG`).

### 3d. Amwind/Downwind-Erkennung ohne Wind zu kennen (für den Bootstrap)

Kernproblem für 3a: Bug-/Manöver-Erkennung hängt heute an `awa`, das
wiederum an `windDir` hängt — Henne-Ei-Problem beim allerersten Manöver
der Session. Idee (siehe Abschnitt 4 für den Praxis-Check): Wende und
Halse rein aus `cogDeg`/`sogKn` unterscheiden, ganz ohne Windrichtung zu
kennen — Wende (Amwind) mit deutlichem Kurswechsel UND spürbarem
Geschwindigkeitseinbruch (Boot muss durch den Wind), Halse (Vorwind) mit
ähnlichem Kurswechsel, aber kaum Geschwindigkeitsverlust.

### 3e. Unabhängiges Bug-Signal (Querverweis)

`tackSign` wird heute zirkulär aus der eigenen Windschätzung abgeleitet
(Zeile 342/358 in `WindEngine.kt`) — genau in der Phase, in der es am
meisten zählt (kurz nach einem echten Shift, AWA nahe der Deadzone), am
unsichersten. Ein unabhängiges physikalisches Signal — Krängungsvorzeichen
oder Crew-Position — würde den Bug ohne diese Zirkularität bestimmen.
Details/Sensor-Optionen (Heel-Angle vom bootsfesten IMU der Mastuhr,
mmWave-Präsenzsensor für Crew-Seite) siehe `Erweiterung_Mastuhr.md`.

## 4. Empirische Prüfung der Speed-Dip-Hypothese (22.09.2026)

Bevor 3d als Baustein eingeplant wird: die Hypothese "Wende = starker
Speed-Einbruch, Halse = kaum Einbruch" gegen die beiden vorhandenen
Diagnose-Logs (15./16.08.2026) geprüft. Skript:
`docs/diagnose-logs/tack_gybe_speed_check.py`.

**Methode:** steady-Kurs-Segmente ähnlich `CourseTracker.steady()` erkennen
(gleitendes 8s-Fenster, `STEADY_COURSE_MAX_DEV` = 8°, `MIN_SPEED_KN` = 1.5,
identisch zu den `Constants`-Werten). Bei jedem Kurswechsel
≥ `TACK_CHANGE_MIN_DEG` (50°) zwischen zwei Segmenten den Punkt stärkster
Drehrate suchen und dort den Geschwindigkeitseinbruch relativ zur Baseline
(Mittel beider angrenzender Segmente) messen. Als Wende/Halse-**Label**
diente NICHT der Kurswinkel selbst, sondern dieselbe AWA-Klassifikation
wie `tickContinuous()` (`abs(awa) < TACK_VS_GYBE_AWA_THRESHOLD_DEG` = 90°),
aus dem im Log mitgeschriebenen `wind_dir_deg` zum Manöver-Zeitpunkt.

**Ergebnis:**

| Log | Wende (Ø Drop) | Halse (Ø Drop) |
|---|---|---|
| 15.08. (n=36 Manöver) | 29.3 % | **53.5 %** — Richtung umgekehrt zur Hypothese |
| 16.08. (n=76 Manöver) | 45.3 % | 47.7 % — praktisch kein Unterschied |

**Befund: Hypothese hält nicht sauber.** Statt einer klaren Trennung ist
das Bild uneinheitlich zwischen den beiden Sessions und am 15.08. sogar
gegenteilig zur physikalischen Erwartung.

**Wichtiger Vorbehalt zur Ground Truth:** Das Wende/Halse-Label stammt aus
`wind_dir_deg`, das die App zum Aufnahmezeitpunkt selbst berechnet hat —
und genau diese beiden Logs sind die, an denen am 16.08. der
Windshift-Plausibilitäts-Bug gefunden wurde (verpasste Wenden/Halsen
wurden vor dem erst am 17.08. eingeführten `WIND_SHIFT_MAX_PLAUSIBLE_DEG`-
Filter als Riesen-Shifts bis 122° fehlinterpretiert, siehe
`PROJEKT_STATUS.md` 16./17.08.). `wind_dir_deg` kann in beiden Logs also
zeitweise selbst verfälscht gewesen sein — das Label, an dem die Analyse
hier misst, ist damit mit Vorsicht zu geniessen, nicht nur das
Messergebnis selbst. Die Analyse kann also nicht sauber zwischen
"Hypothese falsch" und "Ground Truth kaputt" unterscheiden.

**Konsequenz:** 3d (Amwind/Downwind-Erkennung rein aus Speed-Dip) NICHT
ungeprüft in `WindEngine` einbauen — würde im Zweifel öfter falsch als
richtig liegen. Braucht sauberes Test-Ground-Truth (siehe offene Punkte).

## 5. Offene Punkte

- **Saubere Test-Labels einsammeln**: beim nächsten Törn (mit den
  17.08.-Fixes, also verlässlichem `wind_dir_deg`) gezielt einzelne
  Wenden/Halsen fahren und dabei über die bestehende
  "Ereignis markieren"-Funktion (siehe `Erweiterung_Diagnose_Log.md`)
  mit Text wie "jetzt Wende"/"jetzt Halse" markieren — erstmals echte,
  unabhängige Ground Truth statt einer selbst-referenziellen Ableitung.
  `tack_gybe_speed_check.py` lässt sich direkt gegen ein neues Log erneut
  laufen lassen.
- **3d bleibt zurückgestellt** (siehe Abschnitt 6) — die Speed-Dip-
  Hypothese wird nicht mehr gebraucht, um 3a umzusetzen (siehe Trick dort),
  bleibt aber als mögliche spätere Ergänzung im Hinterkopf (z.B. um auch
  den ALLERERSTEN Wert der Session ganz ohne Kalibrierlauf zu bootstrappen).
- **Konkrete Fenstergrössen/Schwellen** — mit Startwerten versehen (siehe
  Abschnitt 6, `Constants.WIND_HISTORY_*`), aber NICHT gegen echte Daten
  kalibriert (nur plausibel gewählt). Nach dem Wochenende gegen das neue
  `wind_sample_count`-Feld im Diagnose-Log prüfen: baut sich die
  Vertrauensanzeige im Wind-Tab plausibel auf, reagiert die Schätzung
  angemessen schnell auf echte Wenden?
- **Verhältnis zum bestehenden Kalibrier-Button**: bleibt als optionaler
  manueller Shortcut erhalten (siehe Abschnitt 6) — bewusste Entscheidung,
  nicht offen.
- **Zusammenspiel mit unabhängigem Bug-Signal** (3e/`Erweiterung_Mastuhr.md`):
  sobald Heel- oder Präsenz-Sensor existiert, sollte er in die
  Qualitätsgewichtung der Puffer-Samples einfliessen (stimmen AWA-Vorzeichen
  und unabhängiges Signal überein → hohe Konfidenz, sonst Sample verwerfen)
  — heute nicht umsetzbar (keine Hardware), Platzhalter für später.
- **Zwei-Zeitskalen-Modell (3c) nur teilweise umgesetzt**: der Ringpuffer
  selbst dämpft Ausreisser bereits deutlich (siehe Abschnitt 6), aber ein
  echtes separates Fast/Slow-Paar mit eigenem Divergenz-Alarm (statt nur
  einem gemeinsamen gewichteten Mittel) ist noch nicht gebaut — bewusst
  zurückgestellt, um vor dem Wochenende nicht zu viel ungetesteten,
  komplexen Code auf einmal einzubauen.
- **Kompilieren + Testen steht noch aus** — diese Umgebung hat kein
  Android-SDK und keinen Netzwerkzugriff auf die Google/Maven-Repos für
  Gradle, siehe Abschnitt 6.

## 6. Umsetzung (22.09.2026)

Umgesetzt in `WindEngine.kt` (+ `Constants.kt`, `GeoUtils.kt`,
`SegeluhrUiState.kt`, `SegeluhrViewModel.kt`, `WindScreen.kt`,
`DiagnosticsLogger.kt`):

- **`WindSample`/`windHistory`** (Ringpuffer, `ArrayDeque<WindSample>`,
  max. `WIND_HISTORY_MAX_SAMPLES` = 12 Einträge, `WIND_HISTORY_MAX_AGE_MS` =
  45 Min.) ersetzt die einzelne fortlaufend verschobene `windDir`-Zahl.
  Jedes Sample hat ein Basisgewicht je Herkunft
  (`WIND_SAMPLE_WEIGHT_EXPLICIT_CALIB` = 1.0,
  `WIND_SAMPLE_WEIGHT_MANEUVER` = 0.6 für automatisch erkannte Wenden/Halsen,
  `WIND_SAMPLE_WEIGHT_SHIFT_OBSERVATION` = 0.8 für den bisherigen
  Kurs-Shift-Pfad) und altert zusätzlich mit `WIND_HISTORY_DECAY_HALFLIFE_MS`
  (15 Min. Halbwertszeit) — `GeoUtils.circularMeanWeighted()` (neu) bildet
  daraus den aktuellen `windDir`. Alle drei bisherigen Zuweisungsstellen
  (`tickCalibration()`-Erfolgsfall, Bug-Wechsel- und Shift-Zweig in
  `tickContinuous()`) laufen jetzt über die neue zentrale `addWindSample()`.
- **Automatischer Bootstrap (3a)**: der bestehende Bug-Wechsel-Erkennung in
  `tickContinuous()` (die für `sessionManeuvers` ohnehin schon läuft)
  erzeugt jetzt zusätzlich ein Wind-Sample aus dem Bisektor beider Legs,
  sofern der Zeitabstand `MANEUVER_SAMPLE_MAX_GAP_MS` (3 Min.) nicht
  überschritten ist — 180°-Mehrdeutigkeit aufgelöst über die zu `windDir`
  näherliegende Richtung (siehe Abschnitt 3a). **Der explizite
  Kalibrierlauf bleibt Pflicht für den ALLERERSTEN Wert der Session**
  (`windDir` ist vorher `null`, es gibt nichts zum Auflösen der
  Mehrdeutigkeit) — danach ist keine weitere Handaktion mehr nötig.
- **Persistenz/Neustart**: `restore()` sät den Puffer beim App-Start mit
  einem Sample aus dem gespeicherten Wert (Zeitstempel "jetzt", da das
  echte Alter unbekannt ist) statt den Wert ungepuffert zu übernehmen.
- **UI**: Wind-Tab zeigt jetzt zusätzlich eine "Vertrauen"-Zeile
  (niedrig/mittel/hoch nach `windSampleCount`) sowie einen Hinweistext,
  dass nach der einmaligen Kalibrierung keine weitere Aktion mehr nötig ist.
- **Diagnose-Log**: neue Spalte `wind_sample_count` — Basis, um nach dem
  Wochenende zu prüfen, ob sich der Puffer plausibel aufbaut/verhält.
- **Watch-/Firmware-Änderungen: KEINE nötig.** `BleGattServerManager.
  notifyWindStatus()` (Signatur/Encoding unverändert) sendet weiterhin nur
  `windDirDeg`/`calibrated`/`trendDeg`/`isLift` — die robustere Berechnung
  passiert komplett phone-seitig, für Galaxy-Watch-App und beide
  T-Watch-Firmwares transparent. `windSampleCount` ist reine Phone-UI/
  Diagnose-Log-Anzeige, nicht Teil des BLE-Protokolls.
- **Nicht kompiliert**: diese Umgebung hat weder Android-SDK noch
  Netzwerkzugriff auf die von Gradle/AGP benötigten Google/Maven-Repos
  (`com.android.application`-Plugin liess sich nicht auflösen, siehe
  Testlauf). Stattdessen manuell Zeile für Zeile gegengelesen. **Vor dem
  Wochenende in Android Studio kompilieren.**

## 7. Ausweich-/Bojenmanöver während der Regatta ausfiltern (Nachtrag 22.09.2026)

Roman-Frage: Während der Regatta (nach dem Countdown) kann die Logik davon
ausgehen, dass fast immer anliegende Kurse (Amwind/Vorwind) gesegelt werden
— Ausnahmen sind Ausweichmanöver und Bojenrundungen, dazwischen aber auch
mal Halbwindkurse oder wartende Stellungen (z.B. Startlinien-Dial-up).
Werden solche Legs ausgefiltert, damit sie die Windschätzung nicht
verfälschen?

**Antwort: teilweise, jetzt vollständig nachgezogen.** Bereits vorher
gefiltert:
- `CourseTracker.sample()` verwirft den ganzen Puffer sofort unter
  `MIN_SPEED_KN` (1.5kn) — reines Treibenlassen/Stillstehen kommt gar nicht
  erst als "ruhiger Kurs" durch.
- `CourseTracker.steady()`s Positions-Check (`POSITION_CHECK_MIN_DIST_M`/
  `POSITION_CHECK_MAX_DEV_DEG`) verwirft eine "ruhige" Kursablesung, wenn
  die tatsächliche Bewegungsrichtung nicht zum gemeldeten COG passt — deckt
  reines Luven auf der Stelle ab.
- `WIND_SHIFT_MAX_PLAUSIBLE_DEG` (45°) verwirft einen einzelnen
  Riesensprung als vermutlich verpasste Wende/Halse.

**Nicht gefiltert war:** ein sauber gehaltener Halbwind-Kurs bei normaler
Fahrt (Ausweichen, Bojenanlauf, Dial-up) — das erfüllt alle obigen Checks
anstandslos und wurde bisher genauso als Wende/Halse-Bisektor bzw.
Kurs-Shift gewertet wie ein echtes Am-Wind-/Vorwind-Leg.

**Fix**: neue `WindEngine.isPlausibleRacingLeg(absAwaDeg, isRacing)` —
verwirft ein Leg für die Windschätzung, wenn sein AWA-Betrag weder nahe
`closehauledAngleDeg` noch nahe `downwindAngleDeg` liegt (Toleranzband
bewusst identisch zu den bestehenden Smart-Modus-Bändern
`SMART_CLOSEHAULED_LEARN_BAND_DEG`/`SMART_DOWNWIND_LEARN_BAND_DEG`, 20° —
dieselbe Frage, schon vorhandene Konstanten wiederverwendet statt neue
erfunden). Nur scharf, wenn `isRacing = true` — ausserhalb der Regatta
sind Raumschots-Kurse ganz normales Segeln und sollen weiterhin zur
Windschätzung beitragen, wie bisher.

- **`tickContinuous()`** bekommt einen neuen Parameter `isRacing: Boolean`
  (Default `false`, rückwärtskompatibel). Geprüft an BEIDEN neuen
  `addWindSample()`-Aufrufstellen: dem automatischen Bootstrap aus Wende/
  Halse (beide Legs müssen plausibel sein) und dem Kurs-Shift-Pfad
  (ebenfalls beide Legs) — dort wird bei Unplausibilität auch die
  Status-/Haptik-Meldung ("Wind-Shift: Header/Lift") unterdrückt, damit
  während eines Ausweichmanövers keine irreführende Wind-Meldung
  aufploppt. Der Referenzkurs (`lastSteadyCOG`) wird trotzdem übernommen,
  analog zum bestehenden `WIND_SHIFT_MAX_PLAUSIBLE_DEG`-Muster.
- **`SegeluhrViewModel.tick()`** übergibt
  `isRacing = competitionActive || trainingEngine.trainMode == TrainMode.RACE`
  — sowohl echte Competition als auch der Trainings-Racemode gelten als
  "es wird jetzt taktisch gesegelt".
- **`_sessionManeuvers`** (Tages-Auswertung, Wende-/Halsen-Zählung) bleibt
  bewusst UNGEFILTERT — ein Ausweichmanöver ist ja trotzdem ein reales
  Manöver, nur kein verlässlicher Windbeleg. Nur der Windschätzungs-Beitrag
  wird unterdrückt, nicht die Statistik.
- **Nebenbei gefunden + gefixt**: `DiagnosticsLogImporter.kt` löste Spalten
  bisher über feste Indizes auf — durch die neue `wind_sample_count`-Spalte
  (Abschnitt 6) wäre das für jede ab jetzt neu aufgezeichnete CSV falsch
  gewesen (alle Indizes ab `watch_connected` um 1 verschoben). Umgestellt
  auf Namens-Auflösung über die Kopfzeile (funktioniert für alte UND neue
  CSV-Spaltenzahl) und `isRacing` beim Reimport aus `competition_active`/
  `train_mode` rekonstruiert, damit ein Reimport denselben Filter anwendet
  wie der Live-Törn.
- **Ebenfalls nicht kompiliert** (siehe Abschnitt 6) — Teil derselben
  ausstehenden Kompilierung vor dem Wochenende.
