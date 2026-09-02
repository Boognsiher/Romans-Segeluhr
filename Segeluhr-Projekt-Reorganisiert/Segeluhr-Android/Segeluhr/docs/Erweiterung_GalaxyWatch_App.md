# Erweiterung: Galaxy-Watch-App (Segeluhr Watch)

**14.08.2026, Roman-Wunsch:** zusätzliche Uhr-Anbindung für eine Samsung
Galaxy Watch 5 Pro (LTE) — funktional ähnlich zur T-Watch Ultra (BLE-
Client zum Handy, dieselben Segel-Tabs, dieselbe Haptik), aber OHNE die
LoRa-Anbindung an eine Land-Uhr (T-Watch S3), da die Galaxy Watch keine
eigene Land-Uhr-Gegenstelle hat.

## Warum ein eigenes Projekt statt Firmware

Die Galaxy Watch 5 Pro läuft **Wear OS** (Samsung/Google), nicht die
Arduino/ESP32-Umgebung der T-Watch-Reihe — "Firmware" im bisherigen Sinn
(Arduino-Sketch, LilyGoLib, LVGL, NimBLE) gibt es dafür nicht. Stattdessen
ist es eine ganz normale **native Android-App** (Kotlin/Jetpack Compose
for Wear OS), die auf der Uhr läuft — die Galaxy Watch ist selbst ein
Android-Gerät. Deshalb neuer Top-Level-Ordner `Segeluhr-GalaxyWatch/`
(eigenständiges Android-Studio-Projekt, Package `com.segeluhr.watch`),
analog zu `Segeluhr-Android/` und `Segeluhr-Firmware/`, nicht als
Uhr-Firmware-Unterordner.

## Architektur-Entscheidungen (mit Roman abgestimmt)

1. **Datenquelle: nur per BLE vom Handy, wie die Ultra.** Kein eigenes
   Solo-GPS/LTE-Datennutzung auf der Uhr, obwohl die Hardware das könnte —
   das Handy bleibt alleinige Quelle für GPS/Wind/Renn-Logik. Deutlich
   weniger Aufwand, volle Wiederverwendung der bestehenden Engines.
2. **BLE-Rolle: GATT-Central**, exakt wie die T-Watch Ultra — der
   bestehende `BleGattServerManager.kt` (Handy = Peripheral/Server) hält
   ohnehin schon ein `Set<BluetoothDevice>` und benachrichtigt ALLE
   verbundenen Geräte gleichzeitig, mehrere Uhren (z.B. Ultra UND Galaxy
   Watch gleichzeitig) sind also problemlos möglich, keine Änderung am
   Handy nötig.
3. **Wegpunkte auch auf der Uhr setzbar**, wie bei der Ultra ("an der
   aktuellen Boots-Position") — PLUS neu ein Kartenpicker direkt auf der
   Uhr (osmdroid, Compose-Interop via `AndroidView`, gleiches Muster wie
   `WaypointMapPickScreen.kt` am Handy). Dafür ein neuer BLE-Befehl
   `CMD_SET_WAYPOINT_AT_COORDS` (siehe unten) — die Ultra kennt das
   Konzept "Wegpunkt per Karte" gar nicht, das gab es bisher nur am Handy.

## Was ggü. der Ultra bewusst GESTRICHEN wurde

Der Auftrag war explizit: "alles streichen was Hardware-mässig nicht
funktioniert wie bei der Ultra". Konkret nicht übernommen:

- **Klio-Gestenerkennung** (BHI260-Sensor, Tilt/Shake): auf der Ultra nie
  zuverlässig zum Laufen gebracht (siehe PROJEKT_STATUS.md, 13.08.2026 —
  Callback feuert trotz drei gefixter Bugs nicht). Die Galaxy Watch hat
  ohnehin einen anderen Sensor-Stack, eine Neuentwicklung wäre unabhängig
  vom Ultra-Ergebnis gewesen. Bojen-Rundungs-Rückfrage läuft hier NUR per
  Touch-Buttons (Ja/Nein), siehe `RoundingConfirmBanner.kt`.
- **LoRa-Anbindung an eine Land-Uhr** (Status-Broadcast, Quick-Messages):
  explizit ausgeschlossen ("ohne Anbindung an die S3"). Es gibt keine
  Land-Gegenstelle für die Galaxy Watch.
- **Standby/Wake-Sonderbehandlung, PMU/DRV2605-Eigenheiten, physischer
  Taster (GPIO0)**: alles T-Watch-Hardware-spezifisch. Wear OS regelt
  Display-Timeout/Standby selbst, die Galaxy Watch hat Standard-Android-
  Vibrator-APIs (`core/HapticPlayer.kt`, 1:1 dieselben Muster wie
  `VibrationPatterns.kt` am Handy) und keinen vergleichbaren Extra-Taster.
- **Eigener "Alltags"-Screen** (Uhr/Timer/Akku/Setup wie auf der Ultra):
  nicht nachgebaut — die Galaxy Watch hat bereits ihr eigenes Ziffernblatt
  und System-Apps (Timer, Stoppuhr, etc.), dafür braucht es keine eigene
  Nachbildung in dieser App. Die App zeigt ausschliesslich die
  Segel-Funktionalität.
- **Time-Sync-Characteristic** (`CHAR_TIME_SYNC_UUID`): nicht ausgelesen —
  die Uhr hat über Wear OS ohnehin eine korrekte Uhrzeit, anders als eine
  isolierte T-Watch ohne eigenes Betriebssystem.
- **Zwei-Betriebsmodi-Umschaltung am Handy** ("Ohne Uhr"/"Mit Uhr"):
  unverändert am Handy — die Galaxy-Watch-App klinkt sich einfach als
  weiterer BLE-Central ein, sobald "Mit Uhr" aktiv ist.

## Tab-Struktur (Compose-Pager statt lv_tabview)

`ui/SegelnApp.kt` — horizontal wischbare Tabs, inhaltlich 1:1 zu den
Ultra-Tabs, technisch `HorizontalPager` statt LVGL-`tabview`:

| Tab | Datei | Inhalt (analog zur Ultra-Funktion) |
|---|---|---|
| Nav | `NavScreen.kt` | Modus (BoatState), Manöver-Ampel, SOG, Ziel-VMC, Lift/Header — `navScreenUpdate()` |
| Wind | `WindScreen.kt` | Windrichtung + Trend — `windScreenUpdate()` |
| Heim | `HomeScreen.kt` | Heimweg-Status, Wende-Empfehlung, ETA, VMC, Distanz — `homeScreenUpdate()` |
| CD | `CountdownScreen.kt` | Countdown-Ring (`CircularProgressIndicator` statt `lv_arc`) — `countdownScreenUpdate()` |
| Man | `ManeuverScreen.kt` | Wende/Halse-Empfehlung, aktuelles Leg — `maneuverScreenUpdate()` |
| Menu | `MenuScreen.kt` | Alle CMD_*-Aktionen, Wegpunkte (Hier/Karte/Löschen) — `buildMenuTab()` |

Zwei bildschirmfüllende Overlays, unabhängig vom aktiven Tab (vereinfacht
ggü. der Ultra, die stattdessen per Auto-Focus auf den passenden Tab
umschaltet):

- Bojen-Rundungs-Rückfrage (`RoundingConfirmBanner.kt`)
- Wegpunkt-Kartenpicker (`WaypointMapPickScreen.kt`)

## Neuer BLE-Befehl: CMD_SET_WAYPOINT_AT_COORDS

Ergänzung zu `BLE_Protokoll.md`/`BleProtocol.kt` (Wert 17, nach dem
bisher letzten `CMD_REJECT_BUOY_ROUNDING = 16`):

```
CMD_SET_WAYPOINT_AT_COORDS (Uhr -> Handy, WRITE auf CHAR_CONTROL_UUID)
Payload: uint8 waypointId, int32 lat_e7, int32 lon_e7 (Little-Endian,
         gleiche 1e7-Fixpunkt-Konvention wie GpsPacket)
```

Gegenstück zu `CMD_SET_WAYPOINT` (setzt immer die AKTUELLE Boots-Position)
für Wegpunkte, die per Kartentipp auf der Uhr gewählt werden — genau wie
`WaypointMapPickScreen.kt` am Handy, nur dass die Koordinate hier erst per
BLE zum Handy muss, statt direkt lokal in die DataStore-Wegpunkte
geschrieben zu werden. Handy-seitig behandelt in
`SegeluhrViewModel.setWaypointAtCoords()` — ruft denselben
`settingsRepo.setWaypoint(key, point)`-Pfad wie `finishWaypointMapPick()`
auf. Nur die Wegpunkt-Typen aus `WaypointSetFlag` werden unterstützt (kein
`LAKE_CENTER` — See-Kreise bleiben eine reine Setup-Aufgabe am Handy,
siehe `Erweiterung_Seegrenze_Zeichnen.md`).

**Rückwärtskompatibel:** rein additiv, ändert nichts an bestehenden
Paketen/Befehlen. Die T-Watch-Ultra-Firmware kennt den neuen Befehl nicht
und muss ihn auch nicht kennen (sendet ihn nie).

## Projekt-Struktur

```
Segeluhr-GalaxyWatch/
  app/src/main/java/com/segeluhr/watch/
    MainActivity.kt
    ble/
      BleProtocol.kt          Portierung der Handy-Seite (Central/Decode statt Peripheral/Encode)
      WatchBleClient.kt        GATT-Central: Scan/Connect/Notify-Abo/Control-Write, GATT-Operations-Queue
      WatchBleBridge.kt        Singleton (ViewModel + Foreground-Service teilen sich EINEN Client)
      WatchBleForegroundService.kt  Hält die BLE-Verbindung bei ausgeschaltetem Display am Leben
    core/
      HapticPlayer.kt          1:1 VibrationPatterns.kt vom Handy, nur als Empfänger statt Erzeuger
    data/WatchUiState.kt        Aggregierter UI-Zustand + BoatState-Ableitung (analog updateBoatState())
    viewmodel/SegeluhrWatchViewModel.kt
    ui/
      SegelnApp.kt              Pager-Wurzel + Overlay
      theme/                    1:1 Farbpalette vom Handy (Wear-Compose-MaterialTheme statt Material3)
      screens/                  Nav/Wind/Home/Countdown/Maneuver/Menu
      components/               StatusBar, CommandOverlay, RoundingConfirmBanner, SailScreenScaffold
```

## 14.08.2026 Abend: erster Hardware-Test (echte Galaxy Watch 5 Pro)

Per ADB-over-WiFi (Pairing-Code-Flow, kein USB an der Uhr) auf Romans
echter Watch 5 Pro installiert und getestet — erste echte Verifikation
dieses ganzen Projekts. Ergebnisse:

- **Kompiliert + installiert + startet ohne Absturz.** BLE-Verbindung zum
  Handy (im "Mit Uhr"-Modus) funktioniert, Heim-Tab empfängt Live-Status.
- **Bug gefunden+gefixt: Statusleiste auf rundem Display abgeschnitten** —
  `StatusBar.kt` pinnte die drei Texte (BLE-Status/Uhrzeit/Akku) per
  `Arrangement.SpaceBetween` an den vollen Breitenrand (nur 20dp Padding).
  Auf einem RUNDEN Display (anders als beim rechteckigen Vorbild
  `statusBarUpdate()` auf der Ultra) liegt die obere linke/rechte Ecke
  ausserhalb des sichtbaren Kreises — abgeschnitten. Fix: das ganze
  Text-Cluster zentriert (`Box`+`Arrangement.spacedBy` statt SpaceBetween)
  plus grösseres Top-Padding via `LocalConfiguration.current.isScreenRound`.
  Auf Hardware verifiziert.
- **Wegpunkt-Kartenpicker auf der Uhr komplett gestrichen** (Roman-
  Feedback: Display zu klein dafür) — `WaypointMapPickScreen.kt`+
  `core/GeoPoint.kt` gelöscht, osmdroid-Abhängigkeit raus, `MenuScreen.kt`
  hat pro Wegpunkt nur noch "Hier"/"Löschen" (wie die Ultra). `CMD_SET_WAYPOINT_AT_COORDS`
  bleibt als reservierte Protokoll-Nummer dokumentiert (Handy-Seite kennt
  den Befehl weiterhin, schadet nicht), Encode-Funktion+Client-Aufruf auf
  der Uhr-Seite entfernt.
- **Bug gefunden+gefixt: Scrollen im Menu-Tab ruckelte** — `MenuScreen.kt`
  bekam bisher das komplette `WatchUiState` übergeben, obwohl es nur
  `waypoints`+`home.active` braucht. Da GPS/Wind 1×/Sekunde vom Handy
  reinkommen, baute Compose die komplette `ScalingLazyColumn` bei JEDEM
  Tick neu auf — auch mitten in der Scroll-Geste. Fix: nur die zwei
  tatsächlich gebrauchten Felder als Parameter statt des ganzen Zustands,
  Compose überspringt die Neuzusammenstellung jetzt, wenn sich nur
  GPS/Wind ändern. Auf Hardware als spürbar besser bestätigt.
- **Neu: Start/Reset/Sync direkt auf dem CD-Tab** (Roman-Wunsch, analog
  zur Ultra-Nachbesserung vom 12.08. "Fach-Tab-Aktionen") — vorher nur im
  Menu-Tab erreichbar. Erster Versuch mit festbreiten 64dp-`Chip`s passte
  nicht in die ca. 168dp verfügbare Breite (212dp Displaybreite bei 340dpi
  Dichte, abzüglich 22dp Seiten-Padding je Seite) und schnitt "Sync"
  rechts ab — gefixt mit `CompactChip` (Wear-Compose-Komponente extra für
  schmale Sekundär-Aktionen). Auf Hardware verifiziert, alle drei Buttons
  vollständig sichtbar.
- Countdown-Dauer selbst ist NICHT einstellbar (weder hier noch am Handy)
  — die 5-4-1-Startsequenz ist laut `StartCountdownEngine.kt`/
  `Constants.COUNTDOWN_DURATION_MS` bewusst fest auf 5 Minuten verdrahtet
  (Standard-Regatta-Startprotokoll), kein fehlendes Feature.

## 02.09.2026 (spät): vier neue Wegpunkte fürs Competition-Kursmodell

Roman-Korrektur am Kurs-Modell (siehe `Erweiterung_Competition_Kursmodell.md`
am Handy) — `MenuScreen.kt` bekam vier neue Wegpunkt-Zeilen (Lee-Boje,
Gate A, Gate B, Zielboje), gleiches "Hier setzen"/"Löschen"-Muster wie die
bestehenden. `BleProtocol.kt` entsprechend erweitert: `WaypointId` 10-13,
neues `WaypointSetFlag2` für den 2. Byte der jetzt 2-Byte-
`WaypointsStatus` (`decodeWaypointsStatus()` liest Byte 2 defensiv per
`getOrNull(1)`, fällt auf `false` zurück statt abzustürzen). Welche der
vier Zeilen tatsächlich gebraucht werden, hängt von der am Handy gewählten
Lee-Variante ab — auf der Uhr trotzdem immer alle vier sichtbar (gleiches
Prinzip wie schon bei Marke1/Marke2, die Uhr kennt die Lee-Variante selbst
nicht). Kein Kartenpicker (wie bei den übrigen Wegpunkten hier schon
14.08. gestrichen), nur "an der aktuellen Boots-Position".

**Nur geschrieben, nicht kompiliert/getestet** — kein Android-SDK hier.

## Offene Punkte

- **Kein Wear-Compose-Vorschau/Emulator-Test** — nur auf der echten Watch
  5 Pro getestet, kein AVD/Emulator in dieser Umgebung vorhanden.
- **BLE-Permission-Flow minimal**: `MainActivity` fragt Runtime-
  Permissions einmal beim Start an, es gibt aber (anders als am Handy)
  noch keine eigene UI für "Permission wurde abgelehnt, bitte in den
  Einstellungen nachtragen".
- Weiteres Nutzungsverhalten (Akkulaufzeit über eine ganze Session,
  Reconnect nach BLE-Abbruch, Verhalten bei ausgeschaltetem Display über
  längere Zeit) noch nicht getestet — nur eine kurze Schreibtisch-Session
  heute Abend.

## 02.09.2026: Physische-Tasten-Bedienung (Wasserdicht-Modus)

**Roman-Problem:** beim Segeln wird die Uhr nass, der Touchscreen ist dann
über den Wasserdicht-Modus deaktiviert — bisher war die App damit während
der Fahrt unbedienbar (nur in Pausen per Touch nutzbar). Einzige
verbleibende Eingabe ist die untere ("Zurück"-)Taste der Watch 5 Pro
(`KEYCODE_BACK`), die im Wasserdicht-Modus laut Roman weiterhin
funktioniert. Die obere Taste ist auf Wear OS i.d.R. system-reserviert
(Power/Bixby/Home) und wird Vordergrund-Apps normalerweise nicht
zugestellt — `MainActivity` fängt defensiv zusätzlich `KEYCODE_STEM_1` ab,
falls sie auf dieser Watch doch durchkommt, unverifiziert bis zum nächsten
Hardwaretest.

**Gestenmuster (Roman-Entscheidung, eine Taste, zwei Gesten):**
- **Kurzer Druck = Kontextaktion.** Aktuell nur auf dem CD-Tab belegt,
  zustandsabhängig (nur eine Taste für drei Aktionen verfügbar): nicht
  gestartet → `CMD_COUNTDOWN_START`, läuft (COUNTDOWN) →
  `CMD_COUNTDOWN_SYNC_NEXT_MINUTE`, läuft (RACE) → `CMD_COUNTDOWN_RESET`.
  Auf allen anderen Tabs aktuell ein No-Op. `CountdownScreen.kt` zeigt
  einen Hinweistext ("Taste: Start"/"Sync"/"Reset"), der exakt dieselbe
  Logik spiegelt — einübbar per Touch in der Pause.
- **Langer Druck (System-Standard-Schwellwert, `ViewConfiguration.
  getLongPressTimeout()`) = nächster Tab**, im Ring (Menu→Nav→...).
- Beide Gesten lösen zusätzlich eine kurze, unterscheidbare Vibration aus
  (1 Puls = Kontextaktion, 2 Pulse = Tab-Wechsel) — blinde Bestätigung,
  da man aufs Display schauen, aber nicht antippen kann.
- **Bewusst NICHT belegt:** Bojen-Rundungs-Bestätigung (`RoundingConfirm
  Banner`) — soll laut Roman langfristig automatisch erkannt werden
  (geplante Auswertung aus Wassertest-Daten), deshalb hier kein manueller
  Tasten-Weg dafür gebaut. Heimweg an/aus war nicht in der Muss-Liste,
  bleibt vorerst Touch-only im Menu-Tab.

**Technisch:** `MainActivity.dispatchKeyEvent()` misst die Druckdauer
(`ACTION_DOWN`-Zeitstempel bis `ACTION_UP`, `repeatCount==0`-Guard gegen
Auto-Repeat beim Halten) und ruft `SegeluhrWatchViewModel.
onHardwareButtonShortPress()`/`onHardwareButtonLongPress()`. Da der aktive
Tab im `HorizontalPager`-Zustand lebt (Compose-UI), nicht im ViewModel,
meldet `SegelnApp.kt` den aktuellen Seitenindex per `onTabChanged()`
zurück; für Tab-Navigation aus dem ViewModel heraus (Taste ODER Auto-
Fokus, siehe unten) gibt es umgekehrt `navigateToTab: SharedFlow<Int>`,
den `SegelnApp.kt` per `LaunchedEffect` abonniert und mit
`pagerState.animateScrollToPage()` umsetzt.

**Zusätzlich: Auto-Fokus auf den Nav-Tab beim Wettfahrt-Start**
(derselbe Roman-Wunsch-Kontext) — im Startmoment (0:00-Signal,
`StartCountdownEngine` COUNTDOWN→RACE) sind beide Hände typischerweise am
Boot beschäftigt, die App springt jetzt von selbst auf den Nav-Tab
(SOG/VMC/Manöver-Ampel), statt dass man das manuell nachholen muss.
`SegeluhrWatchViewModel.watchForRaceStart()` beobachtet dafür
`raceData.raceStateOrdinal` auf den Flankenwechsel 1→2 (COUNTDOWN→RACE)
und emittiert denselben `navigateToTab`-Flow — unabhängig davon, ob es
sich um eine echte Competition oder einen freien Race-Timer handelt,
beide feuern denselben `onRaceStart()` in `StartCountdownEngine`.

**Nur geschrieben, NICHT kompiliert/getestet** — kein Android-SDK/Plugin-
Cache in dieser Umgebung (Netzwerk zum Google-Maven-Repository blockiert),
`gradle compileDebugKotlin` schlägt schon beim Plugin-Auflösen fehl.
Verifikation (inkl. ob `KEYCODE_STEM_1` auf dieser Watch überhaupt
ankommt, ob die Kurz/Lang-Schwelle sich auf dem Wasser gut anfühlt, ob die
Sync/Reset-Zuordnung im Ernstfall die richtige ist) steht beim nächsten
Hardwaretest aus.

**Nachtrag 02.09.2026 (spät): Pin/Boot per Taste.** Roman-Nachfrage bei
der Kurs-Modell-Überarbeitung: die Startlinie (Pin/Boot) wird
erfahrungsgemäss noch kurz vor dem Start final gelegt/korrigiert — anders
als Marke1/Lee-Boje/Gate (stehen laut Kurs-Modell schon vorher fest)
brauchte sie deshalb noch eine eigene Tasten-Aktion, nicht nur Touch im
Menu-Tab. Liegt jetzt auf dem **Nav-Tab** (bisher ohne jede Tasten-
Funktion, kurzer Druck war dort No-Op) statt auf einem thematisch
naheliegenderen Tab, weil Nav der Standard-/meistgesehene Tab ist:
zustandslose feste Regel — Pin fehlt → Pin setzen, sonst Boot fehlt →
Boot setzen, sonst (beide schon gesetzt) → wieder Pin (Annahme: der wird
laut Startlinie-Bias-Logik öfter nachjustiert als das Committee-Boot,
noch nicht durch echte Praxis bestätigt). Hinweistext "Taste: Pin/Boot
setzen" unten auf dem Nav-Tab spiegelt die Regel live. Reine
Wiederverwendung von `CMD_SET_WAYPOINT`/`WaypointId.PIN`/`.BOAT` — kein
neuer BLE-Befehl nötig. **Nur geschrieben, nicht getestet** — ob die feste
"immer wieder Pin"-Regel im Ernstfall die richtige ist (vs. z.B.
Alternierung), steht beim nächsten Hardwaretest aus.
