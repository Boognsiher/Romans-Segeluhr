# Erweiterung: Manöver-Performance-Tracking per IMU

> Nicht in der ursprünglichen Spezifikation. Zweitverwertung derselben
> Sensor-Pipeline (BHI260AP), die für Quick-Message-Gesten aufgebaut wird
> (siehe `Erweiterung_Gesten_Training_Klio.md`) — hier für einen anderen
> Zweck: objektive Bewegungsdaten zur Manöver-Ausführung statt bewusster
> Ja/Nein-Eingabe.

## Status: KONZEPT — Umsetzung durch Claude Code gegen den aktuellen Stand
von `TrainingEngine` und dem bestehenden Room-Manöver-Log.

## 1. Ziel
`TrainingEngine` kommandiert bereits zufällige Wende-/Halse-Manöver während
des Trainings. Bisher wird vermutlich nur erfasst, DASS ein Manöver
kommandiert wurde — nicht WIE gut/schnell es tatsächlich ausgeführt wurde.
Die IMU-Daten der Ultra (dieselbe Sensor-Basis wie für die Gesten) können
das liefern: objektives Feedback zur Manöver-Qualität, nicht nur
Kommando-Protokollierung.

## 2. Mögliche Metriken

- **Reaktionszeit**: Zeit vom Kommando ("Wende jetzt") bis zum erkannten
  Bewegungsbeginn (erste signifikante Körper-/Armbewegung)
- **Ausführungsdauer**: Zeit vom Bewegungsbeginn bis die Bewegung wieder in
  einen ruhigen/stabilen Zustand übergeht (Manöver abgeschlossen)
- **Flüssigkeit/Konsistenz**: z.B. Ruck-Metrik (Ableitung der
  Beschleunigung) während der Ausführung — ruckartige, unrunde Bewegung vs.
  fließende Bewegung, als grobes Qualitätsmaß
- **Konsistenz über die Session**: Streuung der obigen Werte über mehrere
  Manöver hinweg — nimmt die Streuung mit der Zeit ab (= wird
  routinierter)?

## 3. Technischer Ansatz

- Nutzt dieselbe IMU-Datenerfassung wie die Klio-Gestenerkennung, aber
  andere Auswertung: statt Mustervergleich gegen ein trainiertes Ja/Nein-
  Pattern, hier eine **Bewegungserkennungs-/Segmentierungs-Logik** um den
  bekannten Zeitpunkt des Manöver-Kommandos herum (TrainingEngine kennt
  diesen Zeitpunkt bereits exakt)
- Einfachster erster Ansatz: Schwellenwert-basierte Erkennung von
  "Bewegung beginnt" (Beschleunigungs-/Gyro-Werte übersteigen Ruhepegel)
  und "Bewegung endet" (Rückkehr unter Schwellenwert für X Sekunden stabil)
  — ähnlich wie die Wakeup-Interrupt-Logik aus `Erweiterung_Standby_Wecken.md`,
  nur als Zeitfenster-Messung statt einmaliger Trigger
- Fortgeschrittener (später, nicht für den Start): eigenes Klio-Pattern für
  "Wende läuft"/"Halse läuft" trainieren (ähnlich wie Ja/Nein), dann direkt
  erkennen statt nur Schwellenwerte — aber das würde die
  Kalibrierungs-Matrix aus `Erweiterung_Gesten_Training_Klio.md` nochmal
  erweitern, deshalb als spätere Ausbaustufe markiert, nicht sofort

## 4. Anbindung an bestehendes Manöver-Log

- Bestehende Room-Persistenz (Manöver-Log) um die neuen Felder erweitern
  (Reaktionszeit, Ausführungsdauer, Flüssigkeits-Metrik) statt ein
  Parallelsystem zu bauen
- Übertragung Ultra → Handy vermutlich über einen neuen oder erweiterten
  BLE-Befehl (analog zu den bestehenden `CMD_*`-Konstanten) — Ultra sendet
  die gemessenen Werte nach jedem abgeschlossenen Manöver ans Handy
- In der App: Trend über die Session/über mehrere Trainingstage anzeigbar
  (z.B. "Reaktionszeit im Schnitt diese Woche X% schneller als letzte
  Woche") — reine Zusatzauswertung auf Basis vorhandener Log-Struktur

## 5. Abgrenzung zu Quick-Message-Gesten
Bewusst getrennt von der Klio-Ja/Nein-Erkennung, auch wenn dieselbe
Hardware genutzt wird:
- Ja/Nein-Gesten: bewusste, kurze, trainierte Eingabe-Geste
- Manöver-Tracking: passive Beobachtung einer bereits stattfindenden
  Handlung, keine Eingabe-Absicht des Nutzers
- Beide laufen unabhängig nebeneinander, keine gegenseitige Störung zu
  erwarten (unterschiedliche Zeitfenster: Manöver sind durch das
  TrainingEngine-Kommando zeitlich bekannt, Quick-Messages sind
  ereignisgesteuert über LoRa)

## 6. Offene Punkte
- [ ] Exakte Schwellenwerte für Bewegungsbeginn/-ende empirisch festlegen
  (analog zum Kalibrierungs-Protokoll der Gesten-Doku — auch hier gilt:
  am Wasser kalibrieren, nicht am Schreibtisch)
- [ ] Wie granular die Flüssigkeits-Metrik sein soll (reicht ein simpler
  Ruck-Wert, oder soll das differenzierter werden?)
- [ ] BLE-Befehl/Paketformat für die Übertragung der Metriken ans Handy
  festlegen
- [ ] Room-Schema-Migration für die neuen Manöver-Log-Felder
- [ ] Ob/wie das in der bestehenden Trainings-UI der App sichtbar gemacht
  wird (eigener Screen, oder Ergänzung zum bestehenden Manöver-Log)
