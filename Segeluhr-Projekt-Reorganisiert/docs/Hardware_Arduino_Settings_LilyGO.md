# Hardware- & Arduino-Settings — T-Watch Ultra & T-Watch S3

Quelle: offizielles LilyGoLib-Repo (github.com/Xinyuan-LilyGO/LilyGoLib),
Stand der Recherche: 05.08.2026. Claude Code sollte bei Unsicherheiten
trotzdem gegen das Repo selbst prüfen, da LilyGO die Doku laufend anpasst.

## 1. Bibliotheken (für beide Uhren identisch)

1. Arduino IDE installieren
2. **Arduino ESP32 Core V3.3.0-alpha1 oder neuer** installieren
   - Boards-Manager-URL: `https://espressif.github.io/arduino-esp32/package_esp32_dev_index.json`
3. LilyGoLib herunterladen: `https://github.com/Xinyuan-LilyGO/LilyGoLib/archive/refs/heads/master.zip`
   → Arduino IDE → Sketch → Include Library → Add .ZIP Library
4. **LilyGoLib-ThirdParty** (github.com/Xinyuan-LilyGO/LilyGoLib-ThirdParty):
   alle Unterordner (nicht den Ordner selbst!) in den Arduino-`libraries`-Ordner
   kopieren (bei uns: `C:\Arduino\libraries`, außerhalb OneDrive — bekannte Regel)
5. **Wichtig:** Nie über den Library Manager aktualisieren, IDE fragt bei
   jedem Start nach Update — ignorieren, bis Hardware nachweislich stabil läuft
6. Beispiel-Sketch zum Gegentesten: `File → Examples → LilyGOLib → helloworld`

## 2. Arduino IDE Board-Settings — T-Watch S3

| Setting | Wert |
|---|---|
| Board | **LilyGo T-Watch-S3** |
| USB CDC On Boot | Enabled |
| CPU Frequency | 240MHz (WiFi) |
| Core Debug Level | None |
| USB DFU On Boot | Disable |
| Erase All Flash Before Sketch Upload | Disable |
| Events Run On | Core 1 |
| JTAG Adapter | Disable |
| Arduino Runs On | Core 1 |
| USB Firmware MSC On Boot | Disable |
| Partition Scheme | **16M Flash (3MB APP/9.9MB FATFS)** |
| Board Revision | **Radio-SX1262** |
| Upload Mode | **UART0/Hardware CDC** |
| Upload Speed | 921600 |
| USB Mode | **CDC and JTAG** |

### Download-Mode T-Watch S3 (falls Upload nicht klappt)
1. Rückseite öffnen, Batterie abziehen (Kabel nicht abreißen!)
2. Micro-USB einstecken
3. Windows-Geräte-Manager öffnen, Port beobachten
4. Crown lang gedrückt halten, bis Port aus der Liste verschwindet
5. **BOOT**-Taste gedrückt halten
6. Crown 1 Sekunde drücken → Port sollte wieder erscheinen
7. BOOT loslassen → Upload starten
8. Nach Upload: Crown lang drücken zum Ausschalten, neu starten

(Das deckt sich mit unserer bisherigen Erfahrung: automatischer USB-CDC-Reset
funktioniert bei uns nicht zuverlässig, BOOT+Crown-Sequenz ist Standard.)

## 3. Arduino IDE Board-Settings — T-Watch Ultra

| Setting | Wert |
|---|---|
| Board | **LilyGo T-Watch-Ultra** |
| USB CDC On Boot | Enabled |
| CPU Frequency | 240MHz (WiFi) |
| Core Debug Level | None |
| USB DFU On Boot | Disable |
| Erase All Flash Before Sketch Upload | Disable |
| Events Run On | Core 1 |
| JTAG Adapter | Disable |
| Arduino Runs On | Core 1 |
| USB Firmware MSC On Boot | Disable |
| Partition Scheme | **16M Flash (3MB APP/9.9MB FATFS)** |
| Board Revision | **Radio-SX1262** |
| Upload Mode | **UART0/Hardware CDC** |
| Upload Speed | 921600 |
| USB Mode | **CDC and JTAG** |

### Download-Mode T-Watch Ultra (einfacher als bei S3, KEINE Batterie-Demontage nötig)
1. Per USB-C anschließen
2. **BOOT**-Taste gedrückt halten, nicht loslassen
3. **RST**-Taste drücken
4. RST loslassen
5. BOOT loslassen
6. Upload starten
7. Nach Upload: RST drücken, um Download-Mode zu verlassen

**Achtung:** Das ist eine ANDERE Sequenz als bei der S3 (kein Batterie-Ausbau,
kein Crown nötig) — heute Abend beim ersten Ultra-Flash gleich richtig machen.

## 4. Wichtige Pin-Belegung T-Watch Ultra (für LoRa-Sender-Code relevant)

Quelle: `docs/hardware/lilygo-t-watch-ultra.md` im LilyGoLib-Repo.

| LoRa (SX1262) Signal | GPIO |
|---|---|
| RESET | 47 |
| BUSY | 48 |
| CS | 36 |
| Interrupt (DIO1) | 14 |
| SCK / MISO / MOSI | teilt sich den gemeinsamen SPI-Bus (SCK=35, MISO=33, MOSI=34) |

**Kritischer Gotcha:** Der LoRa-Chip hängt am Power-Rail **ALDO3** des
AXP2101-Power-Managers. Das heißt: Vor `radio.begin(...)` muss ALDO3 aktiv
geschaltet sein, sonst bekommt man scheinbar zufällige RadioLib-Fehler
(Timeout/kein Antwortsignal), obwohl die Verkabelung stimmt. LilyGoLib
übernimmt das normalerweise automatisch beim Board-Init — falls der
LoRa-Sender im Skeleton nicht anspringt, zuerst hier nachsehen.

Weitere relevante I2C-Adressen (gemeinsamer I2C-Bus):
| Gerät | Adresse |
|---|---|
| Touch CST9217 | 0x1A |
| GPIO-Expander XL9555 | 0x20 |
| Sensor BHI260AP | 0x28 |
| Power-Manager AXP2101 | 0x34 |
| RTC PCF85063A | 0x51 |
| Haptik DRV2605 | 0x5A |

GNSS (MIA-M10Q): TX=GPIO43, RX=GPIO44, PPS=GPIO13 — für spätere
Stand-alone-GPS-Nutzung relevant, für die heutige LoRa-Aufgabe nicht direkt.

## 5. Was ich NICHT verifizieren konnte

- Ob die LilyGoLib eine eigene Wrapper-Klasse für RadioLib/SX1262 mitbringt
  (wahrscheinlich ja, analog zum Power-Manager-Wrapper) oder ob roher
  RadioLib-Code nötig ist — Claude Code sollte das anhand der
  `File → Examples → LilyGOLib`-Beispiele (insbesondere factory-test-Code
  und ggf. ein LoRa-Beispiel) direkt am Repo prüfen, das ist zuverlässiger
  als meine Websuche.
- Exakte Pin-Belegung der T-Watch S3 (habe ich nicht gefunden/geprüft) —
  aber unkritisch, da die S3 bereits lauffähigen Code hat und "nur" die
  BLE-Rolle entfernt/durch LoRa-Empfang ersetzt wird, nicht die
  Grund-Hardware-Init.
