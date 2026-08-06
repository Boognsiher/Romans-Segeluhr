# Erweiterung: Automatische See-Erkennung fürs Geofencing

> Nicht in der ursprünglichen Spezifikation. Erweitert die bestehende
> `LakeGeofenceEngine` um eine automatisierte Datenquelle statt manuellem
> Abstecken der Seegrenze auf dem Wasser.

## Status: KONZEPT — Umsetzung durch Claude Code gegen den aktuellen Code
von `LakeGeofenceEngine` und der zugehörigen Setup-UI. Ich kenne den
bestehenden Code der Engine nicht, daher hier auf Konzeptebene.

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
- [ ] HTTP-Client für Overpass-Anfrage festlegen (z.B. Retrofit/OkHttp,
  falls nicht schon im Projekt vorhanden)
- [ ] JTS als Abhängigkeit einbinden, Kompatibilität mit Android/Gradle
  prüfen (reines Java, sollte unproblematisch sein, aber verifizieren)
- [ ] Passende UTM-Zone(n) für die Schweiz festlegen (bzw. dynamisch anhand
  der Longitude wählen, falls das Projekt auch außerhalb der Schweiz
  genutzt werden könnte)
- [ ] Verhalten bei fehlendem Internetzugang beim Einrichten (Fehlermeldung,
  Retry) — danach ist ja alles offline nutzbar, nur der Einrichtungsschritt
  selbst braucht Netz
- [ ] Verhalten, falls GPS-Position beim Einrichten (noch am Ufer stehend,
  nicht auf dem Wasser) knapp außerhalb des erkannten Sees liegt — Toleranz
  einbauen, nicht nur "Punkt muss exakt im Polygon liegen"
- [ ] OpenStreetMap-Attribution in der App ergänzen (ODbL-Lizenz verlangt
  Namensnennung, z.B. im Impressum/Über-die-App-Screen)
- [ ] Verhältnis zu ggf. bereits vorhandener manueller Geofence-Eingabe in
  `LakeGeofenceEngine` klären — soll die automatische Erkennung die
  manuelle Eingabe ersetzen oder als Alternative daneben bestehen bleiben?
