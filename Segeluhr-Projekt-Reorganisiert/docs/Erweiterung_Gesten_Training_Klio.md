# Erweiterung: Trainierbare Gestenerkennung (Klio, auf Wasser kalibriert)

> Nicht in der ursprünglichen Spezifikation. Baut auf dem Befund von gestern
> auf: `GESTURE_TILT_TARGET_ANGLE_DEG`/`GESTURE_SHAKE_MIN_AMPLITUDE` beruhten
> auf einer einzigen Schreibtisch-Messung, nicht auf echten Segelbedingungen.

## Status: KONZEPT — Claude Code setzt das gegen den aktuellen Stand von
`Segeluhr_TWatch_Ultra.ino` um, ich habe keinen Einblick in den Code selbst.

## 1. Ziel
Statt fest programmierter Schwellenwerte (Winkel/Amplitude) nutzt die Ultra
Boschs **Klio**-Algorithmus (läuft direkt auf dem BHI260AP-Sensor) — ein
selbstlernender Mustererkenner. Wir trainieren die Gesten (Ja/Nein) direkt
auf dem Wasser, mit echter Krängung/Wellenbewegung als Hintergrund, statt am
Schreibtisch zu raten.

## 2. Trainings-Modus: Ablauf auf der Uhr

Neuer Menüpunkt "Gesten trainieren" (im bestehenden Menü-Screen):

1. Auswahl: welche Geste trainieren? (Ja / Nein)
2. Anzeige: "Geste jetzt X mal ausführen" (empfohlen: 8-10 Wiederholungen,
   siehe Kalibrierungs-Protokoll unten)
3. Bei jeder Wiederholung: kurze Haptik-Bestätigung ("aufgezeichnet"),
   Zähler hochzählen
4. Nach der letzten Wiederholung: Muster an Klio übergeben, Sensor
   trainiert daraus ein wiedererkennbares Muster
5. Kurzer Test direkt danach: Geste nochmal ausführen, Anzeige ob erkannt

## 3. Kalibrierungs-Protokoll (WICHTIG — das eigentliche Problem von gestern)

Reines Training am Steg/Schreibtisch reicht nicht — das war ja genau der
Fehler der letzten Session. Stattdessen:

- **Training in mehreren Segel-Bedingungen durchführen, nicht nur einmal:**
  - Ruhiges Wasser, wenig Krängung
  - Mäßige Krängung (normales Segeln am Wind)
  - Choppiges Wasser / Wellenschlag
- Pro Bedingung 3-4 Wiederholungen, insgesamt die empfohlenen 8-10 Samples
  über die verschiedenen Bedingungen verteilt — nicht alle unter identischen
  Umständen, sonst erkennt Klio nur "die eine Bedingung" zuverlässig
- **Nach dem Training: Fehlalarm-Test.** Normal weitersegeln (Schoteinholen,
  Trimmen, Hinsetzen) für ein paar Minuten, OHNE die Geste auszuführen,
  dabei die Debug-Logs (`[Gesten]`-Präfix, schon vorhanden) mitlesen und
  zählen, wie oft fälschlich erkannt wird. Bei zu vielen Fehlalarmen:
  Training wiederholen mit mehr/klareren Wiederholungen
- Das Ganze für **Ja** und **Nein** getrennt durchführen

## 4. Technische Umsetzung (Claude Code prüft exakte API gegen SensorLib)

Groborientierung, exakte Funktionsnamen bitte gegen die tatsächlichen
SensorLib-Klio-Beispiele verifizieren (Pfad vermutlich `Sensors/IMU/BHI260AP/`
im SensorLib-Repo, Klio-Beispiel-Sketch):

```cpp
// Grober Ablauf, Pseudocode - echte API-Namen in SensorLib pruefen:

enum class TrainingTarget { JA, NEIN };

void startGestureTraining(TrainingTarget target) {
    // Klio in Lern-Modus versetzen fuer den gewaehlten Pattern-Slot
    // (JA und NEIN als zwei getrennte Klio-Pattern-IDs)
}

void recordTrainingSample() {
    // Aktuelle Sensor-Sequenz als ein Trainings-Sample an Klio uebergeben
    // Rueckmeldung (Haptik) sobald Sample akzeptiert wurde
}

void finalizeTraining(TrainingTarget target) {
    // Klio-Pattern aus den gesammelten Samples erzeugen lassen
    // Pattern-Daten persistieren (Flash/NVS), damit sie einen Reboot
    // ueberleben - Klio-Patterns muessen vermutlich explizit
    // gespeichert/geladen werden, nicht automatisch persistent
}

QuickAnswer runGestureRecognition() {
    // Laufende Sensordaten gegen trainierte Patterns pruefen
    // Rueckgabe: JA/NEIN falls erkannt (mit Konfidenz-Schwelle),
    // PENDING falls nichts erkannt wurde
}
```

### Offene technische Punkte
- [ ] Exakte Klio-API in SensorLib verifizieren (Funktionsnamen,
  Pattern-Anzahl-Limit, ob Persistenz über Reboot eingebaut ist oder
  manuell in Flash geschrieben werden muss)
- [ ] Konfidenz-Schwelle für "erkannt" festlegen (Klio liefert vermutlich
  einen Score/Wahrscheinlichkeitswert zurück, nicht nur ja/nein)
- [ ] Verhältnis zum bisherigen Schwellenwert-Ansatz (`GESTURE_TILT_TARGET_ANGLE_DEG`
  etc.): als Fallback behalten, falls Klio-Training fehlschlägt oder das
  Gerät neu aufgesetzt wird, bevor neu trainiert wurde
- [ ] Wo im Menü genau der Trainings-Einstieg sitzt (bestehende
  Menü-Screen-Struktur der Ultra kennt nur Claude Code aktuell)
- [ ] Debug-Logging (`[Gesten]`-Präfix, schon vorhanden) um Klio-Konfidenz-
  Werte ergänzen, damit der Fehlalarm-Test in Abschnitt 3 auswertbar ist

## 5. Warum nicht einfach "mehr Schwellenwerte austesten"
Das war der gestrige Ansatz und genau daran ist die Kalibrierung
hängengeblieben (nur eine Schreibtisch-Messung, Schütteln/Nein nie
verifiziert). Klio löst das grundsätzlich anders: es lernt aus echten
Bewegungsmustern statt dass wir Zahlen raten — dafür muss aber das Training
selbst unter echten Bedingungen (Wasser, nicht Schreibtisch) passieren,
sonst reproduziert man denselben Fehler nur mit mehr Aufwand.
