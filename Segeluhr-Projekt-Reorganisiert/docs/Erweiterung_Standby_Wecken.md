# Erweiterung: Standby-Modus & Aufwecken per Geste

> Nicht in der ursprünglichen Spezifikation.

## Status: 🔧 UMGESETZT, auf S3-Hardware getestet (11.08.2026) — dabei einen
Bug gefunden und gefixt, siehe Abschnitt 3a. Ultra-Seite weiterhin nicht auf
Hardware verifiziert. Beide Firmwares nutzen `instance.sleepDisplay()`/
`wakeupDisplay()` (LilyGoLib) für den eigentlichen Display-Ein/Aus-Schalter
und LVGLs eingebaute Inaktivitäts-Uhr (`lv_display_get_inactive_time()`) für
die 30s-Erkennung — siehe Abschnitt 3 (aktualisiert) für Details und einen
Hardware-Unterschied zwischen den beiden Uhren, der die Umsetzung
vereinfacht hat.

## 1. Ziel
Display schaltet sich nach ~30s Inaktivität ab (Stromersparnis), wacht aber
zuverlässig wieder auf — per Geste, Touch oder automatisch bei Ereignis.

## 2. Verhalten pro Uhr

### Beide Uhren: gemeinsames Grundverhalten
- Nach 30s ohne Interaktion (kein Touch, kein Knopfdruck, keine erkannte
  Geste): Display aus. Restliche Logik (LoRa-Empfang, Statuslogik) läuft
  im Hintergrund normal weiter — nur das Display wird abgeschaltet.
  **Ausnahme seit 13.08.2026: auf der Ultra NUR im Alltags-Modus, siehe
  Ultra-Abschnitt unten** — im Segeln-Modus bleibt das Display dauerhaft an.
- **Automatisches Aufwecken bei eingehender Quick-Message**, unabhängig von
  Geste/Touch — sonst wird die Frage verpasst, weil der Screen aus ist
  (siehe bestehende Benachrichtigungs-Logik, Vibration bleibt zusätzlich)
- Der reguläre 30s-Status-Broadcast weckt NICHT auf (wie bisher: kein
  Ereignis, keine Benachrichtigung, siehe bestehende Doku dazu)

### T-Watch Ultra (Boot)
- Aufwecken: Handgelenk-Heben-Geste (BHI260AP) — dieselbe Sensor-Basis wie
  die trainierte Klio-Gestenerkennung für Ja/Nein, aber als einfachere,
  nicht trainierte "Wrist-Tilt"-Funktion (falls BHI260AP das als fertige
  Standard-Funktion mitbringt, nicht über Klio-Training) oder Knopfdruck
  als Fallback
- **13.08.2026 geändert (Roman-Wunsch): Der 30s-Standby gilt auf der Ultra
  nur noch im Alltags-Modus.** Im Segeln-Modus ist der Bildschirm das
  eigentliche Navigationsinstrument (Nav-/Wind-/Heimweg-Werte auf einen
  Blick, ohne extra Taster/Geste nötig) — ein Blackout nach 30s Inaktivität
  ergibt dort keinen Sinn, anders als im Alltags-Modus (Smartwatch-Nutzung).
  `standbyTick()` kehrt für `MODE_SEGELN` jetzt frühzeitig zurück, Display
  bleibt dauerhaft mit voller Helligkeit an. `switchToMode()` weckt das
  Display beim Wechsel IN den Segeln-Modus explizit auf, falls es gerade
  wegen Alltags-Standby schlief. **Kompiliert, noch nicht auf Hardware
  verifiziert.**

### T-Watch S3 (Land)
- Aufwecken: **Handgelenk-Heben-Geste ODER Antippen des Bildschirms**
  (beide gleichwertig, da die S3 auch mal auf einem Tisch liegen kann statt
  getragen zu werden — dann greift die Geste nicht, Touch schon)
- Nutzt den BMA423-Beschleunigungssensor, der einen eingebauten
  Hardware-"Wakeup"-Interrupt genau für diesen Zweck mitbringt — läuft
  direkt auf dem Sensor-Chip, ohne den Hauptprozessor dauerhaft wach zu
  halten (stromsparend)
- Wichtig: das ist eine ANDERE Funktion als die Ja/Nein-Touch-Buttons bei
  Quick-Messages — hier geht es nur ums Display an/aus, keine Konflikte

## 3. Technische Umsetzung (implementiert)

**Display-Ein/Aus:** `instance.sleepDisplay()`/`instance.wakeupDisplay()`
(LilyGoLib, für beide Boards identisch benannt) — laut Doc-Kommentar in
`LilyGoWatchUltra.h` senkt das den Stromverbrauch auf ca. 10mA, ohne CPU/
Sensoren/Funk anzuhalten. **Wichtig:** das ist etwas anderes als
`instance.sleep()` (echter ESP32-Deep-Sleep, bereits als manueller
"Ausschalten"-Button im Alltag-Menü der Ultra vorhanden, `cbShutdown()`) —
Deep-Sleep hält u.a. LoRa/BLE komplett an, Display-Standby nicht.

**30s-Inaktivitätserkennung:** LVGLs eingebaute Uhr
(`lv_display_get_inactive_time(NULL)`), die für jede Touch-Eingabe
automatisch zurückgesetzt wird. Für Knopfdruck (Ultra) und erkannte Gesten
(beide Uhren) wird sie zusätzlich manuell per
`lv_display_trigger_activity(NULL)` zurückgesetzt — eine einzige Uhr deckt
so alle Interaktionsarten einheitlich ab, kein separates Tracking pro
Quelle nötig.

**Wrist-Tilt-Wake unterscheidet sich zwischen den beiden Uhren** (Antwort
auf die ursprünglich offene Frage in Abschnitt 3, alter Text):
- **S3 (BMA423):** hat eine fertige Hardware-Tilt-Erkennung
  (`SensorBMA423::enableTiltIRQ()`/`isTilt()`) — LilyGoLib konfiguriert sie
  für die S3 bereits vollständig in `instance.begin()` und meldet sie als
  `SENSOR_TILT_DETECTED`-Event über das eingebaute Event-System
  (`instance.onEvent(...)`). Kein eigener Schwellenwert-Code nötig, nur
  das Event abonniert (`onDeviceSensorEvent()` in `Segeluhr_TWatch_S3.ino`).
- **Ultra (BHI260AP):** hat KEIN vergleichbares fertiges Standard-Feature
  (kein "wrist tilt"/"wake" in `SensorBHI260AP`s virtuellen Sensor-IDs
  gefunden). Wiederverwendet deshalb dieselbe Pitch-Schwelle wie die
  (nicht trainierte) JA-Fallback-Geste aus
  `docs/Erweiterung_Gesten_Training_Klio.md`, aber ungegatet (auch ohne
  offene Quick-Message-Frage) — genau die im ursprünglichen Text dieses
  Abschnitts vorgeschlagene "einfachere, nicht trainierte 'Wrist-Tilt'-
  Funktion" statt eines eigenen Klio-Musters.

**Automatisches Aufwecken bei Quick-Message:** in beiden
`handleIncomingQuickMessageRequest()`-Funktionen ergänzt, unabhängig von
Geste/Touch.

## 3a. Bugfix 11.08.2026: `sleepDisplay()`/`wakeupDisplay()` sind auf der S3
Leer-Stubs

Beim Debugging eines Feld-Vorfalls (S3-Bildschirm schwarz/unbedienbar bei
Roman) fiel auf: in der gepinnten LilyGoLib (`LilyGoWatchS3.cpp`, Klasse
`LilyGoWatch2022`, die für die S3 als `instance` verwendet wird) sind
`sleepDisplay()`/`wakeupDisplay()` **auskommentierte Leer-Funktionen**:

```cpp
void LilyGoWatch2022::sleepDisplay()  { /* LilyGoDispSPI::sleep(); */ }
void LilyGoWatch2022::wakeupDisplay() { /* LilyGoDispSPI::wakeup(); */ }
```

Sie tun auf echter Hardware buchstäblich nichts — unser Standby-Code
(`standbyTick()`/`wakeDisplay()` in `Segeluhr_TWatch_S3.ino`) loggte zwar
korrekt "Display ausgeschaltet"/"aufgeweckt", das Backlight/Panel wurde davon
aber nie wirklich beeinflusst. Fix: zusätzlich direkt per
`instance.setBrightness(0)` (schlafen) / `instance.setBrightness(DEVICE_MAX_BRIGHTNESS_LEVEL)`
(aufwecken) steuern — diese Funktion ruft auf der S3 tatsächlich
`LilyGoDispSPI::setBrightness()` auf (verifiziert im LilyGoLib-Quellcode).
Die Stub-Aufrufe bleiben zusätzlich im Code, falls eine künftige LilyGoLib-
Version sie doch befüllt.

**Nicht verifiziert, ob das den ursprünglich gemeldeten schweren Vorfall
(Bildschirm dauerhaft schwarz, nur per Ladekabel aufgeweckt) erklärt** — bei
dem war die Firmware selbst nachweislich nicht abgestürzt (LoRa-Empfang lief
im Serial-Log unauffällig weiter). Für die weitere Ursachensuche siehe
`Erweiterung_S3_Reset_Diagnose.md`.

## 4. Offene technische Punkte
- [x] BHI260AP (Ultra): kein fertiges "Wrist Tilt to Wake"-Feature
  gefunden — Pitch-Schwellenwert-Fallback wiederverwendet (siehe oben)
- [x] BMA423-Wakeup-Interrupt-Konfiguration in SensorLib (S3) verifiziert —
  wird von LilyGoLib bereits automatisch aktiviert, nur das Event
  abonniert
- [ ] 30s-Timeout ggf. konfigurierbar machen (Menü-Einstellung), nicht fest
  verdrahtet (aktuell `STANDBY_TIMEOUT_MS`-Konstante in beiden Dateien)
- [ ] Zusammenspiel mit bestehendem Auto-Stop/Battery-Feature der Android-
  App prüfen (das ist ein separates Feature auf Handy-Seite, aber beide
  zielen auf Stromsparen ab — keine direkte Code-Abhängigkeit, nur beachten)
- [x] Auf S3-Hardware verifiziert (11.08.2026): `sleepDisplay()` legt den
  Touch-Controller NICHT mitschlafen — Touch-Aufwecken funktioniert im Test
  zuverlässig. Der eigentliche Befund war ein anderer, siehe Abschnitt 3a:
  `sleepDisplay()`/`wakeupDisplay()` sind auf der S3 komplett wirkungslose
  Stubs, gefixt durch direkte `setBrightness()`-Aufrufe.
- [ ] Ultra-Seite weiterhin nicht auf Hardware verifiziert (weder Wrist-
  Tilt-Wake noch ob `sleepDisplay()`/`wakeupDisplay()` dort ebenfalls Stubs
  sind — siehe `LilyGoWatchUltra.cpp`, noch nicht geprüft)
