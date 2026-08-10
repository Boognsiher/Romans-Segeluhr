# Strategie: Erster Segeltörn mit den 10.08.-Features

Ziel: aus dem ersten echten Törn mit Boots-Kalibrierung, Vorwind-Winkel,
Distanz-Tracking und Heimweg-Trägheit möglichst viele auswertbare Daten
mitnehmen — nicht nur "hat's funktioniert", sondern konkrete Zahlen, mit
denen sich die gelernten Werte (Wende-/Vorwind-Winkel) und Schwellenwerte
im Nachhinein beurteilen lassen. Werkzeug dafür: das neue Diagnose-Log
(`docs/Erweiterung_Diagnose_Log.md`) — schreibt 1x/s den kompletten
internen Zustand als CSV mit, exportierbar direkt vom Handy.

## Vor dem Ablegen (an Land, App-Check)

1. `git pull`, App frisch bauen + installieren (siehe
   `docs/Test_Checkliste_10_08.md` Punkt 1-2 zuerst — Compile-Fehler lieber
   an Land finden als auf dem Wasser).
2. Setup-Tab → "Diagnose-Log": Schalter "Aufzeichnung aktiv" prüfen
   (Standard AN, aber einmal kontrollieren).
3. Setup-Tab → "Boots-Profil": richtiges Profil aktiv (Musto Skiff oder
   eigenes)?
4. Falls Uhr(en) dabei: koppeln, Betriebsmodus "Mit Uhr" einstellen, grünes
   "Uhr verbunden" abwarten, BEVOR es losgeht — Verbindungsprobleme lieber
   an Land debuggen.
5. GPS-Fix abwarten (Statuszeile oben: "Fix · Fahrt"/"Fix · langsam" statt
   "Kein Fix").

## Auf dem Wasser — Reihenfolge, priorisiert

Wichtig: **vor jedem gezielten Testmanöver kurz "Ereignis markieren"**
(Setup-Tab, Freitext optional, z.B. "bewusste Wende") antippen — das
schreibt sofort eine Log-Zeile mit Notiz. Ohne diese Anker lässt sich im
Log später nur schwer sagen, welche Zeile zu welchem tatsächlichen Manöver
gehört (das Handy kennt nur Zahlen, keine Absicht).

1. **Wind kalibrieren** (Wind-Tab, Kalibrierungsmodus AN) — mehrfach, wenn
   die Bedingungen es hergeben (unterschiedliche Stärke/Schräglage). Jeder
   Lauf verfeinert den Wendewinkel-Mittelwert.
2. **Ein paar bewusste, isolierte Wenden** — vor jeder: Ereignis markieren.
   Ziel: sehen, wie sich `closehauledAngleDeg` durch den Smart-Modus
   entwickelt, und ob die App-Wende-Empfehlung (Heimweg-Modus, Punkt 5)
   zum tatsächlich gefahrenen Winkel passt.
3. **Ein paar bewusste Halsen** — genauso markieren. Das ist der
   eigentliche NEUE Testpunkt heute: `downwindAngleDeg` startet bei 149°
   (Musto-Profil) bzw. 180° (neues Profil) und sollte sich beim Halsen
   Richtung des tatsächlich gesegelten Winkels bewegen, sofern innerhalb
   des ±20°-Toleranzbands (siehe "Risikopunkte" unten).
4. **Freies Segeln mit Smart-Modus AN** — beobachten (im Wind-Tab), ob sich
   Wende-/Vorwind-Winkel stabilisieren oder wild hin- und herspringen. Ein
   Sprung von mehreren Grad pro Minute wäre ein Hinweis auf zu aggressive
   EMA-Parameter (`SMART_CLOSEHAULED_EMA_ALPHA`/analog für Vorwind) — Zahlen
   dafür stehen im Log.
5. **Heimweg-Modus aktivieren** (Normal-Tab), auch testweise mit einem
   Punkt, der nicht das echte Zuhause ist. Zwei Fälle gezielt anpeilen:
   - Ziel dicht am Wind → Wende-Empfehlung sollte kommen (bestehend)
   - Ziel fast direkt vor dem Wind → **Halse**-Empfehlung sollte kommen
     (NEU heute) — Statuszeile sollte "Halse Richtung Heimweg
     empfehlenswert!" zeigen, nicht "Wende"
6. **Distanz-Tracking verifizieren**: bewusst eine halbwegs bekannte
   Strecke fahren (z.B. zwischen zwei erkennbaren Punkten, oder eine Runde
   mit bekannter ungefährer Länge) — Ereignis markieren bei Start/Ende der
   Strecke, damit sich die angezeigte "Zurückgelegte Strecke" (Normal-Tab)
   später gegen die tatsächliche Distanz grob plausibilisieren lässt.
7. **Falls zeitlich möglich**: Competition-Modus kurz anwerfen (Countdown
   durchlaufen lassen, Luvbake setzen), einmal um die Bahn — prüft
   Runden-Zählung und ob die neue Vorwind-Kurs-Berechnung dort (kein festes
   Ziel, nur "Wind ± Vorwind-Winkel") sich sinnvoll anfühlt.
8. **Falls Uhren im Einsatz UND jemand an Land bleibt**: Land-Uhr-Anzeige
   checken — "bisher X km" sollte sich alle ~30s aktualisieren (neues
   `distanceTraveledM`-per-LoRa-Feature).

## Risikopunkte aus den heutigen Änderungen — falls's komisch aussieht, ist das erwartbar, kein Bug

- **Vorwind-Winkel bleibt bei 180°/149°:** passiert, wenn nie nahe genug
  am tatsächlichen Vorwind-Kurs gesegelt wurde (±20°-Toleranzband um den
  aktuellen Schätzwert) — Halsen mit deutlich engerem Winkel als der
  Schätzwert lösen kein Lernen aus, das ist Absicht (siehe
  `docs/Erweiterung_Boots_Kalibrierung.md`).
- **Heimweg-ETA zeigt "--" die ersten ~20 Sekunden** nach Aktivieren — der
  Tracker braucht Mindesthistorie, kein Fehler.
- **Distanz zählt bei sehr langsamer Fahrt nicht mit** — GPS-Jitter-Filter
  (`Constants.MIN_SPEED_KN`), ebenfalls Absicht.
- **BLE-Verbindung zur Uhr kann kurz abreissen** (z.B. bei grösserer
  Distanz/Störung) — Fallback aufs Handy-Vibrieren sollte automatisch
  greifen, siehe `SEGELN_FALLBACK_GRACE_MS` in der Firmware.

## Nach dem Törn

1. Setup-Tab → "Log teilen" → per Mail/Drive an dich selbst schicken.
2. Die CSV-Datei in dieser (oder einer neuen) Claude-Code-Session
   hochladen/anhängen — ich werte sie aus: gelernte Winkel über Zeit,
   Plausibilität der Manöver-Empfehlungen, auffällige Sprünge/Fehlwerte.
3. Bei Firmware-Problemen (Crash, falsches Verhalten der Uhr): kurze
   Beschreibung + ungefähre Uhrzeit reicht, dann können wir das gezielt im
   Code nachvollziehen (die Ereignis-Marker im App-Log helfen auch hier,
   grob zu sehen, was zeitlich parallel auf dem Handy passiert ist).
4. Da du die Uhren am selben Abend noch neu flashen kannst: bei einem
   gefundenen Firmware-Bug direkt Bescheid geben, Fix + Reflash-Anleitung
   kommt sofort, kein Warten auf die nächste Session nötig.

## Nicht Ziel dieses Törns

- Kein Zwang, alle Punkte oben in einer Session zu schaffen — besser
  wenige Dinge sauber getestet (mit Markern) als viel oberflächlich ohne
  Anker im Log.
- Kein Praxis-Test der noch nicht fertigen Punkte (Ton bei Quick-Messages,
  Gesten-Kalibrierung) — die stehen separat in `PROJEKT_STATUS.md`.
