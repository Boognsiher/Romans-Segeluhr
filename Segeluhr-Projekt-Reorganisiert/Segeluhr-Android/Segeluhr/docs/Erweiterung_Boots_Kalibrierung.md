# Erweiterung: Boots-Kalibrierung (Am-Wind-Wendewinkel)

Nicht Teil der ursprünglichen Spezifikation. Ersetzt den bisher fest
verdrahteten 45°-Am-Wind-Winkel (`HomeEngine`/`CompetitionEngine`, jeweils
eigene `CLOSEHAULED_ANGLE_DEG`-Konstante) durch einen pro Boot **lernbaren**
Wert — war schon in `Erweiterung_Heimweg.md` als bewusste Vereinfachung
markiert ("noch nicht auf dem Wasser validiert").

## Warum in `WindEngine` statt einer eigenen Klasse

Die bestehende Am-Wind-Windkalibrierung (zwei Schläge, `tickCalibration()`)
berechnet den Wendewinkel zwischen beiden Schlägen ohnehin schon (zur
Plausibilitätsprüfung, 60°–110°) — bisher wurde er danach einfach
verworfen, nur die Windrichtung (Mittelwert beider Schläge) blieb übrig.
Die Boots-Kalibrierung nutzt exakt dasselbe Manöver, deshalb sitzt sie in
derselben Klasse statt einer parallelen Kopie der Zwei-Schläge-Erkennung.

## Zwei Modi

- **Kalibrierungsmodus** (`WindEngine.calibrationModeEnabled`, Schalter im
  Wind-Tab): Ist er aktiv, verfeinert **jede erfolgreiche** Amwind-
  Windkalibrierung zusätzlich einen laufenden Mittelwert des Wendewinkels
  (`closehauledAngleDeg = halber gemessener Wendewinkel`,
  `closehauledSampleCount` als Lauf-Zähler). Je öfter man kalibriert (z.B.
  bei unterschiedlichem Wind/Trimm), desto stabiler der Wert. Ein einzelner
  Lauf reicht für einen ersten Wert, mehrere für mehr Vertrauen.
- **Smart-Modus** (`WindEngine.smartModeEnabled`, eigener Schalter): läuft
  nebenbei während des normalen Segelns mit (`tickContinuous()`, dieselbe
  Funktion, die auch Wind-Shifts erkennt). Sobald ein ruhiger Kurs plausibel
  am Wind liegt (Toleranzband `SMART_CLOSEHAULED_LEARN_BAND_DEG` = 20° um
  den aktuellen Schätzwert, damit ein Raumschots-Schlag den Wert nicht
  Richtung 90° zieht), wird der Wert per EMA (`SMART_CLOSEHAULED_EMA_ALPHA`
  = 0.03, bewusst klein/"träge") leise nachjustiert — kein expliziter
  Kalibrierlauf nötig, aber auch kein "harter", verlässlicher Messwert wie
  beim Kalibrierungsmodus. Deshalb zwei getrennte Mechanismen (laufender
  Mittelwert vs. EMA) statt einem gemeinsamen.

Beide Modi sind **bewusst nicht persistiert** (wie `homeModeActive`) — nach
einem Neustart sind sie wieder aus, der gelernte Winkel selbst bleibt aber
über `SettingsRepository.boatProfileFlow` erhalten.

## Wo der Wert verwendet wird

`HomeEngine.tick()` und `CompetitionEngine.tick()` bekommen
`closehauledAngleDeg` jetzt als Parameter (Default:
`Constants.DEFAULT_CLOSEHAULED_ANGLE_DEG` = 45°, falls nie kalibriert)
statt der bisherigen lokalen Konstante — `SegeluhrViewModel` übergibt dabei
`windEngine.closehauledAngleDeg`. Betrifft NUR die Entscheidung "direkt
anliegend segelbar oder kreuzen nötig" und den empfohlenen Am-Wind-Kurs,
NICHT die eigentliche Windrichtungs-Kalibrierung.

## Bewusste Vereinfachung

Symmetrische Annahme (Backbord-/Steuerbord-Wendewinkel identisch) — wie
beim bisherigen 45°-Fixwert auch, kein echtes Polardiagramm mit
unterschiedlichen Winkeln je Bug. Passt zum gewählten Umfang: nur der
Wendewinkel wird kalibriert, keine Geschwindigkeits-/Polar-Daten.

## Offener Punkt (10.08.2026): Mehrere Boots-Profile

Aktuell genau **ein** globaler Wendewinkel-Wert pro App-Installation
(`SettingsRepository.boatProfileFlow`, ein DataStore-Eintrag). Roman
möchte mehrere benannte Profile (z.B. für verschiedene Boote) anlegen und
zwischen ihnen wechseln können, plus ein vorbefülltes Grundprofil mit
geschätzten Startwerten statt des reinen 45°-Fallbacks. Geplanter Umbau
(noch nicht umgesetzt, wartet auf die genauen Startwerte vom Nutzer):
Liste von Profilen als JSON in DataStore (gleiches Muster wie
`LAKE_CIRCLES_JSON`) + Zeiger "aktives Profil", Profilverwaltung
vermutlich im Setup-Tab, Kalibrierungsmechanik (dieser Stand hier) bleibt
unverändert im Wind-Tab und wirkt auf das jeweils aktive Profil.

**Noch nicht kompiliert/getestet.**
