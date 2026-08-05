# Erweiterung: Land/Boot-Kommunikation per LoRa

> Diese Erweiterung steht NICHT in der ursprünglichen Segeluhr_Spezifikation.md
> und wird hier gemäß Doku-Konvention separat dokumentiert.

## Status: ENTWURF (vorbereitet für Claude-Code-Session, noch nicht implementiert/getestet)

## 1. Motivation

Bisheriges Setup ging von zwei Uhren aus, bei denen die T-Watch S3 als
Test-Boots-Gerät diente. Mit der Anschaffung von T-Watch Ultra ändert sich
das Deployment:

- **Auf dem Boot (See):** Samsung-Handy (Segeluhr-App) + T-Watch Ultra
- **An Land (Crew):** T-Watch S3

Die beiden Uhren kommunizieren nicht mehr über BLE miteinander, sondern
per **LoRa (SX1262)** über die Distanz See ↔ Land.

## 2. Rollenverteilung

### T-Watch Ultra — "Boots-Uhr"
Übernimmt die komplette bisherige Funktionalität von `Segeluhr_TWatch_S3.ino`:
- BLE Central, verbindet sich mit dem Handy (GATT-Server/Peripheral)
- Alle 6 Segel-Screens (Nav/Kompass, Wind, Heimweg, Countdown, Manöver, Menü)
  + Alltag-Modus
- Auto-Mode-Switching bei BLE Connect/Disconnect (30s Gnadenfrist)
- Haptik (DRV2605, ROM-Effekte)
- **NEU:** GPS intern (Solo-GPS der Ultra) — perspektivisch auch für
  Stand-alone-Betrieb ohne Handy nutzbar (späterer Ausbauschritt, hier nicht
  Teil der heutigen Vorbereitung)
- **NEU:** LoRa-Sender — sendet alle 30 Sekunden ein Status-Paket (siehe
  Abschnitt 4) an die Land-Uhr

### T-Watch S3 — "Land-Uhr"
Verliert die BLE-Rolle komplett. Wird zur reinen Anzeige-Uhr für die Crew an Land:
- **Kein BLE mehr.**
- LoRa-Empfänger, hört kontinuierlich auf Status-Pakete von der Boots-Uhr
- Zwei Screens (siehe Abschnitt 3)
- Eigene RTC/Uhrzeit läuft lokal weiter, auch ohne Empfang
- Erkennt und zeigt Verbindungsverlust an, wenn > 90s kein Paket ankommt
  (3x Sende-Intervall als Toleranz)

## 3. Screens auf der Land-Uhr (S3)

### Hauptscreen (Default)
- Großer Status-Text, z.B.:
  - "WARTE AUF START" (Idle)
  - "COUNTDOWN 4:32"
  - "TRAINING LÄUFT"
  - "WETTFAHRT LÄUFT"
  - "HEIMWEG AKTIV"
  - "MANÖVER!" (kurzzeitig, wenn Wende/Halse-Kommando aktiv war)
- Aktuelle Uhrzeit (lokale RTC, groß, gut lesbar von Weitem)
- Kleiner Verbindungsindikator (z.B. Punkt/Balken: Zeit seit letztem Paket)
- Bei Verbindungsverlust (> 90s): Status-Text wird durch
  "KEIN SIGNAL (seit Xs)" ersetzt, Uhrzeit bleibt sichtbar

### Detailscreen (Wechsel per Knopf/Crown)
- Distanz zur nächsten Bake / nach Hause (aus Paket)
- Speed over Ground (SOG)
- Batteriestand Boots-Uhr
- Windschätzung (falls im Paket vorhanden)
- Paket-Sequenznummer + Alter des letzten Pakets (Debug/Vertrauen in Daten)

### Menü-Screen (dritter Screen, Wechsel per Touch/Knopf)
- **Stumm-Modus Ein/Aus** (Toggle): unterdrückt Vibration UND Ton bei
  eingehenden Quick-Messages/Antworten. Der 30s-Status-Broadcast löst
  ohnehin nie eine Benachrichtigung aus (siehe Abschnitt 6), Stumm betrifft
  also nur die Quick-Message-Benachrichtigungen.
- **Ausschalten**: fährt die Uhr herunter (analog zum bestehenden
  Shutdown-Mechanismus der Boots-Uhr, `instance.sleep()` o.ä.)

Navigation: Haupt → Detail → Menü → zurück zu Haupt (zyklisch), per Wischen/
Antippen (Touch primär, siehe Abschnitt 5) oder Knopf als Fallback.

Navigation zwischen den Screens: einfacher Tap/Button-Druck, kein
komplexes Menü nötig (Land-Uhr soll bewusst simpel bleiben).

## 4. LoRa-Paketformat

Siehe `LoRaPacket.h` (gemeinsam includiert von beiden Firmwares, damit
Sender/Empfänger nie auseinanderlaufen).

- Sende-Intervall: alle 30s (fix, kein Ack nötig — Land-Uhr toleriert
  Paketverlust und zeigt einfach "kein Signal" wenn nichts mehr ankommt)
- Paketgröße: 20 Bytes (siehe Header) — bewusst kompakt gehalten für
  zuverlässige LoRa-Übertragung auch bei größerer Distanz/schlechterem SNR
- `BoatState`-Enum bildet die Top-Level-Zustände ab, die für die Crew
  relevant sind — bewusst NICHT die volle Detailtiefe der App-internen
  Engines (TrainingEngine/CompetitionEngine bleiben intern, Land-Uhr sieht
  nur den vereinfachten Zustand)

## 5. Quick-Messages (lockere Ja/Nein-Fragen)

### Zweck
Zusätzlich zum automatischen Status-Broadcast können beide Uhren sich
gegenseitig lockere Ja/Nein-Fragen schicken (z.B. "Alles gut?", "Hunger?").
Hat mit Segeln nichts zu tun — einfach ein schneller Kommunikationskanal
zwischen Boot und Land.

### Architektur-Änderung
Die Land-Uhr (S3) war bisher reiner LoRa-**Empfänger**. Für Quick-Messages
wird sie zum **Transceiver** — beide Uhren können jetzt senden UND empfangen.
Das Status-Paket (Abschnitt 4) bleibt aber weiterhin Einbahnstraße
(nur Boot → Land, alle 30s).

### Ablauf
1. Auf einer Uhr: Menü öffnen, durch die 10 vordefinierten Fragen blättern
   (kurzer Knopfdruck = nächste Frage), lange drücken = Frage senden
   (`QuickMessageRequest`)
2. Empfangende Uhr: zeigt die Frage als Notification/Overlay über dem
   aktuellen Screen, vibriert kurz
3. Kurzer Druck = Ja, langer Druck = Nein (`QuickMessageResponse`)
4. Sendende Uhr zeigt Antwort an, sobald sie ankommt
5. Falls nach `QUICK_MESSAGE_TIMEOUT_MS` (60s) keine Antwort kommt: Anzeige
   wechselt auf "keine Antwort" und verschwindet nach ein paar Sekunden

### Bekannte Einschränkung: nur ein Knopf → unterschiedlich gelöst je Uhr
Die Ultra hat laut Hardware-Doku nur einen frei programmierbaren Knopf
(GPIO0, "Custom Button") — die Crown ist reiner Hardware-Reset. Für die
Boots-Uhr (nasse Hände/Handschuhe, Touch unzuverlässig) lösen wir das über
Gesten (siehe unten). Für die Land-Uhr (Crew, meist trockene Hände) reicht
**Touch** — beide Watches haben Touch-Hardware (S3: FT5336, Ultra: CST9217),
aber kapazitiver Touch funktioniert schlecht mit nassen Fingern/Handschuhen,
weshalb wir ihn nur dort primär einsetzen, wo das kein Problem ist.

**Land-Uhr (S3): nur Touch, keine Geste**
- Sichtbare **JA**/**NEIN**-Buttons auf dem Bildschirm bei eingehender Frage
- Wischen oder Tippen zum Wechsel Hauptscreen/Detailscreen
- Antippen zum Durchblättern/Senden im Fragen-Menü
- Physischer Knopf bleibt als Fallback im Code (kurz=Ja, lang=Nein bzw.
  Screen-Wechsel), falls Touch mal nicht reagiert

**Boots-Uhr (Ultra): Knopf + Geste, kein primärer Touch**
- **Ja** = Handgelenk kurz hochdrehen (Tilt-Interrupt)
- **Nein** = kurz schütteln (Any-Motion mit Richtungswechsel)
- Knopf-Druckmuster (kurz=Ja, lang=Nein) als Fallback
- Touch optional als Bonus bei ruhigem Wetter, nicht primär geplant

**Empfehlung: Klio statt manueller Schwellenwerte.** `SensorLib` (bereits als
gepinnte Abhängigkeit über LilyGoLib-ThirdParty im Projekt) enthält fertige
BHI260AP-Beispiele, u.a. für **Klio** — Boschs selbstlernenden
Mustererkennungs-Algorithmus, der direkt auf dem Sensor-Chip läuft. Statt
Schwellenwerte für Beschleunigung/Richtungswechsel zu raten (siehe unten),
lässt sich Klio mit ein paar Beispiel-Aufnahmen der echten "Ja"/"Nein"-Geste
trainieren und erkennt danach genau dieses Muster wieder — deutlich
robuster gegen Krängung/Wellenschlag als von Hand geschätzte Schwellenwerte,
weil es auf echten Trainingsdaten statt Annahmen beruht. Die manuellen
Schwellenwert-Konstanten unten (`GESTURE_SHAKE_MIN_REVERSALS` etc.) bleiben
als Fallback/Vergleichsbasis im Code, falls sich Klio in der Praxis doch als
unpraktisch erweist (z.B. Trainingsaufwand zu hoch für den Alltag) — aber
Klio ist der zu bevorzugende erste Versuch.

### Schutz gegen Fehlauslösung durch Segel-Bewegungen (nur Boots-Uhr)
Betrifft nur die Ultra, da nur sie auf dem Boot sitzt und nur sie Gesten
nutzt — die Land-Uhr antwortet ja über Touch. Krängung, Wellenschlag und
Schoteinholen erzeugen ständig Beschleunigungen, die eine Geste vortäuschen
könnten. Drei Schutzebenen:

1. **Gate durch `haveIncomingQuestion`** (bereits im Skeleton): Gesten werden
   nur ausgewertet, wenn tatsächlich eine Frage offen ist (max. 60s-Fenster).
   Den Großteil der Segelzeit läuft die Auswertung gar nicht.
2. **Orientierung statt reinem Bewegungs-Spike für "Ja":** Wie bei "Raise to
   Wake" die Ausrichtung zur Schwerkraft prüfen (Handgelenk tatsächlich in
   Blickposition), nicht nur "gab es eine Bewegung nach oben" — deutlich
   robuster gegen Krängung.
3. **Muster statt Einzelimpuls für "Nein":** mehrere schnelle Richtungswechsel
   innerhalb eines kurzen Zeitfensters mit Mindestamplitude verlangen (siehe
   `GESTURE_SHAKE_MIN_REVERSALS`/`GESTURE_SHAKE_WINDOW_MS` in
   `QuickMessages.h`), statt auf jeden Ruck zu reagieren.

**Wichtig:** Die konkreten Schwellenwerte sind Platzhalter im Code und müssen
**auf dem Wasser** kalibriert werden — Rohdaten während normalem Segeln
gegen eine bewusste Geste vergleichen. Am Schreibtisch getestete Werte sagen
nichts darüber aus, wie sich das bei echter Krängung verhält. Der
Knopf-Fallback bleibt deshalb bewusst im Code, bis die Kalibrierung steht.

### Offene Punkte zur Gestenerkennung (nur Boots-Uhr, Land-Uhr nutzt Touch)
- [ ] SensorLib-Beispiele für BHI260AP/Klio durchgehen (`Sensors/IMU/BHI260AP/`
  laut SensorLib-Repo, u.a. "Klio"-Beispiel) und prüfen, ob sich Ja/Nein
  darüber trainieren lässt — bevorzugter Ansatz, siehe oben
- [ ] Falls Klio nicht praktikabel: Schwellenwerte für "Schütteln" empirisch
  austesten (zu empfindlich = Fehlauslösung beim normalen Segeln/Gehen, zu
  unempfindlich = Geste wird nicht erkannt)
- [ ] Ggf. Gestenerkennung nur aktiv, wenn `haveIncomingQuestion == true`
  (sonst würde jede normale Handbewegung als Antwort gewertet)

### Fragenkatalog
Siehe `QuickMessages.h`, `QuickQuestion`-Enum, 10 Einträge (ALLES_GUT,
HUNGER, KALT_DRAUSSEN, BALD_FERTIG, KAFFEE_TEE_MACHEN, BRAUCHST_DU_WAS,
GUTE_LAUNE, NOCH_LANGE, BRINGST_DU_WAS_MIT, SEHEN_WIR_UNS_GLEICH).

### Automatische Antwort während einer Wettfahrt
Wenn die Boots-Uhr sich im `COMPETITION`-Zustand befindet (den kennt sie
bereits aus dem eigenen Status-Tracking, siehe `currentBoatState`), antwortet
sie auf jede eingehende Quick-Message-Frage **automatisch** mit
`QuickAnswer::AUTO_REGATTA` ("BIN IN EINER REGATTA") — **ohne** Overlay und
**ohne** Vibration. Der Skipper soll während des Rennens gar nicht erst
merken, dass eine Frage kam. Die Land-Uhr zeigt die Antwort trotzdem normal
an, sobald sie ankommt.

Bewusst NUR für `COMPETITION` — nicht für `TRAINING`, `COUNTDOWN` oder
`HEIMWEG`. Falls sich das im Alltag als zu eng erweist (z.B. auch während
des Countdowns keine Ablenkung), lässt sich die Bedingung leicht erweitern.

 (dann: ein Knopf=Ja,
  anderer=Nein statt Druckmuster)
- [ ] Kollisionsfall: was passiert, wenn beide Uhren gleichzeitig eine Frage
  senden? (Einfachster Ansatz: einfach beide Requests unabhängig behandeln,
  kein Lock nötig — Verkehr ist selten genug)
- [ ] Retry-Logik: aktuell kein Ack für den Request selbst, nur für die
  Antwort. Falls das im Test zu unzuverlässig ist: Request 2x hintereinander
  senden mit kurzem Abstand
- [ ] ~~Knopf-Konflikt auf der Land-Uhr~~ — entschärft durch Gesten-Antwort
  (siehe Abschnitt 5): der Knopf ist jetzt nur noch für Screen-Wechsel/Menü
  zuständig, Ja/Nein läuft über Gesten. Knopf-Druckmuster bleibt als Fallback
  im Code, falls sich die Gestenerkennung im Test als unzuverlässig erweist.

## 6. Benachrichtigungen (Vibration/Ton)

Bewusst NICHT bei jedem Funkpaket — sonst würde die Land-Uhr alle 30s den
ganzen Tag vibrieren (Status-Broadcast ist Routine, kein Ereignis).

| Ereignis | Land-Uhr (S3) | Boots-Uhr (Ultra) |
|---|---|---|
| 30s-Status-Broadcast | keine Benachrichtigung, Screen aktualisiert sich still | sendet nur, nichts zu spüren |
| Eingehende Quick-Message-Frage | Vibration + optional Ton (An-Bord-Toggle) | Vibration — außer während `COMPETITION` (siehe Auto-Regatta-Antwort, dort bewusst lautlos) |
| Antwort auf eigene gestellte Frage kommt an | kurze Vibration | kurze Vibration |
| Signal verloren (>90s) | keine Vibration, nur Text "KEIN SIGNAL" — sonst nervt's bei jeder kurzen Funklücke | — |

**Warum Ton nur optional auf der Land-Uhr:** Die Land-Uhr liegt möglicherweise
auf einem Tisch statt am Handgelenk — dort hilft ein Ton zusätzlich zur
Vibration. Die Boots-Uhr sitzt am Handgelenk und ist Wind-/Wellenlärm
ausgesetzt, ein Ton würde dort oft ohnehin untergehen — Vibration reicht.

### Offene Punkte zu Benachrichtigungen
- [ ] Konkrete Haptik-Muster festlegen (kurz/lang, wie viele Pulse) — analog
  zu den bereits bestehenden "reinforced haptic patterns (DRV2605 ROM-Effekte)"
  aus der bisherigen Firmware, nicht neu erfinden
- [ ] Ton auf der Land-Uhr: an/aus-Umschaltbar machen (z.B. per Menüpunkt),
  nicht fest verdrahtet

## 7. Verschlüsselung (AES-128-CTR)

Rohes LoRa (kein LoRaWAN) überträgt standardmäßig unverschlüsselt. Alle
Pakete (Status, Quick-Message-Request/Response) werden deshalb vor dem
Senden mit AES-128 im CTR-Modus verschlüsselt und nach dem Empfang wieder
entschlüsselt — siehe `Crypto.h`.

**Wichtig, damit die Erwartung stimmt:** Das schützt gegen zufälliges
Mitlesen durch jemanden, der zufällig auf eurer Frequenz/Bandbreite/SF/
Sync-Word landet — es ist **kein** Schutz gegen einen gezielten Angreifer,
da der Schlüssel im Repo liegt. Für den Anwendungsfall (Sauberkeit, nicht
Sicherheitsanspruch) ist das die richtige Abwägung.

**Warum CTR-Modus:** kein Padding nötig (unsere Pakete sind klein und nicht
16-Byte-ausgerichtet), jedes Paket bekommt einen eigenen 8-Byte-Zufalls-Nonce
vorangestellt — kein Zustand muss zwischen den Uhren synchron gehalten
werden, jedes Paket ist für sich entschlüsselbar.

**Paketgrößen ändern sich:** +8 Byte Nonce pro gesendetem Paket (z.B.
`LoRaStatusPacket` 20 Byte → 28 Byte auf der Luft). Kein Problem für die
LoRa-Nutzlastgrenze, aber relevant, falls irgendwo eine feste Puffergröße
angenommen wird.

### Offene Punkte zur Verschlüsselung
- [ ] Eigenen zufälligen AES-Schlüssel generieren (z.B. `openssl rand -hex 16`)
  und den Platzhalter in `Crypto.h` ersetzen — auf BEIDEN Uhren identisch!
- [ ] Prüfen, ob `mbedtls/aes.h` und `esp_system.h` im Arduino-ESP32-Core
  ohne zusätzliche Library-Installation verfügbar sind (sollten sie sein,
  da Teil des Cores) — kurzer Compile-Test genügt

## 8. Offene Punkte für die Claude-Code-Session heute Abend

- [ ] RadioLib-Konfiguration für beide T-Watch-Varianten prüfen (Pinbelegung
  SX1262 kann sich zwischen S3 und Ultra unterscheiden — LilyGoLib sollte
  das aber über die jeweilige Board-Definition kapseln)
- [ ] Bestehenden Code aus `Segeluhr_TWatch_S3.ino` in
  `TWatch_Ultra_Boot` Verzeichnis portieren, Ultra-spezifische
  Display-/Touch-Init anpassen
- [ ] Manuelles Flash-Verfahren (BOOT+Crown) ggf. auch für Ultra prüfen
  (bei S3 nötig, unklar ob Ultra denselben USB-CDC-Reset-Bug hat)
- [ ] LilyGoLib-Abhängigkeiten weiterhin NUR aus LilyGoLib-ThirdParty-Repo,
  nie über Library Manager aktualisieren
- [ ] Land-Uhr: lokale RTC initial setzen (z.B. einmalig per Knopfdruck-Menü,
  oder optionalen Zeitstempel im ersten LoRa-Paket für Auto-Sync — Entscheidung
  offen, im Skeleton als TODO markiert)
- [ ] Nach erfolgreichem Test: alte S3-BLE-Test-Firmware
  (`segeluhr_ble_tester_v2.ino`) bleibt als eigenständiges BLE-Testtool
  bestehen (unabhängig von diesem Feature) — nicht verwechseln mit der neuen
  Rolle der echten S3 als Land-Uhr
