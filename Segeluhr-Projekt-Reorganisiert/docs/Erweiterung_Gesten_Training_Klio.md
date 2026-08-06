# Erweiterung: Trainierbare Gestenerkennung (Klio, auf Wasser kalibriert)

> Nicht in der ursprünglichen Spezifikation. Baut auf dem Befund von gestern
> auf: `GESTURE_TILT_TARGET_ANGLE_DEG`/`GESTURE_SHAKE_MIN_AMPLITUDE` beruhten
> auf einer einzigen Schreibtisch-Messung, nicht auf echten Segelbedingungen.

## Status: 🔧 UMGESETZT, KOMPILIERT (06.08.2026) — noch nicht auf Hardware
getestet. Serial-Kommando-Training (Abschnitt 2/4) implementiert und gegen
den echten SensorLib-Quelltext geprüft, siehe Abschnitt 6 (Technische
Umsetzung, aktualisiert) für Details und zwei dabei gefundene, für den
nächsten Hardware-Test wichtige Punkte.

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

## 4. Technische Umsetzung (implementiert, `Segeluhr_TWatch_Ultra.ino`)

Echte API aus `SensorLib` (Klasse `SensorBHI260AP_Klio`, geprüft gegen
`src/SensorBHI260AP_Klio.{hpp,cpp}` und die Beispiele
`BHI260AP_Klio_{Recognition,Selflearning}`), kein Pseudocode mehr:

```cpp
// Klio-Pattern-IDs (bewusst uint8_t-Konstanten statt enum class, siehe
// Kommentar im Code - Arduino-Prototyp-Generierung bricht sonst)
static const uint8_t GESTURE_ID_JA = 1;
static const uint8_t GESTURE_ID_NEIN = 2;

startGestureTraining(GESTURE_ID_JA)  // -> klio.setState(learning=true, reset=true, recognition=false, ...)
onKlioLearningEvent(...)             // Callback: Fortschritt 0-100%, bei Abschluss -> finalizeGestureTraining()
finalizeGestureTraining(learnIndex)  // -> klio.getLearnPattern() + klio.writePattern(id,...) + NVS-Persistenz
onKlioRecognitionEvent(pattern_id, count, ...) // laufende Erkennung, gated hinter haveIncomingQuestion
```

Kein "recordTrainingSample()" nötig, wie in der ersten (Pseudocode-)Version
dieser Doku vermutet: Klio erkennt Wiederholungen selbst aus dem laufenden
Sensor-Datenstrom während `learning()` aktiv ist - der Trainingsfortschritt
(0-100%) kommt automatisch über den Learning-Callback, keine manuelle
"Sample jetzt aufnehmen"-Aktion pro Wiederholung nötig.

### Serial-Kommandos (siehe Abschnitt 2)
`TRAIN JA` / `TRAIN NEIN` (Training starten), `TRAIN CANCEL` (abbrechen,
auch automatisch nach 60s ohne Ergebnis), `TRAIN STATUS` (aktueller Stand),
`TRAIN RESET JA` / `TRAIN RESET NEIN` (gespeichertes Muster löschen, neu
trainieren). Serieller Monitor bei 115200 Baud.

### Persistenz
Klio vergisst gelernte Muster bei Stromverlust (laufen im RAM des
BHI260AP-Sensorchips) - Rohdaten (`klio.getLearnPattern()`, max. 252 Byte)
werden deshalb zusätzlich per `Preferences` (ESP32-NVS, Namespace `klio`,
Keys `ja`/`nein`) auf dem ESP32 selbst abgelegt und bei jedem Boot per
`klio.writePattern()` zurück in den Sensor geschrieben (`restoreKlioPatterns()`).

### Zusammenspiel mit dem Schwellenwert-Fallback
Pro Geste (JA/NEIN) unabhängig: solange für eine Geste noch KEIN
Klio-Muster gespeichert ist, bleibt der alte Schwellenwert-Code
(`GESTURE_TILT_TARGET_ANGLE_DEG`/`GESTURE_SHAKE_MIN_AMPLITUDE`) für genau
diese Geste aktiv. Sobald trainiert, übernimmt Klio komplett (kein
Doppel-Trigger) - kein globaler Umschalter, jede Geste einzeln.

### Gefundene Probleme beim Umsetzen (wichtig für den nächsten Hardware-Test)

1. **`USING_BHI260_SENSOR` war nie definiert.** Der komplette
   Gesten-/BHI260-Code stand zwar schon im Repo, aber ohne dieses Compile-Gate
   (das laut LilyGoLib-Beispielen vom Sketch selbst gesetzt werden muss, nicht
   automatisch vom Board-Paket) wurden ausschließlich Stub-Funktionen
   kompiliert. Der im Code dokumentierte Messwert "Pitch -30° beim
   Hochschauen" vom 05.08. kann also nicht aus dieser Firmware stammen - vor
   dem nächsten Wassertest neu verifizieren, nicht blind übernehmen. Jetzt in
   `Segeluhr_TWatch_Ultra.ino` gesetzt.
2. **LilyGoLib lädt standardmäßig NICHT die Klio-fähige BHI260-Firmware.**
   `instance.begin()` lädt für die T-Watch Ultra die `BOSCH_BHI260_GPIO`-
   Firmware (kein Klio-Support) - die Klio-Firmware (`BOSCH_BHI260_KLIO`)
   wird nur geladen, wenn `USING_XL9555_EXPANDS` gesetzt ist, was laut
   `boards.txt` für die T-Watch Ultra nirgends passiert. Die Firmware wird
   deshalb jetzt nach `instance.begin()` explizit per
   `instance.sensor.uploadFirmware(...)` durch die Klio-Variante ersetzt
   (`setupGestureSensor()`), BEVOR die bestehenden Passthrough-/Quaternion-
   Sensoren aktiviert werden. **Noch nicht auf Hardware verifiziert**, ob
   die Klio-Firmware weiterhin `ACCEL_PASSTHROUGH`/Quaternion unterstützt
   (für den Schwellenwert-Fallback) - sollte laut Bosch-Doku der Fall sein
   (Klio ist ein Zusatz-Algorithmus, kein Ersatz der Basis-Sensoren), aber
   ausdrücklich noch nicht am Gerät bestätigt.

### Offene technische Punkte
- [x] Exakte Klio-API in SensorLib verifiziert (siehe oben) - Pattern-Limit
  über `klio.getMaxPatterns()` zur Laufzeit abfragbar, Persistenz ist NICHT
  eingebaut (siehe "Persistenz" oben, musste manuell gebaut werden)
- [ ] Konfidenz-Schwelle: `onKlioRecognitionEvent()` bekommt `count` (laut
  SensorLib-Doku "current repetition count", keine 0-1-Konfidenz) - noch zu
  klären, ob/wie damit Fehlalarme von echten Treffern unterschieden werden
  sollen, oder ob ein einzelner Recognition-Event (unabhängig von `count`)
  bereits ausreicht (aktuell so implementiert: jeder Event triggert)
- [x] Verhältnis zum Schwellenwert-Ansatz geklärt: bleibt pro Geste
  unabhängig als Fallback aktiv, bis für genau diese Geste trainiert wurde
  (siehe oben)
- [x] Serial-Kommandos festgelegt und implementiert (siehe oben)
- [x] Debug-Logging um Klio-Ausgaben ergänzt (`[Klio]`-Präfix, inkl.
  Recognition-Events unabhängig von `haveIncomingQuestion` fürs
  Fehlalarm-Testen aus Abschnitt 3)

## 5. Warum nicht einfach "mehr Schwellenwerte austesten"
Das war der gestrige Ansatz und genau daran ist die Kalibrierung
hängengeblieben (nur eine Schreibtisch-Messung, Schütteln/Nein nie
verifiziert). Klio löst das grundsätzlich anders: es lernt aus echten
Bewegungsmustern statt dass wir Zahlen raten — dafür muss aber das Training
selbst unter echten Bedingungen (Wasser, nicht Schreibtisch) passieren,
sonst reproduziert man denselben Fehler nur mit mehr Aufwand.
