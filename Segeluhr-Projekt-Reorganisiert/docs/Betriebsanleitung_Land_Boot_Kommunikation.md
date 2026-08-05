# Segeluhr — Betriebsanleitung Land/Boot-Kommunikation

Diese Anleitung ist für dich und deine Crew — nicht für Claude Code oder
Entwickler-Arbeit. Sie erklärt, wie die beiden Uhren im Alltag benutzt werden.

## Wer trägt was

- **Du auf dem Boot:** T-Watch Ultra (zusammen mit dem Handy/der App)
- **Deine Crew an Land:** T-Watch S3

Die beiden Uhren reden automatisch per Funk miteinander — nichts zum
Einstellen oder Koppeln nötig, sie funktionieren einfach, sobald beide an
sind.

## Die Land-Uhr: was die Crew sieht

**Hauptbildschirm** (das, was standardmäßig zu sehen ist):
- Ein Status-Text, z.B. "TRAINING LÄUFT", "HEIMWEG AKTIV", "WETTFAHRT LÄUFT"
- Die aktuelle Uhrzeit
- Ein kleines Zeichen, ob gerade Empfang besteht

**Detailbildschirm** (kurzer Knopfdruck zum Wechseln):
- Entfernung, Geschwindigkeit, Akkustand deines Boots, grobe Windangabe

**Menü-Bildschirm** (nochmal weiterblättern):
- **Stumm-Modus** ein-/ausschalten: unterdrückt Vibration und Ton bei
  Quick-Messages (der normale Status alle 30s macht ohnehin nie Lärm)
- **Ausschalten**: schaltet die Uhr aus

**"Kein Signal":** Falls länger als 90 Sekunden nichts ankommt, steht das da.
Das ist meistens kein Problem — es heißt nur, dass gerade kein Paket
durchgekommen ist (z.B. wegen Entfernung oder Hindernissen). Die Uhrzeit läuft
trotzdem normal weiter. Nichts, was man tun müsste, außer abzuwarten.

## Kurze Nachrichten schicken ("Quick-Messages")

Beide Uhren können sich lockere Ja/Nein-Fragen schicken — hat nichts mit
Segeln zu tun, einfach ein schneller Draht zwischen Boot und Land.

**Frage stellen (auf der Land-Uhr):**
1. Auf dem Hauptbildschirm antippen → öffnet die Fragenliste
2. Antippen zum Durchblättern der Fragen
3. Bei der gewünschten Frage auf "senden" tippen

**Die 10 verfügbaren Fragen:**
1. Alles gut?
2. Hunger?
3. Kalt da draußen?
4. Bald fertig?
5. Soll ich Kaffee/Tee machen?
6. Brauchst du was?
7. Gute Laune?
8. Noch lange?
9. Bringst du was mit?
10. Sehen wir uns gleich?

**Frage beantworten:**
- **Auf der Land-Uhr:** einfach auf **JA** oder **NEIN** auf dem Bildschirm
  tippen — die Frage wird mit sichtbaren Touch-Buttons angezeigt
- **Auf der Boots-Uhr:** Handgelenk kurz hochdrehen (wie beim Blick auf die
  Uhr) = **Ja**, kurz schütteln = **Nein** — Touch ist auf dem Boot mit
  nassen Händen/Handschuhen unzuverlässig, deshalb hier Gesten statt Buttons.
  Falls die Geste mal nicht erkannt wird: kurzer Knopfdruck = Ja, langer
  Knopfdruck = Nein funktioniert auch immer

**Falls keine Antwort kommt:** Nach 60 Sekunden zeigt die fragende Uhr
"keine Antwort" — kein Grund zur Sorge, kann an Funkreichweite oder einfach
daran liegen, dass die andere Person es gerade nicht gesehen hat.

## Sonderfall: Automatische Antwort während einer Wettfahrt

Wenn du während einer laufenden Wettfahrt (Competition-Modus) eine Frage
bekommst, antwortet deine Uhr automatisch mit "BIN IN EINER REGATTA" — ohne
dass du überhaupt etwas mitbekommst (kein Vibrieren, keine Anzeige). Das ist
Absicht: du sollst während des Rennens nicht abgelenkt werden. Deine Crew
sieht trotzdem, dass du gerade nicht antworten kannst.

Außerhalb einer Wettfahrt (Training, Heimweg, normale Fahrt) funktionieren
Quick-Messages ganz normal.

## Praktische Tipps

- **Reichweite:** funktioniert am besten mit freier Sicht zwischen Boot und
  Land; Bäume, Gebäude oder große Distanz können die Verbindung schwächen
- **Beide Uhren müssen an sein**, sonst passiert logischerweise nichts
- **Bei anhaltendem "Kein Signal":** kurz prüfen, ob beide Uhren noch
  ausreichend Akku haben und eingeschaltet sind
- Die Kommunikation ist zwischen den beiden Uhren leicht verschlüsselt —
  reine Vorsichtsmaßnahme, kein Sicherheitsfeature, das ihr aktiv bedienen
  müsst

## Was diese Anleitung NICHT abdeckt

Die sechs Segel-Screens auf der Boots-Uhr (Nav/Kompass, Wind, Heimweg,
Countdown, Manöver, Menü) und die App-Bedienung selbst sind in der
bestehenden `Segeluhr_Spezifikation.md` beschrieben — diese Anleitung
behandelt nur das, was heute neu dazugekommen ist: die Land/Boot-Funkverbindung
und die Quick-Messages.
