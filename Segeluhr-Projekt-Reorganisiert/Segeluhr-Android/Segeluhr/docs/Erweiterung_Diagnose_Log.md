# Erweiterung: Diagnose-Log (CSV-Mitschnitt für den ersten Segeltörn)

Nicht Teil der ursprünglichen Spezifikation. Anlass: erster Segeltörn mit
den am 10.08.2026 gebauten Features (Boots-Kalibrierung, Vorwind-Winkel,
Distanz-Tracking, Heimweg-Trägheit) — Roman wollte eine Möglichkeit, danach
"möglichst viele Infos" auswerten zu lassen, ohne dass am Boot ein Laptop
mit angeschlossenem seriellem Monitor an der Uhr-Firmware nötig ist. Das
Handy (App) ist das einzige Gerät, das während eines echten Törns sinnvoll
mitschreiben kann — die Firmware-Debug-Ausgaben (`Serial.printf`, TODO(Test-
Debug)-markiert) bleiben unverändert nur für USB-Tests am Schreibtisch
relevant.

## Was mitgeschrieben wird

`data/diagnostics/DiagnosticsLogger.kt` — bei jedem 1-Hz-Tick eine CSV-Zeile
mit praktisch dem kompletten `SegeluhrUiState`: GPS-Fix (lat/lon/SOG/COG/
Fix-Qualität), Windrichtung + Kalibrierstatus + Shift-Trend, gelernter
Wende-/Vorwind-Winkel (inkl. Kalibrierungs-/Smart-Modus-Status), Session-
Distanz, Heimweg-Guidance (Peilung/Distanz/empfohlener Kurs/Wende-Bedarf/
VMC/ETA), Competition-Guidance (Leg/Peilung/Distanz/Rundenzahl), Automatische-
See-Erkennung-Distanz, Uhr-Verbindungsstatus, Betriebsmodus, sowie der
aktuelle Status-Text/-Level (dieselbe Info, die auch im Status-Banner der
App steht).

Bewusst **kein** eigenes schlankeres Format — lieber zu viele Spalten als
im Nachhinein festzustellen, dass genau das fehlende Feld für die Auswertung
gebraucht worden wäre. Auswahl der Felder ist Claude-Ermessen (auf Wunsch
Roman: "möglichst viele Infos, welche du brauchst kannst du bestimmen").

## Speicherort und Format

- App-internes Verzeichnis (`context.filesDir/diagnostics/`), **kein**
  externer Speicher, deshalb **keine zusätzliche Berechtigung** nötig.
- Eine CSV-Datei pro App-Start (`diagnose_<Zeitstempel>.csv`), lazy beim
  ersten tatsächlichen Log-Eintrag angelegt.
- Komma-getrennt, eine Kopfzeile, deutsches Locale nur für die
  Zeitstempel-Formatierung (Zahlenwerte selbst nutzen Kotlins
  Standard-`toString()`, also Punkt als Dezimaltrennzeichen — wichtig für
  Tools, die beim Öffnen sonst falsch parsen).
- Nach jeder Zeile sofort `flush()` — kostet bei 1 Schreibvorgang/Sekunde
  nichts Messbares, verhindert aber Datenverlust bei einem App-Absturz
  mitten im Test.

## Bedienung (Setup-Tab, Sektion "Diagnose-Log")

- **Schalter "Aufzeichnung aktiv"** — Standard AN (`diagnosticsEnabled =
  true` in `SegeluhrUiState`), damit nichts vergessen werden kann. Lässt
  sich ausschalten (z.B. für private Alltagsfahrten ohne Segelbezug).
- **Zeilenzähler + Dateiname** zur Kontrolle, dass tatsächlich geschrieben
  wird.
- **"Ereignis markieren"** (Freitext-Feld + Button): schreibt SOFORT eine
  Zeile mit optionaler Notiz, unabhängig vom nächsten reguären Tick. Gedacht
  für gezielte Testmanöver ("jetzt bewusste Wende", "Smart-Modus gerade
  ausgeschaltet") — liefert Claude beim Auswerten Ankerpunkte im sonst
  reinen Zahlenstrom. Wird als normale Zeile mit gefülltem `event`-Feld
  geschrieben (kein zweites Dateiformat).
- **"Log teilen"**: Standard-Android-Share-Intent (`ACTION_SEND`,
  `text/csv`) über `FileProvider` — Datei geht z.B. per Mail/WhatsApp/Drive
  direkt vom Boot aufs eigene Konto, kein Kabel/Computer am Wasser nötig.
  Braucht `AndroidManifest.xml`-`<provider>`-Eintrag + `res/xml/file_paths.xml`
  (neu, 10.08.2026).

## Bewusste Vereinfachungen

- Kein Rotations-/Grössen-Limit — für einen einzelnen Abend-Törn (wenige
  Stunden, ~1 Zeile/Sekunde) bleibt die Datei im niedrigen MB-Bereich, nicht
  weiter optimiert.
- Kein Persistieren des Ein/Aus-Schalters über App-Neustarts hinweg (wie
  `calibrationModeEnabled`/`smartModeEnabled` — bewusst gleiches Muster wie
  dort: nach Neustart Standardverhalten, hier AN statt AUS).
- Keine Verschlüsselung/Zugriffsschutz auf die Log-Datei — enthält GPS-
  Tracks, das ist bei einem Trainings-/Testtörn ohne sensible Daten
  unkritisch, aber falls die App mal für andere Zwecke genutzt wird, wäre
  das ein Punkt zum Nachdenken.

**Noch nicht kompiliert/getestet** — Test heute Abend geplant.

## 02.09.2026: deutlich erweitert im Hinblick auf automatische Bojenerkennung

Roman-Wunsch im Vorfeld des nächsten Wassertests ("logge alles was du
denkst dass es hilft, ich habe genug Speicherplatz") — Ziel: nach dem Test
per Muster-Suche (wie schon 16.08.2026 beim Windshift-Filter, siehe
`PROJEKT_STATUS.md`) herausfinden, ob sich Bojen-Rundungen zuverlässiger
automatisch erkennen lassen als mit der aktuellen `MarkRoundingDetector`-
Heuristik. Neue Spalten (ans Ende der bestehenden Kopfzeile angehängt,
siehe unten):

- **Distanz + Peilung zu allen acht Wegpunkt-Typen** (`dist_*_m`/
  `brg_*_deg` für Pin/Boot-Ende/Ziel/Boje1/Boje2/Home/Marke1/Marke2) — bisher
  gab es nur `home_dist_m`/`comp_dist_m` (aktives Leg), nicht die Rohdistanz
  zu JEDEM gesetzten Punkt gleichzeitig.
- **`wind_side`** ("upwind"/"downwind"/leer) — exakt dieselbe Amwind/
  Vorwind-Formel wie `MarkRoundingDetector` (`abs(angleDiff(cog, windDir))
  < 90`), hier aber rein im Logger aus `cog_deg`/`wind_dir_deg` nachgerechnet,
  OHNE die Engine-internen (privaten) Detector-Instanzen anzufassen —
  bewusst risikoarm kurz vor einem echten Wassertest.
- **`cog_rate_dps`**: Kursänderungsrate in °/s zwischen zwei Ticks (null bei
  Lücken >5s, z.B. nach Uhr-Reconnect) — soll spätere Analyse-Skripte davon
  entlasten, das selbst aus `cog_deg` zu differenzieren.
- **`race_state`/`countdown_seconds`**: fehlte bisher komplett im Log,
  obwohl für die Wettfahrt-Auswertung offensichtlich relevant.
- **`comp_vmc_kn`**: Lücke geschlossen — `CompetitionGuidance.vmcKn` wurde
  schon lange berechnet, aber nie geloggt (nur das Heimweg-Pendant
  `home_vmc_kn` war schon da).
- **`gps_fresh`/`gps_moving`, `line_bias_deg`/`line_bias_favors`,
  `active_buoy_label`/`active_buoy_bearing_deg`/`active_buoy_dist_m`,
  `avg_tack_score`/`avg_jibe_score`**: ebenfalls schon vorhandene, aber
  bisher nie geloggte `SegeluhrUiState`-Felder.
- **`pending_confirm_source`/`pending_confirm_waypoint_key`/
  `pending_confirm_candidate_lat`/`_lon`/`pending_confirm_age_s`**: hält
  fest, wann/wo/warum die BESTEHENDE Rundungs-Rückfrage
  (`PendingBuoyConfirmation`) ausgelöst wurde — direkter Vergleichspunkt für
  jedes künftige Muster ("hätte ein neuer Algorithmus hier auch/anders
  reagiert?").

**Bewusst NICHT gemacht:** die `MarkRoundingDetector`-Instanzen aus
`TrainingEngine`/`CompetitionEngine` live mitzuloggen (z.B. deren
`Result`/`lastSide`/Steady-Kurs pro Tick) — beide Instanzen sind privat,
das hätte Änderungen an den Segel-Engines selbst bedeutet. Kurz vor einem
echten Wassertest bewusst vermieden; falls die Muster-Suche nach dem Test
merkt, dass genau dieses interne Signal fehlt, ist das ein guter Kandidat
für eine gezielte Nacharbeit DANACH, mit den echten Daten als Referenz.

**Spalten-Reihenfolge:** alle neuen Felder hängen strikt HINTER dem
bisherigen letzten Feld (`event`). Grund: `DiagnosticsLogImporter.kt` liest
die für den CSV-Reimport nötigen Felder über feste 0-basierte
Spalten-Indizes (`COL_WATCH_CONNECTED = 39` usw.) — jede Umsortierung
mittendrin hätte den bestehenden Reimport (u.a. der beiden echten Törns
vom 15./16.08.) unbemerkt kaputt gemacht.

**Nur geschrieben, nicht kompiliert/getestet** (kein Android-SDK/Plugin-
Cache in dieser Umgebung) — Verifikation (u.a. ob alle neuen Spalten bei
echten Werten plausibel aussehen) steht beim nächsten Wassertest an,
zusammen mit der physischen-Tasten-Bedienung der Galaxy-Watch-App (siehe
`Erweiterung_GalaxyWatch_App.md`).
