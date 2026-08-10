# Erweiterung: Start-Rollenwahl + App-Stopp

Nicht Teil der ursprünglichen Spezifikation. Zwei zusammenhängende
Ergänzungen (10.08.2026, Roman-Wunsch):

1. **Wahlbildschirm bei App-Start** — "Auf dem Boot" oder "An Land", statt
   stillschweigend immer in der Boots-Rolle zu starten und den Umschalter
   im Setup-Tab suchen zu müssen.
2. **Stopp-Button** — GPS lief bisher durchgehend, solange der App-Prozess
   lebte (kein Mechanismus, es anzuhalten ausser die App komplett zu
   schliessen).

## 1. Start-Rollenwahl (`RolePickerScreen.kt`)

Neuer Vollbild-Screen, wird in `MainActivity.kt` VOR allen anderen Screens
geprüft (`if (!state.roleConfirmedThisSession) { RolePickerScreen(...); return }`)
— zwei grosse Karten "Auf dem Boot"/"An Land", je mit einem dezenten
Hintergrund-Icon (`Icons.Filled.Sailing`/`Icons.Filled.Home`, klein und
transparent gehalten — kein dominantes Hero-Element, nur Wiedererkennung).
Die zuletzt genutzte Rolle ist leicht hervorgehoben ("zuletzt"-Badge).

**Erscheint bei JEDEM App-Start** (Roman-Entscheidung, nicht nur einmalig)
— `SegeluhrUiState.roleConfirmedThisSession` ist bewusst NICHT persistiert
(Default `false`), unabhängig von der tatsächlichen, weiterhin über
`SettingsRepository.appRoleFlow` persistierten Rolle. Ein Tippen auf eine
Karte ruft `SegeluhrViewModel.confirmRole(role)` auf — setzt die Rolle UND
markiert die Session als bestätigt, danach normaler Tab-Baum bzw.
Land-Screen wie gehabt.

**Setup-Tab bleibt der Weg zum Umschalten WÄHREND einer Session** (Roman-
Bestätigung) — unverändert, der Wahlbildschirm ersetzt das nicht, ergänzt
nur den Einstiegspunkt.

## 2. App-Stopp (Setup-Tab, Sektion "App-Betrieb")

### Warum GPS bisher durchgehend lief

`SegeluhrViewModel.onLocationPermissionResult()` startet bei erteilter
Berechtigung einen `viewModelScope.launch { locationProvider.fixFlow()
.collect {...} }` — dieser Coroutine-Job lief bisher, bis die ViewModel-
Instanz selbst zerstört wird (praktisch: bis der App-Prozess endet). Kein
Weg, ihn gezielt zu pausieren, ausser die App zu schliessen. Dieselbe
Situation bei der 1-Hz-Tickschleife (`startTicker()`).

### Umsetzung

- **Job-Referenzen** (`gpsJob`/`tickerJob`, beide `Job?`) statt der bisher
  "fire and forget"-Coroutinen — `startGps()`/`startTicker()` canceln einen
  evtl. laufenden alten Job zuerst (idempotent, kann gefahrlos mehrfach
  aufgerufen werden).
- **`pauseBackgroundWork()`**: cancelt beide Jobs + stoppt den Foreground-
  Service (`SegeluhrForegroundService.stop()`, dasselbe Cleanup wie beim
  Umschalten auf `OperationMode.STANDALONE`). Keine Datenlöschung — nur
  Pause.
- **`resumeBackgroundWork()`**: startet GPS (nur falls Berechtigung schon
  erteilt), Tickschleife, und den Foreground-Service (nur falls
  `OperationMode.WITH_WATCH` aktiv ist).
- **`stopApp()`/`startApp()`** (neue öffentliche ViewModel-Funktionen,
  Setup-Tab-Button "Stopp"/"Start"): rufen direkt `pauseBackgroundWork()`/
  `resumeBackgroundWork()` auf, setzen `uiState.appStopped`.
- **TopAppBar-Statusanzeige**: neuer "Gestoppt"-Zustand (graue Punktfarbe)
  hat Vorrang vor der normalen Fix-Anzeige — sonst würde ein alter,
  eingefrorener GPS-Status weiter angezeigt, obwohl gar keine Updates mehr
  reinkommen.
- **`appStopped` nicht persistiert** — jeder frische App-Start beginnt
  aktiv (wie bisher), Stopp ist eine bewusste Aktion innerhalb der Session,
  kein Dauerzustand über Neustarts hinweg.

### Kopplung mit der Rolle (Bonus-Fix)

`setAppRole()` ruft jetzt automatisch `pauseBackgroundWork()` beim
Wechsel zu `AppRole.SHORE` bzw. `resumeBackgroundWork()` beim Wechsel
zurück zu `AppRole.SAILOR` auf — löst nebenbei einen bereits dokumentierten
offenen Punkt aus `docs/Erweiterung_Landuhr_Kartenansicht.md` ("Bewusst
nicht Teil dieser Ausbaustufe": SegeluhrViewModel lief bisher unnötig
weiter, auch während die App im Land-Modus war). Ausnahme: wurde
ZUSÄTZLICH manuell gestoppt (`appStopped = true`), überschreibt ein
Rollenwechsel zurück zu "Auf dem Boot" das nicht automatisch — sonst würde
ein simpler Rollenwechsel einen bewussten Stopp aufheben.

## Bewusste Vereinfachungen

- Kein Stopp der separaten Land-Rolle-BLE-Verbindung (`LandUhrViewModel`,
  eigene ViewModel-Instanz) — die läuft nur, wenn `AppRole.SHORE` ohnehin
  aktiv gewählt ist (Compose `viewModel()`-Scoping, siehe
  `docs/Erweiterung_Landuhr_Kartenansicht.md`), kein zusätzlicher
  Stopp-Mechanismus dafür nötig.
- Kein Bestätigungsdialog vor "Stopp" (anders als bei "Alles zurücksetzen")
  — reine Pause, nichts geht verloren, kein Risiko.
- Der Wahlbildschirm hat keine "Diesen Bildschirm nicht mehr zeigen"-
  Option — Roman-Entscheidung, soll bewusst bei jedem Start erscheinen.

**Noch nicht kompiliert/getestet.**
