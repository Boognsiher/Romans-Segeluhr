# Segeluhr_WatchS_LoRaEmpfaenger — geplant, noch nicht gebaut

Reiner LoRa-Empfänger an Land, kein BLE nötig. Zeigt an, dass der Segler
auf dem Heimweg ist: ETA, Position, Geschwindigkeit, verbleibende und
zurückgelegte Distanz.

Paketformat (19 Byte) + Empfänger-Pseudocode bereits dokumentiert in
`Segeluhr-Android/Segeluhr/docs/BLE_Protokoll_Ergaenzung_Heimweg_LoRa.md`,
Abschnitt "Watch S (Empfänger, an Land)".

**Nächster Schritt, sobald die Hardware da ist:** LilyGoLib-Beispiel
`radio -> SX1262` (Board: LilyGo T-Watch-S3, Board Revision: Radio-SX1262)
als Ausgangsbasis, `onLoRaPacketReceived()` aus der Doku einbauen, dazu
eine einfache LVGL-Anzeige statt nur `Serial.printf(...)`.
