# Erweiterung: Alltagsfunktionen S3 (Wecker, Schrittzähler, Stoppuhr, Taschenlampe)

> Nicht in der ursprünglichen Spezifikation. Macht die S3 auch außerhalb des
> Segel-Kontexts als normale Alltags-Uhr sinnvoll tragbar.

## Status: KONZEPT — Umsetzung durch Claude Code gegen den aktuellen Code
von `Segeluhr_TWatch_S3.ino`.

## 1. Ziel
Die Land-Uhr soll nicht nur während des Segelns Sinn ergeben, sondern auch
sonst als normale Armbanduhr mit ein paar nützlichen Zusatzfunktionen
tragbar sein — mit möglichst wenig Zusatzaufwand, indem vorhandene Hardware
genutzt wird, die bisher brachliegt.

## 2. Menü-Struktur (Erweiterung des bestehenden Icon-Grids)

Bisheriges Menü: `[Stumm] [Fragen]` + `[Ausschalten]` (breiter Button).
Neu: dritte Kachel `[Alltag]` führt in ein Untermenü mit den Funktionen aus
Abschnitt 3 — hält das Hauptmenü aufgeräumt, statt es mit vielen Kacheln zu
überladen.

```
Menü:  [Stumm] [Fragen]
       [Alltag]
       [   Ausschalten   ]

Alltag-Untermenü:  [Wecker] [Stoppuhr]
                    [Schritte] [Taschenlampe]
                    [ zurück ]
```

## 3. Einzelne Funktionen

### Schrittzähler (praktisch ohne Zusatzaufwand)
- BMA423 (der bereits für Touch-Wake/Standby genutzte Sensor, siehe
  `Erweiterung_Standby_Wecken.md`) hat einen **eingebauten Schrittzähler-
  Modus**, läuft direkt im Sensor-Chip, ohne den Hauptprozessor zu belasten
- Anzeige: einfache Zahl + Datum, Reset um Mitternacht (oder manuell)
- Kein Netzwerk, keine App-Anbindung nötig — reine Lokalanzeige

### Wecker (nutzt vorhandene RTC)
- PCF85063A (Echtzeituhr-Chip, schon für die Grunduhrzeit im Einsatz) hat
  eine **eingebaute Alarm-Funktion** — funktioniert stromsparend, auch wenn
  der Hauptprozessor im Standby ist (weckt das System bei Alarmzeit auf)
- UI: Uhrzeit einstellen (Touch, +/- oder Scroll), Ein/Aus-Toggle
- Weckton: Vibration (DRV2605, wie bei Quick-Messages) + optional Ton,
  analog zum bestehenden Benachrichtigungskonzept

### Stoppuhr/Timer (Software, nutzt bestehende Zeit-/Haptik-Logik)
- Einfacher Start/Stopp/Reset, große Zeitanzeige
- Technisch nichts Neues — dieselbe Grundlage wie der Countdown-Ring, den
  der BLE-Tester schon zu Testzwecken zeigt (`segeluhr_ble_tester_v2.ino`),
  nur ohne die Segel-spezifische Logik drumherum

### Taschenlampen-Modus (trivial)
- Display auf volle Helligkeit + weißer Vollbild-Hintergrund
- Praktisch für den Steg/Boot am Abend
- Verlassen durch Antippen oder automatisch nach X Sekunden (Standby-Logik
  aus `Erweiterung_Standby_Wecken.md` mitnutzen, aber mit kürzerem Timeout,
  da hier bewusst kurz genutzt wird)

### Datum/Wochentag auf dem Hauptscreen (kleine Ergänzung)
- Ergänzt die bestehende Uhrzeit-Anzeige auf dem Hauptscreen um Datum/
  Wochentag (klein, unterhalb der Uhrzeit) — nutzt dieselbe RTC, kein
  zusätzlicher Sensor nötig

### Zeitgesteuerter Stumm-Modus (baut auf bestehendem Stumm-Toggle auf)
- Ergänzung zum manuellen Stumm-Toggle (`Erweiterung_Land_Boot_LoRa_Kommunikation.md`,
  Abschnitt 6): zusätzlich ein Zeitfenster einstellbar (z.B. 22:00-07:00),
  in dem automatisch stumm geschaltet wird, unabhängig vom manuellen Toggle
- UI-technisch im selben Stumm-Bereich des Menüs unterbringen, nicht als
  komplett neue Kachel

## 4. Bewusst NICHT umgesetzt (siehe Begründung im Chat-Verlauf)
- **Kompass**: kein Magnetometer auf der S3 verbaut, bräuchte zusätzliche
  Hardware
- **Wetter/Musiksteuerung**: würde dauerhafte BLE-Verbindung zum Handy
  brauchen, widerspricht der bewussten Entscheidung "BLE nur kurz für den
  Fragen-Editor" (siehe `Erweiterung_S3_BLE_Fragen_Editor.md`)
- **GPS-abhängige Funktionen** (Sonnenauf-/untergang etc.): S3 hat kein
  eigenes GPS, nur die Ultra

## 5. Offene technische Punkte
- [ ] BMA423-Schrittzähler-API in SensorLib verifizieren (Aktivierung,
  Auslesen, ob automatischer Mitternachts-Reset eingebaut ist oder manuell
  gebaut werden muss)
- [ ] PCF85063A-Alarm-API in SensorLib/LilyGoLib verifizieren (wie der
  Alarm gesetzt wird, wie das Aufwachen aus dem Standby bei Alarmzeit
  ausgelöst wird)
- [ ] Persistenz für Wecker-Einstellung (muss Neustart/Ausschalten
  überleben — NVS/Preferences, analog zu anderen Einstellungen)
- [ ] Genaues UI-Layout des Alltag-Untermenüs (Icon-Grid wie beim
  Hauptmenü, siehe Mockup-Stil aus dem Chat)
- [ ] Standby-Timeout für den Taschenlampen-Modus festlegen (kürzer als
  der normale 30s-Standby, da hier bewusst kurzfristig genutzt wird — z.B.
  60-120s, damit man nicht ständig neu antippen muss, aber auch nicht
  versehentlich den Akku leerbrennt)
