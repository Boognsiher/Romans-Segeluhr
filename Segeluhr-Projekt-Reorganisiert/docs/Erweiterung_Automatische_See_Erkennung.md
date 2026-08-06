# Erweiterung: Automatische See-Erkennung fürs Geofencing

> Nicht in der ursprünglichen Spezifikation. Erweitert die bestehende
> `LakeGeofenceEngine` um eine automatisierte Datenquelle statt manuellem
> Abstecken der Seegrenze auf dem Wasser.

## Status: 🔧 UMGESETZT (06.08.2026) — **nicht kompiliert, nicht getestet**,
siehe Warnung unten. Zwei wichtige Abweichungen von der ursprünglichen
Doku-Vision (beide Roman-Entscheidungen 06.08.2026, siehe Abschnitt 6):
**(1)** `LakeGeofenceEngine` kannte keine Polygon-Geofence, nur einen
einzelnen Kreis (Mittelpunkt+Radius) — die volle JTS/OSM-Polygon-Vision
wurde durch eine **Kette von Kreisen** ersetzt (deutlich kleinerer Umbau).
**(2)** Ein einzelner Kreis deckt lange/unregelmässige Seen nicht ab —
deshalb Kreis-KETTE statt Einzelkreis, mit entsprechendem Umbau von
`LakeGeofenceEngine`, `SettingsRepository` und der Setup-UI.

> ⚠️ **Für dieses Feature konnte kein Compile-Lauf durchgeführt werden** —
> anders als bei der Firmware (wo `arduino-cli compile` zur Verfügung
> steht) gibt es im Android-Projekt keinen `gradlew`-Wrapper/keine lokale
> Gradle-Installation. Der Code wurde sorgfältig manuell durchgesehen
> (Typen, Imports, Kotlin-Syntax), aber **vor dem ersten echten Einsatz
> unbedingt einmal in Android Studio bauen/testen**, bevor er auf dem
> Segel-Handy verwendet wird.

## 1. Ziel
Statt die Seegrenze manuell auf dem Wasser abzufahren/einzugeben, erkennt
die App den See automatisch anhand des aktuellen GPS-Standorts, lädt die
echte Uferlinie aus OpenStreetMap-Daten und berechnet daraus automatisch
eine nach innen versetzte Geofence-Grenze (Sicherheitsabstand zum Ufer).

## 2. Ablauf

1. Nutzer tippt in den Einstellungen auf "See automatisch erkennen"
2. App ermittelt aktuellen GPS-Standort (`FusedLocationProviderClient`,
   bereits im Projekt genutzt)
3. Overpass-API-Anfrage: Wassergeometrien (`natural=water`) in einem
   Radius (z.B. 5km) um den aktuellen Standort abfragen
4. Ermitteln, welches der zurückgegebenen Gewässer den aktuellen Standort
   tatsächlich enthält (bei mehreren Treffern z.B. kleinerer Teich in der
   Nähe: größte/plausibelste Fläche wählen, oder bei echter Mehrdeutigkeit
   eine kurze Auswahlliste anzeigen statt zu raten)
5. Rohe Uferlinie (Polygon, ggf. Multipolygon bei komplexen Seen mit
   Buchten/Inseln) aus den Overpass-Daten zusammensetzen
6. **Vereinfachen** (Douglas-Peucker-Algorithmus) — rohe OSM-Seeumrisse
   können tausende Punkte haben, für Echtzeit-Geofence-Prüfung auf dem
   Handy unnötig fein
7. Vereinfachte Uferlinie lokal speichern (Room/DataStore) — das ist die
   einmalige Online-Aktion, danach offline nutzbar
8. **Pufferberechnung** (Sicherheitsabstand nach innen): aus der
   gespeicherten Uferlinie + dem vom Nutzer eingestellten Abstand lokal
   berechnen — läuft komplett offline, damit Abstands-Änderungen keinen
   erneuten Netzwerkzugriff brauchen
9. Ergebnis (gepufferte Geofence-Grenze) an `LakeGeofenceEngine` übergeben

## 3. Technische Kernpunkte

### Overpass-API-Abfrage
Beispielhafte Query-Struktur (Details/Bibliothek für den HTTP-Call durch
Claude Code festlegen):
```
[out:json];
(
  way["natural"="water"](around:5000,<lat>,<lon>);
  relation["natural"="water"](around:5000,<lat>,<lon>);
);
out geom;
```
Relationen (Multipolygone, bei Seen mit Inseln/komplexen Buchten) brauchen
zusätzliche Logik, um äußere/innere Ringe aus den Member-Ways zusammenzusetzen
— nicht trivial, aber ein bekanntes, gut dokumentiertes Muster.

### Geometrische Pufferberechnung
- **Wichtig:** Vor dem Puffern muss von Lat/Lon (Grad) in ein metrisches
  Koordinatensystem projiziert werden (z.B. passende UTM-Zone für die
  Schweiz), sonst stimmt "50 Meter Abstand" nicht — Grad-Abstände sind
  keine gleichmäßigen Meter-Abstände
- Empfehlung: **JTS (Java Topology Suite)** für die eigentliche
  Puffer-Operation (`Geometry.buffer(-abstandInMetern)`) — robuste,
  etablierte Bibliothek für genau solche Operationen, sollte auf Android
  ohne Probleme laufen (reines Java)
- Nach dem Puffern zurück nach Lat/Lon reprojizieren für Speicherung/Nutzung
  in der bestehenden Engine
- Ergebnis kann bei komplexen Uferlinien in mehrere getrennte Polygone
  zerfallen (z.B. schmale Buchten, die durch den Puffer "abgeschnürt"
  werden) — `LakeGeofenceEngine` muss ggf. mit einer Liste von Polygonen
  statt nur einem umgehen können

### Persistenz
- Rohe (vereinfachte) Uferlinie: einmal online geladen, lokal gespeichert
- Gepufferte Geofence-Grenze: lokal aus der gespeicherten Uferlinie
  berechnet, neu berechnet bei jeder Änderung des Sicherheitsabstands
  (kein erneuter Netzwerkzugriff nötig)

## 4. UI/Einstellungen
- Button "See automatisch erkennen" (nutzt aktuellen GPS-Standort)
- Bei Mehrdeutigkeit: kurze Auswahlliste statt automatisch zu raten
- Slider/Eingabefeld für Sicherheitsabstand (z.B. 10-200m, sinnvoller
  Standardwert z.B. 50m), Änderung löst lokale Neuberechnung aus, kein
  Reload nötig
- Bestätigungsanzeige (z.B. Kartenvorschau oder zumindest Textbestätigung
  "See XY erkannt, Geofence mit 50m Abstand berechnet")

## 5. Offene Punkte
- [x] HTTP-Client: OkHttp (neue Abhängigkeit, `app/build.gradle.kts`).
  JSON-Parsing über das in Android eingebaute `org.json` statt einer
  weiteren Abhängigkeit.
- [x] JTS **nicht** eingebunden — bewusst vermieden, siehe Abschnitt 6
  (Kreis-Kette statt Polygon-Pufferung macht JTS überflüssig).
- [x] UTM-Zonen **nicht** nötig — einfache Äquirektangular-Projektion um
  den GPS-Fix als lokalen Ursprung, für die Grösse eines einzelnen Sees
  (wenige km) ausreichend genau, siehe `LakeAutoDetector.LocalProjection`.
- [x] Verhalten bei fehlendem Internetzugang: klare Fehlermeldung über den
  bestehenden `StatusSink` (kein Retry-Mechanismus, Nutzer versucht es
  über den Button einfach erneut).
- [x] Toleranz für "am Ufer stehend": `EDGE_TOLERANCE_M = 50.0` — liegt der
  GPS-Fix in keinem gefundenen Gewässer-Polygon, wird das nächstgelegene
  innerhalb von 50m trotzdem akzeptiert.
- [ ] OpenStreetMap-Attribution in der App **noch nicht ergänzt** (ODbL-
  Lizenz verlangt Namensnennung, z.B. im Impressum/Über-die-App-Screen) —
  vor einem Play-Store-Release oder sonstiger Veröffentlichung nachholen.
- [x] Verhältnis zur manuellen Eingabe geklärt: automatische Erkennung
  **ersetzt** die komplette bestehende Kreis-Kette (nicht additiv), manuelle
  Eingabe bleibt als vollwertiger Fallback bestehen (z.B. wenn der See als
  OSM-Relation vorliegt, siehe bekannte Einschränkung in Abschnitt 6).

## 6. Tatsächliche Umsetzung (Abweichung von Abschnitt 2/3)

Die folgenden Abschnitte (2/3 oben) beschreiben die ursprünglich gedachte
Polygon-Pufferungs-Lösung — **so nicht umgesetzt**. Tatsächlicher Ablauf:

1. Nutzer tippt im Training-Tab auf "See automatisch erkennen"
2. Overpass-API-Abfrage (`natural=water`, 5km-Radius) um den aktuellen
   GPS-Standort — **nur einfache OSM-"way"-Geometrien**, keine
   Relationen/Multipolygone (siehe bekannte Einschränkung unten)
3. Welches Gewässer gemeint ist: enthält der GPS-Punkt eines der
   gefundenen Polygone, wird bei mehreren Treffern das flächenmässig
   grösste gewählt; liegt der Punkt in keinem (z.B. noch am Ufer stehend),
   wird das nächstgelegene innerhalb von 50m akzeptiert
4. Statt Douglas-Peucker-Vereinfachung + JTS-Pufferung + Speicherung der
   rohen Uferlinie: direkt eine **Kette von Sicherheits-Kreisen** aus dem
   Polygon berechnen (`LakeAutoDetector.packCircleChain()`) — greedy
   Gitter-Suche nach dem jeweils grössten Kreis, der noch komplett
   innerhalb des Polygons UND ausserhalb bereits gepackter Kreise liegt,
   bis zu 8 Kreise oder Mindestradius 15m unterschritten wird
5. Ergebnis ersetzt die komplette bisherige Kreis-Kette in
   `SettingsRepository` (JSON-Array, `LAKE_CIRCLES_JSON`)

**`LakeGeofenceEngine`-Umbau:** `tick()`/`distancePct()` nehmen jetzt
`List<LakeCircle>` statt `GeoPoint? + Double?`. Die 80%/65%-Warn-Hysterese
wird auf den **besten (kleinsten) Prozentwert über alle Kreise** angewendet
— eine Position ist sicher, sobald sie innerhalb IRGENDEINES Kreises liegt.

**Manuelle Eingabe** (Setup/Training-Tab) ebenfalls auf die Kreis-Kette
umgestellt: "Kreis hinzufügen" (aktuelle Position = neuer Mittelpunkt) +
"Rand erfassen" (mehrfach möglich, wirkt auf den zuletzt hinzugefügten
Kreis, kleinster gemessener Abstand wird dessen Radius) — jeder Kreis kann
einzeln aus der Liste entfernt werden.

**Bekannte Einschränkung:** grössere/komplexere Seen sind in OSM oft als
Multipolygon-"relation" erfasst (z.B. mit Inseln oder aus mehreren
Uferabschnitten zusammengesetzt) — das Zusammensetzen solcher Relationen
wurde bewusst NICHT implementiert (genau der Teil, der "nicht trivial"
ist und den Aufwand am meisten erhöht hätte). Liegt der Zielsee nur als
Relation vor, schlägt die automatische Erkennung fehl und die manuelle
Eingabe bleibt der Weg.
