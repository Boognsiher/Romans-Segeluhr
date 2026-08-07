# Erweiterung: Galaxy-Watch-App (Wear OS), ohne LoRa

**Nicht Teil der ursprünglichen Spezifikation.** Ziel: eine dritte
Boots-Uhr-Option neben T-Watch S3/Ultra — eine Samsung Galaxy Watch
(getestet: Watch5 Pro LTE, Wear OS 3+) als "Mit Uhr"-Client der Handy-App.
**Bewusst ohne LoRa** — LoRa wird in diesem Projekt ausschliesslich für die
Boot→Land-Fernverbindung gebraucht (`Segeluhr_TWatch_Ultra` →
`Segeluhr_WatchS_LoRaEmpfaenger`, siehe `Erweiterung_Heimweg.md` /
`BLE_Protokoll_Ergaenzung_Heimweg_LoRa.md`). Die reine Boots-Armbanduhr-Rolle
(bisher `Segeluhr_TWatch_S3`) nutzt gar kein LoRa, nur BLE zum Handy — genau
diese Rolle übernimmt die Galaxy Watch, per Standard-Android-BLE-APIs statt
NimBLE-Arduino-Firmware.

## Warum das funktioniert, ohne die Handy-Seite anzufassen

`BleGattServerManager` verwaltet verbundene Geräte bereits als **Set**
(`connectedDevices: MutableSet<BluetoothDevice>`) und benachrichtigt beim
Senden jedes verbundene Gerät in einer Schleife — der GATT-Server war also
schon vor dieser Erweiterung mehrklient-fähig. Die Galaxy Watch verbindet
sich als zusätzlicher/alternativer BLE-Central mit demselben Custom-Service
(`6f6e0001-...`) und denselben Characteristics wie die T-Watch S3. Am
`BleProtocol.kt`/`BleGattServerManager.kt` auf Handy-Seite wurde für diesen
ersten Schritt **nichts geändert**.

## Neues Modul `wear/`

Eigenes Gradle-Modul `wear/` (`applicationId com.segeluhr.wear`), separate
APK, minSdk 30 (Wear OS 3+). Wear-OS-Apps laufen auf echtem Android, daher
Wiederverwendung des Tech-Stacks: Kotlin, Compose (hier: Compose **for Wear
OS**, `androidx.wear.compose.material`, NICHT `androidx.compose.material3` —
eigene, für runde Displays und kleine Screens optimierte Komponenten),
Standard-Android-BLE-APIs (`BluetoothLeScanner`/`BluetoothGatt`) statt
NimBLE-Arduino.

```
wear/src/main/java/com/segeluhr/wear/
  MainActivity.kt          Permission-Handling + Compose-Einstieg
  ble/WearBleProtocol.kt   Decoder-Gegenstück zu app/.../ble/BleProtocol.kt
  ble/WearBleClient.kt     BLE-Central: Scan -> Connect -> Discover -> Subscribe
  ui/ConnectionScreen.kt   Erster Test-Screen (Status + Rohwerte)
  ui/theme/Theme.kt        Wear-Compose-Theme, gleiche nautische Palette
```

## Umfang dieses ersten Schritts

Reiner **BLE-Verbindungstest**, noch keine echten Segeluhr-Screens. Prüft
die zentrale offene Frage vor dem vollen Ausbau: kann die Uhr sich per
eigener BLE-GATT-Verbindung mit dem Handy verbinden, **parallel** zur
normalen Wear-OS-Companion-Kopplung (klassisches Bluetooth für
Benachrichtigungen etc.)? Beide Verbindungstypen sind technisch
unabhängig, aber auf echter Hardware ungetestet.

Implementiert: Scan nach `SERVICE_UUID` → Connect → Service-Discovery →
serielles Abonnieren von CHAR_GPS, CHAR_BATTERY, CHAR_WIND (Notify) →
Anzeige von SOG/COG/Wind/Handy-Akku auf einem einzigen Compose-Screen.

**Bewusst noch nicht implementiert** (folgt nach dem Verbindungstest):
- Reconnect-Logik bei Verbindungsabbruch (T-Watch-Firmware macht das
  bereits; hier bricht die Verbindung aktuell endgültig ab)
- CHAR_CONTROL (Schreiben von `CMD_*` — Countdown starten, Wind
  kalibrieren, Trainingsmodus wählen etc.)
- CHAR_HAPTIC (Notify → `Vibrator`/`VibrationEffect`-Trigger auf der Watch)
- CHAR_HOME_STATUS, CHAR_RACE_STATUS (Live-Screens für Heimweg/Countdown/
  Manöver, analog zu `Erweiterung_BLE_Wind_RaceStatus.md`)
- CHAR_TIME_SYNC (einmaliger Read direkt nach Connect)
- Foreground Service für Dauerverbindung bei Display aus
- Eigene Runtime-Permission-UX (aktuell nur ein simpler
  "Erneut"-Button bei Fehlern/Verweigerung)

## Bekannte offene Punkte / Risiken

- **⚠️ NICHT kompiliert** — kein `gradlew`/lokales Gradle in diesem Repo
  vorhanden, nur manuell durchgesehen (wie beim Rest des Android-Projekts).
  Vor Nutzung unbedingt in Android Studio bauen + auf der Watch testen.
- **Touch bei Nässe/Salzwasser ungeklärt**: Grund, warum die T-Watch S3
  bewusst *ohne* Touch-Interaktion läuft (Auto-Focus, steckt im
  wasserdichten Sack) — kapazitive Touchscreens sind mit nassen/salzigen
  Fingern unzuverlässig. Falls die Galaxy Watch direkt am Handgelenk (ohne
  Sack) getragen wird, ist Bedienbarkeit beim Segeln ein reales Risiko.
  Mögliche Abhilfe: physische Tasten der Watch5 Pro statt Touch für die
  wichtigsten Aktionen nutzen.
- **Parallele Bluetooth-Verbindung ungetestet**: Watch ist bereits klassisch
  mit dem Handy gekoppelt (Benachrichtigungen etc.); ob die zusätzliche
  eigene BLE-GATT-Verbindung zum selben Handy zuverlässig funktioniert, ist
  die zentrale Sache, die dieser Prototyp klären soll.
- **Installation**: kein USB an der Watch5 Pro — Sideload per
  ADB-over-WiFi (Entwickleroptionen auf der Uhr aktivieren) oder
  Play-Store-Interntest-Track.
- **Akkulaufzeit** bei Dauer-BLE + Display an über eine ganze
  Trainings-/Regattasession noch nicht getestet.
- Protokoll-Konstanten/Byte-Layouts sind aktuell **dupliziert** zwischen
  `app/.../ble/BleProtocol.kt` (Encoder) und `wear/.../ble/WearBleProtocol.kt`
  (Decoder) — bei Protokolländerungen an beiden Stellen nachziehen. Bei
  weiterem Ausbau ggf. in ein gemeinsames Kotlin-Modul auslagern.
