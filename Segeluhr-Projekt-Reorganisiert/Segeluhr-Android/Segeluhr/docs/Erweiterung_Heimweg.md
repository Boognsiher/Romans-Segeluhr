# Erweiterung: Heimweg-Navigation ("Nach Hause gehen")

Diese Funktion ist **nicht** Teil der ursprünglichen `Segeluhr_Spezifikation.md`,
sondern eine spätere Erweiterung. Sie funktioniert ähnlich wie die
Racemode-Bojen-Peilung (Abschnitt 6.4 der Spezifikation), aber für einen
einzelnen, fest gesetzten Heimatpunkt statt zweier Bojen — inklusive
Wende-Vorschlag und Ankunftszeit-Schätzung (ETA).

## Bedienung

1. **Setup-Tab → "Zuhause / Hafen"**: aktuellen GPS-Fix als Heimatpunkt
   setzen (gleiches Muster wie Pin/Boot/Ziel — direkt auf dem Wasser bzw.
   im Hafen per Knopfdruck erfassen).
2. **Normal-Tab → Karte "Heimweg"**: Button "Nach Hause navigieren" startet
   die laufende Führung; erneutes Drücken beendet sie wieder.

## Was während der Führung berechnet wird

- **Peilung & Distanz** zum Heimatpunkt — wie bei "Ziel"/Racemode-Boje.
- **Empfohlener Kurs**: Ist der direkte Kurs zum Heimatpunkt weiter als 45°
  vom Wind entfernt, wird er direkt angezeigt (frei anliegend/raumschots
  segelbar). Liegt er näher als 45° am Wind (Ziel liegt zu dicht am Wind),
  wird stattdessen der bessere der beiden Am-Wind-Kurse (Wind ± 45°)
  empfohlen — das Ziel muss dann gekreuzt werden.
- **Wende-Hinweis**: Nur relevant, wenn gekreuzt werden muss. Passt der
  aktuelle Bug nicht zum empfohlenen Am-Wind-Kurs, wird "Wende empfohlen"
  angezeigt und (nur beim Wechsel, nicht dauerhaft) ein Vibrationssignal
  ausgelöst (identisch zum Manöver-Kommando-Muster, Abschnitt 7:
  1× kräftig/lang).
- **ETA**: Berechnet über die "Velocity Made Good" (VMC) — die
  Geschwindigkeitskomponente in Richtung des direkten Heimatpunkt-Kurses
  (`SOG * cos(COG − Peilung)`). Das ist dieselbe Methode, die auch
  handelsübliche Segel-GPS-Geräte für "Time to Waypoint" verwenden, und
  funktioniert unabhängig davon, ob gerade gekreuzt wird oder nicht (beim
  Kreuzen ist die VMC entsprechend kleiner, was implizit die zusätzliche
  Strecke durchs Kreuzen mit einrechnet). Ist die VMC ≤ 0 (Boot entfernt
  sich gerade vom Ziel), wird keine ETA angezeigt.

## Bewusste Vereinfachungen

- **45°-Am-Wind-Winkel ist ein fixer Startwert** (`HomeEngine.CLOSEHAULED_ANGLE_DEG`),
  keine bootsklassenspezifische Polardiagramm-Berechnung. Bei Bedarf
  anpassbar oder später aus echten Trainingsdaten kalibrierbar (ähnlich dem
  Hinweis zur Score-Formel in Abschnitt 6.3 der Hauptspezifikation).
- **Kein Downwind-Halse-Spezialfall**: Liegt das Ziel fast direkt vor dem
  Wind (dead downwind), wird es trotzdem als "direkt anliegend segelbar"
  behandelt (da außerhalb der 45°-Zone) und keine Halsen-Empfehlung für
  eine schnellere S-Kurs-Strecke gegeben — nur einfache Am-Wind-Kreuz-Logik,
  keine volle VMG-Router-Funktion.
- **`homeModeActive` wird NICHT persistiert** — die Navigation startet nach
  einem App-Neustart immer im Zustand "aus", auch wenn ein Heimatpunkt
  gesetzt ist. Das ist eine bewusste Sicherheitsentscheidung (kein
  überraschend aktiver Modus nach Neustart), lässt sich aber über
  DataStore nachrüsten, falls gewünscht.

## Offener Anknüpfungspunkt: LoRa-Statusmeldung an Land

Die Funktion `SegeluhrViewModel.setHomeModeActive()` enthält bereits einen
Kommentar-Hook für eine künftige LoRa-Anbindung, die Personen an Land
automatisch informiert, sobald der Heimweg-Modus aktiviert wird (inkl.
ETA). Das ist noch nicht umgesetzt, da dafür erst die Hardware/das
Protokoll geklärt werden muss (Handy hat kein eingebautes LoRa-Funkmodul) —
siehe die offene Diskussion dazu in der Projekt-Historie.
