# Erweiterung: Startlinie-Bias — BLE-Anbindung + Vorzeichen-Fix

> Kein neues Feature im eigentlichen Sinn — das Startlinie-Bias-Konzept
> (Pin/Boot-Wegpunkte, Peilungs-Berechnung) existiert bereits seit der
> Ur-Spezifikation, war aber nie dokumentiert, nie über BLE an die Uhren
> übertragen und der Vorzeichen-Fehler unten war seit Einführung im Code.
> Wird hier gemäss Doku-Konvention nachträglich festgehalten.

## Status: 13.08.2026 umgesetzt (App-Seite + Ultra-Firmware-Parsing), noch nicht auf dem Wasser verifiziert

## 1. Ausgangslage

Beim Planen der Mastuhr (siehe `Erweiterung_Mastuhr.md`) kam der Wunsch
auf, vor dem Start neben dem Countdown auch die Startlinie-Bias
("welches Ende ist bevorzugt") zu zeigen. Bei der Recherche stellte sich
heraus: das gibt es schon —

- `SettingsRepository.Waypoints.pin`/`.boat` — zwei Wegpunkte,
  gesetzt per aktueller GPS-Position (Setup-Tab, "Pin-Ende"/"Boot-Ende",
  `captureWaypoint`).
- `SegeluhrViewModel.renderTelemetry()` berechnet daraus `lineBiasDeg`
  (Winkel) und `lineBiasFavors` ("Boot"/"Pin"/"neutral").
- `NormalScreen.kt` zeigt das in einer "Startlinie"-Karte an.

Nur: **nie dokumentiert, nie über BLE an die Uhr übertragen**, und im
Code stand ein Warnhinweis, dass das Vorzeichen von `lineBiasFavors`
nie mit echtem Wind gegengeprüft wurde.

## 2. Vorzeichen-Fehler gefunden + analytisch behoben (13.08.2026)

Roman-Hinweis: "Das Startboot ist immer in Windrichtung rechts" (gegen
den Wind geschaut). Daraus liess sich der bis dahin unverifizierte
Vorzeichen-Fall **ohne Wassertest** auflösen:

- `windDir` = Richtung, aus der der Wind kommt (Projekt-Konvention).
- `squareBearing = windDir + 90` = Peilung Pin→Boot bei exakt
  quadratischer Linie (Boot rechts von Pin, wenn man nach Luv schaut).
- `bias = angleDiff(bearingDeg(pin, boat), squareBearing)`
- Ist die tatsächliche Peilung Pin→Boot **grösser** als `squareBearing`
  (`bias > 0`, im Uhrzeigersinn verdreht), rückt das Boot-Ende relativ
  Richtung Lee — das **Pin-Ende ist dann luvwärtiger = bevorzugt**.
- Gegenprobe per Projektion auf die Windachse (Luv-Vorsprung Boot ggü.
  Pin = `-sin(bias)`) bestätigt dasselbe Vorzeichen.

**Vorheriger Code** (fehlerhaft, seit Einführung so):
```kotlin
lineBiasFavors = if (bias > 0) "Boot" else if (bias < 0) "Pin" else "neutral"
```

**Korrigiert** (`SegeluhrViewModel.kt`):
```kotlin
lineBiasFavors = if (bias > 0) "Pin" else if (bias < 0) "Boot" else "neutral"
```

**Trotzdem offen**: analytisch hergeleitet, aber noch nie mit echtem,
bekanntem Wind auf dem Wasser gegengecheckt — siehe Abschnitt 4.

## 3. BLE-Anbindung (neu, 13.08.2026)

`RaceStatusPacket` (`CHAR_RACE_STATUS_UUID`) 7→9 Byte erweitert:
zusätzlich `int16 lineBiasDdeg` (1/10 Grad, vorzeichenbehaftet,
`0x7FFF` = keine Startlinie gesetzt). Bewusst **kein** separates
"Boot"/"Pin"-Textfeld — die Uhr wertet das Vorzeichen selbst aus
(`>0` = Pin bevorzugt, `<0` = Boot bevorzugt), analog zur App-Logik.

Geändert:
- `BleProtocol.kt`: `encodeRaceStatus()` um `lineBiasDeg`-Parameter
  erweitert, Paketgrösse 7→9 Byte.
- `BleGattServerManager.kt`: `notifyRaceStatus()` reicht den Wert durch.
- `SegeluhrViewModel.kt`: übergibt das schon berechnete `lineBiasDeg`.
- `Segeluhr_TWatch_Ultra.ino`: `onRaceStatusNotify()` parst die
  zusätzlichen 2 Byte, `RaceData.lineBiasDeg`/`.haveLineBias` neu —
  **aktuell nur geparst/gespeichert, noch keine eigene Anzeige auf der
  Ultra** (war nicht Teil dieser Runde, Konsument ist die geplante
  Mastuhr).

**Bewusst nicht Teil dieser Runde**: Weiterleitung an die Land-Uhr (S3)
per LoRa (`LoRaPacket.h`) — aktuell kein Bedarf, Startlinie-Bias ist nur
vor dem eigenen Start relevant, nicht für die Crew an Land.

## 4. Offene Punkte

- **Auf dem Wasser verifizieren**: die Vorzeichen-Korrektur in
  Abschnitt 2 ist reine Herleitung, noch nie unter echten
  Segelbedingungen mit bekanntem Wind gegengecheckt. Niedrigere
  Dringlichkeit als vorher (Herleitung zweifach gegengeprüft), aber bei
  Gelegenheit nachholen.
- **Pin/Boot-Wegpunkte nur per "aktuelle Position" setzbar**
  (`captureWaypoint`) — anders als die neueren Wegpunkte (Bojen,
  Competition-Marken) gibt es hier noch keine Kartenauswahl
  (`Erweiterung_Boje_Kartenauswahl.md`-Muster). Kein akuter Bedarf, aber
  Kandidat für spätere Vereinheitlichung.
- **Mastuhr-Anzeige**: die eigentliche Verwendung (Pre-Start-Screen der
  Mastuhr zeigt Countdown + Line-Bias) ist noch nicht gebaut — die
  Mastuhr-Firmware existiert noch nicht (`Segeluhr_Mastuhr.ino`).
- **Ultra-Anzeige**: aktuell nur geparst, nicht dargestellt — bei Bedarf
  liesse sich das leicht auf einem bestehenden Screen ergänzen.

## 5. Pin/Boot auch von der Uhr aus setzbar (NEU 13.08.2026)

Bisher liessen sich Pin-/Boot-Ende nur im App-Setup-Tab setzen (Roman-
Wunsch beim Testen am 13.08.: "will diese auch auf der Uhr setzen
können"). Jetzt analog zu den bestehenden Wegpunkten (Home, Boje 1/2,
Ziel, Comp.-Marken) im Menü-Tab der Ultra:

- `WaypointId.PIN`/`.BOAT` (Werte 1/2) existierten im `CMD_SET_WAYPOINT`/
  `CMD_CLEAR_WAYPOINT`-Protokoll bereits vollständig (App-seitig war
  `waypointKeyFor()` schon fertig verdrahtet) — nur die Uhr hatte dafür
  noch keine Menü-Buttons.
- `CHAR_WAYPOINTS_STATUS_UUID`-Bitmaske (`WaypointSetFlag`/`WPSET_*`) um
  `PIN`/`BOAT` (Bit 6/7) erweitert, damit die Uhr die neuen Buttons wie
  gewohnt grün einfärbt, sobald das Handy eine Koordinate bestätigt.
- Neue Menü-Sektion "Startlinie (Pin/Boot, an akt. Position)" auf der
  Ultra, unterhalb der bestehenden Wegpunkte-Sektion — bewusst getrennt,
  weil Pin/Boot konzeptionell die Startlinie bilden, keine Renn-
  Wegpunkte sind (gleiche Trennung wie im App-Setup-Tab).
- Geändert: `BleProtocol.kt` (`WaypointSetFlag`, `encodeWaypointsStatus`),
  `BleGattServerManager.kt` (`notifyWaypointsStatus`), `SegeluhrViewModel.kt`
  (Aufruf-Stelle), `Segeluhr_TWatch_Ultra.ino` (`WPSET_PIN`/`WPSET_BOAT`,
  `btnSetPin`/`btnSetBoat`, `buildMenuTab()`, `menuScreenUpdate()`).
- **Noch nicht auf Hardware getestet** — kompiliert sauber (App +
  Firmware), Ultra + Handy neu geflasht/installiert (13.08.2026 abends),
  aber die neuen Buttons selbst am Schreibtisch noch nicht angeklickt.
