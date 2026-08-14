# Segeluhr — Projekt-Status

Diese Datei ist die zentrale Übersicht über alle Komponenten. **Bitte bei
jeder Session, in der sich etwas Wesentliches ändert, aktualisieren** —
das ist der eigentliche Zweck: ein Blick hier reicht, um zu wissen, was
gerade läuft, was Baustelle und was nur Test ist.

## Status-Legende

✅ läuft/getestet · 🔧 in Arbeit · 📋 geplant, noch nicht gebaut · 🗄️ archiviert/nicht mehr aktiv gepflegt

## Übersicht

| Komponente | Hardware | Status | Zuletzt getestet | Bemerkung |
|---|---|---|---|---|
| Android-App | Handy (Samsung) | 🔧 | 28.07.2026 (Kernfunktionalität), 10.08.2026 (Diagnose-Log, Seegrenze-Zeichnen, Rollenwahl+App-Stopp, Boots-Profile auf Hardware verifiziert), 12.08.2026 Abend (Build+Install+Start ohne Absturz, "Home setzen" End-to-End) | Master: GATT-Server, alle Engines, BLE-Protokoll. **Automatische See-Erkennung (docs/Erweiterung_Automatische_See_Erkennung.md) 06.08. implementiert** — Overpass-API + Kreis-Ketten-Berechnung statt der urspr. geplanten JTS-Polygon-Lösung (LakeGeofenceEngine kannte nur Kreise, kein Polygon-Support), Kette statt Einzelkreis für lange/unregelmässige Seen. `LakeGeofenceEngine`/`SettingsRepository`/Setup-UI dafür umgebaut. **10.08. Abend kompiliert** (Teil des App-Builds, siehe unten) — die Funktion selbst ("See automatisch erkennen", Overpass-Abfrage) heute nicht gezielt angetestet, nur die neue manuelle Alternative (siehe "See-Grenze auf Karte einzeichnen" unten). Bekannte Einschränkung: nur einfache OSM-Ways, keine Multipolygon-Relationen. **Landuhr-Kartenansicht (docs/Erweiterung_Landuhr_Kartenansicht.md) 09.08. implementiert und auf echter Hardware Ende-zu-Ende verifiziert** — neuer Rollen-Umschalter "Auf dem Boot"/"An Land" (Setup-Tab), im Land-Modus zeigt die App statt der Segel-Tabs eine osmdroid-Karte mit der zuletzt per LoRa/BLE von der Land-Uhr empfangenen Boot-Position (`ble/LandUhrClient.kt`, neue BLE-Central-Rolle, unabhängig vom bestehenden GATT-Server) — Marker erscheint korrekt. Dabei zwei Bugs gefunden+gefixt (Details siehe Doku): korrupte NVS-Bluetooth-Kalibrierungsdaten auf der S3 nach Core-Wechsel (Fix: Flash-Erase), fehlende `BLUETOOTH_SCAN`-Berechtigung bei Installs mit schon erteiltem Standortzugriff (Fix: eigener Berechtigungs-Check/-Button). Nur kurz angetestet, noch nicht über eine längere Session. **10.08.: Session-Distanz-Aufsummierung implementiert** (`core/DistanceTracker.kt`, siehe "Bekannte offene Punkte" unten) — **10.08. Abend kompiliert + auf Hardware installiert**, Startwert 0 und "Alles zurücksetzen" verifiziert; Hochzählen während Bewegung/Stabilität im Stand noch offen (braucht echtes Laufen/Fahren, geplant 11.08.). **10.08.: Heimweg-ETA/VMC träger gemacht** (`core/HomeProgressTracker.kt`, siehe "Bekannte offene Punkte" unten) — **10.08. Abend kompiliert** (Teil des App-Builds), funktionaler Test noch offen (braucht Heimatpunkt + Bewegung, geplant 11.08.). **10.08.: Boots-Kalibrierung (Am-Wind-Wendewinkel) implementiert** (docs/Erweiterung_Boots_Kalibrierung.md) — Kalibrierungsmodus + Smart-Modus in `WindEngine`, ersetzt den fest verdrahteten 45°-Wert in `HomeEngine`/`CompetitionEngine`. **10.08. Abend kompiliert**, funktionaler Kalibrierungs-Test (Wind-Tab, Am-Wind-Werte erfassen) noch offen, braucht echten Wind/Kurs (geplant 11.08.). **10.08.: auf mehrere benannte Boots-Profile umgebaut** (Setup-Tab-Verwaltung, JSON-Liste in DataStore) — Grundprofil "Musto Skiff" (43°, aus vom Nutzer bereitgestellten Referenzdaten) statt reinem 45°-Fallback, neue Profile starten bei 45°. **10.08. Abend auf Hardware verifiziert**: Grundprofil/Startwert, Profil anlegen/wechseln/löschen liefen alle wie erwartet. **10.08.: `distanceTraveledM` per LoRa an die Land-Uhr weitergegeben** — Home-Status-BLE-Characteristic 3→7 Byte (`BleProtocol.encodeHomeStatus`), siehe auch Ultra-/S3-Zeilen unten und `docs/BLE_Protokoll_Ergaenzung_Heimweg_LoRa.md`. **10.08. Abend kompiliert + End-to-End mit beiden Uhren verifiziert** (siehe `docs/Test_Checkliste_10_08.md`, Ultra-/S3-Zeilen unten) — Wert kam heute nahe 0 an (kein GPS-Weg zurückgelegt, nur Schreibtisch-Test), Übertragung/Format bestätigt. **10.08.: Vorwind-Winkel/Halse-Erkennung ergänzt** (docs/Erweiterung_Boots_Kalibrierung.md, Abschnitt "Vorwind-Winkel") — neues `WindEngine.downwindAngleDeg`, gelernt ausschliesslich per Smart-Modus (kein eigener Kalibrierungsmodus, kein TWS-Sensor vorhanden → bewusst gegen ein tactics_pi-artiges Windstärke-Band-Polardiagramm entschieden), `HomeEngine`/`CompetitionEngine` nutzen den Wert jetzt für Vorwind-Legs statt starrem `Wind+180°`. **10.08. Abend kompiliert**, funktionaler Test noch offen (braucht echtes Segeln vorm Wind, geplant 11.08.). **10.08.: Diagnose-Log implementiert** (docs/Erweiterung_Diagnose_Log.md) — `data/diagnostics/DiagnosticsLogger.kt` schreibt 1x/s den kompletten internen Zustand als CSV (App-intern, FileProvider fürs Teilen), Setup-Tab-Sektion mit Ein/Aus-Schalter (Standard AN), "Ereignis markieren"-Button, "Log teilen"-Button. Für den ersten Segeltörn heute Abend gedacht, siehe `docs/Testfahrt_Strategie_10_08.md`. **10.08. Abend komplett auf Hardware verifiziert**: Schalter AN, Zeilenzähler läuft, "Ereignis markieren" funktioniert, Share-Sheet öffnet sich, geteilte CSV hat plausible Kopf-/Datenzeilen. **10.08.: See-Grenze auf Karte einzeichnen implementiert** (docs/Erweiterung_Seegrenze_Zeichnen.md) — dritter Weg neben automatischer OSM-Erkennung und GPS-Rand-Abfahren, für Seen wie den Zürichsee, die als OSM-Relation (nicht "way") erfasst sind und deshalb von der Auto-Erkennung nicht gefunden werden. Neuer `LakeDrawScreen` (osmdroid-Karte, Uferpunkte antippen), Kreis-Packungs-Logik aus `LakeAutoDetector` nach `geo/CirclePacking.kt` ausgelagert (quellen-unabhängig, von beiden Wegen genutzt). **10.08. Abend kompiliert + auf Hardware verifiziert** (Karte öffnet, Punkte setzen/entfernen, Fertig/Abbrechen, neue Kreis-Kette erscheint) — dabei einen Compile-Fehler gefunden+gefixt: `MapEventsOverlay`/`MapEventsReceiver` waren aus dem falschen Package importiert (`org.osmdroid.views.overlay.mapevents.*` existiert in osmdroid 6.1.20 nicht; korrekt `org.osmdroid.events.MapEventsReceiver` + `org.osmdroid.views.overlay.MapEventsOverlay`). **10.08.: Start-Rollenwahl + App-Stopp implementiert** (docs/Erweiterung_App_Stopp_Rollenwahl.md) — neuer `RolePickerScreen` erscheint bei JEDEM App-Start (See/Land-Wahl mit Icon), Setup-Tab-Sektion "App-Betrieb" mit Stopp/Start-Button hält GPS/Tickschleife/Foreground-Service gezielt an (bisher lief GPS durchgehend, solange der Prozess lebte). Nebeneffekt: löst den seit 09.08. bekannten Punkt "SegeluhrViewModel läuft im Land-Modus unnötig weiter" (siehe Erweiterung_Landuhr_Kartenansicht.md). **10.08. Abend auf Hardware verifiziert**: Wahlbildschirm erscheint bei jedem Neustart mit "zuletzt"-Badge, Stopp/Start friert GPS ein/setzt fort, Rollenwechsel im Setup-Tab pausiert/reaktiviert automatisch. **10.08.: Vereinheitlichte Bojen-/Marken-Rundungserkennung implementiert** (docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md) — neue `logic/MarkRoundingDetector.kt` ersetzt drei bisher unterschiedliche Heuristiken (Training-Racemode/Competition-mit-Luvbake/Competition-ohne-Luvbake) durch eine gemeinsame Logik: Distanz zur gesetzten Marke zuerst, sonst Amwind/Vorwind-Kurswechsel (150m-Gate-Radius löst ein Bestätigen/Ablehnen-Banner aus, ansonsten automatisches Setzen ohne Marke). Neues `PendingBuoyConfirmation`-Feld im UiState, 20s-Auto-Bestätigen-Timeout, `BuoyRoundingConfirmBanner` tab-unabhängig in `MainActivity.kt`. BLE-Protokoll um `CMD_CONFIRM_BUOY_ROUNDING`/`CMD_REJECT_BUOY_ROUNDING` sowie ein neues Flag-Bit in `RaceStatusPacket` (Grösse unverändert, 5 Byte) ergänzt — betrifft auch die Ultra-Firmware, siehe dort. **10.08. Abend kompiliert + geflasht**, funktionaler Test (Banner + Tilt/Shake-Geste + Taster-Fallback) noch offen — braucht echten Positionsversatz zur Boje, geplant 11.08. draussen. **12.08.: grosse Nachtrags-Runde** (Roman-Wunschliste "vor dem nächsten Test" + Folgefragen, alles in `docs/Erweiterung_TWatch_Ultra_NavRedesign.md` Abschnitt 6 dokumentiert) — Bugfix `sendControlCommand()`/`CHAR_CONTROL_UUID`: Ultra sendete "Write ohne Antwort", Handy deklariert die Characteristic aber nur mit "Write mit Antwort" — erklärt vermutlich den gemeldeten "Home setzen tut nichts"-Bug, betraf potenziell ALLE `CMD_*`-Befehle. Neue `CHAR_WAYPOINTS_STATUS_UUID` (1x/s Bitmaske, welche Wegpunkte gesetzt sind) fürs Button-Feedback. `CompetitionEngine.kt`: Manöver-Vorschläge entschärft (10s Start-Grace, 15s Push-Cooldown), Nav-Tab-Anzeige bleibt bewusst live für Rot/Grün. Bugfix `stopCompetition()` (fehlendes `countdownEngine.reset()`, `raceState` blieb sonst auf RACE hängen). Heimweg: Auto-Stopp bei Ankunft (20m) + automatische "BIN ZURÜCK!"-Quick-Message per neuem LoRa-Flag an die Land-Uhr. Neue Kartenauswahl für Bojen/Comp.-Marken in der App (`WaypointMapPickScreen.kt`, docs/Erweiterung_Boje_Kartenauswahl.md). **Alles heute nur kompiliert** (`compileDebugKotlin`+`assembleDebug` erfolgreich — erstmals ein lokaler Gradle-Build ohne `gradlew` möglich, siehe [[gradle-local-compile-setup]]), noch nicht auf Hardware getestet, siehe `docs/Test_Checkliste_12_08.md`. **12.08. Abend: `assembleDebug` (bereits UP-TO-DATE vom Nachmittag) per `adb install -r` aufs Handy installiert, App startet ohne `AndroidRuntime`-Fatal-Exception** (nur harmlose Vendor-/Bluetooth-Log-Zeilen). Damit ist Checkliste Abschnitt 1 komplett grün. Im Anschluss Abschnitt 2a angetestet: "Home setzen" auf der Ultra kommt jetzt tatsächlich am Handy an (Heimatpunkt-Feld im Setup-Tab füllt sich) — der eigentliche Auslöser der ganzen Nachtrags-Runde ist damit bestätigt behoben, siehe Ultra-Zeile unten für den dabei gefundenen Absturz-Bug. **14.08.: neuer BLE-Befehl `CMD_SET_WAYPOINT_AT_COORDS`** (`BleProtocol.kt`/`SegeluhrViewModel.setWaypointAtCoords()`) für die neue Galaxy-Watch-App (siehe eigene Zeile unten) — rein additiv, setzt Wegpunkte per vom Client übermittelter Koordinate statt der aktuellen Boots-Position, ruft denselben `settingsRepo.setWaypoint()`-Pfad wie die bestehende `WaypointMapPickScreen.kt` auf. Nur kompiliert (kein Android-SDK in dieser Umgebung), nicht getestet. |
| **Galaxy-Watch-App** (NEU) | Samsung Galaxy Watch 5 Pro (LTE) | 🔧 | — | **14.08.2026, Roman-Wunsch**: zusätzliche Uhr-Anbindung analog zur Ultra (BLE-Client, gleiche Segel-Tabs/Haptik), aber OHNE LoRa-Anbindung an eine Land-Uhr und ohne Klio-Gestenerkennung (auf der Ultra nie zuverlässig zum Laufen gebracht, siehe dortige Zeile) — Bojen-Rundung läuft hier nur per Touch-Buttons. Eigenes Projekt `Segeluhr-GalaxyWatch/` (Kotlin/Jetpack Compose for Wear OS, Package `com.segeluhr.watch`) statt Arduino-Firmware, da die Galaxy Watch selbst Android (Wear OS) ist. Datenquelle bewusst NUR per BLE vom Handy (kein Solo-GPS/LTE-Eigennutzung), Rollenteilung identisch zur Ultra (Handy = GPS-Sensor + Navigations-Logik, Uhr = Anzeige/Haptik/Bedienung). 6 Tabs (Nav/Wind/Heim/CD/Man/Menu) als `HorizontalPager`, `WatchBleClient.kt` als GATT-Central mit eigener Operations-Queue, `WatchBleForegroundService.kt` hält die Verbindung bei ausgeschaltetem Display am Leben. Zusätzlich zur Ultra: Wegpunkte per Kartentipp direkt auf der Uhr setzbar (osmdroid-Compose-Interop, neuer BLE-Befehl `CMD_SET_WAYPOINT_AT_COORDS`, siehe Android-App-Zeile oben). Details/Architektur-Entscheidungen: `Segeluhr-Android/Segeluhr/docs/Erweiterung_GalaxyWatch_App.md`. **14.08. Abend erstmals lokal kompiliert** (`compileDebugKotlin` + `assembleDebug` erfolgreich, `local.properties` mit `sdk.dir` ergänzt) — dabei einen Compile-Fehler gefunden+gefixt: `HorizontalPager`/`rememberPagerState` (`androidx.compose.foundation.pager`) sind experimentelle APIs, `SegelnApp()` brauchte `@OptIn(ExperimentalFoundationApi::class)`. Debug-APK baut durch. Noch nicht auf echter Hardware/Emulator getestet (kein Wear-Emulator in dieser Umgebung) — insbesondere Layout auf dem runden Display und osmdroid-Kartenpicker ungeprüft. |
| `Segeluhr_TWatch_S3` (ALT) | T-Watch S3 | 🗄️ | 28.07.2026 | Ursprüngliche Boots-Uhr-Rolle (BLE Central, Alltags-/Segelmodus, Auto-Focus, Zeit-Sync) — Code jetzt nach `Segeluhr_TWatch_Ultra.ino` portiert, physische S3-Hardware wird zur Land-Uhr (siehe Zeile unten) |
| `Segeluhr_TWatch_Ultra` | T-Watch Ultra | 🔧 | 10.08.2026 (Boot + BLE-Verbindung + distanceTraveledM-Übertragung auf Hardware verifiziert, COM17), 12.08.2026 Abend (Statusleiste, S3-Akku-Layout, "Home setzen"-Absturz gefunden+gefixt — alles auf Hardware verifiziert) | Boots-Uhr, aus S3-Firmware + LoRa-Sender/Quick-Messages zusammengeführt, kompiliert + geflasht (COM17), bootet sauber. **LoRa-Status-Broadcast + Quick-Messages (beide Richtungen, JA per kurzem/NEIN per langem Tasterdruck) Ende-zu-Ende verifiziert.** Selbstempfangs-Bug gefixt. UI-Nacharbeit (Schriftgrösse, gedrehte Eck-Tabs wg. Gehäuse-Abdeckung) mehrfach nachgebessert, **94px-Tab-Leiste 09.08. auf Hardware verifiziert (Eck-Tabs sauber sichtbar)**. **09.08.: Bug "Segelmodus erzwingen hat keinen Weg zurück" gefixt** — Schalter dafür sitzt im Setup-Tab des Alltags-Screens, der beim Erzwingen verlassen wird und (da `appModeTick()` den Rückfall bei `forceSegelnMode` komplett überspringt) nicht mehr erreichbar war; neuer Button "Segelmodus beenden" jetzt im Segeln-Menü, verifiziert. LoRa-Frequenz 06.08. auf 869.525 MHz umgestellt (siehe unten). **Klio-Gestentraining (docs/Erweiterung_Gesten_Training_Klio.md) 06.08. implementiert + kompiliert** (Serial-Kommandos `TRAIN JA`/`TRAIN NEIN`/etc., NVS-Persistenz) — dabei zwei Bugs im bestehenden Gesten-Code gefunden: `USING_BHI260_SENSOR` war nie gesetzt (Gestencode lief nur als Stub, der 05.08.-Pitch-Messwert stammt vermutlich nicht aus dieser Firmware) und LilyGoLib lädt standardmässig eine BHI260-Firmware ohne Klio-Support (jetzt per `uploadFirmware()` nachgeladen) — Details siehe Doku Abschnitt 4. **Noch nicht auf Hardware getestet**, insbesondere ob der Firmware-Swap den Schwellenwert-Fallback (Pitch/Shake) weiter funktionsfähig lässt. **Standby/Wecken (docs/Erweiterung_Standby_Wecken.md) 06.08. implementiert + kompiliert**: Display-Aus nach 30s (`instance.sleepDisplay()`, kein Deep-Sleep), Aufwecken per Knopf/Geste/eingehender Quick-Message — noch nicht auf Hardware getestet. **LoRaStatusPacket sendet jetzt auch die eigene RTC-Zeit mit** (06.08., für Land-Uhr-Zeit-Sync, siehe unten) — Paket 20→26 Bytes. **QuickMessageRequest 06.08. um eigene (BLE-erstellte) Fragen erweitert** (4→37 Bytes, siehe unten) — zeigt eingehende eigene Fragen von der Land-Uhr korrekt an. **Beide Firmwares müssen zusammen neu geflasht werden**, sonst laufen sie auseinander. **BLE zum Handy 06.08. erstmals verbunden und getestet, dabei 3 Bugs gefunden + gefixt** (kompiliert + geflasht, COM17, Details siehe `docs/Offene_Punkte_Hardware_Test_05_08.md`): Absturz beim Verbinden (BLE-Notify-Callbacks riefen LVGL/I2C direkt aus dem falschen FreeRTOS-Task auf, jetzt über Flags entkoppelt und im `loop()`-Task abgearbeitet, dabei auch ein `bleClient`-Speicherleck beim Reconnect gefixt), Segeln-Tabs (Wind/Heim/CD/Man) sprangen ständig zu Nav zurück (`autoFocusTick()` jetzt auf Flankenerkennung umgebaut statt bei jedem Tick zu erzwingen), keine Uhrzeit im Segeln-Modus sichtbar (jetzt zusätzlich in der globalen Statusleiste). **Noch nicht über eine längere Session/echten Segeltörn verifiziert.** **10.08.: `LoRaStatusPacket` um `distanceTraveledM` erweitert** (27→31 Byte, siehe `docs/BLE_Protokoll_Ergaenzung_Heimweg_LoRa.md`) — Wert kommt aus dem ebenfalls erweiterten BLE-Home-Status vom Handy (`onHomeStatusNotify()`, neue Mindestlänge 7 Byte). **10.08. Abend kompiliert + geflasht (COM17), zusammen mit S3 (Land)** — bootet sauber, BLE-Verbindung zum Handy verifiziert, `distanceTraveledM` kommt End-to-End auf der Land-Uhr an (siehe S3-Zeile unten). **10.08.: Vereinheitlichte Bojen-Rundungserkennung** (docs/Erweiterung_Vereinheitlichte_Bojenerkennung.md) — neue `raceData.roundingConfirmPending`, `CMD_CONFIRM_BUOY_ROUNDING`/`CMD_REJECT_BUOY_ROUNDING`, Antwort per Wiederverwendung der bestehenden Klio-Gestenerkennung (Tilt=Ja/Shake=Nein) bzw. Taster-Fallback — bewusst KEIN neuer Eingabeweg, siehe Doku "Geste statt Haptik". Manöver-Tab zeigt bei offener Rückfrage "Boje hier?" statt Wende/Halse, Auto-Focus wechselt automatisch dorthin. **10.08. Abend kompiliert + geflasht**, funktionaler Test (Banner + Tilt/Shake-Geste + Taster-Fallback) noch offen — braucht echten Positionsversatz zur Boje, geplant 11.08. draussen. **12.08.: grosse Nachtrags-Runde** (Details siehe Android-App-Zeile oben + `docs/Erweiterung_TWatch_Ultra_NavRedesign.md` Abschnitt 6) — Fach-Tab-Aktionen (CD Start/Reset/Sync/Stop-mit-Rückfrage, Wind-Kalibrieren) direkt auf den jeweiligen Tabs statt nur im Menü. Statusleiste nach unten verschoben (y=10→34, Schätzwert). Wegpunkt-Buttons im Menü einzeln setz-/löschbar mit Grün-Feedback — behebt nebenbei einen Bug: der alte "Alle Bojen löschen"-Button löschte trotz seines Namens immer nur Boje 1. Nav-Tab zeigt jetzt Rot/Grün statt Amber/Grau (Manöver-Indikator). **On-Watch-Klio-Training** (Menü-Tab, `lv_msgbox` mit Live-Fortschritt + Schritt-für-Schritt-Anleitung durchs Kalibrierungs-Protokoll, kein USB/Laptop mehr nötig) — dabei wichtiger Befund laut Bosch-Beispielcode: ein Trainingslauf ist EINE durchgehende Session, `TRAINING_TIMEOUT_MS` deshalb von 60s auf 5min erhöht. Drei Fehlalarm-Schutz-Bausteine gegen falsche Gesten-Antworten: 3s-Bestätigungsfenster (antippen/Taster bricht ab), Klios `count`-Konfidenzschwelle genutzt (vorher komplett ignoriert), 20s-Gestensperre um Wende/Halse (Tiller-Extension-Handwechsel als grösstes bekanntes Fehlalarm-Risiko laut eigenem Kalibrierungs-Protokoll). Details siehe `docs/Uebersicht_Gestensteuerung.md`. **Alles kompiliert (Sketch 50%/12% RAM), nichts davon auf Hardware getestet**, siehe `docs/Test_Checkliste_12_08.md`. **12.08. Abend auf Hardware getestet:** Statusleiste (BLE/Akku/Uhrzeit) bei y=34 laut Roman-Feedback immer noch "deutlich mehr als die Hälfte der Zeile" vom Gehäuserand verdeckt — auf y=68 erhöht (Nav-Tab-`pad_top` entsprechend mit angehoben), verifiziert. Auf Roman-Wunsch danach zusätzlich: BLE+Akku nur noch auf Nav-Tab (Segeln) bzw. Uhr-Tab (Alltag) sichtbar, auf allen übrigen Tabs ausgeblendet (`statusBarUpdate()` prüft jetzt den aktiven Tab-Index über `lv_tabview_get_tab_act()`, neuer globaler `alltagTabview`-Zeiger fürs Alltag-Pendant zu `tabview`; Uhrzeit selbst bleibt überall sichtbar) — verifiziert. **Absturz gefunden+gefixt beim ersten Drücken von "Home setzen":** `Guru Meditation Error`/"Stack canary watchpoint triggered (loopTask)" — der heutige `sendControlCommand()`-Bugfix (Write mit statt ohne Antwort) macht den BLE-Write blockierend, direkt aus dem LVGL-Button-Klick-Handler heraus aufgerufen; der zusätzliche Stack-Bedarf von NimBLEs synchronem Write-Pfad oben auf die bereits verschachtelte LVGL-Callback-Kette riss den Standard-`loopTask`-Stack (8 KB). Fix: `loopTask`-Stack auf 16 KB erhöht — dieser esp32-Core (3.3.11) hat kein `setLoopTaskStackSize()` zur Laufzeit, stattdessen die schwach (weak) definierte `getArduinoLoopTaskStackSize()` aus `cores/esp32/main.cpp` überschrieben (muss global stehen, nicht in `setup()`, da `xTaskCreateUniversal()` sie vor `setup()` aufruft). Zweimal reproduziert (Serial-Log/Crash-Dump gesichert), nach dem Fix mehrfach verifiziert: kein Absturz mehr, "Home setzen" kommt zuverlässig am Handy an. |
| `Segeluhr_TWatch_S3` (Land) | T-Watch S3 | 🔧 | 10.08.2026 (Boot + End-to-End distanceTraveledM + Detail-Tab-Layout-Fix auf Hardware verifiziert, COM16), 12.08.2026 Abend (eigene Akku-Anzeige in Detail-Tab verschoben, auf Hardware verifiziert) | Land-Uhr (neue Rolle, ersetzt bisherige BLE-Central-Rolle), kompiliert + geflasht (COM16), bootet sauber. **LoRa-Empfang + Quick-Messages (beide Richtungen) Ende-zu-Ende verifiziert**, Buttons/Schrift nach Hardware-Test vergrössert. **Standby/Wecken 06.08. implementiert + kompiliert**: Display-Aus nach 30s, Aufwecken per Touch oder BMA423-Hardware-Tilt (LilyGoLib-Event, kein eigener Schwellenwert-Code nötig) — noch nicht auf Hardware getestet. **Menü-Redesign 06.08. implementiert + kompiliert** (docs/Erweiterung_Land_Boot_LoRa_Kommunikation.md Abschnitt 3): Icon-Grid [Stumm][Fragen]/[Alltag]/[Ausschalten] statt Liste. **Manuelles "Zeit stellen" entfernt**, Land-Uhr übernimmt Zeit jetzt automatisch aus dem ersten LoRa-Paket. **Alltagsfunktionen (docs/Erweiterung_S3_Alltagsfunktionen.md) 06.08. implementiert + kompiliert**: Wecker (Software-Vergleich statt Hardware-Alarm — reale S3 hat PCF8563 statt dokumentiertem PCF85063A, dessen Alarm-IRQ bei dieser LilyGoLib-Version im Wachzustand nicht verdrahtet ist, jetzt mit NVS-Persistenz), Stoppuhr, Schrittzähler (BMA423-Pedometer), Taschenlampe (90s-Overlay). Kein Ton beim Wecker. **BLE-Fragen-Editor (docs/Erweiterung_S3_BLE_Fragen_Editor.md) 06.08. implementiert + kompiliert**: BLE-Toggle im Fragen-Menü (NimBLE-Server, standardmäßig aus), eigene Fragen per Web-Bluetooth-Seite (`docs/fragen_editor_web/index.html`) erstellbar, max. 5 gespeichert (NVS, Ringpuffer). Alles noch nicht auf Hardware getestet. Ton bei Quick-Messages noch offen — siehe `docs/Offene_Punkte_Hardware_Test_05_08.md`. **09.08.: neue BLE-Characteristic für die Landuhr-Kartenansicht** (docs/Erweiterung_Landuhr_Kartenansicht.md) am selben Fragen-Editor-Server/Schalter — sendet die per LoRa empfangene Boot-Position ans Handy an Land. **Auf Hardware verifiziert** (mit vollem Flash-Erase gegen korrupte NVS-BT-Kalibrierungsdaten, siehe Doku), Ende-zu-Ende bis zum Karten-Marker auf dem Land-Handy bestätigt. Noch nicht mit zwei gleichzeitigen BLE-Clients getestet (Fragen-Editor-Webseite + Landuhr-App). **10.08.: Detail-Screen zeigt jetzt zusätzlich `distanceTraveledM`** ("bisher X km", in der bestehenden Paket-Info-Zeile mitgepackt statt neues Label) — Feld kommt aus dem erweiterten `LoRaStatusPacket`, siehe Ultra-Zeile oben. **10.08. Abend kompiliert + geflasht (COM16), End-to-End mit der Ultra verifiziert** — "Paket #N, Xs alt, bisher Y km" kommt korrekt an. Dabei UI-Bug gefunden+gefixt: die fünf Detail-Tab-Labels waren mit festen Pixel-Y-Koordinaten positioniert, nie für mehrzeiligen Text durchgerechnet; durch den jetzt oft mehrzeiligen Paket-Info-Text ("bisher X km") überlappten sie auf Hardware wild. Ein erster Fix (Paket-Info relativ unter dem Wind-Label per `lv_obj_align_to`) reichte nicht, weil die Ausrichtung nur einmal beim leeren Label berechnet wurde, bevor der echte Text gesetzt ist. Endgültiger Fix: Detail-Tab auf LVGL-Flex-Column umgestellt (`lv_obj_set_flex_flow`/`lv_obj_set_flex_align`) — Labels ordnen sich jetzt bei jeder Text-Änderung automatisch neu an, unabhängig von der Zeilenzahl. Auf Hardware verifiziert (COM16, dritter Reflash). **10.08. Abend: "Ausschalten" (Deep-Sleep, `cbShutdown()`) erstmals auf Hardware getestet, dabei Bug gefunden + gefixt** — mit den `instance.sleep()`-Standardquellen (Power-Taste + Touch) wachte die Uhr *jedes* Mal sofort wieder auf, auch ohne USB-Kabel und mit unbeschädigtem Taster. Per Diagnose-Build eingegrenzt: `esp_sleep_get_ext1_wakeup_status()` zeigte zuverlässig `PMU_INT=1`/`TP_INT=0` (Power-Taste-Aufwach-Pin bereits aktiv beim Sleep-Eintritt), `instance.pmu.getIrqStatus()` direkt bei Tastendruck zeigt aber `0x0` — der falsche `PKEY_SHORT_IRQ` entsteht also *während* der `sleep()`-Sequenz selbst (irgendwo im ~4s-Fenster zwischen deren `clearIrqStatus()` und `esp_deep_sleep_start()`), nicht durch alten Zustand. Genauere Eingrenzung bräuchte Debug-Ausgaben innerhalb der gepinnten LilyGoLib selbst — bewusst nicht gemacht (spät, betrifft Vendor-Code). **Fix: Aufwachen nur noch per Touch** (`instance.sleep(WAKEUP_SRC_TOUCH_PANEL)`, Power-Taste als Quelle entfernt), TP_INT ist nachweislich sauber. Zweimal auf Hardware verifiziert: bleibt dauerhaft aus bei Nichtberührung, wacht sauber per Antippen auf. Trade-off bewusst in Kauf genommen (Touch ist ohnehin die primäre Bedienung dieser Uhr). **11.08.: Bugfix `sleepDisplay()`/`wakeupDisplay()` sind auf der S3 wirkungslose Stubs** (siehe `docs/Erweiterung_Standby_Wecken.md` Abschnitt 3a) — Fix per direktem `setBrightness()`, auf Hardware verifiziert. **12.08.: eigene Akku-Anzeige auf dem Status-Tab** (`lblOwnBattery`, gleiche `instance.pmu.getBatteryPercent()`-API wie auf der Ultra) — kompiliert, nicht getestet. **12.08. Abend, Hardware-Feedback:** sass dort direkt unter `lblConnIndicator` und überlappte dessen (teils zweizeiligen) Text — verschoben nach `tabDetail` (letztes Label in dessen Flex-Column, erscheint dadurch automatisch ganz unten unter Distanz/SOG/Akku Boot/Wind/Paket-Info), auf Hardware verifiziert. |
| `segeluhr_ble_tester` | ESP32-C3/XIAO | 🗄️ | — | nur lokal vorhanden, nicht in diesem Repo, siehe `Segeluhr-Firmware/TESTING/README.md` |
| `Segeluhr_Basis_Solo` | (für Ultra gedacht) | 🗄️ | — | nur lokal vorhanden, nie auf echter Hardware getestet |
| `Segeluhr_Mastuhr` (NEU) | Eigenbau: Waveshare ESP32-S3-Touch-LCD-3.49 (integriertes Display+RTC+18650-Akkuhalter+Audio), BLE-only | 📋 | — | **13.08.2026 Konzept-Phase**: fest am Mast montiertes Gerät, Gehäuse per 3D-Druck in Eigenregie. Ursprünglich als eigenständiges GPS+LoRa-Gerät geplant (~CHF 240–260), **13.08. Abend radikal vereinfacht** (Roman-Wunsch): kein GPS, kein LoRa mehr — reine BLE-Anzeige fürs Handy, architektonisch dieselbe Rolle wie die T-Watch S3 heute (kein eigenes GPS, alle Daten per BLE). **13.08. Abend Board-Wahl finalisiert**: Waveshare ESP32-S3-Touch-LCD-3.49 (Roman-Fund) als Standard-Empfehlung statt LilyGO T-Display-S3 — bringt RTC/18650-Akkuhalter/Audio-Codec+Lautsprecher/IMU bereits eingebaut mit, kein LilyGO-Bezug, löst nebenbei die offene Ton-Frage aus `Erweiterung_S3_Ton_QuickMessages.md`. Display ist ein schmaler 172×640-Streifen statt Breitformat — UI-Neuentwurf nötig, kein Copy-Paste der bestehenden LVGL-Tabs. Neue Kostenschätzung ~CHF 85–135. Kommerzielle Alternativen (Sailmon MAX €899, Velocitek ProStart $795, Raymarine Tacktick ~$400–500) geprüft und verworfen zugunsten der Eigenkonstruktion (Integration ins bestehende BLE/LoRa-System). Details: `docs/Erweiterung_Mastuhr.md`. Noch keine Zeile Code, kein Bauteil bestellt. |

## 13.08.2026 Abend: Startlinie-Bias dokumentiert + Bugfix + BLE-Anbindung

Beim Planen der Mastuhr (siehe unten) aufgefallen: das Startlinie-Bias-
Konzept (Pin/Boot-Wegpunkte, Bias-Berechnung in `SegeluhrViewModel`,
Anzeige in `NormalScreen`) existierte schon seit der Ur-Spezifikation,
war aber nie dokumentiert und der Vorzeichen-Fehler im Code (welches
Ende als bevorzugt gilt) stand seit Einführung unverifiziert im Code.
Roman-Hinweis ("Startboot ist immer in Windrichtung rechts, gegen den
Wind geschaut") erlaubte eine analytische Auflösung ohne Wassertest —
Vorzeichen war tatsächlich vertauscht, jetzt korrigiert (zweifach
gegengerechnet: Rotations- und Projektions-Methode, gleiches Ergebnis).
`RaceStatusPacket` (BLE, Handy→Ultra) 7→9 Byte erweitert um
`lineBiasDdeg`, Ultra-Firmware parst es (noch keine eigene Anzeige dort,
Konsument ist die geplante Mastuhr). **Beide Seiten kompiliert** (Android
`compileDebugKotlin` BUILD SUCCESSFUL, Ultra-Firmware `arduino-cli
compile` fehlerfrei, 50%/12% Flash/RAM) — **noch nicht auf Hardware
getestet.** Details: `docs/Erweiterung_Startlinie_Bias.md`. Weiterhin
offen: Vorzeichen mit echtem Wind auf dem Wasser gegenchecken.

## 13.08.2026 Abend (spät): Schreibtisch-Test-Runde vor dem Wassertest

Beide Uhren + Handy neu geflasht/installiert, dann `docs/Test_Checkliste_12_08.md`
Abschnitt 2 (An-Land-Tests) durchgegangen — Details/Einzelstatus siehe dort.
Kurzfassung der dabei gefundenen+gefixten Bugs und neuen Kleinfeatures:

- **Pin-/Boot-Ende (Startlinie) jetzt auch von der Ultra aus setzbar**
  (vorher nur im App-Setup-Tab) — Protokoll (`WaypointId.PIN/BOAT`) war
  bereits vollständig verdrahtet, nur die Uhr-UI fehlte. Getestet, klappt.
- **Wind-Kalibrierung ohne Feedback auf der Uhr** — Roman-Feedback,
  gefixt: Overlay+Vibration bei Start/Abbruch wie bei den Wegpunkt-
  Buttons. Ein stehenbleibendes "läuft gerade"-Symbol ist bewusst
  zurückgestellt (braucht neues Protokollfeld), siehe
  `docs/Erweiterung_TWatch_Ultra_NavRedesign.md` Abschnitt 6.1.
- **Handy-Akku-Anzeige zeigte dauerhaft "--" am Schreibtisch** — Ursache:
  hängt am GPS-Fix (kommt huckepack auf `notifyGpsFix()`), ohne Fix nie
  gesendet. Kein Fix nötig (funktioniert draussen normal), stattdessen
  Roman-Wunsch umgesetzt: Statusleiste zeigt jetzt zusätzlich den
  **Uhr-eigenen Akku** (`instance.pmu.getBatteryPercent()`, unabhängig
  vom GPS-Fix immer verfügbar) — "H X% U Y%".
- **Klio-Training: drei Bugs in Serie gefunden** (`docs/Erweiterung_Gesten_Training_Klio.md`
  Abschnitt 5c für Details):
  1. Firmware-Upload schlug fehl ("Bad Header CRC") — fehlender
     `bhy2_soft_reset()` vor dem Nachladen der Klio-Firmware auf einen
     bereits mit GPIO-Firmware laufenden Chip. **Gefixt+verifiziert.**
  2. Trotzdem kein Fortschritts-Feedback im Dialog — `SensorBHI260AP_Klio`
     führt einen privaten Software-Cache (`k_state`), der von der bisher
     genutzten `setState()`-Überladung nicht mitgepflegt wurde, wodurch
     der Callback lokal unterdrückt blieb. **Gefixt, per Live-Rücklese
     vom Chip verifiziert** (`learning_enabled=1` direkt vom Sensor
     bestätigt).
  3. **Trotzdem feuert der Callback nicht** — Ursache trotz tiefer
     Analyse (Sensor-ID-Mapping, Callback-Registrierung, Enable-
     Reihenfolge alle geprüft und unauffällig) nicht gefunden. Bewusst
     auf den Wassertest verschoben (mehr Zeit, andere Bewegungsqualität
     testen) — Debug-Print bleibt im Code.
- Kartenauswahl für Bojen/Comp.-Marken (12.08. gebaut) **erstmals auf
  Hardware getestet, funktioniert.** Roman-Wunsch aufgenommen (auf später
  verschoben): bereits gesetzte Wegpunkte sollen auf der Karte mit
  angezeigt werden, siehe `docs/Erweiterung_Boje_Kartenauswahl.md`.
- CD-Tab-Aktionen (Start/Reset/Sync/Stopp-mit-Rückfrage) alle auf
  Hardware verifiziert.
- Heimweg-Ankunfts-Simulation (2d) und S3-Akku-Anzeige (2f) heute
  übersprungen, noch offen.

**Alle Fixes sind geflasht/installiert**, Ultra läuft mit dem finalen
Stand dieser Runde (inkl. Klio-Debug-Print).

## Bekannte offene Punkte

- ~~**S3 (Land-Uhr) zeigt eine falsche/alte Zeit**~~ — 09.08. erneut
  getestet, lief korrekt (vermutlich lag's an einem der vielen
  Test-Reflashes vom 06.08.). Root Cause im Code unverändert
  (`loraReceiveTick()`/`timeSyncedFromBoat` synct weiterhin nur einmal pro
  S3-Boot) — Roman-Entscheidung 09.08.: aktuell kein robusterer Fix nötig,
  im Zweifel S3 per RST neu starten. Details siehe
  `docs/Offene_Punkte_Hardware_Test_05_08.md`.
- ~~**"Segelmodus erzwingen" auf der Ultra hatte keinen Weg zurück**~~ —
  09.08. gefunden + gefixt (neuer Button "Segelmodus beenden" im
  Segeln-Menü) + auf Hardware verifiziert.
- **Gesten-Kalibrierung weiterhin offen** (unverändert seit 06.08.):
  `GESTURE_TILT_TARGET_ANGLE_DEG`/`GESTURE_SHAKE_MIN_AMPLITUDE` basieren auf
  nur einer Schreibtisch-Messung bzw. sind komplett unverifiziert, siehe
  `docs/Offene_Punkte_Hardware_Test_05_08.md`. **12.08.: Klio-Training jetzt
  komplett auf der Uhr durchführbar** (kein USB/Laptop mehr nötig, siehe
  Ultra-Zeile oben) — Roman-Entscheidung, das Training beim nächsten
  Wassertag tatsächlich durchzuführen, siehe `docs/Test_Checkliste_12_08.md`.
- ~~**12.08.: grosse Nachtrags-Runde komplett ungetestet**~~ — **12.08.
  Abend teilweise getestet** (Abschnitt 1 + Anfang Abschnitt 2a der
  Checkliste): Android-App gebaut+installiert+startet sauber, beide
  Firmwares geflasht+booten sauber, "Home setzen" kommt End-to-End an
  (der eigentliche Auslöser der ganzen Runde). Dabei ein bis dahin
  unentdeckter Absturz gefunden+gefixt (Stack-Overflow im `loopTask` der
  Ultra durch den heutigen blockierenden BLE-Write, siehe Ultra-Zeile
  oben) sowie zwei Hardware-Feedback-Punkte nachgebessert (Ultra-
  Statusleiste y=34→68 + BLE/Akku nur noch auf Nav-/Uhr-Tab, S3-Akku-
  Anzeige in den Detail-Tab verschoben). **Rest von Abschnitt 2 (Boje/
  Ziel/Comp.-Marken setzen+löschen, CD-/Wind-Tab-Aktionen, Heimweg-
  Ankunfts-Simulation, Bojen-Kartenauswahl, Klio-Training-Mechanik,
  Gesten-Fehlalarm-Schutz) sowie Abschnitt 4 (nur auf dem Wasser) stehen
  noch aus** — geplant für den nächsten Testtag. Konsolidierte Checkliste:
  `docs/Test_Checkliste_12_08.md`.
- ~~**Fehlende Root-Dokumente:** `Segeluhr_Spezifikation.md`/`BLE_Protokoll.md`~~
  — **10.08.2026 geklärt (Roman):** existieren nirgends, auch nicht lokal.
  Waren nur eine vorsorgliche Notiz früherer Sessions, falls es sie mal
  gegeben hätte. Die eigentliche "Spezifikation" lebt faktisch im
  Original-Browser-Prototyp (`index.html`) plus den `Erweiterung_*.md`-
  Dokumenten, die jede spätere Abweichung/Ergänzung festhalten — kein
  fehlendes Dokument, kein Handlungsbedarf.
- ~~**`distanceTraveledM`-Aufsummierung in der App**~~ — 10.08. implementiert
  (`core/DistanceTracker.kt`, Anzeige im Normal-Tab/Heimweg-Karte, Reset
  über "Alles zurücksetzen"). **10.08. Abend kompiliert + getestet**
  (Startwert/Reset verifiziert, Hochzählen bei Bewegung noch offen, 11.08.).
  ~~**Weitergabe per LoRa an die Land-Uhr**~~ — 10.08. ebenfalls
  implementiert: Home-Status-BLE-Characteristic 3->7 Byte erweitert (neues
  `distanceTraveledM`, immer mitgeschickt statt nur im Heimweg-Modus),
  `LoRaStatusPacket` in `LoRaPacket.h` 27->31 Byte, Land-Uhr zeigt's in der
  Detail-Screen-Paket-Info-Zeile. Betraf alle drei Komponenten (Android-App +
  beide Firmwares), siehe `docs/BLE_Protokoll_Ergaenzung_Heimweg_LoRa.md`.
  **10.08. Abend alle drei zusammen gebaut/geflasht, End-to-End verifiziert**
  (Ultra → LoRa → S3 zeigt "bisher Y km" korrekt an, Wert heute nahe 0 mangels
  Bewegung). Dabei auf der S3 einen Label-Überlappungs-Bug im Detail-Tab
  gefunden + auf LVGL-Flex-Layout umgebaut, siehe S3-Zeile oben.
- ~~**ETA/VMC zu reaktiv auf Momentan-Kurs, Distanz auf Land-Uhr dadurch
  verfälscht**~~ — gefunden 10.08. beim Durchspielen der Heimweg-HTML-
  Vorschau, **10.08. behoben**: neue Klasse `core/HomeProgressTracker.kt`
  misst die tatsächliche Annäherung ans Ziel über ein gleitendes
  60s-Zeitfenster (`(Distanz vor 60s − Distanz jetzt) / 60s`) statt aus dem
  Momentan-Kurs zu rechnen — ein COG-Sprung während einer Wende hat dadurch
  keinen direkten Einfluss mehr auf ETA/Distanz. Erst ab 20s Historie wird
  überhaupt ein Wert geliefert. `HomeEngine.tick()` nutzt diesen Wert jetzt
  für `HomeGuidance.vmcKn`/ETA; die Wende-Empfehlung selbst bleibt bewusst
  instantan. Tracker wird bei Modus-Ein/Aus UND bei Heimatpunkt-Änderung
  zurückgesetzt (sonst würde alte Distanz-Historie zum vorherigen Ziel die
  Berechnung verfälschen). Details siehe `docs/Erweiterung_Heimweg.md`.
  **10.08. Abend kompiliert**, funktionaler Test noch offen (braucht
  Heimatpunkt + Bewegung, geplant 11.08.).
- ~~**Boots-Kalibrierung: aktuell nur ein globales Profil, Roman will
  mehrere**~~ — **10.08.2026 umgesetzt**: `SettingsRepository.boatProfilesFlow`
  verwaltet jetzt eine Liste benannter Profile (JSON in DataStore, Muster
  wie `LAKE_CIRCLES_JSON`) + aktives Profil, Verwaltung im Setup-Tab
  ("Boots-Profil": wechseln/anlegen/löschen). Grundprofil "Musto Skiff"
  (43° Wendewinkel) aus vom Nutzer bereitgestellten Referenzdaten
  (Musto Skiff Class Association + Vergleichsklassen), neue Profile starten
  beim generischen 45°-Fallback. Details siehe
  `docs/Erweiterung_Boots_Kalibrierung.md`. **10.08. Abend kompiliert +
  auf Hardware verifiziert** (Profile anlegen/wechseln/löschen, Grundprofil-
  Wert), die eigentliche Wendewinkel-Kalibrierung per Wind-Tab braucht noch
  echten Wind/Kurs (geplant 11.08.).
- ~~**Duty-Cycle-/Kanalwahl für LoRa in der Schweiz**~~ ✅ erledigt
  06.08.2026: Frequenz beider Firmwares von 868.0 MHz auf 869.525 MHz
  umgestellt (Band 869.4-869.65 MHz, 10% statt 1% Duty-Cycle in der
  Schweiz erlaubt). **Korrektur 09.08.2026:** war hier fälschlich noch als
  "nicht geflasht" notiert - tatsächlich wurden beide Uhren schon am
  06.08. mit diesem Stand geflasht (Commit `320ab09`, nach der
  Frequenzänderung `56cf7d7`). Zusätzlich heute (09.08.) indirekt
  bestätigt: Ultra frisch mit aktuellem Source (weiterhin 869.525 MHz)
  geflasht, S3 hat direkt danach wieder erfolgreich die Zeit per LoRa
  übernommen - das geht nur, wenn beide auf derselben Frequenz funken.
- **Ton bei Quick-Messages auf der Land-Uhr (S3)**: 10.08. recherchiert —
  Hardware vorhanden (MAX98357A-Lautsprecher, bestätigt), API-Skizze in
  `docs/Erweiterung_S3_Ton_QuickMessages.md`, aber bewusst noch NICHT in
  die Firmware eingebaut (Version/Compile-Flag der installierten LilyGoLib
  zuerst gegen echte Header prüfen, statt blind zu raten).
- **DRV2605-Haptik-Stärke**: aktuelle stärkste verfügbare ROM-Effekte
  genutzt, aber keine echte Software-Gain-Kontrolle möglich (siehe
  Firmware-Kommentar bei `triggerHaptic()`) — bei Bedarf nochmal
  RTP-Modus auf Register-Ebene prüfen.
- ~~**Drei Bugs beim ersten Kompilieren/Testen der heutigen (10.08.)
  Erweiterungen gefunden**~~ — alle gefixt und verifiziert:
  `LakeDrawScreen.kt` importierte `MapEventsOverlay`/`MapEventsReceiver`
  aus dem falschen osmdroid-Package (`org.osmdroid.views.overlay.mapevents.*`
  existiert in Version 6.1.20 nicht; korrekt `org.osmdroid.events.MapEventsReceiver`
  + `org.osmdroid.views.overlay.MapEventsOverlay`) — Compile-Fehler, sofort
  aufgefallen. `TrainingScreen.kt` fehlte `.verticalScroll()` auf der äusseren
  Column (im Gegensatz zu `NormalScreen.kt`/`SetupScreen.kt`) — die neue
  "See-Geofence"-Sektion war dadurch abgeschnitten, Button nicht erreichbar.
  S3-Detail-Tab-Labels waren mit festen Pixel-Y-Koordinaten positioniert statt
  automatischem Layout — überlappten auf Hardware, sobald der neue
  `distanceTraveledM`-Text mehrzeilig wurde (siehe S3-Zeile oben für den
  Flex-Layout-Fix).
- **Offene GPS-abhängige Funktionstests, geplant 11.08. draussen**: Session-
  Distanz-Hochzählen, Heimweg-ETA/VMC-Trägheit, Boots-Kalibrierung
  (Wind-Tab), Vorwind-Winkel-Lernen, vereinheitlichte Bojenerkennung
  (Rückfrage-Banner + Geste auf der Ultra) — heute nur am Schreibtisch
  kompiliert/grundgetestet, siehe `docs/Testfahrt_Strategie_10_08.md`.
- **PMU-Power-Taste als Deep-Sleep-Aufwach-Quelle kaputt (S3, vermutlich auch
  Ultra)**: 10.08. gefunden (siehe S3-Zeile oben für Details) — Power-Taste
  weckt den Deep-Sleep sofort wieder auf, Ursache liegt irgendwo innerhalb
  der `sleep()`-Funktion der gepinnten LilyGoLib selbst, nicht in unserem
  Sketch. Als Workaround auf der S3 auf Touch-only-Wakeup umgestellt (siehe
  oben). **Ultra hat denselben `instance.sleep()`-Aufruf mit Standardquellen
  (Power-Taste + Touch) in `cbShutdown()` — noch nicht getestet, ob der
  gleiche Bug dort auch auftritt.** Bei Bedarf weiter eingrenzen: Debug-
  Ausgaben innerhalb `LilyGoWatchS3.cpp`/`LilyGoWatchUltra.cpp`
  `::sleep()` zwischen den einzelnen Schritten (Power-Rail-Abschaltungen
  zwischen `clearIrqStatus()` und `esp_deep_sleep_start()` sind der
  wahrscheinlichste Bereich).

## Ordnerstruktur

```
Segeluhr-Android/Segeluhr/     Android-App (Kotlin/Compose)
  docs/                        Erweiterungs-Dokumentation (Erweiterung_*.md)
Segeluhr-Firmware/
  ECHT/                        Firmware für echten Segelbetrieb
    Segeluhr_TWatch_S3/        ✅ aktueller Stand
    Segeluhr_TWatch_Ultra/     📋 geplant
    Segeluhr_WatchS_LoRaEmpfaenger/  📋 geplant
    Segeluhr_Mastuhr/          📋 NEU, Konzept: Eigenbau-Mast-Gerät (kein LilyGO)
  TESTING/                     Tester/Prototypen, nicht für echten Betrieb
Segeluhr-GalaxyWatch/          NEU (14.08.2026): Galaxy-Watch-App (Kotlin/
                                Jetpack Compose for Wear OS, kein Arduino/
                                Firmware-Projekt — die Galaxy Watch ist
                                selbst Android), siehe eigenes README.md
PROJEKT_STATUS.md              diese Datei
```
