# Erweiterung: Regatta-Tracker-Anbindung + Konfig-Ablage

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
