# Segeluhr_TWatch_S3 — "Land-Uhr", 🔧 kompiliert, Hardware-Verhalten noch nicht getestet

`Segeluhr_TWatch_S3.ino` ist die aktuelle, vollständige Firmware für
die S3 in ihrer **neuen** Rolle: reine Anzeige-/Kommunikations-Uhr für die
Crew an Land, kein BLE mehr, kein Handy nötig. Gegenstück ist die Boots-Uhr
`../Segeluhr_TWatch_Ultra/Segeluhr_TWatch_Ultra.ino` (T-Watch Ultra).

Volle Spezifikation: `../../../docs/Erweiterung_Land_Boot_LoRa_Kommunikation.md`

## Funktionsumfang

- 3 Touch-Tabs: **Status** (grosser Status-Text + Uhrzeit + Verbindungs-
  indikator, "KEIN SIGNAL" nach 90s ohne Paket), **Detail** (Distanz/SOG/
  Akku Boot/Wind/Paket-Alter), **Menü** (Stumm-Modus, Zeit stellen,
  Quick-Message-Fragen-Browser, Ausschalten)
- LoRa-Empfang des 30s-Status-Broadcasts der Boots-Uhr
- Quick-Messages (Ja/Nein-Fragen) in beide Richtungen, auf der Land-Uhr
  komplett per Touch (sichtbare JA/NEIN-Buttons bei eingehender Frage) —
  siehe Doku Abschnitt 5, die S3 hat anders als die Ultra keine
  Gesten-/Taster-Antwort

## Abweichungen vom ursprünglichen Skeleton (mit Begründung)

- **Bildschirmwechsel per `lv_tabview`** statt manueller
  `LandScreen`-Zustandsmaschine mit Rohtouch-Koordinaten — nutzt dasselbe
  bewährte Muster wie die Boots-Uhr-Firmware, erfüllt aber weiterhin
  "Wischen oder Tippen" (Tab-Leiste ist antippbar).
- **JA/NEIN- und Menü-Buttons als normale LVGL-Widgets** (`lv_button_create`
  + Click-Callback) statt manueller Touch-Koordinaten-Auswertung — LVGL +
  Touch-Treiber übernehmen das bereits über `beginLvglHelper(instance)`.
- **Physischer Taster-Fallback nicht verdrahtet**: die S3 hat laut
  bisherigem Projektstand keinen für App-Logik nutzbaren Taster (nur
  Touch). Für die Land-Uhr unkritisch, da Touch dort ohnehin der einzige
  vorgesehene Weg ist (trockene Hände an Land, anders als auf dem Boot).
- **RTC-Zeitstellung**: gelöst über ein einfaches +/− Menü (Stunde/Minute),
  bewusst **ohne** das `LoRaStatusPacket`-Format zu erweitern — eine
  Protokolländerung müsste beide Firmwares synchron halten und ist für die
  reine HH:MM-Anzeige nicht nötig. War laut Doku ein offener Punkt mit
  zwei Optionen, das ist die hier gewählte.

## Kompiliert 05.08.2026 (arduino-cli, esp32:esp32:twatchs3)

Fehlerfrei: 935.386 Bytes Flash (29%), 27.804 Bytes RAM (8%). Bibliotheken
liegen jetzt in `C:\Arduino\libraries` (aus OneDrive verschoben, siehe
`docs/Hardware_Arduino_Settings_LilyGO.md`).

## Was noch nicht verifiziert ist (vor dem ersten Flash beachten)

- **LoRa-Parameter**: identisch zur Boots-Uhr übernommen (siehe deren
  README) — MÜSSEN exakt gleich bleiben, sonst kein Empfang.
- **Ton bei Quick-Messages**: laut Doku ein offener Punkt ("an/aus-
  umschaltbar machen") — hier noch nicht implementiert, nur Vibration
  (respektiert den Stumm-Modus-Schalter).
- Reines Compile-Ergebnis — das tatsächliche Verhalten auf der Uhr (LoRa-
  Empfang, Touch, Haptik) ist damit noch nicht geprüft.
