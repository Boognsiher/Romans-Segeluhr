# Erweiterung: Eigene Fragen erstellen (S3 + BLE + Webseite)

> Nicht in der ursprünglichen Spezifikation. Bewusste Abweichung von der
> früheren Entscheidung "S3 hat kein BLE mehr" — hier gezielt wieder
> eingeführt, aber ein-/ausschaltbar, nicht dauerhaft aktiv.

## Status: 🔧 UMGESETZT, KOMPILIERT (06.08.2026) — noch nicht auf Hardware
getestet. Firmware-Seite (BLE-Service, Speicherung, Fragen-Browser) und
Web-Bluetooth-Seite (`docs/fragen_editor_web/index.html`) beide fertig.
Siehe Abschnitt 5 (aktualisiert) für die getroffenen Entscheidungen bei den
zuvor offenen Punkten.

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

## 5. Offene technische Punkte — jetzt entschieden/umgesetzt

- [x] **Maximale Anzahl eigener Fragen: 5** (`CUSTOM_QUESTION_MAX_COUNT` in
  `QuickMessages.h`). Ringpuffer — die sechste neue Frage überschreibt die
  älteste, kein "Speicher voll"-Fehler nötig.
- [x] **Paketformat**: Text direkt im `QuickMessageRequest` mitgeschickt
  (`isCustom`-Flag + `customText[32]`), wie in der Doku als "vermutlich
  einfacher" vorgeschlagen. `QuickMessageRequest` dadurch von 4 auf 37 Byte
  gewachsen — unkritisch, Quick-Messages sind sporadisch, nicht der 30s-
  Status-Broadcast. **Beide Firmwares müssen zusammen neu geflasht werden**
  (wie schon beim Zeit-Sync-Feature), sonst laufen Sender/Empfänger
  auseinander.
- [x] **BLE-UUIDs definiert**: Service `7a6e0001-b5a3-f393-e0a9-e50e24dcca9e`,
  Characteristic (WRITE) `7a6e0002-...` — eigener Präfix (`7a6e` statt
  `6f6e` beim Android-Protokoll), komplett getrennter Service, existiert
  nur auf der S3.
- [x] **Sicherheit**: kein PIN, wie in der ersten Doku-Version schon
  vermutet — "BLE ist per Menü-Toggle standardmäßig aus" reicht als
  Zugriffsschutz für diesen Anwendungsfall.

## 6. Implementierungsdetails

- **Firmware** (`Segeluhr_TWatch_S3.ino`): `CustomQuestion`-Array (Text +
  `used`-Flag), persistiert per `Preferences` (NVS, Namespace `customq`).
  BLE-Toggle im Fragen-Screen (`swBleEditor`) ruft
  `startFragenEditorBle()`/`stopFragenEditorBle()` — Start macht
  `NimBLEDevice::init()` + Server/Service/Characteristic frisch auf, Stop
  ruft `NimBLEDevice::deinit(true)` (kompletter Abbau, nicht nur
  Advertising-Stop) — damit läuft der BLE-Stack tatsächlich nur, solange
  der Editor eingeschaltet ist, nicht nur "unsichtbar aber aktiv".
  `FragenEditorWriteCallbacks::onWrite()` nimmt den geschriebenen Text
  entgegen, kappt ihn bei 31 Zeichen (`CUSTOM_QUESTION_MAX_LEN - 1`) und
  legt ihn als neue `CustomQuestion` ab.
- **Fragen-Browser** (`cbQuickNext`/`cbQuickSend`): blättert jetzt über
  einen kombinierten Index (0-9 = feste `QuickQuestion`, 10-14 = eigene
  Fragen), überspringt noch leere Custom-Slots automatisch.
- **Boots-Uhr** (`Segeluhr_TWatch_Ultra.ino`): zeigt eingehende eigene
  Fragen korrekt an (`quickMessageRequestText()`-Helper in
  `QuickMessages.h`, wählt automatisch zwischen `quickQuestionText()` und
  `customText`) — sendet selbst aber weiterhin nur die 10 festen Fragen,
  da sie keinen BLE-Fragen-Editor hat (wie in Abschnitt 1 beschrieben:
  eigene Fragen werden auf der S3 erstellt).
- **Web-Bluetooth-Seite**: `docs/fragen_editor_web/index.html` — eigen-
  ständige, selbst-enthaltene HTML-Datei (kein Build-Schritt), Textfeld +
  Verbinden/Senden-Buttons, nutzt `navigator.bluetooth.requestDevice()`
  gefiltert auf die Service-UUID. Kann direkt als GitHub-Pages-Quelle
  (`docs/`-Ordner) veröffentlicht oder lokal geöffnet werden.

### Weiterhin offen
- [ ] Auf Hardware getestet (BLE-Verbindungsaufbau vom Handy, tatsächliches
  Schreiben einer Frage, Sende-/Empfangs-Test der neuen Frage über LoRa)
- [ ] Kein Fehler-Feedback zur Webseite, falls eine Frage z.B. wegen
  falscher Kodierung nicht ankommt — aktuell rein "fire and forget"
  (Characteristic hat keine READ/NOTIFY-Bestätigung)
