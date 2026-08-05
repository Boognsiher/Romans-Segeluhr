# Claude Code + GitHub Setup für heute Abend

Ziel: Claude Code läuft lokal auf deinem Windows-Workstation, kann das Repo
`Boognsiher/Romans-Segeluhr` klonen/pullen, Änderungen committen und pushen,
während du beide Uhren per USB angehängt hast.

## 1. Voraussetzungen prüfen

```powershell
git --version
gh --version   # GitHub CLI - falls nicht installiert: winget install GitHub.cli
```

## 2. GitHub-Authentifizierung (einmalig)

```powershell
gh auth login
```
- "GitHub.com" wählen
- "HTTPS" als Protokoll
- Im Browser einloggen und autorisieren

Danach git so konfigurieren, dass es die gh-Anmeldung nutzt:
```powershell
gh auth setup-git
```

## 3. Repo an einem festen Ort klonen

Empfehlung: außerhalb von OneDrive (analog zur bekannten Regel für den
Arduino-libraries-Ordner, um Sync-Konflikte/Locking zu vermeiden), z.B.:

```powershell
cd C:\Dev
git clone https://github.com/Boognsiher/Romans-Segeluhr.git
cd Romans-Segeluhr
git config user.name "Roman"
git config user.email "<deine GitHub-Email>"
```

Wenn du Claude Code in diesem Ordner startest, hat es direkten Zugriff auf
das Repo und kann eigenständig `git add / commit / push` ausführen, wenn du
es darum bittest.

## 4. Vor der Session: offene Altlast nicht vergessen

Laut PROJEKT_STATUS.md steht noch aus: der alte Pfad
`Segeluhr-Firmware/Segeluhr_TWatch_S3/` muss manuell über die GitHub-
Weboberfläche gelöscht werden (git push/Web-Upload löscht keine alten Pfade
automatisch). Falls das noch nicht erledigt ist, heute Abend gleich mit
erledigen, bevor die neue Struktur reinkommt - sonst verwirrt die doppelte
Struktur Claude Code unnötig.

## 5. Die heute vorbereiteten Dateien einsortieren

Aus diesem Vorbereitungspaket in dein lokales Repo kopieren:

```
docs/Erweiterung_Land_Boot_LoRa_Kommunikation.md
Segeluhr-Firmware/shared/LoRaPacket.h
Segeluhr-Firmware/ECHT/TWatch_Ultra_Boot/Segeluhr_TWatch_Ultra_Boot.ino
Segeluhr-Firmware/ECHT/TWatch_S3_Land/Segeluhr_TWatch_S3_Land.ino
docs/Claude_Code_GitHub_Setup.md   (diese Datei)
```

Pfade ggf. an deine tatsächliche Repo-Struktur anpassen - Claude Code sollte
das beim Öffnen des Repos sowieso abgleichen und dir Diskrepanzen melden.

## 6. Einstiegs-Prompt für Claude Code heute Abend (Vorschlag)

> Ich habe die Vorbereitungsdateien für die neue Land/Boot-LoRa-Architektur
> ins Repo kopiert (siehe docs/Erweiterung_Land_Boot_LoRa_Kommunikation.md
> und die beiden Skeleton-.ino-Dateien). T-Watch Ultra ist per USB
> angeschlossen (COM-Port: ___), T-Watch S3 ebenfalls (COM-Port: ___).
> Bitte zuerst Segeluhr_TWatch_S3.ino analysieren und einen Portierungsplan
> für TWatch_Ultra_Boot vorschlagen, bevor du Code schreibst.

## 7. Nach jedem getesteten Fortschritt

Wie gewohnt: nach jedem erfolgreichen Bugfix / getesteter neuer Funktion
daran denken, den Stand auf GitHub zu pushen (bzw. Claude Code direkt bitten,
das zu tun), damit GitHub Single Source of Truth bleibt.
