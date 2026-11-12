# Erweiterung: Regatta-Tracker-Anbindung + Konfig-Ablage

> Schnittstellen-Vertrag: Quelle der Wahrheit ist das Tracker-Repo (`Boognsiher/Roemis-Regatta-Tracker`,
> `docs/GATEWAY_PROTOCOL.md`). Die Segeluhr-Repos bleiben bewusst getrennt. Dieses Dokument beschreibt,
> was die Segeluhr davon nutzt. Fremde LoRa-Tracker (Gateway-Funktion): siehe `Erweiterung_Regatta_Tracker_Gateway.md`.

## Warum

Der separate Server `Roemis-Regatta-Tracker` zeigt Boote live auf einer Karte. Bisher
brauchte jedes Boot dafür eine Browser-Seite auf dem Handy. Die Segeluhr-App hat den GPS-Fix
ohnehin — sie sendet ihn jetzt selbst. Zusätzlich kann die Geräte-Einrichtung (Wegpunkte,
Seegrenze, Boots-Profile, Betriebsmodus) pro Tracker auf dem Server abgelegt und auf einem
anderen/neuen Handy wieder geladen werden.

## Was geändert/ergänzt wurde

**App** (`tracker/TrackerClient.kt`, `SegeluhrViewModel`, `SettingsRepository`, Setup-Tab "Regatta-Tracker"):
- Tracker-Link (`https://host/t/<id>?token=<token>`, aus dem Admin-Bereich) einfügen -> Server,
  ID und Token werden in DataStore gespeichert (`TrackerLink.parse`).
- Schalter "Position senden": alle 5 s `POST /api/trackers/<id>/position` (lat, lon, accuracy,
  speed in m/s, heading = COG, battery 0..1, ts). Nur mit gültigem GPS-Fix, aus dem 1-Hz-Tick.
- Offline-Puffer: schlägt das Senden fehl (Funkloch), bleiben bis zu 200 Positionen im RAM und
  werden nachgeliefert, sobald wieder Netz da ist (mit Original-Zeitstempel, daher stimmt die Spur).
- "Konfig hochladen" -> `PUT /api/trackers/<id>/config`; "Konfig laden" -> `GET` und ersetzt die
  lokalen Werte. Bewusst manuell (keine Auto-Synchronisation), damit nichts ungewollt überschrieben wird.

**Server** (Repo Roemis-Regatta-Tracker): `GET`/`PUT /api/trackers/:id/config`, Bearer-Token wie bei
`/position`, abgelegt in `data/configs.json`.

## Konfig-Format (`data`)

```json
{ "app": "segeluhr", "version": 1,
  "waypoints": { "pin": {"lat":..,"lon":..}, "boat":.., "target":.., "buoy1":.., "buoy2":..,
                 "home":.., "competitionMark1":.., "competitionMark2":..,
                 "lakeCircles": [ {"lat":..,"lon":..,"radius":..} ] },
  "boatProfiles": [ {"id","name","closehauledAngleDeg","sampleCount","downwindAngleDeg"} ],
  "activeBoatProfileId": "...", "wakeLockEnabled": false, "operationMode": "STANDALONE|WITH_WATCH" }
```
Nicht enthalten: Windrichtung/-kalibrierung (tagesaktuell), App-Rolle (pro Handy), Tracker-Zugangsdaten.
Beim Laden werden in der Konfig fehlende Wegpunkte lokal gelöscht (Server-Stand ist maßgeblich).

## Offene Punkte

- **Noch nicht kompiliert/getestet** (Entwicklungsumgebung ohne Android-SDK) — beim nächsten
  Build in Android Studio auf Compile-Fehler achten, dann auf dem Wasser testen.
- Senden läuft über den ViewModel-Tick. Seit dem Merge von `smartphone-tracking-without-watch` (13.09.)
  läuft der Foreground-Service in beiden Betriebsmodi, das Senden geht also auch ohne Uhr bei
  ausgeschaltetem Display weiter. Bei "Stopp" (Setup-Tab "App-Betrieb") und in der Land-Rolle ruht es.
- Token liegt unverschlüsselt in DataStore (wie sonst alle Einstellungen).
- Zusatzdaten für die Karte (Rennstatus, Wind, Countdown) könnten später im Positions-POST mitgesendet werden.

## Genutzte Endpunkte

Alle ausser `/api/version` mit `Authorization: Bearer <Tracker-Token>`. Tracker-ID und Token kommen aus dem Tracker-Link im
Admin-Bereich des Servers (in der App einfuegen, im verschluesselten Speicher ablegen, nie ins Git).

| Zweck | Aufruf |
|---|---|
| Version pruefen | `GET /api/version` (ohne Login) -> `{ "api": 1, "minor": 0 }` |
| Position melden | `POST /api/trackers/<id>/position` mit `lat`, `lon`, `speed` (m/s), `heading`, `battery` (0..1), `ts` (ms, GPS-Zeit) |
| Nachgesendet (Funkloch) | `POST /api/trackers/<id>/positions` mit Liste |
| Konfiguration holen/ablegen | `GET/PUT /api/trackers/<id>/config` (beliebiges JSON-Objekt, max. 500 KB: Wegpunkte, Seegrenze, Boots-Profile) |

Wichtig: `ts` ist die **GPS-Zeit**, nicht die Handy-Uhrzeit (Grundlage der offiziellen Zeitmessung).

## Versionsregeln (Client-Seite)

Die Segeluhr erwartet **API-Major 1**. Vor dem ersten Senden (und bei jedem App-Start) `GET /api/version` abfragen:

- Major gleich -> normal senden. Hoeherer Minor ist unkritisch.
- Major abweichend -> **nicht senden**, Nutzer warnen ("Server-Schnittstelle passt nicht zur App, bitte App/Server aktualisieren").
- Server nicht erreichbar -> Positionen lokal puffern, spaeter senden (kein Abbruch).

Zusaetzlich liefert jede `/api/*`-Antwort den Header `X-API-Version: <major>.<minor>`.

## Kompatibilitaet bei Aenderungen

- Der Server fuegt nur Felder hinzu; Entfernen/Umdeuten bedeutet neue Major-Version (siehe Abschnitt "Versionierung" im Tracker-Repo).
- Beim Config-Objekt eigene Felder frei erweiterbar; der Server speichert es unveraendert.
- Die LoRa-Pakete der Segeluhr (`shared/LoRaPacket.h`, Boot-Uhr zu Land-Uhr, 31 Byte) sind **nicht** das Tracker-Funkpaket
  (`protocol.h`, 21 Byte, Magic `0xA7`) und davon unabhaengig.
