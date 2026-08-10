# Erweiterung: Heimweg-Status per LoRa an Land

Ergänzt `docs/Erweiterung_Heimweg.md` und `docs/BLE_Protokoll_Ergaenzung_Haptik.md`
um die letzte Strecke: Personen an Land sollen automatisch erfahren, dass
der Segler auf dem Heimweg ist, wie lange es ungefähr noch dauert, und wo
er sich gerade befindet.

## Warum das Handy nicht direkt sendet

Smartphones haben **keinen eingebauten LoRa-Funk** (LoRa läuft auf 868 MHz
in der Schweiz/EU, ein komplett anderes Funkmodul als WLAN/Bluetooth/
Mobilfunk). Die Kette ist deshalb dreistufig:

```
Handy --BLE--> LilyGO T-Watch Ultra (SX1262 LoRa-Chip) --LoRa RF--> LilyGO Watch S (SX1262)
 (rechnet alles,      (bereits über BLE_Protokoll.md          (an Land, zeigt Status an)
  Heimweg-Logik)        verbunden — sendet nur weiter,
                        was sie sowieso schon empfängt)
```

**Wichtige Design-Vorgabe (vom Nutzer bestätigt):** Die Ultra-Watch sendet
NUR dann eine LoRa-Nachricht, wenn sie gerade tatsächlich per BLE mit dem
Handy verbunden ist UND der Heimweg-Modus dort aktiv ist. Beides ergibt
sich automatisch aus dem Protokoll unten — es braucht keine zusätzliche
Logik dafür, weil die Ultra-Watch ohne aktive BLE-Verbindung schlicht keine
aktuellen Daten zum Senden hat.

## 1. Handy -> Ultra-Watch (BLE, bereits implementiert)

Neue Characteristic, gehört zum bestehenden Service `6f6e0001-...`:

```
Characteristic Home-Status: 6f6e0006-b5a3-f393-e0a9-e50e24dcca9e
  Properties:  NOTIFY (Handy -> Uhr)
  Payload:     3 Byte, little-endian
    uint8  flags        // bit0: Heimweg-Modus aktiv, bit1: Wende empfohlen
    uint16 etaMinutes   // 0xFFFF = keine ETA verfügbar
```

Die aktuelle Position wird NICHT dupliziert — die kommt bereits jede
Sekunde über die bestehende GPS-Characteristic `6f6e0002-...` (siehe
`BLE_Protokoll.md`). Die Ultra-Watch merkt sich einfach den letzten
empfangenen GPS-Fix und kombiniert ihn beim Senden mit diesem Status.

Implementiert in `BleProtocol.kt` (`encodeHomeStatus`) und
`BleGattServerManager.kt` (`notifyHomeStatus`), aufgerufen bei jedem Tick
in `SegeluhrViewModel`.

## 2. Ultra-Watch -> Watch S (LoRa)

### Paketformat (19 Byte, little-endian)

```c
typedef struct __attribute__((packed)) {
    int32_t  lat_e7;              // wie im BLE-GpsPacket, Breitengrad × 1e7
    int32_t  lon_e7;               // Längengrad × 1e7
    uint16_t etaMinutes;           // 0xFFFF = unbekannt
    uint8_t  flags;                 // bit0: Wende empfohlen (aktuell nur informativ)
    uint16_t sogCkn;                 // Geschwindigkeit über Grund, Zenti-Knoten (wie im GPS-BLE-Paket)
    uint16_t distanceRemainingM;      // verbleibende Distanz bis Heimatpunkt, Meter (0xFFFF = unbekannt)
    uint32_t distanceTraveledM;        // zurückgelegte Gesamtstrecke der aktuellen Session, Meter
} HomeLoRaPacket; // = 19 Bytes
```

Erweitert gegenüber der ursprünglichen 11-Byte-Version um Speed + beide
Distanzen (Nutzer-Wunsch). 19 Byte sind für LoRa immer noch sehr kompakt —
der Airtime-Unterschied zu den ursprünglichen 11 Byte ist bei den
üblichen SF-Einstellungen für diesen Anwendungsfall vernachlässigbar.

**Update 10.08.2026:** Die App-seitige Aufsummierung ist jetzt implementiert
— neue Klasse `core/DistanceTracker.kt`, läuft in `SegeluhrViewModel.tick()`
mit (Summe der Distanzen zwischen aufeinanderfolgenden GPS-Fixes oberhalb
`Constants.MIN_SPEED_KN`, damit GPS-Jitter im Stand nicht mitzählt), Anzeige
im Normal-Tab ("Zurückgelegte Strecke" in der Heimweg-Karte), Reset über
"Alles zurücksetzen" im Setup-Tab. **Noch nicht kompiliert/getestet**
(kein `gradlew` im Projekt, siehe PROJEKT_STATUS.md — vor Nutzung in
Android Studio bauen).

**Update 10.08.2026: LoRa-Weitergabe implementiert.** Das inzwischen
tatsächlich implementierte Funkpaket ist NICHT mehr das `HomeLoRaPacket`
aus diesem Dokument, sondern `LoRaStatusPacket` in
`Segeluhr-Firmware/shared/LoRaPacket.h` (jetzt 31 Byte, u.a. bereits mit
`distanceRemainingM` UND `distanceTraveledM`) — dieses Dokument beschreibt
insofern nur noch das ursprüngliche Konzept, nicht den exakten aktuellen
Feldnamen/-aufbau. Umsetzung:
- **Handy -> Ultra (BLE):** Home-Status-Characteristic (`6f6e0006-...`) von
  3 auf 7 Byte erweitert (`BleProtocol.encodeHomeStatus`) — neues `uint32
  distanceTraveledM` am Ende, IMMER mitgeschickt (nicht nur wenn Heimweg
  aktiv ist, die Session-Distanz läuft ja unabhängig davon mit).
  `SegeluhrViewModel.tick()` übergibt `distanceTracker.totalM`.
- **Ultra -> Watch S (LoRa):** neues Feld `distanceTraveledM` in
  `LoRaStatusPacket` (`LoRaPacket.h`, 27 -> 31 Byte), befüllt in
  `buildAndSendStatusPacket()` aus `homeData.distanceTraveledM`
  (`onHomeStatusNotify()` liest es aus dem erweiterten BLE-Paket, neue
  Mindestlänge 7 Byte statt 3).
- **Watch S (Land):** `detailScreenUpdate()` zeigt es zusätzlich in der
  Paket-Info-Zeile ("Paket #N, Xs alt, bisher Y km") — kein neues Label,
  spart Platz auf dem kleinen Display.

**Wichtig: beide Firmwares (Ultra + S3/Land) UND die Android-App müssen
zusammen neu gebaut/geflasht werden**, sonst laufen Sender/Empfänger
auseinander (kürzeres altes 3-Byte-BLE-Paket würde vom neuen Ultra-Code
verworfen, `len < 7`-Check). **Noch nicht kompiliert/getestet** — Test
heute Abend geplant, siehe `docs/Test_Checkliste_10_08.md`.

### Sende-Regeln auf der Ultra-Watch

1. **Nur senden, wenn `flags.bit0` (Heimweg aktiv) im zuletzt empfangenen
   BLE-Home-Status-Paket gesetzt ist.** Wird der Modus am Handy beendet,
   hört die Ultra-Watch auf zu senden.
2. **Sofort senden bei Zustandswechsel** (Heimweg-Modus wird aktiviert,
   oder eine Wende wird neu empfohlen) — das ist die Nachricht, auf die
   Leute an Land eigentlich warten.
3. **Danach nur noch periodisch**, empfohlen alle 60–120 s, solange der
   Modus aktiv bleibt. Das hat zwei Gründe:
   - **Regulatorisch**: Das 868-MHz-ISM-Band unterliegt in der
     Schweiz/EU einer Duty-Cycle-Begrenzung (je nach Subband z.B. 1 %
     — genaue Werte für den gewählten Kanal in der aktuellen
     ETSI-EN-300-220-Norm bzw. BAKOM-Vorgaben prüfen, das hier ist keine
     Rechtsberatung).
   - **Akku** der Ultra-Watch — Dauerfeuer jede Sekunde wäre unnötig,
     eine ETA ändert sich ohnehin nicht sekündlich relevant.
4. Bei Verlust der BLE-Verbindung zum Handy: letzten bekannten Status
   noch **einmal** senden (informativ "Verbindung zum Handy verloren"),
   dann aufhören, bis wieder Daten reinkommen.

### Kern-Logik zum Einbauen (kein vollständiger Sketch)

Die Ultra-Watch braucht ohnehin schon die BLE-Central-Logik aus
`segeluhr_ble_tester.ino` (Scan/Connect/Notify-Abo für GPS + Home-Status,
1:1 übertragbar). Ergänzend dazu, nach Empfang eines Home-Status-Notifies:

```cpp
// Wird im BLE-Notify-Callback für CHAR_HOME_STATUS_UUID aufgerufen
void onHomeStatusReceived(bool active, bool maneuverNeeded, uint16_t etaMinutes) {
  bool justActivated = active && !wasActive;
  bool maneuverJustChanged = maneuverNeeded != lastManeuverNeeded;
  uint32_t now = millis();

  bool shouldSendNow = active && (justActivated || maneuverJustChanged ||
                                   (now - lastLoRaSendMs >= LORA_MIN_INTERVAL_MS));

  if (shouldSendNow) {
    HomeLoRaPacket pkt;
    pkt.lat_e7 = lastGps.lat_e7;   // aus dem zuletzt empfangenen GPS-Paket
    pkt.lon_e7 = lastGps.lon_e7;
    pkt.etaMinutes = etaMinutes;
    pkt.flags = maneuverNeeded ? 0x01 : 0x00;
    pkt.sogCkn = lastGps.sogCkn;              // direkt aus dem GPS-BLE-Paket übernommen
    pkt.distanceRemainingM = distanceRemainingM; // aus HomeEngine-Peilung
    pkt.distanceTraveledM = distanceTraveledM;   // TODO: neue Aufsummierung, siehe oben
    radio.transmit((uint8_t*)&pkt, sizeof(pkt)); // RadioLib-Standardaufruf
    lastLoRaSendMs = now;
  }
  wasActive = active;
  lastManeuverNeeded = maneuverNeeded;
}
```

`radio.transmit(...)` ist Standard-RadioLib-API (bereits von LilyGO in
allen T-Watch/LoRa-Boards verwendet) — die Initialisierung (`radio.begin()`,
Pin-Zuordnung für CS/IRQ/RST/BUSY) übernimmt bei euch am saubersten die
**offizielle LilyGoLib**, nicht eigene Pin-Definitionen:

```
Arduino IDE -> File -> Examples -> LilyGoLib -> radio -> SX1262 -> ...
(Board: "LilyGo T-Watch-Ultra", Board Revision: "Radio-SX1262")
```

Dieses Beispiel als Ausgangsbasis nehmen und obige `onHomeStatusReceived`-
Logik (plus die BLE-Central-Logik aus dem Tester-Sketch) dort einbauen,
statt die Radio-Initialisierung von Grund auf neu zu schreiben — LilyGoLib
kennt die korrekten Pins für euer Board bereits, ich wollte die hier nicht
raten und dadurch falsche Angaben als geprüft ausgeben.

### Watch S (Empfänger, an Land)

Analog, aber einfacher — kein BLE nötig, nur LoRa-Empfang:

```
Arduino IDE -> File -> Examples -> LilyGoLib -> radio -> SX1262 -> ...
(Board: "LilyGo T-Watch-S3", Board Revision: "Radio-SX1262")
```

```cpp
void onLoRaPacketReceived(uint8_t* data, size_t len) {
  if (len < sizeof(HomeLoRaPacket)) return;
  HomeLoRaPacket pkt;
  memcpy(&pkt, data, sizeof(pkt));

  double lat = pkt.lat_e7 / 1e7;
  double lon = pkt.lon_e7 / 1e7;
  bool maneuverNeeded = pkt.flags & 0x01;
  double sogKn = pkt.sogCkn / 100.0;
  double distRemainingKm = (pkt.distanceRemainingM == 0xFFFF) ? -1 : pkt.distanceRemainingM / 1000.0;
  double distTraveledKm = pkt.distanceTraveledM / 1000.0;

  // TODO: hier LilyGoLib-Display/LVGL ansteuern statt nur Serial-Ausgabe:
  // z.B. "Segler auf dem Heimweg — ETA 42 min, 3.2 kn, noch 1.8 km, bisher 14.6 km"
  Serial.printf("Heimweg-Update: ETA %u min, %.1f kn, Position %.5f, %.5f, noch %.1f km, bisher %.1f km%s\n",
                pkt.etaMinutes, sogKn, lat, lon, distRemainingKm, distTraveledKm,
                maneuverNeeded ? " (Wende gerade empfohlen)" : "");
}
```

## Offene Punkte

- Exakte Duty-Cycle-/Kanalwahl für die Schweiz vor dem ersten Praxiseinsatz
  gegenprüfen (BAKOM-Vorgaben, bzw. die Kanaltabelle, die die gewählte
  RadioLib-Konfiguration nutzt).
- Bestätigungslogik ("Watch S hat die Nachricht wirklich empfangen") ist
  hier nicht vorgesehen — LoRa hier ist reines Fire-and-Forget, kein
  Zwei-Wege-Handshake. Falls Zustellsicherheit wichtig ist, müsste ein
  einfaches ACK-Schema ergänzt werden (Watch S sendet auf Empfang einen
  kurzen Bestätigungscode zurück) — bisher nicht gefordert.
- Die exakten LilyGoLib-Funktionssignaturen (`radio.begin()` Parameter,
  Callback-Registrierung) bitte gegen das aktuelle offizielle SX1262-
  Beispiel prüfen, da sich die Bibliothek weiterentwickelt.
