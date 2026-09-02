# Erweiterung: Competition-Kursmodell (Romans echter Verein-Kurs)

**02.09.2026, Roman-Korrektur:** die ursprüngliche `CompetitionEngine`
(siehe `Erweiterung_Competition_Modus.md`, jetzt SUPERSEDED) war an einem
angenommenen Kurs orientiert (Luvbake → Entlastungsboje/Halbwind →
Vorwind, immer geschätzt), nicht am tatsächlichen Kurs von Romans Verein.
Anlass war eine Regattabahn-Skizze zur Vorbereitung des nächsten
Wassertests — Roman korrigierte sie mit dem echten Kursmodell, das hier
umgesetzt wird.

## Der echte Kurs

```
Start → Luv zur Luvboje (IMMER gegen den Uhrzeigersinn gerundet;
         der kurze Halbwind-Schlag danach ist nur Teil der Rundungs-
         bewegung, KEINE eigene Etappe/Bake)
      → Vorwind zur Lee-Boje/zum Gate (siehe drei Varianten unten)
      → Runde 2 (IMMER genau 2 Runden — "es gibt immer 2 Runden")
      → Ziel (Geometrie hängt von der Lee-Variante ab, siehe unten)
```

**Einzige Ausnahme von "immer gegen den Uhrzeigersinn":** beim Lee-Gate
wird zwischen den zwei Gate-Bojen eine ausgewählt und von **innen nach
aussen** gerundet (andere Rundungsrichtung als Luvboje/einzelne Lee-Boje).
Die App validiert die Rundungsrichtung NICHT aktiv — das ist Kontext fürs
Segeln, keine Regel, die `MarkRoundingDetector` (rein distanz-/
kurswechselbasiert) prüft.

## Drei Lee-Varianten (`LeewardMode`, vor dem Start festgelegt)

Bestimmt sowohl die Rundungs-Marke am Ende jeder Runde als auch die
Ziel-Geometrie danach:

| Variante | Lee-Marke | Ziel danach |
|---|---|---|
| `LEE_IS_PIN` | dieselbe Boje wie der Startlinien-Pin | Halbwind, zwischen Boot und neuer Zielboje in Lee |
| `SEPARATE_BUOY` | eigenständige Lee-Boje | Amwind, durch die Startlinie (Pin↔Boot), wie eine zweite Linien-Querung |
| `GATE` | zwei Gate-Bojen, die NÄHER liegende wird pro Runde automatisch gewählt (Roman-Entscheidung: kein zusätzlicher Bedienschritt auf dem Wasser) | Amwind, wie `SEPARATE_BUOY` |

Setup-Tab, Sektion "Lee-Variante" — Segmented-Button analog zu
Rolle/Betriebsmodus, zeigt darunter nur die für die gewählte Variante
relevanten Wegpunkt-Zeilen.

## Neue Wegpunkte

BLE-`WaypointId` 10–13 (additiv, nach `COMPETITION_MARK2 = 9`):
`LEE_BUOY`, `GATE_A`, `GATE_B`, `FINISH_BUOY` (Zielboje, nur für
`LEE_IS_PIN`). Persistenz analog zu den bestehenden Wegpunkten
(`SettingsRepository`, DataStore-Doppelfeld lat/lon pro Punkt).

**`CHAR_WAYPOINTS_STATUS_UUID` ist jetzt 2 Byte statt 1** (`WaypointSetFlag`
Byte 1 unverändert, `WaypointSetFlag2` Byte 2 neu für die vier neuen
Punkte) — Android-App und Galaxy-Watch-App sind aktualisiert, **die
T-Watch-Ultra-Firmware noch nicht** (siehe "Plattform-Umfang" unten).

Ist der jeweilige Punkt nicht gesetzt, wird wie schon bisher geschätzt:

- Luvboje ohne `COMPETITION_MARK1` → genau gegen den Wind.
- Lee-Ziel ohne passenden Punkt (`LEE_IS_PIN` ohne Pin, `SEPARATE_BUOY`
  ohne `LEE_BUOY`, `GATE` ohne mindestens eine Gate-Boje) → rein per
  Amwind/Vorwind-Kurswechsel erkannt (gleiche Logik wie die alte, immer-
  geschätzte Vorwind-Etappe).
- Ziel ohne Pin/Boot/Zielboje → Peilung fällt auf reinen Windwert zurück
  (`isEstimated = true`, Anzeige "Ziel (Amwind, geschätzt)").

## `CompetitionLeg` — Ordinal geändert

`{ UPWIND, REACH_TO_OFFSET, DOWNWIND }` → `{ UPWIND, DOWNWIND, FINISH }`.
**Ordinal-Bedeutung ändert sich** (0=UPWIND, 1=DOWNWIND, 2=FINISH statt
vorher 1=REACH_TO_OFFSET, 2=DOWNWIND) — betrifft
`BleProtocol.encodeRaceStatus()`'s `competitionLeg`-Byte. Android-App und
Galaxy-Watch-App kennen nur die neue Bedeutung (Watch-Seite interpretiert
den Wert ohnehin nicht selbst, zeigt nur `CompetitionGuidance.label` an).
**T-Watch-Ultra-Firmware zeigt mit der neuen Bedeutung falsche
`legNames[]`-Texte** (`Segeluhr_TWatch_Ultra.ino`, kennt weiterhin die
alte 0/1/2-Zuordnung) — siehe "Plattform-Umfang" unten.

## Immer 2 Runden, dann FINISH

`Constants.COMPETITION_LAP_COUNT = 2`. `lapCount` wird beim Runden der
Lee-Marke hochgezählt; erreicht er das Limit, wechselt `leg` auf `FINISH`
statt zurück auf `UPWIND`.

## FINISH: kein automatisches Ziel-Erkennen (Roman-Entscheidung)

FINISH liefert nur laufende Peilung/Distanz/VMC zur Ziellinie (Amwind:
`closehauledGuidance()` wie auf UPWIND; Halbwind: direkter Kurs, kein
Anluven). **Keine Linien-Kreuzungs-Erkennung** — das hätte neue,
ungetestete Geometrie gebraucht (Punkt-Distanz-Rundung reicht dafür nicht).
Der Segler beendet die Wettfahrt weiterhin manuell über den bestehenden
"Wettfahrt beenden"-Button (`stopCompetition()`), sobald er tatsächlich
durch die Ziellinie ist. FINISH bleibt aktiv, bis das passiert — kein
automatischer Leg-Wechsel danach.

Ziel-Punkt = einfacher arithmetischer Mittelpunkt der beiden Linenenden
(Boot↔Zielboje bzw. Pin↔Boot) — für die kurzen Distanzen einer Start-/
Ziellinie ausreichend genau, keine echte geodätische Berechnung nötig.

## `CompetitionCourseConfig`

Neue Datenklasse (`data/model/Models.kt`) bündelt `leewardMode` + alle
Lee-/Ziel-Wegpunkte (`pin`, `boat`, `leeBuoy`, `gateA`, `gateB`,
`finishBuoy`) — `CompetitionEngine.tick()`/`windShiftReferencePoint()`
brauchen beide dieselbe Konfiguration, ohne dass die Parameterliste auf
über zehn Einzelwerte wächst. `SegeluhrViewModel.currentCourseConfig()`
baut sie 1x/Tick aus `currentWaypoints`/`currentLeewardMode`.

## Plattform-Umfang (Roman-Entscheidung 02.09.2026)

Nur **Android-App + Galaxy-Watch-App** jetzt aktualisiert — die eigentliche
Kurslogik lebt komplett am Handy (Uhren zeigen nur an), für den geplanten
Wassertest reicht das. **T-Watch-Ultra-Firmware bewusst NICHT angefasst**
(nicht im Einsatz bei diesem Test) — offene Folgearbeit vor ihrem nächsten
Competition-Einsatz:

- `CHAR_WAYPOINTS_STATUS_UUID`-Decode auf 2 Byte erweitern (aktuell liest
  die Firmware vermutlich nur 1 Byte — genauer Fundort noch nicht
  geprüft, da ausserhalb des jetzigen Umfangs).
- `legNames[]` in `Segeluhr_TWatch_Ultra.ino` (aktuell
  `{"Luv-Kurs", "Halbwind zur Boje", "Vorwind-Kurs"}`) an die neue
  Ordinal-Bedeutung anpassen (0=Luv, 1=Vorwind/Lee, 2=Ziel) und einen
  vierten Eintrag für FINISH ergänzen.
- Optional: eigene Menü-Buttons für Lee-Boje/Gate A+B/Zielboje (analog zu
  Marke1/Marke2 heute), sonst bleiben diese Punkte von der Ultra aus nicht
  setzbar (nur am Handy oder der Galaxy Watch).

## Nur geschrieben, nicht kompiliert/getestet

Kein Android-SDK/Plugin-Cache in dieser Umgebung — Verifikation (inkl. ob
die Gate-Auswahl auf dem Wasser sinnvoll flippt, ob die FINISH-Peilung bei
beiden Ziel-Varianten stimmt, ob die 2-Byte-`WaypointsStatus` auf der
Galaxy Watch korrekt ankommt) steht beim nächsten Wassertest aus.
