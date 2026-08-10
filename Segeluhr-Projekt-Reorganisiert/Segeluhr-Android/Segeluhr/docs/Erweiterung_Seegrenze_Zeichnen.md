# Erweiterung: See-Grenze auf Karte einzeichnen

Nicht Teil der ursprünglichen Spezifikation. Dritter Weg, um die
See-Geofence-Kreiskette zu befüllen — neben `LakeAutoDetector` (OSM/Overpass,
siehe `docs/Erweiterung_Automatische_See_Erkennung.md`) und dem bisherigen
GPS-Rand-Abfahren ("Rand erfassen", Training-Tab).

## Warum

Anlass war der Zürichsee: der hat mit Ufenau/Lützelau zwei Inseln im
Obersee, und Gewässer mit Inseln werden in OSM praktisch immer als
Multipolygon-**Relation** erfasst, nicht als einzelner **Way** —
`LakeAutoDetector` wertet aber bewusst nur einfache Ways aus (siehe dortige
Doku, "Zusammensetzen von Relationen ist nicht trivial"). Für solche Seen
bleibt die automatische Erkennung wirkungslos.

Statt das Relation-Parsing doch noch zu bauen (der explizit vermiedene,
aufwendige Teil), Roman-Vorschlag 10.08.2026: **er zeichnet die Grenze
selbst auf einer echten Karte ein** — deutlich weniger Aufwand als OSM-
Multipolygone zusammenzusetzen, und funktioniert für JEDEN See, unabhängig
von der OSM-Datenlage.

## Umsetzung

- **`CirclePacking.kt`** (neu, `geo`-Package): die Kreis-Ketten-Packung
  (`largestInscribedCircle`/`packChain`, Gitter-Suche nach dem jeweils
  grössten noch unabgedeckten einbeschriebenen Kreis, max. 8 Kreise, min.
  15m Radius) war bisher privat in `LakeAutoDetector` verdrahtet — 10.08.2026
  herausgelöst in ein eigenes, quellen-unabhängiges Modul. `packChain(polygon:
  List<GeoPoint>): List<LakeCircle>` ist der einzige öffentliche Einstieg,
  nimmt ein beliebiges Polygon entgegen (OSM-Way-Punkte ODER selbst
  gezeichnete Punkte, der Funktion ist die Quelle egal). `LakeAutoDetector`
  ruft jetzt `CirclePacking.packChain(way.points)` auf statt eigener Logik —
  reine Code-Verschiebung, Algorithmus/Verhalten unverändert.
- **`LakeDrawScreen.kt`** (neu, `ui/screens`): eigener Vollbild-Screen (wie
  `LandUhrScreen`, ersetzt komplett den Tab-Baum solange aktiv), osmdroid-
  `MapView` mit echten OSM-Kacheln (dieselbe Konfiguration wie
  `LandUhrScreen` — App-interner Cache, `CopyrightOverlay` für die
  ODbL-Attribution). Tippen auf die Karte (`MapEventsOverlay`) fügt einen
  Uferpunkt hinzu, Marker + halbtransparentes Polygon zeigen die Vorschau
  live. Buttons: "Letzten Punkt entfernen", "Abbrechen", "Fertig" (ab 3
  Punkten aktiv).
- **Bedienung: Antippen statt Freihand-Zeichnen** (Roman-Entscheidung
  10.08.2026) — präziser am Touchscreen, kollidiert nicht mit der
  Karten-Wisch-Geste (Pinch-Zoom/Pan bleiben normal nutzbar, da
  `MapEventsOverlay` nur einzelne, nicht-gezogene Taps abfängt).
- **`SegeluhrViewModel`**: `startLakeDrawing()`/`cancelLakeDrawing()`
  schalten `uiState.lakeDrawModeActive`; `finishLakeDrawing(polygon)` ruft
  `CirclePacking.packChain(polygon)` auf und ersetzt via
  `settingsRepo.setLakeCircles(...)` die komplette bestehende Kreis-Kette —
  **gleiches Verhalten wie bei `autoDetectLake()`** (nicht additiv), für
  Konsistenz zwischen den drei Eingabewegen.
- **Einordnung** (Roman-Entscheidung 10.08.2026): dritte Option NEBEN der
  automatischen Erkennung, ersetzt sie nicht. "See automatisch erkennen"
  bleibt für einfache Seen der schnellste Weg (kein Zeichnen nötig); die
  Karten-Zeichnen-Funktion ist der robuste Fallback für Fälle wie den
  Zürichsee — und ersetzt in der Praxis das bisherige GPS-Rand-Abfahren
  (umständlicher, braucht physische Präsenz am See), das nur noch als
  Offline-Fallback ohne Kartenkacheln sinnvoll bleibt.

## Startposition der Karte

Zentriert auf den aktuellen GPS-Fix (Zoom 14), falls einer vorliegt — sonst
groben Schweiz-Zoom (47.0/8.0, Zoom 8), Nutzer navigiert manuell zum
gewünschten See. Funktioniert also auch **von der Couch aus**, ohne am See
zu sein (im Gegensatz zu allen bisherigen Eingabewegen, die einen echten
GPS-Fix am Wasser brauchten).

## Bewusste Vereinfachungen

- Kein Undo über "letzten Punkt entfernen" hinaus (keine Punkt-Historie/
  Redo) — bei einem Fehler einfach von vorne (Abbrechen + neu starten).
- Kein Verschieben/Editieren einzelner bereits gesetzter Punkte — nur
  anhängen/letzten entfernen.
- Kein Speichern eines Zwischenstands beim Verlassen des Screens
  (Zurück-Geste/Abbrechen verwirft alle gesetzten Punkte) — analog zum
  bisherigen "Rand erfassen", das auch keinen Zwischenstand über einen
  App-Neustart hinweg vorhält.

**Noch nicht kompiliert/getestet** — insbesondere die `MapEventsOverlay`/
`Polygon`-Overlay-Interaktion (in diesem Projekt neu, `MapView`/`Marker`/
`CopyrightOverlay` selbst sind über `LandUhrScreen` bereits auf Hardware
verifiziert). Vor dem ersten Einsatz in Android Studio bauen + testen.
