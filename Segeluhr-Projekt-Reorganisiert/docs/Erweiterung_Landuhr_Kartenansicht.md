# Erweiterung: Landuhr-Kartenansicht (Boot-Position für Land-Handy)

> Status: 09.08.2026 implementiert **und auf echter Hardware Ende-zu-Ende
> verifiziert** (S3 → BLE → Land-Handy → Karte). Dabei zwei Bugs gefunden
> und gefixt, siehe "Testprotokoll 09.08.2026" unten — beide waren
> **vorbestehende/durch Umgebungswechsel verursachte Probleme, nicht in der
> neuen Kartenansicht selbst**.

## Warum

Die Segel-App (Handy des Seglers) und die Boots-/Land-Uhren zeigen die
Boot-Position bisher nur auf dem kleinen Uhr-Display an. Roman wollte
zusätzlich eine Kartenansicht für die Person an Land: ein zweites Handy,
das per Bluetooth mit der Land-Uhr (T-Watch S3) verbunden ist und die vom
Boot per LoRa empfangene Position auf einer echten Karte zeigt.

## Architektur-Entscheidungen (mit Roman abgestimmt)

- **Kartenmaterial**: OpenStreetMap über `osmdroid` statt Google Maps — kein
  API-Key/Cloud-Konto nötig, Kacheln werden automatisch von osmdroid auf
  Disk gecacht (spätere Offline-Nutzung eines bereits besuchten Gebiets
  funktioniert dadurch von selbst). Volles Vorab-Herunterladen eines
  Gebiets für komplett neue Regionen ist **noch nicht gebaut**.
- **Ziel-App**: in die bestehende Segeluhr-App integriert (nicht als
  eigenständige App), über einen neuen Rollen-Umschalter im Setup-Tab
  ("Auf dem Boot" / "An Land", `AppRole` in `data/model/Models.kt`). Im
  Land-Modus wird statt der sechs Segel-Tabs nur ein Karten-Screen gezeigt
  — beide Rollen haben technisch nichts miteinander zu tun (Handy ist beim
  Segeln BLE-**Server** zur Boots-Uhr, an Land BLE-**Client** zur Land-Uhr).
- **Datenweg**: Ultra (GPS vom Handy per BLE) → LoRa → S3 (Land-Uhr) → BLE
  (neue Characteristic am bestehenden Fragen-Editor-Server) → Land-Handy.
  Kein direkter Weg Boot-Handy → Land-Handy (kein Internet/Mobilfunk beim
  Segeln vorausgesetzt).

## Protokoll-Änderungen

### 1. LoRa-Paket (Ultra → S3), `shared/LoRaPacket.h`

Neues Feld `uint8_t gpsValidFix` (Paketgröße 26 → **27 Bytes**). Ohne dieses
Flag ließe sich "noch kein GPS-Fix vom Handy" (Felder bleiben bei 0,0) nicht
von einem echten Fix bei 0°N/0°E unterscheiden. **Beide Firmwares müssen
zusammen neu geflasht werden**, sonst laufen sie auseinander (bestehende
Projekt-Regel).

Ultra setzt es in der Paket-Baufunktion aus `gpsData.validFix` (bereits
vorhanden, kam vorher nur nicht im LoRa-Paket mit an).

### 2. Neue BLE-Characteristic (S3 → Land-Handy), `Segeluhr_TWatch_S3.ino`

Läuft am selben GATT-Server wie der bestehende BLE-Fragen-Editor (siehe
`docs/Erweiterung_S3_BLE_Fragen_Editor.md`), gleicher An/Aus-Schalter im
Menü — **kein separater Schalter**. Wer die Fragen-Editor-BLE einschaltet,
teilt damit automatisch auch die Boot-Position.

- Service: `7a6e0001-b5a3-f393-e0a9-e50e24dcca9e` (bestehend)
- Neue Characteristic `POSITION_CHAR_UUID = 7a6e0003-b5a3-f393-e0a9-e50e24dcca9e`,
  Property NOTIFY + READ, 10 Byte little-endian:
  `int32 latE7, int32 lonE7, uint8 gpsValidFix, uint8 sequence`
- Wird bei jedem gültig empfangenen LoRa-Statuspaket aktualisiert + notified
  (`loraReceiveTick()`), zusätzlich beim BLE-Einschalten direkt mit dem
  letzten bekannten Stand vorbelegt (falls schon vor dem Einschalten ein
  Paket da war) und beim Verbinden per READ sofort abrufbar — die App muss
  nicht bis zu 30s auf die erste Notify warten.

## Android-App-Änderungen

- `data/model/Models.kt`: `enum class AppRole { SAILOR, SHORE }`
- `data/settings/SettingsRepository.kt`: `appRoleFlow`/`setAppRole()`, per
  DataStore persistiert (Default `SAILOR`, damit niemand versehentlich im
  Land-Modus landet)
- `ui/screens/SetupScreen.kt`: neue Karte "Rolle" mit Umschalt-Buttons
- `viewmodel/SegeluhrViewModel.kt`: `appRole` in `SegeluhrUiState`, reine
  Persistenz-Passthrough-Funktion `setAppRole()`
- `ble/LandUhrClient.kt` (**neu**): BLE-Central-Rolle, scannt/verbindet zur
  Land-Uhr, abonniert `POSITION_CHAR_UUID`, exposed `StateFlow<LandPositionState>`
  (Verbindungsstatus, Position, Fix-Gültigkeit, Zeitpunkt des letzten
  Updates als `SystemClock.elapsedRealtime()` — die App rechnet "vor Xs"
  selbst aus, die Uhr kennt nur ihre eigene `millis()`-Laufzeit, keine
  Wanduhrzeit)
- `viewmodel/LandUhrViewModel.kt` (**neu**): dünner Wrapper um
  `LandUhrClient` fürs Compose-`StateFlow`, `connect()`/`disconnect()`
  bewusst nicht in `init{}`, sondern vom Screen aus über dessen Lifecycle
  gesteuert (Scan soll nur laufen, während der Karten-Screen sichtbar ist)
- `ui/screens/LandUhrScreen.kt` (**neu**): osmdroid `MapView` per
  `AndroidView`, Marker an Boot-Position (nur wenn `gpsValidFix`), Status-
  Banner ("Suche...", "Verbunden — kein Fix", "zuletzt vor Xs
  aktualisiert" mit Ampelfarbe ab 90s Alter wie `LORA_SIGNAL_LOST_THRESHOLD_MS`),
  Zurück-Button → `AppRole.SAILOR`
- `ui/MainActivity.kt`: `SegeluhrApp()` verzweigt ganz oben je nach
  `state.appRole` zwischen den bestehenden sechs Tabs und `LandUhrScreen`
- `build.gradle.kts`: `org.osmdroid:osmdroid-android:6.1.20`
- `AndroidManifest.xml`: zusätzlich `BLUETOOTH_SCAN`-Permission (Central-
  Rolle) — `INTERNET`/`ACCESS_FINE_LOCATION`/`BLUETOOTH_CONNECT` waren
  schon vorhanden

## Bewusst nicht Teil dieser Ausbaustufe

- **Kein Vorab-Download eines Kartengebiets** — osmdroid cacht nur, was
  tatsächlich schon angeschaut wurde. Für "erstes Mal an einem neuen See,
  komplett ohne Empfang" reicht das noch nicht.
- ~~**SegeluhrViewModel (Segler-Rolle) läuft im Hintergrund weiter**, auch
  während die App im Land-Modus ist~~ — **10.08.2026 behoben** (als
  Nebeneffekt der App-Stopp-Erweiterung, siehe
  `docs/Erweiterung_App_Stopp_Rollenwahl.md`): `setAppRole()` pausiert
  GPS/Tickschleife/Foreground-Service jetzt automatisch beim Wechsel zu
  `AppRole.SHORE` und nimmt sie beim Zurückwechseln wieder auf. Die
  ViewModel-Instanz selbst lebt weiterhin (unverändert, siehe unten), aber
  ohne mehr aktiv GPS abzufragen oder zu ticken.
- **Keine Bearing/Heading-Anzeige oder Track-Linie** auf der Karte, nur ein
  einzelner Positions-Marker mit dem letzten Stand.

## Lizenz-Hinweis: OpenStreetMap-Attribution

Kartenkacheln kommen über osmdroid vom öffentlichen Mapnik-Tile-Server —
OSM-Daten stehen unter der **ODbL**, die eine sichtbar angezeigte
Attribution verlangt. osmdroid fügt diese **nicht automatisch** hinzu
(`CopyrightOverlay` muss explizit als Overlay hinzugefügt werden). **10.08.
nachgetragen** in `LandUhrScreen.kt` (`overlays.add(CopyrightOverlay(ctx))`)
— vorher fehlte sie komplett, war ein echter Nachholbedarf, kein reiner
Vorsichtsschritt.

## Testprotokoll 09.08.2026

Erster echter Hardware-Test (zweites Handy als Land-Gerät, per adb/Logcat
und S3-Serial-Log live mitverfolgt). Zwei Bugs gefunden, beide gefixt:

### Bug 1: S3 stürzte beim Einschalten des BLE-Fragen-Editors ab

Guru-Meditation-Crash (`LoadStoreError`) tief in ESP-IDF, per
`addr2line` auf den genauen Pfad zurückgeführt:
`cbBleEditorToggle() → NimBLEDevice::init() → esp_bt_controller_enable()
→ esp_phy_load_cal_and_init() → NVS-Lookup der Bluetooth-
Kalibrierungsdaten → Absturz`. Passierte **innerhalb** von
`NimBLEDevice::init()`, also bevor der neue Code aus dieser Erweiterung
überhaupt lief — nicht durch die neue Positions-Characteristic verursacht.

Wahrscheinliche Ursache: die S3 wurde heute mehrfach mit einem neuen
ESP32-Arduino-Core (3.3.11 statt der vorherigen Version) neu geflasht,
ohne den Flash zu löschen (Standard-Vorgehen, siehe
`docs/Hardware_Arduino_Settings_LilyGO.md`) — die alten, unter dem alten
Core geschriebenen BT-Kalibrierungsdaten in NVS passten nicht mehr zum
neuen Core und waren korrupt. Der BLE-Fragen-Editor-Schalter war laut
`docs/Erweiterung_S3_BLE_Fragen_Editor.md` ohnehin noch nie auf Hardware
getestet worden, hat den Bug also einfach zum ersten Mal aufgedeckt.

**Fix**: kompletter Flash-Erase + Neuflash der S3 (erzwingt frische
Kalibrierungsdaten). Danach mehrere Minuten stabil, BLE durchgehend an,
kein Absturz mehr.

### Bug 2: App fand die Land-Uhr nie ("Suche..." blieb für immer)

Per Logcat reproduziert: `SecurityException: Need
android.permission.BLUETOOTH_SCAN permission` beim Scan-Start. Ursache:
`requestAllPermissions()` (fragt alle Bluetooth-Berechtigungen inkl. dem
neuen `BLUETOOTH_SCAN` an) wird nur über einen Button im Setup-Tab
ausgelöst, der nur erscheint, wenn `locationPermissionGranted` noch
`false` ist — bei Installationen mit schon vorher erteiltem Standortzugriff
(wie beim Test-Handy) blieb `BLUETOOTH_SCAN` dadurch für immer
unangefragt, obwohl die Android-Berechtigungsseite die "Geräte in der
Nähe"-Gruppe bereits als "erlaubt" anzeigte (irreführend, da
`BLUETOOTH_CONNECT` schon von früher erteilt war, `BLUETOOTH_SCAN`
speziell aber nicht).

**Fix**: eigenes `bluetoothScanPermissionGranted`-Feld in
`SegeluhrUiState`, unabhängig von `locationPermissionGranted` geprüft
(`MainActivity.hasBluetoothScanPermission()`), eigener Button im
Setup-Tab. Für den sofortigen Test per `adb shell pm grant` live
nachgeholt — bestätigt: App verbindet jetzt zur Land-Uhr.

**Ergebnis: komplette Kette Ende-zu-Ende bestätigt** — Boot-GPS → Ultra →
LoRa → S3 → BLE → Land-Handy, Marker erscheint korrekt auf der Karte.

### Noch offen

- Nur kurz angetestet, nicht über eine längere Session verifiziert
  (Verhalten bei Verbindungsabbruch/Reconnect, Rollen-Umschalter-
  Persistenz über App-Neustart, Verhalten ohne GPS-Fix vom Boot).
- Zwei parallele BLE-Clients an einem NimBLE-Server (Fragen-Editor-Web-
  Bluetooth-Seite + Landuhr-App gleichzeitig verbunden) noch nicht
  getestet, bisher nur mit jeweils einem Client.
