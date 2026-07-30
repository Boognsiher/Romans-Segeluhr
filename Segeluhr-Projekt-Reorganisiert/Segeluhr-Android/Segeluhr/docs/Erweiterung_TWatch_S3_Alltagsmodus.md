# Erweiterung: Alltags-/Segelmodus-Umschaltung (T-Watch S3)

**Nicht Teil der ursprünglichen Spezifikation.** Ergänzt, damit dieselbe
Firmware im Alltag als normale Uhr nutzbar ist, aber automatisch in den
Segelmodus wechselt, sobald die Segeluhr-App auf dem Handy verbindet.

## Zustandsmaschine

```
enum AppMode { MODE_ALLTAG, MODE_SEGELN };
```

Zwei komplett getrennte LVGL-Screens (`screenAlltag`, `screenSegeln`),
Wechsel über `lv_screen_load()`. Die Statusleiste (BLE-Status,
Handy-Akku) liegt auf `lv_layer_top()` und bleibt in beiden Screens
sichtbar.

## Automatischer Wechsel

- **BLE-Connect** (`ClientCallbacks::onConnect`) -> sofort `MODE_SEGELN`.
- **BLE-Disconnect** -> **kein** sofortiger Rückfall. Es wird nur der
  Trennungszeitpunkt gemerkt (`disconnectAtMs`). `appModeTick()` (1x pro
  Loop-Durchlauf, günstig) prüft, ob seit der Trennung mehr als
  `SEGELN_FALLBACK_GRACE_MS` (**30 Sekunden**) vergangen sind — erst dann
  fällt die Uhr zurück auf `MODE_ALLTAG`. Kurze BLE-Dropouts mitten im
  Rennen (Handy kurz ausser Reichweite/Tasche) werden so toleriert, ohne
  dass die Segel-Screens verschwinden und neu aufgebaut werden müssen.

## Manueller Schalter ("Segelmodus erzwingen")

Im Alltags-Screen, Tab "Setup": ein Switch, der `forceSegelnMode` setzt.
Ist er aktiv, bleibt die Uhr dauerhaft in `MODE_SEGELN`, unabhängig vom
Verbindungsstatus — nützlich zum Testen der Segel-UI an Land.

**Wichtig für diese Hardware (T-Watch S3, kein eigenes GPS):** Ohne
BLE-Verbindung zeigen die Segel-Screens dann einfach die vorhandenen
Platzhalter ("warte auf GPS...", "warte auf Wind...") — der Schalter hat
auf der S3 bewusst **keine** Standalone-Segelfunktion, weil dafür ein
eigenes GPS-Modul und eine eigene Navigations-Logik nötig wären (die die
T-Watch Ultra später hat, siehe `Segeluhr_Basis.ino`). Der Schalter ist
hier als UI-Grundgerüst vorbereitet, das auf der Ultra dann echten Sinn
ergibt (dort würde er den Solo-GPS-Modus aktivieren).

## Alltags-Screen (Boot-Zustand)

4 Tabs: **Uhr** (Live-Uhrzeit über `instance.rtc.getDateTime()`),
**Timer** (einfache Stoppuhr, Start/Stop/Reset), **Akku** (eigener
Uhr-Akkustand über `instance.pmu.getBatteryPercent()` — zu unterscheiden
vom Handy-Akkustand in der Statusleiste!), **Setup** (der oben
beschriebene "Segelmodus erzwingen"-Schalter).
