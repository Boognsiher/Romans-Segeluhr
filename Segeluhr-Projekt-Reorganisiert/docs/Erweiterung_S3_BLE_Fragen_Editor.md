# Erweiterung: Eigene Fragen erstellen (S3 + BLE + Webseite)

> Nicht in der ursprünglichen Spezifikation. Bewusste Abweichung von der
> früheren Entscheidung "S3 hat kein BLE mehr" — hier gezielt wieder
> eingeführt, aber ein-/ausschaltbar, nicht dauerhaft aktiv.

## Status: KONZEPT — Umsetzung durch Claude Code gegen den aktuellen Code
von Segeluhr_TWatch_S3.ino.

## 1. Ziel
Zusätzlich zu den 10 vordefinierten Fragen (`QuickQuestion`-Enum) soll die
Crew eigene, freie Text-Fragen erstellen können — bequemer getippt auf dem
Handy statt auf der kleinen Uhr.

## 2. Architektur

### BLE ein-/ausschaltbar (Menü-Einstellung)
- Neue Kachel im Icon-Menü: "Fragen" (siehe Redesign) → führt in die
  Fragenverwaltung
- Dort: Toggle "BLE für Fragen-Editor" ein/aus (Standard: aus)
- Nur wenn eingeschaltet, macht sich die S3 als BLE-Peripheral sichtbar —
  bewusst nicht dauerhaft aktiv, um die "S3 ist einfach/lorabasiert"-Idee
  so weit wie möglich zu erhalten und Akku zu sparen

### Web Bluetooth statt eigener App
- Crew öffnet auf dem Handy eine simple Webseite (kann z.B. als GitHub-
  Pages-Seite oder lokal gehostete HTML-Datei bereitgestellt werden) im
  Browser (Chrome/Edge auf Android — **Web Bluetooth funktioniert NICHT auf
  iOS Safari**, wichtige Einschränkung, siehe Abschnitt 4)
- Seite verbindet sich per `navigator.bluetooth.requestDevice()` direkt mit
  der S3 (kein App-Install nötig)
- Einfaches Textfeld + "Senden"-Button, schreibt die neue Frage über eine
  BLE-Characteristic auf die Uhr
- S3 speichert die neue Frage zusätzlich zu den 10 vordefinierten (in
  DataStore-ähnlicher Persistenz auf dem ESP32, z.B. NVS/Preferences)

### Datenfluss
```
Handy-Browser (Web Bluetooth) --BLE--> S3 (neuer GATT-Service, nur wenn
    "Fragen-Editor" eingeschaltet) --speichert--> zusätzliche Frage im
    Fragenkatalog, ab sofort im normalen Fragen-Menü mit auswählbar
```

## 3. Auswirkung auf bestehende Strukturen
- `QuickQuestion`-Enum (feste 10 Fragen) bleibt bestehen für die
  vordefinierten Fragen — eigene Fragen brauchen eine **separate** Struktur
  (z.B. `CustomQuestion { id, text }`), da der feste Enum keine beliebigen
  Strings aufnehmen kann
- `QuickMessageRequest`-Paket (LoRa) muss angepasst werden: statt nur
  `QuickQuestion question` auch eine Variante für Custom-Fragen (z.B. per
  Text-ID, die auf beiden Uhren gleich aufgelöst wird — oder den Fragetext
  direkt mitschicken, falls Platz im Paket reicht, siehe Abschnitt 5)

## 4. Wichtige Einschränkung: Web Bluetooth Plattform-Support
- Funktioniert: Chrome/Edge/Opera auf Android, Chrome/Edge auf Desktop
- Funktioniert NICHT: Safari auf iOS (Apple unterstützt Web Bluetooth
  grundsätzlich nicht in Safari) — falls jemand aus der Crew ein iPhone
  hat, geht der Weg über die Webseite dort nicht
- Alternative für iOS-Nutzer: Fallback auf Option A von vorhin (Text direkt
  auf der Uhr eintippen) bleibt bestehen, nur unbequemer

## 5. Offene technische Punkte
- [ ] Wie viele eigene Fragen sollen maximal speicherbar sein (Speicher-
  limit auf dem ESP32 klein halten)?
- [ ] Paketformat für Custom-Fragen in `QuickMessageRequest` festlegen —
  Text direkt mitschicken (Paketgröße wächst) vs. nur Text-ID (Land-Uhr
  müsste den Text dann selbst kennen, was bei "auf dem Handy erstellt"
  bedeutet: der Text muss erst noch von der S3 zur Ultra transportiert
  werden, damit die Ultra überhaupt weiß, was gefragt wurde — vermutlich
  einfacher: Text bis zu einer Maximallänge direkt im LoRa-Paket mitschicken)
- [ ] BLE-Service/Characteristic-UUIDs definieren (analog zu den
  bestehenden UUIDs im Android-BLE-Protokoll, aber komplett getrennt davon
  — das ist ein neuer, eigenständiger Service auf der S3, nicht derselbe
  wie der Handy-zu-Uhr-Service aus der alten Architektur)
- [ ] Sicherheit: soll der BLE-Fragen-Editor einen PIN/eine Bestätigung
  brauchen, oder reicht "muss erst im Menü eingeschaltet werden" als
  Zugriffsschutz? (Vermutlich ausreichend für den Anwendungsfall, da BLE ja
  standardmäßig aus ist und nur kurz für den Editier-Vorgang eingeschaltet
  wird)
