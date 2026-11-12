# Gerätelogik: wer macht was, wer spricht mit wem (Gesamtübersicht)

Stand 12.11.2026, geschrieben nach dem Zusammenführen aller Branches auf `main`. Dieses Dokument ist die **zentrale
Übersicht** über Rollen, Verbindungen und Datenflüsse. Details stehen in den jeweils verlinkten `Erweiterung_*.md`; bei
Widerspruch gilt der Code, danach diese Datei.

**Leitidee:** Die Android-App ist das **Hirn des eigenen Boots** (GPS, Wind, Kurs, Regatta-Logik, Entscheidungen) und
gleichzeitig das **Relais für fremde Tracker** (LoRa-Pakete anderer Boote → Tracker-Server). Alle Uhren und Geräte am
eigenen Boot sind Anzeige/Bedienung/Funkstrecke, keine eigene Logik.

## 1. Geräte und Rollen

| Gerät | Hardware | Rolle | Verbindung zum Handy | Status |
|---|---|---|---|---|
| **Handy / Segeluhr-App** | Android (Samsung) | Hirn des eigenen Boots + Relais | – | ✅ läuft |
| **Boots-Uhr (T-Watch Ultra)** | LilyGO Ultra, SX1262 | Anzeige/Haptik am Handgelenk **und** LoRa-Sender des Boot-Status an die Land-Uhr | BLE: Uhr = Central, Handy = GATT-Server | 🔧 |
| **Boots-Uhr (Galaxy Watch 5 Pro)** | Wear OS | Alternative zur Ultra: Anzeige/Haptik/Tasten, **ohne LoRa** | BLE wie Ultra | 🔧 |
| **Land-Uhr (T-Watch S3)** | LilyGO S3 | Empfängt den Boot-Status per LoRa (31-Byte-Paket), zeigt ihn an. Kein eigenes GPS | BLE: Handy (Land-Rolle) = Central, S3 = GATT-Server (Position, Fragen-Editor) | 🔧 |
| **RegattaMaster** | ESP32 T-Beam, LoRa | **Auf dem eigenen Boot**: empfängt per LoRa die Positionen aller anderen Tracker und reicht sie per BLE ans Handy | BLE: Handy = Central, Master = Peripheral (Notify) | ✅ mit Hardware getestet (11.11.) |
| **LoRa-Tracker** | ESP32 + GPS + LoRa | Sitzt auf einem fremden Boot, sendet dessen Position per LoRa | nur LoRa → Master | ✅ |
| **Tracker-Server** | `Roemis-Regatta-Tracker` (eigenes Repo) | Sammelt alle Positionen, zeigt die Live-Karte, legt die App-Konfiguration ab | HTTPS vom Handy | ✅ |
| **Mastuhr** | Eigenbau ESP32-S3 + GPS + LoRa | Geplanter Ersatz/Ergänzung der Boots-Uhr am Mast | – | 📋 Konzept, siehe `docs/Erweiterung_Mastuhr.md` |

Handy-Rollen beim App-Start (`AppRole`): **SAILOR** (Boot, alles aktiv), **SHORE** (Land: Karte der Land-Uhr),
**SPECTATOR** (nur die Live-Karte des Trackers als WebView). SHORE und SPECTATOR pausieren GPS, Tick und Foreground-Service.
Davon unabhängig: `OperationMode` **STANDALONE** (Handy vibriert selbst) oder **WITH_WATCH** (Haptik per BLE an die Uhr).

## 2. Datenflüsse

```
 EIGENES BOOT                                                         ANDERE BOOTE
 ┌────────────────────────────────────────┐
 │  Handy-GPS ─► SegeluhrViewModel (Hirn) │                        LoRa-Tracker ─┐
 │      │   Engines: Wind, Competition,   │                        LoRa-Tracker ─┼─ LoRa ─┐
 │      │   Training, Heimweg, Start      │                        LoRa-Tracker ─┘        │
 │      │                                 │                                               ▼
 │      ├─ BLE GATT-Server ─► Boots-Uhr   │                                      RegattaMaster (auf dem
 │      │   (Ultra / Galaxy Watch)        │   BLE Notify (24 Byte)               eigenen Boot)
 │      │        ▲ Befehle (CMD_*)        │ ◄─────────────────────────────────────────┘
 │      │        └─────────────────────── │
 │      ├─ GatewayClient ─► Room-Queue ───┼─ HTTPS ─┐
 │      └─ eigener Positions-Upload ──────┼─ HTTPS ─┤
 └────────────────────────────────────────┘         ▼
        Boots-Uhr (Ultra) ── LoRa 31 Byte ──►   Tracker-Server ──► Live-Karte (Browser / Zuschauer-Rolle)
        Land-Uhr (S3) ◄─ BLE ─ Handy (Land-Rolle, Karte)
```

### 2.1 Eigenes Boot (Hirn)
- Quelle der Wahrheit für Position, Kurs, Speed, Wind, Wegpunkte und Rennstatus ist das Handy (`FusedLocationProviderClient`,
  1-Hz-Tick im `SegeluhrViewModel`).
- Boots-Uhr bekommt die Daten per BLE-Notify (Characteristics `6f6e0002…000a`: GPS, Akku, Haptik, Home, Wind, Rennstatus,
  Zeit, Wegpunkte) und schickt Bedienbefehle per Write auf `6f6e0004…` zurück (`CMD_COUNTDOWN_START`, `CMD_SET_WAYPOINT`,
  `CMD_CONFIRM/REJECT_BUOY_ROUNDING` …). Die Uhr rechnet nichts selbst, sie zeigt an und löst aus.
- Die **Ultra** funkt zusätzlich denselben Status per LoRa an die Land-Uhr. Das Paket (`shared/LoRaPacket.h`, 31 Byte, AES-
  verschlüsselt) enthält u. a. Position, `gpsValidFix`, SOG, Wind, Akku, Boot-Zustand, Zeit.
- Die **Galaxy Watch** hat kein LoRa und ersetzt nur den BLE-Teil der Ultra.
- Eigene Position geht per `POST /api/trackers/<id>/position` (Bearer-Token) alle 5 s an den Server, mit Offline-Puffer
  (200 Positionen im RAM) und GPS-Zeit als `ts`. Siehe `Erweiterung_Regatta_Tracker.md`.

### 2.2 Relais für fremde Tracker (Gateway)
- Fremde LoRa-Tracker → **RegattaMaster** (LoRa-Empfänger auf dem eigenen Boot) → BLE-Notify (24 Byte, Magic `0xA7`) →
  `GatewayClient` im Handy → persistente Room-Queue (`gateway_positions`) → alle 4 s Batch (max. 500) per
  `POST /api/gateway/positions` mit eigenem Header `x-gateway-token` (unabhängig vom Bearer-Token der eigenen Position).
- Läuft **unabhängig** von App-Stopp und Boots-Rolle (nur Ein/Aus-Schalter im Setup-Tab). Reconnect automatisch, da der
  Master selbst nichts puffert; das Handy fordert per `requestMtu()` eine größere MTU an (sonst 23-Byte-Abschnitt).
- Das Handy macht dabei nur Relais: keine Auswertung der fremden Positionen, ausser der Statusübersicht im Setup-Tab.

### 2.3 Land-Seite
- Land-Uhr (S3) empfängt das LoRa-Paket und gibt Position + `gpsValidFix` per BLE (`7a6e0003…`) an das Handy in der Land-Rolle
  (`LandUhrClient`), das die Boot-Position auf einer OSM-Karte zeigt. Der Zeit-Sync der S3 kommt ebenfalls aus dem ersten LoRa-Paket.
- Zuschauer sehen alles über die Server-Karte (`AppRole.SPECTATOR`), ohne BLE/GPS.

## 3. Gültigkeit von GPS-Daten (Ampel-Regeln)

Zwei getrennte Strecken, beide dreistufig. **Verbindung** ("es kommen Pakete") und **GPS-Fix** ("die Position stimmt")
sind getrennte Dinge und dürfen nie in einer Farbe verschmelzen:

| Strecke | Grau/Rot | Amber | Grün |
|---|---|---|---|
| Land-Uhr S3 (Boot-Paket) | kein Paket seit 90 s (`LORA_SIGNAL_LOST_THRESHOLD_MS`) | Paket kommt, `gpsValidFix == 0` | Paket + `gpsValidFix == 1` |
| Handy Land-Rolle (`LandUhrScreen`) | keine BLE-Verbindung | Verbunden, aber `gpsValidFix` false oder Paket älter als 90 s | Verbunden + Fix + frisch |
| Gateway-Übersicht im Setup-Tab | kein Paket seit 10 s | Paket kommt, aber **kein GPS-Fix** | Paket + GPS-Fix |

**Gateway-Fix-Ableitung (12.11.2026):** Das 24-Byte-Master-Paket hat kein Fix-Flag. `GatewayPacket.hasGpsFix` gilt als
wahr, wenn die Position nicht (0, 0) ist **und** HDOP bekannt ist (Wire-Wert 255 = unbekannt). Das ist eine **Annahme**
und muss gegen `protocol.h` im Tracker-Repo geprüft werden; sendet ein Tracker ohne Fix die letzte bekannte Position
weiter, bleibt das unerkannt.

## 4. Regeln, die man leicht verwechselt

- **Master ist nicht Mastuhr.** *RegattaMaster* = LoRa-Empfänger/BLE-Relais auf dem eigenen Boot. *Mastuhr* = geplantes
  Eigenbau-Anzeigegerät am Mast. Beide Namen kommen im Projekt vor.
- **Zwei Funkprotokolle, nicht austauschbar:** Boots-Uhr → Land-Uhr (`LoRaPacket.h`, 31 Byte, AES) und Tracker → Master
  (`protocol.h` im Tracker-Repo, 21 Byte over LoRa, Magic `0xA7`; als 24-Byte-BLE-Paket weitergereicht).
- **Zwei BLE-Richtungen am Handy:** zu den Boots-Uhren ist das Handy **Server** (UUID `6f6e0001…`), zur Land-Uhr und zum
  RegattaMaster ist es **Client/Central** (`7a6e0001…` bzw. `a7d0c001…`).
- **Zwei Secrets gegenüber dem Server:** Bearer-Token (eigener Tracker) und Gateway-Token (Relais). Beide nie ins Git.
- **Akku des Tracker-Pakets** ist 0..100 (Wire), der Server erwartet 0..1; umgerechnet wird erst beim Upload.
- **Tracker-Vertrag:** Quelle der Wahrheit ist `docs/GATEWAY_PROTOCOL.md` im Tracker-Repo; die App erwartet API-Major 1.

## 5. Bekannte Lücken / zu prüfen

- GPS-Fix-Erkennung der Tracker (Abschnitt 3) gegen die Tracker-Firmware verifizieren.
- Das Gateway lädt aktuell auch Pakete **ohne** GPS-Fix hoch (z. B. Position 0,0 würde auf der Server-Karte erscheinen).
  Entscheidung offen: vor dem Upload verwerfen oder mit Fix-Flag hochladen.
- `GatewayUploader` ist noch nicht live gegen den Server verifiziert (Server war beim Test offline).
- "Aktiv"-Schwelle (10 s) und Batch-Intervall (4 s) sind Schätzwerte, nicht mit mehreren Booten getestet.
- Galaxy-Watch-Tastenbedienung nach Branch-Merge nicht kompiliert/getestet (siehe `Erweiterung_GalaxyWatch_App.md`).
- Gesamtstand seit dem Zusammenführen ist **nicht kompiliert** (kein Android-SDK in der Cloud-Umgebung); zuerst
  `assembleDebug` für Handy-App und Galaxy-Watch-App in Android Studio, dann Hardwaretest.
- Alte Dokumente nennen noch die frühere Rolle "S3 = BLE-Central-Boots-Uhr"; gültig ist: Ultra = Boots-Uhr, S3 = Land-Uhr.
