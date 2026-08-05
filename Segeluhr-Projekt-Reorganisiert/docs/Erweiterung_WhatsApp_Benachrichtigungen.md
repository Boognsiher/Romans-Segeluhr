# Erweiterung: WhatsApp-Benachrichtigungen (konfigurierbar)

> Nicht in der ursprünglichen Segeluhr_Spezifikation.md — Doku gemäß Konvention.

## Status: KONZEPT (noch nicht implementiert — braucht eine eigene
Android-Session, betrifft `SegeluhrViewModel`, Settings-UI, DataStore.
Kein Teil der heutigen Watch-Firmware-Vorbereitung.)

## 1. Zweck
Bei bestimmten Ereignissen (z.B. "Heimweg aktiviert") soll die App eine
vorausgefüllte WhatsApp-Nachricht anbieten, die der Nutzer an einen frei
gewählten Kontakt/Gruppe schicken kann — ohne selbst tippen zu müssen,
aber mit manueller Bestätigung (kein stiller Auto-Versand, siehe Abschnitt 4).

## 2. Auslöse-Ereignisse (konfigurierbar, je einzeln an/aus schaltbar)
- Countdown gestartet
- Training gestartet
- Wettfahrt (Competition) gestartet
- Wettfahrt beendet / Ziel erreicht
- Heimweg aktiviert
- Boot sicher an Land

Jedes Ereignis hat eine eigene, editierbare Text-Vorlage mit Platzhaltern,
z.B. für "Heimweg aktiviert":
```
Bin auf dem Heimweg, ETA ca. {ETA} Uhr, noch {DISTANZ} zu fahren.
```
Platzhalter-Kandidaten: `{ZEIT}`, `{ETA}`, `{DISTANZ}`, `{WINDRICHTUNG}` —
genaue Liste hängt davon ab, welche Daten die jeweilige Engine zum
Zeitpunkt des Ereignisses zur Verfügung hat.

## 3. Empfänger-Auswahl
Bewusst **kein** fest hinterlegter Kontakt in den Settings — der Empfänger
wird bei jedem Ereignis live gewählt (Crew wechselt ggf. von Tag zu Tag).
Technisch: WhatsApps eigene Kontakt-/Gruppenauswahl übernimmt das, siehe
Abschnitt 5 — keine eigene Kontakt-Picker-UI in der App nötig.

## 4. Warum eine Notification statt direktem App-Start
Android erlaubt es einer App/einem Service im Hintergrund nicht, einfach
eine andere App (WhatsApp) zu starten — das würde von neueren Android-
Versionen blockiert. Der saubere Weg: beim Eintreten eines aktivierten
Ereignisses eine Notification mit Aktions-Button anzeigen
("Heimweg aktiviert — Nachricht senden?"). Erst der Tap auf den Button
(klare Nutzerinteraktion) darf den Intent auslösen.

Das passt auch inhaltlich: kein stiller Auto-Versand, der Nutzer entscheidet
jedes Mal bewusst, ob und an wen die Nachricht rausgeht.

## 5. Technische Umsetzung des WhatsApp-Versands
```kotlin
val sendIntent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, renderedMessage)
    setPackage("com.whatsapp")
}
try {
    context.startActivity(sendIntent)
} catch (e: ActivityNotFoundException) {
    // Fallback: kein setPackage, allgemeine Teilen-Auswahl anzeigen
    val fallback = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, renderedMessage)
    }
    context.startActivity(Intent.createChooser(fallback, null))
}
```
`setPackage("com.whatsapp")` öffnet WhatsApp direkt in dessen eigener
Kontakt-/Gruppenauswahl mit vorausgefülltem Text — kein Umweg über einen
allgemeinen Teilen-Dialog nötig, wenn WhatsApp installiert ist.

## 6. Grober Architektur-Vorschlag
- Neue Settings-Sektion (Setup-Tab): Liste der Ereignisse mit
  Toggle + "Vorlage bearbeiten"-Button je Ereignis
- Persistenz: DataStore, analog zu bestehenden Settings/Wegpunkten
- `SegeluhrViewModel` beobachtet Zustandsübergänge der Engines
  (TrainingEngine/CompetitionEngine/HomeEngine) und prüft bei relevanten
  Übergängen, ob eine Benachrichtigung aktiviert ist
- Neuer Baustein z.B. `notifications/WhatsAppNotifier.kt`: baut die
  Notification, rendert die Vorlage mit den aktuellen Platzhalter-Werten,
  löst bei Tap den Intent aus (Code siehe Abschnitt 5)

## 7. Offene Punkte für die Android-Session
- [ ] Genaue Platzhalter-Liste pro Ereignis festlegen (abhängig davon,
  welche Werte die jeweilige Engine zum Auslöse-Zeitpunkt bereits kennt)
- [ ] UI-Entwurf für die Settings-Sektion (wie viele Ereignisse gleichzeitig
  sichtbar, wo in der bestehenden Setup-Tab-Struktur einsortiert)
- [ ] Notification-Channel-Konfiguration (Priorität, ob mit Sound/Vibration)
- [ ] Verhalten, falls WhatsApp Business (`com.whatsapp.w4b`) statt der
  privaten WhatsApp-App installiert ist — ggf. beide Package-Namen prüfen
- [ ] Bestehende ViewModel-/Service-Dateien müssen geteilt werden, bevor
  konkreter Code entstehen kann (wie beim Battery/Auto-Stop-Feature)
