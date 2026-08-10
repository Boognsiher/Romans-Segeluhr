# Erweiterung: Diagnose-Log (CSV-Mitschnitt für den ersten Segeltörn)

Nicht Teil der ursprünglichen Spezifikation. Anlass: erster Segeltörn mit
den am 10.08.2026 gebauten Features (Boots-Kalibrierung, Vorwind-Winkel,
Distanz-Tracking, Heimweg-Trägheit) — Roman wollte eine Möglichkeit, danach
"möglichst viele Infos" auswerten zu lassen, ohne dass am Boot ein Laptop
mit angeschlossenem seriellem Monitor an der Uhr-Firmware nötig ist. Das
Handy (App) ist das einzige Gerät, das während eines echten Törns sinnvoll
mitschreiben kann — die Firmware-Debug-Ausgaben (`Serial.printf`, TODO(Test-
Debug)-markiert) bleiben unverändert nur für USB-Tests am Schreibtisch
relevant.

## Was mitgeschrieben wird

`data/diagnostics/DiagnosticsLogger.kt` — bei jedem 1-Hz-Tick eine CSV-Zeile
mit praktisch dem kompletten `SegeluhrUiState`: GPS-Fix (lat/lon/SOG/COG/
Fix-Qualität), Windrichtung + Kalibrierstatus + Shift-Trend, gelernter
Wende-/Vorwind-Winkel (inkl. Kalibrierungs-/Smart-Modus-Status), Session-
Distanz, Heimweg-Guidance (Peilung/Distanz/empfohlener Kurs/Wende-Bedarf/
VMC/ETA), Competition-Guidance (Leg/Peilung/Distanz/Rundenzahl), Automatische-
See-Erkennung-Distanz, Uhr-Verbindungsstatus, Betriebsmodus, sowie der
aktuelle Status-Text/-Level (dieselbe Info, die auch im Status-Banner der
App steht).

Bewusst **kein** eigenes schlankeres Format — lieber zu viele Spalten als
im Nachhinein festzustellen, dass genau das fehlende Feld für die Auswertung
gebraucht worden wäre. Auswahl der Felder ist Claude-Ermessen (auf Wunsch
Roman: "möglichst viele Infos, welche du brauchst kannst du bestimmen").

## Speicherort und Format

- App-internes Verzeichnis (`context.filesDir/diagnostics/`), **kein**
  externer Speicher, deshalb **keine zusätzliche Berechtigung** nötig.
- Eine CSV-Datei pro App-Start (`diagnose_<Zeitstempel>.csv`), lazy beim
  ersten tatsächlichen Log-Eintrag angelegt.
- Komma-getrennt, eine Kopfzeile, deutsches Locale nur für die
  Zeitstempel-Formatierung (Zahlenwerte selbst nutzen Kotlins
  Standard-`toString()`, also Punkt als Dezimaltrennzeichen — wichtig für
  Tools, die beim Öffnen sonst falsch parsen).
- Nach jeder Zeile sofort `flush()` — kostet bei 1 Schreibvorgang/Sekunde
  nichts Messbares, verhindert aber Datenverlust bei einem App-Absturz
  mitten im Test.

## Bedienung (Setup-Tab, Sektion "Diagnose-Log")

- **Schalter "Aufzeichnung aktiv"** — Standard AN (`diagnosticsEnabled =
  true` in `SegeluhrUiState`), damit nichts vergessen werden kann. Lässt
  sich ausschalten (z.B. für private Alltagsfahrten ohne Segelbezug).
- **Zeilenzähler + Dateiname** zur Kontrolle, dass tatsächlich geschrieben
  wird.
- **"Ereignis markieren"** (Freitext-Feld + Button): schreibt SOFORT eine
  Zeile mit optionaler Notiz, unabhängig vom nächsten reguären Tick. Gedacht
  für gezielte Testmanöver ("jetzt bewusste Wende", "Smart-Modus gerade
  ausgeschaltet") — liefert Claude beim Auswerten Ankerpunkte im sonst
  reinen Zahlenstrom. Wird als normale Zeile mit gefülltem `event`-Feld
  geschrieben (kein zweites Dateiformat).
- **"Log teilen"**: Standard-Android-Share-Intent (`ACTION_SEND`,
  `text/csv`) über `FileProvider` — Datei geht z.B. per Mail/WhatsApp/Drive
  direkt vom Boot aufs eigene Konto, kein Kabel/Computer am Wasser nötig.
  Braucht `AndroidManifest.xml`-`<provider>`-Eintrag + `res/xml/file_paths.xml`
  (neu, 10.08.2026).

## Bewusste Vereinfachungen

- Kein Rotations-/Grössen-Limit — für einen einzelnen Abend-Törn (wenige
  Stunden, ~1 Zeile/Sekunde) bleibt die Datei im niedrigen MB-Bereich, nicht
  weiter optimiert.
- Kein Persistieren des Ein/Aus-Schalters über App-Neustarts hinweg (wie
  `calibrationModeEnabled`/`smartModeEnabled` — bewusst gleiches Muster wie
  dort: nach Neustart Standardverhalten, hier AN statt AUS).
- Keine Verschlüsselung/Zugriffsschutz auf die Log-Datei — enthält GPS-
  Tracks, das ist bei einem Trainings-/Testtörn ohne sensible Daten
  unkritisch, aber falls die App mal für andere Zwecke genutzt wird, wäre
  das ein Punkt zum Nachdenken.

**Noch nicht kompiliert/getestet** — Test heute Abend geplant.
