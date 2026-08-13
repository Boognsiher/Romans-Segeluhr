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

## 5a. Ergänzung 12.08.2026: On-Watch-Training (kein USB/Laptop mehr nötig)

Roman-Entscheidung: das Klio-Training soll durchgeführt werden — dabei
aufgefallen, dass der bisherige Weg (Serial-Kommandos am seriellen
Monitor) auf einem Einhand-Trapez-Skiff kaum praktikabel ist, ein Laptop
lässt sich beim aktiven Segeln/Trapezieren nicht sinnvoll USB-verbunden
halten. Die Serial-Kommandos (Abschnitt 2/4) bleiben als Desktop-Fallback
bestehen, zusätzlich jetzt im Menü-Tab der Uhr:

- **Neue Sektion "Gesten-Training (Klio)"**: Status-Zeile ("Ja: trainiert/
  nicht trainiert · Nein: ...") + vier Buttons ("Ja trainieren", "Nein
  trainieren", "Ja zurücksetzen", "Nein zurücksetzen") — rufen dieselben
  internen Funktionen wie die Serial-Kommandos auf
  (`startGestureTraining()`/`resetGesturePattern()`).
- **Fortschritt läuft jetzt auch auf dem Bildschirm mit**: `lv_msgbox`
  (gleiches Muster wie die Wettfahrt-Stopp-Rückfrage) zeigt Titel
  ("Training: JA"/"Training: NEIN"), einen live aktualisierten
  Fortschrittstext (0–100 %, bzw. Hinweise wie "Bewegung nicht
  gleichmässig genug" / "zu wenig Bewegung" aus `onKlioLearningEvent()`)
  und einen "Abbrechen"-Button. Schliesst sich automatisch bei Erfolg
  (kurzes Overlay "JA trainiert!"/"NEIN trainiert!") oder Fehlschlag.
- Läuft komplett synchron im `loop()`-Task (wie `gestureTick()` selbst
  schon direkte LVGL-Aufrufe macht) — kein Flag-Umweg wie bei den
  NimBLE-Callbacks nötig.
- **Ergänzt (Roman-Nachfrage "zeigt die Uhr die Tätigkeiten an?" — bisher
  nein):** die Dialogbox zeigt jetzt zusätzlich, GROSS über dem
  Klio-Fortschrittstext, die aktuelle Haltung aus dem Kalibrierungs-
  Protokoll (Abschnitt 3, Tabelle 1:1 übernommen, z.B. "3/6: Im Boot
  sitzend, steuernd — Backbord, 2x wiederholen"). Neuer Footer-Button
  "Nächste Haltung" blättert manuell weiter (Wraparound nach 6/6) — **kein**
  Start/Stop pro Haltung, wechselt nur den Anzeigetext, das Klio-Training
  selbst läuft laut Befund unten als eine durchgehende Session weiter.
  Die Fehlalarm-Tests (Wende/Halse/Trapez-Hook) sind bewusst NICHT als
  Schritte hier drin — die passieren erst NACH abgeschlossenem Training,
  während normalem Segeln mit aktiver Erkennung, nicht während der
  Trainings-Box selbst.

**Wichtiger, beim Umsetzen gefundener Punkt zur Kalibrierungs-Protokoll-
Tauglichkeit (Abschnitt 3):** laut Bosch-Beispielsketch
(`SensorLib/examples/BHI260AP_Klio_Selflearning`) ist ein
`klio.learning()`-Aufruf **eine einzige durchgehende Session**, die endet,
sobald Klio selbst "genug gelernt" meldet — jeder erneute
`startGestureTraining()`-Aufruf setzt `learning_reset=true` und
**überschreibt** das vorher gespeicherte Muster beim Abschluss komplett
(`finalizeGestureTraining()`). Es gibt in der SensorLib-API keinen
erkennbaren Weg, mehrere spätere Trainingsläufe zu einem gemeinsamen
Muster zusammenzuführen. Das in Abschnitt 3 geplante Protokoll (6 Haltungen
× 2–3 Wiederholungen) müsste demnach als **eine einzige, durchgehende
Session** gefahren werden — Haltung wechseln, während "Ja trainieren"
aktiv bleibt, nicht 6 separate Button-Drücke. Deshalb `TRAINING_TIMEOUT_MS`
von 60s auf 5 Minuten angehoben (der neue "Abbrechen"-Button auf der Uhr
deckt das gewollte vorzeitige Beenden ab, der Timeout ist nur noch
Sicherheitsnetz). **Nicht auf Hardware verifiziert**, ob Klio einen langen
Multi-Haltungs-Lauf tatsächlich sauber durchhält (Speicherlimit, interne
Session-Dauer-Grenzen o.ä. sind aus der API nicht ersichtlich) — beim
nächsten Training genau beobachten, ob die Fortschrittsanzeige während der
Haltungswechsel weiterläuft.

## 5c. Bugfix 13.08.2026: Klio-Firmware-Upload schlug immer fehl ("Bad Header CRC")

Beim Schreibtisch-Test (Checkliste 2g): "Ja trainieren" auf der Uhr ergab
sofort "Klio nicht verfügbar". Per Serial-Log (`TRAIN JA` manuell
gesendet) gefunden:

```
[Klio] Firmware-Upload fehlgeschlagen (API:[API Success]
Sensor:[Sensor error] Bootloader reports: Firmware Upload Failed: Bad Header CRC)
```

**Ursache**: `setupGestureSensor()` läuft NACH `instance.begin()` (LilyGoLib
hat da bereits die GPIO-Firmware erfolgreich hochgeladen und der BHI260-Chip
führt sie schon aus) und rief bisher direkt
`instance.sensor.uploadFirmware(klio_image, ...)` auf, um die Klio-Firmware
nachzuladen. Der ROM-Bootloader des Chips nimmt aber nur **direkt nach
einem `bhy2_soft_reset()`** neue Firmware-Bytes an — läuft schon eine App,
interpretiert diese die Upload-Bytes stattdessen als Datenmüll, daher der
CRC-Fehler. `SensorBHI260AP::initImpl()` (SensorLib, per Quellcode
verifiziert) macht diesen `bhy2_soft_reset()` beim allerersten `begin()`
automatisch VOR dem Firmware-Upload — genau dieser Schritt fehlte beim
nachträglichen Firmware-Wechsel auf Klio.

**Fix**: `setFirmware()` + erneutes `begin()` statt direktem
`uploadFirmware()` — `begin()`/`initImpl()` ruft `bhy2_soft_reset()` intern
auf, danach erst den Firmware-Upload. Ein zweiter `begin()`-Aufruf auf
demselben Sensor-Objekt ist laut SensorLib-Quelle sicher (`comm`/`hal`/
`_bhy2` sind `unique_ptr`, werden bei Neuzuweisung automatisch sauber
ersetzt, kein Leak).

**Verifiziert per Serial (13.08.2026 abends)**: Boot-Log zeigt jetzt
`[Klio] Online, max. 25 Muster gleichzeitig moeglich.` statt des
Fehlschlags, `TRAIN JA` startet ein Training sauber (`=== Training 'JA'
gestartet ===`), `TRAIN CANCEL` bricht es sauber ab.

**Update**: `ACCEL_PASSTHROUGH`/Quaternion laufen nach dem Firmware-Wechsel
weiterhin sauber (`[Gesten] Pitch=...`-Debug-Zeilen bestätigt live plausibel,
mehrfach beobachtet inkl. starker Bewegung).

### Zweiter Bug in derselben Session: `k_state`-Software-Cache verhinderte den Learning-Callback komplett

Nach obigem Fix startete das Training zwar ("Klio nicht verfügbar" war weg),
aber der Fortschrittstext im On-Watch-Dialog änderte sich nie, auch nicht
bei eindeutiger, kräftiger Bewegung (Serial-Test mit `TRAIN JA` + aktivem
Handgelenk-Bewegen, 16s, keine einzige `[Klio]`-Lernzeile).

**Ursache** (per Quellcode-Analyse `SensorBHI260AP_Klio.cpp` gefunden):
`klio_call_local()` (der interne FIFO-Dispatch-Handler, der am Ende
`learning_callback(...)` aufruft) prüft VOR dem Aufruf zusätzlich ein
privates Member `k_state.learning_enabled` — ein reiner
Software-Cache in der `SensorBHI260AP_Klio`-Objektinstanz, unabhängig vom
tatsächlichen Sensor-Zustand. Die im Code bisher genutzte 4-Parameter-
Überladung `klio.setState(learning_enable, learning_reset,
recognition_enable, recognition_reset)` schreibt korrekt auf den Chip
(`bhy2_klio_set_state()`), aktualisiert aber NICHT dieses lokale
`k_state`-Feld — nur die Komfort-Methode `klio.learning()` tut das
(`k_state = getState(); k_state.learning_enabled = true; setState(k_state);`).
Ohne zusätzlichen `learning()`-Aufruf bleibt der Callback für immer
unterdrückt, obwohl Klio auf dem Sensor selbst korrekt lernt.

**Fix**: `klio.learning()` zusätzlich nach dem bestehenden `setState()`-Aufruf
in `startGestureTraining()`. **Verifiziert per Live-Rücklese vom Chip**
(nicht nur Software-Cache): `klio.getState()` direkt nach dem Fix zeigt
`learning_enabled=1, error=""` — der Sensor bestätigt selbst, dass er lernt.

### Dritter, noch NICHT gelöster Befund: Learning-Callback feuert trotzdem nie

Trotz beider obiger Fixes (Firmware korrekt geladen, `k_state` korrekt
synchronisiert, Chip bestätigt per Live-Readback `learning_enabled=1`) kam
bei mehreren Bewegungstests (bis zu 16s, teils sehr kräftige Bewegung,
Accel-Ausschläge >2g) **keine einzige** `[Klio]`-Lernzeile
(`Trainingsfortschritt`/`nicht wiederholend genug`/`zu wenig Bewegung`) im
Serial-Log an. Geprüft und ausgeschlossen:
- Sensor-ID-Mapping (`KLIO=112`) stimmt exakt mit `BHY2_SENSOR_ID_KLIO`
  überein.
- `bhy2_is_sensor_available(112)` müsste laut Codepfad in `klio.begin()`
  (das `true` zurückgab, siehe Boot-Log "Online, max. 25 Muster...") schon
  bestanden haben.
- Callback-Registrierung (`onResultEvent()` → `_callback_manager.add()`)
  strukturell korrekt, kein offensichtlicher Overwrite-Bug gefunden.
- Reihenfolge `enable()` vor/nach `learning()` getestet (beide Varianten,
  kein Unterschied).

**Nicht weiter verfolgt** (13.08.2026, bewusst zurückgestellt): Ursache
könnte in einem separaten Wake-/Non-Wake-FIFO-Pfad liegen (Klio könnte auf
eine FIFO schreiben, die der bestehende Interrupt-/`sensor.update()`-Pfad
nicht mit ausliest) oder eine Bewegungs-Qualitätsanforderung sein, die auch
kräftiges Bewegen nicht erfüllt (Klio braucht ggf. eine sehr gleichmäßig
WIEDERHOLTE Bewegung, kein einmaliges Schütteln). Der Debug-Print in
`startGestureTraining()` (`[Klio] DEBUG: learning() ok=...`) bleibt bewusst
im Code, bis der eigentliche Callback-Fund gemacht ist. **Nächster Schritt
beim Wassertest**: mit mehr Zeit eine saubere, langsam-gleichmäßige
Wiederholbewegung testen (statt kräftigem Schütteln) und beobachten, ob
sich das Verhalten ändert.

## 5. Warum nicht einfach "mehr Schwellenwerte austesten"
Das war der gestrige Ansatz und genau daran ist die Kalibrierung
hängengeblieben (nur eine Schreibtisch-Messung, Schütteln/Nein nie
verifiziert). Klio löst das grundsätzlich anders: es lernt aus echten
Bewegungsmustern statt dass wir Zahlen raten — dafür muss aber das Training
selbst unter echten Bedingungen (Wasser, nicht Schreibtisch) passieren,
sonst reproduziert man denselben Fehler nur mit mehr Aufwand.

## 5b. Ergänzung 13.08.2026: Hintergrundbetrieb, automatisches Training aus
## Wind-/Manöver-Zustand, mehrere Trainings-Durchläufe kombinieren

Drei Roman-Fragen vom Abend nach dem Hardware-Test, alle noch OHNE
Wassertest — hier nur die Einordnung, kein neuer Hardware-Befund.

### Läuft Klio schon im Hintergrund?
**Ja, bereits heute.** `klio.recognition()` (siehe `restoreRecognitionAfterTraining()`)
läuft nach dem Training dauerhaft auf dem BHI260AP-Sensor-Chip selbst — das
ist ein eigener Co-Prozessor, getrennt von der ESP32-Haupt-CPU, genau für
diesen Zweck gebaut (Always-on-Mustererkennung ohne Strom-/CPU-Last auf dem
Hauptprozessor). `onKlioRecognitionEvent()` feuert laufend, wir **reagieren**
darauf nur, wenn `haveIncomingQuestion`/`raceData.roundingConfirmPending`
gesetzt ist — das ist ein reines App-Level-Gate, kein Sensor-Zustand. Keine
Stromkosten-Frage, das läuft bereits.

### Automatisches Training aus abgeleitetem Zustand (Wind/Manöver)?
Geprüft und **bewusst nicht umgesetzt** für die Beispiele "Amwind/Vor dem
Wind" und "Manöver läuft" — Klios Lern-API kennt kein "im Hintergrund gegen
ein externes Label trainieren", nur einen expliziten Start/Stopp-Lernlauf
mit fester Pattern-ID. Man KÖNNTE `startGestureTraining()` programmatisch
aus `WindEngine`/`CompetitionEngine`-Zustandswechseln auslösen — bringt aber
für genau diese beiden Beispiele nichts: Amwind/Vorwind und Manöver-Zustand
werden schon zuverlässig und günstig aus GPS+Windwinkel berechnet, ein
zweites (unzuverlässigeres, weil aus verrauschter Handgelenksbewegung
gelerntes) Klio-Signal für dieselbe Information wäre bestenfalls redundant,
schlimmstenfalls widersprüchlich. Zusätzliches Problem: die abgeleiteten
Zustände sind selbst Schätzungen (kein Ground Truth) — automatisches
Training dagegen würde deren Fehler nur reproduzieren, ohne die Korrektur,
die ein Mensch bei einer bewussten Trainings-Session liefert.

**Wo die Idee stattdessen Sinn ergäbe:** für Dinge, die aus GPS/Wind
grundsätzlich NICHT ableitbar sind, weil sie reine Körperhaltung/Bewegung
sind — "bin ich gerade am Trapez", "gerade mitten im Tiller-Extension-
Handwechsel bei der Wende" (bevor der GPS-Kurs die Wende überhaupt zeigt).
Das deckt sich mit der bereits in Abschnitt 3 als Fehlalarm-Risiko
identifizierten Wende-/Halse-/Trapez-Bewegung — als eigene, bewusst
trainierte Klio-Patterns (nicht automatisch aus Wind/GPS abgeleitet)
künftig denkbar, siehe "Spätere Idee" unten. **Nicht jetzt gebaut** — erst
das bestehende 2-Pattern-System (JA/NEIN) echt auf dem Wasser verifizieren.

### Mehrere Trainings-Durchläufe kombinieren (z.B. erst an Land, später auf dem Wasser)?
Bisher überschrieb jeder neue `startGestureTraining()`-Aufruf das
gespeicherte Muster komplett (`learning_reset` war hart auf `true` gesetzt).
**13.08.2026 geändert:** `learning_reset` ist jetzt `false`, sobald für die
jeweilige Geste (JA/NEIN getrennt) bereits ein Muster existiert — nur beim
allerersten Training bzw. nach explizitem "X zurücksetzen" bleibt es `true`.
Damit liesse sich z.B. erst ein einfaches Training an Land fahren und
später ein zweites, ergänzendes auf dem Wasser in echten Segel-Situationen,
ohne das erste zu verwerfen.

**Wichtig — das ist ein Experiment, kein bestätigtes Verhalten:** Boschs
eigene Doku zu `learning_reset` ist auf einen einzigen Satz beschränkt
("0 - nop, 1 - reset learning", `bhy2_klio_defs.h`). Daraus geht NICHT
hervor, ob `reset=false` wirklich an ein bereits per `writePattern()`
abgeschlossenes und zwischenzeitlich in den NVS-Flash geschriebenes Muster
anknüpft, oder nur eine einzelne, ununterbrochene Session fortsetzen kann.
**Verifikationsplan für den nächsten Test:** einmal ein kurzes Training an
Land fahren (z.B. nur "im Stehen"-Wiederholungen), Muster testen
(Erkennungstest laut Serial-Log), dann ein zweites Training für dieselbe
Geste starten (jetzt mit `reset=false`, sichtbar am Serial-Log
"ERWEITERT bestehendes Muster") mit anderen/neuen Wiederholungen, danach
erneut beide Bewegungsarten testen. Erkennt Klio jetzt beide zuverlässig,
funktioniert das Kombinieren wie erhofft — erkennt es nur noch die zweite
Charge, verhält sich `reset=false` effektiv wie ein normaler Neustart und
wir fallen zurück auf den in Abschnitt 5a beschriebenen Weg (eine einzige
durchgehende Session über alle gewünschten Situationen hinweg).

### Spätere Idee (nicht jetzt umsetzen)
Eigene Klio-Patterns für Wende-Handwechsel, Halse-Handwechsel und
Trapez-Ein-/Aushaken — würde die bestehende 20s-Gestensperre nach einem
Manöver-Vorschlag (siehe `MANEUVER_GESTURE_SUPPRESS_MS`) um eine
kontextabhängige Erkennung ergänzen, die auch bei spontanen Manövern ausserhalb
eines aktiven Wettfahrt-Kontexts greifen würde. Erst sinnvoll zu bewerten,
nachdem das bestehende JA/NEIN-Training auf dem Wasser gelaufen ist und wir
wissen, wie gross das Fehlalarm-Problem in der Praxis tatsächlich ist.
