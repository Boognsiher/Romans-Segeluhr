# Erweiterung: Mastuhr (eigenständiges Mast-Gerät)

> Diese Erweiterung steht NICHT in der ursprünglichen Spezifikation und wird
> hier gemäss Doku-Konvention separat dokumentiert.

## Status: KONZEPT (13.08.2026, Roman-Wunsch — noch keine Zeile Code, kein Bauteil bestellt)

## 1. Motivation

Bisher basiert die "Uhr am Handgelenk"-Rolle auf gekauften LilyGO-T-Watches
(S3/Ultra). Roman möchte zusätzlich ein **eigenkonstruiertes Gerät**, das
fest am Mast oder sonst am Boot montiert wird — unabhängig von der
gekauften Uhr, mit eigenem ESP32, GPS, Display und Akku. Gehäuse wird
selbst per 3D-Druck gefertigt.

Das Gerät soll die bisherige LoRa-Sender-Rolle der T-Watch Ultra
("Boots-Uhr", siehe `Erweiterung_Land_Boot_LoRa_Kommunikation.md`)
übernehmen können, aber **komplett eigenständig bedienbar sein** — die
Ultra soll optional weiterhin als bequemes tragbares Bedienelement nutzbar
bleiben, ist aber keine Voraussetzung mehr.

## 2. Architektur-Entscheidungen (13.08.2026, mit Roman geklärt)

- **Integration:** Mastuhr bekommt ein **eigenes GPS-Modul** und einen
  **eigenen LoRa-Sender** (SX1262, gleiches Protokoll/Frequenz wie
  bestehend, siehe `Segeluhr-Firmware/shared/LoRaPacket.h`) — sendet
  Statuspakete an die Land-Uhr (S3), genau wie es aktuell für die Ultra
  vorgesehen ist. **Zusätzlich** soll die Ultra weiterhin optional als
  Bedien-/Anzeige-Hilfe am Handgelenk nutzbar sein. Das genaue Kopplungs-
  modell (Mastuhr als eigener BLE-GATT-Server? Lose Kopplung nur über
  gemeinsame LoRa-Pakete? Weiterhin über das Handy?) ist **noch offen**,
  siehe Abschnitt 5.
- **Display:** Transflektives LCD (Sharp Memory Display) — beste
  Ablesbarkeit bei direkter Sonne am Mast, sehr stromsparend. Monochrom,
  daher UI-Design flächig/kontrastreich statt farbcodiert (anders als die
  T-Watch-UIs).
- **Stromversorgung:** Akku + kleines Solarpanel, für wartungsarmen
  Dauerbetrieb über die Saison ohne Ausbau zum Laden.
- **Bedienung:** Da kein Touch-Display (Sharp Memory LCD hat keinen
  Touch), braucht die Mastuhr **eigene physische Taster** (wasserdicht,
  IP67) für Stand-alone-Betrieb ohne Ultra/Handy.

## 3. Geplanter Funktionsumfang (Stand jetzt, grob)

- Eigene GPS-Position/-Geschwindigkeit/-Kurs (Solo-GPS, kein Handy nötig)
- Uhrzeit (aus GPS synchronisiert, PCF8563-Backup-RTC für Zeit ohne Fix)
- LoRa-Statusversand an die Land-Uhr (S3), gleiches Protokoll wie Ultra
- Lokale Basis-Anzeige auf dem Sharp-Display: Uhrzeit, Position/Kurs/SOG,
  Countdown — welche der bestehenden Segel-Screens (Nav/Wind/Heimweg/CD/
  Manöver) tatsächlich Sinn ergeben auf einem fix montierten Gerät (statt
  am Handgelenk) ist noch nicht durchdacht, siehe Abschnitt 5.
- Bedienung über 3-4 wasserdichte Taster (kein Touch)

**Bewusst nicht Teil dieser Konzept-Phase:** Pin-Belegung, konkreter
Code, BLE-Kopplungsprotokoll zur Ultra, Gehäuse-Konstruktion (STL).

## 4. Projektstruktur (neu angelegt)

```
Segeluhr-Firmware/
  ECHT/
    Segeluhr_Mastuhr/            📋 NEU, geplant — eigenständiges Mast-Gerät
      Segeluhr_Mastuhr.ino       (noch nicht angelegt — erst nach Hardware-Entscheid)
      README.md
      hardware/
        Stueckliste.md           Bauteile + Kostenschätzung
        Verkabelung.md           Pin-Zuordnung (Entwurf, noch offen)
        Gehaeuse/
          README.md              Anforderungen an den 3D-Druck-Entwurf
docs/
  Erweiterung_Mastuhr.md         diese Datei
```

## 2a. Radikale Vereinfachung (13.08.2026 Abend, Roman-Wunsch)

Nach der Alternativen-Recherche (Abschnitt 4b) kam der Wunsch, das
Projekt drastisch zu vereinfachen: **kein eigenes GPS, kein LoRa mehr.**
Die Mastuhr wird damit zu einer reinen **BLE-Anzeige fürs Handy** —
architektonisch identisch zur heutigen Rolle der T-Watch S3 (BLE
Central, kein eigenes GPS, alle Nav-Daten kommen per BLE vom Handy, siehe
`CLAUDE.md` "T-Watch S3 hat KEIN eigenes GPS"). Sie wird damit ein
**dritter BLE-Anzeige-Client** neben Handy und T-Watch S3, nur fest am
Mast statt am Handgelenk.

Konsequenzen:
- **Entfällt komplett**: GPS-Modul, LoRa-Modul+Antenne, RTC-Backup-Chip
  (Zeit kommt wie bei der S3 aus der BLE-Verbindung).
- **Fertiges Board statt Einzelteile**: statt ESP32+Display+Laderegler
  selbst zu kombinieren, ein fertiges ESP32-S3-Board mit integriertem
  Display + Akku-Lade-IC (empfohlen: LilyGO T-Display-S3, $9).
- **Display-Prioritäten-Entscheidung**: Roman hat sich bewusst für
  **Einfachheit/Preis vor Sonnenlicht-Ablesbarkeit** entschieden — das
  bedeutet ein normales IPS-TFT statt des ursprünglich geplanten
  transflektiven Sharp Memory LCD. Bei direkter Sonne am Mast wird das
  Display dadurch schlechter ablesbar sein als ursprünglich vorgesehen —
  bewusst in Kauf genommen.
- **Kostenschätzung sinkt** von ~CHF 240–260 auf **~CHF 90–140**
  (siehe `hardware/Stueckliste.md`).
- **Firmware-Wiederverwendung**: BLE-Central-Code + GATT-Protokoll lassen
  sich grösstenteils von `Segeluhr_TWatch_S3.ino` übernehmen, nur die
  Display-Ansteuerung muss auf die Bibliothek des neuen Boards umgestellt
  werden (z.B. TFT_eSPI statt LVGL/LilyGoLib).
- **Hersteller-Frage offen**: das empfohlene T-Display-S3 stammt vom
  selben Hersteller (LilyGO) wie die gekauften T-Watches — als
  generisches Entwicklerboard, nicht als das gekaufte Uhren-Produkt
  selbst. Ob das dem "nicht auf der gekauften Uhr basieren"-Ziel
  widerspricht, ist Romans Entscheidung — Alternativen ohne LilyGO-Bezug
  (M5Stack, Waveshare) stehen in der Stückliste.

Die Original-Architektur (Abschnitt 2, mit GPS+LoRa+Sharp-Display) bleibt
unten dokumentiert als Referenz, falls die Mastuhr später doch
eigenständig (ohne Handy) funken soll.

## 2b. Board-Entscheidung: Waveshare ESP32-S3-Touch-LCD-3.49 (13.08.2026 Abend)

Roman hat einen konkreten AliExpress-Fund eingebracht: das
[Waveshare ESP32-S3-Touch-LCD-3.49](https://www.waveshare.com/esp32-s3-touch-lcd-3.49.htm)
($16–23, je nach Case/Akku-Variante). Als **neue Standard-Empfehlung**
übernommen, anstelle der zuvor evaluierten LilyGO T-Display-S3.

**Warum umgestiegen:**
- Bringt eigenes RTC (PCF85063), 18650-Akkuhalter, Audio-Codec +
  Lautsprecher-Header und 6-Achsen-IMU **bereits eingebaut** mit — spart
  gegenüber der T-Display-S3-Variante ein separates RTC-Breakout und löst
  den zuvor identifizierten JST-Stecker-Mismatch (18650-Standardzelle
  statt proprietärem LiPo-Stecker).
- Löst nebenbei die seit 10.08. offene Frage aus
  `Erweiterung_S3_Ton_QuickMessages.md` (Lautsprecher-Hardware/API der
  bestehenden S3 nicht verifiziert) — dieses Board hat Audio-I/O bereits
  fertig verdrahtet, falls die Ton-Funktion hier nachgezogen werden soll.
- **Kein LilyGO-Bezug** — beantwortet die zuvor offene Frage, ob die
  T-Display-S3-Empfehlung dem "nicht auf der gekauften Uhr basieren"-Ziel
  zu nahekommt.
- Günstiger in Summe (~CHF 85–135 statt ~CHF 90–140) bei mehr eingebauter
  Funktionalität.

**Trade-off, bewusst in Kauf genommen:** Display ist ein schmaler, hoher
Streifen (172×640) statt eines normalen Breitformats — die bestehenden
LVGL-Tab-Layouts der T-Watches lassen sich nicht 1:1 übernehmen, sondern
brauchen einen UI-Neuentwurf (z.B. vertikaler Stapel: Uhrzeit gross oben,
SOG/Kurs darunter). Für "auf einen Blick vom Mast/Cockpit ablesen"
möglicherweise sogar passender als ein quadratisches Display, aber noch
nicht durchdacht.

Details/Stückliste: `hardware/Stueckliste.md`.

## 2c. UI-Neuausrichtung: "erweiterte Anzeige fürs verstaute Handy" (13.08.2026 Abend)

Roman-Korrektur: die Mastuhr soll **keine** Kopie der bestehenden
Tab-Oberfläche (Nav/Wind/Heimweg/CD/Manöver) werden. Das Handy steckt
beim Segeln ohnehin verstaut/geschützt weg — die Mastuhr soll nur die
1-2 Dinge zeigen, die man dabei nicht ständig aufs Handy schauen will:
in erster Linie **Countdown** und ein **Bug/VMG-Qualitäts-Indikator**
("bin ich gerade auf dem schlechten Bug"). Passt auch inhaltlich besser
zum schmalen 172×640-Streifenformat als ein Tab-System.

**Reuse-Check**: Der Bug/VMG-Indikator ist praktisch schon vorhanden —
`CompetitionGuidance.maneuverNeeded` (Rot/Grün-Manöver-Empfehlung, siehe
`Erweiterung_TWatch_Ultra_NavRedesign.md`, 12.08.2026) deckt sowohl
Amwind- als auch Vorwind-Kurse ab (`WindEngine.downwindAngleDeg`). Die
Mastuhr müsste dafür nur `CHAR_RACE_STATUS_UUID` mitlesen — keine neue
App-Logik nötig.

**Vorgeschlagenes Phasen-Layout** (Entwurf, noch nicht final):

| Phase | Erkennung | Oben (gross) | Unten (Ampel/klein) | Datenquelle |
|---|---|---|---|---|
| Vor dem Start | Countdown läuft | Countdown mm:ss | Startlinie-Bias | ✅ jetzt vorhanden, siehe Abschnitt 2d |
| Rennen läuft | Training RACE / Competition aktiv, kein Heimweg | Uhrzeit (klein) | Bug/VMG-Ampel Rot/Grün, Amwind+Vorwind | ✅ vorhanden (`RaceStatusPacket`) |
| Heimweg aktiv | HomeEngine aktiv | Distanz/ETA | VMC-Wert | ✅ vorhanden (Home-Status-Characteristic) |
| Idle | nichts aktiv | Uhrzeit (gross) | BLE/Akku-Status | ✅ vorhanden |

Noch offen: genaues visuelles Layout (Schriftgrössen, Ampel-Darstellung
als Farbfläche vs. Pfeil), Umschalt-Logik zwischen den Phasen im
Firmware-Code selbst (noch nicht geschrieben, `Segeluhr_Mastuhr.ino`
existiert nicht).

## 2d. Startlinie-Bias war bereits vorhanden — jetzt für die Mastuhr nutzbar (13.08.2026)

Die "Startlinie-Bias — NEU, existiert noch nicht"-Lücke aus Abschnitt 2c
oben war ein Irrtum meinerseits: Das Bias-Konzept (Pin/Boot-Wegpunkte,
Berechnung) existiert seit der Ur-Spezifikation, war nur nie
dokumentiert und nie über BLE an die Uhren übertragen. 13.08.2026
nachgezogen: ein Vorzeichen-Fehler gefunden + behoben (Roman-Hinweis
"Startboot ist immer in Windrichtung rechts" erlaubte eine analytische
Auflösung ohne Wassertest), `RaceStatusPacket` um `lineBiasDdeg`
erweitert. Details: `Erweiterung_Startlinie_Bias.md`.

**Für die Mastuhr heisst das**: der Pre-Start-Screen kann fertige Daten
nutzen, sobald die Firmware geschrieben wird — einfach dieselbe
`CHAR_RACE_STATUS_UUID` mitlesen wie die Ultra. Weiterhin offen:
Vorzeichen noch nicht mit echtem Wind auf dem Wasser verifiziert.

## 4a. Stückliste/Bezugsquelle (13.08.2026 recherchiert)

Vollständige Stückliste mit Digikey-Links (einziger geprüfter Händler mit
allen Kernteilen gleichzeitig — Distrelec/Conrad/reichelt hatten jeweils
Lücken): `hardware/Stueckliste.md`. Geschätzte Gesamtkosten ~CHF 240–260.

**AliExpress als Kostenersparnis geprüft** (~CHF 100–130 günstiger möglich),
aber bewusst nicht pauschal empfohlen: kein echter Einzelhändler (viele
unabhängige Shops), 2–5 Wochen Lieferzeit, und zwei Bauteile mit direktem
Projektrisiko bei Fehlkauf (LoRa-Modul: falsches/unklares Frequenzband
statt exakt 869.525 MHz; Sharp Memory LCD: bekannte Fehldeklarationen/
Fälschungen). Falls Kosten später relevanter werden: Hybrid-Ansatz möglich
— LoRa-Modul + Display weiter bei Digikey (Spezifikation sicher), Rest
(ESP32-Board, GPS, Akku, Solar, Taster, Kleinteile) auf AliExpress.

## 4b. Fertige Alternativen geprüft (13.08.2026) — Entscheidung: Eigenbau

Vor dem Weiterbau kurz geprüft, ob fertige Geräte die Anforderung schon
abdecken:

| Produkt | Preis | Bewertung |
|---|---|---|
| Sailmon MAX | €899 (+€499 Windsensor optional) | Funktional am nächsten (25Hz-GPS, transflektives LCD mit Anti-Reflex-Glas, Countdown/Distanz-zur-Startlinie, LiPo, App) |
| Velocitek ProStart | $795 | GPS/Kompass, Mastbügel als Zubehör, aber kein Wind/kein Land-Display |
| Raymarine Tacktick T060 Micro Compass | ~$400–500 | Solarbetrieben, aber nur Kompass/Windshift, kein GPS-Track |

**Keines davon integriert sich in unser System** (kein BLE zum Handy, kein
LoRa zur Land-Uhr, keine Anbindung an `TrainingEngine`/Boots-Profile/Klio-
Gesten/Quick-Messages) — bei einem fertigen Gerät liefe es isoliert neben
der bestehenden App/Land-Uhr her. Bei Faktor 3–4 höherem Preis als der
DIY-Kalkulation (~CHF 240–260 vs. CHF 750–850) und dem Wunsch nach voller
Integration hat sich Roman **bewusst für die Eigenkonstruktion
entschieden** — Sailmon MAX als Referenz im Hinterkopf behalten (gleiche
Display-Technologie, ähnlicher Funktionsumfang, falls der DIY-Ansatz an
Grenzen stösst).

## 5. Offene Punkte

- **Kopplungsmodell zur Ultra:** eigener BLE-GATT-Server auf der Mastuhr?
  Oder bleibt die Ultra unabhängig und beide gleichen sich nur lose über
  die gemeinsamen LoRa-Pakete ab? Braucht eine bewusste Entscheidung,
  sobald es an die Firmware geht.
- **Welche Screens/Werte** auf einem fix montierten Gerät wirklich
  gebraucht werden (anders als am Handgelenk — z.B. Kompass/Kurs evtl.
  wichtiger als Manöver-Kommandos).
- **Sendeintervall/Stromverbrauch-Budget** fürs LoRa (Duty-Cycle-Grenzen
  wie bei der Ultra beachten, siehe `PROJEKT_STATUS.md`) unter
  Solar-Ladebilanz — noch nicht durchgerechnet.
- **Konkrete Pin-Zuordnung** (SPI für Display + LoRa evtl. gemeinsamer Bus,
  UART für GPS, I2C für RTC) — Entwurf fehlt noch, siehe
  `hardware/Verkabelung.md`.
- **Gehäuse-Konstruktion** (3D-Druck durch Roman): IP-Klasse, Mast-
  Befestigung (Durchmesser?), Kabeldurchführungen für Solarpanel/Antennen,
  Sichtbarkeit/Ablesewinkel vom Cockpit aus.
- **Stückzahl:** aktuell für 1 Gerät kalkuliert (siehe
  `hardware/Stueckliste.md`) — falls mehrere Boote/Mastuhren geplant sind,
  Mengenrabatte prüfen.
