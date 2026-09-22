# Erweiterung: Robuste, kontinuierliche Windschätzung (Konzept)

> Diese Erweiterung steht NICHT in der ursprünglichen Spezifikation und wird
> hier gemäss Doku-Konvention separat dokumentiert.

## Status: KONZEPT (22.09.2026, Roman-Wunsch — Idee durchdacht + gegen echte
Diagnose-Logs geprüft, noch keine Code-Änderung an `WindEngine`)

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
"steady" waren (`CourseTracker.steady()`). Damit entsteht der erste
Windwert von selbst aus der ersten sauberen Wende/Halse der Session — kein
Kalibrier-Button mehr nötig, um überhaupt loszulegen. Der bestehende
Button bleibt als optionaler "sofort-und-sicher"-Shortcut erhalten.

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
- **Fallback, falls Speed-Dip sich nicht validieren lässt**: reine
  Kurswinkel-Heuristik (60–110° ≈ Wende, >110° ≈ Halse, ohne Speed-Dip) —
  gröber, aber unabhängig von diesem unsicheren Zusatzsignal.
- **Konkrete Fenstergrössen/Schwellen** für 3b/3c (Puffergrösse, Dauer
  kurzfristiges vs. langfristiges Fenster, Divergenz-Schwelle für einen
  "echten" Shift) — noch nicht festgelegt, braucht wahrscheinlich mehrere
  echte Sessions zum Kalibrieren.
- **Verhältnis zum bestehenden Kalibrier-Button**: bleibt als optionaler
  manueller Shortcut, oder komplett durch den Auto-Bootstrap ersetzt?
  Tendenz: behalten (kostet nichts, hilft z.B. wenn das erste automatisch
  erkannte Manöver in Welle/Böe unsauber war).
- **Zusammenspiel mit unabhängigem Bug-Signal** (3e/`Erweiterung_Mastuhr.md`):
  sobald Heel- oder Präsenz-Sensor existiert, sollte er in die
  Qualitätsgewichtung der Puffer-Samples einfliessen (stimmen AWA-Vorzeichen
  und unabhängiges Signal überein → hohe Konfidenz, sonst Sample verwerfen)
  — heute nicht umsetzbar (keine Hardware), Platzhalter für später.
- **Noch keine Zeile Code in `WindEngine` geändert** — reine
  Konzept-/Analyse-Phase.
