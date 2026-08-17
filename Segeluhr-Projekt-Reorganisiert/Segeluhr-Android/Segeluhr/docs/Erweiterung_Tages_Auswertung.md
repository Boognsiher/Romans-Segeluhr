# Erweiterung: Automatische Tages-/Wettfahrt-Auswertung

**Datum:** 17.08.2026
**Status:** implementiert, kompiliert (`compileDebugKotlin` + `assembleDebug`
BUILD SUCCESSFUL), App startet auf Hardware ohne Absturz (inkl. destruktiver
Room-DB-Migration auf Version 2) — funktionale Prüfung der einzelnen Teile
(Verlauf-Tab-Inhalte, PDF-Karte, Wettfahrt-Session-Grenzen) noch offen.

## Warum

Nach den ersten beiden echten Segeltörnen (15./16.08.2026) wurden die
Diagnose-Log-CSVs (siehe `Erweiterung_Diagnose_Log.md`) jeweils manuell in
einer separaten Claude-Code-Session ausgewertet (Wendewinkel-Nachrechnung,
Wind-Shift-Plausibilität, siehe PROJEKT_STATUS.md). Roman-Wunsch: dieselbe
Art Auswertung automatisch direkt in der App, ohne CSV-Export und
Nachbearbeitung — "wie wenn ich ein Log auswerten lasse", mit den
wichtigsten Kennzahlen (Wendewinkel während der Session, Max-Speed, etc.).

## Was

Neuer `SessionReport` (`data/model/SessionReport.kt`) mit:

- Dauer, Distanz, Max-/Ø-Speed
- Anzahl Wenden/Halsen + jeweiliger Ø-Winkel
- Anzahl erkannter Wind-Shifts (Header/Lift-Aufteilung)
- Anzahl Windkalibrierungen, aktueller gelernter Wende-/Vorwind-Winkel
- Anteil der Zeit mit verbundener Uhr (nur im "Mit Uhr"-Betrieb)

Zwei neue Bausteine erzeugen das:

- **`logic/SessionSummaryEngine.kt`** — läuft wie `DiagnosticsLogger` einmal
  pro ViewModel-Lebensdauer mit, sammelt bei jedem 1-Hz-Tick Speed/
  Uhr-Verbindung (bewusst unabhängig vom Diagnose-Log-Schalter, reine
  In-Memory-Aggregation ohne Datei-I/O).
- **`logic/WindEngine.kt`** — ergänzt um `sessionManeuvers`
  (`List<TackEvent>`) sowie Zähler für Wind-Shifts/Kalibrierungen. Die
  Wende-/Halsen-Erkennung nutzt bewusst denselben Bug-Wechsel-Moment
  (`tackSign`-Vorzeichenwechsel in `tickContinuous()`), den die Header/
  Lift-Erkennung ohnehin schon verfolgt — kein zweiter Erkennungsweg, keine
  Divergenzgefahr. Unterscheidung Wende/Halse rein über `|awa|` zum
  Zeitpunkt des Bug-Wechsels (`< 90°` = Wende, sonst Halse — neue Konstante
  `TACK_VS_GYBE_AWA_THRESHOLD_DEG`), gleiche Symmetrie-Annahme wie beim
  Boots-Kalibrierungsmodus.

`SegeluhrViewModel.stopApp()` baut daraus bei jedem Stopp einen frischen
`SessionReport` (`sessionSummaryEngine.buildReport(windEngine,
distanceTracker.totalM)`) und legt ihn in `SegeluhrUiState.sessionReport`
ab. Neuer `ui/components/SessionReportDialog.kt` zeigt ihn tab-unabhängig
als Dialog an (Muster wie `BuoyRoundingConfirmBanner`, aber als
`Dialog`-Overlay statt Banner — deutlich mehr Inhalt). "Teilen"-Button
verschickt eine Klartext-Zusammenfassung per `ACTION_SEND text/plain` (kein
FileProvider nötig, anders als beim CSV-Log). "Schliessen" blendet nur die
Anzeige aus (`dismissSessionReport()`), der Bericht selbst bleibt in der
Engine erhalten.

## Bewusste Entscheidungen

- **Trigger = App-Stopp**, nicht "Diagnose-Log beendet" — es gibt keinen
  expliziten "Log-Ende"-Zeitpunkt (eine CSV-Datei läuft die ganze
  ViewModel-Lebensdauer), aber App-Stopp ist der natürliche
  "ich bin fertig für heute"-Moment im bestehenden UI
  (`Erweiterung_App_Stopp_Rollenwahl.md`).
- **Kein Reset bei Stopp/Start-Zyklen**: wie `distanceTraveledM` und die
  Diagnose-Log-Datei deckt die `SessionSummaryEngine` den ganzen Tag ab,
  auch über eine Mittagspause (Stopp → Start) hinweg — nicht nur das letzte
  Segment. Bei jedem weiteren Stopp wächst der Bericht entsprechend weiter.
- **Wenden/Halsen-Erkennung wiederverwendet vorhandene, bereits verifizierte
  Logik** statt eines neuen Post-hoc-Algorithmus (wie es die manuelle
  Python-Auswertung am 16.08. war) — dieselbe `tickContinuous()`-Stelle, die
  laut PROJEKT_STATUS.md schon gegen beide echten Logs gegengerechnet wurde
  (140/145 bzw. 39/52 Events exakt getroffen).
- **Speed-Mittelwert über alle validen Ticks**, nicht nur "in Fahrt"
  gefiltert — bewusst einfach gehalten für Version 1, könnte sich als zu
  niedrig herausstellen, falls viel Stillstand/Wartezeit mitgezählt wird
  (siehe "Offene Punkte" unten).

## Nachtrag 17.08.2026 (spät): Persistenz, Wettfahrt-Sessions, PDF + Karte

Roman-Wunsch nach dem ersten Test des Dialogs: Ergebnis als PDF mit Karte
teilbar machen ("so ähnlich wie bei Strava"), UND Sessions dauerhaft
speichern + in einem eigenen Tab mit Route auf der Karte wieder anschauen
können.

### Datenmodell-Umbau

`SessionSummaryEngine` zeichnet jetzt statt reiner Lauf-Aggregate (max/sum)
einen vollständigen, leichtgewichtigen GPS-Track auf (Zeit/Position/Speed/
Uhr-Verbindung, downgesampelt auf max. `ROUTE_MAX_POINTS = 2000` Punkte
fürs Ergebnis) und kann daraus `buildReport(windEngine, kind, fromMs, toMs)`
für ein BELIEBIGES Zeitfenster bauen — nicht mehr nur "seit App-Start".
`WindEngine`s bisherige reine Zähler (Wind-Shifts, Kalibrierungen) sind
dafür auf zeitgestempelte Event-Listen umgestellt (`sessionWindShifts`/
`sessionCalibrations`, analog zu `sessionManeuvers`), damit sich auch sie
nach Zeitfenster filtern lassen. `SessionReport` hat zwei neue Felder:
`kind: SessionKind` (DAY/RACE) und `route: List<GeoPoint>`.

### Zwei Arten von Sessions (Roman-Vorgabe)

- **DAY**: wie bisher der ganze Tag seit App-Start, wächst über Stopp/
  Start-Zyklen hinweg. Wird bei jedem `stopApp()` als EIN DB-Eintrag
  angelegt/überschrieben (`SessionDao.upsertDay`, `dayEntityId` merkt sich
  die id).
- **RACE**: eine einzelne Wettfahrt, eigenes Zeitfenster ab
  `startCountdown()` (bewusst schon ab Countdown-Start, nicht erst ab
  Competition-Start bei 0:00 — die Vorstart-Phase gehört taktisch dazu) bis
  entweder `stopCompetition()` ("Wettfahrt beenden": Dialog erscheint
  sofort, eigener neuer DB-Eintrag) ODER bis zum NÄCHSTEN
  `startCountdown()`-Aufruf, falls die vorige Wettfahrt nie sauber beendet
  wurde (z.B. Fehlstart/Generalrückruf) — dann wird sie still im
  Hintergrund abgeschlossen+gespeichert, OHNE Dialog (würde den gerade
  beginnenden neuen Start stören, Roman-Vorgabe wörtlich umgesetzt).
  Zusätzliches Sicherheitsnetz in `stopApp()`: eine beim App-Stopp noch
  offene Wettfahrt wird ebenfalls still gesichert, statt verloren zu gehen.

### Persistenz

Neue Room-Tabelle `sessions` (`data/db/SessionDb.kt`), Route als JSON-Text-
Spalte (`org.json`, gleiches Muster wie `SettingsRepository.BOAT_PROFILES_JSON`
— kein neuer Dependency). DB-Version 1→2, `fallbackToDestructiveMigration()`
statt echter Migration (App noch in Hardware-Testphase, ein einmaliger
Verlust des alten Manöver-Logs beim ersten Start nach diesem Update bewusst
in Kauf genommen). **Auf Hardware verifiziert**: App startet nach dem
Schema-Wechsel ohne Absturz/Room-Exception.

### Neuer "Verlauf"-Tab

`ui/screens/SessionHistoryScreen.kt` (7. Tab, `Icons.Filled.History`) —
Liste aller gespeicherten Sessions (Icon unterscheidet DAY/RACE), Antippen
öffnet Detailansicht: Route live auf einer osmdroid-Karte
(`ui/components/RouteMapView.kt`, gleiches Muster wie `LakeDrawScreen`/
`WaypointMapPickScreen`, aber nur anzeigend), darunter dieselbe
Statistik-Spalte wie im Dialog (`SessionStatsColumn`, aus
`SessionReportDialog.kt` herausgezogen — EIN Layout, zwei Anzeigeorte),
"Als PDF teilen"- und "Löschen"-Button.

### PDF-Export mit echter Karte

`data/pdf/SessionPdfExporter.kt` — `android.graphics.pdf.PdfDocument`
(Android-Bordmittel, kein neuer Dependency), ein Seite: Titel/Datum,
Statistik-Zeilen (Canvas-Text), darunter ein echter OSM-Kartenausschnitt
mit Route+Start/End-Marker als Bitmap. **Bewusste Entscheidung gegen eine
einfache Routen-Skizze** (Roman: "echte OSM-Kartenkacheln, wie Strava") —
akzeptiertes Robustheitsrisiko:

- Die Karten-Bitmap kommt von einer NIE ans Fenster angehängten, manuell
  `measure()`/`layout()`-ten `MapView` (kein Compose, keine Activity-
  Einbettung) — funktioniert nach aktuellem Kenntnisstand, weil osmdroids
  Tile-Nachladen über einen fix an den Main-Looper gebundenen Handler
  läuft statt über View-Attachment, ist aber nirgends offiziell für diesen
  Anwendungsfall dokumentiert.
- Braucht Internet beim Export.
- Feste Wartezeit (`TILE_LOAD_WAIT_MS = 2500ms`) statt eines echten
  "Kacheln fertig geladen"-Signals — bei langsamem Netz im schlimmsten
  Fall Teil-/keine Kacheln auf der PDF-Karte (Route/Marker selbst sind
  sofort da, unabhängig vom Kachel-Laden).

PDF landet in `filesDir/reports/`, geteilt übers bestehende FileProvider
(neuer `files-path` in `file_paths.xml`, gleiches Muster wie
`diagnostics/`).

## Nachtrag 17.08.2026 (spät): Diagnose-Log-Import + Zeitstempel-Bugfix

Roman-Wunsch: alte, bereits geteilte Diagnose-Log-CSVs (siehe
`Erweiterung_Diagnose_Log.md`) rückwirkend als Session importieren können —
insbesondere die beiden bereits im Repo liegenden echten Törns vom 15./16.08.
(vor dieser Erweiterung entstanden, daher ohne Verlauf-Eintrag).

Neues `data/diagnostics/DiagnosticsLogImporter.kt`: spielt die CSV-Zeilen
der Reihe nach durch eine frische `WindEngine`/`SessionSummaryEngine`-
Instanz (`tickContinuous()`), statt Wenden/Halsen/Wind-Shifts neu zu
erfinden — exakt derselbe, bereits gegen beide echten Logs verifizierte
Erkennungsweg wie im Live-Betrieb. Neuer Button "Diagnose-Log importieren"
oben im Verlauf-Tab (`ActivityResultContracts.OpenDocument`), Ergebnis wird
als neuer DAY-Eintrag gespeichert. Bekannte Einschränkungen: keine Header/
Lift-Aufteilung der Wind-Shifts (kein Ziel-Wegpunkt aus der CSV
rekonstruierbar), keine Windkalibrierungs-Zählung (Importer spielt nur
`tickContinuous()`, nicht `tickCalibration()`, ab).

**Bugfix beim ersten Test gefunden**: importierte Sessions zeigten "Heute,
Dauer 0min" statt der echten Log-Zeit/-Dauer trotz plausibler Distanz/
Manöver-Zahlen. Ursache: `SessionSummaryEngine.onTick()` stempelte jeden
Track-Punkt mit `System.currentTimeMillis()` statt mit dem GPS-Fix-
Zeitstempel — für den Live-Betrieb praktisch folgenlos (Fix kommt ohnehin
"jetzt" an), beim Import aber fatal (alle CSV-Zeilen liefen in Millisekunden
durch, bekamen also alle denselben "jetzt"-Stempel statt ihrer echten,
Stunden auseinanderliegenden Log-Zeiten). Gleicher Bug an drei Stellen in
`WindEngine.kt` (Wenden/Halsen-, Wind-Shift- und Kalibrierungs-Events).
**Fix**: alle vier Stellen nutzen jetzt `fix.timestampMs` statt
`System.currentTimeMillis()`. **Auf Hardware verifiziert** (Re-Import
zeigt jetzt das korrekte Datum/die korrekte Dauer der Original-Session).

## Nachtrag 17.08.2026 (spät, 2): drei Bugs nach erstem Hardware-Test gefunden+gefixt

Nach dem ersten "Als PDF teilen"-Versuch auf dem Handy drei Probleme
gemeldet, alle gefixt und auf Hardware verifiziert:

1. **Karte fehlte im PDF, nur Platzhalter-Raster** — `CacheManager
   .downloadAreaAsync()` (osmdroids Weg fürs aktive Kachel-Vorladen) wirft
   für die genutzte MAPNIK-Quelle (freie OSM-Server) eine
   `TileSourcePolicyException("doesn't support bulk download")` — osmdroids
   eingebauter Schutz gegen automatisiertes Massen-Herunterladen. Erster
   Fix-Versuch entfernte `CacheManager` wieder zugunsten einer festen
   Wartezeit vor dem Zeichnen — **half nicht**, weiterhin nur Raster.
   Ursache gefunden: `mapView.draw(canvas)` wurde nur EIN EINZIGES Mal
   aufgerufen, und zwar erst NACH der Wartezeit — osmdroids `TilesOverlay`
   stösst das asynchrone Nachladen fehlender Kacheln aber erst BEIM
   ZEICHNEN an, die Wartezeit davor lief also komplett ins Leere (kein
   Ladeauftrag existierte, den man hätte abwarten können). **Fix**: `draw()`
   jetzt zweimal aufgerufen — einmal VOR der Wartezeit (stösst die
   Downloads an), einmal DANACH (fängt die inzwischen geladenen Kacheln
   ein). Zusätzlich `tileDownloadThreads` von osmdroids Standard (2) auf 8
   erhöht, `TILE_LOAD_WAIT_MS` auf 8s. **Auf Hardware verifiziert** (PDF-
   Dateigrösse verdoppelte sich, Roman bestätigt: "Kacheln sind vorhanden").
2. **App stürzte beim "Als PDF teilen"-Tap ab** — direkte Folge des ersten
   `CacheManager`-Versuchs: die `TileSourcePolicyException` fliegt auf
   einem rohen `AsyncTask`-Hintergrundthread, ausserhalb jeder Kotlin-
   Coroutine-Fehlerbehandlung, und crasht dadurch hart statt sauber
   abgefangen zu werden. Erledigte sich mit dem Entfernen von
   `CacheManager` (Fix 1) von selbst; zusätzlich `export()` jetzt komplett
   in einem try/catch — jeder künftige Fehler liefert ein PDF ohne Karte
   statt gar keins.
3. **Karte überlappte die Auswertung im Verlauf-Detail-Screen** — zwei
   Ursachen in Serie: (a) `Modifier.fillMaxSize()` auf dem Stats-Block
   bezog sich auf die GESAMTE Höhe des Eltern-Elements statt auf den nach
   der Karte übrigen Platz (`weight(1f)` behoben) — reichte aber ALLEIN
   NICHT (Bug reproduzierte sich weiterhin), weil (b) die native osmdroid-
   `MapView` (eingebunden via Compose `AndroidView`) ihr eigenes Zeichnen
   nicht auf die von Compose zugewiesene Box clippt und dadurch über ihre
   240dp-Höhe hinaus in den Auswertungstext hineinmalte. **Fix**:
   `RouteMapView` jetzt zusätzlich in `Box(modifier.clipToBounds())`
   gewrappt — erzwingt den Schnitt unabhängig vom genauen Compose-
   AndroidView-Interop-Mechanismus. **Auf Hardware verifiziert.**

**Nachtrag zum Nachtrag**: PDF-Export dauert durch die 8s-Kachel-Wartezeit
spürbar — Roman-Feedback "wirkt, als würde der Knopf nichts tun". Neuer
blockierender Ladedialog (`CircularProgressIndicator` + "PDF wird
erstellt…") in `MainActivity.kt`, erscheint bei beiden "Als PDF teilen"-
Stellen (Dialog + Verlauf-Detail) über einen gemeinsamen
`pdfExportInProgress`-State. **Kompiliert, App startet ohne Absturz** —
visuelle Bestätigung des Ladedialogs selbst noch nicht explizit
gegengetestet (nur der PDF-Export dahinter).

## Offene Punkte

- **PDF-Kartenkacheln nicht auf Hardware verifiziert** (grösstes
  Restrisiko dieser Erweiterung, siehe oben) — braucht einen echten Export-
  Versuch mit echter Route und normalem Mobilfunknetz.
- **Wettfahrt-Session-Grenzen nicht auf Hardware getestet** — braucht einen
  echten Countdown-Start→Wettfahrt-Ende-Durchlauf, insbesondere den
  "still speichern bei erneutem Countdown-Start"-Pfad (z.B. bewusst
  Fehlstart simulieren).
- Ø-Speed könnte durch Standzeiten (vor dem Ablegen, in Wartephasen)
  verfälscht sein — bei Bedarf auf `sogKn >= MIN_SPEED_KN` filtern, analog
  zur Kalibrierungslogik.
- Wende-/Halsen-Winkel werden wie beim Kalibrierungsmodus symmetrisch
  angenommen (kein Backstagsegel-Polardiagramm) — bekannte, bewusste
  Vereinfachung, siehe `Erweiterung_Boots_Kalibrierung.md`.
- Route-Downsampling (max. 2000 Punkte) rein auf gleichmässigem Abstand im
  Array, nicht auf tatsächlicher geometrischer Abweichung (Douglas-Peucker
  o.ä.) — für die Kartenanzeige bisher für ausreichend gehalten, bei
  sichtbar eckigen Routen ggf. nachbessern.
- Kein Löschen des Tages-Eintrags vom Verlauf-Tab aus getestet (Code-Pfad
  identisch zum Wettfahrt-Löschen, aber noch nicht gezielt anprobiert).
