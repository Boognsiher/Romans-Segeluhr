# Segeluhr — Android-App (Kotlin / Jetpack Compose)

Native Android-Umsetzung der `Segeluhr_Spezifikation.md` inkl. der
BLE-GPS-Bridge aus `BLE_Protokoll.md` (Handy → T-Watch Ultra).

## Projekt öffnen & bauen

1. Android Studio (aktuelle Version) öffnen → **Open** → diesen Ordner
   (`Segeluhr/`) auswählen.
2. Android Studio erkennt, dass die Gradle-Wrapper-JAR fehlt, und bietet an,
   sie automatisch zu erzeugen ("Gradle wrapper is missing… Create it?" bzw.
   über **File → Sync Project with Gradle Files**). Alternativ, falls lokal
   ein Gradle installiert ist: `gradle wrapper --gradle-version 8.7` im
   Projektordner ausführen.
3. Beim ersten Sync lädt Gradle alle Abhängigkeiten (Compose, Room,
   DataStore, Play-Services-Location, Material) automatisch aus den
   Standard-Repos (`google()`, `mavenCentral()`) — dafür ist eine
   Internetverbindung nötig.
4. **Run ▶** auf einem Gerät/Emulator mit Android 8.0 (API 26) oder neuer.
   Für GPS/BLE-Tests empfiehlt sich ein echtes Gerät (Emulator hat kein
   brauchbares GPS/BLE).

> Hinweis: Der Code wurde in dieser Umgebung ohne Android-SDK geschrieben
> und konnte hier nicht kompiliert/getestet werden. Die Logik ist 1:1 aus
> dem funktionierenden Browser-Prototyp (`segeluhr.html`) portiert und
> gegen die Spezifikation durchgeprüft, aber ein erster echter Gradle-Build
> in Android Studio kann noch kleinere Kompilierfehler (Tippfehler,
> Versions-Mismatches) zutage fördern — dafür bin ich gerne für eine zweite
> Runde da, sobald du den Build-Output hast.

## Zwei Betriebsmodi (Setup-Tab)

- **"Ohne Uhr"** (Standard): Das Handy berechnet alles selbst und vibriert
  auch selbst über `VibrationPatterns`. Keine BLE-Verbindung nötig — das ist
  der normale Standalone-Betrieb.
- **"Mit Uhr"**: Das Handy berechnet weiterhin alles selbst (Variante A aus
  `BLE_Protokoll.md`, "Handy = GPS-Sensor"), sendet aber zusätzlich GPS-Fixe
  **und** Vibrationsmuster-Kommandos per BLE an die T-Watch. Das Handy
  vibriert in diesem Modus selbst NICHT mehr — die komplette Haptik läuft
  über die neue Haptik-Characteristic auf der Uhr (siehe
  `docs/BLE_Protokoll_Ergaenzung_Haptik.md`).

Technisch löst das ein einziges `SwitchableHaptics`-Objekt: Alle
Engines (WindEngine, TrainingEngine, ...) rufen immer dieselbe
`HapticFeedback`-Schnittstelle auf, ohne zu wissen, ob am Ende das Handy
vibriert oder ein BLE-Kommando an die Uhr geschickt wird. Beim Umschalten
im Setup-Tab wird nur das Ziel umgehängt — kein Neustart der Logik, kein
Verlust von Kalibrierfortschritt o.ä.

## Architektur

```
app/src/main/java/com/segeluhr/app/
├── core/            Formeln, Konstanten, CourseTracker, HapticFeedback-
│                    Interface + VibrationPatterns (Handy-Implementierung)
│                    (Abschnitt 3, 7, 9, 10 der Spezifikation)
├── data/
│   ├── model/       Enums & Datenklassen (RaceState, TrainMode,
│   │                OperationMode, ...)
│   ├── db/          Room: Manöver-Log (Ringpuffer, Abschnitt 6.3)
│   └── settings/    DataStore: Wegpunkte, Windkalibrierung, Betriebsmodus
├── location/        FusedLocationProviderClient als Flow<Fix>
├── ble/             BLE-GATT-Peripheral gemäß BLE_Protokoll.md (+ Haptik-
│                    Erweiterung), BleHapticSender (Uhr-Implementierung
│                    von HapticFeedback), BleBridge (Singleton), Foreground-
│                    Service für Hintergrundbetrieb im Modus "Mit Uhr"
├── logic/           Die eigentlichen Zustandsautomaten:
│                    WindEngine (Abschnitt 4), StartCountdownEngine (5),
│                    TrainingEngine (6.1–6.4), LakeGeofenceEngine (6.5),
│                    SwitchableHaptics (Handy/Uhr-Umschaltung)
├── viewmodel/       SegeluhrViewModel — 1-Hz-Hauptschleife, verbindet
│                    alle Engines (analog zu mainTick() im Prototyp)
└── ui/              Jetpack Compose: 6 Tabs (Normal/Start/Wind/Training/
                     Log/Setup), dunkles nautisches Theme analog zum CSS
                     des Browser-Prototyps
```

## Wichtige Design-Entscheidungen / Abweichungen vom Prototyp

- **Persistenz von Anfang an**: Anders als der Watch-Prototyp (Abschnitt 11:
  "nur im RAM") speichert die App Manöver-Log (Room) und Wegpunkte/
  Windkalibrierung (DataStore) direkt persistent.
- **Windkalibrierung**: Nur die Methode "Amwind" (Abschnitt 4.1, rein
  GPS-basiert) ist umgesetzt — identisch zum Prototyp. Die Methode "Zeigen"
  (benötigt einen relativen Rotationssensor, `TYPE_GAME_ROTATION_VECTOR`)
  ist in der Spezifikation beschrieben, aber weder im Prototyp noch hier
  implementiert. Lässt sich als zusätzlicher Kalibrierpfad in `WindEngine`
  ergänzen, falls gewünscht.
- **BLE-Bridge**: Das Handy übernimmt die in `BLE_Protokoll.md` Abschnitt 1
  festgelegte Peripheral/GATT-Server-Rolle. Advertising läuft dauerhaft
  (siehe Vorschlag in Abschnitt 6 des Protokolls), sobald der Schalter in
  Setup → "BLE-Bridge zur T-Watch" aktiviert ist; das Battery-Reporting
  hält sich an die ≥5%-Änderung / max. 1×/60s-Regel aus Abschnitt 5.
- **GPS-Umrechnung**: `Location.getSpeed()` liefert m/s, wird analog zur
  Spezifikation (durchgängig in Knoten gerechnet) mit Faktor 1.943844
  umgerechnet.

## Berechtigungen

| Permission | Grund |
|---|---|
| `ACCESS_FINE_LOCATION` | GPS-Fixe (COG/SOG), Voraussetzung für praktisch alle Funktionen |
| `BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT` (API 31+) | BLE-GATT-Server für die T-Watch-Bridge |
| `VIBRATE` | Signalmuster (Abschnitt 7) |
| `FOREGROUND_SERVICE*` | BLE-Bridge + GPS-Weiterleitung laufen weiter, wenn App im Hintergrund ist |
| `POST_NOTIFICATIONS` (API 33+) | Pflicht-Notification für den Foreground-Service |

## Offene Punkte (siehe auch Abschnitt 11 der Spezifikation)

- Line-Bias-Vorzeichen einmal mit bekannter Windrichtung auf dem Wasser
  gegenprüfen.
- Score-Formel fürs Manöver ist eine Startheuristik, noch nicht validiert.
- Kalibriermethode "Zeigen" (Rotationssensor) fehlt noch, s.o.
- App-Icon ist ein einfaches Platzhalter-Segelsymbol (Vektor, aus dem
  Favicon des Prototyps abgeleitet) — bei Bedarf durch ein finales Icon
  ersetzen.
- BLE-UUIDs sind Platzhalter aus `BLE_Protokoll.md` — vor dem produktiven
  Einsatz final festlegen (Hinweis steht auch im Protokoll-Dokument selbst).
