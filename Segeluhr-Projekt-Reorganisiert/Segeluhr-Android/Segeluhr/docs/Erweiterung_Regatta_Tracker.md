# Erweiterung: Anbindung an den Regatta-Tracker

Die Segeluhr-App kann ihre Position an den selbst gehosteten **Roemis Regatta Tracker** (eigenes Repo:
`Boognsiher/Roemis-Regatta-Tracker`) senden und ihre Konfiguration dort ablegen. Die Repos bleiben bewusst getrennt;
**die Schnittstelle ist im Tracker-Repo definiert** (`docs/GATEWAY_PROTOCOL.md`, dort Quelle der Wahrheit). Dieses Dokument
beschreibt nur, was die Segeluhr davon nutzt.

> Stand 12.11.2026: Dokumentiert den Vertrag. Im Segeluhr-Code ist die Anbindung noch nicht umgesetzt
> (kein Aufruf von `/api/trackers` im Repo). Beim Umsetzen diese Datei aktualisieren.

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
