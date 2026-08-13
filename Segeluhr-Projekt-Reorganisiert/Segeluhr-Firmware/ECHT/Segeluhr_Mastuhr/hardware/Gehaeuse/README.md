# Gehäuse — Anforderungen (3D-Druck durch Roman)

Stand: 13.08.2026 Abend — Konzept-Phase, noch kein Entwurf/STL vorhanden.
Board-Standard jetzt Waveshare ESP32-S3-Touch-LCD-3.49 (siehe
`../Stueckliste.md`), BLE-only (kein GPS/LoRa/Solarpanel-Pflicht mehr).
Diese Datei sammelt die Anforderungen, sobald es an den eigentlichen
3D-Druck-Entwurf geht (STL/CAD-Dateien dann in diesem Ordner ablegen).

## Anforderungen (Sammlung, noch nicht vollständig)

- **Wetterfest/wasserdicht**: fest am Mast montiert, dauerhaft Wind/Regen/
  Gischt ausgesetzt — Ziel mindestens IP65, wo möglich IP67 an den
  Durchführungen.
- **Material**: ASA oder PETG (UV-beständiger als PLA für Dauereinsatz im
  Freien), siehe `../Stueckliste.md`.
- **Sichtfenster über dem Display**: transparent, schmaler hoher
  Ausschnitt passend zum 172×640-Streifenformat des Waveshare-Displays
  (kein quadratisches Fenster wie ursprünglich für ein normales Display
  angenommen). Da bewusst IPS statt transflektiv gewählt wurde
  (Einfachheit vor Sonnenlicht-Ablesbarkeit, siehe Konzept-Doku Abschnitt
  2a), lohnt sich trotzdem eine unverspiegelte, möglichst entspiegelte
  Scheibe — hilft der Ablesbarkeit, auch wenn kein transflektiver Effekt
  mehr genutzt wird.
- **Touch-Bedienung durchs Sichtfenster unklar**: das Board hat einen
  kapazitiven Touchscreen — ob der durch eine 3D-gedruckte/geklebte
  Scheibe hindurch zuverlässig funktioniert, ist ungetestet. Für
  Stand-alone-Bedienung eher auf externe wasserdichte Taster setzen
  (siehe unten), Touch nur als Bonus einplanen — genau das Muster, das
  sich bei der T-Watch S3 schon bewährt hat (dort Auto-Focus statt
  Touch-Abhängigkeit, weil Touch durch den wasserdichten Sack
  unzuverlässig ist).
- **Kabeldurchführungen**: nur noch nötig, falls Solar nachgerüstet wird
  (dann 1× wasserdichte Kabelverschraubung PG7 fürs Solarpanel-Kabel) —
  in der BLE-only-Basisvariante ohne Solar entfällt das komplett, da
  weder GPS- noch LoRa-Antenne mehr gebraucht werden.
- **USB-C-Zugang**: für Firmware-Updates und Laden (falls kein Solar) —
  wasserdichte Klappe/Kappe über dem USB-C-Port einplanen.
- **18650-Akku-Wechsel**: Wartungsöffnung/abnehmbarer Deckel, damit die
  Rundzelle ohne komplette Demontage getauscht werden kann (Standardzelle,
  überall nachkaufbar).
- **Mast-Befestigung**: Durchmesser des Ziel-Mastprofils noch nicht
  vermessen/angegeben — bestimmt die Schellen-/Halterungsgeometrie.
- **Zugänglichkeit für Taster**: 3 wasserdichte Taster müssen von aussen
  bedienbar sein (auch mit nasser Hand/Handschuh), ohne dass das Gehäuse
  geöffnet werden muss.
- **Lautsprecher-Öffnung (optional)**: falls der Audio-Codec fürs
  Quick-Message-Ton genutzt wird (siehe `../Verkabelung.md`), braucht es
  eine akustisch durchlässige, aber wasserdichte Öffnung (z.B. feines
  Gitter + Membran) — nur relevant, falls diese Funktion umgesetzt wird.

## Offene Fragen

- Mastdurchmesser/-profil (rund? oval? Nut vorhanden?)
- Gewünschte ungefähre Gehäusegrösse (abhängig vom Waveshare-
  Platinenmass, deutlich schmaler/länger als beim ursprünglich
  angenommenen quadratischen Display)
- Ob Solar überhaupt kommt (siehe `../Stueckliste.md`, offener Punkt
  Stromverbrauch) — bestimmt, ob Kabeldurchführungen gebraucht werden
- Ob die Lautsprecher-Funktion umgesetzt wird (bestimmt, ob eine
  akustische Öffnung nötig ist)
