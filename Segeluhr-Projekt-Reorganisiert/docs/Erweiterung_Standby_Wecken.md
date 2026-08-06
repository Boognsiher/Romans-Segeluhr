# Erweiterung: Standby-Modus & Aufwecken per Geste

> Nicht in der ursprünglichen Spezifikation.

## Status: 🔧 UMGESETZT, KOMPILIERT (06.08.2026) — noch nicht auf Hardware
getestet. Beide Firmwares nutzen `instance.sleepDisplay()`/`wakeupDisplay()`
(LilyGoLib) für den eigentlichen Display-Ein/Aus-Schalter und LVGLs
eingebaute Inaktivitäts-Uhr (`lv_display_get_inactive_time()`) für die
30s-Erkennung — siehe Abschnitt 3 (aktualisiert) für Details und einen
Hardware-Unterschied zwischen den beiden Uhren, der die Umsetzung
vereinfacht hat.

## 1. Ziel
Display schaltet sich nach ~30s Inaktivität ab (Stromersparnis), wacht aber
zuverlässig wieder auf — per Geste, Touch oder automatisch bei Ereignis.

## 2. Verhalten pro Uhr

### Beide Uhren: gemeinsames Grundverhalten
- Nach 30s ohne Interaktion (kein Touch, kein Knopfdruck, keine erkannte
  Geste): Display aus. Restliche Logik (LoRa-Empfang, Statuslogik) läuft
  im Hintergrund normal weiter — nur das Display wird abgeschaltet
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
- [ ] Noch nicht auf Hardware verifiziert: ob `sleepDisplay()` den Touch-
  Controller mitschlafen legt (dann würde Touch-Aufwecken auf der S3 nicht
  funktionieren) oder ob dieser unabhängig weiterläuft — LilyGoLib bietet
  mit `wakeupTouch()` eine separate Funktion dafür, die aktuell NICHT
  aufgerufen wird (Annahme: Touch-Controller bleibt aktiv, da physisch
  getrennter Chip) — falls sich das als falsch erweist, muss
  `wakeupTouch()` ergänzt werden, was aber "Touch weckt das Display auf"
  zirkulär macht (Touch-Interrupt müsste dann unabhängig vom
  LVGL-Indev-Pfad ausgewertet werden)
