# Erweiterung: Regatta-Tracker-Gateway + Zuschauer-Rolle

## Warum

Andere Boote tragen ESP32-LoRa-Tracker, die ihre GPS-Position an ein zentrales
"RegattaMaster"-Board (ESP32 T-Beam) senden. Der Master reicht die Pakete per
BLE weiter — bisher übernahm das eine Web-Bluetooth-Seite im Browser. Die
Segeluhr-App übernimmt jetzt zusätzlich diese Gateway-Rolle: verbindet sich
per BLE mit dem Master, decodiert die LoRa-Pakete und leitet sie gesammelt an
denselben Tracker-Server weiter wie die eigene Position (siehe
`Erweiterung_Regatta_Tracker.md`).

Dazu (Roman-Wunsch) eine dritte Start-Rolle "Zuschauer", die direkt die
Live-Karten-Webseite des Trackers zeigt, sowie eine Status-Übersicht im
Setup-Tab ("wie viele Tracker senden gerade Daten").

## Was geändert/ergänzt wurde

**BLE** (`ble/GatewayClient.kt`, `ble/GatewayPacket.kt`):
- `GatewayClient`: Handy = BLE-Central zum "RegattaMaster" (Service
  `a7d0c001-…`, Notify-Characteristic `a7d0c002-…`), nach demselben Muster
  wie `LandUhrClient` (Scan → Connect → Notify abonnieren, automatischer
  Reconnect bei Abbruch, da der Master selbst nichts puffert).
- `GatewayPacketCodec.decode()`: 24-Byte-Little-Endian-Binärformat (magic,
  LoRa-ID, Zeitstempel, lat/lon ×1e6, Speed/Heading, Akku-%, HDOP, RSSI, SNR).
  Verwirft ungültige Pakete (falsches Magic-Byte oder Lat/Lon ausserhalb des
  gültigen Bereichs).

**Persistenz** (`data/db/GatewayQueueDb.kt`, DB-Version 2→3):
- Neue Room-Tabelle `gateway_positions` als Offline-Puffer — bewusst
  persistent (nicht nur RAM wie beim eigenen Positions-Puffer in
  `SegeluhrViewModel`), weil hier Fremddaten reinkommen, die der Master
  selbst nicht noch einmal schicken kann.
- Akkustand wird erst hier von 0..100 (Wire-Format) auf 0..1 (Server-Format)
  umgerechnet — bewusst nicht im Codec, damit `GatewayPacket.batteryPct` 1:1
  das Byte-Protokoll widerspiegelt.

**Upload** (`tracker/GatewayUploader.kt`):
- `POST /api/gateway/positions`, Header `x-gateway-token` (eigenes Secret,
  unabhängig vom Bearer-Token des eigenen Tracker-Zugangs), Batch aus der
  Warteschlange. Nutzt dieselbe Server-URL wie der eigene Tracker-Zugang
  (`SettingsRepository.trackerSettingsFlow.serverUrl`) — ein Server für
  beides, aber getrenntes Secret.

**ViewModel/UI** (`SegeluhrViewModel.kt`, `SetupScreen.kt`):
- Alle 4s (`GATEWAY_FLUSH_INTERVAL_MS`) wird die Warteschlange in Batches zu
  je max. 500 (`GATEWAY_BATCH_SIZE`, Server-Limit 2000) gesendet; bei Fehler
  bleibt der Batch unverändert in der Queue für den nächsten Versuch.
- Setup-Tab-Sektion "Regatta-Tracker-Gateway": Token eingeben/entfernen,
  Schalter "Weiterleiten aktiv", BLE-Verbindungsstatus, Status-Übersicht pro
  LoRa-Tracker (In-Memory, `gatewayTrackerStatuses` im ViewModel) — zeigt pro
  Tracker zuletzt-gesehen-vor-Xs, Akku, RSSI, und "N von M aktiv" (Schwelle
  10s ohne neues Paket, siehe `GATEWAY_TRACKER_ACTIVE_MS` in `SetupScreen.kt`).
- Läuft bewusst UNABHÄNGIG von App-Stopp/Boots-Rolle (`resumeBackgroundWork`/
  `pauseBackgroundWork`) — die Gateway-Rolle hat nichts mit der eigenen
  GPS-Tick-Schleife zu tun, nur mit dem Ein/Aus-Schalter.

**Zuschauer-Rolle** (`data/model/Models.kt` — `AppRole.SPECTATOR`,
`ui/screens/SpectatorScreen.kt`, `ui/screens/RolePickerScreen.kt`):
- Dritte Start-Rollenwahl neben "Auf dem Boot"/"An Land". Zeigt die
  Live-Karten-Webseite des Tracker-Servers per WebView (Link wird einmalig
  eingegeben, über `SettingsRepository.spectatorUrlFlow` gemerkt). Kein
  eigener GPS/BLE-Code — die Karte lebt komplett auf dem Server.
- Verhält sich wie `AppRole.SHORE` bezüglich Hintergrundarbeit (GPS/Tick/
  Foreground-Service pausiert, siehe `SegeluhrViewModel.setAppRole`).

## Offene Punkte

- **11.11.2026 mit echter Hardware getestet** (1 LoRa-Tracker + 1
  RegattaMaster-Board): BLE-Verbindung, MTU-Verhandlung, CCCD-Abo und
  Paket-Empfang laufen stabil, Tracker-Übersicht aktualisiert sich alle ~2s.
  Dabei Bug gefunden+gefixt: Default-ATT-MTU (23 Byte) schnitt unser
  24-Byte-Paket auf 20 Byte ab (BLE-Notifies werden nicht automatisch über
  mehrere Pakete verteilt) — `GatewayClient` fordert jetzt per
  `requestMtu()` ein größeres MTU an, bevor `discoverServices()` läuft.
  Server-Upload (`GatewayUploader`) noch nicht live verifiziert, da der
  Tracker-Server während des Tests offline war — Pakete liegen bis dahin in
  der persistenten Queue.
- Gateway-Token liegt wie alle anderen Einstellungen unverschlüsselt in
  DataStore.
- "Aktiv"-Schwelle (10s) und Batch-Intervall (4s) sind erste Schätzwerte,
  noch nicht gegen echten LoRa-Traffic mehrerer Boote verifiziert.
- Zuschauer-WebView hat keinen eigenen Fehler-/Offline-Zustand (z.B. bei
  falschem Link) — zeigt einfach, was die WebView selbst anzeigt.
- **GPS-Status pro Tracker (12.11.2026):** Die Übersicht färbte einen Tracker grün, sobald ein Paket ankam, auch ohne
  GPS-Fix. Jetzt: grün = Paket + Fix, amber = Paket ohne Fix ("KEIN GPS"), grau = inaktiv; die Kopfzeile nennt "N davon
  mit GPS". Fix abgeleitet aus Position ≠ (0,0) und bekanntem HDOP (`GatewayPacket.hasGpsFix`) — Annahme, gegen die
  Tracker-Firmware zu prüfen. Pakete ohne Fix werden weiterhin hochgeladen (offen, siehe `Erweiterung_Geraete_Logik.md`).

