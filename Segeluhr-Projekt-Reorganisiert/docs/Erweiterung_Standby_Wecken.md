# Erweiterung: Standby-Modus & Aufwecken per Geste

> Nicht in der ursprünglichen Spezifikation.

## Status: KONZEPT — Umsetzung durch Claude Code gegen den aktuellen Code
beider Firmwares (Segeluhr_TWatch_Ultra.ino, Segeluhr_TWatch_S3.ino).

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

## 3. Offene technische Punkte
- [ ] Prüfen, ob BHI260AP (Ultra) eine fertige "Wrist Tilt to Wake"-Funktion
  als Standard-Feature mitbringt (analog zu bekannten Smartwatch-
  Implementierungen), oder ob dafür ebenfalls Klio nötig wäre — falls
  letzteres, ggf. einfacher: nur Knopf als Wake-Trigger auf der Ultra
- [ ] BMA423-Wakeup-Interrupt-Konfiguration in SensorLib (S3) verifizieren
- [ ] 30s-Timeout ggf. konfigurierbar machen (Menü-Einstellung), nicht fest
  verdrahtet
- [ ] Zusammenspiel mit bestehendem Auto-Stop/Battery-Feature der Android-
  App prüfen (das ist ein separates Feature auf Handy-Seite, aber beide
  zielen auf Stromsparen ab — keine direkte Code-Abhängigkeit, nur beachten)
