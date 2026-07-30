# TESTING — Tester/Prototypen, nicht für den echten Segelbetrieb

Alles hier simuliert oder testet nur Teile des Systems, läuft aber nicht
als fertige Uhr-Firmware auf dem Wasser. Zur Abgrenzung von `../ECHT/`.

## Fehlt hier noch (existiert lokal bei dir, noch nicht hochgeladen)

- **`segeluhr_ble_tester.ino`** (bzw. `segeluhr_ble_tester_v2.ino`) — lief
  auf einem XIAO ESP32-S3 + separates rundes QSPI-Display, simulierte die
  Central-Rolle einer Uhr, bevor `Segeluhr_TWatch_S3` existierte. Diente
  reinen Protokoll-Tests (GATT-Verbindung, Notify/Write-Handling).
  **Kandidat zum Archivieren** — die T-Watch S3 deckt diesen Testzweck
  inzwischen ab, aber der Tester bleibt nützlich, falls mal ohne die
  eigentliche Uhr-Hardware am BLE-Protokoll allein getestet werden soll.

- **`Segeluhr_Basis.ino`** (Solo-Modus-Prototyp) — für die T-Watch Ultra
  gedacht (eigenes GPS, LVGL, DRV2605), aber nie auf echter Hardware
  getestet. Falls noch relevant, würde er hier als
  `Segeluhr_Basis_Solo/Segeluhr_Basis_Solo.ino` einsortiert.

Lade diese Dateien hoch, wenn du sie mit archivieren möchtest — sonst
bleiben sie einfach lokal bei dir und dieser Ordner bleibt bis dahin leer
bzw. nur mit diesem Hinweis.
