# Segeluhr — Projekt-Status

Diese Datei ist die zentrale Übersicht über alle Komponenten. **Bitte bei
jeder Session, in der sich etwas Wesentliches ändert, aktualisieren** —
das ist der eigentliche Zweck: ein Blick hier reicht, um zu wissen, was
gerade läuft, was Baustelle und was nur Test ist.

## Status-Legende

✅ läuft/getestet · 🔧 in Arbeit · 📋 geplant, noch nicht gebaut · 🗄️ archiviert/nicht mehr aktiv gepflegt

## Übersicht

| Komponente | Hardware | Status | Zuletzt getestet | Bemerkung |
|---|---|---|---|---|
| Android-App | Handy (Samsung) | 🔧 | 28.07.2026 (Kernfunktionalität) | Master: GATT-Server, alle Engines, BLE-Protokoll. **Automatische See-Erkennung (docs/Erweiterung_Automatische_See_Erkennung.md) 06.08. implementiert** — Overpass-API + Kreis-Ketten-Berechnung statt der urspr. geplanten JTS-Polygon-Lösung (LakeGeofenceEngine kannte nur Kreise, kein Polygon-Support), Kette statt Einzelkreis für lange/unregelmässige Seen. `LakeGeofenceEngine`/`SettingsRepository`/Setup-UI dafür umgebaut. **⚠️ NICHT kompiliert** — kein `gradlew`/lokales Gradle im Projekt vorhanden, nur manuell durchgesehen. **Vor Nutzung unbedingt in Android Studio bauen + testen.** Bekannte Einschränkung: nur einfache OSM-Ways, keine Multipolygon-Relationen. **Landuhr-Kartenansicht (docs/Erweiterung_Landuhr_Kartenansicht.md) 09.08. implementiert und auf echter Hardware Ende-zu-Ende verifiziert** — neuer Rollen-Umschalter "Auf dem Boot"/"An Land" (Setup-Tab), im Land-Modus zeigt die App statt der Segel-Tabs eine osmdroid-Karte mit der zuletzt per LoRa/BLE von der Land-Uhr empfangenen Boot-Position (`ble/LandUhrClient.kt`, neue BLE-Central-Rolle, unabhängig vom bestehenden GATT-Server) — Marker erscheint korrekt. Dabei zwei Bugs gefunden+gefixt (Details siehe Doku): korrupte NVS-Bluetooth-Kalibrierungsdaten auf der S3 nach Core-Wechsel (Fix: Flash-Erase), fehlende `BLUETOOTH_SCAN`-Berechtigung bei Installs mit schon erteiltem Standortzugriff (Fix: eigener Berechtigungs-Check/-Button). Nur kurz angetestet, noch nicht über eine längere Session. **10.08.: Session-Distanz-Aufsummierung implementiert** (`core/DistanceTracker.kt`, siehe "Bekannte offene Punkte" unten) — **⚠️ NICHT kompiliert/getestet**, Test heute Abend geplant. **10.08.: Heimweg-ETA/VMC träger gemacht** (`core/HomeProgressTracker.kt`, siehe "Bekannte offene Punkte" unten) — **⚠️ NICHT kompiliert/getestet**. **10.08.: Boots-Kalibrierung (Am-Wind-Wendewinkel) implementiert** (docs/Erweiterung_Boots_Kalibrierung.md) — Kalibrierungsmodus + Smart-Modus in `WindEngine`, ersetzt den fest verdrahteten 45°-Wert in `HomeEngine`/`CompetitionEngine`. **Zwischenstand: nur EIN globales Profil, Umbau auf mehrere benannte Boots-Profile ist als Nächstes geplant** (siehe "Bekannte offene Punkte"). **⚠️ NICHT kompiliert/getestet**. |
| `Segeluhr_TWatch_S3` (ALT) | T-Watch S3 | 🗄️ | 28.07.2026 | Ursprüngliche Boots-Uhr-Rolle (BLE Central, Alltags-/Segelmodus, Auto-Focus, Zeit-Sync) — Code jetzt nach `Segeluhr_TWatch_Ultra.ino` portiert, physische S3-Hardware wird zur Land-Uhr (siehe Zeile unten) |
| `Segeluhr_TWatch_Ultra` | T-Watch Ultra | 🔧 | 09.08.2026 (Segelmodus-Exit-Bug + 94px-Tab-Leiste auf Hardware verifiziert, COM17) | Boots-Uhr, aus S3-Firmware + LoRa-Sender/Quick-Messages zusammengeführt, kompiliert + geflasht (COM17), bootet sauber. **LoRa-Status-Broadcast + Quick-Messages (beide Richtungen, JA per kurzem/NEIN per langem Tasterdruck) Ende-zu-Ende verifiziert.** Selbstempfangs-Bug gefixt. UI-Nacharbeit (Schriftgrösse, gedrehte Eck-Tabs wg. Gehäuse-Abdeckung) mehrfach nachgebessert, **94px-Tab-Leiste 09.08. auf Hardware verifiziert (Eck-Tabs sauber sichtbar)**. **09.08.: Bug "Segelmodus erzwingen hat keinen Weg zurück" gefixt** — Schalter dafür sitzt im Setup-Tab des Alltags-Screens, der beim Erzwingen verlassen wird und (da `appModeTick()` den Rückfall bei `forceSegelnMode` komplett überspringt) nicht mehr erreichbar war; neuer Button "Segelmodus beenden" jetzt im Segeln-Menü, verifiziert. LoRa-Frequenz 06.08. auf 869.525 MHz umgestellt (siehe unten). **Klio-Gestentraining (docs/Erweiterung_Gesten_Training_Klio.md) 06.08. implementiert + kompiliert** (Serial-Kommandos `TRAIN JA`/`TRAIN NEIN`/etc., NVS-Persistenz) — dabei zwei Bugs im bestehenden Gesten-Code gefunden: `USING_BHI260_SENSOR` war nie gesetzt (Gestencode lief nur als Stub, der 05.08.-Pitch-Messwert stammt vermutlich nicht aus dieser Firmware) und LilyGoLib lädt standardmässig eine BHI260-Firmware ohne Klio-Support (jetzt per `uploadFirmware()` nachgeladen) — Details siehe Doku Abschnitt 4. **Noch nicht auf Hardware getestet**, insbesondere ob der Firmware-Swap den Schwellenwert-Fallback (Pitch/Shake) weiter funktionsfähig lässt. **Standby/Wecken (docs/Erweiterung_Standby_Wecken.md) 06.08. implementiert + kompiliert**: Display-Aus nach 30s (`instance.sleepDisplay()`, kein Deep-Sleep), Aufwecken per Knopf/Geste/eingehender Quick-Message — noch nicht auf Hardware getestet. **LoRaStatusPacket sendet jetzt auch die eigene RTC-Zeit mit** (06.08., für Land-Uhr-Zeit-Sync, siehe unten) — Paket 20→26 Bytes. **QuickMessageRequest 06.08. um eigene (BLE-erstellte) Fragen erweitert** (4→37 Bytes, siehe unten) — zeigt eingehende eigene Fragen von der Land-Uhr korrekt an. **Beide Firmwares müssen zusammen neu geflasht werden**, sonst laufen sie auseinander. **BLE zum Handy 06.08. erstmals verbunden und getestet, dabei 3 Bugs gefunden + gefixt** (kompiliert + geflasht, COM17, Details siehe `docs/Offene_Punkte_Hardware_Test_05_08.md`): Absturz beim Verbinden (BLE-Notify-Callbacks riefen LVGL/I2C direkt aus dem falschen FreeRTOS-Task auf, jetzt über Flags entkoppelt und im `loop()`-Task abgearbeitet, dabei auch ein `bleClient`-Speicherleck beim Reconnect gefixt), Segeln-Tabs (Wind/Heim/CD/Man) sprangen ständig zu Nav zurück (`autoFocusTick()` jetzt auf Flankenerkennung umgebaut statt bei jedem Tick zu erzwingen), keine Uhrzeit im Segeln-Modus sichtbar (jetzt zusätzlich in der globalen Statusleiste). **Noch nicht über eine längere Session/echten Segeltörn verifiziert.** |
| `Segeluhr_TWatch_S3` (Land) | T-Watch S3 | 🔧 | 09.08.2026 (Zeitanzeige erneut getestet, lief korrekt) | Land-Uhr (neue Rolle, ersetzt bisherige BLE-Central-Rolle), kompiliert + geflasht (COM16), bootet sauber. **LoRa-Empfang + Quick-Messages (beide Richtungen) Ende-zu-Ende verifiziert**, Buttons/Schrift nach Hardware-Test vergrössert. **Standby/Wecken 06.08. implementiert + kompiliert**: Display-Aus nach 30s, Aufwecken per Touch oder BMA423-Hardware-Tilt (LilyGoLib-Event, kein eigener Schwellenwert-Code nötig) — noch nicht auf Hardware getestet. **Menü-Redesign 06.08. implementiert + kompiliert** (docs/Erweiterung_Land_Boot_LoRa_Kommunikation.md Abschnitt 3): Icon-Grid [Stumm][Fragen]/[Alltag]/[Ausschalten] statt Liste. **Manuelles "Zeit stellen" entfernt**, Land-Uhr übernimmt Zeit jetzt automatisch aus dem ersten LoRa-Paket. **Alltagsfunktionen (docs/Erweiterung_S3_Alltagsfunktionen.md) 06.08. implementiert + kompiliert**: Wecker (Software-Vergleich statt Hardware-Alarm — reale S3 hat PCF8563 statt dokumentiertem PCF85063A, dessen Alarm-IRQ bei dieser LilyGoLib-Version im Wachzustand nicht verdrahtet ist, jetzt mit NVS-Persistenz), Stoppuhr, Schrittzähler (BMA423-Pedometer), Taschenlampe (90s-Overlay). Kein Ton beim Wecker. **BLE-Fragen-Editor (docs/Erweiterung_S3_BLE_Fragen_Editor.md) 06.08. implementiert + kompiliert**: BLE-Toggle im Fragen-Menü (NimBLE-Server, standardmäßig aus), eigene Fragen per Web-Bluetooth-Seite (`docs/fragen_editor_web/index.html`) erstellbar, max. 5 gespeichert (NVS, Ringpuffer). Alles noch nicht auf Hardware getestet. Ton bei Quick-Messages noch offen — siehe `docs/Offene_Punkte_Hardware_Test_05_08.md`. **09.08.: neue BLE-Characteristic für die Landuhr-Kartenansicht** (docs/Erweiterung_Landuhr_Kartenansicht.md) am selben Fragen-Editor-Server/Schalter — sendet die per LoRa empfangene Boot-Position ans Handy an Land. **Auf Hardware verifiziert** (mit vollem Flash-Erase gegen korrupte NVS-BT-Kalibrierungsdaten, siehe Doku), Ende-zu-Ende bis zum Karten-Marker auf dem Land-Handy bestätigt. Noch nicht mit zwei gleichzeitigen BLE-Clients getestet (Fragen-Editor-Webseite + Landuhr-App). |
| `segeluhr_ble_tester` | ESP32-C3/XIAO | 🗄️ | — | nur lokal vorhanden, nicht in diesem Repo, siehe `Segeluhr-Firmware/TESTING/README.md` |
| `Segeluhr_Basis_Solo` | (für Ultra gedacht) | 🗄️ | — | nur lokal vorhanden, nie auf echter Hardware getestet |

## Bekannte offene Punkte

- ~~**S3 (Land-Uhr) zeigt eine falsche/alte Zeit**~~ — 09.08. erneut
  getestet, lief korrekt (vermutlich lag's an einem der vielen
  Test-Reflashes vom 06.08.). Root Cause im Code unverändert
  (`loraReceiveTick()`/`timeSyncedFromBoat` synct weiterhin nur einmal pro
  S3-Boot) — Roman-Entscheidung 09.08.: aktuell kein robusterer Fix nötig,
  im Zweifel S3 per RST neu starten. Details siehe
  `docs/Offene_Punkte_Hardware_Test_05_08.md`.
- ~~**"Segelmodus erzwingen" auf der Ultra hatte keinen Weg zurück**~~ —
  09.08. gefunden + gefixt (neuer Button "Segelmodus beenden" im
  Segeln-Menü) + auf Hardware verifiziert.
- **Gesten-Kalibrierung weiterhin offen** (unverändert seit 06.08.):
  `GESTURE_TILT_TARGET_ANGLE_DEG`/`GESTURE_SHAKE_MIN_AMPLITUDE` basieren auf
  nur einer Schreibtisch-Messung bzw. sind komplett unverifiziert, siehe
  `docs/Offene_Punkte_Hardware_Test_05_08.md`.
- **Fehlende Root-Dokumente:** `Segeluhr_Spezifikation.md` und
  `BLE_Protokoll.md` (die ursprüngliche Basis-Spezifikation) liegen nicht
  in diesem Repo — nur die `Erweiterung_*.md`-Ergänzungsdokumente unter
  `Segeluhr-Android/Segeluhr/docs/`. Falls die Basisdokumente noch woanders
  existieren, gehören sie hier in den Root-Ordner.
- ~~**`distanceTraveledM`-Aufsummierung in der App**~~ — 10.08. implementiert
  (`core/DistanceTracker.kt`, Anzeige im Normal-Tab/Heimweg-Karte, Reset
  über "Alles zurücksetzen"). **Noch nicht kompiliert/getestet.**
  **Weiterhin offen:** Weitergabe per LoRa an die Land-Uhr — das aktuelle
  `LoRaStatusPacket` (`Segeluhr-Firmware/shared/LoRaPacket.h`) hat noch
  kein `distanceTraveledM`-Feld, das ist reine Firmware-Arbeit (beide
  Uhren müssten neu geflasht werden), siehe
  `docs/BLE_Protokoll_Ergaenzung_Heimweg_LoRa.md`.
- ~~**ETA/VMC zu reaktiv auf Momentan-Kurs, Distanz auf Land-Uhr dadurch
  verfälscht**~~ — gefunden 10.08. beim Durchspielen der Heimweg-HTML-
  Vorschau, **10.08. behoben**: neue Klasse `core/HomeProgressTracker.kt`
  misst die tatsächliche Annäherung ans Ziel über ein gleitendes
  60s-Zeitfenster (`(Distanz vor 60s − Distanz jetzt) / 60s`) statt aus dem
  Momentan-Kurs zu rechnen — ein COG-Sprung während einer Wende hat dadurch
  keinen direkten Einfluss mehr auf ETA/Distanz. Erst ab 20s Historie wird
  überhaupt ein Wert geliefert. `HomeEngine.tick()` nutzt diesen Wert jetzt
  für `HomeGuidance.vmcKn`/ETA; die Wende-Empfehlung selbst bleibt bewusst
  instantan. Tracker wird bei Modus-Ein/Aus UND bei Heimatpunkt-Änderung
  zurückgesetzt (sonst würde alte Distanz-Historie zum vorherigen Ziel die
  Berechnung verfälschen). Details siehe `docs/Erweiterung_Heimweg.md`.
  **Noch nicht kompiliert/getestet.**
- **Boots-Kalibrierung: aktuell nur ein globales Profil, Roman will
  mehrere** (10.08.2026): `SettingsRepository.boatProfileFlow` speichert
  genau einen Wendewinkel-Wert für die ganze App-Installation. Gewünscht:
  mehrere benannte Profile (z.B. pro Boot) anlegen/wechseln können, plus
  ein vorbefülltes Grundprofil mit von Roman geschätzten Startwerten statt
  reinem 45°-Fallback — wartet auf die konkreten Startwerte (Zusammenfassung
  angekündigt, noch nicht erhalten). Geplant: Liste als JSON in DataStore
  (Muster wie `LAKE_CIRCLES_JSON`) + Profilverwaltung im Setup-Tab, siehe
  `docs/Erweiterung_Boots_Kalibrierung.md`. **Noch nicht umgesetzt.**
- ~~**Duty-Cycle-/Kanalwahl für LoRa in der Schweiz**~~ ✅ erledigt
  06.08.2026: Frequenz beider Firmwares von 868.0 MHz auf 869.525 MHz
  umgestellt (Band 869.4-869.65 MHz, 10% statt 1% Duty-Cycle in der
  Schweiz erlaubt). **Korrektur 09.08.2026:** war hier fälschlich noch als
  "nicht geflasht" notiert - tatsächlich wurden beide Uhren schon am
  06.08. mit diesem Stand geflasht (Commit `320ab09`, nach der
  Frequenzänderung `56cf7d7`). Zusätzlich heute (09.08.) indirekt
  bestätigt: Ultra frisch mit aktuellem Source (weiterhin 869.525 MHz)
  geflasht, S3 hat direkt danach wieder erfolgreich die Zeit per LoRa
  übernommen - das geht nur, wenn beide auf derselben Frequenz funken.
- **DRV2605-Haptik-Stärke**: aktuelle stärkste verfügbare ROM-Effekte
  genutzt, aber keine echte Software-Gain-Kontrolle möglich (siehe
  Firmware-Kommentar bei `triggerHaptic()`) — bei Bedarf nochmal
  RTP-Modus auf Register-Ebene prüfen.

## Ordnerstruktur

```
Segeluhr-Android/Segeluhr/     Android-App (Kotlin/Compose)
  docs/                        Erweiterungs-Dokumentation (Erweiterung_*.md)
Segeluhr-Firmware/
  ECHT/                        Firmware für echten Segelbetrieb
    Segeluhr_TWatch_S3/        ✅ aktueller Stand
    Segeluhr_TWatch_Ultra/     📋 geplant
    Segeluhr_WatchS_LoRaEmpfaenger/  📋 geplant
  TESTING/                     Tester/Prototypen, nicht für echten Betrieb
PROJEKT_STATUS.md              diese Datei
```
