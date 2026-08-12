# Übersicht: Gestensteuerung (Stand 12.08.2026)

> Analyse-Doku, keine neue Erweiterung — auf Roman-Bitte erstellt, um vor
> einer Überarbeitung erst den IST-Zustand klarzuhaben. Bezieht sich
> ausschliesslich auf die **T-Watch Ultra** (Boot) — die **T-Watch S3**
> (Land) hat **keine** Gestensteuerung, siehe Abschnitt 5.

## 1. Wofür Gesten überhaupt da sind

Zwei Antwortwege für **genau zwei mögliche Fragen an den Segler**, beide
brauchen nur Ja/Nein:

| Auslöser | Frage sichtbar als | Ja-Geste macht | Nein-Geste macht |
|---|---|---|---|
| Eingehende Quick-Message von der Land-Uhr (`haveIncomingQuestion`) | Overlay "ALLES GUT?" o.ä. | `sendQuickAnswer(JA)` | `sendQuickAnswer(NEIN)` |
| Bojen-/Marken-Rundung unklar (`raceData.roundingConfirmPending`) | Manöver-Tab "Boje hier?" | `CMD_CONFIRM_BUOY_ROUNDING` | `CMD_REJECT_BUOY_ROUNDING` |

Bewusste Design-Entscheidung (Roman, 10.08.): **dieselben zwei Gesten für
beide Fragen wiederverwendet**, keine zweite Geste erfunden ("Geste statt
Haptik"). Sind zufällig BEIDE gleichzeitig offen (laut Code-Kommentar
"unwahrscheinlich"), gewinnt die Quick-Message.

**Wichtigstes Sicherheitsmerkmal:** Gesten werden NUR ausgewertet, solange
eine der beiden Fragen offen ist (`haveIncomingQuestion ||
raceData.roundingConfirmPending`). Ausserhalb davon läuft der Sensor zwar
mit, aber jede erkannte Bewegung wird verworfen — sonst würde normales
Segeln (Schoten holen, Krängung, Wellenschlag) dauernd als Antwort
fehlinterpretiert.

## 2. Zwei parallele Erkennungswege, pro Geste unabhängig umschaltbar

Es gibt **nicht nur eine** Gestenerkennung, sondern zwei komplett
verschiedene, die sich pro Geste (Ja/Nein getrennt!) automatisch ablösen:

```
                    ┌─ Klio-Muster für diese Geste trainiert? ─┐
                    │                                           │
                   NEIN                                        JA
                    │                                           │
                    ▼                                           ▼
        Schwellenwert-Fallback                    Klio (BHI260AP, on-chip
        (Pitch-Winkel / Accel-Schwelle,             selbstlernender Mustererkenner)
         siehe Abschnitt 3)                         siehe Abschnitt 4
```

`klioPatternTrained[0]` (Ja) / `klioPatternTrained[1]` (Nein) — sobald für
eine Geste ein Klio-Muster im NVS gespeichert ist, übernimmt Klio
**komplett** für genau diese Geste, der Fallback wird für sie stillgelegt
(kein Doppel-Trigger). Die andere Geste kann parallel noch auf dem
Fallback laufen.

## 3. Schwellenwert-Fallback — der Ist-Zustand, mit dem heute vermutlich noch geflogen wird

| Geste | Sensor | Bedingung | Konstante | Wert |
|---|---|---|---|---|
| Ja (Tilt) | BHI260AP-Quaternion → Pitch | `\|pitch − Ziel\| ≤ Toleranz` | `GESTURE_TILT_TARGET_ANGLE_DEG` | **−30°** |
| | | | `GESTURE_TILT_TOLERANCE_DEG` | **±20°** |
| Nein (Shake) | BHI260AP-Beschleunigung X | ≥2 Vorzeichenwechsel über der Amplitude innerhalb 800ms | `GESTURE_SHAKE_MIN_AMPLITUDE` | **8.0 m/s²** |
| | | | `GESTURE_SHAKE_MIN_REVERSALS` / `_WINDOW_MS` | 2 / 800ms |

**Das ist der eigentliche Kern, warum das noch überarbeitet werden muss:**
diese Werte stammen laut `docs/Erweiterung_Gesten_Training_Klio.md` von
**einer einzigen Schreibtisch-Messung** (05.08.) — und selbst die ist mit
Vorbehalt zu sehen: an dem Tag war `USING_BHI260_SENSOR` noch gar nicht
gesetzt, der Gestencode lief also nur als Stub (Bug, 06.08. gefunden +
gefixt). Der Pitch-Wert "−30° beim Hochschauen" kann demnach **nicht** aus
echt laufendem Sensor-Code stammen. Seitdem (`PROJEKT_STATUS.md`, Zeile
"Gesten-Kalibrierung weiterhin offen") **unverändert** — kein Wassertest
hat diese Zahlen je bestätigt oder korrigiert.

Der Code selbst hat dafür schon eingebautes Debug-Logging
(`[Gesten] Pitch=... Roll=... Heading=...` bzw. `[Gesten] Accel X=...`,
1x/s über `GESTURE_LOG_INTERVAL_MS`), bewusst **ausserhalb** des
Frage-offen-Gates, damit man die Rohwerte auch ohne offene Frage live am
seriellen Monitor beobachten kann — ausgelöst wird trotzdem nur, wenn
tatsächlich eine Frage ansteht. D.h. das Werkzeug zur richtigen
Kalibrierung existiert schon, wurde nur noch nie mit echten Wasser-Daten
gefüttert.

## 4. Klio — gebaut, aber vermutlich noch nie trainiert

Bosch-Algorithmus, läuft direkt auf dem BHI260AP-Chip, lernt Bewegungsmuster
statt fester Schwellenwerte.

**12.08.2026 ergänzt** (Roman-Entscheidung: Training wird durchgeführt):
bisher ging Trainieren **nur** über den seriellen Monitor bei USB-
Verbindung — auf einem Einhand-Trapez-Skiff kaum praktikabel. Jetzt gibt es
zusätzlich eine On-Watch-Variante im Menü-Tab (Buttons + `lv_msgbox`-
Fortschrittsanzeige, kein Laptop mehr nötig), siehe
`docs/Erweiterung_Gesten_Training_Klio.md` Abschnitt 5a. Dabei auch ein
wichtiger, noch unbestätigter Befund zur Protokoll-Tauglichkeit: laut
Bosch-Beispielsketch überschreibt jeder neue Trainingslauf das vorherige
Muster komplett — das geplante 6-Haltungen-Protokoll (Abschnitt 3) müsste
also als EINE durchgehende Session gefahren werden, nicht als 6 einzelne
Trainingsläufe. Serial-Kommandos bleiben als Desktop-Fallback bestehen:

- `TRAIN JA` / `TRAIN NEIN` startet einen Trainingslauf, Firmware fordert
  über Serial zu Wiederholungen auf, meldet Fortschritt 0–100 %.
- Nach Abschluss: Pattern wird sofort ins NVS geschrieben (`Preferences`,
  Namespace `klio`), übersteht Neustart, wird bei jedem Boot per
  `restoreKlioPatterns()` zurück in den Sensor geladen.
- `docs/Erweiterung_Gesten_Training_Klio.md` Abschnitt 3 definiert dafür
  ein sehr detailliertes Kalibrierungs-Protokoll speziell für den Musto
  Skiff (Trapez/sitzend × Steuern/Schoten × Backbord/Steuerbord = 6
  Zustände × 2–3 Wiederholungen je Geste, **plus** explizite
  Fehlalarm-Tests für Wende/Halse-Handwechsel und Trapez-Ein-/Aushaken) —
  in Summe **12–16 Wiederholungen pro Geste**.

**Kein Hinweis in `PROJEKT_STATUS.md`, dass dieser Kalibrierungslauf je
stattgefunden hat.** Die letzten Einträge zur vereinheitlichten
Bojenerkennung (die genau diese Gesten für die Rundungs-Rückfrage nutzt)
listen "funktionaler Test (Banner + Tilt/Shake-Geste + Taster-Fallback)
noch offen" — unverändert seit 10./11.08. Sehr wahrscheinlich läuft die
Uhr also aktuell komplett auf dem unkalibrierten Fallback aus Abschnitt 3,
nicht auf Klio.

**Zusätzliches offenes technisches Risiko** (Doku Abschnitt 4, Punkt 2):
LilyGoLib lädt für die T-Watch Ultra standardmässig eine BHI260-Firmware
*ohne* Klio-Support — die Klio-fähige Variante wird erst nach `instance.begin()`
per `uploadFirmware()` nachgeladen. **Noch nicht auf Hardware verifiziert**,
ob dieser Firmware-Tausch die für den Fallback nötigen
Passthrough-/Quaternion-Sensoren weiterhin liefert. Sollte laut
Bosch-Doku funktionieren, ist aber unbestätigt — im Zweifel könnte der
Fallback durch den Firmware-Swap selbst beeinträchtigt sein.

## 5. Asymmetrie: S3 (Land) hat keine Gesten

Die Land-Uhr beantwortet Fragen **nur per Taster** (kurz = Ja, lang =
Nein) — kein Klio, keine Shake-Erkennung. Der BMA423-Sensor der S3 wird
nur für eine fertige Hardware-Tilt-Funktion genutzt
(`enableTiltIRQ()`/`isTilt()`), und die ausschliesslich zum
**Display-Aufwecken**, nicht als Ja/Nein-Eingabe. Das ist stimmig, weil die
S3 typischerweise nicht am Handgelenk aktiv "bewegt" bedient wird wie die
Ultra am Boot — aber falls das nicht mehr die Annahme ist, wäre das ein
eigener Punkt.

## 5a. Ergänzung 12.08.2026: drei Fehlalarm-Schutz-Bausteine umgesetzt

Roman-Frage: "welche Möglichkeiten haben wir, die Gestenerkennung zu
erweitern — hauptsächlich gegen falsche Antworten, die ich generieren
könnte?" Umgesetzt (kompiliert, nicht auf Hardware verifiziert):

1. **Bestätigungsfenster nach dem Senden**: eine per Geste erkannte
   Antwort wird nicht mehr sofort gesendet, sondern 3s lang als
   "Antwort: JA/NEIN" eingeblendet (`queuePendingAnswer()`/
   `pendingAnswerTick()`). Antippen der Anzeige ODER ein beliebiger
   Tastendruck währenddessen bricht ab. **Nur** für den Gesten-Pfad — der
   Taster-Fallback bleibt bewusst sofort/ungedrosselt.
2. **Klios Konfidenz-/Wiederholungswert genutzt**: `onKlioRecognitionEvent()`
   ignoriert jetzt Events mit `count < KLIO_MIN_RECOGNITION_COUNT` (Platzhalter
   2.0, TODO kalibrieren) statt wie bisher auf jedes Event zu reagieren.
3. **Gesten-Sperre um Wende/Halse**: ab dem `HAPTIC_MANEUVER_CMD`-Trigger
   (Wende-/Halse-Vorschlag) wird die Gestenauswertung für
   `MANEUVER_GESTURE_SUPPRESS_MS` (Platzhalter 20s, TODO kalibrieren)
   ausgesetzt — deckt den in Abschnitt 3 der Klio-Doku als größtes Risiko
   benannten Tiller-Extension-Handwechsel "around the back" ab.

Nebenbei den in Abschnitt 6 dokumentierten toten Code
(`GESTURE_DEBOUNCE_SAMPLES`) aktiviert: der Fallback-Tilt-Pfad (bisher
löste ein EINZELNER Sample über der Schwelle sofort aus) verlangt jetzt
so viele aufeinanderfolgende Samples, bevor er auslöst.

**Nicht umgesetzt** (Roman-Wahl): Doppel-Geste-Bestätigung — als
Alternative zum Bestätigungsfenster verworfen, würde zusätzliche Reibung
bei jeder echten Antwort bedeuten.

## 6. Sonstige Fundstellen beim Durchgehen

- ~~**`GESTURE_DEBOUNCE_SAMPLES` (in `QuickMessages.h`, Wert 2) wird nirgends
  im Code verwendet**~~ — 12.08. behoben, siehe 5a Punkt 3 (Fallback-Tilt-Pfad).
- ~~**Klio-Konfidenz ungenutzt**~~ — 12.08. behoben, siehe 5a Punkt 2.
- Die Tilt-Geste wird **zweimal** verwendet: einmal gated (Ja-Antwort,
  Abschnitt 3/4) und einmal **ungated** fürs Standby-Aufwecken
  (`docs/Erweiterung_Standby_Wecken.md`) — unabhängige Code-Pfade, kein
  Konflikt, aber derselbe physische Handgriff (Handgelenk heben) macht auf
  der Ultra je nach Kontext zwei verschiedene Dinge (Display aufwecken vs.
  Frage beantworten).

## 7. Kurz zusammengefasst — was vermutlich der eigentliche Rework-Bedarf ist

1. **Der Fallback (Abschnitt 3) ist praktisch die einzige Geste, die
   heute wirklich läuft** — und die Zahlen dahinter sind nie über eine
   Schreibtisch-Schätzung hinausgekommen.
2. ~~**Klio (Abschnitt 4) ist aufwendig zu trainieren** (USB+Laptop am
   Wasser...)~~ — 12.08. entschärft: On-Watch-Training (Menü-Tab, kein
   Laptop mehr nötig). Weiterhin offen: das 6-Haltungen-Protokoll müsste
   laut neuem Befund als EINE durchgehende Session gefahren werden, nicht
   auf Hardware verifiziert, ob Klio das durchhält.
3. ~~Zwei kleinere Aufräum-Punkte (Abschnitt 6) — toter Debounce-Code,
   ungenutzte Klio-Konfidenz.~~ — 12.08. behoben, siehe Abschnitt 5a.
4. **Neu, noch offen**: alle drei Fehlalarm-Schutz-Bausteine aus 5a haben
   Platzhalter-Werte (3s Bestätigungsfenster, `count`-Schwelle 2.0, 20s
   Wende/Halse-Sperre) — keiner davon ist kalibriert, das kommt erst mit
   dem nächsten echten Wassertest.

Sag mir, in welche Richtung du weiter willst (z.B. "erstmal nur den
Fallback mit echten Logs kalibrieren, Klio hintanstellen" oder "Klio-
Training diesen Sommer noch durchziehen") — dann plane ich das konkret.
