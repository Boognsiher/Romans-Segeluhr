# Segeluhr Watch — Galaxy-Watch-App (Kotlin / Jetpack Compose for Wear OS)

Begleit-App für Samsung Galaxy Watch 5 Pro (LTE) — funktional analog zur
T-Watch Ultra (`Segeluhr-Firmware/ECHT/Segeluhr_TWatch_Ultra/`), aber als
native Wear-OS-App statt Arduino/ESP32-Firmware, da die Galaxy Watch
selbst Android (Wear OS) ist. Details/Architektur-Entscheidungen siehe
`../Segeluhr-Android/Segeluhr/docs/Erweiterung_GalaxyWatch_App.md`.

## Projekt öffnen & bauen

1. Android Studio → **Open** → diesen Ordner (`Segeluhr-GalaxyWatch/`).
2. Gradle-Sync lädt alle Abhängigkeiten automatisch (Internetverbindung
   nötig) — u.a. Wear-Compose-Material, osmdroid.
3. **Run ▶** auf einer gekoppelten Galaxy Watch (Entwickleroptionen →
   ADB-Debugging über WLAN aktivieren) oder einem Wear-OS-Emulator (API
   30+, "Wear OS Large Round" empfohlen für die runde Watch-5-Pro-Form).

> Hinweis: Der Code wurde in dieser Umgebung ohne Android-SDK geschrieben
> und konnte hier nicht kompiliert/getestet werden — genau wie ursprünglich
> bei der Handy-App (siehe deren README.md). Ein erster echter Gradle-Build
> in Android Studio kann noch kleinere Kompilierfehler zutage fördern.

## Voraussetzung am Handy

Die Segeluhr-Handy-App muss im Modus **"Mit Uhr"** laufen (Setup-Tab) —
das startet den GATT-Server (`BleGattServerManager`), an den sich diese
Uhr als weiterer BLE-Central anschliesst (genau wie eine T-Watch Ultra;
beide können gleichzeitig verbunden sein).

## Architektur

```
app/src/main/java/com/segeluhr/watch/
├── ble/          WatchBleClient (GATT-Central), BleProtocol (Wire-Format,
│                 1:1 zur Handy-Seite), Foreground-Service für Betrieb bei
│                 ausgeschaltetem Display
├── core/         GeoPoint, HapticPlayer (Vibrationsmuster)
├── data/         WatchUiState, BoatState-Ableitung
├── viewmodel/    SegeluhrWatchViewModel — verbindet BLE-Client mit Compose
└── ui/           Compose for Wear OS: 6 Segel-Tabs (Nav/Wind/Heim/CD/Man/
                  Menu) per HorizontalPager, dunkles nautisches Theme
                  (identische Palette zur Handy-App)
```

## Was diese App NICHT ist

Kein Ersatz für die Handy-App — sie zeigt nur an, was die Handy-App
berechnet, und schickt Befehle dorthin zurück (identische Rollenteilung
wie die T-Watch Ultra: Handy = GPS-Sensor + komplette Navigations-Logik,
Uhr = Anzeige + Haptik + Bedienung am Handgelenk).
