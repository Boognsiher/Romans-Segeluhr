# Segeluhr_TWatch_Ultra — geplant, noch nicht gebaut

Diese Firmware existiert noch nicht. Geplanter Funktionsumfang:

- Solo-GPS-Modus (eigenes GPS-Modul, kein Handy nötig) — Grundlage dafür
  ist der Prototyp `Segeluhr_Basis_Solo` unter `../../TESTING/`, der aber
  für andere Display-/Power-Annahmen geschrieben wurde und noch nicht auf
  echter Ultra-Hardware getestet ist.
- BLE-Central-Rolle wie bei `Segeluhr_TWatch_S3` (dieselbe Logik sollte
  sich weitgehend übertragen lassen, siehe deren
  `docs/Erweiterung_TWatch_S3_Firmware.md`).
- LoRa-Sender für den Heimweg-Status an Land, siehe
  `Segeluhr-Android/Segeluhr/docs/BLE_Protokoll_Ergaenzung_Heimweg_LoRa.md`
  (Paket-Design + Sende-Regeln bereits dokumentiert, Implementierung offen).

**Nächster Schritt, sobald die Hardware da ist:** `Segeluhr_TWatch_S3.ino`
als Ausgangsbasis nehmen, Solo-GPS-Zweig ergänzen, LoRa-Sender-Logik aus
der verlinkten Doku einbauen.
