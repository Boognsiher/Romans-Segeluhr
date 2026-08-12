# Erweiterung: Bojen/Marken auf der Karte setzen

> Nicht in der ursprünglichen Spezifikation. Roman-Wunschliste 12.08.2026
> ("vor dem nächsten Test").

## Status: 🔧 Code-komplett — noch NICHT kompiliert/getestet.

## 1. Ziel
Bisher liess sich jeder Wegpunkt (Bojen, Wettfahrt-Marken, Heimatpunkt,
Startlinie, ...) über `WaypointRow`/"Setzen" nur auf die AKTUELLE
GPS-Position fixieren — analog zum ursprünglichen GPS-Rand-Abfahren beim
See-Geofence. Roman wollte dieselbe Kartenauswahl, die es fürs
See-Geofence-Zeichnen schon gibt (`docs/Erweiterung_Seegrenze_Zeichnen.md`),
auch für einzelne Wegpunkte — eigener Button neben "Setzen" statt nur die
eigene Position fixieren zu können.

## 2. Umsetzung
- `WaypointRow` (`ui/components/Common.kt`) bekommt einen optionalen
  dritten Callback `onSetFromMap: (() -> Unit)? = null`. Ist er gesetzt,
  erscheint ein zusätzlicher "Karte"-Button neben "Setzen". Default `null`
  lässt alle bestehenden Aufrufstellen (See-Kreise, Rand erfassen)
  unverändert.
- Neuer Screen `ui/screens/WaypointMapPickScreen.kt` — vom selben
  osmdroid-Muster wie `LakeDrawScreen.kt` abgeleitet (MapView,
  `MapEventsOverlay` für Taps, `CopyrightOverlay`-Pflicht-Attribution),
  aber EIN Punkt statt einer Kette: Tippen setzt/verschiebt einfach immer
  denselben Marker. Bewusst ein eigener kleiner Screen statt
  `LakeDrawScreen` mit einem `maxPoints=1`-Sonderfall zu verzweigen — dessen
  Ketten-/Polygon-Logik (`MIN_POINTS=3`, `CirclePacking`) passt
  konzeptionell nicht zu "ein Wegpunkt".
- Navigations-Muster identisch zu `startLakeDrawing()`/`cancelLakeDrawing()`
  in `SegeluhrViewModel.kt`: neuer State `waypointMapPickKey: String?` in
  `SegeluhrUiState` (null = inaktiv), `MainActivity.kt` ersetzt bei
  gesetztem Key komplett den Tab-Baum durch `WaypointMapPickScreen` (gleiche
  Ebene wie der bestehende `lakeDrawModeActive`-Check).
- Neue ViewModel-Funktionen: `startWaypointMapPick(key)`,
  `cancelWaypointMapPick()`, `finishWaypointMapPick(point)` — Letztere ruft
  denselben `settingsRepo.setWaypoint(key, point)` wie das bestehende
  `captureWaypoint()`, nur mit dem angetippten statt dem aktuellen
  GPS-Punkt.

## 3. Wo verdrahtet
- `TrainingScreen.kt`: `buoy1`/`buoy2` (explizit gewünscht).
- `SetupScreen.kt`: `competitionMark1`/`competitionMark2` (gleiche
  "Boje/Bake"-Kategorie, bisher ebenfalls nur GPS-fixierbar).
- Bewusst NICHT verdrahtet: `home`/`target`/`pin`/`boat` — reiner GPS-Fix
  ergibt dort mehr Sinn (eigene Position markieren, keine fremde Boje).
  Dank der gemeinsamen `WaypointRow`-Erweiterung ist das Nachrüsten pro
  Zeile trivial, falls gewünscht.

## 4. Offene Punkte
- [ ] Noch nicht kompiliert/getestet.
- [ ] Kein Drag-Handle am Marker — Feinjustierung geht nur per erneutem
  Antippen (bewusst einfach gehalten, gleiche "Tippen statt Freihand"-
  Philosophie wie beim See-Zeichnen).
