# Erweiterung: Echte Firmware für die LilyGO T-Watch S3

**Nicht Teil der ursprünglichen Spezifikation.** Die T-Watch S3 dient als
günstigere Zwischenstufe/Machbarkeitsnachweis vor der eigentlichen
Ziel-Hardware LilyGO T-Watch Ultra. Anders als der bisherige
`segeluhr_ble_tester_v2.ino` (simuliert nur die Uhr für Protokoll-Tests,
andere Hardware) ist dies die **echte Uhr-Firmware**: reale Screens mit
echten Live-Daten statt Demo-Werten.

Datei: `Segeluhr-Firmware/Segeluhr_TWatch_S3/Segeluhr_TWatch_S3.ino`

## Warum andere Hardware-Basis als der Tester

Die normale T-Watch S3 (nicht "Plus") hat **kein eingebautes GPS** — nur
die "S3 Plus"-Variante besitzt das MIA-M10Q-Modul. Ein Solo-Modus wie in
`Segeluhr_Basis.ino` (für die T-Watch Ultra gedacht) ist auf der S3 also
gar nicht möglich. Diese Firmware läuft deshalb ausschliesslich im Modus
"Mit Uhr": die Uhr bezieht alle Navigationsdaten per BLE vom Handy.

## Bibliotheken

- **LilyGoLib** (Xinyuan-LilyGO) — Hardware-Abstraktion für Display,
  Touch, DRV2605-Haptik, AXP2101-Power. Wird von LilyGO offiziell für
  T-Watch Ultra **und** T-Watch S3/S3 Plus gemeinsam gepflegt — Code sollte
  sich später relativ direkt auf die Ultra übertragen lassen.
- **NimBLE-Arduino** (h2zero) — BLE-Central-Rolle (Scan/Connect/Subscribe),
  wie schon beim ESP32-Tester.
- Arduino-IDE-Boardeinstellung: LilyGo T-Watch-S3 (siehe LilyGoLib-README
  für Board-Paket-Installation).

## Bedienung: ausschliesslich Touch

Sechs Tabs (Wischen oder Tab-Leiste unten): **Nav** (GPS-Kompass, COG/SOG),
**Wind** (Richtung + Trend), **Heim** (Heimweg-Status/ETA), **CD**
(Countdown-Ring), **Man** (Wende/Halse-Kommando + Competition-Etappe),
**Menu** (alle Steuerbefehle als Touch-Buttons: Countdown, Wind-Kalibrierung,
Trainingsmodus, Wegpunkte an aktueller Position setzen/löschen, Heimweg
an/aus, Wettfahrt beenden, Log löschen).

## Bekannte Einschränkungen / noch zu testende Punkte

- **DRV2605-Effekt-IDs für die 8 Haptik-Muster sind Annäherungen** an die
  Timings aus `VibrationPatterns.kt` (siehe Tabelle im Code-Kommentar) —
  auf dem echten Gerät ausprobieren, ob z.B. `LAKE_WARN5` und
  `MANEUVER_CMD` sich unterscheidbar anfühlen; ggf. Effekt-IDs anpassen.
- Kein Compile-Test möglich (keine ESP32-Toolchain in dieser Umgebung) —
  API-Aufrufe wurden gegen die aktuellen LilyGoLib-Beispiel-Sketches
  geprüft (`helloworld.ino`, `Vibrate_Basic.ino`, `PowerManageMonitor.ino`).
  Gradle-/Compile-Fehler bitte zurückschicken.
- Reconnect-Logik ist einfach gehalten (alle 3s ein neuer Scan-Versuch,
  solange nicht verbunden) — kein Verhalten für "mehrere Handys in
  Reichweite" o.ä., wie beim bisherigen Tester auch nicht nötig.
- Batteriestand der Uhr selbst (`instance.pmu.getBatteryPercent()`) wird
  aktuell nicht angezeigt, nur der des Handys (Battery-Characteristic) —
  bei Bedarf leicht ergänzbar.
