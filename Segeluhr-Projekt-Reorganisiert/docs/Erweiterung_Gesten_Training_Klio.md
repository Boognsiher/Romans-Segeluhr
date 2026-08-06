# Erweiterung: Trainierbare Gestenerkennung (Klio, auf Wasser kalibriert)

> Nicht in der ursprünglichen Spezifikation. Baut auf dem Befund von gestern
> auf: `GESTURE_TILT_TARGET_ANGLE_DEG`/`GESTURE_SHAKE_MIN_AMPLITUDE` beruhten
> auf einer einzigen Schreibtisch-Messung, nicht auf echten Segelbedingungen.

## Status: KONZEPT — Claude Code setzt das gegen den aktuellen Stand von
`Segeluhr_TWatch_Ultra.ino` um, ich habe keinen Einblick in den Code selbst.

> Bootstyp-Kontext: Musto Skiff (Einhand-Trapez-Skiff mit Steuerknüppel-
> Verlängerung/Tiller Extension und Spinnaker) — Details aus dem offiziellen
> Handbuch (mustoskiff.de) fließen ins Kalibrierungs-Protokoll (Abschnitt 3)
> ein, insbesondere die Wende-/Halse-Handwechsel-Bewegung als expliziter
> Fehlalarm-Testfall.

## 1. Ziel
Statt fest programmierter Schwellenwerte (Winkel/Amplitude) nutzt die Ultra
Boschs **Klio**-Algorithmus (läuft direkt auf dem BHI260AP-Sensor) — ein
selbstlernender Mustererkenner. Wir trainieren die Gesten (Ja/Nein) direkt
auf dem Wasser, mit echter Krängung/Wellenbewegung als Hintergrund, statt am
Schreibtisch zu raten.

## 2. Trainings-Ablauf: einmaliger Kalibrierungslauf, kein Dauerbetrieb-UI

**Vereinfachung gegenüber der ersten Version dieser Doku:** Da die Ultra
dauerhaft von derselben Person am rechten Handgelenk getragen wird (kein
Personenwechsel), braucht es KEIN permanentes Trainings-Menü auf der Uhr.
Stattdessen: ein einmaliger, seriell gesteuerter Kalibrierungslauf, danach
wird das trainierte Muster fest ins Flash geschrieben.

Praktischer Ablauf (Vorschlag, Details für Claude Code):
1. Ultra über USB am seriellen Monitor angeschlossen, spezieller
   Kalibrierungs-Modus per Serial-Kommando gestartet (z.B. `TRAIN JA` /
   `TRAIN NEIN` eintippen)
2. Firmware fordert dann über den seriellen Monitor zur nächsten
   Wiederholung auf (siehe Ablauftabelle Abschnitt 3), zählt mit
3. Nach der letzten Wiederholung: Klio-Pattern erzeugen, sofort per
   Serial-Ausgabe einen kurzen Erkennungstest anbieten
4. Gespeichertes Pattern übersteht Neustart (Flash/NVS)

Kein Touch-UI, kein Menüpunkt auf der Uhr selbst nötig — reduziert den
Implementierungsaufwand spürbar gegenüber der ursprünglichen Idee eines
vollständigen Trainings-Menüs (das bliebe nur relevant, falls später doch
mal die Uhr von wechselnden Personen getragen würde).

## 3. Kalibrierungs-Protokoll — WICHTIG: nach Aktivität, Trapez-Nutzung UND Richtung trennen

Reines "verschiedene Wellenbedingungen abdecken" (erste Version dieser
Doku) reicht nicht aus. Der Musto Skiff (Einhand-Trapez-Skiff mit
Steuerknüppel-Verlängerung/Tiller Extension und Spinnaker, laut Handbuch)
bringt mehrere Faktoren mit, die die Handgelenk-Grundhaltung stark verändern:

- **Trapez vs. im Boot sitzend**: beim Trapezieren hängt der Körper weit
  außenbords, praktisch horizontal — fundamental andere Arm-/
  Handgelenkhaltung als sitzend. Muss als eigene Dimension kalibriert werden,
  nicht nur "Steuern vs. Schoten"
- **Steuern** (Tiller Extension in der rechten Hand) vs. **Schoten
  bedienen** (rechte Hand am Tauwerk/Traveller) — unterschiedliche
  Grundhaltung des Handgelenks
- **Backbord- vs. Steuerbord-Kurs** — die rechte Hand ist je nach Richtung
  vorne oder hinten im Boot, andere Körper-/Armhaltung

**Besonders wichtig als Fehlalarm-Kandidat:** Laut Handbuch wird bei der
Wende die Tiller Extension explizit **"around the back of the boat"**
geführt, bevor die Hand wechselt (`5.4 Tacking`) — eine große, schwungvolle
Armbewegung. Das MUSS als eigener Fehlalarm-Testfall geprüft werden, nicht
nur "normales Segeln" — sensorisch ist das eine der wahrscheinlichsten
Quellen für eine fälschlich erkannte Geste, da es ebenfalls eine bewusste,
große Handgelenk-/Armbewegung ist. Gleiches gilt für den Handwechsel bei
der Halse (`5.5 Gybing`).

Das ergibt folgende Kombinations-Matrix für Training UND Fehlalarm-Test:

| Zustand | Kurs | Trainings-Wiederholungen (Ja + Nein) |
|---|---|---|
| Trapez, steuernd | Backbord | 2-3 je Geste |
| Trapez, steuernd | Steuerbord | 2-3 je Geste |
| Im Boot sitzend, steuernd | Backbord | 2 je Geste |
| Im Boot sitzend, steuernd | Steuerbord | 2 je Geste |
| Schoten bedienen (Trapez oder sitzend) | Backbord | 2 je Geste |
| Schoten bedienen (Trapez oder sitzend) | Steuerbord | 2 je Geste |

Zusätzlich als **separate Fehlalarm-Testfälle** (keine Trainings-Samples,
nur Prüfen ob fälschlich erkannt wird):
- Wende komplett durchführen (inkl. Tiller-Extension-Handwechsel um den
  Rücken), mehrfach wiederholen, Debug-Log mitzählen
- Halse komplett durchführen, gleiches Vorgehen
- Ein-/Aushaken am Trapez (ebenfalls eine bewusste Handbewegung Richtung
  Trapezhaken)

Macht in Summe ca. 12-16 Trainings-Wiederholungen pro Geste (Ja/Nein
getrennt) plus die drei zusätzlichen Fehlalarm-Testfälle.

**Praktischer Hinweis für den Ablauf auf dem Wasser:** Am effizientesten
vermutlich in Blöcken je Zustand (z.B. 5 Minuten Trapez/Backbord/steuernd:
Trainings-Wiederholungen + anschließend kurzer Fehlalarm-Test in genau
dieser Haltung, dann zur nächsten Kombination wechseln), Wende/Halse-
Fehlalarmtests am Ende separat als eigener Block.

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
- [ ] Wie das Serial-Kommando/die Ablaufsteuerung genau aussieht (z.B.
  `TRAIN JA`/`TRAIN NEIN` eintippen, oder Knopfdruck-Sequenz als Alternative
  ohne USB-Kabel während der Session)
- [ ] Debug-Logging (`[Gesten]`-Präfix, schon vorhanden) um Klio-Konfidenz-
  Werte ergänzen, damit der Fehlalarm-Test in Abschnitt 3 auswertbar ist

## 5. Warum nicht einfach "mehr Schwellenwerte austesten"
Das war der gestrige Ansatz und genau daran ist die Kalibrierung
hängengeblieben (nur eine Schreibtisch-Messung, Schütteln/Nein nie
verifiziert). Klio löst das grundsätzlich anders: es lernt aus echten
Bewegungsmustern statt dass wir Zahlen raten — dafür muss aber das Training
selbst unter echten Bedingungen (Wasser, nicht Schreibtisch) passieren,
sonst reproduziert man denselben Fehler nur mit mehr Aufwand.
