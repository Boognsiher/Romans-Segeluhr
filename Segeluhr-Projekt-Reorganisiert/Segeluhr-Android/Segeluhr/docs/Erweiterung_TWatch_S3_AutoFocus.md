# Erweiterung: Automatische Bildschirm-Priorisierung (T-Watch S3)

**Nicht Teil der ursprünglichen Spezifikation.** Die Uhr steckt beim
Segeln in einem wasserdichten Sack am Handgelenk — Touch-Bedienung ist
dort praktisch nicht möglich. Bedienung läuft komplett über das Handy
(Segeluhr-App), die Uhr liefert nur noch Anzeige + Haptik.

## Problem vorher

Die 5 Segel-Tabs (Nav, Wind, Heim, CD, Manöver) mussten manuell per Wisch
angewählt werden. Ohne Touch-Zugriff hätte man z.B. den Countdown
verpasst, wenn gerade der Nav-Tab aktiv war.

## Lösung: `autoFocusTick()`

Wird bei jedem eingehenden BLE-Notify aufgerufen (GPS, Wind, Home-Status,
Race-Status — über `refreshActiveScreen()`), prüft den aktuellen Zustand
und springt automatisch zum wichtigsten Tab:

| Priorität | Bedingung | Tab |
|---|---|---|
| 1 (höchste) | `raceData.maneuverNeeded == true` | Manöver |
| 2 | `raceData.raceState == COUNTDOWN` | Countdown |
| 3 | `homeData.active == true` | Heimweg |
| 4 (Standard) | sonst | Nav |

Wind- und Menü-Tab bleiben nur per manuellem Wisch erreichbar — sinnvoll
z.B. an Land vor dem Versiegeln der Uhr, aber nicht Teil der
Auto-Priorisierung, da sie nicht zeitkritisch sind.

**Menü-Tab bleibt bewusst bestehen**, auch wenn die Bedienung jetzt übers
Handy läuft: er dient als Fallback/zum Testen an Land, wird beim Segeln
aber nicht gebraucht.

`switchToMode(MODE_SEGELN)` (siehe `Erweiterung_TWatch_S3_Alltagsmodus.md`)
setzt beim Wechsel keinen expliziten Starttab — der nächste eingehende
Notify löst `autoFocusTick()` ohnehin aus und wählt den richtigen Tab.
