# Heute Abend: Schritt-für-Schritt-Ablauf

Ziel des Abends: Claude Code läuft lokal, hat Zugriff auf dein GitHub-Repo,
und du kannst beide Uhren (T-Watch Ultra + T-Watch S3) nacheinander per USB
anschließen und bespielen lassen.

---

## Teil A — Einmaliges Setup (GitHub + Claude Code)

### A1. Voraussetzungen prüfen
```powershell
git --version
gh --version
```
Falls `gh` fehlt:
```powershell
winget install GitHub.cli
```
Falls Claude Code noch nicht installiert ist: über claude.com/claude-code
installieren (Node.js wird vorausgesetzt — falls unklar, `node --version`
prüfen).

### A2. GitHub-Login einrichten
```powershell
gh auth login
```
- "GitHub.com" → "HTTPS" → im Browser einloggen/autorisieren

```powershell
gh auth setup-git
```
Damit nutzt `git` automatisch die gh-Anmeldung für push/pull.

### A3. Repo klonen (außerhalb OneDrive!)
```powershell
cd C:\Dev
git clone https://github.com/Boognsiher/Romans-Segeluhr.git
cd Romans-Segeluhr
git config user.name "Roman"
git config user.email "<deine GitHub-Email>"
```

### A4. Altlast aufräumen
Im Browser auf GitHub: alten Pfad `Segeluhr-Firmware/Segeluhr_TWatch_S3/`
manuell löschen, falls noch nicht geschehen (Web-Upload löscht alte Pfade
nie automatisch). Danach lokal:
```powershell
git pull
```

### A5. Vorbereitungsdateien einsortieren
Aus dem heute erhaltenen ZIP (`Segeluhr_LoRa_Vorbereitung.zip`) entpacken
und folgende Dateien/Ordner in dein lokales Repo kopieren (Pfade ggf. an
deine echte Struktur anpassen):
```
docs/Erweiterung_Land_Boot_LoRa_Kommunikation.md
docs/Claude_Code_GitHub_Setup.md
Segeluhr-Firmware/shared/LoRaPacket.h
Segeluhr-Firmware/ECHT/TWatch_Ultra_Boot/Segeluhr_TWatch_Ultra_Boot.ino
Segeluhr-Firmware/ECHT/TWatch_S3_Land/Segeluhr_TWatch_S3_Land.ino
```
Dann committen (manuell oder gleich Claude Code machen lassen, siehe B1):
```powershell
git add .
git commit -m "Vorbereitung: Land/Boot-LoRa-Architektur (Skeleton + Doku)"
git push
```

### A6. Git-Permissions für Claude Code (optional, deine Wahl)
Standard = Claude Code fragt vor jedem `git push` nach. Falls du das für
heute Abend automatisieren willst, in `Romans-Segeluhr/.claude/settings.json`:
```json
{
  "permissions": {
    "allow": ["Bash(git add *)", "Bash(git commit *)", "Bash(git push *)"],
    "deny": ["Bash(git push --force *)", "Bash(git reset --hard *)"]
  }
}
```
Empfehlung: heute Abend erstmal OHNE diese Datei starten, jeden Push manuell
bestätigen — bei neuer Hardware/Firmware ist die zusätzliche Kontrolle Gold
wert.

---

## Teil B — Claude Code starten

### B1. Im Repo-Ordner starten
```powershell
cd C:\Dev\Romans-Segeluhr
claude
```

### B2. Einstiegs-Prompt
Als erste Nachricht an Claude Code (COM-Ports vorher in Windows
Geräte-Manager nachschauen):

> Ich habe die Vorbereitungsdateien für die neue Land/Boot-LoRa-Architektur
> ins Repo kopiert (siehe docs/Erweiterung_Land_Boot_LoRa_Kommunikation.md
> und die zwei Skeleton-.ino-Dateien unter Segeluhr-Firmware/ECHT/).
> T-Watch Ultra ist an COM-Port ___ angeschlossen (Boots-Uhr), T-Watch S3 an
> COM-Port ___ (Land-Uhr). Bitte zuerst Segeluhr_TWatch_S3.ino analysieren
> und mir einen Portierungsplan für TWatch_Ultra_Boot vorschlagen, bevor du
> Code schreibst.

---

## Teil C — Hardware anschließen & Reihenfolge

### C0. Port-Zuordnung (WICHTIG, immer zuerst!)

Claude Code kann nicht von selbst erkennen, welcher COM-Port zu welcher
physischen Uhr gehört — beide sind ESP32-S3, ein reiner Chip-Query sagt nur
"ESP32-S3", nicht "das ist die Ultra am Handgelenk". Deshalb einmal manuell
zuordnen, bevor irgendetwas geflasht wird:

1. **Nur die T-Watch Ultra** per USB anschließen (S3 noch nicht anstecken)
2. Claude Code bitten, die verfügbaren Ports zu listen, z.B.:
   ```powershell
   arduino-cli board list
   ```
   (oder Windows Geräte-Manager → Anschlüsse (COM & LPT))
3. Notieren: **Ultra = COM___**
4. Ultra wieder abziehen
5. **Nur die T-Watch S3** anschließen, Schritt 2 wiederholen
6. Notieren: **S3 = COM___**
7. Erst jetzt dürfen beide gleichzeitig angeschlossen bleiben — die Zuordnung
   für den Rest der Session ist fix, außer du steckst zwischendurch um

Diese Zuordnung auch gleich in den Einstiegs-Prompt aus Teil B2 eintragen,
nicht nachträglich raten lassen.

### C1. T-Watch Ultra zuerst
1. Per USB anschließen, COM-Port im Geräte-Manager prüfen
2. Prüfen, ob der automatische USB-CDC-Reset funktioniert, oder ob wie bei
   der S3 manuell **BOOT + Crown** gedrückt werden muss beim Flashen
   (Claude Code sollte das beim ersten Upload-Versuch merken/melden)
3. Erste Iteration: nur Grundgerüst + Display-Init zum Laufen bringen,
   bevor die komplette Screen-Logik portiert wird

### C2. T-Watch S3 danach
1. Per USB anschließen (anderer COM-Port), BOOT+Crown-Sequenz wie gewohnt
2. Neue Land-Firmware flashen (ersetzt die alte BLE-Boots-Rolle komplett
   auf diesem Gerät — die alte `segeluhr_ble_tester_v2.ino` bleibt als
   separates Tool unangetastet, falls du die noch brauchst)
3. Prüfen: Hauptscreen zeigt zumindest Platzhalter-Status + laufende Uhrzeit

### C3. Erster Empfangstest
1. Beide Geräte mit Strom versorgt, nicht zwingend beide am PC gleichzeitig
   (LoRa-Test braucht ohnehin etwas physische Distanz oder zumindest
   getrennte Antennen)
2. Ultra sendet alle 30s ein Test-Paket → S3 sollte spätestens nach 30-60s
   den Status-Text wechseln
3. Falls nach 90s nichts ankommt: RadioLib-Parameter (Frequenz/Bandbreite/SF)
   auf beiden Seiten vergleichen — müssen exakt übereinstimmen

---

## Teil D — Nach jedem Fortschritt

Nach jedem erfolgreich getesteten Schritt (auch Zwischenschritten wie
"Display zeigt Platzhalter" oder "erstes Paket kommt an"):
```powershell
git add .
git commit -m "<kurze Beschreibung was funktioniert>"
git push
```
So bleibt GitHub durchgehend der aktuelle Stand, auch falls der Abend nicht
bis zum fertigen Feature kommt.
