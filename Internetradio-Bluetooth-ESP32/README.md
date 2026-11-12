# Internetradio per Bluetooth mit 2 ESP32

Zwei ESP32-Boards: eins holt einen Internetradio-Stream per WLAN und sendet
ihn per klassischem Bluetooth (A2DP) an das zweite, das den Ton an einen
externen I2S-DAC ausgibt.

```
[Internet]        WLAN         Bluetooth (A2DP)         I2S
Radio-Stream ----> ESP32 #1 -----------------------> ESP32 #2 ----> PCM5102-DAC
(MP3)               (Sender)                          (Empfaenger)   -> Verstaerker/
                                                                         Lautsprecher
```

## Hardware

- **2x klassischer ESP32** (z.B. "ESP32 DevKitC", WROOM-32-Module).
  **Wichtig:** Es muss ein Board mit **Bluetooth Classic** sein — die
  neueren ESP32-S3/C3/C6 haben nur BLE, damit funktioniert A2DP-Audio
  **nicht**.
- **1x PCM5102-Breakout** (I2S-DAC) am Empfaenger-Board, siehe Verkabelung
  im Kopf-Kommentar von `Empfaenger_BluetoothZuDAC.ino`. Alternativ geht
  auch ein MAX98357A (I2S-Verstaerkermodul direkt an einen Lautsprecher),
  dann `data_out_num` entsprechend anpassen, Rest der I2S-Pins gleich.

## Bibliotheken (Arduino-Bibliotheksverwalter)

Beide von **Phil Schatzmann**:

- `ESP32-A2DP` — Bluetooth-A2DP-Source/-Sink fuer ESP32
- `arduino-audio-tools` — nur fuer den **Sender** noetig (WLAN-Stream +
  MP3-Decoder + A2DP-Ausgabe zusammengesteckt)

Boardeinstellung in der Arduino IDE fuer beide Sketches: **"ESP32 Dev
Module"**.

## Einrichtung

1. `Empfaenger_BluetoothZuDAC.ino` auf ESP32 #2 flashen, DAC verkabeln.
2. In `Sender_RadioZuBluetooth.ino` anpassen:
   - `WIFI_SSID` / `WIFI_PASSWORD`
   - `RADIO_STREAM_URL` — die MP3-Stream-URL des gewuenschten Senders
     (Beispiel SRF 3 ist eingetragen, aber Sender aendern ihre Stream-URLs
     gelegentlich — im Zweifel selbst pruefen, z.B. auf der Sender-Website
     nach "Stream-URL"/"MP3" suchen)
   - `BLUETOOTH_EMPFAENGER_NAME` — muss exakt dem Namen entsprechen, den
     `BLUETOOTH_NAME` im Empfaenger-Sketch setzt (Standard bei beiden:
     `ESP32-Radio-Empfaenger`)
3. Sender-Sketch auf ESP32 #1 flashen.
4. Erst den Empfaenger einschalten (wartet auf Verbindung), dann den
   Sender — verbindet sich automatisch anhand des Bluetooth-Namens, kein
   manuelles Pairing noetig.

## Troubleshooting

- **Kein Ton, aber verbunden:** `XMT`/`XSMT`-Pin am PCM5102 pruefen — muss
  auf 3.3V liegen, sonst bleibt der DAC stummgeschaltet.
- **Verbindet nicht:** Namen in beiden Sketches (`BLUETOOTH_EMPFAENGER_NAME`
  vs. `BLUETOOTH_NAME`) auf exakte Uebereinstimmung pruefen (Gross-/
  Kleinschreibung).
- **Stream startet nicht / 404:** Stream-URL ist wahrscheinlich veraltet,
  neue URL beim Sender-Webseite suchen.
- **Aussetzer/Knacken:** WLAN-Signalstaerke am Sender-Board pruefen (RSSI
  per `WiFi.RSSI()` in `loop()` ausgeben), bei schwachem Empfang naeher an
  den Router.
- **Kompiliert nicht:** Bibliotheksversionen pruefen — `arduino-audio-tools`
  entwickelt sich schnell weiter, bei API-Fehlern ggf. auf eine etwas
  aeltere Version zurueckwechseln oder die Beispiele im jeweiligen
  GitHub-Repo (pschatzmann/ESP32-A2DP, pschatzmann/arduino-audio-tools)
  mit dieser Version abgleichen.

## Status

📋 Neu aufgesetzt, noch nicht auf Hardware getestet.
