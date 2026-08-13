# Erweiterung: Akku-Verbrauchs-Log (Ultra)

> Nicht in der ursprünglichen Spezifikation. Ausgelöst durch die Standby-
> Änderung vom 13.08.2026 (siehe `Erweiterung_Standby_Wecken.md`): der
> Bildschirm bleibt im Segeln-Modus jetzt dauerhaft an (Roman braucht die
> Nav-/Wind-/Heimweg-Werte auf einen Blick, ohne extra Taster/Geste) —
> Roman-Nachfrage danach: "wo stehen wir da beim Stromverbrauch, können wir
> das tracken?"

## Status: 🔧 UMGESETZT, KOMPILIERT (13.08.2026) — noch nicht auf Hardware
getestet.

## 1. Ziel
Reale Daten statt Vermutungen darüber, wie stark der dauerhaft eingeschaltete
Bildschirm (+ BLE, LoRa, Sensoren) die Akkulaufzeit der Ultra beim Segeln
beeinflusst — insbesondere im Vergleich zum vorherigen 30s-Standby-Verhalten.

## 2. Warum kein direkter Strom-Sensor
Der AXP2101-PMU-Chip (via `XPowersLib`) exponiert über `instance.pmu` keinen
Momentanverbrauch (mA) — nur `getBatteryPercent()`, Systemspannung
(`getSystemVoltage()`) und Ladestatus (`isCharging()`). Ein Coulomb-Counter
existiert im AXP2101 laut Datenblatt, wird von `XPowersLib` aber nicht
exponiert. Tracking läuft deshalb über den **Akku-Prozent-Verlauf über die
Zeit** (Drain-Rate aus mehreren Messpunkten berechenbar), nicht über
Momentanverbrauch.

## 3. Technische Umsetzung (implementiert, `Segeluhr_TWatch_Ultra.ino`)

**Speicherort:** internes FAT-Flash (`FFat`, ESP32-Standardbibliothek) auf
der 9.9MB-Partition, die das `PartitionScheme=app3M_fat9M_16MB` ohnehin
bereitstellt — bisher von nichts sonst genutzt. Vorteil gegenüber reinem
Serial-Log: überlebt einen ganzen Segeltag auch OHNE angeschlossenen Laptop
(auf einem Einhand-Trapez-Skiff ohnehin unrealistisch, siehe
`Erweiterung_Gesten_Training_Klio.md` Abschnitt 5a).

**Intervall:** 1x/Minute (`BATTERY_LOG_INTERVAL_MS`) — reicht für eine
Drain-Rate-Kurve über Stunden, kein Risiko für Log-Flut oder nennenswerten
Flash-Verschleiss (~65KB/Tag bei minütlichen ca. 45-Byte-Zeilen, Partition
hat 9.9MB Platz).

**Datei:** `/battery_log.csv`, CSV-Header `datum,zeit,modus,display,ble,laden,akku_pct`.
Beispielzeile: `13.08.2026,21:04:17,SEGELN,AN,VERBUNDEN,NEIN,87`

- `datum`/`zeit`: aus `instance.rtc.getDateTime()` (dieselbe RTC, die auch
  die Statusleiste speist — nur sinnvoll, sobald per BLE zeitsynchronisiert,
  siehe `Erweiterung_TWatch_S3_ZeitSync.md`)
- `modus`: `SEGELN`/`ALLTAG` (`appMode`)
- `display`: `AN`/`AUS` (`displayAsleep`) — im Segeln-Modus seit der
  Standby-Änderung praktisch immer `AN`, trotzdem mitgeloggt für den Fall
  künftiger Änderungen und für den Alltags-Modus-Vergleich
- `ble`: `VERBUNDEN`/`GETRENNT` (`bleConnected`) — BLE-Verbindung kostet
  vermutlich ebenfalls messbar Strom, gehört als Variable mit rein
- `laden`: `JA`/`NEIN` (`instance.pmu.isCharging()`) — wichtig, um
  Zeiträume mit angeschlossenem USB-Kabel (Akku-% steigt statt fällt) aus
  der Drain-Rate-Berechnung auszuschliessen
- `akku_pct`: `instance.pmu.getBatteryPercent()`

**Auslesen:** drei neue Serial-Kommandos (Desktop-Fallback wie beim
Klio-Training, 115200 Baud):
- `BATLOG DUMP` — kompletten CSV-Inhalt über Serial ausgeben (zum
  Kopieren/Speichern, z.B. für eine Tabellenkalkulation)
- `BATLOG STATUS` — Dateigrösse + belegter/gesamter Flash-Speicher
- `BATLOG CLEAR` — Log löschen, neue Datei mit Header anlegen (z.B. vor
  einem gezielten Vergleichstest)

**Serial-Kommando-Architektur:** bisher gab es genau einen Serial-Zeilen-
Leser im Sketch (`gestureTrainingSerialTick()`, nur für `TRAIN ...`). Ein
zweiter, unabhängiger `Serial.available()`-Leser für `BATLOG ...` hätte sich
mit dem ersten um ankommende Zeilen gestritten (wer im `loop()` zuerst
drankommt, verschluckt sie für den anderen). Deshalb umbenannt in
`serialCommandTick()` und beide Kommando-Familien im selben Leser
dispatcht — `handleBatteryLogCommand()` ist bewusst ausserhalb des
`USING_BHI260_SENSOR`-Ifdefs definiert und wird von BEIDEN Varianten
(echte Gestenerkennung + Stub ohne BHI260) aufgerufen, damit das Akku-Log
nicht von der Gestensensor-Verfügbarkeit abhängt.

## 4. Offene Punkte
- [ ] **Nicht auf Hardware getestet** — insbesondere ob `FFat.begin(true)`
  auf dieser Partition sauber (neu) formatiert, falls sie vorher nie
  benutzt wurde.
- [ ] Keine Rotation/Grössenbegrenzung eingebaut — bei minütlichem Logging
  über Wochen würde die Datei irgendwann spürbar wachsen (bei aktuell ca.
  65KB/Tag erst nach vielen Monaten relevant für die 9.9MB-Partition,
  trotzdem im Auge behalten via `BATLOG STATUS`, notfalls `BATLOG CLEAR`).
- [ ] Kein Vergleichswert vorhanden, wie viel Strom der alte 30s-Standby vs.
  das neue Dauerhaft-an tatsächlich einspart/kostet — dafür braucht es einen
  echten Test (z.B. zwei vergleichbare Sessions, einmal mit dem alten,
  einmal mit dem neuen Verhalten, `BATLOG DUMP` danach vergleichen).
