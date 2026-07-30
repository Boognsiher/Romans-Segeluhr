# Erweiterung: Competition-Modus (echtes Rennen)

Nicht Teil der ursprünglichen `Segeluhr_Spezifikation.md` — spätere
Erweiterung. Es gibt jetzt zwei klar getrennte "Race"-artige Konzepte, die
absichtlich unterschiedlich heissen, um Verwechslungen zu vermeiden:

| | **Race** (Training-Tab) | **Competition** (Normal-Tab) |
|---|---|---|
| Zweck | Race-Manöver ÜBEN | Echtes Rennen NAVIGIEREN |
| Start | Manuell, Knopf im Training-Tab | Automatisch bei Countdown 0:00 |
| Bojen | Genau 2, Pflicht (Warnung falls nicht gesetzt) | 0, 1 oder 2 Luv-Bojen, alles optional |
| Wende/Halse-Kommandos | Ja, zufälliges Timing (wie Trainingsmodi) | Nein — nur laufende Empfehlung |
| Engine | `TrainingEngine` (`TrainMode.RACE`) | `CompetitionEngine` (eigenständig) |

Beide können unabhängig voneinander laufen (z.B. Training-Race manuell an,
während gleichzeitig Competition durch den Startschuss aktiv ist) — sie
teilen sich nur dieselben Haptik-/Status-Kanäle.

## Automatischer Start

`StartCountdownEngine` ruft bei Restzeit 0:00 einen `onRaceStart`-Callback
auf (siehe `SegeluhrViewModel`), der `CompetitionEngine.activate()` aufruft
und `competitionActive = true` setzt. Manuelles Beenden über den Knopf
"Wettfahrt beenden" im Normal-Tab (`stopCompetition()`).

## Kurs-Modell mit Entlastungsboje

Realistischer Wettfahrt-Aufbau mit Luv-Doppelboje:

```
Start -> Luvbake (mark1) -> [kurzer Halbwind-Schlag] -> Entlastungsboje (mark2)
       -> Vorwind -> (nächste Runde: zurück zur Luvbake) -> ...
```

Die Entlastungsboje ("Offset Mark") wird in echten Regatten oft direkt
neben der Luvbake platziert, damit sich die Boote beim Abfallen nicht
gegenseitig in die Quere kommen — dafür wird zwischen beiden ein kurzer
Halbwind-Schlag (Reach) gesegelt.

### Je nachdem, welche Bojen gesetzt sind

- **Beide Luv-Bojen gesetzt**: volle Führung — Peilung/Distanz zur
  Luvbake, dann zur Entlastungsboje, danach geschätzter Vorwind-Kurs.
- **Nur Luvbake gesetzt**: nach deren Rundung geht's direkt in den
  geschätzten Vorwind-Kurs, kein Halbwind-Schlag.
- **Keine Bojen gesetzt**: komplette Windschätzung — Luvtonne genau gegen
  den Wind, Rundung per Kurs-zu-Wind-Wechsel erkannt (identische Logik zur
  vorherigen "virtuellen Bojen-Schätzung", jetzt aber in `CompetitionEngine`
  statt in `TrainingEngine`). **Genau für den Fall gedacht, dass vor dem
  Start keine Zeit blieb, die Bahn abzufahren.**
- Die Vorwind-Etappe wird **immer** geschätzt (kein Leetonnen-Wegpunkt
  vorgesehen) — nach deren "Rundung" (Kurswechsel zurück auf Am-Wind)
  beginnt automatisch die nächste Runde (`lapCount` wird hochgezählt).

## Laufende Navigationshilfe statt Zufallskommandos

Anders als beim Trainings-Racemode gibt's hier **keine** zufällig getimten
"Jetzt WENDEN!"-Kommandos — das wäre in einem echten Rennen unpassend, da
taktische Entscheidungen (wann wende ich wirklich) beim Segler liegen.
Stattdessen liefert `CompetitionEngine` laufend (jede Sekunde):

- Peilung + Distanz zum aktuellen Etappenziel
- Empfohlenen Kurs (direkt, oder besserer Kreuz-Kurs, falls zu dicht am
  Wind — exakt dieselbe Logik wie im Heimweg-Modus, `HomeEngine`)
- Wende-Empfehlung, nur bei tatsächlichem Wechsel (nicht dauerhaft),
  inklusive Vibration

## Wind-Shift-Bewertung

Nutzt je nach aktueller Etappe automatisch die passende Referenz: Luvbake,
Entlastungsboje, oder — falls geschätzt — einen synthetischen Punkt
2 km in der geschätzten Richtung (`GeoUtils.projectPoint`, nur die Peilung
zählt für den Header/Lift-Vergleich, nicht die exakte Distanz).

## Bekannte Einschränkungen

- Es gibt keine explizite Ziellinie/Finish-Erkennung — "Wettfahrt beenden"
  muss manuell getippt werden.
- Nur EIN Luv-Bojenpaar unterstützt, keine Mehrfach-Bahnen mit
  unterschiedlichen Luv-/Lee-Konfigurationen pro Runde.
- Die Downwind-Schätzung geht von einem klassischen symmetrischen
  Luv-Lee-Kurs aus (siehe auch die entsprechende Einschränkung, die vorher
  schon für die virtuelle Bojen-Schätzung galt).
