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
über `SettingsRepository.boatProfilesFlow` erhalten (siehe "Mehrere
Boots-Profile" unten).

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
unterschiedlichen Winkeln je Bug. Passt zum gewählten Umfang: nur die
Wende-/Vorwind-Winkel werden kalibriert, keine vollständigen
Geschwindigkeits-/Polar-Daten (siehe Abschnitt "Warum kein TWS-Polardiagramm"
unten für die konkrete Abwägung).

## Vorwind-Winkel / Halse-Erkennung (10.08.2026)

Ergänzt das bisher rein Am-Wind-fokussierte System um ein Pendant für die
Vorwind-Seite — Anstoss war eine [Referenzdatei zu Polar-basierter
Segelnavigation](https://github.com/rgleason/tactics_pi) (Analyse des
OpenCPN-Plugins `tactics_pi`, GPLv3 — hier nur als **konzeptionelle
Inspiration** genutzt, kein übernommener Code, siehe Lizenz-Hinweis in der
Referenzdatei selbst).

### Warum kein TWS-Polardiagramm (Windstärke-Bänder)

`tactics_pi` arbeitet mit einer vollen Polar-Tabelle (Windgeschwindigkeit ×
Windwinkel → Bootsgeschwindigkeit) und wählt darüber TWS-abhängige optimale
Winkel. Die Segeluhr hat **keinen TWS-Sensor** — Windrichtung wird nur aus
dem gesegelten Kurs abgeleitet (siehe Klassendoku oben), Windstärke gar
nicht gemessen. Ein Windstärke-Band-Modell (z.B. 3 Bänder wie in der
Musto-Skiff-Referenzdatei) liesse sich also nie zuverlässig der aktuell
richtigen Zeile zuordnen. Entscheidung Roman (10.08.2026): stattdessen den
bereits bestehenden Smart-Modus um eine zweite, unabhängige Grösse
erweitern — ein Wert, keine TWS-Tabelle.

### Mechanik

- **Kein eigener Kalibrierungsmodus** für den Vorwind-Winkel — anders als
  beim Wendewinkel gibt es keine dedizierte Zwei-Schläge-Messung mit
  Plausibilitätsprüfung. Nur der bestehende **Smart-Modus**-Schalter lernt
  ihn mit, per EMA, genau wie den Wendewinkel — derselbe `tickContinuous()`,
  zwei unabhängige Toleranzbänder (`SMART_CLOSEHAULED_LEARN_BAND_DEG` um
  den Wendewinkel, `SMART_DOWNWIND_LEARN_BAND_DEG` um den Vorwind-Winkel),
  überschneiden sich bei den üblichen Werten (~45° vs. ~150-180°) nicht.
- **`WindEngine.downwindAngleDeg`**, Default `Constants.DEFAULT_DOWNWIND_ANGLE_DEG`
  = 180° — das ist bewusst ein No-Op-Wert: 180° = exakt "vor dem Wind",
  identisch zum bisherigen festen `Wind + 180°`-Verhalten, bis der
  Smart-Modus einen abweichenden Wert gelernt hat.
- **`HomeEngine.tick()`**: kennt jetzt neben "zu dicht am Wind → Wende
  nötig" auch "zu tief vor dem Wind → Halse nötig" (`tooDeep`-Zweig,
  spiegelbildlich zum bestehenden Wende-Zweig, gleicher Code-Pfad wählt die
  näherliegende der beiden symmetrischen Kurs-Optionen). Status-Text
  unterscheidet "Wende" vs. "Halse".
- **`CompetitionEngine.tickDownwind()`**: hat KEINEN eigenen
  Leetonnen-Wegpunkt ("bewusst nicht vorgesehen", siehe Klassendoku dort) —
  ohne Ziel gibt es kein "besserer Bug"-Konzept wie bei Wende/Halse Richtung
  eines echten Punkts. Stattdessen wird die aktuell gefahrene Gybe-Seite
  beibehalten und als Zielkurs `Wind ± downwindAngleDeg` (statt starr
  `Wind + 180°`) genutzt — realistischerer Kurs, aber weiterhin keine
  Manöver-Empfehlung auf diesem Leg (`maneuverNeeded` bleibt `false`, wie
  bisher).
- **Grundprofil "Musto Skiff"**: `downwindAngleDeg = 149°`, gleiche
  Herleitung wie der 43°-Wendewinkel — Mittelwert der drei geschätzten
  Vorwind-TWA-Bänder aus `docs/musto_skiff_reference_data.json`
  (Leichtwind 130-145°, Haupt-Racebereich 140-155°, Starkwind 155-170° —
  Mittelpunkte 137.5/147.5/162.5 gemittelt ≈ 149°). Neue Profile starten
  weiterhin beim generischen 180°-Fallback.
- **UI**: Wind-Tab zeigt "Vorwind-Winkel" unter dem Wendewinkel (kein
  Kalibrierläufe-Zähler, da kein Kalibrierungsmodus dafür existiert),
  Setup-Tab-Profilliste zeigt beide Werte kompakt in einer Zeile.
  "Wendewinkel zurücksetzen"-Button setzt jetzt beide Winkel zurück.

## Mehrere Boots-Profile (10.08.2026)

Statt eines einzelnen globalen Werts gibt es jetzt benannte Profile (z.B.
für verschiedene Boote) — Motivation: eigenes Boot + Charter-/Vereinsboot
getrennt kalibrieren, oder ein neues Boot einmessen, ohne den bisherigen
Wert zu verlieren.

- **Speicherung**: `SettingsRepository.BoatProfiles(profiles, activeProfileId)`
  — Liste als JSON in DataStore (`BOAT_PROFILES_JSON`, gleiches Muster wie
  `LAKE_CIRCLES_JSON`), `ACTIVE_BOAT_PROFILE_ID` zeigt auf das aktive.
  Zeigt die gespeicherte ID auf kein (mehr) vorhandenes Profil (z.B.
  gelöscht), fällt der Flow automatisch auf das erste verbleibende zurück.
  Mindestens ein Profil bleibt immer bestehen (`deleteBoatProfile`
  verweigert das Löschen des letzten).
- **Grundprofil** (`SettingsRepository.DEFAULT_BOAT_PROFILE`, id
  `musto-skiff-default`): jede Installation startet mit einem Profil
  "Musto Skiff", Wendewinkel 43° statt des reinen 45°-Fallbacks — Mittelwert
  der drei geschätzten Am-Wind-TWA-Bänder aus der vom Nutzer bereitgestellten
  Referenzdatei (`docs/musto_skiff_reference_data.json`, im Repo abgelegt
  für Nachvollziehbarkeit) (Musto Skiff Class Association + Vergleichsklassen 49er/
  RS800: Leichtwind 42-48°, Mittelwind/optimales Pointing 38-42°, Starkwind
  40-50° — Mittelpunkte 45/40/45 gemittelt ≈ 43°). Ausdrücklich
  Community-Schätzwerte, keine echten Messdaten (`sampleCount = 0`, wie in
  der Quelle selbst empfohlen: "Startwerte für ein lernendes Modell, nicht
  verifizierte Messdaten"). Ein **neu angelegtes** Profil (Setup-Tab "+ Neues
  Profil") startet dagegen beim generischen `Constants.DEFAULT_CLOSEHAULED_ANGLE_DEG`
  (45°) — für beliebige künftige Boote gibt's keine Referenzdaten.
- **Profilverwaltung**: Setup-Tab, neue Sektion "Boots-Profil" — Liste
  aller Profile (antippen aktiviert), "+ Neues Profil" (Name eingeben),
  Löschen per ×-Icon (ausgeblendet, wenn nur noch eines übrig ist).
- **Kalibrierungsmechanik unverändert im Wind-Tab** (siehe oben), wirkt
  aber immer auf das gerade aktive Profil — `WindEngine.activeProfileId`
  wird vom ViewModel bei jedem Profilwechsel per `restoreBoatProfile()`
  umgeschaltet (nicht bei jeder Detail-Änderung desselben Profils, sonst
  würde jede Smart-Modus-Mikrokorrektur unnötig neu "laden").
- ~~Downwind-Polardaten aus der Referenzdatei NICHT verwendet~~ — **10.08.2026
  nachgeholt**, siehe Abschnitt "Vorwind-Winkel / Halse-Erkennung" oben.

**Noch nicht kompiliert/getestet.**
